package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val TAX_HISTORY_MAX_PAGES = 100

/** Shared by tax, trade and balance queries. Lock covers one request, never a retry delay. */
internal class KisRequestPacer(private val intervalMillis: Long = 250L) {
    private val mutex = Mutex()
    private var lastStarted = 0L
    suspend fun request(retries: Int = 2, load: suspend () -> HttpTextResponse): HttpTextResponse {
        var remaining = retries
        while (true) {
            val response = mutex.withLock {
                val elapsed = (System.nanoTime() - lastStarted) / 1_000_000
                if (elapsed < intervalMillis) delay(intervalMillis - elapsed)
                lastStarted = System.nanoTime()
                load()
            }
            val code = runCatching { JsonParser.parseString(response.text).asJsonObject.get("msg_cd")?.asString }.getOrNull()
            if ((response.code != 429 && code != "EGW00201") || remaining <= 0) return response
            delay((retries - remaining + 1) * 800L)
            remaining--
        }
    }
}

/** Keeps completed pages on a later failure; only actual EOF is complete. */
internal suspend fun loadKisHistoryPages(
    maxPages: Int,
    query: LinkedHashMap<String, String>,
    fkField: String,
    nkField: String,
    mark: (HistoryIncompleteReason) -> Unit,
    keepPartialOnFailure: Boolean = true,
    load: suspend (Map<String, String>, String) -> JsonObject?,
): List<JsonObject> {
    val pages = mutableListOf<JsonObject>()
    val current = LinkedHashMap(query)
    val pagination = HistoryPagination(maxPages)
    var continuation = ""
    repeat(maxPages) {
        val page = try { load(current, continuation) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (!keepPartialOnFailure) throw error; null }
        if (page == null) { mark(HistoryIncompleteReason.UNAVAILABLE); return pages }
        pages += page
        if (listOf("output", "output1").none { key -> page.get(key)?.let { it.isJsonArray || it.isJsonObject } == true } ||
            listOf("output", "output1").any { key -> page.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.any { !it.isJsonObject } == true })
            mark(HistoryIncompleteReason.MALFORMED_RESPONSE)
        fun field(key: String): String = runCatching { page.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")
        if (field("__tr_cont") !in setOf("F", "M")) return pages
        val fk = field(fkField)
        val nk = field(nkField)
        if (!pagination.advance(true, listOf(fk, nk).filter(String::isNotBlank).joinToString("|"), pages.size)) {
            pagination.reason?.let(mark)
            return pages
        }
        current[fkField.uppercase()] = fk
        current[nkField.uppercase()] = nk
        continuation = "N"
    }
    return pages
}
