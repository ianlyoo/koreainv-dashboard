package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class TaxLoadingStabilityTest {
    @Test fun fiftyPagesWithIntermittentBrokerRateLimitComplete() = runBlocking {
        val shape = JsonParser.parseString(javaClass.classLoader!!.getResource("tax/kis-page-counts.json")!!.readText()).asJsonObject
            .getAsJsonObject("fills").getAsJsonObject("NASD")
        val totalPages = shape.get("pages").asInt
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
        val attempts = mutableMapOf<Int, Int>()
        val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { /* HTTP headers */ }
                        val page = Regex("page=(\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: 0
                        val attempt = attempts.getOrDefault(page, 0) + 1
                        attempts[page] = attempt
                        val limited = page in setOf(11, 37) && attempt == 1
                        val body = if (limited) """{"rt_cd":"1","msg_cd":"EGW00201"}""" else JsonObject().apply {
                            addProperty("rt_cd", "0")
                            addProperty("ctx_area_fk200", "fixture")
                            addProperty("ctx_area_nk200", "${page + 1}")
                            add("output", com.google.gson.JsonArray().apply {
                                repeat(shape.get("pageSize").asInt) { row ->
                                    add(JsonObject().apply {
                                        addProperty("ord_dt", "20260202"); addProperty("pdno", "FAKE")
                                        addProperty("ccld_qty", "1"); addProperty("ft_ccld_amt3", "100")
                                        addProperty("sll_buy_dvsn_cd", "02"); addProperty("odno", "${page * 20 + row}")
                                    })
                                }
                            })
                        }.toString()
                        val bytes = body.toByteArray()
                        val continuation = if (!limited && page < totalPages - 1) "M" else ""
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\ntr_cont: $continuation\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (_: SocketTimeoutException) { /* poll close */ }
                catch (error: SocketException) { if (!server.isClosed) throw error }
            }
        }.apply { isDaemon = true; start() }
        val client = OkHttpClient()
        val pacer = KisRequestPacer(intervalMillis = 1)
        val reasons = mutableSetOf<HistoryIncompleteReason>()
        try {
            val pages = loadKisHistoryPages(TAX_HISTORY_MAX_PAGES, linkedMapOf(), "ctx_area_fk200", "ctx_area_nk200", reasons::add) { query, continuation ->
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/fills?page=${query["CTX_AREA_NK200"] ?: 0}")
                    .header("tr_cont", continuation).build()
                val response = pacer.request { client.newCall(request).awaitTextResponse() }
                JsonParser.parseString(response.text).asJsonObject.apply { addProperty("__tr_cont", response.header("tr_cont")) }
            }
            assertEquals(50, pages.size)
            assertEquals(1000, pages.sumOf { it.getAsJsonArray("output").size() })
            assertTrue(reasons.isEmpty())
            assertEquals(52, attempts.values.sum())
            // Recorded probe's final page had nineteen rows, all other pages twenty.
            assertEquals(999, shape.get("rows").asInt)
        } finally {
            server.close(); worker.join(2000); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }

    @Test fun laterPageFailureKeepsPartialRowsAndNamesPart() = runBlocking {
        val coverage = HistoryCoverage()
        var calls = 0
        val rows = loadKisHistoryPages(100, linkedMapOf(), "fk", "nk", { coverage.mark(it, "한투 해외 체결") }) { _, _ ->
            if (++calls == 2) throw java.io.IOException("fake network failure")
            JsonParser.parseString("""{"output":[],"__tr_cont":"M","fk":"1","nk":"1"}""").asJsonObject
        }
        assertEquals(1, rows.size)
        assertEquals(setOf(HistoryIncompleteReason.UNAVAILABLE), coverage.snapshot())
        assertEquals(setOf("한투 해외 체결"), coverage.partSnapshot())
    }

    @Test fun nullCursorAndMalformedRowsRetainStructuredWarning() = runBlocking {
        val reasons = mutableSetOf<HistoryIncompleteReason>()
        val pages = loadKisHistoryPages(100, linkedMapOf(), "fk", "nk", { reasons.add(it) }) { _, _ ->
            JsonParser.parseString("""{"output":[null],"__tr_cont":"M","fk":null,"nk":null}""").asJsonObject
        }
        assertEquals(1, pages.size)
        assertEquals(setOf(HistoryIncompleteReason.MALFORMED_RESPONSE, HistoryIncompleteReason.BROKEN_CURSOR), reasons)
    }

    @Test fun rangeFetchCoversEveryLotDateAndSecondRunUsesSameRates() = runBlocking {
        val wanted = (0..364).map { FxDate("USD", LocalDate.of(2026, 1, 1).plusDays(it.toLong())) }.toSet()
        val cache = TaxDailyFxCache()
        val calls = AtomicInteger()
        suspend fun load(range: FxRange): Map<LocalDate, BigDecimal> {
            calls.incrementAndGet()
            return generateSequence(range.start) { it.plusDays(1) }.takeWhile { it <= range.end }
                .associateWith { BigDecimal(1200 + it.dayOfYear) }
        }
        val first = cache.hydrate(wanted, load = ::load)
        val firstCalls = calls.get()
        val second = cache.hydrate(wanted, load = ::load)
        assertEquals(wanted, first.keys)
        assertEquals(first, second)
        assertEquals(firstCalls, calls.get())
        assertTrue(firstCalls <= 5)
        val shape = JsonParser.parseString(javaClass.classLoader!!.getResource("tax/kis-field-shapes.json")!!.readText()).asJsonObject
        val sell = requireNotNull(parseKisTaxSale(shape.getAsJsonObject("usdSale"), "NASD", "USD"))
        val buys = listOf("buyOne", "buyTwo").map { requireNotNull(parseKisTaxFill(shape.getAsJsonObject(it), "NASD")) }
        fun amounts(rates: Map<FxDate, BigDecimal>) = buildCapitalGainsBases(buys.map { it.copy(fx = rates[FxDate(it.currency, it.date)]) } + sell, 1700.0)
        assertEquals(amounts(first), amounts(second))
    }

    @Test fun dailyRateCacheSurvivesNewSessionWithoutChangingPrices() = runBlocking {
        var stored: String? = null
        val date = FxDate("USD", LocalDate.of(2026, 2, 2))
        val first = TaxDailyFxCache(savePersisted = { stored = TaxDailyFxCodec.serialize(it) })
        val initial = first.hydrate(setOf(date)) { mapOf(date.date to BigDecimal("1380.0000")) }
        val second = TaxDailyFxCache(loadPersisted = { TaxDailyFxCodec.parse(stored) })
        assertEquals(initial, second.hydrate(setOf(date)) { error("No network on repeated dates") })
        assertTrue(TaxDailyFxCodec.parse("malformed").isEmpty())
    }

    @Test fun yenSalesSelectAllConsumedLotDatesForRangeHydration() {
        val shape = JsonParser.parseString(javaClass.classLoader!!.getResource("tax/kis-field-shapes.json")!!.readText()).asJsonObject
        val sell = requireNotNull(parseKisTaxSale(shape.getAsJsonObject("jpySale"), "TKSE", "JPY"))
        val row = shape.getAsJsonObject("buyOne").deepCopy().apply {
            addProperty("pdno", "FAKE-JP"); addProperty("crcy_cd", "JPY"); addProperty("ccld_qty", "10")
            remove("frst_bltn_exrt")
        }
        val buy = requireNotNull(parseKisTaxFill(row, "TKSE"))
        val keys = taxFxExecutionKeys(listOf(buy, sell), LocalDate.of(2026,1,1), LocalDate.of(2026,12,31))
        assertEquals(setOf(buy.key, sell.key), keys)
        val wanted = listOf(buy, sell).filter { it.key in keys }.map { FxDate(it.currency, it.date) }.toSet()
        assertTrue(wanted.all { key -> taxFxRanges(wanted).any { key.currency == it.currency && key.date in it.start..it.end } })
    }

    @Test fun persistenceFailureKeepsCompletedDailyRates() = runBlocking {
        val key = FxDate("USD", LocalDate.of(2026,2,2))
        val cache = TaxDailyFxCache(loadPersisted = { throw java.io.IOException("fake storage") }, savePersisted = { throw java.io.IOException("fake storage") })
        val rates = cache.hydrate(setOf(key)) { mapOf(key.date to BigDecimal("1380")) }
        assertEquals(BigDecimal("1380"), rates[key])
        assertEquals(rates, cache.hydrate(setOf(key)) { error("Completed rates survive in memory") })
    }

    @Test fun sharedPacerSerializesConcurrentTradeAndTaxCalls() = runBlocking {
        val pacer = KisRequestPacer(intervalMillis = 30)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val starts = mutableListOf<Long>()
        coroutineScope {
            (1..4).map { async {
                pacer.request {
                    starts.add(System.nanoTime())
                    maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                    delay(10)
                    active.decrementAndGet()
                    HttpTextResponse(200, okhttp3.Headers.Builder().build(), "{}")
                }
            } }.awaitAll()
        }
        assertEquals(1, maximum.get())
        assertTrue(starts.zipWithNext().all { (first, second) -> (second - first)/1_000_000 >= 29 })
    }

    @Test fun yenCrossRateIsPerOneYenAndDailyShapeUsesClose() {
        assertEquals(0, BigDecimal("9.2").compareTo(nativeKrwCrossRate(BigDecimal("1380"), BigDecimal("150"))))
        val rates = parseKisDailyFx(JsonParser.parseString("""{"output2":[{"stck_bsop_date":"20260202","ovrs_nmix_prpr":"1380.0000"}]}""").asJsonObject)
        assertEquals(0, BigDecimal("1380").compareTo(rates.getValue(LocalDate.of(2026, 2, 2))))
    }

    @Test fun emptyAndPartialWarningsUseBrokerPartInsteadOfGenericError() {
        val empty = TradeHistoryResponse(TradePeriod("2026-01-01","2026-12-31","fixture"), TradeSummary(0.0,0.0,0.0,0.0), emptyList(),
            historyCompleteness = listOf(AccountHistoryCompleteness("fake", "fake", setOf(HistoryIncompleteReason.PAGE_LIMIT), setOf("한투 해외 체결"))))
        val result = estimateCapitalGainsTax(2026, empty)
        assertNull(result.estimatedTaxKrw)
        assertEquals(listOf("한투 해외 체결 일부 미조회"), result.historyWarnings)
    }

    @Test fun rangeHydrationBudgetAndCancellationAreBounded() = runBlocking {
        val keys = setOf(FxDate("USD", LocalDate.of(2025,1,1)), FxDate("USD", LocalDate.of(2026,1,1)))
        val start = System.nanoTime()
        val rates = TaxDailyFxCache().hydrate(keys, budgetMillis=80, requestMillis=30) { delay(1000); emptyMap() }
        assertTrue(rates.isEmpty())
        assertTrue((System.nanoTime() - start)/1_000_000 < 500)
        val entered = CompletableDeferred<Unit>()
        val job = launch { TaxDailyFxCache().hydrate(keys) { entered.complete(Unit); awaitCancellation() } }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun fifoCoverageExpandsOnlyForSelectedUncoveredSales() {
        val shape = JsonParser.parseString(javaClass.classLoader!!.getResource("tax/kis-field-shapes.json")!!.readText()).asJsonObject
        val sell = requireNotNull(parseKisTaxSale(shape.getAsJsonObject("usdSale"), "NASD", "USD"))
        val buys = listOf("buyOne", "buyTwo").map { requireNotNull(parseKisTaxFill(shape.getAsJsonObject(it), "NASD")) }
        assertFalse(hasUncoveredTaxSales(buys + sell))
        assertTrue(hasUncoveredTaxSales(listOf(sell)))
        assertFalse(hasUncoveredTaxSales(listOf(sell), emptySet()))
    }
}
