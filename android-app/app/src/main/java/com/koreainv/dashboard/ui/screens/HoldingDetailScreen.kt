package com.koreainv.dashboard.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.DashboardResponse
import com.koreainv.dashboard.network.Holding
import com.koreainv.dashboard.network.KisRepository
import com.koreainv.dashboard.network.US_DAY_MARKET_REFRESH_INTERVAL_MILLIS
import com.koreainv.dashboard.network.US_DAY_MARKET_REFRESH_WINDOW_MILLIS
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.Success
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingDetailScreen(
    repository: KisRepository,
    symbol: String,
    onBackClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf<Holding?>(null) }
    var usdRate by remember { mutableStateOf(1350.0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currencyMode by remember { mutableStateOf(CurrencyDisplayMode.KRW) }

    fun applyDashboard(dashboard: DashboardResponse) {
        usdRate = dashboard.summary.usdExchangeRate
        holding = dashboard.holdings.find { it.symbol == symbol }
        if (holding == null) {
            errorMessage = "종목 정보를 찾을 수 없습니다. [$symbol]"
        }
    }

    fun loadHolding(forceRefresh: Boolean = false) {
        isLoading = true
        errorMessage = null
        scope.launch {
            runCatching { repository.fetchDashboard(forceRefresh = forceRefresh) }
                .onSuccess { dashboard ->
                    applyDashboard(dashboard)
                }
                .onFailure {
                    val detail = it.message?.takeIf(String::isNotBlank) ?: it::class.simpleName ?: "unknown"
                    errorMessage = "종목 정보를 불러오지 못했습니다. [$detail]"
                }
            isLoading = false
        }
    }

    LaunchedEffect(symbol) {
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

    LaunchedEffect(symbol, holding?.quoteSession) {
        val current = holding ?: return@LaunchedEffect
        if (current.market != "USA" || current.quoteSession != "day_market") return@LaunchedEffect

        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < US_DAY_MARKET_REFRESH_WINDOW_MILLIS) {
            val refreshed = runCatching { repository.refreshDashboardQuotes() }.getOrNull() ?: break
            applyDashboard(refreshed)
            val updated = holding ?: break
            if (updated.quoteSession != "day_market") break
            delay(US_DAY_MARKET_REFRESH_INTERVAL_MILLIS)
        }
    }

    Scaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.holding_detail),
                lastSynced = repository.peekDashboard()?.summary?.lastSynced,
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
                        onModeChange = { currencyMode = it },
                    )
                    if (isLoading && holding != null) {
                        HeaderLoadingIndicator()
                    } else {
                        HeaderIconButton(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            onClick = { loadHolding(forceRefresh = true) },
                        )
                    }
                },
            )
        },
        containerColor = Background,
    ) { paddingValues ->
        ScreenBackground(modifier = Modifier.padding(paddingValues)) {
            when {
                isLoading && holding == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextGold,
                    )
                }

                errorMessage != null && holding == null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        DashboardPillButton(
                            label = stringResource(R.string.retry),
                            onClick = { loadHolding(forceRefresh = true) },
                            tone = AccentTone.Accent,
                        )
                    }
                }

                holding != null -> {
                    val data = holding!!
                    val profitColor = if (data.profitLossKrw >= 0) Success else Error

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        HeroTopSection {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SurfaceBadge(label = data.market, tone = AccentTone.Info)
                                if (data.market == "USA" && data.quoteSession == "day_market" && data.quoteStale) {
                                    SurfaceBadge(label = "종가", tone = AccentTone.Neutral)
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = data.name,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = data.symbol,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                )
                            }
                            Text(
                                text = formatCurrencyAmount(data.totalValueKrw, currencyMode, usdRate),
                                style = MaterialTheme.typography.displayLarge,
                                color = TextGold,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                MetricPill(
                                    label = stringResource(R.string.quantity),
                                    value = formatWholeNumber(data.quantity),
                                    modifier = Modifier.weight(1f),
                                )
                                MetricPill(
                                    label = stringResource(R.string.profit_loss_percentage),
                                    value = formatSignedPercent(data.profitLossRate),
                                    modifier = Modifier.weight(1f),
                                    valueColor = profitColor,
                                )
                            }
                        }

                        PremiumGlassCard {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                HoldingMetricRow(stringResource(R.string.current_price), formatHoldingUnitPrice(data.currentPrice, data.currency, currencyMode, usdRate))
                                HoldingMetricRow(stringResource(R.string.average_cost), formatHoldingUnitPrice(data.averageCost, data.currency, currencyMode, usdRate))
                                HoldingMetricRow(stringResource(R.string.total_cost), formatCurrencyAmount(data.totalCostKrw, currencyMode, usdRate))
                                HoldingMetricRow(stringResource(R.string.total_value), formatCurrencyAmount(data.totalValueKrw, currencyMode, usdRate))
                            }
                        }

                        PremiumGlassCard {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                HoldingMetricRow(
                                    stringResource(R.string.profit_loss_amount),
                                    formatCurrencyAmount(data.profitLossKrw, currencyMode, usdRate, signed = true),
                                    valueColor = profitColor,
                                )
                                HoldingMetricRow(
                                    stringResource(R.string.profit_loss_percentage),
                                    formatSignedPercent(data.profitLossRate),
                                    valueColor = profitColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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

@Composable
private fun HoldingMetricRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = TextPrimary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
