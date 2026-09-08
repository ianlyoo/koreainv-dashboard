package com.koreainv.dashboard.network.insight

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.Locale

/** Memory only, at most 16 tickers, 3 active endpoints globally. Nothing polls. */
class SaveTickerInsightRepository(private val session: InsightSessionManager, maxCachedStocks: Int = 8) {
    private val cacheLimit = maxCachedStocks.coerceIn(1, 16)
    val connectionState: StateFlow<InsightConnectionState> = session.state
    private data class Key(val lease: InsightLease, val market: String, val ticker: String)
    private data class Entry(val snapshot: InsightSnapshot, val expires: Long, val failed: Boolean)
    private class Flight(val task: Deferred<InsightSnapshot>, var readers: Int = 0)
    private val guard = Any()
    private val cache = LinkedHashMap<Key, Entry>(16, .75f, true)
    private val flights = mutableMapOf<Key, Flight>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parallel = Semaphore(3)
    private var cacheGeneration = 0L
    private var closed = false
    private val unregister = session.onInvalidated(::clearCache)

    fun clearCache() = synchronized(guard) {
        cacheGeneration++; cache.clear(); flights.values.forEach { it.task.cancel() }; flights.clear()
    }
    fun trimCache() = synchronized(guard) { cache.clear() }
    fun close() { clearCache(); synchronized(guard) { closed = true }; unregister(); scope.cancel() }
    fun peek(ticker: String, marketType: String = "USA"): InsightSnapshot? {
        val lease = runCatching { session.lease() }.getOrNull() ?: return null
        val key = Key(lease, marketType.uppercase(Locale.ROOT), ticker.trim().uppercase(Locale.ROOT))
        return session.adopt(lease) { synchronized(guard) { cache[key]?.let { if (it.expires <= session.now()) it.snapshot.copy(isStale = true) else it.snapshot } } }
    }
    suspend fun fetch(ticker: String, marketType: String = "USA", forceRefresh: Boolean = false): InsightSnapshot {
        val symbol = ticker.trim().uppercase(Locale.ROOT)
        val market = marketType.uppercase(Locale.ROOT)
        if (market != "USA" || !Regex("[A-Z][A-Z0-9.-]{0,19}").matches(symbol)) return InsightSnapshot(symbol, market, status = InsightSnapshotStatus.UNSUPPORTED, message = "미국 주식 종목만 지원합니다.")
        val lease = session.lease()
        val key = Key(lease, market, symbol)
        var cachedSnapshot: InsightSnapshot? = null
        val flight = session.adopt(lease) {
            synchronized(guard) {
                check(!closed)
                val old = cache[key]
                // Force refresh bypasses success TTL, never a supplier/error cooldown.
                if (old != null && session.now() < old.expires && (!forceRefresh || old.failed)) { cachedSnapshot = old.snapshot; return@synchronized null }
                flights[key]?.also { it.readers++ } ?: run {
                    if (flights.size >= 16) throw IOException("인사이트 요청이 많습니다. 잠시 후 다시 시도해 주세요.")
                    val generation = cacheGeneration
                    val task = scope.async(start = CoroutineStart.LAZY) {
                        val result = load(key, old?.snapshot)
                        session.adopt(lease) {
                            synchronized(guard) {
                                ensureActive(); check(cacheGeneration == generation)
                                val failed = result.sections.values.any { it.status == InsightSectionStatus.ERROR || it.status == InsightSectionStatus.RATE_LIMITED } || result.status in setOf(InsightSnapshotStatus.OFFLINE, InsightSnapshotStatus.UNAVAILABLE, InsightSnapshotStatus.RATE_LIMITED)
                                val ttl = if (failed) 60_000 else 300_000
                                cache[key] = Entry(result, maxOf(session.now() + ttl, result.retryAtMillis ?: 0), failed)
                                while (cache.size > cacheLimit) cache.remove(cache.keys.first())
                            }
                        }
                        result
                    }
                    Flight(task, 1).also { flights[key] = it }
                }
            }
        }
        cachedSnapshot?.let { return it }
        requireNotNull(flight)
        try { return flight.task.await().also { session.adopt(lease) {} } }
        finally { synchronized(guard) { flight.readers--; if (flight.readers == 0) { if (!flight.task.isCompleted) flight.task.cancel(); if (flights[key] === flight) flights.remove(key) } } }
    }
    private data class Section(val value: Any? = null, val info: InsightSectionInfo, val offline: Boolean = false, val retryAt: Long? = null)
    private suspend fun load(key: Key, previous: InsightSnapshot?): InsightSnapshot = coroutineScope {
        val names = listOf("header", "key_metrics", "revenue", "analyst", "insider", "options", "news", "bars")
        val endpoints = listOf("header", "key-metrics", "revenue-trend", "analyst", "sec-insider", "options", "news", "bars?range=1y&interval=day")
        val results = names.zip(endpoints).map { (name, endpoint) -> async {
            name to parallel.withPermit {
                val path = if (name == "news") "/api/news/company?page=1&page_size=3&ticker=${key.ticker}&sort=created_at_desc" else "/api/stocks/api/v1/tickers/${key.ticker}/$endpoint"
                try {
                    val response = session.get(key.lease, path)
                    when (response.code) {
                        204 -> Section(info = InsightSectionInfo(InsightSectionStatus.EMPTY))
                        404 -> Section(info = InsightSectionInfo(InsightSectionStatus.UNSUPPORTED))
                        200 -> {
                            val root = InsightParser.root(response.body)
                            if (root.size() == 0) Section(info = InsightSectionInfo(InsightSectionStatus.EMPTY)) else {
                                val value: Any = when (name) {
                                    "header" -> InsightParser.header(root); "key_metrics" -> InsightParser.metrics(root); "revenue" -> InsightParser.revenue(root)
                                    "analyst" -> InsightParser.analyst(root); "insider" -> InsightParser.insider(root); "options" -> InsightParser.options(root)
                                    "news" -> InsightParser.news(root); else -> InsightParser.bars(root)
                                }
                                val empty = when (value) { is InsightRevenue -> value.quarters.isEmpty(); is InsightNews -> value.items.isEmpty(); is List<*> -> value.isEmpty(); else -> false }
                                val asOf = when (value) { is InsightHeader -> value.asOf; is InsightKeyMetrics -> value.asOf; is InsightAnalyst -> value.asOf; is InsightInsider -> value.asOf; is InsightOptions -> value.asOf; else -> null }
                                val source = when (value) { is InsightRevenue -> value.source; is InsightAnalyst -> value.provider; else -> null } ?: "SaveTicker"
                                Section(value, InsightSectionInfo(if (empty) InsightSectionStatus.EMPTY else InsightSectionStatus.AVAILABLE, source, asOf, (value as? InsightOptions)?.batchIsProvisional))
                            }
                        }
                        else -> Section(info = InsightSectionInfo(InsightSectionStatus.ERROR, message = "공급자 응답 오류 (${response.code})"))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: InsightExpiredException) { throw e }
                catch (e: InsightRateLimitException) { Section(info = InsightSectionInfo(InsightSectionStatus.RATE_LIMITED, message = e.message), retryAt = e.retryAt) }
                catch (_: IOException) { Section(info = InsightSectionInfo(InsightSectionStatus.ERROR, message = "네트워크 연결을 확인해 주세요."), offline = true) }
                catch (_: Exception) { Section(info = InsightSectionInfo(InsightSectionStatus.ERROR, message = "공급자 데이터 형식을 확인할 수 없습니다.")) }
            }
        } }.awaitAll().toMap()
        session.adopt(key.lease) {}
        if (results.values.all { it.offline } && previous != null) return@coroutineScope previous.copy(status = InsightSnapshotStatus.OFFLINE, isStale = true, message = "오프라인 · 이 연결 세션의 마지막 데이터를 표시합니다.")
        val count = results.values.count { it.info.status == InsightSectionStatus.AVAILABLE }
        val limited = results.values.any { it.retryAt != null }
        val status = when {
            count == names.size -> InsightSnapshotStatus.AVAILABLE
            count > 0 -> InsightSnapshotStatus.PARTIAL
            limited -> InsightSnapshotStatus.RATE_LIMITED
            results.values.all { it.info.status == InsightSectionStatus.UNSUPPORTED } -> InsightSnapshotStatus.UNSUPPORTED
            results.values.all { it.info.status == InsightSectionStatus.EMPTY } -> InsightSnapshotStatus.EMPTY
            results.values.all { it.offline } -> InsightSnapshotStatus.OFFLINE
            else -> InsightSnapshotStatus.UNAVAILABLE
        }
        @Suppress("UNCHECKED_CAST")
        InsightSnapshot(key.ticker, key.market, results["header"]?.value as? InsightHeader, results["key_metrics"]?.value as? InsightKeyMetrics,
            results["revenue"]?.value as? InsightRevenue, results["analyst"]?.value as? InsightAnalyst, results["insider"]?.value as? InsightInsider,
            results["options"]?.value as? InsightOptions, results["news"]?.value as? InsightNews, (results["bars"]?.value as? List<PriceBar>).orEmpty(),
            results.mapValues { it.value.info }, session.now(), status, false, when (status) {
                InsightSnapshotStatus.PARTIAL -> "일부 데이터만 제공됩니다."
                InsightSnapshotStatus.UNSUPPORTED -> "지원하지 않는 종목입니다."
                InsightSnapshotStatus.EMPTY -> "아직 제공된 데이터가 없습니다."
                InsightSnapshotStatus.OFFLINE -> "네트워크 연결을 확인해 주세요."
                InsightSnapshotStatus.RATE_LIMITED -> "요청이 많습니다. 잠시 후 다시 시도해 주세요."
                InsightSnapshotStatus.UNAVAILABLE -> "데이터를 불러오지 못했습니다."
                else -> null
            }, results.values.mapNotNull { it.retryAt }.maxOrNull())
    }
}
