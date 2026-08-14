package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CashSelectionTest {
    private fun row(vararg pairs: Pair<String, String>): JsonObject = JsonObject().apply {
        pairs.forEach { (key, value) -> addProperty(key, value) }
    }

    @Test
    fun domesticCashUsesDncaTotAmtFromTttc8434rSummary() {
        val summary = row("dnca_tot_amt" to "1234500", "evlu_amt_smtl_amt" to "9000000")

        assertEquals(1_234_500.0, pickDomesticDeposit(summary), 0.001)
    }

    @Test
    fun domesticCashIsZeroWhenSummaryMissing() {
        assertEquals(0.0, pickDomesticDeposit(null), 0.001)
    }

    @Test
    fun overseasDepositRowWinsOverOrderableAmount() {
        val rows = listOf(row("crcy_cd" to "USD", "frcr_dncl_amt_2" to "5000"))

        val cash = resolveOverseasCash(
            depositFromHoldingRow = 0.0,
            depositFromRows = pickForeignDepositFromRows(rows, "USD"),
            depositFromOutput3 = 0.0,
        )

        assertEquals(5000.0, cash, 0.001)
    }

    @Test
    fun orderableAmountIsNotUsedAsDeposit() {
        val cash = resolveOverseasCash(
            depositFromHoldingRow = 0.0,
            depositFromRows = 0.0,
            depositFromOutput3 = 0.0,
        )

        assertEquals(0.0, cash, 0.001)
    }

    @Test
    fun currencyRowsAreStrictlyMatchedWithoutCrossCurrencyFallback() {
        val jpyOnlyRows = listOf(row("crcy_cd" to "JPY", "frcr_dncl_amt_2" to "100000"))

        assertEquals(0.0, pickForeignDepositFromRows(jpyOnlyRows, "USD"), 0.001)
        assertEquals(100000.0, pickForeignDepositFromRows(jpyOnlyRows, "JPY"), 0.001)
    }

    @Test
    fun currencyPseudoRowCanProvideActualDeposit() {
        val pseudoRows = listOf(row("pdno" to "USD", "prdt_name" to "미 달러화", "ccld_qty_smtl1" to "7777"))

        assertEquals(7777.0, pickForeignDepositFromCurrencyHoldingRow(pseudoRows, "USD"), 0.001)
    }

    @Test
    fun japanUsesTrMarketCd01ForCtrp6504r() {
        assertEquals("01", overseasTrMarketCd("392"))
        assertEquals("00", overseasTrMarketCd("840"))
    }

    @Test
    fun output3DepositIsUsedBeforeOrderable() {
        val output3 = row("frcr_dncl_amt_2" to "250.5")

        val cash = resolveOverseasCash(
            depositFromHoldingRow = 0.0,
            depositFromRows = 0.0,
            depositFromOutput3 = pickForeignDepositFromOutput3(output3),
        )

        assertEquals(250.5, cash, 0.001)
    }
}
