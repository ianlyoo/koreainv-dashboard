package com.koreainv.dashboard.ui.screens

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.Trade
import com.koreainv.dashboard.ui.theme.Background
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.Success
import com.koreainv.dashboard.ui.theme.TextGold
import com.koreainv.dashboard.ui.theme.TextPrimary
import com.koreainv.dashboard.ui.theme.TextSecondary
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeDetailScreen(
    trade: Trade,
    usdRate: Double,
    lastSynced: String?,
    onBackClick: () -> Unit,
) {
    val isBuy = trade.side == stringResource(R.string.buy)
    val sideTone = if (isBuy) AccentTone.Positive else AccentTone.Negative
    val sideColor = if (isBuy) Success else Error
    val hasSupplementaryMetrics = trade.realizedProfitKrw != null || trade.returnRate != null

    Scaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.trade_detail),
                lastSynced = lastSynced,
                navigationButton = {
                    HeaderIconButton(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        onClick = onBackClick,
                    )
                },
            )
        },
        containerColor = Background,
    ) { paddingValues ->
        ScreenBackground(modifier = Modifier.padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                HeroTopSection {
                    SurfaceBadge(label = trade.side, tone = sideTone)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = trade.name,
                            style = MaterialTheme.typography.displaySmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${trade.market} · ${trade.ticker}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                    Text(
                        text = formatTradeAmountForDetail(trade, usdRate),
                        style = MaterialTheme.typography.displayLarge,
                        color = TextGold,
                    )
                    HeroMetricGroup {
                        HeroMetricRow(
                            primaryLabel = stringResource(R.string.quantity),
                            primaryValue = formatWholeNumber(trade.quantity),
                            secondaryLabel = stringResource(R.string.trade_currency),
                            secondaryValue = trade.currency,
                            secondaryValueColor = sideColor,
                        )
                    }
                }

                PremiumGlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TradeMetricRow(stringResource(R.string.trade_type), trade.side, valueColor = sideColor)
                        TradeMetricRow(stringResource(R.string.trade_date), trade.date)
                        TradeMetricRow(stringResource(R.string.ticker), trade.ticker)
                        TradeMetricRow(stringResource(R.string.trade_currency), trade.currency)
                        TradeMetricRow(stringResource(R.string.trade_unit_price), formatTradeUnitPriceForDetail(trade, usdRate))
                        TradeMetricRow(stringResource(R.string.trade_amount), formatTradeAmountForDetail(trade, usdRate))
                    }
                }

                if (hasSupplementaryMetrics) {
                    PremiumGlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            trade.realizedProfitKrw?.let {
                                TradeMetricRow(
                                    stringResource(R.string.realized_profit),
                                    formatCurrencyAmount(it, CurrencyDisplayMode.KRW, usdRate, signed = true),
                                    valueColor = if (it >= 0) Success else Error,
                                )
                            }
                            trade.returnRate?.let {
                                TradeMetricRow(
                                    stringResource(R.string.return_label),
                                    formatSignedPercent(it),
                                    valueColor = if (it >= 0) Success else Error,
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
private fun TradeMetricRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary,
) {
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

private fun formatTradeAmountForDetail(trade: Trade, usdRate: Double): String {
    return when {
        trade.currency == "USD" -> "$${formatUsdNumberForDetail(trade.amountNative)}"
        trade.currency == "JPY" -> "¥${formatWholeNumber(trade.amountNative)}"
        else -> formatCurrencyAmount(trade.amountKrw, CurrencyDisplayMode.KRW, usdRate)
    }
}

private fun formatTradeUnitPriceForDetail(trade: Trade, usdRate: Double): String {
    val safeUsdRate = usdRate.takeIf { it > 0.0 } ?: 1350.0
    return when (trade.currency) {
        "USD" -> "$${formatUsdNumberForDetail(trade.unitPrice)}"
        "JPY" -> "¥${formatWholeNumber(trade.unitPrice)}"
        "KRW" -> formatCurrencyAmount(trade.unitPrice, CurrencyDisplayMode.KRW, safeUsdRate)
        else -> formatCurrencyAmount(trade.unitPrice, CurrencyDisplayMode.KRW, safeUsdRate)
    }
}

private fun formatUsdNumberForDetail(value: Double): String =
    NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }.format(abs(value))
