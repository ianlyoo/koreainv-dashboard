package com.koreainv.dashboard.network

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.PingFrame
import org.java_websocket.handshake.ServerHandshake

internal const val US_DAY_MARKET_REFRESH_INTERVAL_MILLIS = 3_000L
internal const val US_DAY_MARKET_REFRESH_WINDOW_MILLIS = 60_000L

private const val WS_REAL_URL = "ws://ops.koreainvestment.com:21000"
private const val TR_ASKING_PRICE = "HDFSASP0"
private const val TR_CONTRACT = "HDFSCNT0"
private const val QUOTE_STALE_SECONDS = 180L
private const val APPKEY_CONFLICT_COOLDOWN_SECONDS = 180L
private const val CONTRACT_FIELD_COUNT = 26
private const val ASK_FIELD_COUNT = 17

private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
private val NEW_YORK_ZONE: ZoneId = ZoneId.of("America/New_York")
private val APPROVAL_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

internal data class UsMarketSessionInfo(
    val session: String,
    val isOpen: Boolean,
    val usesDayPrefix: Boolean,
)

internal data class UsQuoteSnapshot(
    val ticker: String,
    val trKey: String,
    val price: Double,
    val bid: Double? = null,
    val ask: Double? = null,
    val source: String = "websocket",
    val quoteSession: String? = null,
    val quotedAt: ZonedDateTime? = null,
    val updatedAt: ZonedDateTime? = null,
) {
    fun isStale(now: ZonedDateTime = ZonedDateTime.now(KST_ZONE)): Boolean {
        val refreshedAt = updatedAt ?: return true
        return Duration.between(refreshedAt, now).seconds > QUOTE_STALE_SECONDS
    }
}

internal fun getUsMarketSession(nowKst: ZonedDateTime = ZonedDateTime.now(KST_ZONE)): UsMarketSessionInfo {
    val normalized = nowKst.withZoneSameInstant(KST_ZONE)
    val hm = normalized.hour * 60 + normalized.minute
    val weekday = normalized.dayOfWeek.value - 1
    val isDst = NEW_YORK_ZONE.rules.isDaylightSavings(normalized.toInstant())

    val dayMarketStart = if (isDst) 9 * 60 else 10 * 60
    val dayMarketEnd = if (isDst) 17 * 60 else 18 * 60
    val premarketStart = if (isDst) 17 * 60 else 18 * 60
    val regularStart = if (isDst) 22 * 60 + 30 else 23 * 60 + 30
    val regularEnd = if (isDst) 5 * 60 else 6 * 60
    val aftermarketEnd = if (isDst) 8 * 60 else 9 * 60

    if (weekday < 5 && hm in dayMarketStart until dayMarketEnd) {
        return UsMarketSessionInfo(session = "day_market", isOpen = true, usesDayPrefix = true)
    }
    if (weekday < 5 && hm in premarketStart until regularStart) {
        return UsMarketSessionInfo(session = "premarket", isOpen = true, usesDayPrefix = false)
    }
    if ((weekday < 5 && hm >= regularStart) || (weekday in 1..5 && hm < regularEnd)) {
        return UsMarketSessionInfo(session = "regular", isOpen = true, usesDayPrefix = false)
    }
    if (weekday in 1..5 && hm in regularEnd until aftermarketEnd) {
        return UsMarketSessionInfo(session = "aftermarket", isOpen = true, usesDayPrefix = false)
    }
    return UsMarketSessionInfo(session = "closed", isOpen = false, usesDayPrefix = false)
}

