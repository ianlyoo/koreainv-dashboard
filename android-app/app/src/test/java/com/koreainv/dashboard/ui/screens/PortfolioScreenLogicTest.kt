package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.Holding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortfolioScreenLogicTest {
    private val firstAccountHolding = holding(
        symbol = "AAPL",
        accountId = "account-a",
        accountLabel = "연금 계좌",
        value = 100.0,
        returnRate = 5.0,
        profit = 10.0,
    )
    private val secondAccountHolding = holding(
        symbol = "AAPL",
        accountId = "account-b",
        accountLabel = "일반 계좌",
        value = 200.0,
        returnRate = 2.0,
        profit = 30.0,
    )

    @Test
    fun accountFilters_areUniqueAndKeepDisplayOrder() {
        val filters = holdingAccountFilters(
            listOf(firstAccountHolding, secondAccountHolding, firstAccountHolding.copy(symbol = "MSFT")),
        )

        assertEquals(
            listOf(
                HoldingAccountFilter("account-a", "연금 계좌"),
                HoldingAccountFilter("account-b", "일반 계좌"),
            ),
            filters,
        )
    }

    @Test
    fun filteringAndSorting_applyToTheSelectedAccountOnly() {
        val result = filterAndSortHoldings(
            holdings = listOf(firstAccountHolding, secondAccountHolding),
            accountId = "account-a",
            sortMode = HoldingSortMode.PROFIT,
        )

        assertEquals(listOf(firstAccountHolding), result)
    }

    @Test
    fun detailLookup_disambiguatesTheSameSymbolByAccount() {
        val holdings = listOf(firstAccountHolding, secondAccountHolding)

        assertEquals(secondAccountHolding, findHolding(holdings, "AAPL", "account-b"))
        assertNull(findHolding(holdings, "AAPL", "missing"))
    }

    @Test
    fun longAccountLabels_areCompactedForTheFilterButton() {
        assertEquals("장기투자계좌", compactAccountFilterLabel("장기투자계좌"))
        assertEquals("장기투자용계좌…", compactAccountFilterLabel("장기투자용계좌이름"))
    }

    private fun holding(
        symbol: String,
        accountId: String,
        accountLabel: String,
        value: Double,
        returnRate: Double,
        profit: Double,
    ) = Holding(
        symbol = symbol,
        name = symbol,
        market = "USA",
        quantity = 1.0,
        currentPrice = value,
        averageCost = value - profit,
        totalValueKrw = value,
        totalCostKrw = value - profit,
        profitLossKrw = profit,
        profitLossRate = returnRate,
        currency = "USD",
        accountLabel = accountLabel,
        accountId = accountId,
    )
}
