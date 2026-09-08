package com.koreainv.dashboard.ui

import android.net.Uri
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Settings
import com.koreainv.dashboard.ui.screens.DashboardIcons
import androidx.compose.material.icons.Icons
import com.koreainv.dashboard.ui.appearance.ThemeMode
import com.koreainv.dashboard.ui.screens.SettingsScreen
import com.koreainv.dashboard.ui.screens.DashboardGlassHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavHostController
import com.koreainv.dashboard.ui.screens.LoginBackdrop
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.AccountProfile
import com.koreainv.dashboard.network.KisRepository
import com.koreainv.dashboard.network.SettingsManager
import com.koreainv.dashboard.network.Holding
import com.koreainv.dashboard.network.insight.InsightSessionManager
import com.koreainv.dashboard.network.insight.SaveTickerInsightRepository
import com.koreainv.dashboard.ui.screens.StockInsightScreen
import com.koreainv.dashboard.ui.screens.InsightConnectionScreen
import com.koreainv.dashboard.network.Trade
import com.koreainv.dashboard.ui.screens.AssetStatusScreen
import com.koreainv.dashboard.ui.screens.AccountManagementScreen
import com.koreainv.dashboard.ui.screens.DashboardBottomTabBar
import com.koreainv.dashboard.ui.screens.DashboardTabItem
import com.koreainv.dashboard.ui.screens.HoldingDetailScreen
import com.koreainv.dashboard.ui.screens.HoldingAccountFilter
import com.koreainv.dashboard.ui.screens.LocalCurrencyPreference
import com.koreainv.dashboard.ui.screens.LocalDashboardBottomBarHeight
import com.koreainv.dashboard.ui.screens.rememberCurrencyPreference
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
import kotlinx.coroutines.cancelChildren

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Setup : Screen("setup")
    data object Unlock : Screen("unlock")
    data object Portfolio : Screen("portfolio")
    data object TradeHistory : Screen("trade_history")
    data object TradeDetail : Screen("trade_detail")
    data object AssetStatus : Screen("asset_status")
    data object Settings : Screen("settings")
    data object AccountManagement : Screen("account_management")
    data object InsightConnection : Screen("insight_connection?required={required}") {
        fun createRoute(required: Boolean = false) = "insight_connection?required=$required"
    }
    data object StockInsight : Screen("stock_insight/{symbol}?market={market}&name={name}") {
        fun createRoute(symbol: String, market: String, name: String) =
            "stock_insight/${Uri.encode(symbol)}?market=${Uri.encode(market)}&name=${Uri.encode(name)}"
    }
    data object HoldingDetail : Screen("holding_detail/{symbol}?accountId={accountId}") {
        fun createRoute(symbol: String, accountId: String?): String =
            "holding_detail/${Uri.encode(symbol)}?accountId=${Uri.encode(accountId.orEmpty())}"
    }
}

@Composable
fun KoreaInvApp(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val backgroundEntry by navController.currentBackStackEntryAsState()
    val currencyPreference = rememberCurrencyPreference()
    val bottomBarHeight = remember { mutableStateOf(116.dp) }
    CompositionLocalProvider(
        LocalCurrencyPreference provides currencyPreference,
        LocalDashboardBottomBarHeight provides bottomBarHeight,
    ) {
        DashboardGlassHost(background = {
            if (backgroundEntry?.destination?.route == Screen.Unlock.route) LoginBackdrop()
        }) {
            KoreaInvAppContent(themeMode, onThemeModeChange, navController)
        }
    }
}