internal fun buildUsTrKey(
    ticker: String,
    exchangeCode: String,
    sessionInfo: UsMarketSessionInfo = getUsMarketSession(),
): String? {
    val normalizedTicker = ticker.trim().uppercase(Locale.US)
    if (normalizedTicker.isBlank()) return null

    val exchange = exchangeCode.trim().uppercase(Locale.US)
    val regularPrefix = mapOf(
        "NASD" to "DNAS",
        "NAS" to "DNAS",
        "NYSE" to "DNYS",
        "NYS" to "DNYS",
        "AMEX" to "DAMS",
        "AMS" to "DAMS",
    )
    val dayPrefix = mapOf(
        "NASD" to "RBAQ",
        "NAS" to "RBAQ",
        "NYSE" to "RBAY",
        "NYS" to "RBAY",
        "AMEX" to "RBAA",
        "AMS" to "RBAA",
    )
    val prefix = if (sessionInfo.usesDayPrefix) dayPrefix else regularPrefix
    return prefix[exchange]?.plus(normalizedTicker)
}

internal class KisUsQuoteService(
    private val credentials: AppCredentials,
    private val client: OkHttpClient,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var approvalKey: String? = null
    private var webSocket: WebSocketClient? = null
    private var reconnectJob: Job? = null
    private var opening = false
    private var closed = false
    private var reconnectAttempt = 0
    private var appKeyConflictRetryAtMillis = 0L
    private var sessionName: String = "closed"
    private var trackedHoldings: List<Holding> = emptyList()
    private var desiredKeys: Map<String, Pair<String, String>> = emptyMap()
    private var subscribedKeys: Set<String> = emptySet()
    private val quoteCache = mutableMapOf<String, UsQuoteSnapshot>()

    suspend fun syncHoldings(usHoldings: List<Holding>, forceRetry: Boolean = false) {
        val filtered = usHoldings.filter { it.market == "USA" && !it.exchangeCode.isNullOrBlank() }
        val sessionInfo = getUsMarketSession()
        val socket = synchronized(lock) {
            trackedHoldings = filtered
            recomputeTargetsLocked(sessionInfo)
            if (forceRetry) {
                appKeyConflictRetryAtMillis = 0L
            }
            webSocket
        }
        if (filtered.isEmpty() || !sessionInfo.isOpen) {
            closeSocket()
            return
        }
        if (socket != null) {
            sendSubscriptions(socket)
            return
        }
        scope.launch {
            runCatching { ensureConnected() }
                .onFailure { error ->
                    Log.w(
                        "KisUsQuoteService",
                        "Unable to start U.S. quote stream; using balance-price fallback",
                        error,
                    )
                    scheduleReconnect(backoffDelayMillis())
                }
        }
    }

    fun enrichHoldings(holdings: List<Holding>): List<Holding> {
        val now = ZonedDateTime.now(KST_ZONE)
        val sessionInfo = getUsMarketSession(now)
        return holdings.map { holding ->
            if (holding.market != "USA") return@map holding

            val ticker = holding.symbol.trim().uppercase(Locale.US)
            val exchangeCode = holding.exchangeCode.orEmpty()
            val snapshot = synchronized(lock) { quoteCache[ticker] }
            val quoteTrKey = buildUsTrKey(ticker, exchangeCode, sessionInfo)
            val price = if (snapshot != null && !snapshot.isStale(now)) snapshot.price else holding.currentPrice
            val quoteSource = if (snapshot != null && !snapshot.isStale(now)) snapshot.source else "balance"
            val quoteStale = snapshot == null || snapshot.isStale(now)
            val quoteTimestamp = if (!quoteStale) (snapshot?.quotedAt ?: snapshot?.updatedAt)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) else null
            val rate = if (holding.currency == "JPY") max(holding.exchangeRate, 0.0) / 100.0 else max(holding.exchangeRate, 0.0)
            val totalValue = holding.quantity * price * rate
            val totalCost = holding.quantity * holding.averageCost * rate
            val profitLoss = totalValue - totalCost

            holding.copy(
                currentPrice = price,
                totalValueKrw = totalValue,
                totalCostKrw = totalCost,
                profitLossKrw = profitLoss,
                profitLossRate = if (totalCost > 0.0) profitLoss / totalCost * 100.0 else 0.0,
                quoteSession = sessionInfo.session,
                quoteSource = quoteSource,
                quoteStale = quoteStale,
                quoteTimestamp = quoteTimestamp,
                quoteTrKey = if (!quoteTrKey.isNullOrBlank()) quoteTrKey else holding.quoteTrKey,
            )
        }
    }

    fun getMarketStatus(usHoldings: List<Holding>): UsMarketStatus {
        val sessionInfo = getUsMarketSession()
        val now = ZonedDateTime.now(KST_ZONE)
        val freshCount = synchronized(lock) {
            usHoldings.count { holding ->
                val snapshot = quoteCache[holding.symbol.trim().uppercase(Locale.US)]
                snapshot != null && !snapshot.isStale(now)
            }
        }
        val totalCount = usHoldings.size
        val fallbackCount = max(totalCount - freshCount, 0)
        val sourceState = when {
            totalCount == 0 -> "idle"
            freshCount == totalCount -> "live"
            freshCount > 0 -> "mixed"
            else -> "fallback"
        }
        return UsMarketStatus(
            session = sessionInfo.session,
            isOpen = sessionInfo.isOpen,
            usesDayPrefix = sessionInfo.usesDayPrefix,
            sourceState = sourceState,
            trackedCount = totalCount,
            freshCount = freshCount,
            fallbackCount = fallbackCount,
        )
    }

    override fun close() {
        synchronized(lock) {
            closed = true
        }
        reconnectJob?.cancel()
        closeSocket()
        scope.cancel()
    }

    private suspend fun ensureConnected() {
        val waitMillis = synchronized(lock) {
            if (closed || opening || desiredKeys.isEmpty() || webSocket != null) {
                return
            }
            val now = System.currentTimeMillis()
            if (appKeyConflictRetryAtMillis > now) {
                return@synchronized appKeyConflictRetryAtMillis - now
            }
            opening = true
            0L
        }
        if (waitMillis > 0L) {
            scheduleReconnect(waitMillis)
            return
        }

        try {
            val approval = getApprovalKey()
            val listener = object : WebSocketClient(URI(WS_REAL_URL)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    runCatching {
                        synchronized(lock) {
                            this@KisUsQuoteService.webSocket = this
                            reconnectAttempt = 0
                        }
                        Log.d("KisUsQuoteService", "KIS U.S. quote websocket connected")
                        sendSubscriptions(this)
                    }.onFailure { error ->
                        Log.e("KisUsQuoteService", "KIS U.S. quote onOpen failed", error)
                        handleSocketClosed()
                    }
                }

                override fun onMessage(message: String?) {
                    runCatching { handleMessage(this, message.orEmpty()) }
                        .onFailure { error ->
                            Log.e("KisUsQuoteService", "KIS U.S. quote onMessage failed", error)
                        }
                }

                override fun onError(ex: Exception?) {
                    Log.w("KisUsQuoteService", "KIS U.S. quote websocket error", ex)
                    handleSocketClosed()
                    scheduleReconnect(backoffDelayMillis())
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    runCatching {
                        Log.d("KisUsQuoteService", "KIS U.S. quote websocket closed code=$code reason=${reason.orEmpty()} remote=$remote")
                        handleSocketClosed()
                        scheduleReconnect(backoffDelayMillis())
                    }.onFailure { error ->
                        Log.e("KisUsQuoteService", "KIS U.S. quote onClose failed", error)
                    }
                }
            }
            synchronized(lock) {
                approvalKey = approval
            }
            runCatching { listener.connect() }
                .onFailure { error ->
                    Log.e("KisUsQuoteService", "KIS U.S. quote connect start failed", error)
                    throw error
                }
        } finally {
            synchronized(lock) {
                opening = false
            }
        }
    }

    private suspend fun getApprovalKey(): String {
        synchronized(lock) {
            approvalKey?.let { return it }
        }
        val body = JsonObject().apply {
            addProperty("grant_type", "client_credentials")
            addProperty("appkey", credentials.appKey)
            addProperty("secretkey", credentials.appSecret)
        }.toString().toRequestBody(APPROVAL_JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("https://openapi.koreainvestment.com:9443/oauth2/Approval")
            .post(body)
            .header("content-type", "application/json")
            .build()

        val fetched = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("KIS approval key request failed: ${response.code}")
                }
                val payload = response.body?.string().orEmpty()
                val json = JsonParser().parse(payload).asJsonObject
                json.get("approval_key")?.asString.orEmpty().takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("KIS approval key missing")
            }
        }
        synchronized(lock) {
            approvalKey = fetched
        }
        return fetched
    }

    private fun sendSubscriptions(webSocket: WebSocketClient) {
        val approval = synchronized(lock) { approvalKey } ?: return
        val desired = synchronized(lock) { desiredKeys }
        val subscribed = synchronized(lock) { subscribedKeys }
        val removed = subscribed - desired.keys
        val added = desired.keys - subscribed

        scope.launch {
            removed.sorted().forEach { trKey ->
                webSocket.send(buildSubscribeMessage(approval, TR_ASKING_PRICE, trKey, "2"))
                webSocket.send(buildSubscribeMessage(approval, TR_CONTRACT, trKey, "2"))
                delay(50L)
            }
            added.sorted().forEach { trKey ->
                webSocket.send(buildSubscribeMessage(approval, TR_ASKING_PRICE, trKey, "1"))
                delay(50L)
                webSocket.send(buildSubscribeMessage(approval, TR_CONTRACT, trKey, "1"))
                delay(50L)
            }
            synchronized(lock) {
                if (this@KisUsQuoteService.webSocket == webSocket) {
                    subscribedKeys = desired.keys
                }
            }
        }
    }

    private fun buildSubscribeMessage(
        approvalKey: String,
        trId: String,
        trKey: String,
        trType: String,
    ): String {
        return JsonObject().apply {
            add("header", JsonObject().apply {
                addProperty("approval_key", approvalKey)
                addProperty("custtype", "P")
                addProperty("tr_type", trType)
                addProperty("content-type", "utf-8")
            })
            add("body", JsonObject().apply {
                add("input", JsonObject().apply {
                    addProperty("tr_id", trId)
                    addProperty("tr_key", trKey)
                })
            })
        }.toString()
    }

    private fun handleMessage(webSocket: WebSocketClient, data: String) {
        if (data.isBlank()) return
        if (data[0] == '0' || data[0] == '1') {
            val parts = data.split('|')
            if (parts.size >= 4) {
                when (parts[1]) {
                    TR_CONTRACT -> parseContractPayload(parts[3])
                    TR_ASKING_PRICE -> parseAskingPayload(parts[3])
                }
            }
            return
        }

        val json = runCatching { JsonParser().parse(data).asJsonObject }.getOrNull() ?: return
        val header = json.get("header")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val trId = header.get("tr_id")?.asString.orEmpty()
        if (trId == "PINGPONG") {
            val ping = PingFrame().apply {
                setPayload(ByteBuffer.wrap(data.toByteArray(Charsets.UTF_8)))
            }
            webSocket.sendFrame(ping)
            return
        }

        val body = json.get("body")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        if (body.get("rt_cd")?.asString.orEmpty() != "0") {
            val message = body.get("msg1")?.asString.orEmpty()
            if (message.contains("ALREADY IN USE appkey", ignoreCase = true)) {
                synchronized(lock) {
                    appKeyConflictRetryAtMillis = System.currentTimeMillis() + APPKEY_CONFLICT_COOLDOWN_SECONDS * 1000
                    subscribedKeys = emptySet()
                }
                webSocket.close(1000, message)
            }
            return
        }

        synchronized(lock) {
            recomputeTargetsLocked()
        }
        sendSubscriptions(webSocket)
    }

    private fun parseContractPayload(payload: String) {
        val fields = payload.split('^')
        val rows = fields.size / CONTRACT_FIELD_COUNT
        if (rows <= 0) return
        val now = ZonedDateTime.now(KST_ZONE)
        repeat(rows) { index ->
            val row = fields.subList(index * CONTRACT_FIELD_COUNT, (index + 1) * CONTRACT_FIELD_COUNT)
            val ticker = row[1].trim().uppercase(Locale.US)
            val trKey = row[0].trim().uppercase(Locale.US)
            val last = toDouble(row[11])
            if (ticker.isBlank() || last <= 0.0) return@repeat
            synchronized(lock) {
                quoteCache[ticker] = UsQuoteSnapshot(
                    ticker = ticker,
                    trKey = trKey,
                    price = last,
                    bid = toDouble(row[15]).takeIf { it > 0.0 },
                    ask = toDouble(row[16]).takeIf { it > 0.0 },
                    source = "websocket_contract",
                    quoteSession = sessionName,
                    quotedAt = parseKstTimestamp(row[6], row[7]),
                    updatedAt = now,
                )
            }
        }
    }

    private fun parseAskingPayload(payload: String) {
        val fields = payload.split('^')
        if (fields.size < ASK_FIELD_COUNT) return
        val ticker = fields[1].trim().uppercase(Locale.US)
        val trKey = fields[0].trim().uppercase(Locale.US)
        val bid = toDouble(fields[11])
        val ask = toDouble(fields[12])
        if (ticker.isBlank() || bid <= 0.0 || ask <= 0.0) return
        val midpoint = (bid + ask) / 2.0
        val now = ZonedDateTime.now(KST_ZONE)

        synchronized(lock) {
            val existing = quoteCache[ticker]
            if (existing?.updatedAt != null && Duration.between(existing.updatedAt, now).seconds < 5) {
                return
            }
            quoteCache[ticker] = UsQuoteSnapshot(
                ticker = ticker,
                trKey = trKey,
                price = midpoint,
                bid = bid,
                ask = ask,
                source = "websocket_bid_ask_mid",
                quoteSession = sessionName,
                quotedAt = parseKstTimestamp(fields[5], fields[6]),
                updatedAt = now,
            )
        }
    }

    private fun parseKstTimestamp(ymd: String?, hms: String?): ZonedDateTime? {
        val date = ymd.orEmpty().trim()
        val time = hms.orEmpty().trim()
        if (date.length != 8 || time.length != 6) return null
        return runCatching {
            LocalDateTime.parse(date + time, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atZone(KST_ZONE)
        }.getOrNull()
    }

    private fun recomputeTargetsLocked(sessionInfo: UsMarketSessionInfo = getUsMarketSession()) {
        sessionName = sessionInfo.session
        desiredKeys = trackedHoldings.mapNotNull { holding ->
            val exchangeCode = holding.exchangeCode.orEmpty()
            buildUsTrKey(holding.symbol, exchangeCode, sessionInfo)?.let { trKey ->
                trKey to (holding.symbol to exchangeCode)
            }
        }.toMap()
    }

    private fun handleSocketClosed() {
        synchronized(lock) {
            webSocket = null
            subscribedKeys = emptySet()
        }
    }

    private fun closeSocket() {
        val socket = synchronized(lock) {
            val current = webSocket
            webSocket = null
            subscribedKeys = emptySet()
            current
        }
        socket?.close(1000, "closing")
    }

    private fun backoffDelayMillis(): Long {
        val attempt = synchronized(lock) {
            reconnectAttempt = minOf(reconnectAttempt + 1, 5)
            reconnectAttempt
        }
        return (1L shl (attempt - 1)).coerceAtMost(30L) * 1000L
    }

    private fun scheduleReconnect(delayMillis: Long) {
        val shouldSchedule = synchronized(lock) {
            !closed && desiredKeys.isNotEmpty() && reconnectJob?.isActive != true
        }
        if (!shouldSchedule) return
        reconnectJob = scope.launch {
            delay(delayMillis)
            ensureConnected()
        }
    }

    private fun toDouble(value: String?): Double =
        value.orEmpty().replace(",", "").trim().toDoubleOrNull() ?: 0.0
}
