package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TradeHistoryParsingTest {
    @Test
    fun firstPositiveNumberSkipsZeroStringAndUsesFallbackAmount() {
        val row = JsonObject().apply {
            addProperty("tr_frcr_amt2", "0")
            addProperty("frcr_sll_amt_smtl", "350.50")
        }

        val value = firstPositiveNumber(row, listOf("tr_frcr_amt2", "frcr_sll_amt_smtl"))

        assertEquals(350.50, value, 0.0001)
    }

    @Test
    fun resolvedTradeAmountDerivesAmountFromQuantityAndUnitPriceWhenAmountMissing() {
        val value = resolvedTradeAmount(amount = 0.0, quantity = 3.0, unitPrice = 71_000.0)

        assertEquals(213_000.0, value, 0.0001)
    }
}
