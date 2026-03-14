package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.KisRepository
import com.koreainv.dashboard.network.Trade
import com.koreainv.dashboard.network.TradeHistoryResponse
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.Success
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.SurfaceGlassLight
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeHistoryScreen(
    repository: KisRepository,
    onCheckUpdatesClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onTradeClick: (Trade, Double, String?) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var tradeData by remember { mutableStateOf<TradeHistoryResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tradeFilter by remember { mutableStateOf("all") }
    var selectedRange by remember { mutableStateOf("this_month") }
    var selectedRangeLabel by remember { mutableStateOf(rangeLabel("this_month")) }
    var rangeExpanded by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    var currencyMode by remember { mutableStateOf(CurrencyDisplayMode.KRW) }

    fun loadTradeHistory(range: String = selectedRange, forceRefresh: Boolean = false) {
        val resolvedLabel = rangeLabel(range)
        val rangeChanged = range != selectedRange
        selectedRange = range
        selectedRangeLabel = resolvedLabel
        isLoading = true
        errorMessage = null
        if (rangeChanged) {
            tradeData = null
        }
        coroutineScope.launch {
            runCatching { repository.fetchTradeHistory(range = range, forceRefresh = forceRefresh) }
                .onSuccess {
                    tradeData = it
                    selectedRangeLabel = it.period.label.ifBlank { resolvedLabel }
                }
                .onFailure {
                    val detail = it.message?.takeIf(String::isNotBlank) ?: it::class.simpleName ?: "unknown"
                    errorMessage = "거래내역을 불러오지 못했습니다. [$detail]"
                }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadTradeHistory(range = selectedRange)
    }

    Scaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.trade_history_title),
                lastSynced = tradeData?.lastSynced,
                actions = {
                    CompactCurrencyToggle(
                        mode = currencyMode,
                        onModeChange = { currencyMode = it },
                    )
                    if (isLoading && tradeData != null) {
                        HeaderLoadingIndicator()
                    } else {
                        HeaderIconButton(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            onClick = { loadTradeHistory(range = selectedRange, forceRefresh = true) },
                        )
                    }
                    DashboardUtilityMenu(
                        onCheckUpdates = onCheckUpdatesClick,
                        onLogout = onLogoutClick,
                    )
                },
            )
        },
        containerColor = Background,
    ) { paddingValues ->
        ScreenBackground(modifier = Modifier.padding(paddingValues)) {
            when {
                isLoading && tradeData == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextGold,
                    )
                }

                errorMessage != null && tradeData == null -> {
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
                            onClick = { loadTradeHistory() },
                            tone = AccentTone.Accent,
                        )
                    }
                }

                tradeData != null -> {
                    val data = tradeData!!
                    val filterTone = when (tradeFilter) {
                        "buy" -> AccentTone.Positive
                        "sell" -> AccentTone.Negative
                        else -> AccentTone.Neutral
                    }
                    val filteredTrades = data.trades.filter { trade ->
                        tradeFilter == "all" ||
                            (tradeFilter == "buy" && trade.side == stringResource(R.string.buy)) ||
                            (tradeFilter == "sell" && trade.side == stringResource(R.string.sell))
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 132.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        item {
                            TradeSummaryCard(
                                data = data,
                                currencyMode = currencyMode,
                                selectedRangeLabel = selectedRangeLabel,
                            )
                        }

                        item {
                            SectionHeader(
                                title = "",
                                modifier = Modifier.padding(top = 8.dp),
                                titleContent = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        SectionTitle(title = stringResource(R.string.trade_list))
                                        Box {
                                            DashboardPillButton(
                                                label = selectedRangeLabel,
                                                onClick = { rangeExpanded = true },
                                                trailingIcon = Icons.Default.ArrowDropDown,
                                            )
                                            DropdownMenu(
                                                expanded = rangeExpanded,
                                                onDismissRequest = { rangeExpanded = false },
                                                modifier = Modifier
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                                                    .background(SurfaceGlassLight)
                                                    .border(1.dp, SurfaceBorder, androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
                                            ) {
                                                tradeRangeOptions().forEach { option ->
                                                    DropdownMenuItem(
                                                        text = { Text(option.second, color = TextPrimary) },
                                                        colors = MenuDefaults.itemColors(textColor = TextPrimary),
                                                        onClick = {
                                                            rangeExpanded = false
                                                            loadTradeHistory(range = option.first)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                action = {
                                    Box {
                                        DashboardPillButton(
                                            label = when (tradeFilter) {
                                                "buy" -> stringResource(R.string.buy)
                                                "sell" -> stringResource(R.string.sell)
                                                else -> stringResource(R.string.all)
                                            },
                                            onClick = { filterExpanded = true },
                                            trailingIcon = Icons.Default.ArrowDropDown,
                                            tone = filterTone,
                                        )
                                        DropdownMenu(
                                            expanded = filterExpanded,
                                            onDismissRequest = { filterExpanded = false },
                                            modifier = Modifier
                                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                                                .background(SurfaceGlassLight)
                                                .border(1.dp, SurfaceBorder, androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.all), color = TextPrimary) },
                                                colors = MenuDefaults.itemColors(textColor = TextPrimary),
                                                onClick = { tradeFilter = "all"; filterExpanded = false },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.buy), color = TextPrimary) },
                                                colors = MenuDefaults.itemColors(textColor = TextPrimary),
                                                onClick = { tradeFilter = "buy"; filterExpanded = false },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.sell), color = TextPrimary) },
                                                colors = MenuDefaults.itemColors(textColor = TextPrimary),
                                                onClick = { tradeFilter = "sell"; filterExpanded = false },
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        if (filteredTrades.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.no_trades_found),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextSecondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            items(filteredTrades) { trade ->
                                TradeItemCard(
                                    trade = trade,
                                    currencyMode = currencyMode,
                                    usdRate = data.usdExchangeRate,
                                    onClick = { onTradeClick(trade, data.usdExchangeRate, data.lastSynced) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TradeSummaryCard(
    data: TradeHistoryResponse,
    currencyMode: CurrencyDisplayMode,
    selectedRangeLabel: String,
) {
    val profitColor = when {
        data.summary.totalRealizedProfitKrw > 0 -> Success
        data.summary.totalRealizedProfitKrw < 0 -> Error
        else -> TextPrimary
    }

    HeroTopSection {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.realized_profit),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            HeroHeadlineValue(
                value = formatCurrencyAmount(
                    data.summary.totalRealizedProfitKrw,
                    currencyMode,
                    data.usdExchangeRate,
                    signed = true,
                ),
                color = profitColor,
            )
            Text(
                text = selectedRangeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        HeroMetricGroup {
            HeroMetricRow(
                primaryLabel = stringResource(R.string.domestic),
                primaryValue = formatCurrencyAmount(
                    data.summary.domesticRealizedProfitKrw,
                    currencyMode,
                    data.usdExchangeRate,
                    signed = true,
                ),
                primaryValueColor = profitColorForAmount(data.summary.domesticRealizedProfitKrw),
                secondaryLabel = stringResource(R.string.overseas),
                secondaryValue = formatCurrencyAmount(
                    data.summary.overseasRealizedProfitKrw,
                    currencyMode,
                    data.usdExchangeRate,
                    signed = true,
                ),
                secondaryValueColor = profitColorForAmount(data.summary.overseasRealizedProfitKrw),
                syncValueSizing = true,
            )
        }
    }
}

@Composable
fun TradeItemCard(
    trade: Trade,
    currencyMode: CurrencyDisplayMode,
    usdRate: Double,
    onClick: () -> Unit,
) {
    val isBuy = trade.side == stringResource(R.string.buy)
    val sideTone = if (isBuy) AccentTone.Positive else AccentTone.Negative
    val sideColor = if (isBuy) Success else Error

    PremiumListItem(onClick = onClick) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SurfaceBadge(
                    label = trade.side,
                    tone = sideTone,
                    modifier = Modifier.offset(x = (-6).dp),
                )
                Text(
                    text = trade.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.share_quantity_price,
                        formatWholeNumber(trade.quantity),
                        formatTradeUnitPrice(trade),
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.width(116.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = formatTradeAmount(trade, currencyMode, usdRate),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            )
            if (trade.realizedProfitKrw != null && !isBuy) {
                Text(
                    text = formatCurrencyAmount(trade.realizedProfitKrw, currencyMode, usdRate, signed = true),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (trade.realizedProfitKrw >= 0) Success else Error,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            } else {
                Text(
                    text = trade.market,
                    style = MaterialTheme.typography.bodyMedium,
                    color = sideColor,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

private fun profitColorForAmount(amount: Double) = when {
    amount > 0 -> Success
    amount < 0 -> Error
    else -> TextPrimary
}

private fun formatTradeAmount(trade: Trade, currencyMode: CurrencyDisplayMode, usdRate: Double): String {
    return when {
        currencyMode == CurrencyDisplayMode.USD && trade.currency == "USD" -> "$${formatUsdNumber(trade.amountNative)}"
        currencyMode == CurrencyDisplayMode.KRW && trade.currency == "USD" -> formatCurrencyAmount(trade.amountKrw, CurrencyDisplayMode.KRW, usdRate)
        currencyMode == CurrencyDisplayMode.KRW && trade.currency == "JPY" -> formatCurrencyAmount(trade.amountKrw, CurrencyDisplayMode.KRW, usdRate)
        else -> formatCurrencyAmount(trade.amountKrw, currencyMode, usdRate)
    }
}

private fun formatTradeUnitPrice(trade: Trade): String {
    return when {
        trade.currency == "USD" -> "$${formatUsdNumber(trade.unitPrice)}"
        trade.currency == "JPY" -> "¥${formatWholeNumber(trade.unitPrice)}"
        else -> "₩${formatWholeNumber(trade.unitPrice)}"
    }
}

private fun formatUsdNumber(value: Double): String =
    NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }.format(value)

private fun tradeRangeOptions(): List<Pair<String, String>> = listOf(
    "this_month" to "이번 달",
    "last_month" to "지난 달",
    "3m" to "최근 3개월",
    "6m" to "지난 6개월",
)

private fun rangeLabel(range: String): String =
    tradeRangeOptions().firstOrNull { it.first == range }?.second ?: "이번 달"
