package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.insight.*
import org.junit.Assert.*
import org.junit.Test

class InsightLedgerTest {
    @Test fun timestampKeepsTimezoneFractionAndDateOnlyMeaning() {
        assertEquals("2026.09.08\n16:05:07.511787 UTC", insightTimestamp("2026-09-08T16:05:07.511787Z"))
        assertEquals("2026.09.09\n01:05:07 UTC+09:00", insightTimestamp("2026-09-09T01:05:07+09:00"))
        assertEquals("2026.09.08", insightTimestamp("2026-09-08"))
        assertEquals("2026-09-08T16:05:07", insightTimestamp("2026-09-08T16:05:07"))
        assertEquals("unknown", insightTimestamp("unknown"))
        assertEquals("2026.09.08\n00:00:00 UTC", insightTimestamp("1788825600000"))
    }

    @Test fun groupsKeepAllOptionMetadataAndDeduplicateIdenticalResponseTime() {
        val date = "2026-09-08T16:05:00Z"
        val s = InsightSnapshot("AVGO", fetchedAtMillis = 1000, isStale = true,
            sections = mapOf("options" to InsightSectionInfo(InsightSectionStatus.AVAILABLE, "SaveTicker", date, true)),
            options = InsightOptions(asOf = date, snapshotDate = "2026-09-07", snapshotUpdatedAt = "2026-09-08T12:00:00Z",
                snapshotIsPriorDay = true, batchDate = "2026-09-08", batchUpdatedAt = "2026-09-08T13:00:00Z",
                batchIsPriorDay = false, batchIsProvisional = true,
                optionVolumeVsAvg = InsightVolumeAverage(roundSeq = 0.0, asOfMinute = 154.0)))
        val groups = insightLedgerGroups(s)
        assertEquals(listOf("retrieval", "options"), groups.map { it.key })
        assertEquals("1970-01-01T00:00:01Z", groups.first().rows.single().original)
        val rows = groups.last().rows.associateBy { it.key }
        assertEquals("SaveTicker", rows.getValue("source").original)
        assertEquals(date, rows.getValue("asof").original)
        assertFalse(rows.containsKey("current"))
        assertEquals("전일", rows.getValue("snapshot_prior").value)
        assertEquals("당일", rows.getValue("batch_prior").value)
        assertEquals("잠정 집계", rows.getValue("batch_provisional").value)
        assertEquals("2026-09-08T12:00:00Z", rows.getValue("snapshot_updated").original)
        assertEquals("2026-09-08T13:00:00Z", rows.getValue("batch_updated").original)
        assertEquals("0", rows.getValue("comparison_round").value)
    }

    @Test fun ledgerIsCollapsedByDefaultAndExpandedRowsRemainUniqueLazyItems() {
        val s = InsightSnapshot("AVGO", sections = mapOf(
            "header" to InsightSectionInfo(InsightSectionStatus.AVAILABLE, asOf = "2026-09-08"),
            "options" to InsightSectionInfo(InsightSectionStatus.ERROR)),
            header = InsightHeader(asOf = "2026-09-08T16:00:00Z"))
        val closed = insightSectionItems(s, emptySet(), {}, {})
        assertEquals("ledger", closed.last().key)
        assertFalse(closed.any { it.key.startsWith("ledger_") })
        val opened = insightSectionItems(s, setOf("ledger"), {}, {})
        assertEquals(opened.size, opened.map { it.key }.distinct().size)
        assertTrue(opened.any { it.key == "ledger_header_asof" })
        assertTrue(opened.any { it.key == "ledger_header_quote" })
        assertTrue(opened.any { it.key == "ledger_options_status" })
        assertTrue(opened.filter { it.key.startsWith("ledger_") }.all { it.section == "news" })
    }

    @Test fun originalSectionSourcesAndPriorAnalystSourcesRemainAvailable() {
        val s = InsightSnapshot("AVGO", sections = mapOf(
            "revenue" to InsightSectionInfo(InsightSectionStatus.AVAILABLE, "SaveTicker")),
            revenue = InsightRevenue(source = "SEC"),
            analyst = InsightAnalyst(provider = "TipRanks", asOf = "2026-09-08", recent = listOf(InsightAnalystRow(prevSource = "vendor"))))
        val groups = insightLedgerGroups(s).associateBy { it.key }
        assertEquals(listOf("SaveTicker", "제공됨", "SEC"), groups.getValue("revenue").rows.map { it.value })
        assertEquals(listOf("provider", "analyst_date", "previous_0"), groups.getValue("analyst").rows.map { it.key })
    }
}
