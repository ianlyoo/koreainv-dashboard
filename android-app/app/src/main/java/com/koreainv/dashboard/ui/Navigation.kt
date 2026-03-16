package com.koreainv.dashboard.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.AppCredentials
import com.koreainv.dashboard.network.KisRepository
import com.koreainv.dashboard.network.SettingsManager
import com.koreainv.dashboard.network.Trade
import com.koreainv.dashboard.ui.screens.AssetStatusScreen
import com.koreainv.dashboard.ui.screens.DashboardBottomTabBar
import com.koreainv.dashboard.ui.screens.DashboardTabItem
import com.koreainv.dashboard.ui.screens.HoldingDetailScreen
import com.koreainv.dashboard.ui.screens.PinUnlockScreen
import com.koreainv.dashboard.ui.screens.PortfolioScreen
import com.koreainv.dashboard.ui.screens.SetupScreen
import com.koreainv.dashboard.ui.screens.TradeDetailScreen
import com.koreainv.dashboard.ui.screens.TradeHistoryScreen
import com.koreainv.dashboard.ui.screens.TradeHistorySessionState
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.update.AppUpdateManager
import com.koreainv.dashboard.update.InstallPreparationResult
import com.koreainv.dashboard.update.ReleaseInfo
import com.koreainv.dashboard.update.ReleasePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Setup : Screen("setup")
    data object Unlock : Screen("unlock")
    data object Portfolio : Screen("portfolio")
    data object TradeHistory : Screen("trade_history")
    data object TradeDetail : Screen("trade_detail")
    data object AssetStatus : Screen("asset_status")
    data object HoldingDetail : Screen("holding_detail/{symbol}") {
        fun createRoute(symbol: String): String = "holding_detail/$symbol"
    }
}

