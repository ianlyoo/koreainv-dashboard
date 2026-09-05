package com.koreainv.dashboard.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetStatusScreenLogicTest {
    @Test
    fun usdCash_preservesCentsIncludingSubDollarAndZeroBalances() {
        assertEquals("$1,234.56", formatCashUsd(1234.56))
        assertEquals("$0.49", formatCashUsd(0.49))
        assertEquals("$0.00", formatCashUsd(0.0))
        assertEquals("$12.00", formatCashUsd(12.0))
    }

    @Test
    fun usdCash_doesNotDiscardNegativeSign() {
        assertEquals("$-12.34", formatCashUsd(-12.34))
    }
}
