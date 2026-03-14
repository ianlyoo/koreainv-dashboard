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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.DashboardResponse
import com.koreainv.dashboard.network.Holding
import com.koreainv.dashboard.network.KisRepository
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.MarketJapanBg
import com.koreainv.dashboard.ui.theme.MarketJapanFg
import com.koreainv.dashboard.ui.theme.MarketKoreaBg
import com.koreainv.dashboard.ui.theme.MarketKoreaFg
import com.koreainv.dashboard.ui.theme.MarketUsaBg
import com.koreainv.dashboard.ui.theme.MarketUsaFg
import com.koreainv.dashboard.ui.theme.Success
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.SurfaceGlassLight
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(
    repository: KisRepository,
    onCheckUpdatesClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onHoldingClick: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var dashboardData by remember { mutableStateOf<DashboardResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currencyMode by remember { mutableStateOf(CurrencyDisplayMode.KRW) }
    var sortMode by remember { mutableStateOf(HoldingSortMode.VALUE) }
    var sortExpanded by remember { mutableStateOf(false) }

    fun loadDashboard(forceRefresh: Boolean = false) {
        isLoading = true
        errorMessage = null
        coroutineScope.launch {
            runCatching { repository.fetchDashboard(forceRefresh = forceRefresh) }
                .onSuccess { dashboardData = it }
                .onFailure {
                    val detail = it.message?.takeIf(String::isNotBlank) ?: it::class.simpleName ?: "unknown"
                    errorMessage = "자산 정보를 불러오지 못했습니다. [$detail]"
                }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        val cached = repository.peekDashboard()
        if (cached != null) {
            dashboardData = cached
            isLoading = false
        } else {
            loadDashboard()
        }
    }

    Scaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.portfolio),
                lastSynced = dashboardData?.summary?.lastSynced,
                actions = {
                    CompactCurrencyToggle(
                        mode = currencyMode,
                        onModeChange = { currencyMode = it },
                    )
                    if (isLoading && dashboardData != null) {
                        HeaderLoadingIndicator()
                    } else {
                        HeaderIconButton(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            onClick = { loadDashboard(forceRefresh = true) },
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
                isLoading && dashboardData == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextGold,
                    )
                }

                errorMessage != null && dashboardData == null -> {
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
                            onClick = { loadDashboard() },
                            tone = AccentTone.Accent,
                        )
                    }
                }

                dashboardData != null -> {
                    val data = dashboardData!!
                    val sortedHoldings = remember(data.holdings, sortMode) {
                        when (sortMode) {
                            HoldingSortMode.VALUE -> data.holdings.sortedByDescending { it.totalValueKrw }
                            HoldingSortMode.RETURN -> data.holdings.sortedByDescending { it.profitLossRate }
                            HoldingSortMode.PROFIT -> data.holdings.sortedByDescending { it.profitLossKrw }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 132.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        item {
                            PortfolioSummarySection(data = data, currencyMode = currencyMode)
                        }

                        item {
                            SectionHeader(
                                title = stringResource(R.string.holdings),
                                modifier = Modifier.padding(top = 8.dp),
                                action = {
                                    Box {
                                        DashboardPillButton(
                                            label = sortMode.label(),
                                            onClick = { sortExpanded = true },
                                            tone = AccentTone.Neutral,
                                            trailingIcon = Icons.Default.ArrowDropDown,
                                        )
                                        DropdownMenu(
                                            expanded = sortExpanded,
                                            onDismissRequest = { sortExpanded = false },
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(24.dp))
                                                .background(SurfaceGlassLight)
                                                .border(1.dp, SurfaceBorder, RoundedCornerShape(24.dp)),
                                        ) {
                                            HoldingSortMode.entries.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(mode.label(), color = TextPrimary) },
                                                    colors = MenuDefaults.itemColors(textColor = TextPrimary),
                                                    onClick = {
                                                        sortMode = mode
                                                        sortExpanded = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                        }

                        if (data.holdings.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.no_holdings_found),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextSecondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            items(sortedHoldings) { holding ->
                                HoldingItem(
                                    holding = holding,
                                    currencyMode = currencyMode,
                                    usdRate = data.summary.usdExchangeRate,
                                    onClick = { onHoldingClick(holding.symbol) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class HoldingSortMode {
    VALUE,
    RETURN,
    PROFIT,
}

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
                color = TextGold,
            )
            Text(
                text = stringResource(R.string.all_assets),
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
                MarketBadge(
                    market = holding.market,
                    modifier = Modifier.offset(x = (-6).dp),
                )
                Text(
                    text = holding.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = holding.symbol,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
                Text(
                    text = stringResource(R.string.share_count, formatWholeNumber(holding.quantity)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
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
                text = formatCurrencyAmount(holding.totalValueKrw, currencyMode, usdRate),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
            )
            Text(
                text = formatSignedPercent(holding.profitLossRate),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = profitColor,
                textAlign = TextAlign.End,
            )
        }
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
