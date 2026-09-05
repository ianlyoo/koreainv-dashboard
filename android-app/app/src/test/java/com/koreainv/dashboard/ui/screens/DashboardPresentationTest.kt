package com.koreainv.dashboard.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class DashboardPresentationTest {
    @Test
    fun negativeAmountsKeepTheirSignWithoutOptionalPositiveSign() {
        assertEquals("-₩1,350", formatCurrencyAmount(-1350.0, CurrencyDisplayMode.KRW, 1350.0))
        assertEquals("-\$1.00", formatCurrencyAmount(-1350.0, CurrencyDisplayMode.USD, 1350.0))
        assertEquals("₩1,350", formatCurrencyAmount(1350.0, CurrencyDisplayMode.KRW, 1350.0))
        assertEquals("+\$1.00", formatCurrencyAmount(1350.0, CurrencyDisplayMode.USD, 1350.0, signed = true))
    }

    @Test
    fun zeroDoesNotGetAPositiveSignAndUsdKeepsCents() {
        assertEquals("₩0", formatCurrencyAmount(0.0, CurrencyDisplayMode.KRW, 1350.0, signed = true))
        assertEquals("\$1.23", formatCurrencyAmount(1660.5, CurrencyDisplayMode.USD, 1350.0))
        assertEquals("\$1.00", formatCurrencyAmount(1350.0, CurrencyDisplayMode.USD, 0.0))
    }

    @Test
    fun refreshErrorsOfferRecoveryWithoutExposingBrokerPayloads() {
        val payload = "private-account-secret"
        listOf(
            IllegalStateException(payload),
            IOException(payload),
            SocketTimeoutException(payload),
            IllegalStateException("EGW00123 TOKEN $payload"),
            IllegalStateException("EGW00201 code=429 $payload"),
        ).forEach { error -> assertFalse(dashboardErrorMessage(error).contains(payload)) }
        assertEquals("인터넷 연결을 확인한 뒤 다시 시도해 주세요.", dashboardErrorMessage(IOException()))
        assertEquals("응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.", dashboardErrorMessage(SocketTimeoutException()))
    }
}
