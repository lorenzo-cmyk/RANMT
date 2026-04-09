package dev.ranmt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.ranmt.ui.MainViewModel
import dev.ranmt.ui.screens.HistoryScreen
import dev.ranmt.ui.screens.NewMeasurementScreen
import dev.ranmt.ui.screens.RunningScreen
import dev.ranmt.ui.screens.SettingsScreen
import dev.ranmt.ui.screens.SessionDetailScreen
import dev.ranmt.ui.screens.saveFileToDownloads
import dev.ranmt.ui.screens.shareFile
import dev.ranmt.ui.theme.RANMTTheme

@Composable
fun RANMTApp(openRunning: Boolean = false) {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()
    val bottomBarRoutes = setOf(AppDestination.History.route, AppDestination.NewMeasurement.route)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current
    val runningState by viewModel.runningState.collectAsState()

    LaunchedEffect(runningState.sessionId, viewModel.hasActiveSession, openRunning) {
        if (viewModel.suppressAutoNavigate && runningState.sessionId == null && !viewModel.hasActiveSession) {
            viewModel.clearSuppressAutoNavigate()
        }
        if (!viewModel.suppressAutoNavigate && (runningState.sessionId != null || viewModel.hasActiveSession)) {
            if (currentRoute != AppDestination.Running.route) {
                navController.navigate(AppDestination.Running.route) {
                    launchSingleTop = true
                    popUpTo(AppDestination.History.route)
                }
            }
        }
        if (openRunning && currentRoute != AppDestination.Running.route) {
            navController.navigate(AppDestination.Running.route) {
                launchSingleTop = true
                popUpTo(AppDestination.History.route)
            }
        }
        if (runningState.sessionId == null && !viewModel.hasActiveSession) {
            viewModel.refreshActiveSession()
            viewModel.refreshSessions()
        }
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute == AppDestination.History.route || currentRoute == AppDestination.NewMeasurement.route) {
            viewModel.refreshActiveSession()
            viewModel.refreshSessions()
        }
    }

    RANMTTheme {
        Scaffold(
            bottomBar = {
                if (currentRoute in bottomBarRoutes) {
                    RANMTBottomBar(navController)
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    )
                    .padding(padding)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = AppDestination.History.route
                ) {
                    composable(AppDestination.History.route) {
                        HistoryScreen(
                            sessions = viewModel.sessions,
                            activeSessionId = viewModel.activeSessionId,
                            onOpen = { id ->
                                if (id == viewModel.activeSessionId) {
                                    navController.navigate(AppDestination.Running.route) {
                                        launchSingleTop = true
                                        popUpTo(AppDestination.History.route)
                                    }
                                } else {
                                    viewModel.loadDetail(id)
                                    navController.navigate("details/$id")
                                }
                            },
                            onDelete = { id -> viewModel.deleteSession(id) },
                            onOpenSettings = { navController.navigate(AppDestination.Settings.route) },
                            onRefresh = {
                                viewModel.refreshActiveSession()
                                viewModel.refreshSessions()
                            }
                        )
                    }
                    composable(AppDestination.NewMeasurement.route) {
                        NewMeasurementScreen(
                            config = viewModel.config,
                            onConfigChange = viewModel::updateConfig,
                            allowStart = !viewModel.hasActiveSession,
                            onStart = {
                                viewModel.startMeasurement()
                                navController.navigate(AppDestination.Running.route)
                            }
                        )
                    }
                    composable(AppDestination.Running.route) {
                        RunningScreen(
                            config = viewModel.config,
                            runningState = runningState,
                            onStop = {
                                viewModel.stopMeasurement()
                                navController.popBackStack(AppDestination.History.route, false)
                            }
                        )
                    }
                    composable(AppDestination.SessionDetail.route) { backStack ->
                        val id = backStack.arguments?.getString("id")
                        if (id != null) {
                            val detail = viewModel.selectedDetail
                            if (detail == null || detail.summary.id != id) {
                                viewModel.loadDetail(id)
                            }
                            SessionDetailScreen(
                                detail = viewModel.selectedDetail,
                                onBack = {
                                    viewModel.clearDetail()
                                    navController.popBackStack()
                                },
                                sessionFileSize = viewModel.sessionFileSize(id),
                                onExport = { format, destination ->
                                    val file = viewModel.exportSession(id, format)
                                    if (file != null) {
                                        when (destination) {
                                            dev.ranmt.data.ExportDestination.Share -> shareFile(context, file)
                                            dev.ranmt.data.ExportDestination.Downloads -> saveFileToDownloads(context, file)
                                        }
                                    }
                                }
                            )
                        }
                    }
                    composable(AppDestination.Settings.route) {
                        SettingsScreen(
                            settings = viewModel.settings,
                            onUpdate = viewModel::updateSettings,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