@Composable
fun KoreaInvApp() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val updateManager = remember { AppUpdateManager() }
    var unlockedCredentials by remember { mutableStateOf<AppCredentials?>(null) }
    var availableUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var hasAutoCheckedUpdate by remember { mutableStateOf(false) }
    var selectedTrade by remember { mutableStateOf<Trade?>(null) }
    var selectedTradeUsdRate by remember { mutableStateOf(1350.0) }
    var selectedTradeLastSynced by remember { mutableStateOf<String?>(null) }
    var tradeHistorySessionState by remember { mutableStateOf(TradeHistorySessionState()) }
    val repository = remember(unlockedCredentials) { unlockedCredentials?.let { KisRepository(it) } }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val primaryTabs = listOf(
        DashboardTabItem(route = Screen.Portfolio.route, label = stringResource(R.string.portfolio)),
        DashboardTabItem(route = Screen.AssetStatus.route, label = stringResource(R.string.asset_status)),
        DashboardTabItem(route = Screen.TradeHistory.route, label = stringResource(R.string.trade_history_title)),
    )
    val primaryRoutes = remember(primaryTabs) { primaryTabs.map { it.route }.toSet() }

    DisposableEffect(repository) {
        onDispose {
            repository?.close()
        }
    }

    LaunchedEffect(unlockedCredentials) {
        if (unlockedCredentials == null || hasAutoCheckedUpdate) return@LaunchedEffect

        navController.currentBackStackEntryFlow
            .map { it.destination.route in primaryRoutes }
            .first { it }
        hasAutoCheckedUpdate = true
        delay(900L)
        availableUpdate = try {
            updateManager.checkForUpdate(context, includeRecommended = false) ?: run {
                delay(1200L)
                updateManager.checkForUpdate(context, includeRecommended = false)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun navigateToPrimaryTab(route: String) {
        navController.navigate(route) {
            launchSingleTop = true
            popUpTo(Screen.Portfolio.route) { inclusive = false }
        }
    }

    fun checkForUpdates() {
        scope.launch {
            isCheckingUpdate = true
            updateMessage = null
            availableUpdate = try {
                updateManager.checkForUpdate(context).also {
                    if (it == null) updateMessage = context.getString(R.string.update_up_to_date)
                }
            } catch (_: Exception) {
                updateMessage = context.getString(R.string.update_check_failed)
                null
            }
            isCheckingUpdate = false
        }
    }

    fun logout() {
        scope.launch {
            unlockedCredentials = null
            hasAutoCheckedUpdate = false
            selectedTrade = null
            selectedTradeUsdRate = 1350.0
            selectedTradeLastSynced = null
            tradeHistorySessionState = TradeHistorySessionState()
            navController.navigate(Screen.Unlock.route) {
                popUpTo(Screen.Portfolio.route) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Splash.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Screen.Splash.route) {
                    var isChecking by remember { mutableStateOf(true) }

                    LaunchedEffect(Unit) {
                        val isSetupComplete = settingsManager.isSetupCompleteFlow.first()
                        navController.navigate(if (isSetupComplete) Screen.Unlock.route else Screen.Setup.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                        isChecking = false
                    }

                    if (isChecking) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Background),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = TextGold)
                        }
                    }
                }

                composable(Screen.Setup.route) {
                    SetupScreen(
                        settingsManager = settingsManager,
                        onSetupSuccess = { credentials ->
                            unlockedCredentials = credentials
                            hasAutoCheckedUpdate = false
                            navController.navigate(Screen.Portfolio.route) {
                                popUpTo(Screen.Setup.route) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Screen.Unlock.route) {
                    var errorMessage by remember { mutableStateOf<String?>(null) }
                    var isUnlocking by remember { mutableStateOf(false) }

                    PinUnlockScreen(
                        errorMessage = errorMessage,
                        isLoading = isUnlocking,
                        onUnlock = { pin ->
                            scope.launch {
                                isUnlocking = true
                                val credentials = settingsManager.unlock(pin)
                                if (credentials != null) {
                                    unlockedCredentials = credentials
                                    hasAutoCheckedUpdate = false
                                    errorMessage = null
                                    navController.navigate(Screen.Portfolio.route) {
                                        popUpTo(Screen.Unlock.route) { inclusive = true }
                                    }
                                } else {
                                    errorMessage = context.getString(R.string.invalid_pin)
                                }
                                isUnlocking = false
                            }
                        },
                    )
                }

                composable(Screen.Portfolio.route) {
                    val activeRepository = repository
                    if (activeRepository == null) {
                        LaunchedEffect(Unit) {
                            navController.navigate(Screen.Unlock.route) {
                                popUpTo(Screen.Portfolio.route) { inclusive = true }
                            }
                        }
                    } else {
                        PortfolioScreen(
                            repository = activeRepository,
                            onCheckUpdatesClick = ::checkForUpdates,
                            onLogoutClick = ::logout,
                            onHoldingClick = { symbol -> navController.navigate(Screen.HoldingDetail.createRoute(symbol)) },
                        )
                    }
                }

                composable(Screen.AssetStatus.route) {
                    val activeRepository = repository
                    if (activeRepository == null) {
                        LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                    } else {
                        AssetStatusScreen(
                            repository = activeRepository,
                            onCheckUpdatesClick = ::checkForUpdates,
                            onLogoutClick = ::logout,
                        )
                    }
                }

                composable(Screen.TradeHistory.route) {
                    val activeRepository = repository
                    if (activeRepository == null) {
                        LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                    } else {
                        TradeHistoryScreen(
                            repository = activeRepository,
                            onCheckUpdatesClick = ::checkForUpdates,
                            onLogoutClick = ::logout,
                            sessionState = tradeHistorySessionState,
                            onSessionStateChange = { tradeHistorySessionState = it },
                            onTradeClick = { trade, usdRate, lastSynced ->
                                selectedTrade = trade
                                selectedTradeUsdRate = usdRate
                                selectedTradeLastSynced = lastSynced
                                navController.navigate(Screen.TradeDetail.route)
                            },
                        )
                    }
                }

                composable(Screen.TradeDetail.route) {
                    val trade = selectedTrade
                    if (trade == null) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        TradeDetailScreen(
                            trade = trade,
                            usdRate = selectedTradeUsdRate,
                            lastSynced = selectedTradeLastSynced,
                            onBackClick = { navController.popBackStack() },
                        )
                    }
                }

                composable(
                    route = Screen.HoldingDetail.route,
                    arguments = listOf(navArgument("symbol") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val activeRepository = repository
                    val symbol = backStackEntry.arguments?.getString("symbol")
                    if (activeRepository == null || symbol.isNullOrBlank()) {
                        LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                    } else {
                        HoldingDetailScreen(
                            repository = activeRepository,
                            symbol = symbol,
                            onBackClick = { navController.popBackStack() },
                        )
                    }
                }
            }
        }

        if (unlockedCredentials != null && currentRoute in primaryRoutes) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                DashboardBottomTabBar(
                    items = primaryTabs,
                    currentRoute = currentRoute,
                    onTabSelected = { navigateToPrimaryTab(it.route) },
                )
            }
        }
    }

    if (availableUpdate != null) {
        val release = availableUpdate!!
        val isMandatoryUpdate = release.policy == ReleasePolicy.MANDATORY
        AlertDialog(
            onDismissRequest = {
                if (!isMandatoryUpdate) {
                    availableUpdate = null
                }
            },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Text(
                    if (isMandatoryUpdate) {
                        "필수 업데이트 ${release.tagName}을 다운로드해야 계속 사용할 수 있습니다."
                    } else {
                        "새 버전 ${release.tagName}을 다운로드할 수 있습니다."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isDownloadingUpdate = true
                            var launchedInstaller = false
                            try {
                                when (val result = updateManager.downloadUpdate(context, release)) {
                                    InstallPreparationResult.PermissionRequired -> {
                                        updateManager.requestInstallPermission(context)
                                        updateMessage = context.getString(R.string.install_permission_required)
                                    }

                                    is InstallPreparationResult.Ready -> {
                                        updateManager.launchInstaller(context, result.apkFile)
                                        launchedInstaller = true
                                    }
                                }
                            } catch (_: Exception) {
                                updateMessage = context.getString(R.string.download_update_failed)
                            }
                            isDownloadingUpdate = false
                            if (shouldCloseUpdateDialog(release.policy, launchedInstaller)) {
                                availableUpdate = null
                            }
                        }
                    },
                ) { Text(stringResource(R.string.update_now)) }
            },
            dismissButton = if (isMandatoryUpdate) {
                null
            } else {
                {
                    TextButton(onClick = { availableUpdate = null }) {
                        Text(stringResource(R.string.later))
                    }
                }
            },
        )
    }

    if (updateMessage != null) {
        AlertDialog(
            onDismissRequest = { updateMessage = null },
            title = { Text(stringResource(R.string.check_for_updates)) },
            text = { Text(updateMessage!!) },
            confirmButton = {
                TextButton(onClick = { updateMessage = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (isCheckingUpdate || isDownloadingUpdate) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = TextGold)
        }
    }
}

internal fun shouldCloseUpdateDialog(policy: ReleasePolicy, launchedInstaller: Boolean): Boolean {
    return launchedInstaller || policy != ReleasePolicy.MANDATORY
}
