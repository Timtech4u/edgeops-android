package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.HybridAiService
import com.example.ai.LocalPrivacyShield
import com.example.data.CloudInvestigationResult
import com.example.data.CloudRunInvestigatorClient
import com.example.data.Connector
import com.example.data.ConnectorUtils
import com.example.data.DecisionScores
import com.example.data.EdgeOpsRepository
import com.example.data.InboxFilter
import com.example.data.Incident
import com.example.data.IncidentSeverity
import com.example.data.IncidentStatus
import com.example.data.InferenceSourceType
import com.example.data.NetworkMonitor
import com.example.data.Organization
import com.example.data.SlackChannelInfo
import com.example.data.SlackWorkspaceStatus
import com.example.data.ToolExecutionResult
import com.example.data.TriageChatMessage
import com.example.data.UiState
import com.example.data.UserSettings
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "EdgeOpsViewModel"
private const val STOP_TIMEOUT_MS = 5000L

data class DetailRuntimeState(
    val isRunningLocalTriage: Boolean = false,
    val localRuntimeDetail: String = "On-Device · Private",
    val isRunningCloudInvestigation: Boolean = false,
    val isResolving: Boolean = false,
    val cloudError: String? = null,
    val latestCloudResult: CloudInvestigationResult? = null,
    val chatMessages: List<TriageChatMessage> = emptyList(),
    val isStreamingChat: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeOpsViewModel(
    private val repository: EdgeOpsRepository,
    private val aiService: HybridAiService,
    private val cloudRunClient: CloudRunInvestigatorClient,
    private val networkMonitor: NetworkMonitor,
    private val currentUserId: String
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkMonitor.observeOnlineStatus()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = networkMonitor.isCurrentlyOnline()
        )

    val incidentsState: StateFlow<UiState<List<Incident>>> =
        repository.observeIncidents(currentUserId)
            .map<List<Incident>, UiState<List<Incident>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Error observing incidents", root)
                emit(UiState.Error(root.message ?: "Failed to load incidents."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val connectorsState: StateFlow<UiState<List<Connector>>> =
        repository.observeConnectors(currentUserId)
            .map<List<Connector>, UiState<List<Connector>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, "Error observing connectors", root)
                emit(UiState.Error(root.message ?: "Failed to load connections."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val settingsUiState: StateFlow<UiState<UserSettings>> =
        repository.observeUserSettings(currentUserId)
            .map<UserSettings, UiState<UserSettings>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                Log.w(TAG, "Error observing settings", firestoreError ?: error)
                emit(UiState.Success(UserSettings(id = currentUserId, userId = currentUserId)))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val organizationState: StateFlow<Organization?> =
        settingsUiState
            .flatMapLatest { state ->
                val orgId = (state as? UiState.Success)?.data?.orgId
                if (orgId.isNullOrBlank()) {
                    flowOf(null)
                } else {
                    repository.observeOrganization(currentUserId, orgId)
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = null
            )

    // Live Cloud Run Slack status & channels (strictly read-only)
    private val _slackStatus = MutableStateFlow<UiState<SlackWorkspaceStatus>>(UiState.Loading)
    val slackStatus: StateFlow<UiState<SlackWorkspaceStatus>> = _slackStatus.asStateFlow()

    private val _slackChannels = MutableStateFlow<List<SlackChannelInfo>>(emptyList())
    val slackChannels: StateFlow<List<SlackChannelInfo>> = _slackChannels.asStateFlow()

    private val _isSyncingSlack = MutableStateFlow(false)
    val isSyncingSlack: StateFlow<Boolean> = _isSyncingSlack.asStateFlow()

    private val _isOnboardingBusy = MutableStateFlow(false)
    val isOnboardingBusy: StateFlow<Boolean> = _isOnboardingBusy.asStateFlow()

    private val _selectedFilter = MutableStateFlow(InboxFilter.NEEDS_ACTION)
    val selectedFilter: StateFlow<InboxFilter> = _selectedFilter.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _detailStates = MutableStateFlow<Map<String, DetailRuntimeState>>(emptyMap())
    val detailStates: StateFlow<Map<String, DetailRuntimeState>> = _detailStates.asStateFlow()

    private val _decisionScores = MutableStateFlow<Map<String, DecisionScores>>(emptyMap())
    val decisionScores: StateFlow<Map<String, DecisionScores>> = _decisionScores.asStateFlow()

    private val _globalChatMessages = MutableStateFlow<List<TriageChatMessage>>(emptyList())
    val globalChatMessages: StateFlow<List<TriageChatMessage>> = _globalChatMessages.asStateFlow()

    private val _unreadProactiveCount = MutableStateFlow(0)
    val unreadProactiveCount: StateFlow<Int> = _unreadProactiveCount.asStateFlow()

    private val _isGlobalChatStreaming = MutableStateFlow(false)
    val isGlobalChatStreaming: StateFlow<Boolean> = _isGlobalChatStreaming.asStateFlow()

    private val _proactiveNotificationEvent = MutableStateFlow<Pair<String, String>?>(null)
    val proactiveNotificationEvent: StateFlow<Pair<String, String>?> = _proactiveNotificationEvent.asStateFlow()

    private var lastProactiveSignature: String? = null
    private var latestSyncedSnapshot: List<com.example.data.SyncedSlackMessage> = emptyList()

    init {
        // Automatically detect live connected Slack workspace & sync real messages on launch
        syncSlackFromBackend(userInitiated = false)
    }

    fun selectFilter(filter: InboxFilter) {
        _selectedFilter.value = filter
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    fun markGlobalCopilotRead() {
        _unreadProactiveCount.value = 0
    }

    fun consumeProactiveNotification() {
        _proactiveNotificationEvent.value = null
    }

    private fun buildBriefingThreadsPayload(
        synced: List<com.example.data.SyncedSlackMessage>
    ): JSONArray {
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val arr = JSONArray()
        for (msg in synced.take(25)) {
            val shield = LocalPrivacyShield.redactLocally(msg.rawMessage)
            val cleanTitle = LocalPrivacyShield.deriveCleanTitle(msg.title, shield.scrubbedText, msg.channel)
            val ds = msg.decisionScores
            val route = ds?.effectiveRoute()
            val iso = isoFormatter.format(Date(msg.timestampMillis ?: System.currentTimeMillis()))
            arr.put(
                JSONObject().apply {
                    put("groupKey", msg.externalId)
                    put("latestId", msg.externalId)
                    put("title", cleanTitle)
                    put("channel", msg.channel)
                    put("count", 1)
                    put("bucket", ds?.bucket ?: "needs_action")
                    if (route != null) put("route", route)
                    if (ds != null) {
                        put("priorityScore", ds.priorityScore)
                        put("needsAction", (ds.needsAction * 100).toInt())
                        put("customerBlocked", (ds.customerBlocked * 100).toInt())
                        put("isNoise", (ds.isNoise * 100).toInt())
                        put("revenueRisk", (ds.revenueRisk * 100).toInt())
                        put("manualMoneyMove", (ds.manualMoneyMove * 100).toInt())
                        put("needsEngineering", (ds.needsEngineering * 100).toInt())
                        ds.latencyMs?.let { put("latencyMs", it) }
                    }
                    put("isBot", msg.isBot)
                    put("replyCount", msg.replyCount)
                    put("unanswered", msg.replyCount == 0)
                    put("createdAtIso", iso)
                    put("scrubbedSummary", shield.scrubbedText.replace("\n", " ").take(280))
                    put("resolved", false)
                }
            )
        }
        return arr
    }

    private fun emitProactiveBriefingIfNeeded(
        workspaceName: String,
        synced: List<com.example.data.SyncedSlackMessage>
    ) {
        latestSyncedSnapshot = synced
        val actionable = synced.filter { msg ->
            val ds = msg.decisionScores
            val route = ds?.effectiveRoute()
            route == "page_eng" || route == "notify_ops" || (ds?.priorityScore ?: 0) >= 85
        }
        if (actionable.isEmpty()) return

        val topTitles = actionable.take(3).map { msg ->
            val clean = LocalPrivacyShield.deriveCleanTitle(msg.title, msg.rawMessage, msg.channel)
            val ds = msg.decisionScores
            val routeTag = ds?.routeShortLabel() ?: "Action"
            val pct = ds?.priorityScore ?: 90
            "• ${msg.channel} ($routeTag · $pct%): $clean"
        }
        val signature = "${actionable.size}:${topTitles.firstOrNull().orEmpty()}"
        if (signature == lastProactiveSignature && _globalChatMessages.value.isNotEmpty()) return
        lastProactiveSignature = signature

        val briefingBody = buildString {
            appendLine("**Decisions API: what stood out in $workspaceName**")
            topTitles.forEach { appendLine(it) }
            append("Nothing was posted in Slack.")
        }.trim()

        val msgId = "proactive_${System.currentTimeMillis()}"
        val proactiveMsg = TriageChatMessage(
            id = msgId,
            isUser = false,
            text = briefingBody,
            sourceLabel = "Proactive · Decisions API (0 output tokens)",
            isStreaming = false
        )
        _globalChatMessages.value = _globalChatMessages.value + proactiveMsg
        _unreadProactiveCount.value = _unreadProactiveCount.value + 1

        val topHeadline = LocalPrivacyShield.deriveCleanTitle(
            actionable.first().title,
            actionable.first().rawMessage,
            actionable.first().channel
        )
        val topRoute = actionable.first().decisionScores?.routeShortLabel() ?: "Alert"
        val topPct = actionable.first().decisionScores?.priorityScore ?: 95
        _proactiveNotificationEvent.value =
            "$workspaceName · $topRoute ($topPct%)" to
            "$topHeadline — Tap to open CalmDuty Copilot"

        // Upgrade in-place with full executive window briefing from POST /api/briefing (strictly scrubbed)
        if (isOnline.value) {
            viewModelScope.launch {
                val payload = buildBriefingThreadsPayload(synced)
                val tz = TimeZone.getDefault().id
                cloudRunClient.fetchExecutiveBriefing(workspaceName, tz, payload)
                    .onSuccess { (briefingText, _) ->
                        _globalChatMessages.value = _globalChatMessages.value.map { m ->
                            if (m.id == msgId) {
                                m.copy(
                                    text = briefingText,
                                    sourceLabel = "Proactive · Decisions API (0 output tokens)"
                                )
                            } else {
                                m
                            }
                        }
                    }
            }
        }
    }

    fun requestExecutiveBriefing(workspaceName: String) {
        if (_isGlobalChatStreaming.value) return
        val msgId = "brief_${System.currentTimeMillis()}"
        _isGlobalChatStreaming.value = true
        _globalChatMessages.value = _globalChatMessages.value + TriageChatMessage(
            id = msgId,
            isUser = false,
            text = "Building executive Decisions API window briefing...",
            sourceLabel = "Proactive · Decisions API",
            isStreaming = true
        )

        viewModelScope.launch {
            try {
                val threadsPayload = if (latestSyncedSnapshot.isNotEmpty()) {
                    buildBriefingThreadsPayload(latestSyncedSnapshot)
                } else {
                    val allIncidents = (incidentsState.value as? UiState.Success)?.data.orEmpty()
                    val scoresMap = _decisionScores.value
                    val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    JSONArray().apply {
                        for (inc in allIncidents.take(25)) {
                            val shield = LocalPrivacyShield.redactLocally(inc.rawMessage)
                            val ds = scoresMap[inc.id]
                            put(
                                JSONObject().apply {
                                    put("groupKey", inc.id)
                                    put("latestId", inc.id)
                                    put("title", LocalPrivacyShield.deriveCleanTitle(inc.title, shield.scrubbedText, inc.channel))
                                    put("channel", inc.channel)
                                    put("count", 1)
                                    put("bucket", ds?.bucket ?: if (inc.noiseSuppressed) "hidden_noise" else "needs_action")
                                    ds?.effectiveRoute()?.let { put("route", it) }
                                    ds?.let {
                                        put("priorityScore", it.priorityScore)
                                        put("needsAction", (it.needsAction * 100).toInt())
                                        put("customerBlocked", (it.customerBlocked * 100).toInt())
                                        put("isNoise", (it.isNoise * 100).toInt())
                                        put("revenueRisk", (it.revenueRisk * 100).toInt())
                                        put("manualMoneyMove", (it.manualMoneyMove * 100).toInt())
                                        put("needsEngineering", (it.needsEngineering * 100).toInt())
                                        it.latencyMs?.let { ms -> put("latencyMs", ms) }
                                    }
                                    put("isBot", true)
                                    put("replyCount", 0)
                                    put("unanswered", true)
                                    put("createdAtIso", isoFormatter.format(inc.createdAt?.toDate() ?: Date()))
                                    put("scrubbedSummary", shield.scrubbedText.replace("\n", " ").take(280))
                                    put("resolved", inc.statusEnum() == IncidentStatus.RESOLVED)
                                }
                            )
                        }
                    }
                }

                val tz = TimeZone.getDefault().id
                val result = cloudRunClient.fetchExecutiveBriefing(workspaceName, tz, threadsPayload)
                val finalText = result.getOrNull()?.first
                    ?: "Working offline — open any thread to inspect local Privacy Shield and Decisions scores. Nothing was posted in Slack."
                val finalLabel = if (result.isSuccess) {
                    "Proactive · Decisions API (0 output tokens)"
                } else {
                    "On-Device · Offline"
                }
                _globalChatMessages.value = _globalChatMessages.value.map { m ->
                    if (m.id == msgId) {
                        m.copy(text = finalText, sourceLabel = finalLabel, isStreaming = false)
                    } else {
                        m
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isGlobalChatStreaming.value = false
            }
        }
    }

    fun sendGlobalCopilotPrompt(workspaceName: String, prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank() || _isGlobalChatStreaming.value) return

        val allIncidents = (incidentsState.value as? UiState.Success)?.data.orEmpty()
        val scoresMap = _decisionScores.value
        val scrubbedDigest = allIncidents
            .sortedByDescending { scoresMap[it.id]?.priorityScore ?: 0 }
            .take(15)
            .joinToString("\n") { inc ->
                val clean = LocalPrivacyShield.deriveCleanTitle(inc.title, inc.rawMessage, inc.channel)
                val scrubbed = LocalPrivacyShield.redactLocally(inc.rawMessage).scrubbedText
                    .replace("\n", " ")
                    .take(160)
                val pct = scoresMap[inc.id]?.priorityScore
                val scoreTag = if (pct != null) " [$pct%]" else ""
                "- ${inc.channel}$scoreTag $clean: $scrubbed"
            }
            .ifBlank { "No synced alerts yet." }

        val userMsg = TriageChatMessage(
            id = "gu_${System.currentTimeMillis()}",
            isUser = true,
            text = trimmed
        )
        val botId = "gb_${System.currentTimeMillis() + 1}"
        val placeholder = TriageChatMessage(
            id = botId,
            isUser = false,
            text = "",
            sourceLabel = "Thinking...",
            isStreaming = true
        )
        val historyText = _globalChatMessages.value.takeLast(4).joinToString("\n") {
            "${if (it.isUser) "Operator" else "EdgeOps"}: ${it.text}"
        }

        _isGlobalChatStreaming.value = true
        _globalChatMessages.value = _globalChatMessages.value + userMsg + placeholder

        viewModelScope.launch {
            try {
                val (finalText, finalSource) = aiService.streamWorkspaceCopilotChat(
                    workspaceName = workspaceName,
                    scrubbedDigestContext = scrubbedDigest,
                    userPrompt = trimmed,
                    historyText = historyText
                ) { accumulated, src ->
                    _globalChatMessages.value = _globalChatMessages.value.map { m ->
                        if (m.id == botId) m.copy(text = accumulated, sourceLabel = src, isStreaming = true) else m
                    }
                }
                _globalChatMessages.value = _globalChatMessages.value.map { m ->
                    if (m.id == botId) m.copy(text = finalText, sourceLabel = finalSource, isStreaming = false) else m
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _globalChatMessages.value = _globalChatMessages.value.map { m ->
                    if (m.id == botId) {
                        m.copy(
                            text = "Couldn't finish briefing (${e.localizedMessage ?: "offline"}).",
                            sourceLabel = "Offline",
                            isStreaming = false
                        )
                    } else {
                        m
                    }
                }
            } finally {
                _isGlobalChatStreaming.value = false
            }
        }
    }

    fun sendTriageChat(
        incident: Incident,
        occurrenceCount: Int,
        prompt: String
    ) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return
        val current = _detailStates.value[incident.id] ?: DetailRuntimeState()
        if (current.isStreamingChat) return

        val userMsg = TriageChatMessage(
            id = "u_${System.currentTimeMillis()}",
            isUser = true,
            text = trimmed
        )
        val botId = "b_${System.currentTimeMillis() + 1}"
        val placeholderBot = TriageChatMessage(
            id = botId,
            isUser = false,
            text = "",
            sourceLabel = "Thinking...",
            isStreaming = true
        )
        val historyText = current.chatMessages.takeLast(4).joinToString("\n") {
            "${if (it.isUser) "Operator" else "EdgeOps"}: ${it.text}"
        }

        updateDetailState(incident.id) { state ->
            state.copy(
                isStreamingChat = true,
                chatMessages = state.chatMessages + userMsg + placeholderBot
            )
        }

        viewModelScope.launch {
            try {
                val shield = LocalPrivacyShield.redactLocally(incident.rawMessage)
                val cleanTitle = LocalPrivacyShield.deriveCleanTitle(
                    incident.title,
                    incident.rawMessage,
                    incident.channel
                )
                val (finalText, finalSource) = aiService.streamTriageChat(
                    title = cleanTitle,
                    channel = incident.channel,
                    scrubbedMessage = shield.scrubbedText,
                    occurrenceCount = occurrenceCount,
                    userPrompt = trimmed,
                    historyText = historyText
                ) { accumulated, src ->
                    updateDetailState(incident.id) { state ->
                        state.copy(
                            chatMessages = state.chatMessages.map { m ->
                                if (m.id == botId) {
                                    m.copy(text = accumulated, sourceLabel = src, isStreaming = true)
                                } else {
                                    m
                                }
                            }
                        )
                    }
                }
                updateDetailState(incident.id) { state ->
                    state.copy(
                        isStreamingChat = false,
                        chatMessages = state.chatMessages.map { m ->
                            if (m.id == botId) {
                                m.copy(text = finalText, sourceLabel = finalSource, isStreaming = false)
                            } else {
                                m
                            }
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateDetailState(incident.id) { state ->
                    state.copy(
                        isStreamingChat = false,
                        chatMessages = state.chatMessages.map { m ->
                            if (m.id == botId) {
                                m.copy(
                                    text = "Couldn't complete chat right now (${e.localizedMessage ?: "offline"}).",
                                    sourceLabel = "Error",
                                    isStreaming = false
                                )
                            } else {
                                m
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Live read-only Slack sync pipeline:
     * 1. GET  /api/slack/status -> detect connected workspace (e.g. Northstar Ops)
     * 2. GET  /api/slack/channels?orgId={orgId} -> fetch real bot channels
     * 3. POST /api/slack/sync { "orgId": orgId } -> pull real Slack messages (read-only)
     * 4. Clean Slack formatting + run local Privacy Shield + on-device Gemini Nano triage -> persist scrubbed metadata only
     */
    fun syncSlackFromBackend(userInitiated: Boolean = true) {
        if (_isSyncingSlack.value) return
        viewModelScope.launch {
            _isSyncingSlack.value = true
            try {
                val savedOrgId = (settingsUiState.value as? UiState.Success)?.data?.orgId
                val statusResult = cloudRunClient.fetchSlackStatus(savedOrgId)

                statusResult.onFailure { err ->
                    _slackStatus.value = UiState.Error(
                        err.localizedMessage ?: "Could not reach EdgeOps backend."
                    )
                    if (userInitiated) {
                        _snackbarMessage.value = "Offline or backend unreachable — showing cached inbox."
                    }
                    return@launch
                }

                val status = statusResult.getOrThrow()
                _slackStatus.value = UiState.Success(status)

                if (!status.connected) {
                    _slackChannels.value = emptyList()
                    if (userInitiated) {
                        _snackbarMessage.value = "No Slack workspace connected yet."
                    }
                    return@launch
                }

                // Auto-skip onboarding straight into Inbox if Cloud Run already reports a connected workspace
                val currentSettings = (settingsUiState.value as? UiState.Success)?.data
                if (currentSettings == null || currentSettings.needsOnboarding()) {
                    repository.completeOnboarding(
                        orgId = status.orgId.ifBlank { null },
                        orgName = status.teamName.ifBlank { "CalmDuty" }
                    )
                }

                val activeOrgId = status.orgId.ifBlank { savedOrgId.orEmpty() }

                // Step 2: Fetch real Slack channels
                cloudRunClient.fetchSlackChannels(activeOrgId)
                    .onSuccess { channels -> _slackChannels.value = channels }

                // Step 3: Pull real Slack messages (strictly read-only)
                val syncResult = cloudRunClient.syncSlackMessages(activeOrgId)
                syncResult.onFailure { err ->
                    if (userInitiated) {
                        _snackbarMessage.value = err.localizedMessage ?: "Slack sync failed."
                    }
                    return@launch
                }

                val allSynced = syncResult.getOrThrow()
                // Store all live gemini-decision-1 scores immediately (even for already-persisted docs)
                val updatedScores = _decisionScores.value.toMutableMap()
                for (msg in allSynced) {
                    val docId = EdgeOpsRepository.buildDeterministicSlackDocId(
                        userId = currentUserId,
                        externalId = msg.externalId
                    )
                    msg.decisionScores?.let { updatedScores[docId] = it }
                }
                _decisionScores.value = updatedScores

                val syncedMessages = allSynced
                    .sortedWith(
                        compareByDescending<com.example.data.SyncedSlackMessage> { it.decisionScores?.priorityScore ?: 0 }
                            .thenByDescending { it.timestampMillis ?: 0L }
                    )
                    .take(25)

                val existingIds = (incidentsState.value as? UiState.Success)
                    ?.data
                    ?.map { it.id }
                    ?.toSet()
                    .orEmpty()

                var newlyAdded = 0
                for (msg in syncedMessages) {
                    val expectedDocId = EdgeOpsRepository.buildDeterministicSlackDocId(
                        userId = currentUserId,
                        externalId = msg.externalId
                    )
                    // Always cache raw unredacted text strictly in on-device RAM for Before/After Privacy Diff
                    val shield = LocalPrivacyShield.redactLocally(msg.rawMessage)
                    repository.rememberLocalRawMessage(expectedDocId, shield.rawText)

                    if (existingIds.contains(expectedDocId)) {
                        continue
                    }

                    val cleanTitle = LocalPrivacyShield.deriveCleanTitle(
                        explicitTitle = msg.title,
                        rawMessage = msg.rawMessage,
                        channel = msg.channel
                    )

                    val ds = msg.decisionScores
                    val noiseSuppressed: Boolean
                    val severity: IncidentSeverity
                    val triageSummary: String
                    val suggestedReply: String
                    val inferenceSource: InferenceSourceType

                    if (ds != null) {
                        noiseSuppressed = ds.bucket == "hidden_noise"
                        severity = when {
                            noiseSuppressed -> IncidentSeverity.NOISE
                            ds.priorityScore >= 85 -> IncidentSeverity.HIGH
                            ds.priorityScore >= 55 -> IncidentSeverity.MEDIUM
                            else -> IncidentSeverity.LOW
                        }
                        val actionPct = (ds.needsAction * 100).toInt()
                        val riskPct = (ds.revenueRisk * 100).toInt()
                        triageSummary = if (noiseSuppressed) {
                            "Routine FYI notification filtered automatically (gemini-decision-1 · 0 output tokens)."
                        } else {
                            "Scored ${ds.priorityScore}% priority (Needs action $actionPct% · Risk $riskPct%) by gemini-decision-1 at 0 output tokens. Tap Check what happened for full diagnosis."
                        }
                        suggestedReply = "Thanks for flagging this in ${msg.channel}. We're reviewing it now and will follow up shortly."
                        inferenceSource = InferenceSourceType.ON_DEVICE_NANO
                    } else {
                        val triage = aiService.runLocalTriage(
                            title = cleanTitle,
                            channel = msg.channel,
                            scrubbedMessage = shield.scrubbedText,
                            isOnline = isOnline.value
                        )
                        noiseSuppressed = triage.noiseSuppressed
                        severity = triage.severity
                        triageSummary = triage.triageSummary
                        suggestedReply = triage.suggestedReply
                        inferenceSource = triage.inferenceSource
                    }

                    val initialStatus = when {
                        !msg.existingDiagnosis.isNullOrBlank() -> IncidentStatus.INVESTIGATED
                        noiseSuppressed -> IncidentStatus.HIDDEN_NOISE
                        else -> IncidentStatus.NEEDS_ACTION
                    }

                    val insertRes = repository.insertSyncedIncidentIfAbsent(
                        externalId = msg.externalId,
                        title = cleanTitle,
                        channel = msg.channel,
                        severity = severity,
                        status = initialStatus,
                        noiseSuppressed = noiseSuppressed,
                        rawMessage = shield.rawText,
                        scrubbedMessage = shield.scrubbedText,
                        redactionCount = shield.redactions.size,
                        triageSummary = triageSummary,
                        suggestedReply = suggestedReply,
                        inferenceSource = inferenceSource,
                        existingDiagnosis = msg.existingDiagnosis
                    )
                    if (insertRes.getOrDefault(false)) {
                        newlyAdded++
                    }
                }

                if (userInitiated) {
                    _snackbarMessage.value = when {
                        newlyAdded > 0 -> "Synced $newlyAdded new Slack message(s) from ${status.teamName.ifBlank { "Slack" }}"
                        syncedMessages.isNotEmpty() -> "Inbox is up to date (${status.teamName.ifBlank { "Slack" }})"
                        else -> "Connected to ${status.teamName.ifBlank { "Slack" }} · No new messages"
                    }
                }

                emitProactiveBriefingIfNeeded(
                    workspaceName = status.teamName.ifBlank { "EdgeOps" },
                    synced = syncedMessages
                )
            } finally {
                _isSyncingSlack.value = false
            }
        }
    }

    fun createWorkspaceAndEnter(orgName: String) {
        val cleanName = orgName.trim()
        if (cleanName.isBlank()) {
            _snackbarMessage.value = "Enter your workspace name."
            return
        }
        if (_isOnboardingBusy.value) return
        viewModelScope.launch {
            _isOnboardingBusy.value = true
            try {
                val createRes = repository.createOrganization(cleanName)
                createRes.onSuccess { org ->
                    repository.completeOnboarding(orgId = org.id, orgName = org.orgName)
                        .onSuccess {
                            _snackbarMessage.value = "Workspace \"${org.orgName}\" created."
                            syncSlackFromBackend(userInitiated = false)
                        }
                        .onFailure { err ->
                            _snackbarMessage.value = err.localizedMessage ?: "Failed to save workspace."
                        }
                }.onFailure { err ->
                    _snackbarMessage.value = err.localizedMessage ?: "Could not create workspace."
                }
            } finally {
                _isOnboardingBusy.value = false
            }
        }
    }

    fun rerunLocalTriage(incident: Incident) {
        if (incident.statusEnum() == IncidentStatus.RESOLVED) return
        viewModelScope.launch {
            updateDetailState(incident.id) { it.copy(isRunningLocalTriage = true, cloudError = null) }
            try {
                val shield = LocalPrivacyShield.redactLocally(incident.rawMessage)
                val triage = aiService.runLocalTriage(
                    title = incident.title,
                    channel = incident.channel,
                    scrubbedMessage = shield.scrubbedText,
                    isOnline = isOnline.value
                )
                val nextStatus = when {
                    triage.noiseSuppressed -> IncidentStatus.HIDDEN_NOISE
                    incident.statusEnum() == IncidentStatus.INVESTIGATED -> IncidentStatus.INVESTIGATED
                    else -> IncidentStatus.NEEDS_ACTION
                }
                repository.saveLocalTriage(
                    incidentId = incident.id,
                    scrubbedMessage = shield.scrubbedText,
                    redactionCount = shield.redactions.size,
                    triageSummary = triage.triageSummary,
                    suggestedReply = triage.suggestedReply,
                    inferenceSource = triage.inferenceSource,
                    noiseSuppressed = triage.noiseSuppressed,
                    severity = triage.severity,
                    status = nextStatus
                )
                updateDetailState(incident.id) {
                    it.copy(
                        isRunningLocalTriage = false,
                        localRuntimeDetail = triage.runtimeDetail
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateDetailState(incident.id) { it.copy(isRunningLocalTriage = false) }
                _snackbarMessage.value = e.localizedMessage ?: "Local triage failed."
            }
        }
    }

    fun toggleNoiseStatus(incident: Incident, markAsNoise: Boolean) {
        if (incident.statusEnum() == IncidentStatus.RESOLVED) return
        viewModelScope.launch {
            repository.toggleNoiseStatus(incident.id, markAsNoise)
                .onSuccess {
                    _snackbarMessage.value = if (markAsNoise) {
                        "Moved to Hidden Noise"
                    } else {
                        "Restored to Needs Action"
                    }
                }
                .onFailure {
                    _snackbarMessage.value = it.localizedMessage ?: "Could not update alert status."
                }
        }
    }

    /**
     * "Check what happened" -> sends ONLY the locally scrubbed payload to
     * POST https://calmduty.web.app/api/investigate
     * with Firebase AI Logic fallback.
     */
    fun checkWhatHappened(incident: Incident) {
        if (incident.statusEnum() == IncidentStatus.RESOLVED) return
        viewModelScope.launch {
            updateDetailState(incident.id) {
                it.copy(isRunningCloudInvestigation = true, cloudError = null)
            }
            val customConnectors = (connectorsState.value as? UiState.Success)?.data.orEmpty()
            val result = aiService.runCloudInvestigation(
                incident = incident,
                customConnectors = customConnectors
            )

            result.onSuccess { investigation ->
                repository.saveCloudInvestigation(
                    incidentId = incident.id,
                    diagnosis = investigation.diagnosis,
                    toolsUsed = investigation.toolsExecuted.map { it.toolId },
                    totalToolsAvailable = investigation.totalToolsAvailable,
                    suggestedReply = investigation.suggestedReply
                ).onFailure { err ->
                    _snackbarMessage.value = "Investigation ready, sync warning: ${err.localizedMessage}"
                }
                updateDetailState(incident.id) {
                    it.copy(
                        isRunningCloudInvestigation = false,
                        cloudError = null,
                        latestCloudResult = investigation
                    )
                }
            }.onFailure { err ->
                updateDetailState(incident.id) {
                    it.copy(
                        isRunningCloudInvestigation = false,
                        cloudError = err.localizedMessage ?: "Could not complete cloud check."
                    )
                }
            }
        }
    }

    /**
     * Marks ticket resolved locally in Firestore (strictly read-only to Slack — NEVER posts to Slack).
     */
    fun markIncidentResolved(incident: Incident, replyNote: String) {
        if (incident.statusEnum() == IncidentStatus.RESOLVED) return
        viewModelScope.launch {
            updateDetailState(incident.id) { it.copy(isResolving = true) }
            try {
                val note = replyNote.trim().ifBlank { incident.suggestedReply.ifBlank { "Marked resolved." } }
                repository.markIncidentResolved(incident.id, note)
                    .onSuccess {
                        _snackbarMessage.value = "Marked resolved in EdgeOps"
                    }
                    .onFailure { err ->
                        _snackbarMessage.value = err.localizedMessage ?: "Failed to mark resolved."
                    }
            } finally {
                updateDetailState(incident.id) { it.copy(isResolving = false) }
            }
        }
    }

    fun connectMcpServer(name: String, endpointUrl: String, declaredToolsCsv: String) {
        val cleanName = name.trim()
        val cleanUrl = endpointUrl.trim()
        if (cleanName.isBlank() || !cleanUrl.startsWith("http")) {
            _snackbarMessage.value = "Enter a server name and a valid http(s) MCP Server URL."
            return
        }
        viewModelScope.launch {
            val tools = declaredToolsCsv.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val statusLabel = if (isOnline.value) {
                ConnectorUtils.probeMcpEndpoint(cleanUrl)
            } else {
                "Registered · Offline Ready"
            }

            repository.addConnector(
                name = cleanName,
                connectorType = "MCP_SERVER",
                endpointUrl = cleanUrl,
                toolNames = tools,
                statusLabel = statusLabel
            ).onSuccess {
                _snackbarMessage.value = "Connected MCP Server \"$cleanName\""
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to connect MCP server."
            }
        }
    }

    fun importOpenApiSpec(rawJson: String) {
        val parsed = ConnectorUtils.parseOpenApiSpec(rawJson)
        parsed.onFailure { err ->
            _snackbarMessage.value = err.localizedMessage ?: "Invalid OpenAPI JSON."
            return
        }
        val (title, baseUrl, tools) = parsed.getOrThrow()
        viewModelScope.launch {
            repository.addConnector(
                name = title,
                connectorType = "OPENAPI_SPEC",
                endpointUrl = baseUrl,
                toolNames = tools,
                statusLabel = "Imported · ${tools.size} ops"
            ).onSuccess {
                _snackbarMessage.value = "Imported \"$title\" (${tools.size} operations)"
            }.onFailure {
                _snackbarMessage.value = it.localizedMessage ?: "Failed to import OpenAPI spec."
            }
        }
    }

    fun toggleConnector(connector: Connector, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleConnector(connector.id, enabled)
        }
    }

    fun deleteConnector(connector: Connector) {
        viewModelScope.launch {
            repository.deleteConnector(connector.id)
                .onSuccess { _snackbarMessage.value = "Removed ${connector.name}" }
        }
    }

    fun savedToolsForIncident(incident: Incident): List<ToolExecutionResult> {
        return incident.toolsUsed.map { toolId ->
            ToolExecutionResult(
                toolId = toolId,
                toolName = toolId.replace('_', ' ').replaceFirstChar { it.uppercase() },
                sourceLabel = "Cloud Run API",
                summaryOutput = "Executed on scrubbed Slack message.",
                latencyMs = 0
            )
        }
    }

    private fun updateDetailState(
        incidentId: String,
        transform: (DetailRuntimeState) -> DetailRuntimeState
    ) {
        _detailStates.update { map ->
            val existing = map[incidentId] ?: DetailRuntimeState()
            map + (incidentId to transform(existing))
        }
    }
}
