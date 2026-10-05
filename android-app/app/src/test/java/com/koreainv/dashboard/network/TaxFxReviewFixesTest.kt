package com.koreainv.dashboard.network

import com.google.gson.JsonParser
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TaxFxReviewFixesTest {
    private val feb2 = LocalDate.of(2026, 2, 2)
    private val feb3 = feb2.plusDays(1)
    private val feb4 = feb3.plusDays(1)
    private val yearStart = LocalDate.of(2026, 1, 1)
    private val yearEnd = LocalDate.of(2026, 12, 31)
    private fun key(date: LocalDate) = FxDate("USD", date)
    private fun decimal(value: String) = BigDecimal(value)
    private fun proxyInputs(): List<TaxExecution> = JsonParser.parseString(
        javaClass.classLoader!!.getResource("tax/toss-proxy-v1.9.11.json")!!.readText()
    ).asJsonObject.getAsJsonArray("tax_executions").map { requireNotNull(parseTossTaxExecution(it.asJsonObject)) }
    private fun history(inputs: List<TaxExecution>): TradeHistoryResponse {
        val bases = buildCapitalGainsBases(inputs, 1700.0)
        val trades = inputs.filter { !it.buy }.map { sell ->
            Trade(sell.date.toString(), "매도", sell.symbol, sell.name, sell.market, sell.currency,
                sell.quantity.toDouble(), 0.0, sell.amount.toDouble(), 0.0, 999999999.0, null,
                capitalGainsBasis = bases.getValue(sell.key), broker = Broker.TOSS)
        }
        return TradeHistoryResponse(TradePeriod(yearStart.toString(), yearEnd.toString(), "fixture"),
            TradeSummary(0.0,0.0,0.0,0.0), trades)
    }
    private fun estimate(inputs: List<TaxExecution>) = estimateCapitalGainsTax(2026, history(inputs))

    @Test fun overlappingDatesFetchExactRateDespiteCachedPreviousDay() = runBlocking {
        val cache = TaxDailyFxCache()
        cache.hydrate(setOf(key(feb2))) { mapOf(feb2 to decimal("1380")) }
        var calls = 0
        val result = cache.hydrateWithProvenance(setOf(key(feb3))) { range ->
            calls++
            assertTrue(feb3 in range.start..range.end)
            mapOf(feb3 to decimal("1400"))
        }.getValue(key(feb3))
        assertEquals(1, calls)
        assertEquals(decimal("1400"), result.value)
        assertEquals(feb3, result.publicationDate)
    }

    @Test fun persistedCacheReloadFetchesMissingExactDatesAndDoesNotPersistSubstitutes() = runBlocking {
        var stored: String? = null
        val first = TaxDailyFxCache(savePersisted = { stored = TaxDailyFxCodec.serialize(it) })
        first.hydrate(setOf(key(feb2))) { mapOf(feb2 to decimal("1380")) }
        val substituted = first.hydrateWithProvenance(setOf(key(feb3))) { emptyMap() }.getValue(key(feb3))
        assertEquals(feb2, substituted.publicationDate)
        assertFalse(TaxDailyFxCodec.parse(stored).containsKey(key(feb3)))
        val reloaded = TaxDailyFxCache(loadPersisted = { TaxDailyFxCodec.parse(stored) },
            savePersisted = { stored = TaxDailyFxCodec.serialize(it) })
        var calls = 0
        val recovered = reloaded.hydrate(setOf(key(feb3))) { calls++; mapOf(feb3 to decimal("1400")) }
        assertEquals(1, calls)
        assertEquals(decimal("1400"), recovered[key(feb3)])
        assertEquals(decimal("1380"), TaxDailyFxCodec.parse(stored)[key(feb2)])
        assertEquals(decimal("1400"), TaxDailyFxCodec.parse(stored)[key(feb3)])
    }

    @Test fun freshAndPreseededCachesProduceIdenticalGains() = runBlocking {
        val inputs = proxyInputs().map { it.copy(fx = null) }
        val fresh = TaxDailyFxCache()
        val seeded = TaxDailyFxCache()
        seeded.hydrate(setOf(key(feb2))) { mapOf(feb2 to decimal("1380")) }
        suspend fun calculate(cache: TaxDailyFxCache): CapitalGainsEstimate = estimate(
            hydrateTaxExecutionFx(inputs, yearStart, yearEnd, cache) {
                mapOf(feb3 to decimal("1400"), feb4 to decimal("1500"))
            })
        val one = calculate(fresh)
        val two = calculate(seeded)
        assertEquals(one.netGainKrw, two.netGainKrw)
        assertEquals(one.estimatedTaxKrw, two.estimatedTaxKrw)
        assertEquals(decimal("3997000"), one.netGainKrw)
        assertFalse(one.fxSubstituted)
        assertFalse(two.fxSubstituted)
    }

    @Test fun legacyProxyFxSurvivesEmptyAndFailedKisHydration() = runBlocking {
        val inputs = proxyInputs()
        val expected = estimate(inputs)
        for (fail in listOf(false, true)) {
            var requests = 0
            val hydrated = hydrateTaxExecutionFx(inputs, yearStart, yearEnd, TaxDailyFxCache()) {
                requests++
                if (fail) throw IOException("fake KIS failure") else emptyMap()
            }
            assertEquals(1, requests)
            assertEquals(inputs, hydrated)
            assertEquals(decimal("1500"), hydrated.single { !it.buy }.fx)
            assertEquals(expected.netGainKrw, estimate(hydrated).netGainKrw)
            assertEquals(expected.estimatedTaxKrw, estimate(hydrated).estimatedTaxKrw)
            assertFalse(estimate(hydrated).fxSubstituted)
        }
    }

    @Test fun validProxyFxWinsOverPreviousDaySubstituteAfterKisFailure() = runBlocking {
        val inputs = proxyInputs()
        val cache = TaxDailyFxCache()
        cache.hydrate(setOf(key(feb3))) { mapOf(feb3 to decimal("1380")) }
        val hydrated = hydrateTaxExecutionFx(inputs, yearStart, yearEnd, cache) { throw IOException("fake KIS failure") }
        assertEquals(inputs, hydrated)
        assertEquals(estimate(inputs).netGainKrw, estimate(hydrated).netGainKrw)
    }

    @Test fun consumedAcquisitionSubstituteAppearsInRowReasonAndFxFlag() = runBlocking {
        val inputs = proxyInputs().map { if (it.buy) it.copy(fx = null) else it }
        val cache = TaxDailyFxCache()
        cache.hydrate(setOf(key(feb2))) { mapOf(feb2 to decimal("1380")) }
        val hydrated = hydrateTaxExecutionFx(inputs, yearStart, yearEnd, cache, preserveSaleRate = true) { emptyMap() }
        assertEquals(decimal("1500"), hydrated.single { !it.buy }.fx)
        assertEquals(decimal("1380"), hydrated.single { it.buy }.fx)
        assertFalse(cache.isExact(key(feb3)))
        val result = estimate(hydrated)
        assertTrue(result.fxSubstituted)
        assertTrue(result.rows.single().estimated)
        assertTrue(result.rows.single().trade.capitalGainsBasis!!.estimated)
        assertTrue(result.rows.single().reason.contains("취득 $TAX_FX_SUBSTITUTED"))
        assertNotNull(result.netGainKrw)
    }

    @Test fun retryAfterOutageRecoversMissingDatesAndDoesNotRefetchSuccessfulRates() = runBlocking {
        val inputs = proxyInputs().map { it.copy(fx = null) }
        val cache = TaxDailyFxCache()
        // A successful sale-day lookup preceded the acquisition-day outage.
        cache.hydrate(setOf(key(feb4))) { mapOf(feb4 to decimal("1500")) }
        var outage = true
        var saved: TradeHistoryResponse? = null
        var historyLoads = 0
        val requests = mutableListOf<FxRange>()
        suspend fun annual(force: Boolean) = loadAnnualTaxHistory(2026, force, saved, save = { saved = it }) {
            historyLoads++
            history(hydrateTaxExecutionFx(inputs, yearStart, yearEnd, cache) { range ->
                requests += range
                assertFalse(feb4 in range.start..range.end) // No request for the cached sale date.
                if (outage) emptyMap() else mapOf(feb3 to decimal("1400"))
            })
        }
        val partial = annual(false)
        assertTrue(estimateCapitalGainsTax(2026, partial).fxSubstituted)
        assertNull(saved) // A fallback is shown now but never pinned as final.
        outage = false
        val recovered = annual(true) // Same forceRefresh flag as the notice's button.
        val result = estimateCapitalGainsTax(2026, recovered)
        assertFalse(result.fxSubstituted)
        assertEquals(decimal("3997000"), result.netGainKrw)
        assertEquals(2, requests.size)
        assertSame(recovered, saved)
        assertSame(recovered, annual(false))
        assertEquals(2, historyLoads)
        annual(true)
        assertEquals(3, historyLoads)
        assertEquals(2, requests.size) // Explicit refresh keeps both successful exact rates.
    }

    @Test fun reopeningRejectsFallbackStoredByAnOlderSessionCachePolicy() = runBlocking {
        val fallback = history(proxyInputs().map { if (it.buy) it.copy(fx = null) else it })
        val exact = history(proxyInputs())
        var saved: TradeHistoryResponse? = fallback
        var loads = 0
        val recovered = loadAnnualTaxHistory(2026, false, saved, { saved = it }) { loads++; exact }
        assertEquals(1, loads)
        assertSame(exact, recovered)
        assertSame(exact, saved)
    }

    @Test fun retryClearsPriorSubstituteProvenanceOnceExactRateArrives() = runBlocking {
        val cache = TaxDailyFxCache()
        cache.hydrate(setOf(key(feb2))) { mapOf(feb2 to decimal("1380")) }
        val inputs = proxyInputs().map { if (it.buy) it.copy(fx = null) else it }
        val partial = hydrateTaxExecutionFx(inputs, yearStart, yearEnd, cache, preserveSaleRate = true) { emptyMap() }
        assertTrue(estimate(partial).fxSubstituted)
        val recovered = hydrateTaxExecutionFx(partial, yearStart, yearEnd, cache, preserveSaleRate = true) {
            mapOf(feb3 to decimal("1400"))
        }
        assertFalse(recovered.single { it.buy }.source.contains(TAX_FX_SUBSTITUTED))
        assertFalse(estimate(recovered).fxSubstituted)
        assertEquals(decimal("1400"), recovered.single { it.buy }.fx)
    }
}
