package com.koreainv.dashboard.network

/** A fallback amount can be shown now, but must not become the session's final FX result. */
internal suspend fun loadAnnualTaxHistory(
    year: Int,
    forceRefresh: Boolean,
    cached: TradeHistoryResponse?,
    save: (TradeHistoryResponse?) -> Unit,
    load: suspend () -> TradeHistoryResponse,
): TradeHistoryResponse {
    if (!forceRefresh && cached != null && !estimateCapitalGainsTax(year, cached).fxSubstituted) return cached
    val result = load()
    save(result.takeUnless { estimateCapitalGainsTax(year, it).fxSubstituted })
    return result
}
