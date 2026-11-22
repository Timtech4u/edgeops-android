package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.data.TriageChatMessage
import com.example.ui.theme.BlueBorder
import com.example.ui.theme.BlueContainer
import com.example.ui.theme.BlueOnContainer
import com.example.ui.theme.BluePrimary
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CanvasLight
import com.example.ui.theme.EmeraldBorder
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldOnContainer
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.RoseOnContainer
import com.example.ui.theme.SurfaceWhite

@Composable
fun GlobalCopilotFab(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BadgedBox(
        modifier = modifier,
        badge = {
            if (unreadCount > 0) {
                Badge(
                    containerColor = RoseOnContainer,
                    contentColor = SurfaceWhite
                ) {
                    Text(
                        text = if (unreadCount > 9) "9+" else "$unreadCount",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    ) {
        FloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = BluePrimary,
            contentColor = SurfaceWhite,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
            modifier = Modifier.testTag("global_copilot_fab")
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = "Open CalmDuty Copilot",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GlobalCopilotSheet(
    workspaceName: String,
    trackedCount: Int,
    messages: List<TriageChatMessage>,
    isStreaming: Boolean,
    notificationsEnabled: Boolean,
    onDismiss: () -> Unit,
    onRequestWindowBriefing: () -> Unit,
    onSendPrompt: (String) -> Unit,
    onToggleNotifications: () -> Unit,
    onTestNotification: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var inputText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceWhite,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = BlueContainer,
                        border = BorderStroke(1.dp, BlueBorder)
                    ) {
                        Box(
                            modifier = Modifier.size(34.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = BluePrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "CalmDuty Copilot",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = InkPrimary
                        )
                        Text(
                            text = "$workspaceName · Watching $trackedCount alerts privately",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkSecondary
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        onClick = onTestNotification,
                        shape = RoundedCornerShape(999.dp),
                        color = CanvasLight,
                        border = BorderStroke(1.dp, BorderHairline),
                        modifier = Modifier.testTag("test_notification_chip")
                    ) {
                        Text(
                            text = "Test alert",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = InkPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    IconButton(
                        onClick = onToggleNotifications,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("toggle_notifications_button")
                    ) {
                        Icon(
                            imageVector = if (notificationsEnabled) {
                                Icons.Rounded.NotificationsActive
                            } else {
                                Icons.Rounded.NotificationsNone
                            },
                            contentDescription = "Toggle notifications",
                            tint = if (notificationsEnabled) BluePrimary else InkMuted
                        )
                    }
                }
            }

            HorizontalDivider(color = BorderHairline)

            // 1-click workspace prompt chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    onClick = { if (!isStreaming) onRequestWindowBriefing() },
                    shape = RoundedCornerShape(999.dp),
                    color = BlueContainer,
                    border = BorderStroke(1.dp, BlueBorder),
                    modifier = Modifier.testTag("window_briefing_chip")
                ) {
                    Text(
                        text = "Window briefing",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = BlueOnContainer,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                    )
                }
                listOf(
                    "Summarize urgent fires",
                    "What's blocking revenue?",
                    "Draft team standup"
                ).forEach { chip ->
                    Surface(
                        onClick = { if (!isStreaming) onSendPrompt(chip) },
                        shape = RoundedCornerShape(999.dp),
                        color = CanvasLight,
                        border = BorderStroke(1.dp, BorderHairline)
                    ) {
                        Text(
                            text = chip,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = InkPrimary,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Messages list (proactive briefings + interactive streaming replies)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 360.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    val isProactive = !msg.isUser && msg.sourceLabel?.contains("Proactive", ignoreCase = true) == true
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = when {
                            msg.isUser -> BlueContainer
                            isProactive -> EmeraldContainer.copy(alpha = 0.55f)
                            else -> CanvasLight
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                msg.isUser -> BlueBorder
                                isProactive -> EmeraldBorder
                                else -> BorderHairline
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(13.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (isProactive) {
                                Text(
                                    text = "PROACTIVE BRIEFING",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = EmeraldOnContainer
                                )
                            }
                            Text(
                                text = formatCopilotMarkdown(msg.text.ifBlank { "..." }),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (msg.isUser) BlueOnContainer else InkPrimary
                            )
                            if (!msg.isUser && !msg.sourceLabel.isNullOrBlank()) {
                                Text(
                                    text = msg.sourceLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkMuted
                                )
                            }
                        }
                    }
                }
            }

            // Streaming chat input bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Ask across all $workspaceName channels...",
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
                    onClick = {
                        if (inputText.isNotBlank() && !isStreaming) {
                            onSendPrompt(inputText)
                            inputText = ""
                        }
                    },
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
            Spacer(modifier = Modifier.size(2.dp))
        }
    }
}

internal fun formatCopilotMarkdown(raw: String): AnnotatedString {
    val cleaned = raw.replace(Regex("(?m)^\\s*\\*\\s+"), "• ")
    val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
    return buildAnnotatedString {
        var cursor = 0
        for (match in boldRegex.findAll(cleaned)) {
            if (match.range.first > cursor) {
                append(cleaned.substring(cursor, match.range.first))
            }
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(match.groupValues[1])
            }
            cursor = match.range.last + 1
        }
        if (cursor < cleaned.length) {
            append(cleaned.substring(cursor))
        }
    }
}
