package com.example.data

import android.util.Log
import com.example.ai.LocalPrivacyShield
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG = "CloudRunClient"

/**
 * Live Cloud Run API client for EdgeOps Web backend:
 * https://calmduty.web.app
 *
 * Strictly read-only for Slack:
 * - GET  /api/slack/status
 * - GET  /api/slack/channels?orgId={orgId}
 * - POST /api/slack/sync  { "orgId": orgId }
 * - POST /api/investigate { "incidentTitle", "scrubbedSummary", ... } (locally scrubbed PII-free payload ONLY)
 */
class CloudRunInvestigatorClient(
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://calmduty.web.app"
        const val SLACK_STATUS_PATH = "/api/slack/status"
        const val SLACK_CHANNELS_PATH = "/api/slack/channels"
        const val SLACK_SYNC_PATH = "/api/slack/sync"
        const val INVESTIGATE_PATH = "/api/investigate"
        const val BRIEFING_PATH = "/api/briefing"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private suspend fun optionalAuthHeader(): String? {
        return try {
            Firebase.auth.currentUser?.getIdToken(false)?.await()?.token?.let { "Bearer $it" }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * GET /api/slack/status -> detects active connected Slack workspace (orgId, team, botName, connected).
     */
    suspend fun fetchSlackStatus(orgId: String? = null): Result<SlackWorkspaceStatus> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "$baseUrl$SLACK_STATUS_PATH".toHttpUrl().newBuilder()
            if (!orgId.isNullOrBlank()) {
                urlBuilder.addQueryParameter("orgId", orgId)
            }
            val requestBuilder = Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .get()
            optionalAuthHeader()?.let { requestBuilder.header("Authorization", it) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "GET /api/slack/status returned HTTP ${response.code}: ${rawBody.take(200)}")
                    return@withContext Result.failure(IllegalStateException("Slack status returned HTTP ${response.code}"))
                }
                if (rawBody.isBlank()) {
                    return@withContext Result.success(SlackWorkspaceStatus(connected = false))
                }
                val root = JSONObject(rawBody)
                val obj = root.optJSONObject("workspace")
                    ?: root.optJSONObject("slack")
                    ?: root.optJSONObject("data")
                    ?: root

                val resolvedOrgId = listOf("orgId", "org_id", "workspaceId", "teamId", "team_id")
                    .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                    ?: root.optString("orgId").takeIf { it.isNotBlank() && it != "null" }
                    ?: ""
                val resolvedTeam = listOf("team", "teamName", "team_name", "workspaceName", "orgName", "name")
                    .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                    ?: root.optString("team").takeIf { it.isNotBlank() && it != "null" }
                    ?: ""
                val resolvedBot = listOf("botName", "bot_name", "botUser", "bot", "user")
                    .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                    ?: root.optString("botName").takeIf { it.isNotBlank() && it != "null" }
                    ?: ""
                val isConnected = when {
                    obj.has("connected") -> obj.optBoolean("connected", false)
                    root.has("connected") -> root.optBoolean("connected", false)
                    obj.has("ok") -> obj.optBoolean("ok", false) && (resolvedTeam.isNotBlank() || resolvedOrgId.isNotBlank())
                    else -> resolvedTeam.isNotBlank() || resolvedOrgId.isNotBlank()
                }

                Result.success(
                    SlackWorkspaceStatus(
                        connected = isConnected,
                        orgId = resolvedOrgId,
                        teamName = resolvedTeam,
                        botName = resolvedBot
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GET /api/slack/status failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * GET /api/slack/channels?orgId={orgId} -> lists real channels the Slack bot belongs to.
     */
    suspend fun fetchSlackChannels(orgId: String): Result<List<SlackChannelInfo>> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "$baseUrl$SLACK_CHANNELS_PATH".toHttpUrl().newBuilder()
            if (orgId.isNotBlank()) {
                urlBuilder.addQueryParameter("orgId", orgId)
            }
            val requestBuilder = Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .get()
            optionalAuthHeader()?.let { requestBuilder.header("Authorization", it) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "GET /api/slack/channels returned HTTP ${response.code}: ${rawBody.take(200)}")
                    return@withContext Result.failure(IllegalStateException("Slack channels returned HTTP ${response.code}"))
                }
                if (rawBody.isBlank()) {
                    return@withContext Result.success(emptyList())
                }
                val channelsArray = extractArray(rawBody, listOf("channels", "items", "data"))
                val channels = buildList {
                    if (channelsArray != null) {
                        for (i in 0 until channelsArray.length()) {
                            val obj = channelsArray.optJSONObject(i)
                            if (obj != null) {
                                val id = obj.optString("id").ifBlank { "ch_$i" }
                                val rawName = obj.optString("name")
                                    .ifBlank { obj.optString("channelName") }
                                    .ifBlank { id }
                                val formattedName = if (rawName.startsWith("#")) rawName else "#$rawName"
                                val members = obj.optInt("num_members", obj.optInt("memberCount", 0))
                                val isPrivate = obj.optBoolean("is_private", obj.optBoolean("isPrivate", false))
                                add(
                                    SlackChannelInfo(
                                        id = id,
                                        name = formattedName,
                                        memberCount = members,
                                        isPrivate = isPrivate
                                    )
                                )
                            } else {
                                val str = channelsArray.optString(i).trim()
                                if (str.isNotBlank()) {
                                    add(
                                        SlackChannelInfo(
                                            id = str,
                                            name = if (str.startsWith("#")) str else "#$str"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                Result.success(channels)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GET /api/slack/channels failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * POST /api/slack/sync with { "orgId": orgId } -> pulls real Slack messages (strictly read-only).
     */
    suspend fun syncSlackMessages(orgId: String): Result<List<SyncedSlackMessage>> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                if (orgId.isNotBlank()) {
                    put("orgId", orgId)
                }
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = payload.toString().toRequestBody(mediaType)
            val requestBuilder = Request.Builder()
                .url("$baseUrl$SLACK_SYNC_PATH")
                .header("Accept", "application/json")
                .post(body)
            optionalAuthHeader()?.let { requestBuilder.header("Authorization", it) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "POST /api/slack/sync returned HTTP ${response.code}: ${rawBody.take(200)}")
                    return@withContext Result.failure(IllegalStateException("Slack sync returned HTTP ${response.code}"))
                }
                if (rawBody.isBlank()) {
                    return@withContext Result.success(emptyList())
                }

                val messagesArray = extractArray(
                    rawBody,
                    listOf("incidents", "messages", "synced", "alerts", "items", "events", "data")
                )
                val messages = buildList {
                    if (messagesArray != null) {
                        for (i in 0 until messagesArray.length()) {
                            val obj = messagesArray.optJSONObject(i) ?: continue
                            val rawText = listOf("rawText", "raw_text", "rawMessage", "raw_message", "text", "message", "body", "content", "summary", "scrubbedMessage")
                                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                                ?: continue

                            val rawChannel = listOf("channelName", "channel_name", "channel", "channelId")
                                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                                ?: "slack"
                            val normalizedChannel = if (rawChannel.startsWith("#")) rawChannel else "#$rawChannel"

                            val explicitTitle = listOf("title", "incidentTitle", "subject")
                                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                            val cleanTitle = LocalPrivacyShield.deriveCleanTitle(
                                explicitTitle = explicitTitle,
                                rawMessage = rawText,
                                channel = normalizedChannel
                            )

                            val rawId = listOf("id", "incidentId", "slackTs", "messageTs", "ts", "eventId", "client_msg_id")
                                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
                                ?: "${normalizedChannel}_${abs(rawText.hashCode())}"

                            val author = listOf("userName", "user_name", "author", "sender", "user")
                                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }

                            val existingDiagnosis = listOf("diagnosis", "whatHappened")
                                .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }

                            val tsMillis = parseSlackTimestampMillis(obj)
                            val isBot = obj.optBoolean("isBot", false)
                            val replyCount = obj.optInt("replyCount", 0).coerceAtLeast(0)
                            val dsObj = obj.optJSONObject("decisionScores")
                            val decisionScores = if (dsObj != null) {
                                val needsAction = dsObj.optDouble("needsAction", 0.0).coerceIn(0.0, 1.0)
                                val customerBlocked = dsObj.optDouble("customerBlocked", 0.0).coerceIn(0.0, 1.0)
                                val isNoise = dsObj.optDouble("isNoise", 0.0).coerceIn(0.0, 1.0)
                                val revenueRisk = dsObj.optDouble("revenueRisk", 0.0).coerceIn(0.0, 1.0)
                                val manualMoneyMove = dsObj.optDouble("manualMoneyMove", 0.0).coerceIn(0.0, 1.0)
                                val needsEngineering = dsObj.optDouble("needsEngineering", 0.0).coerceIn(0.0, 1.0)
                                val rawRoute = dsObj.optString("route", "").trim()
                                val latencyMs = if (dsObj.has("latencyMs") && !dsObj.isNull("latencyMs")) {
                                    dsObj.optInt("latencyMs", 0).takeIf { it > 0 }
                                } else {
                                    null
                                }
                                val computedMax = maxOf(needsAction, customerBlocked, manualMoneyMove, revenueRisk)
                                val priorityScore = obj.optInt(
                                    "priorityScore",
                                    (computedMax * 100).toInt()
                                ).coerceIn(0, 100)
                                val tentative = DecisionScores(
                                    needsAction = needsAction,
                                    customerBlocked = customerBlocked,
                                    isNoise = isNoise,
                                    revenueRisk = revenueRisk,
                                    manualMoneyMove = manualMoneyMove,
                                    needsEngineering = needsEngineering,
                                    route = rawRoute,
                                    latencyMs = latencyMs,
                                    priorityScore = priorityScore,
                                    bucket = "needs_action",
                                    model = dsObj.optString("model").ifBlank { "gemini-decision-1" },
                                    outputTokens = dsObj.optInt("outputTokens", 0)
                                )
                                val resolvedRoute = tentative.effectiveRoute()
                                val bucket = obj.optString("bucket").ifBlank {
                                    if (resolvedRoute == "page_eng" || resolvedRoute == "notify_ops") {
                                        "needs_action"
                                    } else {
                                        "hidden_noise"
                                    }
                                }
                                tentative.copy(route = resolvedRoute, bucket = bucket)
                            } else {
                                null
                            }

                            add(
                                SyncedSlackMessage(
                                    externalId = rawId,
                                    channel = normalizedChannel,
                                    title = cleanTitle,
                                    rawMessage = rawText,
                                    author = author,
                                    timestampMillis = tsMillis,
                                    isBot = isBot,
                                    replyCount = replyCount,
                                    existingDiagnosis = existingDiagnosis,
                                    decisionScores = decisionScores
                                )
                            )
                        }
                    }
                }
                Result.success(messages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "POST /api/slack/sync failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * POST /api/briefing -> generates a calm Decisions API executive window briefing from strictly scrubbed thread summaries.
     * NEVER posts to Slack.
     */
    suspend fun fetchExecutiveBriefing(
        workspaceName: String,
        timeZone: String,
        threads: JSONArray
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("workspaceName", workspaceName)
                put("timeZone", timeZone)
                put("threads", threads)
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = payload.toString().toRequestBody(mediaType)
            val requestBuilder = Request.Builder()
                .url("$baseUrl$BRIEFING_PATH")
                .header("Accept", "application/json")
                .post(body)
            optionalAuthHeader()?.let { requestBuilder.header("Authorization", it) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Briefing endpoint returned HTTP ${response.code}")
                    )
                }
                val root = JSONObject(rawBody)
                val briefing = root.optString("briefing").trim()
                val modelUsed = root.optString("modelUsed").ifBlank { "gemini-3.5-flash-lite" }
                if (briefing.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Empty briefing response"))
                }
                Result.success(briefing to modelUsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "POST /api/briefing failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * POST /api/investigate -> sends ONLY the locally scrubbed payload (incidentTitle + scrubbedSummary).
     */
    suspend fun investigate(
        incident: Incident,
        scrubbedMessage: String,
        redactionCount: Int,
        customConnectors: List<Connector>
    ): Result<CloudInvestigationResult> = withContext(Dispatchers.IO) {
        try {
            val enabledConnectors = customConnectors.filter { it.enabled }
            val enabledToolNames = enabledConnectors
                .flatMap { it.toolNames }
                .distinct()

            // Build full DeferredTool objects so server.ts runs real gemini-decision-1 Dynamic Tool Retrieval
            val deferredToolsArray = JSONArray()
            val seenTools = mutableSetOf<String>()
            for (connector in enabledConnectors) {
                val sourceType = if (connector.connectorType.equals("OPENAPI", ignoreCase = true)) {
                    "openapi"
                } else {
                    "mcp"
                }
                val category = if (sourceType == "openapi") "OpenAPI" else "MCP"
                for (toolName in connector.toolNames) {
                    val trimmed = toolName.trim()
                    if (trimmed.isEmpty() || !seenTools.add(trimmed)) continue
                    val readable = trimmed.replace('_', ' ').replace('-', ' ')
                    deferredToolsArray.put(
                        JSONObject().apply {
                            put("name", trimmed)
                            put("description", "$readable via ${connector.name} (${connector.endpointUrl})")
                            put("category", category)
                            put("roleTag", "Viewer")
                            put("defer_loading", true)
                            put("sourceType", sourceType)
                            put("sourceName", connector.name)
                            put("endpointHint", connector.endpointUrl)
                        }
                    )
                }
            }

            val requestJson = JSONObject().apply {
                put("incidentId", incident.id)
                put("incidentTitle", incident.title)
                put("title", incident.title)
                put("channel", incident.channel)
                put("scrubbedSummary", scrubbedMessage)
                put("scrubbedMessage", scrubbedMessage)
                put("message", scrubbedMessage)
                put("redactionCount", redactionCount)
                if (deferredToolsArray.length() > 0) {
                    put("tools", deferredToolsArray)
                }
                if (enabledToolNames.isNotEmpty()) {
                    put("availableTools", JSONArray(enabledToolNames))
                    put("totalToolsAvailable", enabledToolNames.size)
                }
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = requestJson.toString().toRequestBody(mediaType)
            val requestBuilder = Request.Builder()
                .url("$baseUrl$INVESTIGATE_PATH")
                .header("Accept", "application/json")
                .post(body)
            optionalAuthHeader()?.let { requestBuilder.header("Authorization", it) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "POST /api/investigate returned HTTP ${response.code}: ${responseBody.take(200)}")
                    return@withContext Result.failure(
                        IllegalStateException("Cloud Run backend returned HTTP ${response.code}")
                    )
                }
                if (responseBody.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Cloud Run backend returned an empty response.")
                    )
                }

                Result.success(parseInvestigateResponse(responseBody, incident))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "POST /api/investigate unreachable; falling back to Firebase AI Logic: ${e.message}")
            Result.failure(e)
        }
    }

    private fun parseInvestigateResponse(
        rawBody: String,
        incident: Incident
    ): CloudInvestigationResult {
        val root = JSONObject(rawBody)
        val dataObj = root.optJSONObject("result")
            ?: root.optJSONObject("investigation")
            ?: root.optJSONObject("data")
            ?: root

        val diagnosis = listOf("whatHappened", "what_happened", "diagnosis", "summary", "analysis", "explanation")
            .firstNotNullOfOrNull { key -> dataObj.optString(key).takeIf { it.isNotBlank() && it != "null" } }
            ?: "Investigation completed for ${incident.title} on the locally scrubbed message."

        val suggestedReply = listOf("suggestedSlackReply", "suggested_slack_reply", "suggestedReply", "suggested_reply", "slackReply", "reply", "draftReply")
            .firstNotNullOfOrNull { key -> dataObj.optString(key).takeIf { it.isNotBlank() && it != "null" } }
            ?: incident.suggestedReply.ifBlank {
                "Thanks for flagging this in ${incident.channel}. We reviewed the details and are following up with the next steps."
            }

        val actionsArray = dataObj.optJSONArray("recommendedActions")
            ?: dataObj.optJSONArray("recommended_actions")
            ?: dataObj.optJSONArray("actions")
            ?: dataObj.optJSONArray("nextSteps")
        val recommendedActions = buildList {
            if (actionsArray != null) {
                for (i in 0 until actionsArray.length()) {
                    val item = actionsArray.optString(i).trim()
                    if (item.isNotBlank()) add(item)
                }
            }
        }

        val toolsArray = dataObj.optJSONArray("toolsUsed")
            ?: dataObj.optJSONArray("tools_used")
            ?: dataObj.optJSONArray("toolsExecuted")
            ?: dataObj.optJSONArray("tools")

        val parsedTools = buildList {
            if (toolsArray != null) {
                for (i in 0 until toolsArray.length()) {
                    val obj = toolsArray.optJSONObject(i)
                    if (obj != null) {
                        val id = obj.optString("toolId")
                            .ifBlank { obj.optString("id") }
                            .ifBlank { obj.optString("name") }
                            .ifBlank { "tool_${i + 1}" }
                        val name = obj.optString("toolName")
                            .ifBlank { obj.optString("name") }
                            .ifBlank { id.replace('_', ' ').replaceFirstChar { it.uppercase() } }
                        val output = obj.optString("summaryOutput")
                            .ifBlank { obj.optString("output") }
                            .ifBlank { obj.optString("result") }
                            .ifBlank { "Executed on Cloud Run backend." }
                        val latency = obj.optInt("latencyMs", 0)
                        add(
                            ToolExecutionResult(
                                toolId = id,
                                toolName = name,
                                sourceLabel = obj.optString("sourceLabel").ifBlank { "Cloud Run API" },
                                summaryOutput = output,
                                latencyMs = latency
                            )
                        )
                    } else {
                        val toolId = toolsArray.optString(i).trim()
                        if (toolId.isNotBlank()) {
                            add(
                                ToolExecutionResult(
                                    toolId = toolId,
                                    toolName = toolId.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                    sourceLabel = "Cloud Run API",
                                    summaryOutput = "Executed on Cloud Run backend with scrubbed payload.",
                                    latencyMs = 0
                                )
                            )
                        }
                    }
                }
            }
        }

        val totalTools = listOf("totalToolsAvailable", "total_tools_available", "totalTools", "total_tools")
            .firstNotNullOfOrNull { key -> dataObj.optInt(key, -1).takeIf { it > 0 } }
            ?: parsedTools.size

        return CloudInvestigationResult(
            diagnosis = diagnosis,
            toolsExecuted = parsedTools,
            totalToolsAvailable = totalTools,
            recommendedActions = recommendedActions,
            suggestedReply = suggestedReply,
            backendSourceLabel = "Live Cloud Run API · europe-west1"
        )
    }

    private fun extractArray(rawBody: String, candidateKeys: List<String>): JSONArray? {
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("[")) {
            return JSONArray(trimmed)
        }
        val root = JSONObject(trimmed)
        for (key in candidateKeys) {
            root.optJSONArray(key)?.let { return it }
        }
        val nested = root.optJSONObject("data") ?: root.optJSONObject("result")
        if (nested != null) {
            for (key in candidateKeys) {
                nested.optJSONArray(key)?.let { return it }
            }
        }
        return null
    }

    private fun parseSlackTimestampMillis(obj: JSONObject): Long? {
        val tsStr = listOf("ts", "slackTs", "messageTs", "timestamp")
            .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() && it != "null" } }
        if (tsStr != null) {
            val asDouble = tsStr.toDoubleOrNull()
            if (asDouble != null) {
                return if (asDouble > 1e12) asDouble.toLong() else (asDouble * 1000.0).toLong()
            }
        }
        val createdAtMs = obj.optLong("createdAtMillis", 0L)
        return createdAtMs.takeIf { it > 0L }
    }
}
