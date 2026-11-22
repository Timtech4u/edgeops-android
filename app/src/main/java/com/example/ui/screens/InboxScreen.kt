package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.LocalPrivacyShield
import com.example.data.DecisionScores
import com.example.data.InboxFilter
import com.example.data.Incident
import com.example.data.IncidentGroup
import com.example.data.IncidentGrouper
import com.example.data.IncidentStatus
import com.example.data.UiState
import com.example.ui.components.TopBarStatusPill
import com.example.ui.theme.BlueBorder
import com.example.ui.theme.BlueContainer
import com.example.ui.theme.BlueOnContainer
import com.example.ui.theme.BluePrimary
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CanvasLight
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoseBorder
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseOnContainer
import com.example.ui.theme.SurfaceWhite
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    incidentsState: UiState<List<Incident>>,
    decisionScores: Map<String, DecisionScores>,
    selectedFilter: InboxFilter,
    workspaceName: String?,
    isOnline: Boolean,
    isSyncing: Boolean,
    onSelectFilter: (InboxFilter) -> Unit,
    onSyncSlack: () -> Unit,
    onOpenIncident: (String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allIncidents = (incidentsState as? UiState.Success)?.data.orEmpty()
    val needsActionCount = remember(allIncidents) {
        allIncidents.count {
            it.statusEnum() == IncidentStatus.NEEDS_ACTION || it.statusEnum() == IncidentStatus.INVESTIGATED
        }
    }
    val hiddenNoiseCount = remember(allIncidents) {
        allIncidents.count { it.statusEnum() == IncidentStatus.HIDDEN_NOISE }
    }
    val groupedIncidents = remember(allIncidents, selectedFilter, decisionScores) {
        val base = when (selectedFilter) {
            InboxFilter.NEEDS_ACTION -> allIncidents.filter {
                it.statusEnum() == IncidentStatus.NEEDS_ACTION || it.statusEnum() == IncidentStatus.INVESTIGATED
            }
            InboxFilter.HIDDEN_NOISE -> allIncidents.filter {
                it.statusEnum() == IncidentStatus.HIDDEN_NOISE
            }
            InboxFilter.ALL -> allIncidents
        }
        val byKey = LinkedHashMap<String, MutableList<Incident>>()
        for (inc in base) {
            val clean = LocalPrivacyShield.deriveCleanTitle(inc.title, inc.rawMessage, inc.channel)
            val key = IncidentGrouper.buildKey(inc, clean)
            byKey.getOrPut(key) { mutableListOf() }.add(inc)
        }
        val groups = byKey.entries.map { (key, list) ->
            val sorted = list.sortedByDescending { decisionScores[it.id]?.priorityScore ?: 0 }
            val primary = sorted.first()
            IncidentGroup(
                groupKey = key,
                primary = primary,
                occurrences = sorted,
                scores = decisionScores[primary.id]
            )
        }
        if (selectedFilter == InboxFilter.NEEDS_ACTION) {
            groups.sortedByDescending { it.scores?.priorityScore ?: 0 }
        } else {
            groups
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CanvasLight,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = workspaceName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = InkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    TopBarStatusPill(isOnline = isOnline)
                    IconButton(
                        onClick = onSyncSlack,
                        enabled = !isSyncing,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("sync_slack_top_button")
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = BluePrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.cd_sync_slack),
                                tint = InkSecondary
                            )
                        }
                    }
                    IconButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = stringResource(R.string.action_sign_out),
                            tint = InkSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CanvasLight,
                    scrolledContainerColor = SurfaceWhite
                )
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = onSyncSlack,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "filters") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InboxFilter.entries.forEach { filter ->
                            val selected = selectedFilter == filter
                            val count = when (filter) {
                                InboxFilter.NEEDS_ACTION -> needsActionCount
                                InboxFilter.HIDDEN_NOISE -> hiddenNoiseCount
                                InboxFilter.ALL -> allIncidents.size
                            }
                            val label = if (count > 0) "${filter.label} · $count" else filter.label
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectFilter(filter) },
                                modifier = Modifier.testTag("filter_${filter.name.lowercase()}"),
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BlueContainer,
                                    selectedLabelColor = BlueOnContainer,
                                    containerColor = SurfaceWhite,
                                    labelColor = InkSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = BorderHairline,
                                    selectedBorderColor = BlueBorder
                                )
                            )
                        }
                    }
                }

                when (incidentsState) {
                    is UiState.Loading -> {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 56.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = BluePrimary, strokeWidth = 2.5.dp)
                            }
                        }
                    }
                    is UiState.Error -> {
                        item(key = "error") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = RoseContainer,
                                border = BorderStroke(1.dp, RoseBorder)
                            ) {
                                Text(
                                    text = incidentsState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = RoseOnContainer,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                    is UiState.Success -> {
                        if (groupedIncidents.isEmpty()) {
                            item(key = "empty") {
                                CalmEmptyInboxCard(
                                    selectedFilter = selectedFilter,
                                    hasAnyIncidents = allIncidents.isNotEmpty(),
                                    isSyncing = isSyncing,
                                    onSyncSlack = onSyncSlack,
                                    onShowAll = { onSelectFilter(InboxFilter.ALL) }
                                )
                            }
                        } else {
                            items(groupedIncidents, key = { it.primary.id }) { group ->
                                CalmIncidentCard(
                                    incident = group.primary,
                                    occurrenceCount = group.count,
                                    scores = group.scores,
                                    onClick = { onOpenIncident(group.primary.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalmIncidentCard(
    incident: Incident,
    occurrenceCount: Int,
    scores: DecisionScores?,
    onClick: () -> Unit
) {
    val timeLabel = remember(incident.createdAt) {
        val date = incident.createdAt?.toDate()
        if (date != null) {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        } else {
            "Just now"
        }
    }
    val cleanTitle = remember(incident.title, incident.rawMessage, incident.channel) {
        LocalPrivacyShield.deriveCleanTitle(incident.title, incident.rawMessage, incident.channel)
    }
    val cleanPreview = remember(incident.scrubbedMessage, incident.triageSummary, incident.rawMessage) {
        val source = incident.scrubbedMessage.ifBlank { incident.triageSummary.ifBlank { incident.rawMessage } }
        LocalPrivacyShield.redactLocally(source).scrubbedText
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    val isResolved = incident.statusEnum() == IncidentStatus.RESOLVED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("incident_card_${incident.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, BorderHairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
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
                        color = InkSecondary,
                        maxLines = 1
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkMuted
                    )
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = InkMuted,
                        maxLines = 1
                    )
                    if (occurrenceCount > 1) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = CanvasLight,
                            border = BorderStroke(1.dp, BorderHairline)
                        ) {
                            Text(
                                text = "×$occurrenceCount",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = InkSecondary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                    }
                    if (scores != null && !isResolved && scores.bucket == "needs_action" && scores.priorityScore >= 55) {
                        val isUrgent = scores.priorityScore >= 85
                        Spacer(modifier = Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (isUrgent) RoseContainer else BlueContainer,
                            border = BorderStroke(1.dp, if (isUrgent) RoseBorder else BlueBorder)
                        ) {
                            Text(
                                text = if (isUrgent) "Urgent · ${scores.priorityScore}%" else "Action · ${scores.priorityScore}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isUrgent) RoseOnContainer else BlueOnContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                    }
                    if (isResolved) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Resolved",
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Text(
                    text = cleanTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = InkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = cleanPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = InkMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CalmEmptyInboxCard(
    selectedFilter: InboxFilter,
    hasAnyIncidents: Boolean,
    isSyncing: Boolean,
    onSyncSlack: () -> Unit,
    onShowAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, BorderHairline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_inbox_calm_1795260059645),
                contentDescription = stringResource(R.string.cd_empty_inbox),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Fit
            )
            Text(
                text = if (!hasAnyIncidents) {
                    "Your inbox is clear"
                } else {
                    "Nothing in ${selectedFilter.label}"
                },
                style = MaterialTheme.typography.titleMedium,
                color = InkPrimary
            )
            Text(
                text = if (!hasAnyIncidents) {
                    "Pull down or tap Sync Slack to fetch live messages from your connected workspace."
                } else {
                    "Switch to All to review resolved or hidden messages."
                },
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (!hasAnyIncidents) {
                Button(
                    onClick = onSyncSlack,
                    enabled = !isSyncing,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BluePrimary,
                        contentColor = SurfaceWhite
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("empty_sync_slack_button")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = SurfaceWhite
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing...")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_sync_slack))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = onShowAll,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderHairline),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Show All Messages", color = InkPrimary)
                }
            }
        }
    }
}
