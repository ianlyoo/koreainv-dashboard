package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.koreainv.dashboard.R
import com.koreainv.dashboard.network.Trade
import com.koreainv.dashboard.ui.theme.Error
import com.koreainv.dashboard.ui.theme.Success
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
    val currencyPreference = rememberCurrencyPreference()
    val currencyMode = currencyPreference.mode
    val isBuy = trade.side == stringResource(R.string.buy)
    val sideColor = if (isBuy) Success else Error
    val hasSupplementaryMetrics = trade.realizedProfitKrw != null || trade.returnRate != null

    DashboardScaffold(
        topBar = {
            DashboardTopBar(
                title = stringResource(R.string.trade_detail),
                lastSynced = lastSynced,
                actions = {
                    CompactCurrencyToggle(mode = currencyMode, onModeChange = currencyPreference.onModeChange)
                },
                navigationButton = {
                    HeaderIconButton(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        onClick = onBackClick,
                    )
                },
            )
        },
    ) { paddingValues ->
        ScreenBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = paddingValues.calculateTopPadding() + 8.dp, bottom = paddingValues.calculateBottomPadding() + 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                HeroTopSection {
                    Text(trade.side, style = MaterialTheme.typography.labelLarge, color = sideColor)
                    Text(
                        text = trade.accountLabel.takeIf(String::isNotBlank) ?: "계좌 이름 없음",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = trade.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${trade.market} · ${trade.ticker}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                    FullMonetaryValue(
                        label = "${stringResource(R.string.trade_amount)} (${currencyMode.name})",
                        value = formatTradeAmount(trade, currencyMode, usdRate),
                        valueColor = TextPrimary,
                    )
                    HeroMetricGroup {
                        ResponsiveDetailRow("거래 수량", formatWholeNumber(trade.quantity))
                        ResponsiveDetailRow(stringResource(R.string.trade_currency), trade.currency)
                    }
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ResponsiveDetailRow(stringResource(R.string.trade_type), trade.side, valueColor = sideColor)
                        ResponsiveDetailRow(stringResource(R.string.trade_date), trade.date)
                        ResponsiveDetailRow(stringResource(R.string.ticker), trade.ticker)
                        ResponsiveDetailRow(stringResource(R.string.trade_currency), trade.currency)
                        ResponsiveDetailRow("${stringResource(R.string.trade_unit_price)} (${trade.currency})", formatTradeUnitPriceForDetail(trade, usdRate))
                        ResponsiveDetailRow("거래 통화 금액 (${trade.currency})", formatTradeAmountForDetail(trade, usdRate))
                    }
                }

                if (hasSupplementaryMetrics) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            trade.realizedProfitKrw?.let {
                                ResponsiveDetailRow(
                                    if (trade.realizedProfitEstimated) "실현 손익(추정)" else stringResource(R.string.realized_profit),
                                    formatCurrencyAmount(it, currencyMode, usdRate, signed = true),
                                    valueColor = if (it >= 0) Success else Error,
                                )
                            }
                            trade.returnRate?.let {
                                ResponsiveDetailRow(
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
