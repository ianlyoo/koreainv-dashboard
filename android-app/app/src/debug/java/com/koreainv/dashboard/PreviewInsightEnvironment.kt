package com.koreainv.dashboard

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.koreainv.dashboard.network.insight.*
import kotlinx.coroutines.delay
import okhttp3.Cookie
import okhttp3.Request
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin

/** Debug-source-set only: real parser/repository with memory storage and zero network. */
internal class PreviewInsightEnvironment(context: Context, private val fixture: String) {
    private val payload = InsightParser.root(context.assets.open("insight/AVGO.json").bufferedReader().use { it.readText() })
    private class Store : InsightSecretStore {
        override val profileId = "00000000-0000-0000-0000-000000000001"
        private var saved: InsightCredentials? = null
        override fun hasStored() = saved != null
        override fun canRemember() = true
        override fun read() = saved
        override fun write(credentials: InsightCredentials) { saved = credentials }
        override fun beginUse() = Unit
        override fun finishUse(validUntilMillis: Long) = Unit
        override fun delete() { saved = null }
    }
    private var offline = false
    private val transport = object : InsightTransport {
        override suspend fun execute(request: Request): InsightHttpResponse {
            delay(if (fixture == "loading") 60_000 else 30)
            if (offline) throw IOException("Offline preview")
            val path = request.url.pathSegments.last()
            if (path == "login") return InsightHttpResponse(200,"""{"user_info":{}}""",listOf(
                Cookie.Builder().name("access_token").value("offline-preview-only").hostOnlyDomain("saveticker.com").path("/").secure().httpOnly().build()
            ),null)
            if (fixture == "partial" && path == "options") return InsightHttpResponse(503,"",emptyList(),null)
            val key = mapOf("key-metrics" to "key_metrics","revenue-trend" to "revenue","sec-insider" to "insider","company" to "news")[path] ?: path
            return InsightHttpResponse(200,if (path == "bars") bars.toString() else payload.get(key).toString(),emptyList(),null)
        }
        override fun cancelAll() = Unit
        override fun close() = Unit
    }
    private var clock = System.currentTimeMillis()
    val session = InsightSessionManager(Store(), transport) { clock }
    val repository = SaveTickerInsightRepository(session,4)
    private val bars = JsonObject().apply {
        addProperty("range","1y"); addProperty("interval","day")
        add("bars",JsonArray().apply {
            var day = LocalDate.of(2025,9,5)
            var index = 0
            while (day <= LocalDate.of(2026,9,4)) {
                if (day.dayOfWeek !in listOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY)) {
                    val quote = payload.getAsJsonObject("header").get("price").asDouble
                    val lastClose = quote / (1 + payload.getAsJsonObject("header").get("changePercent").asDouble / 100)
                    val close = lastClose + (index-260)*.45 + (sin(index*.23)-sin(260*.23))*8
                    val open = close + sin(index*.71)*3
                    add(JsonObject().apply {
                        addProperty("time",day.atStartOfDay().toEpochSecond(ZoneOffset.UTC))
                        addProperty("open",open);addProperty("close",close)
                        addProperty("high",maxOf(open,close)+3);addProperty("low",minOf(open,close)-3)
                        addProperty("volume",1_000_000 + index*1500)
                    }); index++
                }
                day=day.plusDays(1)
            }
        })
    }
    suspend fun start() {
        session.unlock()
        if (fixture !in listOf("disconnected","expired","connection")) {
            session.connect("preview@example.com","preview-only",false)
            if (fixture == "offline") { repository.fetch("AVGO"); clock += 300_001; offline=true }
        }
    }
    fun close() { session.lock();repository.close() }
}
