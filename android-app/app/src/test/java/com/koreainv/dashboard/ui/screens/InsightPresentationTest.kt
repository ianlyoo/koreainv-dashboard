package com.koreainv.dashboard.ui.screens

import com.koreainv.dashboard.network.insight.*
import org.junit.Assert.*
import org.junit.Test

class InsightPresentationTest {
    @org.junit.Test fun replacementAnnotationDoesNotEraseAPresentMetricValue() {
        val metric = InsightCoreMetric("EPS", InsightMetric(value = -2.5, replaceText = "적자 전환"), " USD")
        org.junit.Assert.assertEquals("-2.5 USD", metric.exactValue)
        org.junit.Assert.assertTrue(metric.detail.contains("적자 전환"))
    }
    @Test fun missingAndZeroAreDistinctAndPercentageIsNotScaled() {
        assertEquals("제공 안 됨",insightNumber(null,"%"))
        assertEquals("0%",insightNumber(0.0,"%"))
        assertEquals("12.345%",insightNumber(12.345,"%"))
        assertEquals("-20 USD",insightSigned(-20.0," USD"))
    }
    @Test fun externalLinksMustBeHttpsWithHostAndWithoutCredentials() {
        assertTrue(insightSafeLink("https://example.com/article"))
        listOf("javascript:alert(1)","http://example.com","https://user:secret@example.com","https:///missing","intent://news").forEach { assertFalse(it,insightSafeLink(it)) }
    }
    @Test fun rangeUsesCalendarMonthsAndKeepsSelectedEndBar() {
        val bars=listOf("2026-01-30","2026-02-28","2026-03-01","2026-03-31").mapIndexed { i,d -> PriceBar(i.toLong(),d,1.0,1.0,1.0,1.0,0.0) }
        assertEquals(listOf("2026-02-28","2026-03-01","2026-03-31"),insightBars(bars,1).map{it.date})
        assertEquals(emptyList<PriceBar>(),insightBars(emptyList(),12))
    }
    @Test fun ledgerRetainsOriginalSnapshotAndProvisionalMetadata() {
        val s=InsightSnapshot("AVGO",fetchedAtMillis=1000,isStale=true,options=InsightOptions(snapshotDate="2026-09-08",snapshotIsPriorDay=true,batchDate="2026-09-07",batchIsProvisional=true))
        val rows=insightLedger(s).toMap()
        assertEquals("1970-01-01T00:00:01Z",rows["조회"])
        assertTrue(rows.getValue("옵션 스냅샷").contains("전일"))
        assertTrue(rows.getValue("옵션 배치").contains("잠정 집계"))
    }
    @Test fun flattenedRowsHaveUniqueKeysAndCanonicalSectionOwnership() {
        val snapshot = InsightSnapshot(
            "AVGO",
            revenue = InsightRevenue(quarters = listOf(InsightQuarter(), InsightQuarter())),
            analyst = InsightAnalyst(recent = listOf(InsightAnalystRow(), InsightAnalystRow())),
            insider = InsightInsider(recent = listOf(InsightInsiderRow())),
            news = InsightNews(items = listOf(InsightNewsItem("Article", link = "https://example.com"))),
        )
        val rows = insightSectionItems(snapshot, setOf("financial", "analyst", "insider"), {}, {})
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(listOf("overview", "financial", "quarter_0", "quarter_1", "analyst", "analyst_0", "analyst_1", "options", "insider", "insider_0", "news", "news_0", "ledger"), rows.map { it.key })
        assertEquals("financial", rows.first { it.key == "quarter_1" }.section)
        assertEquals("analyst", rows.first { it.key == "analyst_1" }.section)
        assertEquals("news", rows.last().section)
    }
    @Test fun coreComparisonsUseTheirOwnUnitsAndReplacementValues() {
        val metrics = insightCoreMetrics(InsightSnapshot("AVGO", keyMetrics = InsightKeyMetrics(
            per = InsightMetric(value = 59.0, compare = 78.0),
            eps = InsightMetric(value = 1.91, compare = 85.4),
            revenueTtm = InsightMetric(value = 22187000000.0, compare = 47.9),
            dividendYield = InsightMetric(value = .7, compare = .65),
            shortInterestPct = InsightMetric(value = 1.2, compare = -.1),
            daysToCover = InsightMetric(value = 3.21, compare = -.01),
            roe = InsightMetric(replaceText = "적자"),
        )))
        assertEquals(8, metrics.size)
        assertEquals("업종 평균 78배", metrics[1].comparison)
        assertEquals("전년 대비 85.4%", metrics[2].comparison)
        assertEquals("전년 대비 47.9%", metrics[3].comparison)
        assertEquals("분기 배당 0.65 USD", metrics[4].comparison)
        assertEquals("적자", metrics[5].exactValue)
        assertEquals("2주 변화 -0.1%p", metrics[6].comparison)
        assertEquals("2주 변화 -0.01일", metrics[7].comparison)
        assertEquals("22.19B", insightCompactMoney(22187000000.0))
        assertEquals("0", insightCompactMoney(0.0))
    }
    @Test fun optionalLedgerFieldsAreOmittedAndQuarterReplacementIsActualChange() {
        assertEquals(emptyList<Pair<String,String>>(), insightLedger(InsightSnapshot("AVGO")))
        assertEquals("—", insightQuarterChange(InsightQuarter(yoy = null, replaceText = "—", direction = "flat")))
        assertEquals("의견 유지", insightProductLabel("maintained"))
    }
    @Test fun priceAndVolumeAreReadableWithoutLosingExactFormatter() {
        assertEquals("298.18", insightPrice(298.177451414815))
        assertEquals("0.1235", insightPrice(.123456789))
        assertEquals("0.00", insightPrice(0.0))
        assertEquals("1,390,000", insightVolume(1390000.0))
        assertEquals("298.177451414815", insightNumber(298.177451414815))
        assertEquals("제공 안 됨", insightPrice(null))
    }
}
