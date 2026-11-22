package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class IncidentSeverity(val displayName: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    NOISE("Noise");

    companion object {
        fun fromRaw(raw: String?): IncidentSeverity =
            entries.firstOrNull { it.name == raw } ?: MEDIUM
    }
}

enum class IncidentStatus(val displayName: String) {
    NEEDS_ACTION("Needs Action"),
    HIDDEN_NOISE("Hidden Noise"),
    INVESTIGATED("Investigated"),
    RESOLVED("Resolved");

    companion object {
        fun fromRaw(raw: String?): IncidentStatus =
            entries.firstOrNull { it.name == raw } ?: NEEDS_ACTION
    }
}

enum class InferenceSourceType(val badgeLabel: String, val isCloud: Boolean) {
    ON_DEVICE_NANO("On-Device · Private", false),
    LOCAL_OFFLINE_GUARD("On-Device · Private", false),
    CLOUD_FALLBACK("On-Device · Private", false),
    CLOUD_INVESTIGATION("Cloud Investigation", true);

    companion object {
        fun fromRaw(raw: String?): InferenceSourceType =
            entries.firstOrNull { it.name == raw } ?: ON_DEVICE_NANO
    }
}

enum class InboxFilter(val label: String) {
    NEEDS_ACTION("Needs Action"),
    ALL("All"),
    HIDDEN_NOISE("Hidden Noise")
}

data class SlackWorkspaceStatus(
    val connected: Boolean = false,
    val orgId: String = "",
    val teamName: String = "",
    val botName: String = ""
)

data class SlackChannelInfo(
    val id: String,
    val name: String,
    val memberCount: Int = 0,
    val isPrivate: Boolean = false
)

data class DecisionScores(
    val needsAction: Double = 0.0,
    val customerBlocked: Double = 0.0,
    val isNoise: Double = 0.0,
    val revenueRisk: Double = 0.0,
    val manualMoneyMove: Double = 0.0,
    val needsEngineering: Double = 0.0,
    val route: String = "",
    val latencyMs: Int? = null,
    val priorityScore: Int = 0,
    val bucket: String = "needs_action",
    val model: String = "gemini-decision-1",
    val outputTokens: Int = 0
) {
    fun effectiveRoute(): String {
        if (route in setOf("page_eng", "notify_ops", "digest_only", "ignore")) return route
        if (isNoise >= 0.70) return "ignore"
        val hasImpact =
            needsAction >= 0.55 || customerBlocked >= 0.70 || revenueRisk >= 0.75 || manualMoneyMove >= 0.65
        return when {
            needsEngineering >= 0.65 && hasImpact -> "page_eng"
            hasImpact -> "notify_ops"
            needsAction >= 0.35 && isNoise < 0.65 -> "digest_only"
            else -> "ignore"
        }
    }

    fun routeShortLabel(): String = when (effectiveRoute()) {
        "page_eng" -> "Page Eng"
        "notify_ops" -> "Notify Ops"
        "digest_only" -> "Digest"
        else -> "Ignored"
    }
}

data class SyncedSlackMessage(
    val externalId: String,
    val channel: String,
    val title: String,
    val rawMessage: String,
    val author: String? = null,
    val timestampMillis: Long? = null,
    val isBot: Boolean = false,
    val replyCount: Int = 0,
    val existingDiagnosis: String? = null,
    val decisionScores: DecisionScores? = null
)

@Keep
@IgnoreExtraProperties
data class Incident(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val channel: String = "#general",
    val industryPack: String = "LIVE_SLACK",
    val severity: String = IncidentSeverity.MEDIUM.name,
    val status: String = IncidentStatus.NEEDS_ACTION.name,
    val noiseSuppressed: Boolean = false,
    val rawMessage: String = "",
    val scrubbedMessage: String = "",
    val redactionCount: Long = 0L,
    val triageSummary: String = "",
    val suggestedReply: String = "",
    val inferenceSource: String = InferenceSourceType.ON_DEVICE_NANO.name,
    val diagnosis: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val totalToolsAvailable: Long = 0L,
    val investigatedRole: String? = null,
    val approvedReply: String? = null,
    val approvedByRole: String? = null,
    val slackPosted: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun severityEnum(): IncidentSeverity = IncidentSeverity.fromRaw(severity)
    fun statusEnum(): IncidentStatus = IncidentStatus.fromRaw(status)
    fun inferenceSourceEnum(): InferenceSourceType = InferenceSourceType.fromRaw(inferenceSource)
}

@Keep
@IgnoreExtraProperties
data class Connector(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val connectorType: String = "MCP_SERVER",
    val endpointUrl: String = "",
    val toolNames: List<String> = emptyList(),
    val toolCount: Long = 0L,
    val enabled: Boolean = true,
    val statusLabel: String = "Connected",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Organization(
    @DocumentId val id: String = "",
    val userId: String = "",
    val orgName: String = "",
    val operatorRole: String = "SUPPORT",
    val webhookUrl: String = "",
    val slackConnected: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class UserSettings(
    @DocumentId val id: String = "",
    val userId: String = "",
    val orgId: String? = null,
    val orgName: String? = null,
    val onboardingCompleted: Boolean = false,
    val activePack: String = "LIVE_SLACK",
    val activeRole: String = "SUPPORT",
    val slackWebhookUrl: String? = null,
    val slackChannel: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun needsOnboarding(): Boolean = !onboardingCompleted && orgId.isNullOrBlank()
}

data class RedactionEntry(
    val category: String,
    val originalValue: String,
    val replacementToken: String
)

data class PrivacyShieldReport(
    val rawText: String,
    val scrubbedText: String,
    val redactions: List<RedactionEntry>
)

data class ToolExecutionResult(
    val toolId: String,
    val toolName: String,
    val sourceLabel: String,
    val summaryOutput: String,
    val latencyMs: Int
)

data class LocalTriageResult(
    val noiseSuppressed: Boolean,
    val severity: IncidentSeverity,
    val triageSummary: String,
    val suggestedReply: String,
    val inferenceSource: InferenceSourceType,
    val runtimeDetail: String
)

data class CloudInvestigationResult(
    val diagnosis: String,
    val toolsExecuted: List<ToolExecutionResult>,
    val totalToolsAvailable: Int,
    val recommendedActions: List<String>,
    val suggestedReply: String,
    val backendSourceLabel: String = "Live Cloud Run API · europe-west1"
)

data class TriageChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val sourceLabel: String? = null,
    val isStreaming: Boolean = false
)

data class IncidentGroup(
    val groupKey: String,
    val primary: Incident,
    val occurrences: List<Incident>,
    val scores: DecisionScores? = null
) {
    val count: Int get() = occurrences.size
}

object IncidentGrouper {
    private val entityRegex = Regex("(?:Business|Account|Job ID):\\s*([^\\n(•]+)", RegexOption.IGNORE_CASE)

    fun buildKey(incident: Incident, cleanTitle: String): String {
        val baseTitle = cleanTitle
            .lowercase()
            .replace(Regex("^\\[[a-z0-9 _.-]{2,32}\\]\\s*"), "")
            .replace(Regex("\\b[0-9a-f]{8,}\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(56)
        val entity = entityRegex.find(incident.rawMessage)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.lowercase()
            ?.take(32)
            .orEmpty()
        return "${incident.channel.lowercase()}|$baseTitle|$entity"
    }
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
