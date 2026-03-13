package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.Holding
import com.koreainv.dashboard.network.KisRepository
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.TextGold
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

    fun loadHolding(forceRefresh: Boolean = false) {
        isLoading = true
        errorMessage = null
        scope.launch {
            runCatching { repository.fetchDashboard(forceRefresh = forceRefresh) }
                .onSuccess { dashboard ->
                    usdRate = dashboard.summary.usdExchangeRate
                    holding = dashboard.holdings.find { it.symbol == symbol }
                    if (holding == null) {
                        errorMessage = "종목 정보를 찾을 수 없습니다. [$symbol]"
                    }
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
            usdRate = cached.summary.usdExchangeRate
            holding = cached.holdings.find { it.symbol == symbol }
            isLoading = false
            if (holding == null) {
                loadHolding()
            }
        } else {
            loadHolding()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    InlineTitleWithSync(
                        title = stringResource(R.string.holding_detail),
                        lastSynced = repository.peekDashboard()?.summary?.lastSynced,
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
                    if (isLoading && holding != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp),
                            color = TextGold,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { loadHolding(forceRefresh = true) }) {
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
                isLoading && holding == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TextGold,
                    )
                }

                errorMessage != null && holding == null -> {
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
                            onClick = { loadHolding(forceRefresh = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = TextGold),
                        ) {
                            Text(stringResource(R.string.retry), color = Color.Black)
                        }
                    }
                }

                holding != null -> {
                    val data = holding!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        PremiumCard {
                            Column {
                                Text(
                                    text = data.name,
                                    style = MaterialTheme.typography.displaySmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "${data.market} · ${data.symbol}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Text(
                                    text = formatCurrencyAmount(data.totalValueKrw, CurrencyDisplayMode.KRW, usdRate),
                                    style = MaterialTheme.typography.displayMedium,
                                    color = TextGold,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        PremiumGlassCard {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                HoldingMetricRow(stringResource(R.string.current_price), formatHoldingUnitPrice(data.currentPrice, data.currency))
                                HoldingMetricRow(stringResource(R.string.average_cost), formatHoldingUnitPrice(data.averageCost, data.currency))
                                HoldingMetricRow(stringResource(R.string.quantity), formatWholeNumber(data.quantity))
                                HoldingMetricRow(stringResource(R.string.total_cost), formatCurrencyAmount(data.totalCostKrw, CurrencyDisplayMode.KRW, usdRate))
                                HoldingMetricRow(stringResource(R.string.total_value), formatCurrencyAmount(data.totalValueKrw, CurrencyDisplayMode.KRW, usdRate))
                                HoldingMetricRow(stringResource(R.string.profit_loss_amount), formatCurrencyAmount(data.profitLossKrw, CurrencyDisplayMode.KRW, usdRate, signed = true))
                                HoldingMetricRow(stringResource(R.string.profit_loss_percentage), formatSignedPercent(data.profitLossRate))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatHoldingUnitPrice(price: Double, currency: String): String {
    val formatter = if (currency == "USD") {
        NumberFormat.getNumberInstance(Locale.US).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 2
        }
    } else {
        NumberFormat.getNumberInstance(Locale.KOREA).apply {
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }
    }
    val prefix = when (currency) {
        "USD" -> "$"
        "JPY" -> "¥"
        else -> "₩"
    }
    return "$prefix${formatter.format(abs(price))}"
}

@Composable
private fun HoldingMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.64f))
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}
