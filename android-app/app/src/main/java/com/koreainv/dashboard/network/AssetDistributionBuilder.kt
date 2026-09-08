package com.koreainv.dashboard.network

import java.util.Locale

/** Account and broker identify a position, but do not change the asset's allocation. */
internal fun buildAssetDistribution(holdings: List<Holding>): List<AssetDistribution> {
    val total = holdings.sumOf { it.totalValueKrw }
    if (total <= 0.0) return emptyList()
    return holdings.withIndex().groupBy { (index, holding) ->
        val symbol = holding.symbol.trim().uppercase(Locale.ROOT)
        // Missing symbols cannot establish that two positions are the same instrument.
        Triple(marketBadgeCode(holding.market), symbol, if (symbol.isBlank()) index else -1)
    }.map { (identity, indexedPositions) ->
        val positions = indexedPositions.map { it.value }
        val value = positions.sumOf { it.totalValueKrw }
        AssetDistribution(
            symbol = identity.second,
            name = positions.first().name,
            weightPercent = value / total * 100.0,
            valueKrw = value,
        )
    }.sortedByDescending { it.valueKrw }
}

internal fun DashboardResponse.withRebuiltAssetDistribution(): DashboardResponse =
    copy(assetDistribution = buildAssetDistribution(holdings))

internal fun marketBadgeCode(code: String): String = when (val normalized = code.trim().uppercase(Locale.ROOT)) {
    "KOR", "KRX", "J", "UN", "NX" -> "KOR"
    "USA", "NAS", "NASD", "NYS", "NYSE", "AMS", "AMEX" -> "USA"
    "JPN", "TSE", "TKSE", "JPX", "TYO" -> "JPN"
    else -> normalized
}
