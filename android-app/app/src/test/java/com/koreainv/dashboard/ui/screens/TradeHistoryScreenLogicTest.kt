package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.TradeHistoryResponse
import com.koreainv.dashboard.network.TradePeriod
import com.koreainv.dashboard.network.TradeSummary
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TradeHistoryScreenLogicTest {
    @Test
    fun tradeRangeOptionsIncludeOneYear() {
        assertTrue(tradeRangeOptions().contains("1y" to "최근 1년"))
        assertEquals("최근 1년", rangeLabel("1y"))
    }
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

    @Test
    fun partialAccountFailure_alwaysProducesWarningWithoutRawBrokerPayload() {
        val warning = tradeHistoryWarningMessage(null, listOf("internal broker payload"))

        assertTrue(warning.orEmpty().contains("누락"))
        assertFalse(warning.orEmpty().contains("internal broker payload"))
        assertNull(tradeHistoryWarningMessage(null, emptyList()))
    }

    @Test
    fun cachedRefreshError_remainsVisibleAlongsideAccountCompletenessWarning() {
        val warning = tradeHistoryWarningMessage("다시 시도해 주세요", listOf("unavailable"))

        assertTrue(warning.orEmpty().contains("다시 시도해 주세요"))
        assertTrue(warning.orEmpty().contains("합계와 거래 목록"))
    }

    @Test
    fun failedListAfterSummary_doesNotPersistPreviewAsCompletedHistory() {
        val summary = tradeHistoryResponse("2026-04-18T12:00:00+09:00")
        val snapshots = TradeHistorySnapshots().withSummary(summary).afterFailure()

        assertEquals(summary, snapshots.displayed)
        assertNull(snapshots.complete)
    }

    @Test
    fun failedRefresh_preservesPreviouslyCompletedEmptyHistory() {
        val empty = tradeHistoryResponse("2026-04-18T12:00:00+09:00")
        val newerSummary = tradeHistoryResponse("2026-04-18T12:01:00+09:00")
        val snapshots = TradeHistorySnapshots().withSuccess(empty).withSummary(newerSummary).afterFailure()

        assertEquals(empty, snapshots.displayed)
        assertEquals(empty, snapshots.complete)
    }

    @Test
    fun restoredFilters_cannotReuseSnapshotFromAnotherAccountOrPeriod() {
        val session = TradeHistorySessionState(
            tradeData = tradeHistoryResponse("2026-04-18T12:00:00+09:00"),
            selectedRange = "this_month",
            selectedAccountId = "account-a",
        )

        assertTrue(tradeHistorySessionMatches(session, "this_month", "account-a"))
        assertFalse(tradeHistorySessionMatches(session, "last_month", "account-a"))
        assertFalse(tradeHistorySessionMatches(session, "this_month", "account-b"))
        assertFalse(tradeHistorySessionMatches(session, "this_month", null))
    }

    @Test
    fun missingAccount_isUnavailableWithoutBecomingAllAccounts() {
        val selection = resolveAccountSelection("account-a", listOf(HoldingAccountFilter("account-b", "Other")))

        assertTrue(selection.unavailable)
        assertEquals("account-a", selection.accountId)
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
