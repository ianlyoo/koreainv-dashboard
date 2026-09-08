package com.koreainv.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.network.*
import com.koreainv.dashboard.ui.screens.*
import com.koreainv.dashboard.ui.theme.KoreaInvDashboardTheme
import com.koreainv.dashboard.ui.appearance.AppearancePreference
import com.koreainv.dashboard.ui.appearance.ThemeMode
import kotlinx.coroutines.awaitCancellation

/** Debug-only screen host. Never creates a repository, transport, or order client. */
class UiPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialScreen = intent.getStringExtra("screen") ?: "portfolio"
        val fixture = intent.getStringExtra("fixture") ?: "normal"
        val source = SyntheticDashboardSource(fixture)
        // Run only on the fresh review emulator; setup is displayed without entering credentials.
        val appearance = AppearancePreference(applicationContext)
        intent.getStringExtra("theme")?.let { appearance.setThemeMode(ThemeMode.fromStoredValue(it)) }
        setContent {
            KoreaInvDashboardTheme(darkTheme = appearance.themeMode.isDark(isSystemInDarkTheme())) {
                val bottomBarHeight = remember { mutableStateOf(116.dp) }
                val currencyPreference = rememberCurrencyPreference()
                CompositionLocalProvider(
                    LocalDashboardBottomBarHeight provides bottomBarHeight,
                    LocalCurrencyPreference provides currencyPreference,
                ) {
                    DashboardGlassHost {
                        var screen by rememberSaveable { mutableStateOf(initialScreen) }
                        var detailSymbol by rememberSaveable { mutableStateOf("005930") }
                        var detailAccount by rememberSaveable { mutableStateOf<String?>("demo-kis") }
                        val screenStateHolder = rememberSaveableStateHolder()
                        var detailTrade by remember { mutableStateOf(source.allTrades.first()) }
                        var tradeSession by remember { mutableStateOf(TradeHistorySessionState()) }
                        val back = { screen = "portfolio" }
                        val accounts = { screen = "accounts" }
                        val settings = { screen = "settings" }
                        var updateNotice by remember { mutableStateOf(false) }
                        val logout = { screen = "unlock" }
                        val tabs = listOf(
                            DashboardTabItem("portfolio", stringResource(R.string.portfolio), Icons.Default.Home),
                            DashboardTabItem("assets", stringResource(R.string.asset_status), Icons.Default.AccountBox),
                            DashboardTabItem("trades", stringResource(R.string.trade_history_title), Icons.Default.List),
                            DashboardTabItem("settings", "설정", Icons.Default.Settings),
                        )
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().recordNavigationBackdrop()) {
                            screenStateHolder.SaveableStateProvider(screen) {
                                when (screen) {
                                    "portfolio" -> PortfolioScreen(source, source.filters, settings) { symbol, account ->
                                        detailSymbol = symbol; detailAccount = account; screen = "details"
                                    }
                                    "assets" -> AssetStatusScreen(source, settings)
                                    "trades" -> TradeHistoryScreen(source, source.filters, settings,
                                        { trade, _, _ -> detailTrade = trade; screen = "trade-details" },
                                        tradeSession, { tradeSession = it })
                                    "details" -> HoldingDetailScreen(source, detailSymbol, detailAccount, back)
                                    "trade-details" -> TradeDetailScreen(detailTrade, 1350.0, SyntheticDashboardSource.SYNC, { screen = "trades" })
                                    "unlock" -> PinUnlockScreen(if (fixture == "error") "합성 PIN 오류" else null,
                                        fixture == "loading", { screen = "portfolio" })
                                    "accounts" -> AccountManagementScreen(source.profile, fixture == "loading",
                                        if (fixture == "error") "합성 저장 오류" else null, { _, _ -> }, back)
                                    "setup" -> SetupScreen(SettingsManager(this@UiPreviewActivity), {})
                                    "settings" -> SettingsScreen(appearance.themeMode, appearance::setThemeMode,
                                        "1.8.1-preview", false, false, { updateNotice = true }, accounts, logout, back)
                                    else -> error("Unknown preview screen: $screen")
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
                                    DashboardBottomTabBar(tabs, screen) { screen = it.route }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
    private val holdings = if (fixture == "empty") emptyList() else allHoldings
    private val value = holdings.sumOf { it.totalValueKrw }
    private val cost = holdings.sumOf { it.totalCostKrw }
    private val cashKrw = if (fixture == "empty") 0.0 else 12600000.0
    private val cashUsd = if (fixture == "empty") 0.0 else 2400.0
    private val totalCash = cashKrw + cashUsd * 1350.0
    private val dashboard = DashboardResponse(
        DashboardSummary(value + totalCash, cost, value - cost, if (cost == 0.0) 0.0 else (value - cost) / cost * 100,
            cashKrw, totalCash, cashUsd, 0.0, orderableCashKrw = cashKrw, usdExchangeRate = 1350.0,
            domesticCount = holdings.count { it.currency == "KRW" }, overseasCount = holdings.count { it.currency != "KRW" }, lastSynced = SYNC),
        holdings, holdings.map { AssetDistribution(it.symbol, it.name, it.totalValueKrw / value * 100, it.totalValueKrw) },
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
