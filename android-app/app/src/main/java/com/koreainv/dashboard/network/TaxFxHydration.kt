package com.koreainv.dashboard.network

import kotlinx.coroutines.withTimeoutOrNull

internal const val TAX_FX_BUDGET_MILLIS = 30_000L
internal const val TAX_FX_MAX_REQUESTS = 40
internal const val TAX_FX_REQUEST_MILLIS = 5_000L

/** Parent cancellation propagates. Budget/per-quote expiry retains completed rates only. */
internal suspend fun hydrateTaxFx(
    timestamps: List<String>,
    budgetMillis: Long = TAX_FX_BUDGET_MILLIS,
    maxRequests: Int = TAX_FX_MAX_REQUESTS,
    requestMillis: Long = TAX_FX_REQUEST_MILLIS,
    load: suspend (String) -> Double,
): Map<String, Double> {
    val rates = mutableMapOf<String, Double>()
    withTimeoutOrNull(budgetMillis) {
        var failures = 0
        for (timestamp in timestamps.distinct().take(maxRequests)) {
            val rate = withTimeoutOrNull(requestMillis) { load(timestamp) } ?: 0.0
            rates[timestamp] = rate
            failures = if (rate > 0.0 && rate.isFinite()) 0 else failures + 1
            if (failures >= 2) break
        }
    }
    return rates
}
