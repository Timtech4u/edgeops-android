package com.example

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ai.HybridAiService
import com.example.data.CloudRunInvestigatorClient
import com.example.data.EdgeOpsRepository
import com.example.data.NetworkMonitor
import com.example.data.UiState
import com.example.data.UserSettings
import com.example.firebase.authStateFlow
import com.example.firebase.configureAppCheck
import com.example.firebase.signOutUser
import com.example.notifications.EdgeOpsNotificationHelper
import com.example.ui.components.GlobalCopilotFab
import com.example.ui.components.GlobalCopilotSheet
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ConnectorsScreen
import com.example.ui.screens.InboxScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.TicketDetailScreen
import com.example.ui.theme.BlueContainer
import com.example.ui.theme.BlueOnContainer
import com.example.ui.theme.BluePrimary
import com.example.ui.theme.CanvasLight
import com.example.ui.theme.EdgeOpsTheme
import com.example.ui.theme.InkMuted
import com.example.ui.theme.SurfaceWhite
import com.example.viewmodel.DetailRuntimeState
import com.example.viewmodel.EdgeOpsViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

const val ROUTE_INBOX = "inbox"
const val ROUTE_CONNECTIONS = "connections"
const val ROUTE_DETAIL = "detail/{incidentId}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1 (MANDATORY): Configure Firebase App Check with launch intent before any UI or ViewModels
        configureAppCheck(this, intent)
        EdgeOpsNotificationHelper.ensureChannel(this)

        // Strict crisp light-mode system bars
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        setContent {
            EdgeOpsTheme {
                EdgeOpsRoot()
            }
        }
    }
}

@Composable
private fun EdgeOpsRoot() {
    val context = LocalContext.current
    val networkMonitor = remember(context) { NetworkMonitor(context.applicationContext) }
    val isOnline by networkMonitor.observeOnlineStatus()
        .collectAsStateWithLifecycle(initialValue = networkMonitor.isCurrentlyOnline())

    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val user = currentUser
    if (user == null) {
        AuthScreen(
            isOnline = isOnline,
            onAuthSuccess = { /* authStateFlow updates automatically */ }
        )
    } else {
        key(user.uid) {
            AuthenticatedEdgeOpsApp(currentUserId = user.uid)
        }
    }
}

