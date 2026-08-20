package com.koreainv.dashboard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TossRealizedProfitEstimatorTest {
    @Test
    fun `prior buys create moving-average cost basis with fees and taxes`() {
        val result = estimateTossRealizedProfit(
            executions = listOf(
                execution("buy-1", "20260601", "매수", 10.0, 1_000.0, commission = 10.0),
                execution("buy-2", "20260701", "매수", 10.0, 2_000.0, commission = 20.0),
                execution("sell", "20260802", "매도", 5.0, 1_250.0, commission = 5.0, tax = 10.0),
            ),
            startDate = "20260801",
            endDate = "20260831",
            usdExchangeRate = 1_400.0,
        )

        assertEquals(477.5, result.profitsByExecutionKey.getValue("sell").realizedProfitKrw, 0.0001)
        assertEquals(757.5, result.totalBuyAmountKrw, 0.0001)
        assertTrue(result.profitAvailable)
        assertTrue(result.profitComplete)
        assertEquals(0, result.unpricedSellCount)
    }

    @Test
    fun `sell without purchase history remains unpriced`() {
        val result = estimateTossRealizedProfit(
            executions = listOf(
                execution("sell", "20260802", "매도", 1.0, 200.0, currency = "USD"),
            ),
            startDate = "20260801",
            endDate = "20260831",
            usdExchangeRate = 1_400.0,
        )

        assertFalse(result.profitAvailable)
        assertFalse(result.profitComplete)
        assertEquals(1, result.unpricedSellCount)
    }

    @Test
    fun `US sell without exchange rate remains unpriced`() {
        val result = estimateTossRealizedProfit(
            executions = listOf(
                execution("buy", "20260701", "매수", 1.0, 100.0, currency = "USD"),
                execution("sell", "20260802", "매도", 1.0, 120.0, currency = "USD"),
            ),
            startDate = "20260801",
            endDate = "20260831",
            usdExchangeRate = 0.0,
        )

        assertFalse(result.profitAvailable)
        assertFalse(result.profitComplete)
        assertEquals(1, result.unpricedSellCount)
    }

    private fun execution(
        key: String,
        date: String,
        side: String,
        quantity: Double,
        amount: Double,
        currency: String = "KRW",
        commission: Double = 0.0,
        tax: Double = 0.0,
    ) = TossExecutionForEstimate(
        key = key,
        date = date,
        time = "090000",
        symbol = "TEST",
        side = side,
        currency = currency,
        quantity = quantity,
        amountNative = amount,
        commissionNative = commission,
        taxNative = tax,
    )
}
