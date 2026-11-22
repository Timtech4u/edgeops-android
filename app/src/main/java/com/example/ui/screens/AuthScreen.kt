package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.firebase.attemptAutoSignIn
import com.example.firebase.onGoogleSignInClicked
import com.example.ui.components.TopBarStatusPill
import com.example.ui.theme.BluePrimary
import com.example.ui.theme.BorderHairline
import com.example.ui.theme.CanvasLight
import com.example.ui.theme.EmeraldBorder
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.RoseBorder
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseOnContainer
import com.example.ui.theme.SurfaceWhite

@Composable
fun AuthScreen(
    isOnline: Boolean,
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = { /* Wait for interactive button tap */ },
            scope = scope
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasLight)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = EmeraldContainer,
                        border = BorderStroke(1.dp, EmeraldBorder),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = stringResource(R.string.cd_privacy_shield),
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = InkPrimary
                    )
                }

                TopBarStatusPill(isOnline = isOnline)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = BorderStroke(1.dp, BorderHairline),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Image(
                        painter = painterResource(id = R.drawable.img_edgeops_hero_1795260060244),
                        contentDescription = stringResource(R.string.cd_hero_illustration),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(188.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Calm incident triage that protects customer privacy first",
                        style = MaterialTheme.typography.titleLarge,
                        color = InkPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "On-device Gemini Nano filters alert noise and redacts emails & account IDs locally before Cloud Gemini ever investigates.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FeatureHighlightRow(
                        icon = Icons.Rounded.Lock,
                        title = "Local Privacy Shield",
                        subtitle = "Scrubs emails, phones, and IDs on-device before any cloud check."
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    FeatureHighlightRow(
                        icon = Icons.Rounded.CloudQueue,
                        title = "Read-Only Slack Sync",
                        subtitle = "Pulls live alerts from your Slack channels without ever posting automatically."
                    )
                }
            }

            if (errorMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = RoseContainer,
                    border = BorderStroke(1.dp, RoseBorder)
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = RoseOnContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Button(
                onClick = {
                    errorMessage = null
                    isLoading = true
                    onGoogleSignInClicked(
                        context = context,
                        credentialManager = credentialManager,
                        onAuthSuccess = {
                            isLoading = false
                            onAuthSuccess()
                        },
                        onAuthError = { msg ->
                            isLoading = false
                            errorMessage = msg
                        },
                        scope = scope,
                        onAuthCancelled = {
                            isLoading = false
                        }
                    )
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("google_sign_in_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BluePrimary,
                    contentColor = SurfaceWhite
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SurfaceWhite,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Signing in...", style = MaterialTheme.typography.labelLarge)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.action_sign_in_google),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Text(
                text = "Your incidents sync securely via Cloud Firestore with offline persistence enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )
        }
    }
}

@Composable
private fun FeatureHighlightRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = EmeraldContainer,
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = InkPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )
        }
    }
}
