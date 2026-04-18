package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.TradeHistoryResponse
import com.koreainv.dashboard.network.TradePeriod
import com.koreainv.dashboard.network.TradeSummary
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeHistoryScreenLogicTest {
    @Test
    fun isTradeHistorySnapshotStaleReturnsTrueForOldSnapshot() {
        val now = OffsetDateTime.of(2026, 4, 18, 12, 0, 0, 0, ZoneOffset.ofHours(9))
        val data = tradeHistoryResponse(now.minusSeconds(31).toString())

        assertTrue(isTradeHistorySnapshotStale(data, now = now, ttlMillis = 30_000L))
    }

    @Test
    fun isTradeHistorySnapshotStaleReturnsFalseForFreshSnapshot() {
        val now = OffsetDateTime.of(2026, 4, 18, 12, 0, 0, 0, ZoneOffset.ofHours(9))
        val data = tradeHistoryResponse(now.minusSeconds(10).toString())

        assertFalse(isTradeHistorySnapshotStale(data, now = now, ttlMillis = 30_000L))
    }

    @Test
    fun isTradeHistorySnapshotStaleReturnsTrueWhenTimestampMissing() {
        assertTrue(isTradeHistorySnapshotStale(tradeHistoryResponse("")))
    }

    private fun tradeHistoryResponse(lastSynced: String): TradeHistoryResponse = TradeHistoryResponse(
        period = TradePeriod(start = "2026-04-01", end = "2026-04-18", label = "이번 달"),
        summary = TradeSummary(
            totalRealizedProfitKrw = 0.0,
            domesticRealizedProfitKrw = 0.0,
            overseasRealizedProfitKrw = 0.0,
            totalRealizedReturnRate = 0.0,
        ),
        trades = emptyList(),
        lastSynced = lastSynced,
    )
}
