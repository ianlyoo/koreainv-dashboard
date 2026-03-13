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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
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
import com.koreainv.dashboard.network.Trade
import com.koreainv.dashboard.network.TradeHistoryResponse
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Success
import com.koreainv.dashboard.ui.theme.SurfaceBorder
import com.koreainv.dashboard.ui.theme.SurfaceGlassLight
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeHistoryScreen(
    repository: KisRepository,
    onBackClick: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var tradeData by remember { mutableStateOf<TradeHistoryResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var tradeFilter by remember { mutableStateOf("all") }
    var selectedRange by remember { mutableStateOf("this_month") }
    var rangeExpanded by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    var currencyMode by remember { mutableStateOf(CurrencyDisplayMode.KRW) }

    fun loadTradeHistory(range: String = selectedRange, forceRefresh: Boolean = false) {
        isLoading = true
        errorMessage = null
        coroutineScope.launch {
            runCatching { repository.fetchTradeHistory(range = range, forceRefresh = forceRefresh) }
                .onSuccess { tradeData = it }
                .onFailure {
                    val detail = it.message?.takeIf(String::isNotBlank) ?: it::class.simpleName ?: "unknown"
                    errorMessage = "거래내역을 불러오지 못했습니다. [$detail]"
                }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        val cached = repository.peekTradeHistory(selectedRange)
        if (cached != null) {
            tradeData = cached
            isLoading = false
        } else {
            loadTradeHistory()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    InlineTitleWithSync(
                        title = stringResource(R.string.trade_history_title),
                        lastSynced = tradeData?.lastSynced,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextGold,
                ),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                },
                actions = {
                    CompactCurrencyToggle(
                        mode = currencyMode,
                        onModeChange = { currencyMode = it },
                    )
                    if (isLoading && tradeData != null) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 10.dp, end = 16.dp)
                                .size(20.dp),
                            color = TextGold,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { loadTradeHistory(range = selectedRange, forceRefresh = true) }) {
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
                isLoading && tradeData == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextGold,
                    )
                }

                errorMessage != null && tradeData == null -> {
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
                            onClick = { loadTradeHistory() },
                            colors = ButtonDefaults.buttonColors(containerColor = TextGold),
                        ) {
                            Text(stringResource(R.string.retry), color = Color.Black)
                        }
                    }
                }

                tradeData != null -> {
                    val data = tradeData!!
                    val filteredTrades = data.trades.filter { trade ->
                        tradeFilter == "all" ||
                            (tradeFilter == "buy" && trade.side == stringResource(R.string.buy)) ||
                            (tradeFilter == "sell" && trade.side == stringResource(R.string.sell))
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            TradeSummaryCard(data, currencyMode)
                        }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box {
                                    TextButton(onClick = { rangeExpanded = true }) {
                                        Text(
                                            text = stringResource(R.string.trade_list_title, rangeLabel(selectedRange)),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White.copy(alpha = 0.8f),
                                            letterSpacing = 1.sp,
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = stringResource(R.string.trade_period),
                                            tint = Color.White.copy(alpha = 0.8f),
                                        )
                                    }
                                    DropdownMenu(expanded = rangeExpanded, onDismissRequest = { rangeExpanded = false }) {
                                        tradeRangeOptions().forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option.second) },
                                                onClick = {
                                                    rangeExpanded = false
                                                    selectedRange = option.first
                                                    val cachedRange = repository.peekTradeHistory(option.first)
                                                    if (cachedRange != null) {
                                                        tradeData = cachedRange
                                                        isLoading = false
                                                    } else {
                                                        loadTradeHistory(range = option.first)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                                Box {
                                    TextButton(onClick = { filterExpanded = true }) {
                                        Text(
                                            text = when (tradeFilter) {
                                                "buy" -> stringResource(R.string.buy)
                                                "sell" -> stringResource(R.string.sell)
                                                else -> stringResource(R.string.all)
                                            },
                                            color = TextGold,
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = stringResource(R.string.trade_filter),
                                            tint = TextGold,
                                        )
                                    }
                                    DropdownMenu(expanded = filterExpanded, onDismissRequest = { filterExpanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.all)) },
                                            onClick = { tradeFilter = "all"; filterExpanded = false },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.buy)) },
                                            onClick = { tradeFilter = "buy"; filterExpanded = false },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.sell)) },
                                            onClick = { tradeFilter = "sell"; filterExpanded = false },
                                        )
                                    }
                                }
                            }
                        }

                        if (filteredTrades.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.no_trades_found),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            items(filteredTrades) { trade ->
                                TradeItemCard(trade = trade, currencyMode = currencyMode, usdRate = data.usdExchangeRate)
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

@Composable
fun TradeSummaryCard(data: TradeHistoryResponse, currencyMode: CurrencyDisplayMode) {
    val isPositive = data.summary.totalRealizedProfitKrw >= 0
    val profitColor = when {
        data.summary.totalRealizedProfitKrw > 0 -> Success
        data.summary.totalRealizedProfitKrw < 0 -> MaterialTheme.colorScheme.error
        else -> TextPrimary
    }

    PremiumCard {
        Column {
            Text(
                text = stringResource(R.string.realized_profit),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                letterSpacing = 1.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatCurrencyAmount(data.summary.totalRealizedProfitKrw, currencyMode, data.usdExchangeRate, signed = true),
                style = MaterialTheme.typography.displayMedium,
                color = profitColor,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.domestic),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatCurrencyAmount(data.summary.domesticRealizedProfitKrw, currencyMode, data.usdExchangeRate, signed = true),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (data.summary.domesticRealizedProfitKrw > 0) Success else if (data.summary.domesticRealizedProfitKrw < 0) MaterialTheme.colorScheme.error else TextPrimary,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.overseas),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatCurrencyAmount(data.summary.overseasRealizedProfitKrw, currencyMode, data.usdExchangeRate, signed = true),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (data.summary.overseasRealizedProfitKrw > 0) Success else if (data.summary.overseasRealizedProfitKrw < 0) MaterialTheme.colorScheme.error else TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
fun TradeItemCard(trade: Trade, currencyMode: CurrencyDisplayMode, usdRate: Double) {
    val isBuy = trade.side == "매수"
    val typeColor = if (isBuy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    PremiumListItem {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = trade.side,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = typeColor,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = trade.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = trade.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.share_quantity_price,
                    formatWholeNumber(trade.quantity),
                    formatTradeUnitPrice(trade, currencyMode, usdRate),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatTradeAmount(trade, currencyMode, usdRate),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            if (trade.realizedProfitKrw != null && !isBuy) {
                Spacer(modifier = Modifier.height(6.dp))
                val isProfitPositive = trade.realizedProfitKrw >= 0
                val profitColor = if (isProfitPositive) Success else MaterialTheme.colorScheme.error
                Text(
                    text = formatCurrencyAmount(trade.realizedProfitKrw, currencyMode, usdRate, signed = true),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = profitColor,
                )
            }
        }
    }
}

private fun formatTradeAmount(trade: Trade, currencyMode: CurrencyDisplayMode, usdRate: Double): String {
    return when {
        currencyMode == CurrencyDisplayMode.USD && trade.currency == "USD" -> "$${formatUsdNumber(trade.amountNative)}"
        currencyMode == CurrencyDisplayMode.KRW && trade.currency == "USD" -> formatCurrencyAmount(trade.amountKrw, CurrencyDisplayMode.KRW, usdRate)
        currencyMode == CurrencyDisplayMode.KRW && trade.currency == "JPY" -> formatCurrencyAmount(trade.amountKrw, CurrencyDisplayMode.KRW, usdRate)
        else -> formatCurrencyAmount(trade.amountKrw, currencyMode, usdRate)
    }
}

private fun formatTradeUnitPrice(trade: Trade, currencyMode: CurrencyDisplayMode, usdRate: Double): String {
    val safeUsdRate = usdRate.takeIf { it > 0.0 } ?: 1350.0
    return when {
        trade.currency == "USD" && currencyMode == CurrencyDisplayMode.USD -> "$${formatUsdNumber(trade.unitPrice)}"
        trade.currency == "USD" && currencyMode == CurrencyDisplayMode.KRW -> "₩${formatWholeNumber(trade.unitPrice * safeUsdRate)}"
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
