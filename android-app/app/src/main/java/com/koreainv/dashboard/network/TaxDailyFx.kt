package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class FxDate(val currency: String, val date: LocalDate)
internal data class FxRange(val currency: String, val start: LocalDate, val end: LocalDate)

/** Small calendar ranges cover lot dates; unavailable daily rows remain labelled fallbacks. */
internal fun taxFxRanges(dates: Set<FxDate>): List<FxRange> = dates.groupBy { it.currency }.flatMap { (currency, wanted) ->
    val first = wanted.minOf { it.date }.minusDays(7)
    val last = wanted.maxOf { it.date }
    buildList {
        var start = first
        while (start <= last) {
            val end = minOf(start.plusDays(89), last)
            if (wanted.any { it.date in start..end }) add(FxRange(currency, start.minusDays(7), end))
            start = end.plusDays(1)
        }
    }
}

internal fun parseKisDailyFx(page: JsonObject): Map<LocalDate, BigDecimal> =
    page.getAsJsonArray("output2")?.mapNotNull { element ->
        runCatching {
            val row = element.asJsonObject
            val date = LocalDate.parse(row.get("stck_bsop_date").asString, DateTimeFormatter.BASIC_ISO_DATE)
            val rate = row.get("ovrs_nmix_prpr").asString.replace(",", "").toBigDecimal()
            if (rate.signum() > 0) date to rate else null
        }.getOrNull()
    }?.toMap().orEmpty()

/** Immutable successful dates survive explicit annual reloads. Memory lock excludes I/O. */
internal class TaxDailyFxCache(
    private val loadPersisted: suspend () -> Map<FxDate, BigDecimal> = { emptyMap() },
    private val savePersisted: suspend (Map<FxDate, BigDecimal>) -> Unit = {},
) {
    private val initializationMutex = Mutex()
    private var initialized = false
    private val mutex = Mutex()
    private val rates = mutableMapOf<FxDate, BigDecimal>()
    suspend fun isExact(key: FxDate): Boolean = mutex.withLock { rates.containsKey(key) }
    suspend fun hydrate(
        wanted: Set<FxDate>,
        budgetMillis: Long = TAX_FX_BUDGET_MILLIS,
        requestMillis: Long = TAX_FX_REQUEST_MILLIS,
        load: suspend (FxRange) -> Map<LocalDate, BigDecimal>,
    ): Map<FxDate, BigDecimal> {
        initializationMutex.withLock {
            if (!initialized) {
                val persisted = try { loadPersisted() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyMap() }
                mutex.withLock { rates.putAll(persisted) }
                initialized = true
            }
        }
        fun lookup(key: FxDate): BigDecimal? = rates[key] ?: (1L..7L).firstNotNullOfOrNull { lag ->
            rates[FxDate(key.currency, key.date.minusDays(lag))]
        }
        val missing = mutex.withLock { wanted.filter { lookup(it) == null }.toSet() }
        withTimeoutOrNull(budgetMillis) {
            var failures = 0
            for (range in taxFxRanges(missing)) {
                val fetched = withTimeoutOrNull(requestMillis) {
                    try { load(range) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyMap() }
                }.orEmpty()
                failures = if (fetched.isEmpty()) failures + 1 else 0
                mutex.withLock {
                    fetched.filter { (date, rate) -> date in range.start..range.end && rate.signum() > 0 }
                        .forEach { (date, rate) -> rates.putIfAbsent(FxDate(range.currency, date), rate) }
                }
                if (fetched.isNotEmpty()) {
                    try { savePersisted(mutex.withLock { rates.toMap() }) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Keep the completed rates in this session if storage is unavailable. */ }
                }
                if (failures >= 2) break
            }
        }
        return mutex.withLock { wanted.mapNotNull { key -> lookup(key)?.let { key to it } }.toMap() }
    }
}

internal fun nativeKrwCrossRate(usdKrw: BigDecimal, unitsPerUsd: BigDecimal): BigDecimal =
    usdKrw.divide(unitsPerUsd, MathContext.DECIMAL128)

/** Public daily prices only: no account IDs, execution timestamps or authentication data. */
internal object TaxDailyFxCodec {
    fun parse(raw: String?): Map<FxDate, BigDecimal> = runCatching {
        com.google.gson.JsonParser.parseString(raw).asJsonObject.entrySet().mapNotNull { (key, value) ->
            runCatching {
                val (currency, date) = key.split(":", limit = 2)
                val rate = value.asString.toBigDecimal()
                if (currency.matches(Regex("[A-Z]{3}")) && rate.signum() > 0)
                    FxDate(currency, LocalDate.parse(date)) to rate else null
            }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())
    fun serialize(rates: Map<FxDate, BigDecimal>): String = JsonObject().apply {
        rates.entries.sortedBy { "${it.key.currency}:${it.key.date}" }.forEach { (key, value) ->
            addProperty("${key.currency}:${key.date}", value.toPlainString())
        }
    }.toString()
}
