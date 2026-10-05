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
internal data class DailyTaxFxRate(val value: BigDecimal, val publicationDate: LocalDate)
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
    ): Map<FxDate, BigDecimal> = hydrateWithProvenance(wanted, budgetMillis, requestMillis, load).mapValues { it.value.value }

    /** Value and publication date come from the same locked snapshot. */
    suspend fun hydrateWithProvenance(
        wanted: Set<FxDate>,
        budgetMillis: Long = TAX_FX_BUDGET_MILLIS,
        requestMillis: Long = TAX_FX_REQUEST_MILLIS,
        load: suspend (FxRange) -> Map<LocalDate, BigDecimal>,
    ): Map<FxDate, DailyTaxFxRate> {
        initializationMutex.withLock {
            if (!initialized) {
                val persisted = try { loadPersisted() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyMap() }
                mutex.withLock { rates.putAll(persisted) }
                initialized = true
            }
        }
        fun lookup(key: FxDate): DailyTaxFxRate? = rates[key]?.let { DailyTaxFxRate(it, key.date) }
            ?: (1L..7L).firstNotNullOfOrNull { lag ->
                val published = key.date.minusDays(lag)
                rates[FxDate(key.currency, published)]?.let { DailyTaxFxRate(it, published) }
            }
        // A nearby published rate may substitute only after trying the requested date.
        val missing = mutex.withLock { wanted.filterNot { rates.containsKey(it) }.toSet() }
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

internal const val TAX_FX_SUBSTITUTED = "환율 일부 대체(직전 고시일)"
private const val KIS_DAILY_FX_SOURCE = "한투 일별 참고환율"

/** Shared by the real repository and recorded-shape/recovery fixtures. */
internal suspend fun hydrateTaxExecutionFx(
    inputs: List<TaxExecution>,
    start: LocalDate,
    end: LocalDate,
    cache: TaxDailyFxCache,
    preserveSaleRate: Boolean = false,
    load: suspend (FxRange) -> Map<LocalDate, BigDecimal>,
): List<TaxExecution> {
    val fifoKeys = taxFxExecutionKeys(inputs, start, end)
    val needed = inputs.filter {
        it.key in fifoKeys && it.currency != "KRW" &&
            (if (it.buy || preserveSaleRate) it.fx == null || it.source.contains(TAX_FX_SUBSTITUTED) else true)
    }
    val keys = needed.map { FxDate(it.currency, it.date) }.toSet()
    val rates = cache.hydrateWithProvenance(keys, load = load)
    val neededKeys = needed.map { it.key }.toSet()
    return inputs.map { input ->
        val replacement = rates[FxDate(input.currency, input.date)]
        val originalValid = input.fx?.signum() == 1 && !input.source.contains(TAX_FX_SUBSTITUTED)
        when {
            input.key !in neededKeys || replacement == null -> input
            replacement.publicationDate != input.date && originalValid -> input
            else -> {
                // Clear an earlier substitute label if a retry obtains the exact date.
                val source = input.source.split(" · ").filterNot { it == KIS_DAILY_FX_SOURCE || it == TAX_FX_SUBSTITUTED }
                    .joinToString(" · ")
                input.copy(fx = replacement.value, source = listOfNotNull(source, KIS_DAILY_FX_SOURCE,
                    TAX_FX_SUBSTITUTED.takeIf { replacement.publicationDate != input.date }).joinToString(" · "))
            }
        }
    }
}
