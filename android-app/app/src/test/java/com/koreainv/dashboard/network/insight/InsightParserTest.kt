package com.koreainv.dashboard.network.insight

import org.junit.Assert.*
import org.junit.Test

class InsightParserTest {
    @Test fun observedFixturesKeepPrecisionRowsAndMetricMeaning() {
        for (ticker in listOf("AVGO", "AAPL")) {
            val fixture = javaClass.getResourceAsStream("/insight/$ticker.json")!!.bufferedReader().use { it.readText() }
            val root = InsightParser.root(fixture)
            val metrics = InsightParser.metrics(root.getAsJsonObject("key_metrics"))
            val revenue = InsightParser.revenue(root.getAsJsonObject("revenue"))
            val options = InsightParser.options(root.getAsJsonObject("options"))
            val analyst = InsightParser.analyst(root.getAsJsonObject("analyst"))
            val insider = InsightParser.insider(root.getAsJsonObject("insider"))
            assertEquals(metrics.periodLabel, revenue.quarters.first().label)
            assertEquals(metrics.revenueTtm!!.value, revenue.quarters.first().revenue)
            assertEquals(5, revenue.quarters.size)
            assertEquals(5, analyst.recent.size)
            assertEquals(if (ticker == "AVGO") 9 else 5, insider.recent.size)
            assertEquals(3, InsightParser.news(root.getAsJsonObject("news")).items.size)
            assertEquals(if (ticker == "AVGO") 73.8 else 40.1, options.optionVolumeVsAvg!!.windows.getValue("d3").ratioPct!!, 0.00001)
            assertEquals(if (ticker == "AVGO") 0.7097 else 0.3313, metrics.dividendYield!!.value!!, 0.000001)
        }
    }
    @Test fun nullAndZeroAreDistinctAndUnavailableRatioIsNotPublished() {
        val metrics = InsightParser.metrics(InsightParser.root("""{"eps":{"value":0,"compare":null},"per":null,"unknown":{"secret":"discard"}}"""))
        assertEquals(0.0, metrics.eps!!.value!!, 0.0); assertNull(metrics.eps!!.compare); assertNull(metrics.per)
        val options = InsightParser.options(InsightParser.root("""{"optionable":true,"volumeShare":{"call":0,"put":null},"optionVolumeVsAvg":{"windows":{"d3":{"available":false,"ratioPct":123},"d7":{"available":true,"ratioPct":0},"d99":{"available":true,"ratioPct":55}}}}"""))
        assertNull(options.volumeShare!!.put); assertNull(options.optionVolumeVsAvg!!.windows.getValue("d3").ratioPct)
        assertEquals(0.0, options.optionVolumeVsAvg!!.windows.getValue("d7").ratioPct!!, 0.0)
        assertFalse(options.optionVolumeVsAvg!!.windows.containsKey("d99"))
    }
    @Test fun rejectsWrongNumericTypeDeepOversizedAndDuplicateJson() {
        for (raw in listOf("{" + "\"x\":{".repeat(14) + "\"a\":1" + "}".repeat(15), "{\"rows\":[" + "0,".repeat(1000) + "0]}", "{\"a\":1,\"a\":2}", "{\"a\":1e999}")) {
            assertThrows(Exception::class.java) { InsightParser.root(raw) }
        }
        assertThrows(Exception::class.java) { InsightParser.header(InsightParser.root("""{"price":"0"}""")) }
        assertThrows(Exception::class.java) { InsightParser.header(InsightParser.root("""{"account":"unexpected"}""")) }
    }
    @Test fun validatesBarsOrderRangesAndKeepsZeroVolume() {
        val row = """{"time":1757030400,"open":10,"high":12,"low":9,"close":11,"volume":0}"""
        val valid = InsightParser.bars(InsightParser.root("""{"interval":"day","range":"1y","bars":[$row]}"""))
        assertEquals(1757030400000, valid.single().time); assertEquals(0.0, valid.single().volume, 0.0)
        assertThrows(Exception::class.java) { InsightParser.bars(InsightParser.root("""{"bars":[$row,$row]}""")) }
        assertThrows(Exception::class.java) { InsightParser.bars(InsightParser.root("""{"bars":[${row.replace("\"high\":12", "\"high\":8")}]}""")) }
    }
    @Test fun stripsUnsafeNewsLinksAndCapsThree() {
        val root = InsightParser.root("""{"items":[{"title":"bad","link":"http://example.com"},{"title":"bad","link":"https://user:pass@example.com"},{"title":"good","link":"https://saveticker.com/news/1","body":"discard"}]}""")
        val news = InsightParser.news(root)
        assertEquals(1, news.items.size); assertEquals("good", news.items.single().title)
    }
    @Test fun retryAfterUsesSecondsDateAndFallback() {
        assertEquals(120000, InsightSessionManager.parseRetryAfter("120", 0))
        assertEquals(60000, InsightSessionManager.parseRetryAfter("invalid", 0))
        assertEquals(86400000, InsightSessionManager.parseRetryAfter("99999999", 0))
    }
    @Test fun tenThousandNestedContainersFailBeforeDeepTreeAllocation() {
        val raw = "{\"value\":" + "[".repeat(10000) + "0" + "]".repeat(10000) + "}"
        assertThrows(Exception::class.java) { InsightParser.root(raw) }
    }
    @Test fun strictReaderRejectsTrailingTokensAndMalformedJson() {
        for (raw in listOf("{\"a\":1} {\"b\":2}", "{\"a\":1} garbage", "{\"a\":1,}", "{a:1}")) {
            assertThrows(Exception::class.java) { InsightParser.root(raw) }
        }
    }

}
