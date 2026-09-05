package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.Holding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

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

    @Test
    fun missingAccountSelection_keepsItsScopeUntilExplicitReset() {
        val selection = resolveAccountSelection("removed", emptyList())

        assertTrue(selection.unavailable)
        assertEquals("removed", selection.accountId)
        assertNull(selection.label)
        assertTrue(filterAndSortHoldings(listOf(firstAccountHolding), selection.accountId, HoldingSortMode.VALUE).isEmpty())
        assertFalse(resolveAccountSelection(null, emptyList()).unavailable)
    }

    @Test
    fun lateCancelledRequest_cannotOverwriteNewResultOrFinishItsLoadingState() = runBlocking {
        val owner = ScreenRequestOwner()
        val releaseOldRequest = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        owner.launch(
            scope = this,
            load = {
                // Model a transport that returns even after cancellation.
                withContext(NonCancellable) { releaseOldRequest.await() }
                "old"
            },
            onSuccess = { events += it },
            onFailure = { events += "old error" },
            onFinished = { events += "old finished" },
        )
        yield()
        val oldVersion = owner.version
        owner.launch(
            scope = this,
            load = { "new" },
            onSuccess = { events += it },
            onFailure = { events += "new error" },
            onFinished = { events += "new finished" },
        )
        releaseOldRequest.complete(Unit)
        yield()

        assertFalse(owner.accepts(oldVersion))
        assertEquals(listOf("new", "new finished"), events)
    }

    @Test
    fun disposingRequest_cancelsWorkWithoutPublishingFailure() = runBlocking {
        val owner = ScreenRequestOwner()
        var wasCancelled = false
        val events = mutableListOf<String>()
        owner.launch(
            scope = this,
            load = {
                try {
                    awaitCancellation()
                } finally {
                    wasCancelled = true
                }
            },
            onSuccess = { events += "success" },
            onFailure = { events += "error" },
            onFinished = { events += "finished" },
        )
        yield()
        owner.cancel()
        yield()

        assertTrue(wasCancelled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancellationException_isNotReportedAsRefreshFailure() = runBlocking {
        val owner = ScreenRequestOwner()
        var failed = false
        var finished = false
        owner.launch(
            scope = this,
            load = { throw CancellationException("screen left") },
            onSuccess = { },
            onFailure = { failed = true },
            onFinished = { finished = true },
        )
        yield()

        assertFalse(failed)
        assertTrue(finished)
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
