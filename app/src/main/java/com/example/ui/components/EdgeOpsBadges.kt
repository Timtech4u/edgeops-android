package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.IncidentSeverity
import com.example.data.IncidentStatus
import com.example.data.InferenceSourceType
import com.example.ui.theme.AmberBorder
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberOnContainer
import com.example.ui.theme.BlueBorder
import com.example.ui.theme.BlueContainer
import com.example.ui.theme.BlueOnContainer
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.EmeraldBorder
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldOnContainer
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SurfaceSubtle
import com.example.ui.theme.TealBorder
import com.example.ui.theme.TealContainer
import com.example.ui.theme.TealAccent

@Composable
fun EdgeOpsPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    monospace: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = if (monospace) {
                    MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                },
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun InferenceModeBadge(
    isCloudInvestigation: Boolean,
    modifier: Modifier = Modifier
) {
    if (isCloudInvestigation) {
        EdgeOpsPill(
            text = stringResource(R.string.badge_cloud_investigation),
            containerColor = BlueContainer,
            contentColor = BlueOnContainer,
            borderColor = BlueBorder,
            icon = Icons.Rounded.CloudQueue,
            contentDescription = stringResource(R.string.cd_cloud_investigation),
            modifier = modifier
        )
    } else {
        EdgeOpsPill(
            text = stringResource(R.string.badge_on_device),
            containerColor = EmeraldContainer,
            contentColor = EmeraldOnContainer,
            borderColor = EmeraldBorder,
            icon = Icons.Rounded.Shield,
            contentDescription = stringResource(R.string.cd_privacy_shield),
            modifier = modifier
        )
    }
}

@Composable
fun InferenceSourcePill(
    source: InferenceSourceType,
    modifier: Modifier = Modifier
) {
    InferenceModeBadge(
        isCloudInvestigation = source.isCloud,
        modifier = modifier
    )
}

@Composable
fun WorksOfflinePill(
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    val label = if (isOnline) {
        stringResource(R.string.badge_works_offline)
    } else {
        stringResource(R.string.badge_offline_active)
    }
    val container = if (isOnline) TealContainer else AmberContainer
    val ink = if (isOnline) TealAccent else AmberOnContainer
    val border = if (isOnline) TealBorder else AmberBorder
    val icon = if (isOnline) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff

    EdgeOpsPill(
        text = label,
        containerColor = container,
        contentColor = ink,
        borderColor = border,
        icon = icon,
        contentDescription = stringResource(R.string.cd_offline_status),
        modifier = modifier
    )
}

@Composable
fun TopBarStatusPill(
    isOnline: Boolean,
    isCloudInvestigation: Boolean = false,
    modifier: Modifier = Modifier
) {
    when {
        !isOnline -> {
            EdgeOpsPill(
                text = stringResource(R.string.badge_works_offline),
                containerColor = AmberContainer,
                contentColor = AmberOnContainer,
                borderColor = AmberBorder,
                icon = Icons.Outlined.CloudOff,
                contentDescription = stringResource(R.string.cd_offline_status),
                modifier = modifier
            )
        }
        isCloudInvestigation -> {
            EdgeOpsPill(
                text = stringResource(R.string.badge_cloud_investigation),
                containerColor = BlueContainer,
                contentColor = BlueOnContainer,
                borderColor = BlueBorder,
                icon = Icons.Rounded.CloudQueue,
                contentDescription = stringResource(R.string.cd_cloud_investigation),
                modifier = modifier
            )
        }
        else -> {
            EdgeOpsPill(
                text = stringResource(R.string.badge_on_device_compact),
                containerColor = EmeraldContainer,
                contentColor = EmeraldOnContainer,
                borderColor = EmeraldBorder,
                icon = Icons.Rounded.Shield,
                contentDescription = stringResource(R.string.cd_privacy_shield),
                modifier = modifier
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HybridStatusBannerRow(
    isCloudInvestigation: Boolean,
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        InferenceModeBadge(isCloudInvestigation = isCloudInvestigation)
        WorksOfflinePill(isOnline = isOnline)
    }
}

@Composable
fun ChannelTagPill(
    channel: String,
    modifier: Modifier = Modifier
) {
    EdgeOpsPill(
        text = channel,
        containerColor = SurfaceSubtle,
        contentColor = InkSecondary,
        borderColor = BorderHairline,
        monospace = true,
        modifier = modifier
    )
}

@Composable
fun IncidentStatusPill(
    status: IncidentStatus,
    severity: IncidentSeverity,
    modifier: Modifier = Modifier
) {
    val (bg, fg, border, icon) = when (status) {
        IncidentStatus.RESOLVED -> Quad(
            EmeraldContainer,
            EmeraldOnContainer,
            EmeraldBorder,
            Icons.Rounded.CheckCircle
        )
        IncidentStatus.INVESTIGATED -> Quad(
            BlueContainer,
            BlueOnContainer,
            BlueBorder,
            Icons.Rounded.AutoAwesome
        )
        IncidentStatus.HIDDEN_NOISE -> Quad(
            SurfaceSubtle,
            InkMuted,
            BorderHairline,
            Icons.Rounded.NotificationsOff
        )
        IncidentStatus.NEEDS_ACTION -> {
            if (severity == IncidentSeverity.HIGH) {
                Quad(AmberContainer, AmberOnContainer, AmberBorder, Icons.Rounded.PriorityHigh)
            } else {
                Quad(BlueContainer, BlueOnContainer, BlueBorder, Icons.Outlined.Shield)
            }
        }
    }
    EdgeOpsPill(
        text = status.displayName,
        containerColor = bg,
        contentColor = fg,
        borderColor = border,
        icon = icon,
        modifier = modifier
    )
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
