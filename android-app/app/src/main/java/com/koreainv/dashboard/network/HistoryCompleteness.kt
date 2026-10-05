package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import java.util.Collections
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class HistoryIncompleteReason { MALFORMED_RESPONSE, BROKEN_CURSOR, PAGE_LIMIT, UNAVAILABLE }

data class AccountHistoryCompleteness(
    val accountId: String,
    val accountLabel: String,
    val reasons: Set<HistoryIncompleteReason> = emptySet(),
    val parts: Set<String> = emptySet(),
) {
    val complete: Boolean get() = reasons.isEmpty()
}

/** Load-local context shared by the account's parallel KIS market queries. */
internal class HistoryCoverage : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HistoryCoverage>
    private val parts = Collections.synchronizedSet(mutableSetOf<String>())
    private val failures = Collections.synchronizedSet(mutableSetOf<HistoryIncompleteReason>())
    fun mark(reason: HistoryIncompleteReason, part: String = "한투 해외 체결") { failures.add(reason); parts.add(part) }
    fun partSnapshot(): Set<String> = synchronized(parts) { parts.toSet() }
    fun snapshot(): Set<HistoryIncompleteReason> = synchronized(failures) { failures.toSet() }
}

internal fun tossProxyHistoryReasons(result: JsonObject): Set<HistoryIncompleteReason> {
    if (result.get("items")?.isJsonArray != true ||
        result.getAsJsonArray("items").any { !it.isJsonObject } ||
        result.get("profit_history_complete")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } != true
    ) return setOf(HistoryIncompleteReason.MALFORMED_RESPONSE)
    return if (result.get("profit_history_complete").asBoolean) emptySet()
    else setOf(HistoryIncompleteReason.UNAVAILABLE)
}

/** A continuation without a usable cursor, or beyond the cap, is never EOF. */
internal class HistoryPagination(private val maxPages: Int) {
    private val cursors = mutableSetOf<String>()
    var reason: HistoryIncompleteReason? = null
        private set
    fun advance(hasNext: Boolean, cursor: String, pages: Int): Boolean {
        if (!hasNext) return false
        reason = when {
            cursor.isBlank() || !cursors.add(cursor) -> HistoryIncompleteReason.BROKEN_CURSOR
            pages >= maxPages -> HistoryIncompleteReason.PAGE_LIMIT
            else -> null
        }
        return reason == null
    }
}
