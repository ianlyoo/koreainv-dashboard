package com.koreainv.dashboard.network

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsQuoteSupportTest {
    private val kst: ZoneId = ZoneId.of("Asia/Seoul")

    @Test
    fun dayMarketUsesRbaqPrefix() {
        val now = ZonedDateTime.of(2026, 3, 10, 10, 30, 0, 0, kst)

        val session = getUsMarketSession(now)

        assertEquals("day_market", session.session)
        assertTrue(session.usesDayPrefix)
        assertEquals("RBAQAAPL", buildUsTrKey("AAPL", "NASD", session))
    }

    @Test
    fun dayMarketStartsAtNineAmKstDuringDst() {
        val now = ZonedDateTime.of(2026, 3, 10, 9, 30, 0, 0, kst)

        val session = getUsMarketSession(now)

        assertEquals("day_market", session.session)
        assertTrue(session.usesDayPrefix)
    }

    @Test
    fun dayMarketStaysClosedBeforeTenAmOutsideDst() {
        val now = ZonedDateTime.of(2026, 2, 10, 9, 30, 0, 0, kst)

        val session = getUsMarketSession(now)

        assertEquals("closed", session.session)
        assertFalse(session.usesDayPrefix)
    }

    @Test
    fun regularMarketUsesDnasPrefix() {
        val now = ZonedDateTime.of(2026, 3, 10, 23, 0, 0, 0, kst)

        val session = getUsMarketSession(now)

        assertEquals("regular", session.session)
        assertFalse(session.usesDayPrefix)
        assertEquals("DNASAAPL", buildUsTrKey("AAPL", "NASD", session))
    }
}
