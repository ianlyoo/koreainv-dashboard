package com.koreainv.dashboard.network

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class CapitalGainsTaxTest {
    private fun payment(amount: String, date: String = "2026-06-01", fx: String? = null, currency: String = "KRW") =
        TaxPayment(BigDecimal(amount), currency, LocalDate.parse(date), fx?.let(::BigDecimal))
    private fun sale(proceeds: String = "6000000", cost: String = "1000000", broker: String = Broker.KIS) =
        Trade("20260601", "매도", "FAKE", "합성 종목", "USA", "KRW", 1.0, 1.0, 1.0, 1.0, null, null,
            broker = broker, capitalGainsBasis = CapitalGainsBasis(payment(proceeds), listOf(payment(cost)), emptyList(), true))
    private fun history(vararg trades: Trade, errors: List<String> = emptyList()) = TradeHistoryResponse(
        TradePeriod("2025-12-01", "2027-01-31", "세금 조회"), TradeSummary(0.0, 0.0, 0.0, 0.0),
        trades.toList(), accountErrors = errors)
    private fun estimate(vararg trades: Trade) = estimateCapitalGainsTax(2026, history(*trades))
    private fun equalsKrw(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))

    @Test fun lossYearHasNoTax() {
        val result = estimate(sale("500000", "1000000"))
        equalsKrw("-500000", result.netGainKrw); equalsKrw("0", result.estimatedTaxKrw)
    }
    @Test fun gainBelowDeductionHasNoTax() {
        val result = estimate(sale("2000000", "1000000"))
        equalsKrw("0", result.taxableBaseKrw); equalsKrw("0", result.estimatedTaxKrw)
    }
    @Test fun gainAboveDeductionUsesTwentyTwoPercent() {
        val result = estimate(sale())
        equalsKrw("2500000", result.taxableBaseKrw); equalsKrw("550000", result.estimatedTaxKrw)
    }
    @Test fun bothBrokersShareOneDeductionAndOffsetLosses() {
        val result = estimate(sale(), sale("500000", "1000000", Broker.TOSS))
        equalsKrw("4500000", result.netGainKrw); equalsKrw("440000", result.estimatedTaxKrw)
        assertEquals(2, result.eligibleCount)
    }
    @Test fun ownSettlementFxCanFlipNativeLossToKrwGain() {
        val trade = sale().copy(currency = "USD", realizedProfitKrw = -140000.0,
            capitalGainsBasis = CapitalGainsBasis(payment("900", fx = "1400", currency = "USD"),
                listOf(payment("1000", "2025-10-01", "1200", "USD")), emptyList(), true))
        equalsKrw("60000", estimate(trade).netGainKrw)
    }
    @Test fun missingFxIsEstimatedAndNeverZeroTax() {
        val trade = sale().copy(capitalGainsBasis = CapitalGainsBasis(payment("1000", currency = "USD"),
            listOf(payment("500", fx = "1300", currency = "USD")), emptyList(), true))
        val result = estimate(trade)
        assertEquals(1, result.estimatedCount); assertEquals(1, result.missingCount)
        assertNull(result.netGainKrw); assertNull(result.estimatedTaxKrw)
    }
    @Test fun domesticIsExemptAndDoesNotOffsetOverseasGain() {
        val result = estimate(sale(), sale("0", "10000000").copy(market = "KOR"))
        assertEquals(1, result.domesticCount); equalsKrw("550000", result.estimatedTaxKrw)
        assertTrue(result.rows.last().exempt)
    }
    @Test fun settlementYearWinsOverFillDate() {
        val trade = sale().copy(date = "20251231", capitalGainsBasis = CapitalGainsBasis(
            payment("6000000", "2026-01-02"), listOf(payment("1000000", "2025-01-02")), emptyList(), true))
        assertEquals(1, estimate(trade).eligibleCount)
        assertTrue(estimateCapitalGainsTax(2025, history(trade)).rows.isEmpty())
    }
    @Test fun buyAndSellFeesUseTheirOwnFx() {
        val trade = sale().copy(capitalGainsBasis = CapitalGainsBasis(payment("1000", fx = "1400", currency = "USD"),
            listOf(payment("500", "2025-01-01", "1200", "USD")),
            listOf(payment("10", "2025-01-01", "1200", "USD"), payment("5", fx = "1400", currency = "USD")), true))
        equalsKrw("781000", estimate(trade).netGainKrw)
    }
    @Test fun exFxDisplayProfitCannotSupplyMissingTaxBasis() {
        val result = estimate(sale().copy(capitalGainsBasis = null, realizedProfitKrw = 10000000.0,
            realizedProfitEstimated = true, profitExchangeRate = 1400.0))
        assertNull(result.estimatedTaxKrw); assertEquals(1, result.estimatedCount)
    }
    @Test fun estimatedBasisIsPricedButCountedSeparately() {
        val trade = sale().copy(realizedProfitEstimated = true)
        val result = estimate(trade)
        assertEquals(0, result.eligibleCount); assertEquals(1, result.estimatedCount)
        equalsKrw("550000", result.estimatedTaxKrw); assertTrue(result.incomplete)
    }
    @Test fun unknownFeesRemainUnpricedAndVisible() {
        val trade = sale().let { it.copy(capitalGainsBasis = it.capitalGainsBasis!!.copy(costsComplete = false)) }
        assertEquals(1, estimate(trade).missingCount)
    }
    @Test fun partialBrokerFailureIsNeverCompleteZero() {
        val result = estimateCapitalGainsTax(2026, history(errors = listOf("합성 오류")))
        assertTrue(result.incomplete); assertNull(result.estimatedTaxKrw)
    }
    @Test fun queriesCoverSettlementBoundaryAndStopAtToday() {
        val period = capitalGainsQueryPeriod(2025, LocalDate.of(2026, 10, 5))
        assertEquals(LocalDate.of(2024, 12, 1), period.first)
        assertEquals(LocalDate.of(2026, 1, 31), period.second)
        assertEquals(LocalDate.of(2026, 10, 5), capitalGainsQueryPeriod(2026, LocalDate.of(2026, 10, 5)).second)
    }
    @Test fun invalidNegativePaymentIsUnpriced() {
        val trade = sale().let { it.copy(capitalGainsBasis = it.capitalGainsBasis!!.copy(proceeds = payment("-1"))) }
        assertNull(estimate(trade).estimatedTaxKrw)
    }
    @Test fun yenFxIsPerOneYen() {
        val trade = sale().copy(capitalGainsBasis = CapitalGainsBasis(payment("10000", fx = "9", currency = "JPY"),
            listOf(payment("5000", "2025-01-01", "8", "JPY")), emptyList(), true))
        equalsKrw("50000", estimate(trade).netGainKrw)
    }
    private class TaxSource(private val response: TradeHistoryResponse, private val cancel: Boolean = false) : DashboardDataSource {
        var requestedRange: String? = null
        var requestedAccount: String? = "unrequested"
        override fun peekDashboard(): DashboardResponse? = null
        override suspend fun fetchDashboard(forceRefresh: Boolean): DashboardResponse = error("unused")
        override suspend fun refreshDashboardQuotes(): DashboardResponse? = null
        override fun peekTradeHistory(range: String, accountId: String?): TradeHistoryResponse? = null
        override suspend fun fetchTradeHistory(range: String, accountId: String?, forceRefresh: Boolean,
            onSummaryReady: (suspend (TradeHistoryResponse) -> Unit)?): TradeHistoryResponse {
            requestedRange = range
            requestedAccount = accountId
            if (cancel) throw CancellationException("synthetic cancellation")
            return response
        }
    }
    @Test fun taxDataBoundaryAlwaysRequestsBothBrokersForSelectedYear() = runBlocking {
        val source = TaxSource(history(sale(), sale(broker = Broker.TOSS)))
        assertTrue(estimateCapitalGainsTax(2025, source.fetchCapitalGainsHistory(2025)).rows.isEmpty())
        assertEquals("tax:2025", source.requestedRange)
        assertNull(source.requestedAccount)
        assertEquals(2, source.fetchCapitalGainsHistory(2026).trades.size)
        assertEquals("tax:2026", source.requestedRange)
        assertNull(source.requestedAccount)
    }
    @Test fun taxDataBoundaryPropagatesCancellation() = runBlocking {
        val source = TaxSource(history(), cancel = true)
        try {
            source.fetchCapitalGainsHistory(2026)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals("tax:2026", source.requestedRange)
        }
    }
}
