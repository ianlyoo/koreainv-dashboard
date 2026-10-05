package com.koreainv.dashboard.network

import com.google.gson.JsonParser
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TaxReviewFixesTest {
    private fun json(raw: String) = JsonParser.parseString(raw).asJsonObject
    private fun history(reasons: Set<HistoryIncompleteReason> = emptySet(), trades: List<Trade> = emptyList()) =
        TradeHistoryResponse(TradePeriod("20260101", "20261231", "test"), TradeSummary(0.0, 0.0, 0.0, 0.0), trades,
            historyCompleteness = listOf(AccountHistoryCompleteness("fake", "가짜 계좌", reasons)))
    private fun incomplete(reasons: Set<HistoryIncompleteReason>) {
        val result = estimateCapitalGainsTax(2026, history(reasons))
        assertTrue(result.historyIncomplete)
        assertNull(result.estimatedTaxKrw)
    }

    @Test fun malformedProxyCannotBecomeZero() {
        incomplete(tossProxyHistoryReasons(json("""{"items":{},"profit_history_complete":true}""")))
        incomplete(tossProxyHistoryReasons(json("""{}""")))
        incomplete(tossProxyHistoryReasons(json("""{"items":[42],"profit_history_complete":true}""")))
    }
    @Test fun emptyIncompleteProxyCannotBecomeZero() {
        incomplete(tossProxyHistoryReasons(json("""{"items":[],"profit_history_complete":false}""")))
    }
    @Test fun completeHistoryWithNoSalesIsZero() {
        val result = estimateCapitalGainsTax(2026, history(tossProxyHistoryReasons(
            json("""{"items":[],"profit_history_complete":true}"""))))
        assertFalse(result.historyIncomplete)
        assertEquals(BigDecimal.ZERO, result.estimatedTaxKrw)
    }
    @Test fun missingAndRepeatedTossCursorsAreIncomplete() {
        val missing = HistoryPagination(100)
        assertFalse(missing.advance(true, "", 1))
        incomplete(setOf(missing.reason!!))
        val repeated = HistoryPagination(100)
        assertTrue(repeated.advance(true, "same", 1))
        assertFalse(repeated.advance(true, "same", 2))
        incomplete(setOf(repeated.reason!!))
    }
    @Test fun tossPaginationExhaustionIsIncomplete() {
        val pages = HistoryPagination(100)
        for (n in 1..99) assertTrue(pages.advance(true, "$n", n))
        assertFalse(pages.advance(true, "100", 100))
        assertEquals(HistoryIncompleteReason.PAGE_LIMIT, pages.reason)
        incomplete(setOf(pages.reason!!))
    }
    @Test fun kisTenPageCapIsIncompleteButTenthPageEofIsComplete() {
        val capped = HistoryPagination(10)
        for (n in 1..9) assertTrue(capped.advance(true, "fk|$n", n))
        assertFalse(capped.advance(true, "fk|10", 10))
        incomplete(setOf(capped.reason!!))
        val finished = HistoryPagination(10)
        assertFalse(finished.advance(false, "", 10))
        assertNull(finished.reason)
    }
    @Test fun nonemptyTruncatedHistoryKeepsPartialAmountAndWarning() {
        val payment = TaxPayment(BigDecimal("6000000"), "KRW", java.time.LocalDate.of(2026, 6, 1))
        val trade = Trade("20260601", "매도", "FAKE", "가짜", "USA", "USD", 1.0, 1.0, 1.0, 1.0, null, null,
            capitalGainsBasis = CapitalGainsBasis(payment, listOf(payment.copy(amountNative = BigDecimal("1000000"))), emptyList(), true))
        val result = estimateCapitalGainsTax(2026, history(setOf(HistoryIncompleteReason.PAGE_LIMIT), listOf(trade)))
        assertTrue(result.historyIncomplete)
        assertEquals(BigDecimal("550000"), result.estimatedTaxKrw)
    }
    @Test fun allTimeoutsStopAfterTwoWithinOverallBudget() = runBlocking {
        val calls = AtomicInteger()
        val start = System.nanoTime()
        val rates = hydrateTaxFx((1..500).map { "$it" }, budgetMillis = 300, requestMillis = 20) {
            calls.incrementAndGet(); delay(15_000); 1.0
        }
        assertEquals(2, calls.get())
        assertTrue(rates.values.all { it == 0.0 })
        assertTrue((System.nanoTime() - start) / 1_000_000 < 300)
    }
    @Test fun requestBudgetAndOverallDeadlineRetainOnlyCompletedRates() = runBlocking {
        val rates = hydrateTaxFx((1..500).map { "$it" }, maxRequests = 3) { 1300.0 }
        assertEquals(3, rates.size)
        val timed = hydrateTaxFx((1..500).map { "$it" }, budgetMillis = 40, requestMillis = 1000) {
            delay(15); 1300.0
        }
        assertTrue(timed.size in 1..3)
    }
    @Test fun cancellingAnnualHydrationStopsQuotesAndPropagates() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val job = launch {
            try { hydrateTaxFx(listOf("first", "second")) { entered.complete(Unit); awaitCancellation() } }
            finally { stopped.complete(Unit) }
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertTrue(stopped.isCompleted)
    }
    @Test fun concurrentTradeQuoteDoesNotWaitForTaxHydrationCacheLock() = runBlocking {
        val cache = TossHistoricalFxCache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tax = launch { cache.getOrLoad("fake", "2026-06-01T00:00:00Z", { 0L }) {
            entered.complete(Unit); release.await(); null
        } }
        entered.await()
        try {
            val ordinary = withTimeout(300) {
                cache.getOrLoad("fake", "2026-09-01T00:00:00Z", { 0L }) { null }
            }
            assertEquals(0.0, ordinary, 0.0)
        } finally { release.complete(Unit); tax.join() }
    }
    @Test fun failedFxDoesNotChangeConsecutiveSalesNativeCosts() {
        fun execution(key: String, date: String, side: String, amount: Double, rate: Double = 0.0) =
            TossExecutionForEstimate(key, date, "090000", "FAKE", side, "USD", 1.0, amount, 0.0, 0.0, saleMidRate = rate)
        val executions = listOf(execution("b1", "20260102", "BUY", 100.0), execution("b2", "20260103", "BUY", 200.0),
            execution("s1", "20260601", "SELL", 220.0, 1300.0), execution("s2", "20260602", "SELL", 220.0))
        val result = estimateTossRealizedProfit(executions, "20260101", "20261231", 1300.0)
        assertEquals(300.0, result.nativeCostsByExecutionKey.values.sum(), 0.00001)
        assertEquals(150.0, result.nativeCostsByExecutionKey.getValue("s2"), 0.00001)
        assertFalse(result.profitsByExecutionKey.containsKey("s2"))
        val inputs = executions.map { e -> parseTossTaxExecution(json("""{"order_no":"${e.key}","date":"${e.date}","side":"${e.side}","symbol":"FAKE","currency":"USD","quantity":1,"amount_native":${e.amountNative},"commission_native":0,"tax_native":0}"""))!!
            .copy(nativeCost = result.nativeCostsByExecutionKey[e.key]?.let(BigDecimal::valueOf),
                fx = e.saleMidRate.takeIf { it > 0 }?.let(BigDecimal::valueOf)) }
        val bases = buildCapitalGainsBases(inputs, 1300.0)
        val nativeTotal = bases.values.flatMap { it.acquisitions }.fold(BigDecimal.ZERO) { sum, p -> sum + p.amountNative }
        assertEquals(0, BigDecimal("300").compareTo(nativeTotal))
    }
}
