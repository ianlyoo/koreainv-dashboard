package com.koreainv.dashboard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetDistributionBuilderTest {
    private fun holding(symbol: String, value: Double, market: String = "USA", account: String = "one") = Holding(
        symbol = symbol, name = "Name $symbol", market = market, quantity = 1.0,
        currentPrice = value, averageCost = value, totalValueKrw = value, totalCostKrw = value,
        profitLossKrw = 0.0, profitLossRate = 0.0, currency = "USD", accountId = account,
    )

    @Test
    fun combinesAccountsAndBrokersThenSortsByCombinedValue() {
        val holdings = listOf(
            holding("MSFT", 500.0),
            holding(" aapl ", 300.0, "nasd").copy(name = "Apple"),
            holding("AAPL", 300.0, "USA", "two").copy(broker = Broker.TOSS, currency = "KRW"),
        )
        val result = buildAssetDistribution(holdings)
        assertEquals(listOf("AAPL", "MSFT"), result.map { it.symbol })
        assertEquals("Apple", result.first().name)
        assertEquals(600.0, result.first().valueKrw, 0.0001)
        assertEquals(600.0 / 1100.0 * 100.0, result.first().weightPercent, 0.0001)
        assertEquals(1100.0, result.sumOf { it.valueKrw }, 0.0001)
        assertEquals(100.0, result.sumOf { it.weightPercent }, 0.0001)
        assertEquals(3, holdings.size)
    }

    @Test
    fun canonicalMarketAliasesMergeButDifferentMarketsStaySeparate() {
        val result = buildAssetDistribution(listOf(
            holding("1234", 100.0, "KRX"), holding("1234", 200.0, "KOR", "two"),
            holding("1234", 400.0, "TSE"), holding("1234", 50.0, "USA"),
        ))
        assertEquals(listOf(400.0, 300.0, 50.0), result.map { it.valueKrw })
    }

    @Test
    fun missingSymbolsDoNotMergeUnidentifiedPositions() {
        val result = buildAssetDistribution(listOf(holding("", 100.0), holding(" ", 200.0)))
        assertEquals(2, result.size)
    }

    @Test
    fun emptyAndNonPositivePortfoliosHaveNoDistribution() {
        assertTrue(buildAssetDistribution(emptyList()).isEmpty())
        assertTrue(buildAssetDistribution(listOf(holding("AAPL", 0.0))).isEmpty())
        assertTrue(buildAssetDistribution(listOf(holding("AAPL", -1.0))).isEmpty())
    }

    @Test
    fun rebuildsCachedDistributionWithoutChangingHoldingsOrSummary() {
        val holdings = listOf(holding("AAPL", 100.0), holding("AAPL", 200.0, account = "two"))
        val summary = DashboardSummary(
            totalAssetsKrw = 350.0, totalPurchaseKrw = 300.0, totalProfitKrw = 0.0,
            totalProfitRate = 0.0, cashKrw = 50.0, totalCashKrw = 50.0, cashUsd = 0.0,
            cashJpy = 0.0, usdExchangeRate = 1300.0, domesticCount = 0, overseasCount = 2,
            lastSynced = "cached",
        )
        val cached = DashboardResponse(summary, holdings, listOf(
            AssetDistribution("AAPL", "Apple", 33.33, 100.0),
            AssetDistribution("AAPL", "Apple", 66.67, 200.0),
        ))
        val rebuilt = cached.withRebuiltAssetDistribution()
        assertEquals(1, rebuilt.assetDistribution.size)
        assertEquals(300.0, rebuilt.assetDistribution.single().valueKrw, 0.0001)
        assertEquals(100.0, rebuilt.assetDistribution.single().weightPercent, 0.0001)
        assertSame(holdings, rebuilt.holdings)
        assertSame(summary, rebuilt.summary)
    }
}
