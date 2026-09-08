package com.koreainv.dashboard.network.insight

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset

/** Bounded strict JSON reader. Unknown fields are never retained in returned DTOs. */
internal object InsightParser {
    const val MAX_BYTES = 512 * 1024
    const val MAX_ROWS = 1000
    fun root(raw: String): JsonObject {
        require(raw.length <= MAX_BYTES) { "Response too large" }
        var nodes = 0
        fun read(r: JsonReader, depth: Int): JsonElement {
            require(depth <= 12 && ++nodes <= 20000) { "JSON complexity limit" }
            return when (r.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().also { o ->
                    r.beginObject(); var count = 0
                    while (r.hasNext()) { require(++count <= 128); val key = r.nextName(); require(key.length <= 128 && !o.has(key)); o.add(key, read(r, depth + 1)) }; r.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().also { a -> r.beginArray(); while (r.hasNext()) { require(a.size() < MAX_ROWS); a.add(read(r, depth + 1)) }; r.endArray() }
                JsonToken.STRING -> JsonPrimitive(r.nextString().also { require(it.length <= 8192) })
                JsonToken.NUMBER -> JsonPrimitive(r.nextString().also { require(it.length <= 64) }.toBigDecimal().also { require(it.toDouble().isFinite()) })
                JsonToken.BOOLEAN -> JsonPrimitive(r.nextBoolean())
                JsonToken.NULL -> { r.nextNull(); JsonNull.INSTANCE }
                else -> error("Invalid JSON")
            }
        }
        return JsonReader(StringReader(raw)).use { r ->
            r.setStrictness(Strictness.STRICT)
            r.setNestingLimit(12)
            val value = read(r, 0); require(r.peek() == JsonToken.END_DOCUMENT && value.isJsonObject); value.asJsonObject
        }
    }
    private fun JsonObject.v(k: String) = get(k)?.takeUnless { it.isJsonNull }
    private fun JsonObject.s(k: String): String? = v(k)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
    private fun JsonObject.n(k: String): Double? = v(k)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber); it.asDouble.also { n -> require(n.isFinite()) } }
    private fun JsonObject.b(k: String): Boolean? = v(k)?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean }
    private fun JsonObject.o(k: String): JsonObject? = v(k)?.let { require(it.isJsonObject); it.asJsonObject }
    private fun JsonObject.rows(k: String, max: Int = MAX_ROWS): List<JsonObject> = v(k)?.let { require(it.isJsonArray && it.asJsonArray.size() <= max); it.asJsonArray.map { row -> require(row.isJsonObject); row.asJsonObject } } ?: emptyList()
    private fun JsonObject.time(k: String): String? = v(k)?.let { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) Instant.ofEpochMilli(n(k)!!.toLong()).toString() else s(k) }
    private fun JsonObject.recognize(vararg keys: String) { require(keys.any { has(it) }) { "Unsupported section schema" } }
    private fun range(o: JsonObject?) = o?.let { InsightRange(it.n("low"), it.n("high"), it.n("current")) }
    private fun metric(o: JsonObject?) = o?.let { InsightMetric(it.n("value"), it.n("compare"), it.s("replaceText"), it.s("direction"), it.s("sectorPercentile")) }
    fun header(o: JsonObject): InsightHeader {
        o.recognize("price", "marketCap", "week52Range")
        return InsightHeader(o.s("marketStatus"), o.time("asOf"), o.n("price"), o.n("changePercent"), o.s("changeBasis"), o.n("turnover"), range(o.o("dayRange")), range(o.o("week52Range")), o.o("extendedHours")?.let { InsightExtendedHours(it.n("price"), it.n("changePercent"), it.time("asOf"), it.s("session")) }, o.n("marketCap"), o.n("marketCapRank"), o.n("turnoverRank"))
    }
    fun metrics(o: JsonObject): InsightKeyMetrics {
        o.recognize("per", "eps", "revenueTtm", "dividendYield", "roe", "shortInterestPct")
        return InsightKeyMetrics(metric(o.o("per")), metric(o.o("eps")), metric(o.o("revenueTtm")), metric(o.o("dividendYield")), metric(o.o("roe")), metric(o.o("shortInterestPct")), metric(o.o("daysToCover")), o.s("shortAsOf"), o.s("shortBasis"), o.time("asOf"), o.s("periodLabel"))
    }
    fun revenue(o: JsonObject): InsightRevenue {
        o.recognize("quarters")
        return InsightRevenue(o.s("source"), o.rows("quarters", 80).map { InsightQuarter(it.s("label"), it.n("revenue"), it.n("yoy"), it.s("replaceText"), it.s("direction")) })
    }
    fun analyst(o: JsonObject): InsightAnalyst {
        o.recognize("analystCount", "dist", "target", "recent")
        return InsightAnalyst(o.n("analystCount"), o.o("dist")?.let { InsightDistribution(it.n("buy"), it.n("hold"), it.n("sell")) }, o.s("label"), o.o("target")?.let { InsightTarget(it.n("mean"), it.n("low"), it.n("high")) }, o.n("upsidePct"), o.rows("recent").map { InsightAnalystRow(it.s("firm"), it.s("firmKo"), it.n("target"), it.n("prevTarget"), it.s("rating"), it.s("prevRating"), it.s("action"), it.s("at"), it.s("prevSource"), it.n("upsidePct"), it.b("isNew")) }, o.s("provider"), o.time("asOf"))
    }
    fun insider(o: JsonObject): InsightInsider {
        o.recognize("buyCount", "sellCount", "netValue", "recent")
        return InsightInsider(o.n("window"), o.n("buyCount"), o.n("sellCount"), o.n("netValue"), o.s("label"), o.rows("recent").map { InsightInsiderRow(it.s("name"), it.s("title"), it.n("value"), it.s("transactionDate"), it.s("transactionCode")) }, o.time("asOf"))
    }
    private fun share(o: JsonObject?) = o?.let { InsightShare(it.n("call"), it.n("put")) }
    fun options(o: JsonObject): InsightOptions {
        o.recognize("optionable", "volume", "volumeShare", "openInterestShare")
        val average = o.o("optionVolumeVsAvg")?.let { a ->
            val windows = a.o("windows")
            InsightVolumeAverage(a.n("roundSeq"), a.n("asOfMinute"), a.n("currentCumVolume"), listOf("d3", "d7", "d30").mapNotNull { key -> windows?.o(key)?.let { w -> val available = w.b("available") == true; key to InsightAverageWindow(available, if (available) w.n("ratioPct") else null, w.n("baselineCumVolume")) } }.toMap())
        }
        return InsightOptions(o.b("optionable"), o.time("asOf"), o.s("snapshotDate"), o.b("snapshotIsPriorDay"), o.time("snapshotUpdatedAt"), o.s("batchDate"), o.b("batchIsPriorDay"), o.time("batchUpdatedAt"), o.b("batchIsProvisional"), o.s("nearestExpiry"), o.n("daysToExpiry"), o.n("maxPain"), o.n("volume"), o.n("putCallRatioVolume"), o.n("putCallRatioOpenInterest"), share(o.o("volumeShare")), share(o.o("openInterestShare")), share(o.o("premiumShare")), average, o.n("referencePrice"), o.n("netGammaExposure"), o.n("gammaPer1Pct"), o.n("callWall"), o.n("putWall"), o.n("gammaFlip"))
    }
    fun news(o: JsonObject): InsightNews {
        o.recognize("news_list", "items")
        val normalized = o.has("items")
        return InsightNews(o.rows(if (normalized) "items" else "news_list").take(3).mapNotNull { row ->
            val title = row.s("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = if (normalized) row.s("link") else row.v("id")?.let { id ->
                require(id.isJsonPrimitive && (id.asJsonPrimitive.isString || id.asJsonPrimitive.isNumber))
                val text = id.asString; require(text.isNotBlank() && text.length <= 128)
                "https://saveticker.com".toHttpUrlOrNull()!!.newBuilder().addPathSegment("news").addPathSegment(text).build().toString()
            }
            val url = link?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443 } ?: return@mapNotNull null
            InsightNewsItem(title, row.s(if (normalized) "publisher" else "source"), url.toString(), row.s(if (normalized) "published_at" else "created_at"))
        })
    }
    fun bars(o: JsonObject): List<PriceBar> {
        o.recognize("bars")
        require(o.s("interval")?.let { it == "day" } != false && o.s("range")?.let { it == "1y" } != false)
        var previous = Long.MIN_VALUE
        return o.rows("bars", 400).map { row ->
            val rawTime = requireNotNull(row.n("time")); require(rawTime > 0 && rawTime % 1.0 == 0.0 && rawTime < 32_503_680_000_000.0)
            val time = (if (rawTime < 100_000_000_000.0) rawTime * 1000 else rawTime).toLong()
            require(time > previous); previous = time
            val open = requireNotNull(row.n("open")); val high = requireNotNull(row.n("high")); val low = requireNotNull(row.n("low")); val close = requireNotNull(row.n("close")); val volume = requireNotNull(row.n("volume"))
            require(low >= 0 && high >= low && open in low..high && close in low..high && volume >= 0)
            PriceBar(time, Instant.ofEpochMilli(time).atOffset(ZoneOffset.UTC).toLocalDate().toString(), open, high, low, close, volume)
        }
    }
}
