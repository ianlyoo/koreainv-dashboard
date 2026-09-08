package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.DashboardDataSource
import com.koreainv.dashboard.network.Trade
import com.koreainv.dashboard.network.TradeHistoryResponse
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.Success
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.NumberFormat
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TradeHistoryScreen(
    repository: DashboardDataSource,
    accountFilters: List<HoldingAccountFilter>,
    onSettingsClick: () -> Unit,
    onTradeClick: (Trade, Double, String?) -> Unit,
    sessionState: TradeHistorySessionState,
    onSessionStateChange: (TradeHistorySessionState) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var tradeFilter by rememberSaveable { mutableStateOf(sessionState.tradeFilter) }
    var selectedRange by rememberSaveable { mutableStateOf(sessionState.selectedRange) }
    var selectedAccountId by rememberSaveable { mutableStateOf(sessionState.selectedAccountId) }
    val currencyPreference = rememberCurrencyPreference()
    val currencyMode = currencyPreference.mode
    val currentCurrencyMode by rememberUpdatedState(currencyMode)
    var selectedRangeLabel by rememberSaveable {
        mutableStateOf(if (selectedRange == sessionState.selectedRange) sessionState.selectedRangeLabel else rangeLabel(selectedRange))
    }
    val initialTradeData = remember(repository) {
        sessionState.tradeData?.takeIf { tradeHistorySessionMatches(sessionState, selectedRange, selectedAccountId) }
            ?: repository.peekTradeHistory(selectedRange, selectedAccountId)
    }
    var snapshots by remember(repository) {
        mutableStateOf(TradeHistorySnapshots(displayed = initialTradeData, complete = initialTradeData))
    }
    val tradeData = snapshots.displayed
    var isLoading by remember(repository) { mutableStateOf(initialTradeData == null) }
    var isTradeListLoading by remember(repository) { mutableStateOf(false) }
    var errorMessage by remember(repository) {
        mutableStateOf(sessionState.errorMessage.takeIf { tradeHistorySessionMatches(sessionState, selectedRange, selectedAccountId) })
    }
    var rangeExpanded by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    var accountExpanded by remember { mutableStateOf(false) }
    val requestOwner = remember(repository) { ScreenRequestOwner() }
    val updateSessionState by rememberUpdatedState(onSessionStateChange)
    DisposableEffect(requestOwner) {
        onDispose { requestOwner.cancel() }
    }

    fun persistSessionState(snapshot: TradeHistoryResponse? = snapshots.complete) {
        updateSessionState(
            TradeHistorySessionState(
                tradeData = snapshot,
                tradeFilter = tradeFilter,
                selectedRange = selectedRange,
                selectedRangeLabel = selectedRangeLabel,
                currencyMode = currentCurrencyMode,
                selectedAccountId = selectedAccountId,
                errorMessage = errorMessage,
            ),
        )
    }

    fun loadTradeHistory(
        range: String = selectedRange,
        accountId: String? = selectedAccountId,
        forceRefresh: Boolean = false,
    ) {
        val resolvedLabel = rangeLabel(range)
        val scopeChanged = range != selectedRange || accountId != selectedAccountId
        val previousFullTradeData = snapshots.complete.takeUnless { scopeChanged }
        val showSummaryPreview = previousFullTradeData == null
        selectedRange = range
        selectedRangeLabel = resolvedLabel
        selectedAccountId = accountId
        isLoading = true
        isTradeListLoading = false
        if (scopeChanged) {
            snapshots = TradeHistorySnapshots()
            errorMessage = null
        }
        persistSessionState()
        requestOwner.launch(
            scope = coroutineScope,
            load = { requestVersion ->
                repository.fetchTradeHistory(
                    range = range,
                    accountId = accountId,
                    forceRefresh = forceRefresh,
                    onSummaryReady = if (showSummaryPreview) {
                        { summary ->
                            currentCoroutineContext().ensureActive()
                            if (requestOwner.accepts(requestVersion)) {
                                snapshots = snapshots.withSummary(summary)
                                selectedRangeLabel = summary.period.label.ifBlank { resolvedLabel }
                                isTradeListLoading = true
                            }
                        }
                    } else {
                        null
                    },
                )
            },
            onSuccess = {
                snapshots = snapshots.withSuccess(it)
                selectedRangeLabel = it.period.label.ifBlank { resolvedLabel }
                isTradeListLoading = false
                errorMessage = null
                persistSessionState()
            },
            onFailure = {
                errorMessage = dashboardErrorMessage(it)
                isTradeListLoading = false
                snapshots = snapshots.afterFailure()
                persistSessionState()
            },
            onFinished = { isLoading = false },
        )
    }

    LaunchedEffect(repository) {
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
                        onModeChange = currencyPreference.onModeChange,
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
                    DashboardSettingsButton(onClick = onSettingsClick)
                },
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        ScreenBackground(modifier = Modifier.padding(paddingValues)) {
            when {
                isLoading && tradeData == null -> {
                    DashboardLoadingState(
                        message = "거래내역을 불러오는 중입니다…",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                errorMessage != null && tradeData == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DashboardErrorNotice(
                            message = errorMessage.orEmpty(),
                            onRetry = { loadTradeHistory(forceRefresh = true) },
                        )
                        if (resolveAccountSelection(selectedAccountId, accountFilters).unavailable) {
                            DashboardEmptyState(
                                title = "선택한 계좌를 확인해 주세요",
                                message = "현재 계좌 목록에 없는 계좌입니다. 전체 계좌로 다시 조회할 수 있습니다.",
                                actionLabel = "전체 계좌 보기",
                                onAction = { loadTradeHistory(accountId = null) },
                            )
                        }
                    }
                }

                tradeData != null -> {
                    val data = tradeData
                    val accountSelection = resolveAccountSelection(selectedAccountId, accountFilters)
                    val selectedAccountLabel = accountSelection.label
                        ?: if (accountSelection.unavailable) "확인할 수 없는 계좌" else stringResource(R.string.all_accounts)
                    val warningMessage = tradeHistoryWarningMessage(errorMessage, data.accountErrors)
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
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = dashboardBottomContentPadding()),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (warningMessage != null) {
                            item {
                                DashboardErrorNotice(
                                    message = warningMessage,
                                    onRetry = { loadTradeHistory(forceRefresh = true) },
                                    usingCachedData = errorMessage != null && snapshots.complete != null,
                                )
                            }
                        }
                        if (accountSelection.unavailable) {
                            item {
                                DashboardEmptyState(
                                    title = "선택한 계좌를 확인해 주세요",
                                    message = "현재 계좌 목록에 없는 계좌입니다. 전체 계좌로 다시 조회할 수 있습니다.",
                                    actionLabel = "전체 계좌 보기",
                                    onAction = { loadTradeHistory(accountId = null) },
                                )
                            }
                        }
                        item {
                            TradeSummaryCard(
                                data = data,
                                currencyMode = currencyMode,
                                selectedRangeLabel = selectedRangeLabel,
                                selectedAccountLabel = selectedAccountLabel,
                            )
                        }

                        item {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(modifier = Modifier.widthIn(min = 120.dp).weight(1f)) {
                                    DashboardPillButton(
                                        label = selectedRangeLabel,
                                        onClick = { rangeExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = Icons.Default.ArrowDropDown,
                                        compact = true,
                                    )
                                    ScreenFilterMenu(
                                        expanded = rangeExpanded,
                                        onDismissRequest = { rangeExpanded = false },
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
                                Box(modifier = Modifier.widthIn(min = 120.dp).weight(1f)) {
                                    DashboardPillButton(
                                        label = selectedAccountLabel,
                                        onClick = { accountExpanded = true },
                                        modifier = Modifier.fillMaxWidth().semantics {
                                            contentDescription = "계좌 선택: $selectedAccountLabel"
                                        },
                                        trailingIcon = Icons.Default.ArrowDropDown,
                                        compact = true,
                                    )
                                    ScreenFilterMenu(
                                        expanded = accountExpanded,
                                        onDismissRequest = { accountExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.all_accounts), color = TextPrimary) },
                                            colors = MenuDefaults.itemColors(textColor = TextPrimary),
                                            onClick = {
                                                accountExpanded = false
                                                loadTradeHistory(accountId = null)
                                            },
                                        )
                                        accountFilters.forEach { account ->
                                            DropdownMenuItem(
                                                text = { Text(account.label, color = TextPrimary) },
                                                colors = MenuDefaults.itemColors(textColor = TextPrimary),
                                                onClick = {
                                                    accountExpanded = false
                                                    loadTradeHistory(accountId = account.accountId)
                                                },
                                            )
                                        }
                                    }
                                }
                                Box(modifier = Modifier.widthIn(min = 100.dp).weight(1f)) {
                                    DashboardPillButton(
                                        label = when (tradeFilter) {
                                            "buy" -> stringResource(R.string.buy)
                                            "sell" -> stringResource(R.string.sell)
                                            else -> stringResource(R.string.all)
                                        },
                                        onClick = { filterExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = Icons.Default.ArrowDropDown,
                                        tone = filterTone,
                                        compact = true,
                                    )
                                    ScreenFilterMenu(
                                        expanded = filterExpanded,
                                        onDismissRequest = { filterExpanded = false },
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
                            }
                        }

                        item {
                            Text(
                                text = if (isTradeListLoading) "거래 목록 · 불러오는 중" else
                                    "거래 목록 · 확인된 ${filteredTrades.size}건 · $selectedAccountLabel · $selectedRangeLabel",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                            )
                        }
                        if (isTradeListLoading) {
                            item { DashboardLoadingState(message = "거래 목록을 불러오는 중입니다…") }
                        } else if (filteredTrades.isEmpty()) {
                            if (!accountSelection.unavailable) {
                                item {
                                    DashboardEmptyState(
                                        title = if (warningMessage != null) "거래 목록을 확인해 주세요" else "조건에 맞는 거래가 없습니다",
                                        message = if (warningMessage != null) {
                                            "조회가 완료되지 않아 거래가 없는지 확인할 수 없습니다. 다시 시도해 주세요."
                                        } else {
                                            "$selectedAccountLabel · $selectedRangeLabel 범위입니다. 기간이나 매수·매도 조건을 바꿔 보세요."
                                        },
                                        actionLabel = if (tradeFilter != "all") "매수·매도 모두 보기" else null,
                                        onAction = if (tradeFilter != "all") ({
                                            tradeFilter = "all"
                                            persistSessionState()
                                        }) else null,
                                    )
                                }
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
    val selectedAccountId: String? = null,
    val errorMessage: String? = null,
)

/** Summary callbacks may populate the screen, but only completed responses survive navigation. */
internal data class TradeHistorySnapshots(
    val displayed: TradeHistoryResponse? = null,
    val complete: TradeHistoryResponse? = null,
) {
    fun withSummary(summary: TradeHistoryResponse) = copy(displayed = summary)
    fun withSuccess(response: TradeHistoryResponse) = TradeHistorySnapshots(response, response)
    fun afterFailure() = copy(displayed = complete ?: displayed)
}

internal fun tradeHistoryWarningMessage(errorMessage: String?, accountErrors: List<String>): String? {
    val messages = listOfNotNull(
        errorMessage,
        "일부 계좌의 조회가 완료되지 않았습니다. 합계와 거래 목록에 누락이 있을 수 있습니다."
            .takeIf { accountErrors.isNotEmpty() },
    )
    return messages.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

internal fun tradeHistorySessionMatches(
    session: TradeHistorySessionState,
    range: String,
    accountId: String?,
): Boolean = session.selectedRange == range && session.selectedAccountId == accountId

@Composable
fun TradeSummaryCard(
    data: TradeHistoryResponse,
    currencyMode: CurrencyDisplayMode,
    selectedRangeLabel: String,
    selectedAccountLabel: String,
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
                value = if (data.profitAvailable) {
                    formatCurrencyAmount(
                        data.summary.totalRealizedProfitKrw,
                        currencyMode,
                        data.usdExchangeRate,
                        signed = true,
                    )
                } else {
                    "-"
                },
                color = if (data.profitAvailable) profitColor else TextPrimary,
            )
            Text(
                text = buildString {
                    append(selectedAccountLabel)
                    append(" · ")
                    append(selectedRangeLabel)
                    append(" · 매도 실현 손익")
                    if (!data.profitAvailable) {
                        append(" · 토스 추정 불가(원가 이력 부족)")
                    } else {
                        if (data.profitEstimated) append(" · 토스 추정 손익 포함")
                        if (!data.profitComplete) {
                            append(" · 원가 부족 ")
                            append(data.unpricedSellCount)
                            append("건 미산출")
                        }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        HeroMetricGroup {
            HeroMetricRow(
                primaryLabel = stringResource(R.string.domestic),
                primaryValue = if (data.profitAvailable) formatCurrencyAmount(
                    data.summary.domesticRealizedProfitKrw,
                    currencyMode,
                    data.usdExchangeRate,
                    signed = true,
                ) else "-",
                primaryValueColor = profitColorForAmount(data.summary.domesticRealizedProfitKrw),
                secondaryLabel = stringResource(R.string.overseas),
                secondaryValue = if (data.profitAvailable) formatCurrencyAmount(
                    data.summary.overseasRealizedProfitKrw,
                    currencyMode,
                    data.usdExchangeRate,
                    signed = true,
                ) else "-",
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
    val accountLabel = trade.accountLabel.takeIf(String::isNotBlank) ?: "계좌 이름 없음"
    val realizedProfit = trade.realizedProfitKrw?.takeIf { !isBuy }
    val profitText = realizedProfit?.let {
        "${if (trade.realizedProfitEstimated) "손익(추정)" else "손익"} ${formatCurrencyAmount(it, currencyMode, usdRate, signed = true)}"
    }
    PremiumListItem(onClick = onClick) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = trade.name,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Text(
                text = "${trade.side} · $accountLabel · ${trade.ticker} · ${stringResource(R.string.share_count, formatWholeNumber(trade.quantity))} · ${trade.date}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            AdaptiveListAmounts(
                amount = formatTradeAmount(trade, currencyMode, usdRate),
                secondary = profitText,
                secondaryColor = if (realizedProfit != null) profitColorForAmount(realizedProfit) else TextSecondary,
            )
        }
    }
}

@Composable
private fun profitColorForAmount(amount: Double) = when {
    amount > 0 -> Success
    amount < 0 -> Error
    else -> TextPrimary
}

internal fun formatTradeAmount(trade: Trade, currencyMode: CurrencyDisplayMode, usdRate: Double): String {
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

internal fun tradeRangeOptions(): List<Pair<String, String>> = listOf(
    "this_month" to "이번 달",
    "last_month" to "지난 달",
    "3m" to "최근 3개월",
    "6m" to "지난 6개월",
    "1y" to "최근 1년",
)

internal fun rangeLabel(range: String): String =
    tradeRangeOptions().firstOrNull { it.first == range }?.second ?: "이번 달"
