package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.KisRepository
import com.koreainv.dashboard.network.DashboardResponse
import com.koreainv.dashboard.network.Holding
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Surface
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
    onMenuClick: () -> Unit,
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
            TopAppBar(
                title = {
                    InlineTitleWithSync(
                        title = stringResource(R.string.portfolio),
                        lastSynced = dashboardData?.summary?.lastSynced,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextGold,
                ),
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu), tint = Color.White)
                    }
                },
                actions = {
                    CompactCurrencyToggle(
                        mode = currencyMode,
                        onModeChange = { currencyMode = it },
                    )
                    if (isLoading && dashboardData != null) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 10.dp, end = 16.dp)
                                .size(20.dp),
                            color = TextGold,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { loadDashboard(forceRefresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh), tint = Color.White)
                        }
                    }
                },
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .padding(paddingValues),
        ) {
            when {
                isLoading && dashboardData == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextGold,
                    )
                }

                errorMessage != null && dashboardData == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { loadDashboard() },
                            colors = ButtonDefaults.buttonColors(containerColor = TextGold),
                        ) {
                            Text(stringResource(R.string.retry), color = Color.Black)
                        }
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item {
                            PortfolioSummarySection(data, currencyMode)
                        }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionTitle(
                                    title = stringResource(R.string.holdings),
                                    modifier = Modifier,
                                )
                                Box {
                                    TextButton(onClick = { sortExpanded = true }) {
                                        Text(
                                            text = sortMode.label(),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = TextGold,
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = stringResource(R.string.sort_by),
                                            tint = TextGold,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = sortExpanded,
                                        onDismissRequest = { sortExpanded = false },
                                    ) {
                                        HoldingSortMode.entries.forEach { mode ->
                                            DropdownMenuItem(
                                                text = { Text(mode.label()) },
                                                onClick = {
                                                    sortMode = mode
                                                    sortExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (data.holdings.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.no_holdings_found),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextSecondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
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

                        item {
                            Spacer(modifier = Modifier.height(32.dp))
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
    val profitColor = if (isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    val stockEvalAmount = data.summary.totalAssetsKrw - data.summary.totalCashKrw

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PremiumCard {
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.stock_evaluation_amount),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    letterSpacing = 1.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = formatCurrencyAmount(stockEvalAmount, currencyMode, data.summary.usdExchangeRate),
                    style = MaterialTheme.typography.displayMedium,
                    color = TextGold,
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.profit_loss),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            letterSpacing = 0.5.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = formatCurrencyAmount(data.summary.totalProfitKrw, currencyMode, data.summary.usdExchangeRate, signed = true),
                            style = MaterialTheme.typography.titleLarge,
                            color = profitColor,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.return_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            letterSpacing = 0.5.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = formatSignedPercent(data.summary.totalProfitRate),
                            style = MaterialTheme.typography.titleLarge,
                            color = profitColor,
                        )
                    }
                }
            }
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
    val isPositive = holding.profitLossKrw >= 0
    val profitColor = if (isPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val badgeColors = when (holding.market) {
        "KOR" -> Color(0x263B82F6) to Color(0xFF60A5FA)
        "USA" -> Color(0x26F43F5E) to Color(0xFFFB7185)
        "JPN" -> Color(0x26A855F7) to Color(0xFFC084FC)
        else -> Color.White.copy(alpha = 0.1f) to Color.White.copy(alpha = 0.7f)
    }

    PremiumListItem(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = holding.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColors.first)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = holding.market,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColors.second,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.share_count, formatWholeNumber(holding.quantity)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatCurrencyAmount(holding.totalValueKrw, currencyMode, usdRate),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatSignedPercent(holding.profitLossRate),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = profitColor,
            )
        }
    }
}
