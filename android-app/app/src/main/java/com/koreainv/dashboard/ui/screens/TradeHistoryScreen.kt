package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeHistoryScreen(
    repository: KisRepository,
    onCheckUpdatesClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onTradeClick: (Trade, Double, String?) -> Unit,
    sessionState: TradeHistorySessionState,
    onSessionStateChange: (TradeHistorySessionState) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    val initialTradeData = sessionState.tradeData ?: repository.peekTradeHistory(sessionState.selectedRange)
    var tradeData by remember(sessionState, initialTradeData) { mutableStateOf(initialTradeData) }
    var isLoading by remember(sessionState, initialTradeData) { mutableStateOf(initialTradeData == null) }
    var isTradeListLoading by remember(sessionState) { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tradeFilter by remember(sessionState) { mutableStateOf(sessionState.tradeFilter) }
    var selectedRange by remember(sessionState) { mutableStateOf(sessionState.selectedRange) }
    var selectedRangeLabel by remember(sessionState) { mutableStateOf(sessionState.selectedRangeLabel) }
    var rangeExpanded by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    var currencyMode by remember(sessionState) { mutableStateOf(sessionState.currencyMode) }
    var activeLoadRequestId by remember { mutableStateOf(0) }

    fun persistSessionState(snapshot: TradeHistoryResponse? = tradeData) {
        onSessionStateChange(
            TradeHistorySessionState(
                tradeData = snapshot,
                tradeFilter = tradeFilter,
                selectedRange = selectedRange,
                selectedRangeLabel = selectedRangeLabel,
                currencyMode = currencyMode,
            ),
        )
    }

    fun loadTradeHistory(range: String = selectedRange, forceRefresh: Boolean = false) {
        val resolvedLabel = rangeLabel(range)
        val rangeChanged = range != selectedRange
        val previousFullTradeData = tradeData?.takeIf { current -> current.trades.isNotEmpty() }
        val showSummaryPreview = previousFullTradeData == null
        val requestId = activeLoadRequestId + 1
        activeLoadRequestId = requestId
        selectedRange = range
        selectedRangeLabel = resolvedLabel
        isLoading = true
        isTradeListLoading = false
        errorMessage = null
        if (rangeChanged) {
            tradeData = null
        }
        persistSessionState()
        coroutineScope.launch {
            runCatching {
                repository.fetchTradeHistory(
                    range = range,
                    forceRefresh = forceRefresh,
                    onSummaryReady = if (showSummaryPreview) {
                        { summary ->
                            if (requestId == activeLoadRequestId) {
                                tradeData = summary
                                selectedRangeLabel = summary.period.label.ifBlank { resolvedLabel }
                                isTradeListLoading = true
                            }
                        }
                    } else {
                        null
                    },
                )
            }
                .onSuccess {
                    if (requestId != activeLoadRequestId) {
                        return@onSuccess
                    }
                    tradeData = it
                    selectedRangeLabel = it.period.label.ifBlank { resolvedLabel }
                    isTradeListLoading = false
                    persistSessionState()
                }
                .onFailure {
                    if (requestId != activeLoadRequestId) {
                        return@onFailure
                    }
                    val detail = it.message?.takeIf(String::isNotBlank) ?: it::class.simpleName ?: "unknown"
                    errorMessage = "거래내역을 불러오지 못했습니다. [$detail]"
                    isTradeListLoading = false
                    if (previousFullTradeData != null) {
                        tradeData = previousFullTradeData
                    }
                    val persistedTradeData = previousFullTradeData ?: tradeData?.takeIf { current -> current.trades.isNotEmpty() }
                    persistSessionState(snapshot = persistedTradeData)
                }
            if (requestId == activeLoadRequestId) {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (tradeData == null) {
            loadTradeHistory(range = selectedRange)
        } else if (isTradeHistorySnapshotStale(tradeData)) {
            loadTradeHistory(range = selectedRange, forceRefresh = true)
        }
    }

    Scaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.trade_history_title),
                lastSynced = tradeData?.lastSynced,
                actions = {
                    CompactCurrencyToggle(
                        mode = currencyMode,
                        onModeChange = {
                            currencyMode = it
                            persistSessionState()
                        },
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
                                                onClick = {
                                                    tradeFilter = "all"
                                                    filterExpanded = false
                                                    persistSessionState()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.buy), color = TextPrimary) },
                                                colors = MenuDefaults.itemColors(textColor = TextPrimary),
                                                onClick = {
                                                    tradeFilter = "buy"
                                                    filterExpanded = false
                                                    persistSessionState()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.sell), color = TextPrimary) },
                                                colors = MenuDefaults.itemColors(textColor = TextPrimary),
                                                onClick = {
                                                    tradeFilter = "sell"
                                                    filterExpanded = false
                                                    persistSessionState()
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        if (isTradeListLoading) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(color = TextGold)
                                    Text(
                                        text = "거래 목록을 불러오는 중…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        } else if (errorMessage != null && filteredTrades.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = errorMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center,
                                    )
                                    DashboardPillButton(
                                        label = stringResource(R.string.retry),
                                        onClick = { loadTradeHistory(range = selectedRange, forceRefresh = true) },
                                        tone = AccentTone.Accent,
                                    )
                                }
                            }
                        } else if (filteredTrades.isEmpty()) {
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

private const val TRADE_HISTORY_SCREEN_STALE_MILLIS = 10_000L

internal fun isTradeHistorySnapshotStale(
    data: TradeHistoryResponse?,
    now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.ofHours(9)),
    ttlMillis: Long = TRADE_HISTORY_SCREEN_STALE_MILLIS,
): Boolean {
    val lastSynced = data?.lastSynced?.takeIf(String::isNotBlank) ?: return true
    val syncedAt = runCatching { OffsetDateTime.parse(lastSynced) }.getOrNull() ?: return true
    return Duration.between(syncedAt.toInstant(), now.toInstant()).toMillis() > ttlMillis
}

