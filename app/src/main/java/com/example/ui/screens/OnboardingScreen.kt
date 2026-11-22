package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
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
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SurfaceSubtle
import com.example.ui.theme.SurfaceWhite

fun buildSlackManifestYaml(orgName: String, webhookUrl: String): String {
    val safeDisplayName = orgName.ifBlank { "EdgeOps" }.take(35)
    return """
display_information:
  name: EdgeOps ($safeDisplayName)
  description: Read-only local-first Slack incident triage
features:
  bot_user:
    display_name: EdgeOps
    always_online: true
oauth_config:
  scopes:
    bot:
      - channels:history
      - channels:read
      - app_mentions:read
settings:
  event_subscriptions:
    request_url: $webhookUrl
    bot_events:
      - app_mention
      - message.channels
  org_deploy_enabled: false
  socket_mode_enabled: false
    """.trimIndent()
}

internal fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
fun OnboardingScreen(
    isOnline: Boolean,
    isBusy: Boolean,
    isCheckingSlack: Boolean,
    onCreateWorkspace: (orgName: String) -> Unit,
    onRecheckSlackConnection: () -> Unit,
    onSignOut: () -> Unit
) {
    var orgName by rememberSaveable { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasLight)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = InkPrimary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TopBarStatusPill(isOnline = isOnline)
                    IconButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("onboarding_sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = stringResource(R.string.action_sign_out),
                            tint = InkSecondary
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = BorderStroke(1.dp, BorderHairline),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.onboarding_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = InkPrimary
                        )
                        Text(
                            text = "Name your workspace to start syncing Slack alerts privately on-device. If Slack is already connected on EdgeOps Web, tap Check Slack Connection below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkSecondary
                        )
                    }

                    OutlinedTextField(
                        value = orgName,
                        onValueChange = { orgName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_org_name_input"),
                        label = { Text("Workspace name") },
                        placeholder = { Text("e.g. Northstar Ops") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BluePrimary,
                            unfocusedBorderColor = BorderHairline,
                            focusedContainerColor = CanvasLight,
                            unfocusedContainerColor = CanvasLight
                        )
                    )

                    Button(
                        onClick = { onCreateWorkspace(orgName) },
                        enabled = !isBusy && orgName.trim().isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("onboarding_continue_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BluePrimary,
                            contentColor = SurfaceWhite
                        )
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = SurfaceWhite,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Creating workspace...")
                        } else {
                            Text("Continue to Inbox", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    OutlinedButton(
                        onClick = onRecheckSlackConnection,
                        enabled = !isCheckingSlack && !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("onboarding_check_slack_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, BlueBorder)
                    ) {
                        if (isCheckingSlack) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = BluePrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking Slack...", color = BlueOnContainer)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = BluePrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Check Slack Connection", color = BlueOnContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CopyableCodeBox(
    label: String,
    value: String,
    copied: Boolean,
    singleLinePreview: Boolean,
    testTag: String,
    onCopy: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = InkPrimary
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (copied) EmeraldContainer else BlueContainer,
                border = BorderStroke(1.dp, if (copied) EmeraldBorder else BlueBorder),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onCopy)
                    .testTag(testTag)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy),
                        tint = if (copied) EmeraldOnContainer else BlueOnContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (copied) EmeraldOnContainer else BlueOnContainer
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = SurfaceSubtle,
            border = BorderStroke(1.dp, BorderHairline)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFontFamily
                ),
                color = InkPrimary,
                maxLines = if (singleLinePreview) 2 else 9,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
