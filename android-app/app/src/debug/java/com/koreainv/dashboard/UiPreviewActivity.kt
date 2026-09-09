package com.koreainv.dashboard

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.outlined.Settings
import com.koreainv.dashboard.ui.screens.DashboardIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.*
import com.koreainv.dashboard.ui.screens.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.koreainv.dashboard.ui.DashboardNavigationMotion
import com.koreainv.dashboard.ui.DashboardNavigationScene
import com.koreainv.dashboard.ui.theme.KoreaInvDashboardTheme
import com.koreainv.dashboard.ui.appearance.AppearancePreference
import com.koreainv.dashboard.ui.appearance.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation

/** Debug-only host. Brokerage data is synthetic; insight uses a memory-only fake HTTP transport. */
class UiPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialScreen = intent.getStringExtra("screen") ?: "portfolio"
        val fixture = intent.getStringExtra("fixture") ?: "normal"
        val source = SyntheticDashboardSource(fixture)
        val insightEnvironment = lazy { PreviewInsightEnvironment(this, fixture) }
        // Run only on the fresh review emulator; setup is displayed without entering credentials.
        val appearance = AppearancePreference(applicationContext)
        intent.getStringExtra("theme")?.let { appearance.setThemeMode(ThemeMode.fromStoredValue(it)) }
        setContent {
            DisposableEffect(Unit) {
                onDispose { if (insightEnvironment.isInitialized()) insightEnvironment.value.close() }
            }
            KoreaInvDashboardTheme(darkTheme = appearance.themeMode.isDark(isSystemInDarkTheme())) {
                val bottomBarHeight = remember { mutableStateOf(116.dp) }
                val currencyPreference = rememberCurrencyPreference()
                CompositionLocalProvider(
                    LocalDashboardBottomBarHeight provides bottomBarHeight,
                    LocalCurrencyPreference provides currencyPreference,
                ) {
                    val navController = rememberNavController()
                    val navEntry by navController.currentBackStackEntryAsState()
                    val screen = navEntry?.destination?.route ?: initialScreen
                    val primaryRoutes = listOf("portfolio", "assets", "trades", "settings")
                    fun navigatePreview(route: String) {
                        val currentRoute = navController.currentDestination?.route
                        if (currentRoute == route) return
                        navController.navigate(route) {
                            launchSingleTop = true
                            if (route in primaryRoutes && currentRoute in primaryRoutes) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                restoreState = true
                            }
                        }
                    }
                    DashboardGlassHost(background = { if (screen == "unlock") LoginBackdrop() }) {
                        var detailSymbol by rememberSaveable { mutableStateOf("005930") }
                        var detailAccount by rememberSaveable { mutableStateOf<String?>("demo-kis") }
                        var detailTrade by remember { mutableStateOf(source.allTrades.first()) }
                        var insightSymbol by rememberSaveable { mutableStateOf("AVGO") }
                        var insightName by rememberSaveable { mutableStateOf("브로드컴") }
                        var insightMarket by rememberSaveable { mutableStateOf("USA") }
                        var connectionRequired by rememberSaveable { mutableStateOf(fixture == "disconnected") }
                        var tradeSession by remember { mutableStateOf(TradeHistorySessionState()) }
                        val back: () -> Unit = {
                            if (!navController.popBackStack()) navigatePreview("portfolio")
                        }
                        val accounts = { navigatePreview("accounts") }
                        val settings = { navigatePreview("settings") }
                        var updateNotice by remember { mutableStateOf(false) }
                        var unlockError by remember { mutableStateOf<String?>(null) }
                        var unlockBusy by remember { mutableStateOf(false) }
                        val previewScope = rememberCoroutineScope()
                        val logout = {
                            navController.navigate("unlock") {
                                popUpTo(navController.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        val tabs = listOf(
                            DashboardTabItem("portfolio", stringResource(R.string.portfolio), DashboardIcons.Portfolio),
                            DashboardTabItem("assets", stringResource(R.string.asset_status), DashboardIcons.Assets),
                            DashboardTabItem("trades", stringResource(R.string.trade_history_title), DashboardIcons.Trades),
                            DashboardTabItem("settings", "설정", Icons.Outlined.Settings),
                        )
                        Box(Modifier.fillMaxSize()) {
                            NavHost(
                                navController = navController,
                                startDestination = initialScreen,
                                modifier = Modifier.fillMaxSize(),
                                enterTransition = { DashboardNavigationMotion.enter(
                                    previewMotionRoute(initialState.destination.route),
                                    previewMotionRoute(targetState.destination.route)) },
                                exitTransition = { DashboardNavigationMotion.exit(
                                    previewMotionRoute(initialState.destination.route),
                                    previewMotionRoute(targetState.destination.route)) },
                                popEnterTransition = { DashboardNavigationMotion.enter(
                                    previewMotionRoute(initialState.destination.route),
                                    previewMotionRoute(targetState.destination.route), isPop = true) },
                                popExitTransition = { DashboardNavigationMotion.exit(
                                    previewMotionRoute(initialState.destination.route),
                                    previewMotionRoute(targetState.destination.route), isPop = true) },
                            ) {
                                (primaryRoutes + listOf("details", "trade-details", "unlock", "accounts", "setup", "insight", "connection")).forEach { scene ->
                                    composable(scene) { entry ->
                                        val targetEntry by navController.currentBackStackEntryAsState()
                                        DashboardNavigationScene(isNavigationSource = entry.id == targetEntry?.id) {
                                            when (scene) {
                                                "portfolio" -> PortfolioScreen(source, source.filters, settings) { symbol, account ->
                                                    detailSymbol = symbol; detailAccount = account; navigatePreview("details")
                                                }
                                                "assets" -> AssetStatusScreen(source, settings)
                                                "trades" -> TradeHistoryScreen(source, source.filters, settings,
                                                    { trade, _, _ -> detailTrade = trade; navigatePreview("trade-details") },
                                                    tradeSession, { tradeSession = it })
                                                "details" -> HoldingDetailScreen(source, detailSymbol, detailAccount, back) { holding ->
                                                    insightSymbol=holding.symbol; insightName=holding.name; insightMarket=holding.market
                                                    connectionRequired=fixture == "disconnected"
                                                    navigatePreview(if(connectionRequired) "connection" else "insight")
                                                }
                                                "insight", "connection" -> {
                                                    val env=remember { insightEnvironment.value }
                                                    LaunchedEffect(env) { if(!env.session.state.value.isUnlocked) env.start() }
                                                    if(scene == "insight") StockInsightScreen(env.repository,insightSymbol,insightMarket,back,
                                                        { connectionRequired=true;navigatePreview("connection") },displayName=insightName)
                                                    else InsightConnectionScreen(env.session,back,{navigatePreview("insight")},connectionRequired,env.repository::clearCache)
                                                }
                                                "trade-details" -> TradeDetailScreen(detailTrade, 1350.0, SyntheticDashboardSource.SYNC, back)
                                                "unlock" -> PinUnlockScreen(
                                                    unlockError ?: if (fixture == "error") "잠금번호가 올바르지 않습니다." else null,
                                                    unlockBusy || fixture == "loading",
                                                ) { pin ->
                                                    if (!unlockBusy) {
                                                        android.util.Log.i("UiPreviewUnlock", "Synthetic unlock attempt")
                                                        unlockError = null
                                                        unlockBusy = true
                                                        previewScope.launch {
                                                            delay(when (fixture) { "slow-unlock" -> 1500L; "instant-error" -> 0L; else -> 100L })
                                                            if (fixture in listOf("reject", "instant-error") || pin != "1234") {
                                                                unlockError = "잠금번호가 올바르지 않습니다."
                                                            } else {
                                                                navigatePreview("portfolio")
                                                            }
                                                            unlockBusy = false
                                                        }
                                                    }
                                                }
                                                "accounts" -> AccountManagementScreen(source.profile, fixture == "loading",
                                                    if (fixture == "error") "합성 저장 오류" else null, { _, _ -> }, back)
                                                "setup" -> SetupScreen(SettingsManager(this@UiPreviewActivity), {})
                                                "settings" -> SettingsScreen(appearance.themeMode, appearance::setThemeMode,
                                                    "1.9.7-preview", false, false, { updateNotice = true }, accounts, logout, back,
                                                    { connectionRequired=false; navigatePreview("connection") })
                                                else -> error("Unknown preview screen: $scene")
                                            }
                                        }
                                    }
                                }
                            }
                            if (updateNotice) {
                                androidx.compose.material3.AlertDialog(onDismissRequest = { updateNotice = false },
                                    title = { androidx.compose.material3.Text("업데이트 확인") },
                                    text = { androidx.compose.material3.Text("가상 데이터 미리보기입니다.") },
                                    confirmButton = { androidx.compose.material3.TextButton(onClick = { updateNotice = false }) {
                                        androidx.compose.material3.Text("확인")
                                    } })
                            }
                            if (screen in tabs.map { it.route }) {
                                Box(Modifier.align(Alignment.BottomCenter)) {
                                    DashboardBottomTabBar(tabs, screen) { navigatePreview(it.route) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun previewMotionRoute(route: String?): String? = when (route) {
    "assets" -> "asset_status"
    "trades" -> "trade_history"
    "details" -> "holding_detail/{symbol}?accountId={accountId}"
    "trade-details" -> "trade_detail"
    "accounts" -> "account_management"
    "insight" -> "stock_insight"
    "connection" -> "insight_connection"
    else -> route
}

/** All prices, accounts and transactions are fictional and deterministic. */
internal class SyntheticDashboardSource(private val fixture: String) : DashboardDataSource {
    companion object { const val SYNC = "2026-09-04T15:30:00+09:00" }
    val profile = AccountProfile(listOf(
        AccountCredential("demo-kis", "데모 한국투자", "", "", "00000000", "01"),
        AccountCredential("demo-toss", "데모 토스증권", "", "", "11111111", "01", broker = Broker.TOSS),
    ))
    val filters = profile.accounts.map { HoldingAccountFilter(it.id, it.label) }
    private fun holding(symbol: String, name: String, market: String, quantity: Double, price: Double,
                        cost: Double, currency: String, rate: Double, account: Int): Holding {
        val value = quantity * price * rate
        val purchase = quantity * cost * rate
        return Holding(symbol, name, market, quantity, price, cost, value, purchase,
            value - purchase, (price / cost - 1) * 100, currency, rate,
            accountId = profile.accounts[account].id, accountLabel = profile.accounts[account].label,
            broker = profile.accounts[account].broker, quoteStale = fixture == "stale")
    }
    private val allHoldings = listOf(
        holding("005930", "삼성전자", "KOR", 150.0, 73500.0, 68000.0, "KRW", 1.0, 0),
        holding("000660", "SK하이닉스", "KOR", 30.0, 162000.0, 175000.0, "KRW", 1.0, 0),
        holding("AAPL", "Apple Inc.", "USA", 20.0, 225.0, 190.0, "USD", 1350.0, 1),
        holding("NVDA", "NVIDIA Corporation", "USA", 35.0, 120.0, 130.0, "USD", 1350.0, 1),
        holding("7203", "Toyota Motor", "JPN", 100.0, 2700.0, 2450.0, "JPY", 9.0, 0),
    )
    private val holdings = when (fixture) {
        "empty" -> emptyList()
        "long-name" -> listOf(allHoldings.first().copy(name = "Global Semiconductor Innovation Holdings"))
        "same-asset-accounts" -> allHoldings +
            holding("005930", "삼성전자", "KOR", 50.0, 73500.0, 68000.0, "KRW", 1.0, 1)
        else -> allHoldings
    }
    private val value = holdings.sumOf { it.totalValueKrw }
    private val cost = holdings.sumOf { it.totalCostKrw }
    private val cashKrw = if (fixture == "empty") 0.0 else 12600000.0
    private val cashUsd = if (fixture == "empty") 0.0 else 2400.0
    private val totalCash = cashKrw + cashUsd * 1350.0
    private val dashboard = DashboardResponse(
        DashboardSummary(value + totalCash, cost, value - cost, if (cost == 0.0) 0.0 else (value - cost) / cost * 100,
            cashKrw, totalCash, cashUsd, 0.0, orderableCashKrw = cashKrw, usdExchangeRate = 1350.0,
            domesticCount = holdings.count { it.currency == "KRW" }, overseasCount = holdings.count { it.currency != "KRW" }, lastSynced = SYNC),
        holdings, buildAssetDistribution(holdings),
    )
    val allTrades = listOf(
        Trade("2026-09-04", "매도", "005930", "삼성전자", "KOR", "KRW", 20.0, 73500.0, 1470000.0, 1470000.0, 110000.0, 8.09,
            accountId = "demo-kis", accountLabel = "데모 한국투자"),
        Trade("2026-09-03", "매수", "AAPL", "Apple Inc.", "USA", "USD", 5.0, 225.0, 1125.0, 1518750.0, null, null,
            accountId = "demo-toss", accountLabel = "데모 토스증권", broker = Broker.TOSS),
        Trade("2026-09-02", "매도", "NVDA", "NVIDIA Corporation", "USA", "USD", 10.0, 120.0, 1200.0, 1620000.0, -135000.0, -7.69,
            accountId = "demo-toss", accountLabel = "데모 토스증권", broker = Broker.TOSS),
    )
    private fun trades(accountId: String?) = TradeHistoryResponse(
        TradePeriod("2026-09-01", "2026-09-05", "이번 달"),
        if (fixture == "empty") TradeSummary(0.0, 0.0, 0.0, 0.0) else TradeSummary(-25000.0, 110000.0, -135000.0, -0.8),
        if (fixture == "empty") emptyList() else allTrades.filter { accountId == null || it.accountId == accountId },
        lastSynced = SYNC, accountErrors = if (fixture == "partial") listOf("데모 토스증권: 합성 조회 오류") else emptyList(),
    )
    private suspend fun checkFixture() {
        android.util.Log.i("UiPreviewFixture", "Synthetic request fixture=$fixture")
        if (fixture == "loading") awaitCancellation()
        if (fixture == "motion") delay(800L)
        check(fixture != "error" && fixture != "cached-error") { "합성 데이터 오류 — 실제 네트워크 요청 없음" }
    }
    override fun peekDashboard() = if (fixture in listOf("loading", "error")) null else dashboard
    override suspend fun fetchDashboard(forceRefresh: Boolean): DashboardResponse { checkFixture(); return dashboard }
    override suspend fun refreshDashboardQuotes(): DashboardResponse { checkFixture(); return dashboard }
    override fun peekTradeHistory(range: String, accountId: String?) = if (fixture in listOf("loading", "error")) null else trades(accountId)
    override suspend fun fetchTradeHistory(range: String, accountId: String?, forceRefresh: Boolean,
        onSummaryReady: (suspend (TradeHistoryResponse) -> Unit)?): TradeHistoryResponse {
        checkFixture()
        return trades(accountId).also { onSummaryReady?.invoke(it) }
    }
}
