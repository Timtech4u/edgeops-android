package com.example.ai

import android.util.Log
import com.example.data.CloudInvestigationResult
import com.example.data.CloudRunInvestigatorClient
import com.example.data.Connector
import com.example.data.Incident
import com.example.data.IncidentSeverity
import com.example.data.InferenceSourceType
import com.example.data.LocalTriageResult
import com.google.firebase.Firebase
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "HybridAiService"

/**
 * EdgeOps Hybrid AI Engine backed by Firebase AI Logic SDK:
 * 1. Local Triage uses Hybrid Inference (`InferenceMode.PREFER_ON_DEVICE`) with Gemini Nano (AICore)
 *    and automatic Cloud Gemini fallback (`gemini-3.5-flash-lite`) + offline-ready local resilience.
 * 2. Cloud Investigation escalates ONLY the locally scrubbed payload to Cloud Run `/api/investigate`,
 *    falling back cleanly to Firebase AI Logic `ONLY_IN_CLOUD` (`gemini-3.8-flash`).
 */
@OptIn(PublicPreviewAPI::class)
class HybridAiService(
    private val cloudRunClient: CloudRunInvestigatorClient = CloudRunInvestigatorClient()
) {

    // Hybrid model configured with PREFER_ON_DEVICE (Gemini Nano via AICore -> Cloud fallback)
    private val hybridTriageModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 256
            },
            onDeviceConfig = OnDeviceConfig(
                mode = InferenceMode.PREFER_ON_DEVICE,
                maxOutputTokens = 256
            )
        )
    }

    // Cloud Investigation fallback schema operating strictly on locally redacted text
    private val cloudInvestigationSchema = Schema.obj(
        properties = mapOf(
            "whatHappened" to Schema.string(
                description = "Plain-English 2-3 sentence diagnosis explaining what happened without technical jargon."
            ),
            "recommendedActions" to Schema.array(
                items = Schema.string(),
                description = "2 to 3 concise, practical next steps."
            ),
            "suggestedReply" to Schema.string(
                description = "Friendly, concise reply draft the operator can copy into Slack."
            )
        )
    )

    private val cloudInvestigationModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-4-hydrogen",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = cloudInvestigationSchema
                temperature = 0.25f
            },
            onDeviceConfig = OnDeviceConfig(
                mode = InferenceMode.ONLY_IN_CLOUD
            )
        )
    }

    private val streamingChatModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                temperature = 0.3f
                maxOutputTokens = 384
            },
            onDeviceConfig = OnDeviceConfig(
                mode = InferenceMode.PREFER_ON_DEVICE,
                maxOutputTokens = 384
            )
        )
    }

    /**
     * Ultra-fast streaming triage chat:
     * Streams via Firebase AI Logic Hybrid Inference (`PREFER_ON_DEVICE` -> `gemini-3.5-flash-lite`)
     * on ONLY the locally scrubbed payload so answers start appearing in milliseconds and work offline.
     */
    suspend fun streamTriageChat(
        title: String,
        channel: String,
        scrubbedMessage: String,
        occurrenceCount: Int,
        userPrompt: String,
        historyText: String,
        onChunk: (accumulatedText: String, sourceLabel: String) -> Unit
    ): Pair<String, String> {
        val groupContext = if (occurrenceCount > 1) {
            "This alert occurred $occurrenceCount times recently in $channel."
        } else {
            "Channel: $channel."
        }
        val prompt = buildString {
            appendLine("You are EdgeOps, a fast, calm operations copilot. Be concise (2-4 sentences or short bullets), plain English, zero jargon.")
            appendLine("Alert: $title")
            appendLine(groupContext)
            appendLine("Scrubbed details: ${scrubbedMessage.take(1200)}")
            if (historyText.isNotBlank()) {
                appendLine("Recent conversation:")
                appendLine(historyText.take(600))
            }
            appendLine("Operator question: $userPrompt")
        }

        val accumulated = StringBuilder()
        var sourceLabel = "Cloud · gemini-3.5-flash-lite"
        try {
            streamingChatModel.generateContentStream(prompt).collect { chunk ->
                val text = chunk.text.orEmpty()
                if (text.isNotEmpty()) {
                    if (chunk.inferenceSource == InferenceSource.ON_DEVICE) {
                        sourceLabel = "On-Device · Gemini Nano"
                    }
                    accumulated.append(text)
                    onChunk(accumulated.toString(), sourceLabel)
                }
            }
            val finalOutput = accumulated.toString().trim()
            if (finalOutput.isNotBlank()) {
                return finalOutput to sourceLabel
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Streaming chat fallback: ${e.message}")
        }

        val fallback = if (userPrompt.contains("reply", ignoreCase = true)) {
            "Hi team, we've flagged this alert in $channel and are checking the details now. We'll follow up with an update shortly."
        } else {
            "This alert in $channel ($title) looks actionable${if (occurrenceCount > 1) " and has repeated $occurrenceCount times" else ""}. Review the latest record and confirm whether manual approval or retry is needed."
        }
        onChunk(fallback, "On-Device · Offline Ready")
        return fallback to "On-Device · Offline Ready"
    }

    /**
     * Global Workspace-Aware Proactive Copilot Chat:
     * Knows ALL locally-scrubbed synced alerts across channels and streams sub-second answers
     * via Firebase AI Logic Hybrid Inference (PREFER_ON_DEVICE -> gemini-3.5-flash-lite).
     */
    suspend fun streamWorkspaceCopilotChat(
        workspaceName: String,
        scrubbedDigestContext: String,
        userPrompt: String,
        historyText: String,
        onChunk: (accumulatedText: String, sourceLabel: String) -> Unit
    ): Pair<String, String> {
        val prompt = buildString {
            appendLine("You are EdgeOps Copilot for workspace '$workspaceName'. You know every active operations alert across all connected Slack channels.")
            appendLine("Be concise, calm, plain English, and actionable (short bullets or 2-4 crisp sentences, zero raw developer jargon).")
            appendLine("Active locally-scrubbed alerts (highest priority first):")
            appendLine(scrubbedDigestContext.take(2800))
            if (historyText.isNotBlank()) {
                appendLine("Recent conversation:")
                appendLine(historyText.take(600))
            }
            appendLine("Operator question: $userPrompt")
        }

        val accumulated = StringBuilder()
        var sourceLabel = "Cloud · gemini-3.5-flash-lite"
        try {
            streamingChatModel.generateContentStream(prompt).collect { chunk ->
                val text = chunk.text.orEmpty()
                if (text.isNotEmpty()) {
                    if (chunk.inferenceSource == InferenceSource.ON_DEVICE) {
                        sourceLabel = "On-Device · Gemini Nano"
                    }
                    accumulated.append(text)
                    onChunk(accumulated.toString(), sourceLabel)
                }
            }
            val finalOutput = accumulated.toString().trim()
            if (finalOutput.isNotBlank()) {
                return finalOutput to sourceLabel
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Workspace Copilot stream fallback: ${e.message}")
        }

        val fallback = "Here's your quick $workspaceName pulse from locally cached alerts: focus on the top Urgent items in Needs Action first, especially overdue compliance verifications and pending AdPay budget reconciliations."
        onChunk(fallback, "On-Device · Offline Ready")
        return fallback to "On-Device · Offline Ready"
    }

    /**
     * Runs immediately on synced or opened Slack messages using PREFER_ON_DEVICE (Gemini Nano).
     * Receives ONLY the locally cleaned & scrubbed message so PII never leaves the device.
     */
    suspend fun runLocalTriage(
        title: String,
        channel: String,
        scrubbedMessage: String,
        isOnline: Boolean
    ): LocalTriageResult {
        val localHeuristic = buildOfflineTriageFallback(title, channel, scrubbedMessage)
        val prompt = """
            You are EdgeOps on-device triage running on Gemini Nano.
            Classify whether this Slack message is routine NOISE or an ACTIONABLE issue, summarize it plainly, and draft a brief helpful reply.
            Channel: $channel
            Title: $title
            Scrubbed Message: $scrubbedMessage

            Respond in exactly 4 lines:
            VERDICT: NOISE or ACTIONABLE
            SEVERITY: HIGH, MEDIUM, LOW, or NOISE
            SUMMARY: <one crisp plain-English sentence>
            REPLY: <one friendly 1-2 sentence reply draft>
        """.trimIndent()

        return try {
            val response = withTimeout(25_000L) {
                hybridTriageModel.generateContent(prompt)
            }
            val text = response.text?.trim().orEmpty()
            val executedOnDevice = response.inferenceSource == InferenceSource.ON_DEVICE
            val sourceType = if (executedOnDevice) {
                InferenceSourceType.ON_DEVICE_NANO
            } else {
                InferenceSourceType.CLOUD_FALLBACK
            }
            val detail = if (executedOnDevice) {
                "Executed locally on Gemini Nano (AICore)"
            } else {
                "Hybrid PREFER_ON_DEVICE (scrubbed payload)"
            }

            if (text.isBlank()) {
                localHeuristic.copy(inferenceSource = sourceType, runtimeDetail = detail)
            } else {
                parseTriageLines(text, localHeuristic, sourceType, detail)
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Log.w(TAG, "Hybrid triage timed out; using local offline shield result", e)
                localHeuristic.copy(
                    inferenceSource = InferenceSourceType.LOCAL_OFFLINE_GUARD,
                    runtimeDetail = "On-Device · Private"
                )
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hybrid triage fell back to local mode (online=$isOnline): ${e.message}")
            localHeuristic.copy(
                inferenceSource = InferenceSourceType.LOCAL_OFFLINE_GUARD,
                runtimeDetail = "On-Device · Private"
            )
        }
    }

    /**
     * Sends ONLY the locally scrubbed payload to live Cloud Run `/api/investigate`
     * with graceful Firebase AI Logic (`gemini-3.8-flash`) fallback.
     */
    suspend fun runCloudInvestigation(
        incident: Incident,
        customConnectors: List<Connector>
    ): Result<CloudInvestigationResult> {
        val shield = LocalPrivacyShield.redactLocally(incident.rawMessage)
        val safePayload = shield.scrubbedText

        // 1. Primary path: Live Cloud Run Backend API (/api/investigate)
        val cloudRunAttempt = cloudRunClient.investigate(
            incident = incident,
            scrubbedMessage = safePayload,
            redactionCount = shield.redactions.size,
            customConnectors = customConnectors
        )
        if (cloudRunAttempt.isSuccess) {
            return cloudRunAttempt
        }

        Log.w(
            TAG,
            "Cloud Run /api/investigate fallback triggered (${cloudRunAttempt.exceptionOrNull()?.message}); using Firebase AI Logic"
        )

        // 2. Fallback: Firebase AI Logic (`gemini-3.8-flash`) on scrubbed text only
        val prompt = """
            You are EdgeOps Cloud Investigator. Analyze this ALREADY-REDACTED Slack message (contains zero PII).
            Channel: ${incident.channel}
            Title: ${incident.title}
            Scrubbed Message: $safePayload

            Return clean JSON matching the schema with:
            - Plain-English "whatHappened" diagnosis (2-3 clear sentences)
            - 2-3 practical "recommendedActions"
            - A concise, polite "suggestedReply" the operator can copy and paste into Slack
        """.trimIndent()

        return try {
            val response = withTimeout(45_000L) {
                cloudInvestigationModel.generateContent(prompt)
            }
            val jsonText = response.text
                ?: throw IllegalStateException("Cloud Investigation returned an empty response.")
            val json = JSONObject(jsonText)
            val diagnosis = json.optString("whatHappened")
                .takeIf { it.isNotBlank() }
                ?: "Analysis completed for ${incident.title}."
            val actionsArray = json.optJSONArray("recommendedActions")
            val actions = buildList {
                if (actionsArray != null) {
                    for (i in 0 until actionsArray.length()) {
                        val item = actionsArray.optString(i).trim()
                        if (item.isNotBlank()) add(item)
                    }
                }
            }
            val suggestedReply = json.optString("suggestedReply").takeIf { it.isNotBlank() }
                ?: incident.suggestedReply.ifBlank {
                    "Thanks for reporting this in ${incident.channel}. We reviewed the issue and are following up shortly."
                }

            Result.success(
                CloudInvestigationResult(
                    diagnosis = diagnosis,
                    toolsExecuted = emptyList(),
                    totalToolsAvailable = 0,
                    recommendedActions = actions,
                    suggestedReply = suggestedReply,
                    backendSourceLabel = "Firebase AI Logic · Cloud Gemini"
                )
            )
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Result.failure(IllegalStateException("Investigation timed out. Please try again."))
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase AI Logic fallback failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseTriageLines(
        rawOutput: String,
        fallback: LocalTriageResult,
        sourceType: InferenceSourceType,
        detail: String
    ): LocalTriageResult {
        val lines = rawOutput.lines().map { it.trim() }
        val verdictLine = lines.firstOrNull { it.startsWith("VERDICT:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.uppercase()
        val severityLine = lines.firstOrNull { it.startsWith("SEVERITY:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.uppercase()
        val summaryLine = lines.firstOrNull { it.startsWith("SUMMARY:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        val replyLine = lines.firstOrNull { it.startsWith("REPLY:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()

        val isNoise = when {
            verdictLine?.contains("NOISE") == true -> true
            verdictLine?.contains("ACTION") == true -> false
            else -> fallback.noiseSuppressed
        }
        val severity = when {
            isNoise -> IncidentSeverity.NOISE
            severityLine != null -> IncidentSeverity.fromRaw(severityLine)
            else -> fallback.severity
        }

        return LocalTriageResult(
            noiseSuppressed = isNoise,
            severity = severity,
            triageSummary = summaryLine?.takeIf { it.isNotBlank() } ?: fallback.triageSummary,
            suggestedReply = replyLine?.takeIf { it.isNotBlank() } ?: fallback.suggestedReply,
            inferenceSource = sourceType,
            runtimeDetail = detail
        )
    }

    private fun buildOfflineTriageFallback(
        title: String,
        channel: String,
        scrubbedMessage: String
    ): LocalTriageResult {
        val combined = "$title $scrubbedMessage $channel".lowercase()
        val noiseSignals = listOf(
            "joined the channel", "has joined", "left the channel",
            "cronbot", "heartbeat", "uptimebot", "no action needed",
            "0 warnings", "routine", "newsletter", "digest", "ops-noise", "marketing-bot"
        )
        val isNoise = noiseSignals.any { combined.contains(it) }
        val isHigh = !isNoise && listOf("urgent", "p1", "outage", "down", "charged twice", "failed", "error", "broken", "401", "500").any {
            combined.contains(it)
        }
        val severity = when {
            isNoise -> IncidentSeverity.NOISE
            isHigh -> IncidentSeverity.HIGH
            else -> IncidentSeverity.MEDIUM
        }
        val summary = if (isNoise) {
            "Routine status update suppressed locally so your inbox stays calm."
        } else {
            scrubbedMessage.lineSequence().firstOrNull { it.isNotBlank() }?.take(180)
                ?: "Actionable message triaged locally with personal details scrubbed."
        }
        val reply = if (isNoise) {
            "Routine update — no reply required."
        } else {
            "Thanks for sharing this in $channel. We're looking into it now and will share an update shortly."
        }
        return LocalTriageResult(
            noiseSuppressed = isNoise,
            severity = severity,
            triageSummary = summary,
            suggestedReply = reply,
            inferenceSource = InferenceSourceType.ON_DEVICE_NANO,
            runtimeDetail = "On-Device · Private"
        )
    }
}
