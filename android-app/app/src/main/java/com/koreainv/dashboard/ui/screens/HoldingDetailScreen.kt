package com.koreainv.dashboard.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.DashboardResponse
import com.koreainv.dashboard.network.Holding
import com.koreainv.dashboard.network.DashboardDataSource
import com.koreainv.dashboard.network.US_DAY_MARKET_REFRESH_INTERVAL_MILLIS
import com.koreainv.dashboard.network.US_DAY_MARKET_REFRESH_WINDOW_MILLIS
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.Success
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingDetailScreen(
    repository: DashboardDataSource,
    symbol: String,
    accountId: String?,
    onBackClick: () -> Unit,
    onInsightClick: (Holding) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var holding by remember(repository, symbol, accountId) { mutableStateOf<Holding?>(null) }
    var usdRate by remember(repository, symbol, accountId) { mutableStateOf(1350.0) }
    var lastSynced by remember(repository, symbol, accountId) { mutableStateOf<String?>(null) }
    var isLoading by remember(repository, symbol, accountId) { mutableStateOf(true) }
    var errorMessage by remember(repository, symbol, accountId) { mutableStateOf<String?>(null) }
    val currencyPreference = rememberCurrencyPreference()
    val currencyMode = currencyPreference.mode

    val requestOwner = remember(repository, symbol, accountId) { ScreenRequestOwner() }
    DisposableEffect(requestOwner) {
        onDispose { requestOwner.cancel() }
    }

    fun applyDashboard(dashboard: DashboardResponse) {
        usdRate = dashboard.summary.usdExchangeRate
        lastSynced = dashboard.summary.lastSynced
        holding = findHolding(dashboard.holdings, symbol, accountId)
        errorMessage = if (holding == null) "종목 정보를 찾을 수 없습니다. [$symbol]" else null
    }

    fun loadHolding(forceRefresh: Boolean = false) {
        isLoading = true
        requestOwner.launch(
            scope = scope,
            load = { repository.fetchDashboard(forceRefresh = forceRefresh) },
            onSuccess = { applyDashboard(it) },
            onFailure = { errorMessage = dashboardErrorMessage(it) },
            onFinished = { isLoading = false },
        )
    }

    LaunchedEffect(repository, symbol, accountId) {
        val cached = repository.peekDashboard()
        if (cached != null) {
            applyDashboard(cached)
            isLoading = false
            if (holding == null) {
                loadHolding()
            }
        } else {
            loadHolding()
        }
    }

    LaunchedEffect(repository, symbol, accountId, holding?.quoteSession) {
        val current = holding ?: return@LaunchedEffect
        if (current.market != "USA" || current.quoteSession != "day_market") return@LaunchedEffect

        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < US_DAY_MARKET_REFRESH_WINDOW_MILLIS) {
            if (!isLoading) {
                val version = requestOwner.version
                val refreshed = try {
                    repository.refreshDashboardQuotes()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (requestOwner.accepts(version)) errorMessage = dashboardErrorMessage(error)
                    break
                }
                currentCoroutineContext().ensureActive()
                if (refreshed == null) break
                if (requestOwner.accepts(version)) {
                    // Quote success must not erase a failed full-dashboard refresh notice.
                    val previousError = errorMessage
                    applyDashboard(refreshed)
                    if (holding != null) errorMessage = previousError
                    val updated = holding ?: break
                    if (updated.quoteSession != "day_market") break
                }
            }
            delay(US_DAY_MARKET_REFRESH_INTERVAL_MILLIS)
        }
    }

    DashboardScaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.holding_detail),
                lastSynced = lastSynced,
                navigationButton = {
                    HeaderIconButton(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        onClick = onBackClick,
                    )
                },
                actions = {
                    CompactCurrencyToggle(
                        mode = currencyMode,
                        onModeChange = currencyPreference.onModeChange,
                    )
                    HeaderRefreshButton(
                        isRefreshing = isLoading,
                        contentDescription = stringResource(R.string.refresh),
                        onClick = { loadHolding(forceRefresh = true) },
                    )
                },
            )
        },
    ) { paddingValues ->
        ScreenBackground {
            when {
                isLoading && holding == null -> {
                    DashboardLoadingState(
                        message = "종목 정보를 불러오는 중입니다…",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                errorMessage != null && holding == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DashboardErrorNotice(
                            message = errorMessage.orEmpty(),
                            onRetry = { loadHolding(forceRefresh = true) },
                        )
                    }
                }

                holding != null -> {
                    val data = holding!!
                    val profitColor = if (data.profitLossKrw >= 0) Success else Error
                    val unitPriceCurrency = if (data.currency == "USD") currencyMode.name else data.currency

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = paddingValues.calculateTopPadding() + 8.dp, bottom = paddingValues.calculateBottomPadding() + 32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        if (errorMessage != null) {
                            DashboardErrorNotice(
                                message = errorMessage.orEmpty(),
                                onRetry = { loadHolding(forceRefresh = true) },
                                usingCachedData = true,
                            )
                        }
                        HeroTopSection {
                            Text(
                                text = data.market + if (data.market == "USA" && data.quoteSession == "day_market" && data.quoteStale) " · 종가 기준" else "",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextSecondary,
                            )
                            Text(
                                text = data.accountLabel?.takeIf(String::isNotBlank) ?: "계좌 이름 없음",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = data.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = data.symbol,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                )
                            }
                            FullMonetaryValue(
                                label = "${stringResource(R.string.total_value)} (${currencyMode.name})",
                                value = formatCurrencyAmount(data.totalValueKrw, currencyMode, usdRate),
                                valueColor = TextPrimary,
                            )
                            HeroMetricGroup {
                                ResponsiveDetailRow(stringResource(R.string.quantity), formatWholeNumber(data.quantity))
                                ResponsiveDetailRow(
                                    stringResource(R.string.profit_loss_percentage),
                                    formatSignedPercent(data.profitLossRate),
                                    valueColor = profitColor,
                                )
                            }
                        }

                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                ResponsiveDetailRow("${stringResource(R.string.current_price)} ($unitPriceCurrency)", formatHoldingUnitPrice(data.currentPrice, data.currency, currencyMode, usdRate))
                                ResponsiveDetailRow("${stringResource(R.string.average_cost)} ($unitPriceCurrency)", formatHoldingUnitPrice(data.averageCost, data.currency, currencyMode, usdRate))
                                ResponsiveDetailRow(stringResource(R.string.total_cost), formatCurrencyAmount(data.totalCostKrw, currencyMode, usdRate))
                                ResponsiveDetailRow(stringResource(R.string.total_value), formatCurrencyAmount(data.totalValueKrw, currencyMode, usdRate))
                            }
                        }

                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                ResponsiveDetailRow(
                                    stringResource(R.string.profit_loss_amount),
                                    formatCurrencyAmount(data.profitLossKrw, currencyMode, usdRate, signed = true),
                                    valueColor = profitColor,
                                )
                                ResponsiveDetailRow(
                                    stringResource(R.string.profit_loss_percentage),
                                    formatSignedPercent(data.profitLossRate),
                                    valueColor = profitColor,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .clickable { onInsightClick(data) },
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("종목 인사이트", style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text("가격 차트 · 재무 · 시장 분석", style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary)
                            }
                            Icon(DashboardIcons.ChevronRight, contentDescription = null, tint = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

internal fun findHolding(holdings: List<Holding>, symbol: String, accountId: String?): Holding? =
    holdings.find { holding ->
        holding.symbol == symbol && (accountId == null || holding.accountId == accountId)
    }

private fun formatHoldingUnitPrice(
    price: Double,
    currency: String,
    mode: CurrencyDisplayMode,
    usdRate: Double,
): String {
    if (currency == "USD") {
        return if (mode == CurrencyDisplayMode.USD) {
            val formatter = NumberFormat.getNumberInstance(Locale.US).apply {
                maximumFractionDigits = 2
                minimumFractionDigits = 2
            }
            "$${formatter.format(abs(price))}"
        } else {
            formatCurrencyAmount(price * usdRate, CurrencyDisplayMode.KRW, usdRate)
        }
    }

    val formatter = if (currency == "JPY") {
        NumberFormat.getNumberInstance(Locale.JAPAN).apply {
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }
    } else {
        NumberFormat.getNumberInstance(Locale.KOREA).apply {
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }
    }
    val prefix = when (currency) {
        "JPY" -> "¥"
        else -> "₩"
    }
    return "$prefix${formatter.format(abs(price))}"
}
