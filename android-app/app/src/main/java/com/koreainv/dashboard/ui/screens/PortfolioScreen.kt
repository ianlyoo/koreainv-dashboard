package com.koreainv.dashboard.ui.screens

import android.os.SystemClock
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.DashboardResponse
import com.koreainv.dashboard.network.Holding
import com.koreainv.dashboard.network.DashboardDataSource
import com.koreainv.dashboard.network.US_DAY_MARKET_REFRESH_INTERVAL_MILLIS
import com.koreainv.dashboard.network.US_DAY_MARKET_REFRESH_WINDOW_MILLIS
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.MarketJapanBg
import com.koreainv.dashboard.ui.theme.MarketJapanFg
import com.koreainv.dashboard.ui.theme.MarketKoreaBg
import com.koreainv.dashboard.ui.theme.MarketKoreaFg
import com.koreainv.dashboard.ui.theme.MarketUsaBg
import com.koreainv.dashboard.ui.theme.MarketUsaFg
import com.koreainv.dashboard.ui.theme.Success
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    repository: DashboardDataSource,
    accountFilters: List<HoldingAccountFilter>,
    onSettingsClick: () -> Unit,
    onHoldingClick: (String, String?) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var dashboardData by remember(repository) { mutableStateOf<DashboardResponse?>(null) }
    var isLoading by remember(repository) { mutableStateOf(true) }
    var errorMessage by remember(repository) { mutableStateOf<String?>(null) }
    val currencyPreference = rememberCurrencyPreference()
    val currencyMode = currencyPreference.mode
    var sortMode by rememberSaveable { mutableStateOf(HoldingSortMode.VALUE) }
    var sortExpanded by remember { mutableStateOf(false) }
    var selectedAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountExpanded by remember { mutableStateOf(false) }

    val requestOwner = remember(repository) { ScreenRequestOwner() }
    DisposableEffect(requestOwner) {
        onDispose { requestOwner.cancel() }
    }

    fun loadDashboard(forceRefresh: Boolean = false) {
        isLoading = true
        requestOwner.launch(
            scope = coroutineScope,
            load = { repository.fetchDashboard(forceRefresh = forceRefresh) },
            onSuccess = {
                dashboardData = it
                errorMessage = null
            },
            onFailure = {
                errorMessage = dashboardErrorMessage(it)
            },
            onFinished = { isLoading = false },
        )
    }

    LaunchedEffect(repository) {
        val cached = repository.peekDashboard()
        if (cached != null) {
            dashboardData = cached
            isLoading = false
        } else {
            loadDashboard()
        }
    }

    LaunchedEffect(repository, dashboardData?.usMarketStatus?.session) {
        val current = dashboardData ?: return@LaunchedEffect
        if (current.usMarketStatus.session != "day_market") return@LaunchedEffect

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
                    if (requestOwner.accepts(version)) {
                        errorMessage = dashboardErrorMessage(error)
                    }
                    break
                }
                currentCoroutineContext().ensureActive()
                if (refreshed == null) break
                if (requestOwner.accepts(version)) {
                    dashboardData = refreshed
                    if (refreshed.usMarketStatus.session != "day_market") break
                }
            }
            delay(US_DAY_MARKET_REFRESH_INTERVAL_MILLIS)
        }
    }

    DashboardScaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.portfolio),
                lastSynced = dashboardData?.summary?.lastSynced,
                actions = {
                    CompactCurrencyToggle(
                        mode = currencyMode,
                        onModeChange = currencyPreference.onModeChange,
                    )
                    HeaderRefreshButton(
                        isRefreshing = isLoading,
                        contentDescription = stringResource(R.string.refresh),
                        onClick = { loadDashboard(forceRefresh = true) },
                    )
                    DashboardSettingsButton(onClick = onSettingsClick)
                },
            )
        },
    ) { paddingValues ->
        ScreenBackground {
            when {
                isLoading && dashboardData == null -> {
                    DashboardLoadingState(
                        message = "보유 자산을 불러오는 중입니다…",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                errorMessage != null && dashboardData == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DashboardErrorNotice(
                            message = errorMessage.orEmpty(),
                            onRetry = { loadDashboard(forceRefresh = true) },
                        )
                    }
                }

                dashboardData != null -> {
                    val data = dashboardData!!
                    val accountSelection = resolveAccountSelection(selectedAccountId, accountFilters)
                    val activeAccountId = accountSelection.accountId
                    val selectedAccountLabel = accountSelection.label
                        ?: if (accountSelection.unavailable) "확인할 수 없는 계좌" else stringResource(R.string.all_accounts)
                    val sortedHoldings = remember(data.holdings, sortMode, activeAccountId) {
                        filterAndSortHoldings(data.holdings, activeAccountId, sortMode)
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = paddingValues.calculateTopPadding() + 8.dp, bottom = dashboardBottomContentPadding()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (errorMessage != null) {
                            item {
                                DashboardErrorNotice(
                                    message = errorMessage.orEmpty(),
                                    onRetry = { loadDashboard(forceRefresh = true) },
                                    usingCachedData = true,
                                )
                            }
                        }
                        if (accountSelection.unavailable) {
                            item {
                                DashboardEmptyState(
                                    title = "선택한 계좌를 확인해 주세요",
                                    message = "현재 계좌 목록에 없는 계좌입니다. 표시 범위를 전체 계좌로 바꿀 수 있습니다.",
                                    actionLabel = "전체 계좌 보기",
                                    onAction = { selectedAccountId = null },
                                )
                            }
                        }
                        item {
                            PortfolioSummarySection(data = data, currencyMode = currencyMode)
                        }

                        item {
                            Column(
                                modifier = Modifier.padding(top = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(28.dp),
                            ) {
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val metadataMaxWidth = maxWidth * 0.6f
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        SectionTitle(
                                            title = stringResource(R.string.holdings),
                                            modifier = Modifier.weight(1f).alignByBaseline(),
                                        )
                                        Text(
                                            text = "$selectedAccountLabel · ${sortedHoldings.size}종목",
                                            modifier = Modifier.widthIn(max = metadataMaxWidth).alignByBaseline(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextSecondary,
                                            textAlign = TextAlign.End,
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.weight(1f)) {
                                        DashboardInlineButton(
                                            label = sortMode.label(),
                                            onClick = { sortExpanded = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            trailingIcon = Icons.Default.ArrowDropDown,
                                            compact = true,
                                        )
                                        ScreenFilterMenu(sortExpanded, { sortExpanded = false }) {
                                            HoldingSortMode.entries.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(mode.label(), color = TextPrimary) },
                                                    onClick = { sortMode = mode; sortExpanded = false },
                                                )
                                            }
                                        }
                                    }
                                    Box(Modifier.weight(1.3f)) {
                                        DashboardInlineButton(
                                            label = selectedAccountLabel,
                                            onClick = { accountExpanded = true },
                                            modifier = Modifier.fillMaxWidth().semantics {
                                                contentDescription = "계좌 선택: $selectedAccountLabel"
                                            },
                                            trailingIcon = Icons.Default.ArrowDropDown,
                                            compact = true,
                                        )
                                        ScreenFilterMenu(accountExpanded, { accountExpanded = false }) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.all_accounts), color = TextPrimary) },
                                                onClick = { selectedAccountId = null; accountExpanded = false },
                                            )
                                            accountFilters.forEach { account ->
                                                DropdownMenuItem(
                                                    text = { Text(account.label, color = TextPrimary) },
                                                    onClick = { selectedAccountId = account.accountId; accountExpanded = false },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (sortedHoldings.isEmpty()) {
                            if (!accountSelection.unavailable) {
                                item {
                                    DashboardEmptyState(
                                        title = "보유 종목이 없습니다",
                                        message = "$selectedAccountLabel 기준입니다. 계좌를 바꾸거나 최신 정보를 다시 불러오세요.",
                                        actionLabel = if (activeAccountId != null) "전체 계좌 보기" else "새로고침",
                                        onAction = {
                                            if (activeAccountId != null) selectedAccountId = null
                                            else loadDashboard(forceRefresh = true)
                                        },
                                    )
                                }
                            }
                        } else {
                            items(sortedHoldings, contentType = { "holding" }) { holding ->
                                HoldingItem(
                                    holding = holding,
                                    currencyMode = currencyMode,
                                    usdRate = data.summary.usdExchangeRate,
                                    onClick = { onHoldingClick(holding.symbol, holding.accountId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Confined to the screen's UI coroutine context; never owns repository caches. */
internal class ScreenRequestOwner {
    var version: Long = 0
        private set
    private var job: Job? = null

    fun accepts(requestVersion: Long): Boolean = version == requestVersion

    fun cancel() {
        version += 1
        job?.cancel()
        job = null
    }

    fun <T> launch(
        scope: CoroutineScope,
        load: suspend (requestVersion: Long) -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit,
        onFinished: () -> Unit,
    ) {
        cancel()
        val requestVersion = version
        job = scope.launch {
            try {
                val result = load(requestVersion)
                currentCoroutineContext().ensureActive()
                if (accepts(requestVersion)) onSuccess(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (accepts(requestVersion)) onFailure(error)
            } finally {
                if (accepts(requestVersion)) onFinished()
            }
        }
    }
}

internal data class AccountFilterSelection(
    val accountId: String?,
    val label: String?,
    val unavailable: Boolean,
)

internal fun resolveAccountSelection(
    selectedAccountId: String?,
    accountFilters: List<HoldingAccountFilter>,
): AccountFilterSelection {
    val account = accountFilters.firstOrNull { it.accountId == selectedAccountId }
    return AccountFilterSelection(
        accountId = selectedAccountId,
        label = account?.label,
        unavailable = selectedAccountId != null && account == null,
    )
}

internal enum class HoldingSortMode {
    VALUE,
    RETURN,
    PROFIT,
}

data class HoldingAccountFilter(
    val accountId: String,
    val label: String,
)

internal fun holdingAccountFilters(holdings: List<Holding>): List<HoldingAccountFilter> =
    holdings
        .filter { !it.accountId.isNullOrBlank() }
        .distinctBy { it.accountId }
        .map { holding ->
            HoldingAccountFilter(
                accountId = holding.accountId.orEmpty(),
                label = holding.accountLabel?.takeIf(String::isNotBlank)
                    ?: holding.accountId.orEmpty().takeLast(6),
            )
        }

internal fun filterAndSortHoldings(
    holdings: List<Holding>,
    accountId: String?,
    sortMode: HoldingSortMode,
): List<Holding> {
    val filtered = accountId?.let { selected -> holdings.filter { it.accountId == selected } } ?: holdings
    return when (sortMode) {
        HoldingSortMode.VALUE -> filtered.sortedByDescending { it.totalValueKrw }
        HoldingSortMode.RETURN -> filtered.sortedByDescending { it.profitLossRate }
        HoldingSortMode.PROFIT -> filtered.sortedByDescending { it.profitLossKrw }
    }
}

internal fun compactAccountFilterLabel(label: String): String =
    if (label.length <= 8) label else "${label.take(7)}…"

@Composable
private fun HoldingSortMode.label(): String = when (this) {
    HoldingSortMode.VALUE -> stringResource(R.string.sort_by_value)
    HoldingSortMode.RETURN -> stringResource(R.string.sort_by_return)
    HoldingSortMode.PROFIT -> stringResource(R.string.sort_by_profit)
}

@Composable
fun PortfolioSummarySection(data: DashboardResponse, currencyMode: CurrencyDisplayMode) {
    val isPositive = data.summary.totalProfitKrw >= 0
    val profitColor = if (isPositive) Success else Error
    val stockEvalAmount = data.summary.totalAssetsKrw - data.summary.totalCashKrw

    HeroTopSection {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.stock_evaluation_amount),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            HeroHeadlineValue(
                value = formatCurrencyAmount(stockEvalAmount, currencyMode, data.summary.usdExchangeRate),
                color = TextPrimary,
            )
            Text(
                text = "전체 계좌 합계",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        HeroMetricGroup {
            HeroMetricRow(
                primaryLabel = stringResource(R.string.profit_loss),
                primaryValue = formatCurrencyAmount(
                    data.summary.totalProfitKrw,
                    currencyMode,
                    data.summary.usdExchangeRate,
                    signed = true,
                ),
                primaryValueColor = profitColor,
                secondaryLabel = stringResource(R.string.return_label),
                secondaryValue = formatSignedPercent(data.summary.totalProfitRate),
                secondaryValueColor = profitColor,
            )
        }
    }
}

@Composable
fun HoldingItem(
    holding: Holding,
    currencyMode: CurrencyDisplayMode,
    usdRate: Double,
    onClick: () -> Unit,
) {
    val profitColor = if (holding.profitLossKrw >= 0) Success else Error
    val accountLabel = holding.accountLabel?.takeIf(String::isNotBlank) ?: "계좌 이름 없음"
    val staleQuote = holding.market == "USA" && holding.quoteSession == "day_market" && holding.quoteStale
    PremiumListItem(onClick = onClick) {
        LedgerRowContent(
            name = holding.name,
            identity = "${holding.symbol} · $accountLabel",
            detail = stringResource(R.string.share_count, formatWholeNumber(holding.quantity)) + " · ${holding.market}" +
                if (staleQuote) " · 종가 기준" else "",
            amount = formatCurrencyAmount(holding.totalValueKrw, currencyMode, usdRate),
            secondary = formatSignedPercent(holding.profitLossRate),
            secondaryColor = profitColor,
        )
    }
}

/** Keep ledger amounts aligned while allowing full text at accessibility sizes. */
@Composable
internal fun LedgerRowContent(
    name: String,
    identity: String,
    detail: String,
    amount: String,
    secondary: String?,
    secondaryColor: Color,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val amountStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val amountWidth = remember(measurer, amount, amountStyle, density.density, density.fontScale) {
        measurer.measure(amount, style = amountStyle, softWrap = false).size.width
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val amountColumnWidth = (maxWidth - 16.dp) * 0.46f
        val stacked = maxWidth < 320.dp || density.fontScale > 1.2f ||
            amountWidth > with(density) { amountColumnWidth.toPx() }
        val identityContent: @Composable () -> Unit = {
            Text(name, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(identity, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        val valueContent: @Composable () -> Unit = {
            Text(amount, style = amountStyle, color = TextPrimary,
                textAlign = if (stacked) TextAlign.Start else TextAlign.End)
            secondary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = secondaryColor,
                    textAlign = if (stacked) TextAlign.Start else TextAlign.End)
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { identityContent() }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { valueContent() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(name, modifier = Modifier.weight(1f).alignByBaseline(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(amount, modifier = Modifier.width(amountColumnWidth).alignByBaseline(),
                        style = amountStyle, color = TextPrimary, textAlign = TextAlign.End)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).alignBy(LastBaseline),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(identity, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    if (secondary != null) {
                        Text(secondary, modifier = Modifier.width(amountColumnWidth).alignBy(LastBaseline),
                            style = MaterialTheme.typography.bodySmall, color = secondaryColor,
                            textAlign = TextAlign.End)
                    } else {
                        Spacer(Modifier.width(amountColumnWidth))
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdaptiveListAmounts(amount: String, secondary: String? = null, secondaryColor: Color = TextSecondary) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val amountStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val secondaryStyle = MaterialTheme.typography.bodyMedium
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availablePx = with(density) { maxWidth.toPx() }
        val amountWidth = remember(textMeasurer, amount, amountStyle, density.density, density.fontScale) {
            textMeasurer.measure(amount, style = amountStyle, softWrap = false).size.width
        }
        val secondaryWidth = remember(textMeasurer, secondary, secondaryStyle, density.density, density.fontScale) {
            secondary?.let {
                textMeasurer.measure(it, style = secondaryStyle, softWrap = false).size.width
            } ?: 0
        }
        val stack = maxWidth < 280.dp || density.fontScale > 1.2f ||
            amountWidth + secondaryWidth + with(density) { 12.dp.toPx() } > availablePx
        if (secondary == null || stack) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(amount, style = amountStyle, color = TextPrimary)
                secondary?.let { Text(it, style = secondaryStyle, color = secondaryColor) }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(amount, modifier = Modifier.weight(1f), style = amountStyle, color = TextPrimary)
                Text(secondary, style = secondaryStyle, color = secondaryColor)
            }
        }
    }
}

/** Full-width, wrapping amounts preserve digits and accessibility font scaling. */
@Composable
internal fun FullMonetaryValue(label: String, value: String, valueColor: Color = TextPrimary) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(
            value,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}

@Composable
private fun MarketBadge(market: String, modifier: Modifier = Modifier) {
    val (backgroundColor, textColor) = when (market) {
        "KOR" -> MarketKoreaBg to MarketKoreaFg
        "USA" -> MarketUsaBg to MarketUsaFg
        "JPN" -> MarketJapanBg to MarketJapanFg
        else -> Color.Transparent to TextSecondary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(1.dp, textColor.copy(alpha = 0.24f), RoundedCornerShape(12.dp)),
        )
        Text(
            text = market,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
