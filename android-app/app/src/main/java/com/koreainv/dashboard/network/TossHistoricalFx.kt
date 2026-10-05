package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import java.time.OffsetDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val TOSS_HISTORICAL_FX_SOURCE = "토스 매도 시점 참고환율(midRate) · 환차손익 제외"
private const val HISTORICAL_FX_WINDOW_TOLERANCE_SECONDS = 600L

internal fun supportsTossHistoricalProxyProfit(result: JsonObject): Boolean =
    result.text("profit_fx_basis") == "sale_historical_mid_rate"

internal fun tossHistoryNote(startDate: String, complete: Boolean): String =
    "토스 조회 가능 체결 이력: ${startDate.ifBlank { "없음" }}부터 · ${if (complete) "페이지 조회 완료(이전 원가 보장 안 됨)" else "이력 조회 미완료"}"

private fun JsonObject.text(name: String): String =
    runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

private fun JsonObject.finiteNumber(name: String): Double? =
    text(name).toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun validatedTossHistoricalMidRate(result: JsonObject, filledAt: String): Double {
    if (result.text("baseCurrency") != "USD" || result.text("quoteCurrency") != "KRW") return 0.0
    val requested = runCatching { OffsetDateTime.parse(filledAt).toInstant() }.getOrNull() ?: return 0.0
    val from = runCatching { OffsetDateTime.parse(result.text("validFrom")).toInstant() }.getOrNull() ?: return 0.0
    val until = runCatching { OffsetDateTime.parse(result.text("validUntil")).toInstant() }.getOrNull() ?: return 0.0
    if (from >= until || requested < from.minusSeconds(HISTORICAL_FX_WINDOW_TOLERANCE_SECONDS) ||
        requested > until.plusSeconds(HISTORICAL_FX_WINDOW_TOLERANCE_SECONDS)
    ) return 0.0
    return result.finiteNumber("midRate")?.takeIf { it > 0.0 } ?: 0.0
}

/** Repository-local cache: credentials stay outside the key; each entry is revalidated for the requested timestamp. */
internal class TossHistoricalFxCache(private val capacity: Int = 1024) {
    private val mutex = Mutex()
    private data class Entry(val result: JsonObject?, val expiresAt: Long)
    private val entries = LinkedHashMap<Pair<String, String>, Entry>()

    suspend fun getOrLoad(scope: String, filledAt: String, now: () -> Long, load: suspend () -> JsonObject?): Double {
        mutex.withLock { get(scope, filledAt, now()) }?.let { return it }
        val result = load()
        return mutex.withLock { put(scope, filledAt, result, now()) }
    }

    fun get(scope: String, filledAt: String, now: Long): Double? {
        val key = scope to filledAt
        val entry = entries.remove(key) ?: return null
        if (now >= entry.expiresAt) return null
        entries[key] = entry
        return entry.result?.let { validatedTossHistoricalMidRate(it, filledAt) } ?: 0.0
    }

    fun put(scope: String, filledAt: String, result: JsonObject?, now: Long): Double {
        val rate = result?.let { validatedTossHistoricalMidRate(it, filledAt) } ?: 0.0
        val key = scope to filledAt
        entries.remove(key)
        entries[key] = Entry(if (rate > 0.0) result?.deepCopy() else null, now + if (rate > 0.0) 86_400_000 else 60_000)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return rate
    }
}

/** Official OrderExecution is an aggregate fill, with nullable charges. */
internal fun parseTossExecutionForEstimate(row: JsonObject): TossExecutionForEstimate? {
    val execution = row.get("execution")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val quantity = execution.finiteNumber("filledQuantity")?.takeIf { it > 0.0 } ?: return null
    val filledAt = execution.text("filledAt")
    val timestamp = filledAt.ifBlank { row.text("orderedAt") }
    val date = timestamp.take(10).replace("-", "")
    if (date.length != 8) return null
    val price = execution.finiteNumber("averageFilledPrice") ?: 0.0
    val amount = execution.finiteNumber("filledAmount")?.takeIf { it > 0.0 } ?: quantity * price
    val commission = execution.finiteNumber("commission")
    val tax = execution.finiteNumber("tax")
    val time = if (timestamp.length >= 19) timestamp.substring(11, 19).replace(":", "") else ""
    val symbol = row.text("symbol")
    val side = row.text("side").uppercase()
    val displaySide = if (side == "BUY") "매수" else if (side == "SELL") "매도" else side
    val key = row.text("orderId").ifBlank { listOf(date, time, symbol, displaySide, quantity, amount).joinToString("|") }
    return TossExecutionForEstimate(
        key, date, time, symbol, side, row.text("currency").uppercase(), quantity, amount,
        commission ?: 0.0, tax ?: 0.0, filledAt = filledAt,
        costsComplete = commission != null && tax != null,
    )
}
