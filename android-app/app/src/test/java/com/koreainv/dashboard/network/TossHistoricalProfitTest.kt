package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TossHistoricalProfitTest {
    private fun fixture(): JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.getResource("/toss/orders-and-fx.json")).readText(),
    ).asJsonObject

    private fun executions(): List<TossExecutionForEstimate> = fixture()
        .getAsJsonObject("orders").getAsJsonObject("result").getAsJsonArray("orders")
        .mapNotNull { parseTossExecutionForEstimate(it.asJsonObject) }

    private fun estimate(rows: List<TossExecutionForEstimate>, complete: Boolean = true) =
        estimateTossRealizedProfit(rows, "20260901", "20260930", 1700.0, complete)

    @Test fun `fractional buys fees taxes and historical sale rates ignore today's FX`() {
        val rows = executions().map { it.copy(saleMidRate = if (it.key == "sell-two") 1300.0 else 1400.0) }
        val result = estimate(rows)
        assertEquals((180 - .5 - 125.25) * 1400, result.profitsByExecutionKey.getValue("sell-one").realizedProfitKrw, .0001)
        assertEquals((150 - .4 - 125.25) * 1300, result.profitsByExecutionKey.getValue("sell-two").realizedProfitKrw, .0001)
        assertEquals(125.25 * 2700, result.totalBuyAmountKrw, .0001)
        assertEquals(TOSS_HISTORICAL_FX_SOURCE, result.profitsByExecutionKey.getValue("sell-one").rateSource)
        assertEquals("20260601", result.historyStartDate)
        assertTrue(result.profitComplete)
        assertEquals(result, estimate(rows + rows[2]))
    }

    @Test fun `midRate requires matching direction finite value and timestamp window`() {
        val quote = fixture().getAsJsonObject("exchange_rate").getAsJsonObject("result")
        val timestamp = "2026-09-02T09:00:30+09:00"
        assertEquals(1400.0, validatedTossHistoricalMidRate(quote, timestamp), .0001)
        assertEquals(1400.0, validatedTossHistoricalMidRate(quote, "2026-09-02T00:00:30Z"), .0001)
        listOf("baseCurrency" to "KRW", "quoteCurrency" to "USD", "midRate" to "NaN", "midRate" to "0", "midRate" to "", "validFrom" to "2026-09-03T09:00:00+09:00", "validUntil" to "2026-09-02T09:00:00+09:00").forEach { (key, value) ->
            val invalid = quote.deepCopy().apply { addProperty(key, value) }
            assertEquals(0.0, validatedTossHistoricalMidRate(invalid, timestamp), .0001)
        }
        assertEquals(0.0, validatedTossHistoricalMidRate(quote, "2026-09-02T09:00:30"), .0001)
        assertEquals(0.0, validatedTossHistoricalMidRate(quote.deepCopy().apply { remove("midRate") }, timestamp), .0001)
    }

    @Test fun `sale quote window skew includes both ten minute boundaries`() {
        val quote = fixture().getAsJsonObject("exchange_rate").getAsJsonObject("result").apply {
            addProperty("validFrom", "2026-09-02T09:00:45+09:00")
            addProperty("validUntil", "2026-09-02T09:05:45+09:00")
        }
        listOf(
            "2026-09-02T09:00:30+09:00" to 1400.0, // window starts 15 seconds after sale
            "2026-09-02T00:00:30Z" to 1400.0,
            "2026-09-02T08:50:45+09:00" to 1400.0,
            "2026-09-02T08:50:44.999+09:00" to 0.0,
            "2026-09-02T09:05:45+09:00" to 1400.0,
            "2026-09-02T09:15:45+09:00" to 1400.0,
            "2026-09-02T09:15:45.001+09:00" to 0.0,
        ).forEach { (timestamp, expected) ->
            assertEquals(timestamp, expected, validatedTossHistoricalMidRate(quote, timestamp), .0001)
        }
    }

    @Test fun `cache separates scopes expires failures and bounds entries`() {
        val cache = TossHistoricalFxCache(capacity = 2)
        val quote = fixture().getAsJsonObject("exchange_rate").getAsJsonObject("result")
        val timestamp = "2026-09-02T09:00:30+09:00"
        assertEquals(1400.0, cache.put("a", timestamp, quote, 0), .0001)
        assertEquals(1400.0, requireNotNull(cache.get("a", timestamp, 10)), .0001)
        assertNull(cache.get("b", timestamp, 10))
        assertEquals(0.0, cache.put("b", timestamp, null, 0), .0001)
        assertEquals(0.0, requireNotNull(cache.get("b", timestamp, 10)), .0001)
        assertNull(cache.get("b", timestamp, 60_000))
        cache.put("b", timestamp, quote, 0)
        cache.put("c", timestamp, quote, 0)
        assertNull(cache.get("a", timestamp, 10))
        assertNull(cache.get("c", timestamp, 86_400_000))
    }

    @Test fun `missing historical FX does not fall back to current rate and retains sale reasons`() {
        val result = estimate(executions())
        assertEquals(2, result.unpricedSellCount)
        assertEquals("매도 시점 참고환율 정보 부족", result.reasonsByExecutionKey["sell-one"])
        assertFalse(result.profitAvailable)
        assertFalse(result.profitComplete)
    }

    @Test fun `basis gaps incomplete pagination and unsupported currency are visible`() {
        val rows = executions().map { it.copy(saleMidRate = 1400.0) }
        val noBasis = estimate(rows.drop(2))
        assertEquals("매수 원가 이력 부족", noBasis.reasonsByExecutionKey["sell-one"])
        val partial = estimate(rows, complete = false)
        assertEquals("거래 이력 조회 미완료", partial.reasonsByExecutionKey["sell-one"])
        assertFalse(partial.profitComplete)
        val unsupported = estimate(rows.map { it.copy(currency = "JPY") })
        assertEquals("지원하지 않는 통화", unsupported.reasonsByExecutionKey["sell-one"])
    }

    @Test fun `nullable and malformed charges never become confirmed zero`() {
        for (index in listOf(0, 2)) {
            for (value in listOf(null, "", "not-a-number", "NaN")) {
                val orders = fixture().getAsJsonObject("orders").getAsJsonObject("result").getAsJsonArray("orders")
                orders[index].asJsonObject.getAsJsonObject("execution").addProperty("commission", value)
                val rows = orders.mapNotNull { parseTossExecutionForEstimate(it.asJsonObject)?.copy(saleMidRate = 1400.0) }
                val result = estimate(rows)
                assertFalse(result.profitsByExecutionKey.containsKey("sell-one"))
                assertTrue(result.reasonsByExecutionKey.getValue("sell-one").contains("수수료"))
            }
        }
    }

    @Test fun `old central profit cannot claim historical rate support`() {
        val legacy = JsonObject().apply { addProperty("profit_estimated", true) }
        assertFalse(supportsTossHistoricalProxyProfit(legacy))
        legacy.addProperty("profit_fx_basis", "sale_historical_mid_rate")
        assertTrue(supportsTossHistoricalProxyProfit(legacy))
    }
}
