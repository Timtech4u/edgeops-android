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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.CloudRunInvestigatorClient
import com.example.data.Connector
import com.example.data.Organization
import com.example.data.SlackChannelInfo
import com.example.data.SlackWorkspaceStatus
import com.example.data.UiState
import com.example.data.UserSettings
import com.example.ui.components.EdgeOpsPill
import com.example.ui.components.TopBarStatusPill
import com.example.ui.theme.AmberBorder
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberOnContainer
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
import com.example.ui.theme.SurfaceSubtle
import com.example.ui.theme.SurfaceWhite

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectorsScreen(
    settings: UserSettings,
    organization: Organization?,
    slackStatusState: UiState<SlackWorkspaceStatus>,
    slackChannels: List<SlackChannelInfo>,
    connectorsState: UiState<List<Connector>>,
    isOnline: Boolean,
    isSyncingSlack: Boolean,
    onSyncSlack: () -> Unit,
    onConnectMcp: (name: String, url: String, toolsCsv: String) -> Unit,
    onImportOpenApi: (rawJson: String) -> Unit,
    onToggleConnector: (Connector, Boolean) -> Unit,
    onDeleteConnector: (Connector) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val customConnectors = (connectorsState as? UiState.Success)?.data.orEmpty()
    val liveSlack = (slackStatusState as? UiState.Success)?.data

    var showMcpDialog by remember { mutableStateOf(false) }
    var showOpenApiDialog by remember { mutableStateOf(false) }
    var showManifest by remember { mutableStateOf(false) }
    var copiedKey by remember { mutableStateOf<String?>(null) }

    val resolvedOrgId = liveSlack?.orgId?.takeIf { it.isNotBlank() }
        ?: organization?.id?.takeIf { it.isNotBlank() }
        ?: settings.orgId?.takeIf { it.isNotBlank() }
    val workspaceName = liveSlack?.teamName?.takeIf { it.isNotBlank() }
        ?: organization?.orgName?.takeIf { it.isNotBlank() }
        ?: settings.orgName?.takeIf { it.isNotBlank() }
        ?: "Slack Workspace"
    val eventsWebhookUrl = resolvedOrgId?.let {
        "${CloudRunInvestigatorClient.DEFAULT_BASE_URL}/webhooks/slack/$it"
    }
    val manifestYaml = remember(workspaceName, eventsWebhookUrl) {
        buildSlackManifestYaml(
            workspaceName,
            eventsWebhookUrl ?: "${CloudRunInvestigatorClient.DEFAULT_BASE_URL}/webhooks/slack/your-org"
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CanvasLight,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.connections_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = InkPrimary
                    )
                },
                actions = {
                    TopBarStatusPill(
                        isOnline = isOnline,
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Live Slack Status & Synced Channels Card (Read-Only)
            item(key = "slack_status") {
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Forum,
                                    contentDescription = null,
                                    tint = BluePrimary,
                                    modifier = Modifier.size(19.dp)
                                )
                                Column {
                                    Text(
                                        text = workspaceName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = InkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val botLabel = liveSlack?.botName?.takeIf { it.isNotBlank() }
                                    Text(
                                        text = if (botLabel != null) {
                                            "Bot: $botLabel · Read-only sync"
                                        } else {
                                            "Read-only Slack sync"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = InkMuted
                                    )
                                }
                            }

                            when {
                                slackStatusState is UiState.Loading -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = BluePrimary
                                    )
                                }
                                liveSlack?.connected == true -> {
                                    EdgeOpsPill(
                                        text = "Connected",
                                        containerColor = EmeraldContainer,
                                        contentColor = EmeraldOnContainer,
                                        borderColor = EmeraldBorder
                                    )
                                }
                                else -> {
                                    EdgeOpsPill(
                                        text = "Not Connected",
                                        containerColor = AmberContainer,
                                        contentColor = AmberOnContainer,
                                        borderColor = AmberBorder
                                    )
                                }
                            }
                        }

                        // Synced Channels List from GET /api/slack/channels?orgId=...
                        if (slackChannels.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Synced Channels (${slackChannels.size})",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = InkSecondary
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    slackChannels.forEach { channel ->
                                        EdgeOpsPill(
                                            text = channel.name,
                                            containerColor = SurfaceSubtle,
                                            contentColor = InkPrimary,
                                            borderColor = BorderHairline,
                                            icon = if (channel.isPrivate) Icons.Rounded.Lock else null,
                                            monospace = true
                                        )
                                    }
                                }
                            }
                        } else if (liveSlack?.connected == true) {
                            Text(
                                text = "Invite the EdgeOps bot to any Slack channel to include it in read-only sync.",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        } else if (slackStatusState is UiState.Error) {
                            Text(
                                text = slackStatusState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }

                        OutlinedButton(
                            onClick = onSyncSlack,
                            enabled = !isSyncingSlack,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("connections_sync_slack_button"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BlueBorder)
                        ) {
                            if (isSyncingSlack) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = BluePrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Syncing Slack...", color = BlueOnContainer)
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = BluePrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sync Channels & Messages", color = BlueOnContainer)
                            }
                        }

                        if (eventsWebhookUrl != null) {
                            HorizontalDivider(color = BorderHairline)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showManifest = !showManifest }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Slack Webhook & Manifest",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = BluePrimary
                                )
                                Icon(
                                    imageVector = if (showManifest) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = stringResource(R.string.cd_expand_section),
                                    tint = BluePrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            AnimatedVisibility(visible = showManifest) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CopyableCodeBox(
                                        label = "Events Webhook URL",
                                        value = eventsWebhookUrl,
                                        copied = copiedKey == "webhook",
                                        singleLinePreview = true,
                                        testTag = "settings_copy_webhook_button",
                                        onCopy = {
                                            copyTextToClipboard(context, "EdgeOps Webhook URL", eventsWebhookUrl)
                                            copiedKey = "webhook"
                                        }
                                    )
                                    CopyableCodeBox(
                                        label = "slack-manifest.yaml",
                                        value = manifestYaml,
                                        copied = copiedKey == "manifest",
                                        singleLinePreview = false,
                                        testTag = "settings_copy_manifest_button",
                                        onCopy = {
                                            copyTextToClipboard(context, "slack-manifest.yaml", manifestYaml)
                                            copiedKey = "manifest"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Remote MCP & OpenAPI Connections
            item(key = "connect_actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ConnectorActionCard(
                        title = "Remote MCP",
                        subtitle = "Add MCP Server URL",
                        icon = Icons.Rounded.Api,
                        buttonLabel = "Add Server",
                        testTag = "open_mcp_dialog_button",
                        onClick = { showMcpDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    ConnectorActionCard(
                        title = "OpenAPI JSON",
                        subtitle = "Import 3.x spec",
                        icon = Icons.Rounded.Code,
                        buttonLabel = "Import Spec",
                        testTag = "open_openapi_dialog_button",
                        onClick = { showOpenApiDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (customConnectors.isNotEmpty()) {
                item(key = "custom_header") {
                    Text(
                        text = "Connected MCP & OpenAPI Sources (${customConnectors.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = InkSecondary
                    )
                }
                items(customConnectors, key = { it.id }) { connector ->
                    CustomConnectorCard(
                        connector = connector,
                        onToggle = { enabled -> onToggleConnector(connector, enabled) },
                        onDelete = { onDeleteConnector(connector) }
                    )
                }
            }
        }
    }

    if (showMcpDialog) {
        ConnectMcpDialog(
            onDismiss = { showMcpDialog = false },
            onConfirm = { name, url, toolsCsv ->
                onConnectMcp(name, url, toolsCsv)
                showMcpDialog = false
            }
        )
    }

    if (showOpenApiDialog) {
        ImportOpenApiDialog(
            onDismiss = { showOpenApiDialog = false },
            onConfirm = { json ->
                onImportOpenApi(json)
                showOpenApiDialog = false
            }
        )
    }
}

@Composable
private fun ConnectorActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    buttonLabel: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, BorderHairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BlueContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = InkPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag(testTag),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BlueContainer,
                    contentColor = BlueOnContainer
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(buttonLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomConnectorCard(
    connector: Connector,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, BorderHairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = connector.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = InkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        EdgeOpsPill(
                            text = if (connector.connectorType == "MCP_SERVER") "MCP" else "OpenAPI",
                            containerColor = BlueContainer,
                            contentColor = BlueOnContainer,
                            borderColor = BlueBorder
                        )
                    }
                    Text(
                        text = connector.endpointUrl,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = connector.enabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SurfaceWhite,
                            checkedTrackColor = EmeraldPrimary
                        )
                    )
                    IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete connection",
                            tint = InkMuted
                        )
                    }
                }
            }
            if (connector.toolNames.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    connector.toolNames.take(5).forEach { toolName ->
                        EdgeOpsPill(
                            text = toolName,
                            containerColor = SurfaceSubtle,
                            contentColor = InkSecondary,
                            borderColor = BorderHairline,
                            monospace = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectMcpDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var toolsCsv by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        title = { Text("Connect Remote MCP Server", color = InkPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Register your remote Model Context Protocol endpoint URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    placeholder = { Text("e.g. Internal Ops MCP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("MCP Server URL") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = toolsCsv,
                    onValueChange = { toolsCsv = it },
                    label = { Text("Tool Names (optional, comma-separated)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, url, toolsCsv) },
                enabled = name.isNotBlank() && url.startsWith("http"),
                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary)
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = InkSecondary)
            }
        }
    )
}

@Composable
private fun ImportOpenApiDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var rawJson by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceWhite,
        title = { Text("Import OpenAPI JSON Spec", color = InkPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste your OpenAPI 3.x JSON specification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
                OutlinedTextField(
                    value = rawJson,
                    onValueChange = { rawJson = it },
                    label = { Text("OpenAPI JSON") },
                    placeholder = { Text("{ \"openapi\": \"3.0.3\", \"paths\": { ... } }") },
                    minLines = 6,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFontFamily
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(rawJson) },
                enabled = rawJson.trim().startsWith("{"),
                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary)
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = InkSecondary)
            }
        }
    )
}