@Composable
private fun AuthenticatedEdgeOpsApp(
    currentUserId: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: EdgeOpsViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val repo = EdgeOpsRepository(app.applicationContext)
                val cloudRunClient = CloudRunInvestigatorClient()
                val aiService = HybridAiService(cloudRunClient)
                val monitor = NetworkMonitor(app.applicationContext)
                EdgeOpsViewModel(
                    repository = repo,
                    aiService = aiService,
                    cloudRunClient = cloudRunClient,
                    networkMonitor = monitor,
                    currentUserId = currentUserId
                )
            }
        }
    )

    val settingsUiState by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val organization by viewModel.organizationState.collectAsStateWithLifecycle()
    val slackStatusState by viewModel.slackStatus.collectAsStateWithLifecycle()
    val slackChannels by viewModel.slackChannels.collectAsStateWithLifecycle()
    val isSyncingSlack by viewModel.isSyncingSlack.collectAsStateWithLifecycle()
    val isOnboardingBusy by viewModel.isOnboardingBusy.collectAsStateWithLifecycle()
    val incidentsState by viewModel.incidentsState.collectAsStateWithLifecycle()
    val connectorsState by viewModel.connectorsState.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val detailStates by viewModel.detailStates.collectAsStateWithLifecycle()
    val decisionScores by viewModel.decisionScores.collectAsStateWithLifecycle()
    val globalChatMessages by viewModel.globalChatMessages.collectAsStateWithLifecycle()
    val unreadProactiveCount by viewModel.unreadProactiveCount.collectAsStateWithLifecycle()
    val isGlobalChatStreaming by viewModel.isGlobalChatStreaming.collectAsStateWithLifecycle()
    val proactiveNotificationEvent by viewModel.proactiveNotificationEvent.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    var showGlobalCopilot by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(EdgeOpsNotificationHelper.hasPermission(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsEnabled = granted
    }

    LaunchedEffect(proactiveNotificationEvent, notificationsEnabled) {
        val event = proactiveNotificationEvent
        if (event != null) {
            if (notificationsEnabled && EdgeOpsNotificationHelper.hasPermission(context)) {
                EdgeOpsNotificationHelper.showProactiveAlert(
                    context = context,
                    title = event.first,
                    body = event.second,
                    notificationId = (System.currentTimeMillis() % 10000).toInt()
                )
            }
            viewModel.consumeProactiveNotification()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    val loadedSettings: UserSettings? = when (val state = settingsUiState) {
        is UiState.Loading -> null
        is UiState.Success -> state.data
        is UiState.Error -> UserSettings(id = currentUserId, userId = currentUserId)
    }

    val isLiveSlackConnected = (slackStatusState as? UiState.Success)?.data?.connected == true
    val waitingOnInitialSlackCheck = loadedSettings?.needsOnboarding() == true &&
        slackStatusState is UiState.Loading &&
        isOnline

    if (loadedSettings == null || waitingOnInitialSlackCheck) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CanvasLight),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = BluePrimary, strokeWidth = 2.5.dp)
        }
        return
    }

    // Auto-skip Onboarding straight into Inbox whenever Cloud Run already reports a connected Slack workspace
    if (loadedSettings.needsOnboarding() && !isLiveSlackConnected) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = CanvasLight,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                OnboardingScreen(
                    isOnline = isOnline,
                    isBusy = isOnboardingBusy,
                    isCheckingSlack = isSyncingSlack,
                    onCreateWorkspace = viewModel::createWorkspaceAndEnter,
                    onRecheckSlackConnection = { viewModel.syncSlackFromBackend(userInitiated = true) },
                    onSignOut = {
                        signOutUser(
                            credentialManager = credentialManager,
                            scope = scope
                        )
                    }
                )
            }
        }
        return
    }

    val settings = loadedSettings
    val activeWorkspaceName = (slackStatusState as? UiState.Success)?.data?.teamName?.takeIf { it.isNotBlank() }
        ?: organization?.orgName?.takeIf { it.isNotBlank() }
        ?: settings.orgName?.takeIf { it.isNotBlank() }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = currentRoute == ROUTE_INBOX || currentRoute == ROUTE_CONNECTIONS

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = CanvasLight,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                GlobalCopilotFab(
                    unreadCount = unreadProactiveCount,
                    onClick = {
                        viewModel.markGlobalCopilotRead()
                        showGlobalCopilot = true
                    }
                )
            },
            bottomBar = {
                if (!useRail && isTopLevel) {
                    NavigationBar(
                        containerColor = SurfaceWhite,
                        tonalElevation = 0.dp,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_INBOX,
                            onClick = { navigateTopLevel(ROUTE_INBOX) },
                            modifier = Modifier.testTag("nav_inbox"),
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_INBOX) Icons.Rounded.Inbox else Icons.Outlined.Inbox,
                                    contentDescription = stringResource(R.string.tab_inbox)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_inbox)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BluePrimary,
                                selectedTextColor = BlueOnContainer,
                                indicatorColor = BlueContainer,
                                unselectedIconColor = InkMuted,
                                unselectedTextColor = InkMuted
                            )
                        )
                        NavigationBarItem(
                            selected = currentRoute == ROUTE_CONNECTIONS,
                            onClick = { navigateTopLevel(ROUTE_CONNECTIONS) },
                            modifier = Modifier.testTag("nav_connections"),
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_CONNECTIONS) Icons.Rounded.Hub else Icons.Outlined.Hub,
                                    contentDescription = stringResource(R.string.tab_connections)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_connections)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BluePrimary,
                                selectedTextColor = BlueOnContainer,
                                indicatorColor = BlueContainer,
                                unselectedIconColor = InkMuted,
                                unselectedTextColor = InkMuted
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail && isTopLevel) {
                    NavigationRail(containerColor = SurfaceWhite) {
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_INBOX,
                            onClick = { navigateTopLevel(ROUTE_INBOX) },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_INBOX) Icons.Rounded.Inbox else Icons.Outlined.Inbox,
                                    contentDescription = stringResource(R.string.tab_inbox)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_inbox)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = BluePrimary,
                                selectedTextColor = BlueOnContainer,
                                indicatorColor = BlueContainer,
                                unselectedIconColor = InkMuted,
                                unselectedTextColor = InkMuted
                            )
                        )
                        NavigationRailItem(
                            selected = currentRoute == ROUTE_CONNECTIONS,
                            onClick = { navigateTopLevel(ROUTE_CONNECTIONS) },
                            icon = {
                                Icon(
                                    imageVector = if (currentRoute == ROUTE_CONNECTIONS) Icons.Rounded.Hub else Icons.Outlined.Hub,
                                    contentDescription = stringResource(R.string.tab_connections)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_connections)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = BluePrimary,
                                selectedTextColor = BlueOnContainer,
                                indicatorColor = BlueContainer,
                                unselectedIconColor = InkMuted,
                                unselectedTextColor = InkMuted
                            )
                        )
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_INBOX,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 840.dp)
                ) {
                    composable(ROUTE_INBOX) {
                        InboxScreen(
                            incidentsState = incidentsState,
                            decisionScores = decisionScores,
                            selectedFilter = selectedFilter,
                            workspaceName = activeWorkspaceName,
                            isOnline = isOnline,
                            isSyncing = isSyncingSlack,
                            onSelectFilter = viewModel::selectFilter,
                            onSyncSlack = { viewModel.syncSlackFromBackend(userInitiated = true) },
                            onOpenIncident = { id ->
                                navController.navigate("detail/$id")
                            },
                            onSignOut = {
                                signOutUser(
                                    credentialManager = credentialManager,
                                    scope = scope
                                )
                            }
                        )
                    }

                    composable(ROUTE_CONNECTIONS) {
                        ConnectorsScreen(
                            settings = settings,
                            organization = organization,
                            slackStatusState = slackStatusState,
                            slackChannels = slackChannels,
                            connectorsState = connectorsState,
                            isOnline = isOnline,
                            isSyncingSlack = isSyncingSlack,
                            onSyncSlack = { viewModel.syncSlackFromBackend(userInitiated = true) },
                            onConnectMcp = viewModel::connectMcpServer,
                            onImportOpenApi = viewModel::importOpenApiSpec,
                            onToggleConnector = viewModel::toggleConnector,
                            onDeleteConnector = viewModel::deleteConnector
                        )
                    }

                    composable(
                        route = ROUTE_DETAIL,
                        arguments = listOf(navArgument("incidentId") { type = NavType.StringType })
                    ) { entry ->
                        val incidentId = entry.arguments?.getString("incidentId").orEmpty()
                        val incidents = (incidentsState as? UiState.Success)?.data.orEmpty()
                        val incident = incidents.firstOrNull { it.id == incidentId }
                        val occurrenceCount = remember(incident, incidents) {
                            if (incident == null) 1 else {
                                val title = com.example.ai.LocalPrivacyShield.deriveCleanTitle(
                                    incident.title,
                                    incident.rawMessage,
                                    incident.channel
                                )
                                val key = com.example.data.IncidentGrouper.buildKey(incident, title)
                                incidents.count { other ->
                                    val otherTitle = com.example.ai.LocalPrivacyShield.deriveCleanTitle(
                                        other.title,
                                        other.rawMessage,
                                        other.channel
                                    )
                                    com.example.data.IncidentGrouper.buildKey(other, otherTitle) == key
                                }.coerceAtLeast(1)
                            }
                        }
                        val detailState = detailStates[incidentId] ?: DetailRuntimeState()
                        val savedTools = remember(incident) {
                            if (incident != null && incident.toolsUsed.isNotEmpty()) {
                                viewModel.savedToolsForIncident(incident)
                            } else {
                                emptyList()
                            }
                        }

                        TicketDetailScreen(
                            incident = incident,
                            occurrenceCount = occurrenceCount,
                            decisionScores = decisionScores[incidentId],
                            runtimeState = detailState,
                            savedToolResults = savedTools,
                            isOnline = isOnline,
                            onBack = { navController.popBackStack() },
                            onRerunLocalTriage = viewModel::rerunLocalTriage,
                            onToggleNoise = viewModel::toggleNoiseStatus,
                            onCheckWhatHappened = viewModel::checkWhatHappened,
                            onSendChat = { inc, prompt ->
                                viewModel.sendTriageChat(inc, occurrenceCount, prompt)
                            },
                            onMarkResolved = viewModel::markIncidentResolved
                        )
                    }
                }
            }
        }

        if (showGlobalCopilot) {
            val totalTracked = (incidentsState as? UiState.Success)?.data?.size ?: 0
            val wsLabel = activeWorkspaceName ?: "CalmDuty"
            GlobalCopilotSheet(
                workspaceName = wsLabel,
                trackedCount = totalTracked,
                messages = globalChatMessages,
                isStreaming = isGlobalChatStreaming,
                notificationsEnabled = notificationsEnabled,
                onDismiss = { showGlobalCopilot = false },
                onRequestWindowBriefing = {
                    viewModel.requestExecutiveBriefing(wsLabel)
                },
                onSendPrompt = { prompt ->
                    viewModel.sendGlobalCopilotPrompt(wsLabel, prompt)
                },
                onToggleNotifications = {
                    if (!EdgeOpsNotificationHelper.hasPermission(context) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notificationsEnabled = !notificationsEnabled
                    }
                },
                onTestNotification = {
                    if (!EdgeOpsNotificationHelper.hasPermission(context) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        notificationsEnabled = true
                        EdgeOpsNotificationHelper.showProactiveAlert(
                            context = context,
                            title = "$wsLabel · CalmDuty Copilot",
                            body = "Proactive alerts active — watching $totalTracked live Slack threads privately on your Pixel.",
                            notificationId = 2026
                        )
                    }
                }
            )
        }
    }
}