data class TradeHistorySessionState(
    val tradeData: TradeHistoryResponse? = null,
    val tradeFilter: String = "all",
    val selectedRange: String = "this_month",
    val selectedRangeLabel: String = rangeLabel("this_month"),
    val currencyMode: CurrencyDisplayMode = CurrencyDisplayMode.KRW,
)

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
            TradeCardAmountText(
                text = formatTradeAmount(trade, currencyMode, usdRate),
                color = TextPrimary,
                primary = true,
            )
            if (trade.realizedProfitKrw != null && !isBuy) {
                TradeCardAmountText(
                    text = formatCurrencyAmount(trade.realizedProfitKrw, currencyMode, usdRate, signed = true),
                    color = if (trade.realizedProfitKrw >= 0) Success else Error,
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

@Composable
private fun TradeCardAmountText(
    text: String,
    color: Color,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val textSpec = tradeCardAmountTextSpec(
            value = text,
            maxWidth = maxWidth,
            primary = primary,
        )
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = textSpec.style,
            color = color,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

private data class TradeCardTextSpec(
    val style: TextStyle,
)

@Composable
private fun tradeCardAmountTextSpec(
    value: String,
    maxWidth: Dp,
    primary: Boolean,
): TradeCardTextSpec {
    val density = LocalDensity.current
    val styles = if (primary) {
        listOf(
            MaterialTheme.typography.titleMedium,
            MaterialTheme.typography.titleSmall,
            MaterialTheme.typography.bodyLarge,
            MaterialTheme.typography.bodyMedium,
            MaterialTheme.typography.bodySmall,
            MaterialTheme.typography.labelLarge,
            MaterialTheme.typography.labelMedium,
        )
    } else {
        listOf(
            MaterialTheme.typography.bodyMedium,
            MaterialTheme.typography.bodySmall,
            MaterialTheme.typography.labelLarge,
            MaterialTheme.typography.labelMedium,
        )
    }
    val availablePx = with(density) { maxWidth.toPx() }
    val chosen = styles.firstOrNull { style ->
        val fontPx = with(density) { style.fontSize.toPx() }
        (value.length * fontPx * 0.52f) <= availablePx
    } ?: styles.last()

    return TradeCardTextSpec(style = chosen)
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