@Composable
private fun KoreaInvAppContent(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    navController: NavHostController,
) {
    val scope = rememberCoroutineScope()
    val credentialScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = remember(context) { context.activityOrNull()?.lifecycle }
    val performance = LocalDevicePerformancePolicy.current
    val insightSession = remember(context) { InsightSessionManager.getInstance(context.applicationContext) }
    val insightRepository = remember(insightSession) {
        SaveTickerInsightRepository(insightSession, maxCachedStocks = if (performance.liveGlass) 8 else 4)
    }
    val insightConnection by insightSession.state.collectAsState()
    var sessionRevision by remember { mutableLongStateOf(0L) }
    var pendingInsightRoute by remember { mutableStateOf<String?>(null) }
    val upToDateText = stringResource(R.string.update_up_to_date)
    val updateFailedText = stringResource(R.string.update_check_failed)
    val invalidPinText = stringResource(R.string.invalid_pin)
    val installPermissionText = stringResource(R.string.install_permission_required)
    val downloadFailedText = stringResource(R.string.download_update_failed)
    val settingsManager = remember { SettingsManager(context) }
    val updateManager = remember { AppUpdateManager() }
    var unlockedProfile by remember { mutableStateOf<AccountProfile?>(null) }
    var availableUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var hasAutoCheckedUpdate by remember { mutableStateOf(false) }
    var selectedTrade by remember { mutableStateOf<Trade?>(null) }
    var selectedTradeUsdRate by remember { mutableStateOf(1350.0) }
    var selectedTradeLastSynced by remember { mutableStateOf<String?>(null) }
    var tradeHistorySessionState by remember { mutableStateOf(TradeHistorySessionState()) }
    var isSavingAccounts by remember { mutableStateOf(false) }
    var accountManagementError by remember { mutableStateOf<String?>(null) }
    val repository = remember(unlockedProfile) {
        unlockedProfile?.let { profile ->
            KisRepository(profile.accounts, settingsManager, maxConcurrentAccounts = if (performance.liveGlass) 3 else 2)
        }
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val primaryTabs = listOf(
        DashboardTabItem(route = Screen.Portfolio.route, label = stringResource(R.string.portfolio), icon = DashboardIcons.Portfolio),
        DashboardTabItem(route = Screen.AssetStatus.route, label = stringResource(R.string.asset_status), icon = DashboardIcons.Assets),
        DashboardTabItem(route = Screen.TradeHistory.route, label = stringResource(R.string.trade_history_title), icon = DashboardIcons.Trades),
        DashboardTabItem(route = Screen.Settings.route, label = "설정", icon = Icons.Outlined.Settings),
    )
    val primaryRoutes = remember(primaryTabs) { primaryTabs.map { it.route }.toSet() }

    DisposableEffect(repository) {
        onDispose {
            repository?.close()
        }
    }

    LaunchedEffect(unlockedProfile) {
        if (unlockedProfile == null || hasAutoCheckedUpdate) return@LaunchedEffect

        navController.currentBackStackEntryFlow
            .map { it.destination.route in primaryRoutes }
            .first { it }
        hasAutoCheckedUpdate = true
        delay(900L)
        if (isCheckingUpdate || isDownloadingUpdate || availableUpdate != null) return@LaunchedEffect
        isCheckingUpdate = true
        try {
            availableUpdate = updateManager.checkForUpdate(context, includeRecommended = false) ?: run {
                delay(1200L)
                updateManager.checkForUpdate(context, includeRecommended = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Automatic checks stay quiet; Settings provides the explicit retry action.
        } finally {
            isCheckingUpdate = false
        }
    }

    fun navigateToPrimaryTab(route: String) {
        if (navController.currentDestination?.route == route) return
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
            popUpTo(Screen.Portfolio.route) { saveState = true }
        }
    }

    fun checkForUpdates() {
        if (isCheckingUpdate || isDownloadingUpdate || availableUpdate != null) return
        isCheckingUpdate = true
        updateMessage = null
        scope.launch {
            try {
                availableUpdate = updateManager.checkForUpdate(context).also {
                    if (it == null) updateMessage = upToDateText
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateMessage = updateFailedText
            } finally {
                isCheckingUpdate = false
            }
        }
    }

    suspend fun prepareInsightSession() {
        try {
            insightSession.unlock()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional market insights must never block access to the broker vault.
            insightSession.lock()
            updateMessage = "종목 인사이트 연결 저장소를 열지 못했습니다. 계좌는 사용할 수 있으며, 설정에서 연결을 다시 확인해 주세요."
        }
    }

    fun logout() {
            sessionRevision++
            credentialScope.coroutineContext.cancelChildren()
            insightSession.lock()
            repository?.close()
            unlockedProfile = null
            pendingInsightRoute = null
            hasAutoCheckedUpdate = false
            selectedTrade = null
            selectedTradeUsdRate = 1350.0
            selectedTradeLastSynced = null
            tradeHistorySessionState = TradeHistorySessionState()
            if (navController.currentDestination != null) navController.navigate(Screen.Unlock.route) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
    }

    val backgroundLock by rememberUpdatedState {
        // Invalidate even a PIN operation that has not published an unlocked profile yet.
        if (unlockedProfile != null) logout() else {
            sessionRevision++
            credentialScope.coroutineContext.cancelChildren()
            insightSession.lock()
        }
    }
    DisposableEffect(lifecycle, insightRepository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) backgroundLock()
        }
        lifecycle?.addObserver(observer)
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = insightRepository.trimCache()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) insightRepository.trimCache()
            }
        }
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        onDispose {
            lifecycle?.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            insightSession.lock()
            insightRepository.close()
        }
    }

    fun openInsight(holding: Holding) {
        val route = Screen.StockInsight.createRoute(holding.symbol, holding.market, holding.name)
        if (holding.market == "USA" && !insightConnection.hasCredentials) {
            pendingInsightRoute = route
            navController.navigate(Screen.InsightConnection.createRoute(required = true)) { launchSingleTop = true }
        } else {
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Splash.route,
                enterTransition = { DashboardNavigationMotion.enter(initialState.destination.route, targetState.destination.route) },
                exitTransition = { DashboardNavigationMotion.exit(initialState.destination.route, targetState.destination.route) },
                popEnterTransition = { DashboardNavigationMotion.enter(initialState.destination.route, targetState.destination.route, isPop = true) },
                popExitTransition = { DashboardNavigationMotion.exit(initialState.destination.route, targetState.destination.route, isPop = true) },
            ) {
                dashboardComposable(navController, Screen.Splash.route) {
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

                dashboardComposable(navController, Screen.Setup.route) {
                    val setupRevision = remember { sessionRevision }
                    SetupScreen(
                        settingsManager = settingsManager,
                        onSetupSuccess = { profile ->
                            credentialScope.launch {
                                if (setupRevision != sessionRevision || lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == false) {
                                    navController.navigate(Screen.Unlock.route) { popUpTo(Screen.Setup.route) { inclusive = true } }
                                    return@launch
                                }
                                prepareInsightSession()
                                if (setupRevision != sessionRevision) return@launch
                                unlockedProfile = profile
                                hasAutoCheckedUpdate = false
                                navController.navigate(Screen.Portfolio.route) {
                                    popUpTo(Screen.Setup.route) { inclusive = true }
                                }
                            }
                        },
                    )
                }

                dashboardComposable(navController, Screen.Unlock.route) {
                    var errorMessage by remember { mutableStateOf<String?>(null) }
                    var isUnlocking by remember { mutableStateOf(false) }

                    PinUnlockScreen(
                        errorMessage = errorMessage,
                        isLoading = isUnlocking,
                        onUnlock = { pin ->
                            credentialScope.launch {
                                if (isUnlocking) return@launch
                                val revision = sessionRevision
                                errorMessage = null
                                isUnlocking = true
                                try {
                                    val profile = settingsManager.unlockProfile(pin)
                                    if (profile != null) {
                                        if (revision != sessionRevision) return@launch
                                        prepareInsightSession()
                                        if (revision != sessionRevision) return@launch
                                        unlockedProfile = profile
                                        hasAutoCheckedUpdate = false
                                        errorMessage = null
                                        navController.navigate(Screen.Portfolio.route) {
                                            popUpTo(Screen.Unlock.route) { inclusive = true }
                                        }
                                    } else {
                                        errorMessage = invalidPinText
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    errorMessage = "잠금을 해제하지 못했습니다. 다시 시도해 주세요."
                                } finally {
                                    isUnlocking = false
                                }
                            }
                        },
                    )
                }

                dashboardComposable(navController, Screen.Portfolio.route) {
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
                            accountFilters = unlockedProfile.orEmptyAccountFilters(),
                            onSettingsClick = { navigateToPrimaryTab(Screen.Settings.route) },
                            onHoldingClick = { symbol, accountId ->
                                navController.navigate(Screen.HoldingDetail.createRoute(symbol, accountId))
                            },
                        )
                    }
                }

                dashboardComposable(navController, Screen.AssetStatus.route) {
                    val activeRepository = repository
                    if (activeRepository == null) {
                        LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                    } else {
                        AssetStatusScreen(
                            repository = activeRepository,
                            onSettingsClick = { navigateToPrimaryTab(Screen.Settings.route) },
                        )
                    }
                }

                dashboardComposable(navController, Screen.TradeHistory.route) {
                    val activeRepository = repository
                    if (activeRepository == null) {
                        LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                    } else {
                        TradeHistoryScreen(
                            repository = activeRepository,
                            accountFilters = unlockedProfile.orEmptyAccountFilters(),
                            onSettingsClick = { navigateToPrimaryTab(Screen.Settings.route) },
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

                dashboardComposable(navController, Screen.TradeDetail.route) {
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

                dashboardComposable(navController, Screen.Settings.route) {
                    if (unlockedProfile == null) {
                        LaunchedEffect(Unit) {
                            navController.navigate(Screen.Unlock.route) {
                                popUpTo(Screen.Portfolio.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        SettingsScreen(
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            versionName = remember(context) {
                                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                            },
                            isCheckingUpdate = isCheckingUpdate,
                            isDownloadingUpdate = isDownloadingUpdate,
                            onCheckUpdatesClick = ::checkForUpdates,
                            onManageAccountsClick = {
                                accountManagementError = null
                                navController.navigate(Screen.AccountManagement.route) { launchSingleTop = true }
                            },
                            onLogoutClick = ::logout,
                            onBackClick = { navController.popBackStack() },
                            onInsightSettingsClick = {
                                pendingInsightRoute = null
                                navController.navigate(Screen.InsightConnection.createRoute()) { launchSingleTop = true }
                            },
                            insightConnectionLabel = if (insightConnection.hasCredentials) "연결됨" else "연결 필요",
                        )
                    }
                }
                dashboardComposable(navController, Screen.AccountManagement.route) {
                    val profile = unlockedProfile
                    if (profile == null) {
                        LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                    } else {
                        AccountManagementScreen(
                            profile = profile,
                            isSaving = isSavingAccounts,
                            errorMessage = accountManagementError,
                            onSave = { pin, accounts ->
                                credentialScope.launch {
                                    if (isSavingAccounts) return@launch
                                    val revision = sessionRevision
                                    isSavingAccounts = true
                                    accountManagementError = null
                                    try {
                                        val updatedProfile = settingsManager.updateProfile(accounts, pin)
                                        if (revision != sessionRevision) return@launch
                                        if (updatedProfile == null) {
                                            accountManagementError = invalidPinText
                                        } else {
                                            unlockedProfile = updatedProfile
                                            tradeHistorySessionState = TradeHistorySessionState()
                                            navController.popBackStack()
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        accountManagementError = when (error.message) {
                                            "ACCOUNT_PROFILE_DUPLICATE" -> "같은 계좌번호와 상품코드를 중복 등록할 수 없습니다."
                                            "ACCOUNT_NUMBER_INVALID" -> "계좌번호는 숫자 8자리여야 합니다."
                                            "ACCOUNT_PRODUCT_CODE_INVALID" -> "계좌상품코드는 숫자 2자리여야 합니다."
                                            "ACCOUNT_CREDENTIALS_REQUIRED" -> "신규 계좌의 APP KEY와 APP SECRET을 입력하세요."
                                            else -> "계좌 설정을 저장하지 못했습니다."
                                        }
                                    } finally {
                                        isSavingAccounts = false
                                    }
                                }
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }

                dashboardComposable(
                    navController = navController,
                    route = Screen.HoldingDetail.route,
                    arguments = listOf(
                        navArgument("symbol") { type = NavType.StringType },
                        navArgument("accountId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { backStackEntry ->
                    val activeRepository = repository
                    val symbol = backStackEntry.arguments?.getString("symbol")
                    val accountId = backStackEntry.arguments?.getString("accountId")?.takeIf(String::isNotBlank)
                    if (activeRepository == null || symbol.isNullOrBlank()) {
                        LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) }
                    } else {
                        HoldingDetailScreen(
                            repository = activeRepository,
                            symbol = symbol,
                            accountId = accountId,
                            onBackClick = { navController.popBackStack() },
                            onInsightClick = ::openInsight,
                        )
                    }
                }

                dashboardComposable(
                    navController, Screen.InsightConnection.route,
                    arguments = listOf(navArgument("required") { type = NavType.BoolType; defaultValue = false }),
                ) { entry ->
                    if (unlockedProfile == null) {
                        LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) { launchSingleTop = true } }
                    } else {
                        LaunchedEffect(Unit) {
                            if (!insightSession.state.value.isUnlocked) prepareInsightSession()
                        }
                        InsightConnectionScreen(
                            sessionManager = insightSession,
                            connectionRequired = entry.arguments?.getBoolean("required") == true,
                            onBackClick = { pendingInsightRoute = null; navController.popBackStack() },
                            onConnected = {
                                pendingInsightRoute?.let { route ->
                                    pendingInsightRoute = null
                                    navController.popBackStack()
                                    if (navController.currentDestination?.route != Screen.StockInsight.route) {
                                        navController.navigate(route) { launchSingleTop = true }
                                    }
                                }
                            },
                            onClearCache = insightRepository::clearCache,
                        )
                    }
                }
                dashboardComposable(
                    navController, Screen.StockInsight.route,
                    arguments = listOf(
                        navArgument("symbol") { type = NavType.StringType },
                        navArgument("market") { type = NavType.StringType; defaultValue = "USA" },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val symbol = entry.arguments?.getString("symbol").orEmpty()
                    val market = entry.arguments?.getString("market") ?: "USA"
                    val name = entry.arguments?.getString("name").orEmpty().ifBlank { symbol }
                    if (unlockedProfile == null) {
                        LaunchedEffect(Unit) { navController.navigate(Screen.Unlock.route) { launchSingleTop = true } }
                    } else {
                        StockInsightScreen(
                            repository = insightRepository, symbol = symbol, marketType = market, displayName = name,
                            onBackClick = { navController.popBackStack() },
                            onConnectClick = {
                                pendingInsightRoute = Screen.StockInsight.createRoute(symbol, market, name)
                                navController.navigate(Screen.InsightConnection.createRoute(required = true)) { launchSingleTop = true }
                            },
                        )
                    }
                }
            }
        }

        if (unlockedProfile != null && currentRoute in primaryRoutes) {
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
                    enabled = !isDownloadingUpdate,
                    onClick = {
                        if (isDownloadingUpdate) return@TextButton
                        isDownloadingUpdate = true
                        scope.launch {
                            var launchedInstaller = false
                            try {
                                when (val result = updateManager.downloadUpdate(context, release)) {
                                    InstallPreparationResult.PermissionRequired -> {
                                        updateManager.requestInstallPermission(context)
                                        updateMessage = installPermissionText
                                    }

                                    is InstallPreparationResult.Ready -> {
                                        updateManager.launchInstaller(context, result.apkFile)
                                        launchedInstaller = true
                                    }
                                }
                            } catch (_: Exception) {
                                updateMessage = downloadFailedText
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

    if (isDownloadingUpdate) {
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

private fun AccountProfile?.orEmptyAccountFilters(): List<HoldingAccountFilter> =
    this?.accounts?.map { account ->
        HoldingAccountFilter(accountId = account.id, label = account.label)
    }.orEmpty()

private fun Context.activityOrNull(): ComponentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? ComponentActivity
}

internal fun shouldCloseUpdateDialog(policy: ReleasePolicy, launchedInstaller: Boolean): Boolean {
    return launchedInstaller || policy != ReleasePolicy.MANDATORY
}

/** Give shared glass recording to the target entry, including interrupted transitions. */
private fun NavGraphBuilder.dashboardComposable(
    navController: NavHostController,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        val targetEntry by navController.currentBackStackEntryAsState()
        DashboardNavigationScene(isNavigationSource = entry.id == targetEntry?.id) {
            content(entry)
        }
    }
}
