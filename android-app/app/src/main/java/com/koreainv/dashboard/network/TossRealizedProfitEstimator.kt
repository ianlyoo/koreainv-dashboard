package com.koreainv.dashboard.network

internal data class TossExecutionForEstimate(
    val key: String,
    val date: String,
    val time: String,
    val symbol: String,
    val side: String,
    val currency: String,
    val quantity: Double,
    val amountNative: Double,
    val commissionNative: Double,
    val taxNative: Double,
)

internal data class TossEstimatedProfit(
    val realizedProfitKrw: Double,
    val buyAmountKrw: Double,
    val returnRate: Double?,
)

internal data class TossProfitEstimateResult(
    val profitsByExecutionKey: Map<String, TossEstimatedProfit>,
    val domesticProfitKrw: Double,
    val overseasProfitKrw: Double,
    val totalBuyAmountKrw: Double,
    val profitAvailable: Boolean,
    val profitComplete: Boolean,
    val estimatedSellCount: Int,
    val unpricedSellCount: Int,
)

private data class MovingAveragePosition(
    var quantity: Double = 0.0,
    var costNative: Double = 0.0,
)

internal fun estimateTossRealizedProfit(
    executions: List<TossExecutionForEstimate>,
    startDate: String,
    endDate: String,
    usdExchangeRate: Double,
    historyComplete: Boolean = true,
): TossProfitEstimateResult {
    val positions = mutableMapOf<Pair<String, String>, MovingAveragePosition>()
    val unknownBasis = mutableSetOf<Pair<String, String>>()
    val profits = mutableMapOf<String, TossEstimatedProfit>()
    var selectedSellCount = 0
    var estimatedSellCount = 0
    var domesticProfit = 0.0
    var overseasProfit = 0.0
    var totalBuyAmount = 0.0

    executions.sortedWith(
        compareBy<TossExecutionForEstimate> { it.date }
            .thenBy { it.time }
            .thenBy { it.key },
    ).forEach { execution ->
        if (execution.symbol.isBlank() || execution.quantity <= 0.0) return@forEach
        val currency = execution.currency.uppercase()
        val positionKey = execution.symbol to currency
        val selected = execution.date in startDate..endDate
        when (execution.side.uppercase()) {
            "BUY", "매수" -> {
                if (positionKey in unknownBasis) return@forEach
                val position = positions.getOrPut(positionKey) { MovingAveragePosition() }
                position.quantity += execution.quantity
                position.costNative += execution.amountNative +
                    execution.commissionNative + execution.taxNative
            }

            "SELL", "매도" -> {
                if (selected) selectedSellCount += 1
                val position = positions[positionKey]
                if (
                    positionKey in unknownBasis ||
                    position == null ||
                    position.quantity + 1e-9 < execution.quantity
                ) {
                    unknownBasis += positionKey
                    positions.remove(positionKey)
                    return@forEach
                }

                val allocatedCost = position.costNative * (execution.quantity / position.quantity)
                val proceeds = execution.amountNative -
                    execution.commissionNative - execution.taxNative
                val profitNative = proceeds - allocatedCost
                position.quantity -= execution.quantity
                position.costNative -= allocatedCost
                if (position.quantity <= 1e-9) positions.remove(positionKey)
                if (!selected) return@forEach

                val exchangeRate = if (currency == "USD") usdExchangeRate else 1.0
                if (currency != "KRW" && exchangeRate <= 0.0) return@forEach
                val profitKrw = profitNative * exchangeRate
                val buyAmountKrw = allocatedCost * exchangeRate
                profits[execution.key] = TossEstimatedProfit(
                    realizedProfitKrw = profitKrw,
                    buyAmountKrw = buyAmountKrw,
                    returnRate = if (allocatedCost > 0.0) profitNative / allocatedCost * 100.0 else null,
                )
                estimatedSellCount += 1
                totalBuyAmount += buyAmountKrw
                if (currency == "KRW") domesticProfit += profitKrw
                else overseasProfit += profitKrw
            }
        }
    }

    return TossProfitEstimateResult(
        profitsByExecutionKey = profits,
        domesticProfitKrw = domesticProfit,
        overseasProfitKrw = overseasProfit,
        totalBuyAmountKrw = totalBuyAmount,
        profitAvailable = selectedSellCount == 0 || estimatedSellCount > 0,
        profitComplete = historyComplete && selectedSellCount == estimatedSellCount,
        estimatedSellCount = estimatedSellCount,
        unpricedSellCount = selectedSellCount - estimatedSellCount,
    )
}
