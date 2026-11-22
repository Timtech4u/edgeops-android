package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.example.ui.components.formatCopilotMarkdown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.LocalPrivacyShield
import com.example.data.DecisionScores
import com.example.data.Incident
import com.example.data.IncidentStatus
import com.example.data.PrivacyShieldReport
import com.example.data.ToolExecutionResult
import com.example.data.TriageChatMessage
import com.example.ui.components.EdgeOpsPill
import com.example.ui.components.TopBarStatusPill
import com.example.ui.theme.BlueBorder
import com.example.ui.theme.BlueContainer
import com.example.ui.theme.BlueOnContainer
import com.example.ui.theme.BluePrimary
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CanvasLight
import com.example.ui.theme.EmeraldBorder
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldOnContainer
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoseBorder
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseOnContainer
import com.example.ui.theme.SurfaceSubtle
import com.example.ui.theme.SurfaceWhite
import com.example.viewmodel.DetailRuntimeState
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TicketDetailScreen(
    incident: Incident?,
    occurrenceCount: Int = 1,
    decisionScores: DecisionScores?,
    runtimeState: DetailRuntimeState,
    savedToolResults: List<ToolExecutionResult>,
    isOnline: Boolean,
    onBack: () -> Unit,
    onRerunLocalTriage: (Incident) -> Unit,
    onToggleNoise: (Incident, Boolean) -> Unit,
    onCheckWhatHappened: (Incident) -> Unit,
    onSendChat: (Incident, String) -> Unit,
    onMarkResolved: (Incident, String) -> Unit
) {
    val context = LocalContext.current

    if (incident == null) {
        Scaffold(
            containerColor = CanvasLight,
            topBar = {
                TopAppBar(
                    title = { Text("Ticket") },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasLight)
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading ticket...", color = InkSecondary)
            }
        }
        return
    }

    val isResolved = incident.statusEnum() == IncidentStatus.RESOLVED
    val hasCloudInvestigation = runtimeState.latestCloudResult != null || !incident.diagnosis.isNullOrBlank()
    val privacyReport = remember(incident.rawMessage) {
        LocalPrivacyShield.redactLocally(incident.rawMessage)
    }
    val cleanTitle = remember(incident.title, incident.rawMessage, incident.channel) {
        LocalPrivacyShield.deriveCleanTitle(incident.title, incident.rawMessage, incident.channel)
    }
    val timeLabel = remember(incident.createdAt) {
        val date = incident.createdAt?.toDate()
        if (date != null) SimpleDateFormat("h:mm a", Locale.getDefault()).format(date) else "Just now"
    }

    var replyDraft by remember(incident.id) {
        mutableStateOf(incident.approvedReply ?: incident.suggestedReply)
    }
    LaunchedEffect(incident.suggestedReply, incident.approvedReply, runtimeState.latestCloudResult) {
        val updated = incident.approvedReply
            ?: runtimeState.latestCloudResult?.suggestedReply
            ?: incident.suggestedReply
        if (updated.isNotBlank()) {
            replyDraft = updated
        }
    }

    // Compact single-row metadata inspector: only one lightweight panel opens when tapped
    var activeMetaPanel by remember(incident.id) { mutableStateOf<String?>(null) }
    var chatInput by remember(incident.id) { mutableStateOf("") }
    var copiedReply by remember(incident.id) { mutableStateOf(false) }
    var selectedToolId by remember(incident.id) { mutableStateOf<String?>(null) }

    val toolsToShow = runtimeState.latestCloudResult?.toolsExecuted ?: savedToolResults
    val diagnosisText = runtimeState.latestCloudResult?.diagnosis ?: incident.diagnosis.orEmpty()
    val plainEnglishSummary = if (hasCloudInvestigation && diagnosisText.isNotBlank()) {
        diagnosisText
    } else {
        incident.triageSummary.ifBlank {
            privacyReport.scrubbedText.ifBlank { "Triaged privately on your device." }
        }
    }

    Scaffold(
        containerColor = CanvasLight,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Ticket",
                        style = MaterialTheme.typography.titleLarge,
                        color = InkPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("detail_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = InkPrimary
                        )
                    }
                },
                actions = {
                    TopBarStatusPill(
                        isOnline = isOnline,
                        isCloudInvestigation = hasCloudInvestigation,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CanvasLight,
                    scrolledContainerColor = SurfaceWhite
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Lead Summary & Primary Action Card (Zero Role Switcher)
            item(key = "lead_summary") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    border = BorderStroke(1.dp, if (isResolved) EmeraldBorder else BorderHairline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = incident.channel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = InkSecondary
                            )
                            Text("·", style = MaterialTheme.typography.labelSmall, color = InkMuted)
                            Text(
                                text = timeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = InkMuted
                            )
                            if (isResolved) {
                                Text("·", style = MaterialTheme.typography.labelSmall, color = InkMuted)
                                Text(
                                    text = "Resolved",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = EmeraldOnContainer
                                )
                            }
                        }

                        Text(
                            text = cleanTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            color = InkPrimary
                        )

                        // Plain-English Summary / What Happened Diagnosis
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = if (hasCloudInvestigation) BlueContainer.copy(alpha = 0.65f) else CanvasLight,
                            border = BorderStroke(
                                1.dp,
                                if (hasCloudInvestigation) BlueBorder else BorderHairline
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (hasCloudInvestigation) {
                                        stringResource(R.string.what_happened_title)
                                    } else {
                                        "Summary"
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (hasCloudInvestigation) BlueOnContainer else InkSecondary
                                )
                                Text(
                                    text = plainEnglishSummary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkPrimary
                                )
                            }
                        }

                        // Recommended actions (if returned by Cloud Run / Gemini)
                        val actions = runtimeState.latestCloudResult?.recommendedActions.orEmpty()
                        if (actions.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                actions.forEach { step ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.VerifiedUser,
                                            contentDescription = null,
                                            tint = EmeraldPrimary,
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .size(16.dp)
                                        )
                                        Text(
                                            text = step,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = InkPrimary
                                        )
                                    }
                                }
                            }
                        }

                        if (runtimeState.cloudError != null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = RoseContainer,
                                border = BorderStroke(1.dp, RoseBorder)
                            ) {
                                Text(
                                    text = runtimeState.cloudError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = RoseOnContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        // Primary Action: Check what happened -> Read-only Copy Reply + Mark Resolved
                        if (isResolved) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = EmeraldContainer,
                                border = BorderStroke(1.dp, EmeraldBorder)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = EmeraldPrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Marked resolved in EdgeOps",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = EmeraldOnContainer
                                        )
                                    }
                                    if (replyDraft.isNotBlank()) {
                                        Text(
                                            text = replyDraft,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = InkPrimary
                                        )
                                    }
                                }
                            }
                        } else if (!hasCloudInvestigation) {
                            Button(
                                onClick = { onCheckWhatHappened(incident) },
                                enabled = !runtimeState.isRunningCloudInvestigation,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("check_what_happened_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BluePrimary,
                                    contentColor = SurfaceWhite
                                )
                            ) {
                                if (runtimeState.isRunningCloudInvestigation) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = SurfaceWhite,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Checking...")
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.action_check_what_happened))
                                }
                            }
                        } else {
                            // Suggested Reply preview (Strictly read-only — Copy Reply to paste manually in Slack)
                            if (replyDraft.isNotBlank()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    color = CanvasLight,
                                    border = BorderStroke(1.dp, BorderHairline)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Suggested Reply (Read-Only)",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = InkSecondary
                                        )
                                        Text(
                                            text = replyDraft,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = InkPrimary
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (replyDraft.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            copyTextToClipboard(context, "EdgeOps Suggested Reply", replyDraft)
                                            copiedReply = true
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(50.dp)
                                            .testTag("copy_reply_button"),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, if (copiedReply) EmeraldBorder else BlueBorder)
                                    ) {
                                        Icon(
                                            imageVector = if (copiedReply) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                            contentDescription = null,
                                            tint = if (copiedReply) EmeraldPrimary else BluePrimary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (copiedReply) "Copied" else stringResource(R.string.action_copy_reply),
                                            color = if (copiedReply) EmeraldOnContainer else BlueOnContainer
                                        )
                                    }
                                }

                                Button(
                                    onClick = { onMarkResolved(incident, replyDraft) },
                                    enabled = !runtimeState.isResolving,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("mark_resolved_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EmeraldPrimary,
                                        contentColor = SurfaceWhite
                                    )
                                ) {
                                    if (runtimeState.isResolving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = SurfaceWhite,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.action_mark_resolved))
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                TextButton(
                                    onClick = { onCheckWhatHappened(incident) },
                                    enabled = !runtimeState.isRunningCloudInvestigation,
                                    modifier = Modifier.testTag("recheck_what_happened_button")
                                ) {
                                    Text(
                                        text = if (runtimeState.isRunningCloudInvestigation) "Checking..." else "Check again",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = BluePrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Single Compact Metadata Pill Strip (Replaces stacked bottom accordions)
            item(key = "compact_meta_strip") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val privacyOpen = activeMetaPanel == "privacy"
                    Surface(
                        onClick = { activeMetaPanel = if (privacyOpen) null else "privacy" },
                        shape = RoundedCornerShape(999.dp),
                        color = if (privacyOpen) EmeraldContainer else SurfaceWhite,
                        border = BorderStroke(1.dp, if (privacyOpen) EmeraldBorder else BorderHairline),
                        modifier = Modifier.testTag("chip_privacy_shield")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (privacyReport.redactions.isEmpty()) "Private" else "${privacyReport.redactions.size} hidden",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (privacyOpen) EmeraldOnContainer else InkPrimary
                            )
                        }
                    }

                    if (decisionScores != null) {
                        val priorityOpen = activeMetaPanel == "priority"
                        val effectiveRoute = decisionScores.effectiveRoute()
                        val isUrgent = decisionScores.priorityScore >= 85 || effectiveRoute == "page_eng"
                        Surface(
                            onClick = { activeMetaPanel = if (priorityOpen) null else "priority" },
                            shape = RoundedCornerShape(999.dp),
                            color = if (priorityOpen || isUrgent) {
                                if (isUrgent) RoseContainer else BlueContainer
                            } else {
                                SurfaceWhite
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isUrgent) RoseBorder else if (priorityOpen) BlueBorder else BorderHairline
                            ),
                            modifier = Modifier.testTag("chip_priority_triage")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = if (isUrgent) RoseOnContainer else BluePrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "${decisionScores.routeShortLabel()} · ${decisionScores.priorityScore}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isUrgent) RoseOnContainer else if (priorityOpen) BlueOnContainer else InkPrimary
                                )
                            }
                        }
                    }

                    if (occurrenceCount > 1) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = SurfaceWhite,
                            border = BorderStroke(1.dp, BorderHairline)
                        ) {
                            Text(
                                text = "×$occurrenceCount alerts",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily
                                ),
                                color = InkSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }

                    if (toolsToShow.isNotEmpty()) {
                        val toolsOpen = activeMetaPanel == "tools"
                        Surface(
                            onClick = { activeMetaPanel = if (toolsOpen) null else "tools" },
                            shape = RoundedCornerShape(999.dp),
                            color = if (toolsOpen) BlueContainer else SurfaceWhite,
                            border = BorderStroke(1.dp, if (toolsOpen) BlueBorder else BorderHairline),
                            modifier = Modifier.testTag("chip_tools_used")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Build,
                                    contentDescription = null,
                                    tint = BluePrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "${toolsToShow.size} tools",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (toolsOpen) BlueOnContainer else InkPrimary
                                )
                            }
                        }
                    }
                }
            }

            if (activeMetaPanel != null) {
                item(key = "active_meta_panel") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = BorderStroke(1.dp, BorderHairline),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Box(modifier = Modifier.padding(16.dp)) {
                            when (activeMetaPanel) {
                                "privacy" -> PrivacyShieldDiffContent(report = privacyReport)
                                "priority" -> decisionScores?.let { DecisionScoresContent(scores = it) }
                                "tools" -> RealToolsAccordionContent(
                                    toolsToShow = toolsToShow,
                                    selectedToolId = selectedToolId,
                                    onSelectTool = { id ->
                                        selectedToolId = if (selectedToolId == id) null else id
                                    },
                                    sourceLabel = runtimeState.latestCloudResult?.backendSourceLabel
                                )
                            }
                        }
                    }
                }
            }

            // 3. Ultra-Fast Streaming Triage Chat (Hybrid On-Device Nano / Streaming gemini-3.5-flash-lite)
            item(key = "triage_chat_card") {
                TriageChatCard(
                    messages = runtimeState.chatMessages,
                    isStreaming = runtimeState.isStreamingChat,
                    occurrenceCount = occurrenceCount,
                    inputText = chatInput,
                    onInputChange = { chatInput = it },
                    onSend = { prompt ->
                        onSendChat(incident, prompt)
                        chatInput = ""
                    },
                    onCopyMessage = { text ->
                        copyTextToClipboard(context, "EdgeOps Triage Reply", text)
                    }
                )
            }

            // 4. Quiet Secondary Controls
            if (!isResolved) {
                item(key = "secondary_actions") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onRerunLocalTriage(incident) },
                            enabled = !runtimeState.isRunningLocalTriage,
                            modifier = Modifier.testTag("rerun_nano_button")
                        ) {
                            if (runtimeState.isRunningLocalTriage) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = EmeraldPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = InkSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Re-run Nano",
                                style = MaterialTheme.typography.labelMedium,
                                color = InkSecondary
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        TextButton(
                            onClick = {
                                val currentlyNoise = incident.statusEnum() == IncidentStatus.HIDDEN_NOISE
                                onToggleNoise(incident, !currentlyNoise)
                            },
                            modifier = Modifier.testTag("toggle_noise_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.NotificationsOff,
                                contentDescription = null,
                                tint = InkSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (incident.statusEnum() == IncidentStatus.HIDDEN_NOISE) {
                                    "Restore"
                                } else {
                                    "Hide as noise"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = InkSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableDetailAccordion(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    summaryBadge: String,
    badgeBg: Color,
    badgeFg: Color,
    badgeBorder: Color,
    expanded: Boolean,
    testTag: String,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, BorderHairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag(testTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = InkPrimary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EdgeOpsPill(
                        text = summaryBadge,
                        containerColor = badgeBg,
                        contentColor = badgeFg,
                        borderColor = badgeBorder
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(R.string.cd_expand_section),
                        tint = InkMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = BorderHairline)
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacyShieldDiffContent(report: PrivacyShieldReport) {
    val beforeAnnotated = remember(report) {
        buildAnnotatedString {
            val raw = report.rawText
            if (report.redactions.isEmpty()) {
                append(raw)
            } else {
                var cursor = 0
                val matches = report.redactions.mapNotNull { entry ->
                    val idx = raw.indexOf(entry.originalValue, startIndex = 0)
                    if (idx >= 0) Triple(idx, idx + entry.originalValue.length, entry) else null
                }.sortedBy { it.first }

                for ((start, end, _) in matches) {
                    if (start < cursor) continue
                    append(raw.substring(cursor, start))
                    withStyle(
                        SpanStyle(
                            background = RoseContainer,
                            color = RoseOnContainer,
                            textDecoration = TextDecoration.LineThrough,
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append(raw.substring(start, end))
                    }
                    cursor = end
                }
                if (cursor < raw.length) append(raw.substring(cursor))
            }
        }
    }

    val afterAnnotated = remember(report) {
        buildAnnotatedString {
            val scrubbed = report.scrubbedText
            val tokenRegex = Regex("""\[Protected [A-Za-z ]+]""")
            var cursor = 0
            for (m in tokenRegex.findAll(scrubbed)) {
                append(scrubbed.substring(cursor, m.range.first))
                withStyle(
                    SpanStyle(
                        background = EmeraldContainer,
                        color = EmeraldOnContainer,
                        fontWeight = FontWeight.Bold,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                ) {
                    append(m.value)
                }
                cursor = m.range.last + 1
            }
            if (cursor < scrubbed.length) append(scrubbed.substring(cursor))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("privacy_shield_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Slack formatting is cleaned and sensitive details are scrubbed locally on-device before any cloud call.",
            style = MaterialTheme.typography.bodySmall,
            color = InkSecondary
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = CanvasLight,
            border = BorderStroke(1.dp, BorderHairline)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Before (Local Only)",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = RoseOnContainer
                )
                Text(
                    text = beforeAnnotated,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkPrimary
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = EmeraldContainer.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, EmeraldBorder)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "After (Safe for Cloud)",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = EmeraldOnContainer
                )
                Text(
                    text = afterAnnotated,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkPrimary
                )
            }
        }

        if (report.redactions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                report.redactions.forEach { entry ->
                    EdgeOpsPill(
                        text = "${entry.category} → ${entry.replacementToken}",
                        containerColor = SurfaceSubtle,
                        contentColor = EmeraldOnContainer,
                        borderColor = EmeraldBorder,
                        monospace = true
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RealToolsAccordionContent(
    toolsToShow: List<ToolExecutionResult>,
    selectedToolId: String?,
    onSelectTool: (String) -> Unit,
    sourceLabel: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!sourceLabel.isNullOrBlank()) {
            Text(
                text = sourceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = BlueOnContainer
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            toolsToShow.forEach { tool ->
                val isSelected = selectedToolId == tool.toolId
                EdgeOpsPill(
                    text = tool.toolId,
                    containerColor = if (isSelected) BluePrimary else BlueContainer,
                    contentColor = if (isSelected) SurfaceWhite else BlueOnContainer,
                    borderColor = BlueBorder,
                    icon = Icons.Rounded.Build,
                    monospace = true,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSelectTool(tool.toolId) }
                        .testTag("tool_chip_${tool.toolId}")
                )
            }
        }

        val activeTool = toolsToShow.firstOrNull { it.toolId == selectedToolId } ?: toolsToShow.firstOrNull()
        if (activeTool != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = CanvasLight,
                border = BorderStroke(1.dp, BorderHairline)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${activeTool.toolName} · ${activeTool.sourceLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = InkPrimary
                        )
                        if (activeTool.latencyMs > 0) {
                            Text(
                                text = "${activeTool.latencyMs}ms",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily
                                ),
                                color = InkMuted
                            )
                        }
                    }
                    Text(
                        text = activeTool.summaryOutput,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun DecisionScoresContent(scores: DecisionScores) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DecisionScoreBarRow(
            label = "Customer blocked",
            probability = scores.customerBlocked,
            accentColor = if (scores.customerBlocked >= 0.70) RoseOnContainer else BluePrimary
        )
        DecisionScoreBarRow(
            label = "Needs action",
            probability = scores.needsAction,
            accentColor = if (scores.needsAction >= 0.70) RoseOnContainer else BluePrimary
        )
        DecisionScoreBarRow(
            label = "Revenue / SLA risk",
            probability = scores.revenueRisk,
            accentColor = if (scores.revenueRisk >= 0.70) RoseOnContainer else BluePrimary
        )
        DecisionScoreBarRow(
            label = "Manual money move",
            probability = scores.manualMoneyMove,
            accentColor = if (scores.manualMoneyMove >= 0.65) RoseOnContainer else BluePrimary
        )
        DecisionScoreBarRow(
            label = "Engineering needed",
            probability = scores.needsEngineering,
            accentColor = BluePrimary
        )
        DecisionScoreBarRow(
            label = "Routine FYI",
            probability = scores.isNoise,
            accentColor = InkSecondary
        )
        HorizontalDivider(color = BorderHairline)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${scores.model} · ${scores.effectiveRoute()}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMonoFontFamily
                ),
                color = InkMuted
            )
            val latencyPrefix = scores.latencyMs?.let { ms ->
                if (ms >= 1000) String.format(Locale.US, "%.1f s · ", ms / 1000.0) else "$ms ms · "
            }.orEmpty()
            Text(
                text = "${latencyPrefix}${scores.outputTokens} output tokens",
                style = MaterialTheme.typography.labelSmall,
                color = InkMuted
            )
        }
    }
}

@Composable
private fun DecisionScoreBarRow(
    label: String,
    probability: Double,
    accentColor: Color
) {
    val pct = (probability.coerceIn(0.0, 1.0) * 100).toInt()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = InkPrimary
            )
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.SemiBold
                ),
                color = InkSecondary
            )
        }
        LinearProgressIndicator(
            progress = { probability.toFloat().coerceIn(0.02f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = accentColor,
            trackColor = SurfaceSubtle
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriageChatCard(
    messages: List<TriageChatMessage>,
    isStreaming: Boolean,
    occurrenceCount: Int,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onCopyMessage: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, BorderHairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Quick Triage Chat",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = InkPrimary
                    )
                }
                Text(
                    text = "Streaming · Private",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkMuted
                )
            }

            // 1-click prompt chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val quickPrompts = buildList {
                    add("Explain & next step")
                    add("Draft customer reply")
                    if (occurrenceCount > 1) add("Summarize all ×$occurrenceCount alerts")
                }
                quickPrompts.forEach { chipPrompt ->
                    Surface(
                        onClick = { if (!isStreaming) onSend(chipPrompt) },
                        shape = RoundedCornerShape(999.dp),
                        color = CanvasLight,
                        border = BorderStroke(1.dp, BorderHairline)
                    ) {
                        Text(
                            text = chipPrompt,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = InkPrimary,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (messages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    messages.forEach { msg ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = if (msg.isUser) BlueContainer else CanvasLight,
                            border = BorderStroke(1.dp, if (msg.isUser) BlueBorder else BorderHairline)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (msg.isUser) {
                                    Text(
                                        text = msg.text.ifBlank { "..." },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BlueOnContainer
                                    )
                                } else {
                                    Text(
                                        text = formatCopilotMarkdown(msg.text.ifBlank { "..." }),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = InkPrimary
                                    )
                                }
                                if (!msg.isUser) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = msg.sourceLabel.orEmpty(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = InkMuted
                                        )
                                        if (!msg.isStreaming && msg.text.isNotBlank()) {
                                            IconButton(
                                                onClick = { onCopyMessage(msg.text) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ContentCopy,
                                                    contentDescription = "Copy reply",
                                                    tint = InkSecondary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Ask anything about this alert...",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkMuted
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = InkPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BluePrimary,
                        unfocusedBorderColor = BorderHairline,
                        focusedContainerColor = CanvasLight,
                        unfocusedContainerColor = CanvasLight
                    )
                )
                IconButton(
                    onClick = { if (inputText.isNotBlank() && !isStreaming) onSend(inputText) },
                    enabled = inputText.isNotBlank() && !isStreaming,
                    modifier = Modifier.size(44.dp)
                ) {
                    if (isStreaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = BluePrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send",
                            tint = if (inputText.isNotBlank()) BluePrimary else InkMuted
                        )
                    }
                }
            }
        }
    }
}
