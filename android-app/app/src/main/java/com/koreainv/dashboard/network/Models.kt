package com.koreainv.dashboard.network

data class AppCredentials(
    val appKey: String,
    val appSecret: String,
    val cano: String,
    val acntPrdtCd: String,
)

data class SetupInput(
    val appKey: String,
    val appSecret: String,
    val cano: String,
    val acntPrdtCd: String,
    val pin: String,
)

sealed interface AppLockState {
    data object NeedsSetup : AppLockState
    data object Locked : AppLockState
    data class Unlocked(val credentials: AppCredentials) : AppLockState
}

data class DashboardResponse(
    val summary: DashboardSummary,
    val holdings: List<Holding>,
    val assetDistribution: List<AssetDistribution>,
)

data class DashboardSummary(
    val totalAssetsKrw: Double,
    val totalPurchaseKrw: Double,
    val totalProfitKrw: Double,
    val totalProfitRate: Double,
    val cashKrw: Double,
    val totalCashKrw: Double,
    val cashUsd: Double,
    val cashJpy: Double,
    val usdExchangeRate: Double,
    val domesticCount: Int,
    val overseasCount: Int,
    val lastSynced: String,
)

data class Holding(
    val symbol: String,
    val name: String,
    val market: String,
    val quantity: Double,
    val currentPrice: Double,
    val averageCost: Double,
    val totalValueKrw: Double,
    val totalCostKrw: Double,
    val profitLossKrw: Double,
    val profitLossRate: Double,
    val currency: String,
)

data class AssetDistribution(
    val symbol: String,
    val name: String,
    val weightPercent: Double,
    val valueKrw: Double,
)

data class TradeHistoryResponse(
    val period: TradePeriod,
    val summary: TradeSummary,
    val trades: List<Trade>,
    val lastSynced: String = "",
    val usdExchangeRate: Double = 1350.0,
)

data class TradePeriod(
    val start: String,
    val end: String,
    val label: String,
)

data class TradeSummary(
    val totalRealizedProfitKrw: Double,
    val domesticRealizedProfitKrw: Double,
    val overseasRealizedProfitKrw: Double,
    val totalRealizedReturnRate: Double,
)

data class Trade(
    val date: String,
    val side: String,
    val ticker: String,
    val name: String,
    val market: String,
    val currency: String,
    val quantity: Double,
    val unitPrice: Double,
    val amountNative: Double,
    val amountKrw: Double,
    val realizedProfitKrw: Double?,
    val returnRate: Double?,
)

data class AuthToken(
    val value: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
)
