package com.koreainv.dashboard.network

import android.os.SystemClock
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class KisRepository(
    private val credentials: AppCredentials,
    private val settingsManager: SettingsManager,
) {
    companion object {
        private const val BASE_URL = "https://openapi.koreainvestment.com:9443"
        private const val TOKEN_BUFFER_SECONDS = 60L
        private const val DASHBOARD_CACHE_TTL_MILLIS = 15_000L
        private const val TRADE_HISTORY_CACHE_TTL_MILLIS = 30_000L
        private const val EXCHANGE_QUERY_CONCURRENCY = 4
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JSON_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val ISO_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val tokenMutex = Mutex()
    private val dashboardCacheMutex = Mutex()
    private val dashboardLoadMutex = Mutex()
    private val quoteRefreshMutex = Mutex()
    private val tradeHistoryCacheMutex = Mutex()
    private val tradeHistoryLoadMutex = Mutex()
    private val centralOrderClient by lazy { CentralOrderClient(client) }
    private var authToken: AuthToken? = null
    private var cachedBaseDashboard: Pair<Long, DashboardResponse>? = null
    private var cachedDashboard: Pair<Long, DashboardResponse>? = null
    private var cachedTradeHistory: MutableMap<String, Pair<Long, TradeHistoryResponse>> = mutableMapOf()
    private var lastKnownUsdRate: Double = 1350.0
    private val usQuoteService = KisUsQuoteService(credentials, client)

    fun peekDashboard(): DashboardResponse? = cachedDashboard?.second
    fun peekTradeHistory(range: String = "this_month"): TradeHistoryResponse? {
        val normalizedRange = range.lowercase(Locale.US)
        val cached = cachedTradeHistory[normalizedRange] ?: return null
        return cached.second.takeIf { System.currentTimeMillis() - cached.first <= TRADE_HISTORY_CACHE_TTL_MILLIS }
    }

    suspend fun fetchDashboard(forceRefresh: Boolean = false): DashboardResponse = withContext(Dispatchers.IO) {
        dashboardLoadMutex.withLock {
            if (!forceRefresh) {
                val cachedBase = dashboardCacheMutex.withLock {
                    cachedBaseDashboard?.takeIf { System.currentTimeMillis() - it.first <= DASHBOARD_CACHE_TTL_MILLIS }?.second
                }
                if (cachedBase != null) {
                    val refreshed = refreshDashboardFromBase(cachedBase, forceRetry = false)
                    dashboardCacheMutex.withLock {
                        cachedDashboard = System.currentTimeMillis() to refreshed
                    }
                    return@withLock refreshed
                }
            }

            val domestic = getDomesticBalance()
            val overseas = getOverseasBalance()
            val baseDashboard = buildDashboard(domestic, overseas)
            val refreshed = refreshDashboardFromBase(baseDashboard, forceRetry = forceRefresh)
            val cachedAt = System.currentTimeMillis()
            dashboardCacheMutex.withLock {
                cachedBaseDashboard = cachedAt to baseDashboard
                cachedDashboard = cachedAt to refreshed
            }
            refreshed
        }
    }

    suspend fun refreshDashboardQuotes(): DashboardResponse? = withContext(Dispatchers.IO) {
        quoteRefreshMutex.withLock {
            val baseDashboard = dashboardCacheMutex.withLock { cachedBaseDashboard?.second } ?: return@withLock null
            val refreshed = refreshDashboardFromBase(baseDashboard, forceRetry = false)
            dashboardCacheMutex.withLock {
                cachedDashboard = System.currentTimeMillis() to refreshed
            }
            refreshed
        }
    }

    fun close() {
        usQuoteService.close()
    }

    suspend fun submitScheduledDomesticOrder(
        request: ScheduledDomesticOrderRequest,
    ): ScheduledOrderSummary = withContext(Dispatchers.IO) {
        centralOrderClient.submitScheduledDomesticOrder(credentials, request)
    }

    suspend fun fetchTradeHistory(
        range: String = "this_month",
        forceRefresh: Boolean = false,
        onSummaryReady: (suspend (TradeHistoryResponse) -> Unit)? = null,
    ): TradeHistoryResponse = withContext(Dispatchers.IO) {
        tradeHistoryLoadMutex.withLock {
            val normalizedRange = range.lowercase(Locale.US)
            if (!forceRefresh) {
                tradeHistoryCacheMutex.withLock {
                    cachedTradeHistory[normalizedRange]?.takeIf { System.currentTimeMillis() - it.first <= TRADE_HISTORY_CACHE_TTL_MILLIS }?.let {
                        return@withContext it.second
                    }
                }
            }
            val resolved = resolveTradeRange(normalizedRange)
            val startDate = resolved.first.format(JSON_FORMAT)
            val endDate = resolved.second.format(JSON_FORMAT)

            val (domesticTradeProfit, overseasTradeProfit, domesticTrades, overseasTrades) = coroutineScope {
                val domesticProfitDeferred = async { getDomesticRealizedTradeProfit(startDate, endDate) }
                val overseasProfitDeferred = async { getOverseasRealizedTradeProfit(startDate, endDate) }
                val domesticTradesDeferred = async { getDomesticTradeHistory(startDate, endDate) }
                val overseasTradesDeferred = async {
                    var overseas = getOverseasTradeHistory(startDate, endDate)
                    if (!hasJapanTradeRows(overseas)) {
                        overseas = dedupeTradeRows(overseas + getJapanTradeHistoryCcnl(startDate, endDate))
                    }
                    overseas
                }
                val domesticProfit = domesticProfitDeferred.await()
                val overseasProfit = overseasProfitDeferred.await()
                val summaryOnly = buildTradeHistoryResponse(
                    resolved = resolved,
                    domesticTradeProfit = domesticProfit,
                    overseasTradeProfit = overseasProfit,
                    trades = emptyList(),
                )
                onSummaryReady?.let { callback ->
                    withContext(Dispatchers.Main) {
                        callback(summaryOnly)
                    }
                }

                Quadruple(
                    domesticProfit,
                    overseasProfit,
                    domesticTradesDeferred.await(),
                    overseasTradesDeferred.await(),
                )
            }

            val allTrades = dedupeTradeRows(domesticTrades + overseasTrades)
            attachRealizedProfitToSellTrades(allTrades, domesticTradeProfit, overseasTradeProfit)
            val sortedTrades = sortTradeRowsNewestFirst(allTrades)

            val built = buildTradeHistoryResponse(
                resolved = resolved,
                domesticTradeProfit = domesticTradeProfit,
                overseasTradeProfit = overseasTradeProfit,
                trades = sortedTrades,
            )

            tradeHistoryCacheMutex.withLock {
                cachedTradeHistory[normalizedRange] = System.currentTimeMillis() to built
            }
            built
        }
    }

    private fun buildTradeHistoryResponse(
        resolved: Triple<LocalDate, LocalDate, String>,
        domesticTradeProfit: List<RealizedTradeProfitRow>,
        overseasTradeProfit: List<RealizedTradeProfitRow>,
        trades: List<TradeRow>,
    ): TradeHistoryResponse {
        val domesticProfit = domesticTradeProfit.sumOf { it.realizedProfitKrw }
        val overseasProfit = overseasTradeProfit.sumOf { it.realizedProfitKrw }
        val totalBuyAmount = domesticTradeProfit.sumOf { it.buyAmountKrw } + overseasTradeProfit.sumOf { it.buyAmountKrw }
        val totalReturnRate = if (totalBuyAmount > 0.0) {
            (domesticTradeProfit.sumOf { it.realizedProfitKrw } + overseasTradeProfit.sumOf { it.realizedProfitKrw }) / totalBuyAmount * 100.0
        } else {
            0.0
        }

        return TradeHistoryResponse(
            period = TradePeriod(
                start = resolved.first.toString(),
                end = resolved.second.toString(),
                label = resolved.third,
            ),
            summary = TradeSummary(
                totalRealizedProfitKrw = domesticProfit + overseasProfit,
                domesticRealizedProfitKrw = domesticProfit,
                overseasRealizedProfitKrw = overseasProfit,
                totalRealizedReturnRate = totalReturnRate,
            ),
            trades = trades.map { trade ->
                Trade(
                    date = trade.date,
                    side = trade.side,
                    ticker = trade.symbol,
                    name = trade.name,
                    market = trade.market,
                    currency = trade.currency,
                    quantity = trade.quantity,
                    unitPrice = trade.unitPrice,
                    amountNative = trade.amountNative,
                    amountKrw = trade.amountKrw,
                    realizedProfitKrw = trade.realizedProfitKrw,
                    returnRate = trade.realizedReturnRate,
                )
            },
            lastSynced = OffsetDateTime.now(ZoneOffset.ofHours(9)).format(ISO_FORMAT),
            usdExchangeRate = lastKnownUsdRate,
        )
    }

    private suspend fun getDomesticBalance(): DomesticBalancePayload {
        val token = requireToken() ?: throw IllegalStateException("KIS_TOKEN_FAILURE[dashboard:domestic]")
        val orderableCash = getDomesticOrderableCash(token)
        val response = getJson(
            path = "/uapi/domestic-stock/v1/trading/inquire-balance",
            trId = "TTTC8434R",
            query = linkedMapOf(
                "CANO" to credentials.cano,
                "ACNT_PRDT_CD" to credentials.acntPrdtCd,
                "AFHR_FLPR_YN" to "N",
                "OFL_YN" to "",
                "INQR_DVSN" to "01",
                "UNPR_DVSN" to "01",
                "FUND_STTL_ICLD_YN" to "N",
                "FNCG_AMT_AUTO_RDPT_YN" to "N",
                "PRCS_DVSN" to "00",
                "CTX_AREA_FK100" to "",
                "CTX_AREA_NK100" to "",
            ),
            token = token,
        ) ?: return DomesticBalancePayload()

        val summaryRows = jsonArray(response, "output2")
        val summary = summaryRows.firstOrNull()?.asJsonObject
        val holdings = jsonArray(response, "output1").mapNotNull { element ->
            val item = element.asJsonObject
            val qty = number(item, "hldg_qty")
            if (qty <= 0.0) {
                return@mapNotNull null
            }
            val avgPrice = number(item, "pchs_avg_pric")
            val nowPrice = number(item, "prpr")
            DomesticHoldingRaw(
                symbol = string(item, "pdno"),
                name = string(item, "prdt_name").ifBlank { string(item, "pdno") },
                quantity = qty,
                averageCost = avgPrice,
                currentPrice = nowPrice,
            )
        }

        return DomesticBalancePayload(
            totalPurchaseKrw = summary?.let { number(it, "pchs_amt_smtl_amt") } ?: 0.0,
            totalEvalKrw = summary?.let { number(it, "evlu_amt_smtl_amt") } ?: holdings.sumOf { it.quantity * it.currentPrice },
            totalProfitKrw = summary?.let { number(it, "evlu_pfls_smtl_amt") } ?: 0.0,
            cashKrw = orderableCash,
            holdings = holdings,
        )
    }

    private suspend fun getDomesticOrderableCash(token: String): Double {
        val response = getJson(
            path = "/uapi/domestic-stock/v1/trading/inquire-psbl-order",
            trId = "TTTC8908R",
            query = linkedMapOf(
                "CANO" to credentials.cano,
                "ACNT_PRDT_CD" to credentials.acntPrdtCd,
                "PDNO" to "005930",
                "ORD_UNPR" to "1",
                "ORD_DVSN" to "01",
                "CMA_EVLU_AMT_ICLD_YN" to "N",
                "OVRS_ICLD_YN" to "N",
            ),
            token = token,
        ) ?: return 0.0
        val output = jsonObject(response, "output") ?: return 0.0
        val keys = listOf("nrcvb_buy_amt", "ord_psbl_cash", "ord_psbl_amt", "max_buy_amt")
        val selected = keys.firstNotNullOfOrNull { key ->
            val value = number(output, key)
            value.takeIf { it > 0.0 }
        } ?: 0.0
        Log.d("KisRepository", "domestic_cash selected=$selected keys=$keys")
        return selected
    }

    private suspend fun getOverseasBalance(): OverseasBalancePayload = coroutineScope {
        val token = requireToken() ?: throw IllegalStateException("KIS_TOKEN_FAILURE[dashboard:overseas]")
        val usDeferred = async { getSingleOverseasBalance(token, "840", "USD", "NASD") }
        val jpDeferred = async { getSingleOverseasBalance(token, "392", "JPY", "TKSE") }
        val us = usDeferred.await()
        val jp = jpDeferred.await()
        OverseasBalancePayload(
            usdCashBalance = us.cashBalance,
            usdExchangeRate = us.exchangeRate,
            usHoldings = us.holdings,
            jpyCashBalance = jp.cashBalance,
            jpyExchangeRate = jp.exchangeRate,
            jpHoldings = jp.holdings,
        )
    }

    private suspend fun getSingleOverseasBalance(
        token: String,
        nationCode: String,
        currencyCode: String,
        fallbackExchange: String,
    ): SingleOverseasBalancePayload {
        val response = getJson(
            path = "/uapi/overseas-stock/v1/trading/inquire-present-balance",
            trId = "CTRP6504R",
            query = linkedMapOf(
                "CANO" to credentials.cano,
                "ACNT_PRDT_CD" to credentials.acntPrdtCd,
                "WCRC_FRCR_DVSN_CD" to "02",
                "NATN_CD" to nationCode,
                "TR_MKET_CD" to "00",
                "INQR_DVSN_CD" to "00",
                "CTX_AREA_FK200" to "",
                "CTX_AREA_NK200" to "",
            ),
            token = token,
        ) ?: return SingleOverseasBalancePayload()

        val output1 = normalizeRows(response.get("output1"))
        val output2 = normalizeRows(response.get("output2"))
        val output3 = jsonObject(response, "output3")
        val holdings = output1.mapNotNull { row ->
            val symbol = string(row, "pdno").uppercase()
            if (symbol == currencyCode) {
                return@mapNotNull null
            }
            val quantity = number(row, "ccld_qty_smtl1")
            if (quantity <= 0.0) {
                return@mapNotNull null
            }
            OverseasHoldingRaw(
                symbol = string(row, "pdno"),
                name = string(row, "prdt_name").ifBlank { string(row, "pdno") },
                exchangeCode = string(row, "ovrs_excg_cd").ifBlank { fallbackExchange },
                quantity = quantity,
                averageCost = number(row, "avg_unpr3"),
                currentPrice = number(row, "ovrs_now_pric1"),
                exchangeRate = number(row, "bass_exrt").takeIf { it > 0.0 } ?: 1.0,
                currency = currencyCode,
            )
        }

        val exchangeRate = when (currencyCode) {
            "USD" -> resolveUsdExchangeRate(output1, output2)
            "JPY" -> resolveJpyExchangeRate(output1, output2)
            else -> output1.firstNotNullOfOrNull { row ->
                number(row, "bass_exrt").takeIf { it > 0.0 }
            } ?: output2.firstNotNullOfOrNull { row ->
                number(row, "bass_exrt").takeIf { it > 0.0 }
            } ?: 0.0
        }

        val cash = when (currencyCode) {
            "USD" -> {
                getOverseasOrderableCash(token, "USD").takeIf { it > 0.0 }
                    ?: pickForeignBalanceFromHoldings(output1, "USD").takeIf { it > 0.0 }
                    ?: pickForeignBalanceFromOutput2(output2, "USD").takeIf { it > 0.0 }
                    ?: output3?.let { pickFirstPositive(it, listOf("frcr_dncl_amt_2", "tot_frcr_cblc_smtl", "frcr_use_psbl_amt", "frcr_drwg_psbl_amt_1", "ord_psbl_frcr_amt")) }
                    ?: 0.0
            }
            "JPY" -> {
                getOverseasOrderableCash(token, currencyCode).takeIf { it > 0.0 }
                    ?: pickForeignBalanceFromHoldings(output1, currencyCode).takeIf { it > 0.0 }
                    ?: pickForeignBalanceFromOutput2(output2, currencyCode).takeIf { it > 0.0 }
                    ?: output3?.let { pickFirstPositive(it, listOf("frcr_dncl_amt_2", "tot_frcr_cblc_smtl", "frcr_use_psbl_amt", "ord_psbl_frcr_amt", "frcr_drwg_psbl_amt_1")) }
                    ?: 0.0
            }
            else -> 0.0
        }

        Log.d(
            "KisRepository",
            "foreign_cash currency=$currencyCode cash=$cash exchangeRate=$exchangeRate holdings=${holdings.size}",
        )

        return SingleOverseasBalancePayload(cashBalance = cash, exchangeRate = exchangeRate, holdings = holdings)
    }

    private suspend fun getOverseasOrderableCash(token: String, currencyCode: String): Double {
        val tries = when (currencyCode.uppercase()) {
            "USD" -> listOf("NASD" to "QQQ", "NYSE" to "KO")
            "JPY" -> listOf("TKSE" to "7203")
            else -> emptyList()
        }
        for ((exchange, item) in tries) {
            val response = getJson(
                path = "/uapi/overseas-stock/v1/trading/inquire-psamount",
                trId = "TTTS3007R",
                query = linkedMapOf(
                    "CANO" to credentials.cano,
                    "ACNT_PRDT_CD" to credentials.acntPrdtCd,
                    "OVRS_EXCG_CD" to exchange,
                    "OVRS_ORD_UNPR" to "1",
                    "ITEM_CD" to item,
                ),
                token = token,
            ) ?: continue
            val output = jsonObject(response, "output") ?: continue
            val value = pickFirstPositive(output, listOf("ovrs_ord_psbl_amt", "ord_psbl_frcr_amt"))
            if (value > 0.0) {
                return value
            }
        }
        return 0.0
    }

    private suspend fun getDomesticTradeHistory(startDate: String, endDate: String): List<TradeRow> {
        val token = requireToken() ?: throw IllegalStateException("KIS_TOKEN_FAILURE[trade-history:domestic]")
        val start = LocalDate.parse(startDate, JSON_FORMAT)
        val today = OffsetDateTime.now(ZoneOffset.ofHours(9)).toLocalDate()
        val trId = if (today.toEpochDay() - start.toEpochDay() > 92) "CTSC9215R" else "TTTC0081R"
        val pages = paginatedJson(
            path = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld",
            trId = trId,
            query = linkedMapOf(
                "CANO" to credentials.cano,
                "ACNT_PRDT_CD" to credentials.acntPrdtCd,
                "INQR_STRT_DT" to startDate,
                "INQR_END_DT" to endDate,
                "SLL_BUY_DVSN_CD" to "00",
                "PDNO" to "",
                "CCLD_DVSN" to "01",
                "INQR_DVSN" to "00",
                "INQR_DVSN_3" to "00",
                "ORD_GNO_BRNO" to "",
                "ODNO" to "",
                "INQR_DVSN_1" to "",
                "CTX_AREA_FK100" to "",
                "CTX_AREA_NK100" to "",
                "EXCG_ID_DVSN_CD" to "ALL",
            ),
            token = token,
            fkField = "ctx_area_fk100",
            nkField = "ctx_area_nk100",
        )
        return pages.flatMap { page ->
            jsonArray(page, "output1").map { element ->
                val row = element.asJsonObject
                val quantity = firstPositiveNumber(row, listOf("tot_ccld_qty", "ord_qty"))
                val rawAmount = firstPositiveNumber(row, listOf("tot_ccld_amt", "ccld_amt", "ord_amt"))
                val fallbackUnitPrice = firstPositiveNumber(row, listOf("avg_prvs", "ord_unpr"))
                val unitPrice = if (quantity > 0.0 && rawAmount > 0.0) rawAmount / quantity else fallbackUnitPrice
                val amount = resolvedTradeAmount(rawAmount, quantity, unitPrice)
                TradeRow(
                    date = string(row, "ord_dt"),
                    market = "KOR",
                    symbol = string(row, "pdno"),
                    name = string(row, "prdt_name").ifBlank { string(row, "pdno") },
                    side = normalizeSide(string(row, "sll_buy_dvsn_cd"), string(row, "sll_buy_dvsn_cd_name")),
                    currency = "KRW",
                    quantity = quantity,
                    unitPrice = unitPrice,
                    amountNative = amount,
                    amountKrw = amount,
                    time = string(row, "ord_tmd"),
                )
            }
        }.filter { it.date.isNotBlank() && it.symbol.isNotBlank() && it.quantity > 0.0 }
    }

    private suspend fun getOverseasTradeHistory(startDate: String, endDate: String): List<TradeRow> {
        val token = requireToken() ?: throw IllegalStateException("KIS_TOKEN_FAILURE[trade-history:overseas]")
        val exchanges = listOf("NAS", "NYS", "AMS", "TSE", "TKSE", "HKS", "SHS", "SZS", "HSX", "HNX")
        return fetchExchangeRows(exchanges) { exchange ->
                val pages = paginatedJson(
                    path = "/uapi/overseas-stock/v1/trading/inquire-period-trans",
                    trId = "CTOS4001R",
                    query = linkedMapOf(
                        "CANO" to credentials.cano,
                        "ACNT_PRDT_CD" to credentials.acntPrdtCd,
                        "ERLM_STRT_DT" to startDate,
                        "ERLM_END_DT" to endDate,
                        "OVRS_EXCG_CD" to exchange,
                        "PDNO" to "",
                        "SLL_BUY_DVSN_CD" to "00",
                        "LOAN_DVSN_CD" to "",
                        "CTX_AREA_FK100" to "",
                        "CTX_AREA_NK100" to "",
                    ),
                    token = token,
                    fkField = "ctx_area_fk100",
                    nkField = "ctx_area_nk100",
                )
            pages.flatMap { page -> normalizeOverseasTradeRows(jsonArray(page, "output1"), exchange) }
        }
    }

    private suspend fun getJapanTradeHistoryCcnl(startDate: String, endDate: String): List<TradeRow> {
        val token = requireToken() ?: throw IllegalStateException("KIS_TOKEN_FAILURE[trade-history:japan-ccnl]")
        val exchanges = listOf("TKSE", "TSE")
        return fetchExchangeRows(exchanges) { exchange ->
            val pages = paginatedJson(
                path = "/uapi/overseas-stock/v1/trading/inquire-ccnl",
                trId = "TTTS3035R",
                query = linkedMapOf(
                    "CANO" to credentials.cano,
                    "ACNT_PRDT_CD" to credentials.acntPrdtCd,
                    "PDNO" to "%",
                    "ORD_STRT_DT" to startDate,
                    "ORD_END_DT" to endDate,
                    "SLL_BUY_DVSN" to "00",
                    "CCLD_NCCS_DVSN" to "01",
                    "OVRS_EXCG_CD" to exchange,
                    "SORT_SQN" to "DS",
                    "ORD_DT" to "",
                    "ORD_GNO_BRNO" to "",
                    "ODNO" to "",
                    "CTX_AREA_FK200" to "",
                    "CTX_AREA_NK200" to "",
                ),
                token = token,
                fkField = "ctx_area_fk200",
                nkField = "ctx_area_nk200",
            )
            pages.flatMap { page -> normalizeOverseasTradeRows(jsonArray(page, "output1"), exchange) }
        }
    }

    private suspend fun getDomesticRealizedTradeProfit(startDate: String, endDate: String): List<RealizedTradeProfitRow> {
        val token = requireToken() ?: throw IllegalStateException("KIS_TOKEN_FAILURE[realized-profit:domestic]")
        val pages = paginatedJson(
            path = "/uapi/domestic-stock/v1/trading/inquire-period-trade-profit",
            trId = "TTTC8715R",
            query = linkedMapOf(
                "CANO" to credentials.cano,
                "ACNT_PRDT_CD" to credentials.acntPrdtCd,
                "SORT_DVSN" to "01",
                "INQR_STRT_DT" to startDate,
                "INQR_END_DT" to endDate,
                "CBLC_DVSN" to "00",
                "PDNO" to "",
                "CTX_AREA_FK100" to "",
                "CTX_AREA_NK100" to "",
            ),
            token = token,
            fkField = "ctx_area_fk100",
            nkField = "ctx_area_nk100",
        )
        return dedupeRealizedRows(pages.flatMap { page ->
            jsonArray(page, "output1").mapNotNull { element ->
                val row = element.asJsonObject
                val quantity = number(row, "sll_qty")
                val amount = number(row, "sll_amt")
                if (quantity <= 0.0 || amount <= 0.0) {
                    return@mapNotNull null
                }
                val realizedProfit = number(row, "rlzt_pfls")
                val buyAmount = number(row, "buy_amt").takeIf { it > 0.0 }
                    ?: max(amount - realizedProfit - number(row, "fee") - number(row, "tl_tax"), 0.0)
                RealizedTradeProfitRow(
                    date = string(row, "trad_dt"),
                    symbol = string(row, "pdno"),
                    quantity = quantity,
                    amount = amount,
                    realizedProfitKrw = realizedProfit,
                    buyAmountKrw = buyAmount,
                    realizedReturnRate = null,
                )
            }
        })
    }

    private suspend fun getOverseasRealizedTradeProfit(startDate: String, endDate: String): List<RealizedTradeProfitRow> {
        val token = requireToken() ?: throw IllegalStateException("KIS_TOKEN_FAILURE[realized-profit:overseas]")
        val exchanges = listOf(
            "NASD" to "USD",
            "NYSE" to "USD",
            "AMEX" to "USD",
            "TKSE" to "JPY",
            "SEHK" to "HKD",
            "SHAA" to "CNY",
            "HASE" to "VND",
        )
        return dedupeRealizedRows(fetchExchangeRows(exchanges) { (exchange, currency) ->
                val pages = paginatedJson(
                    path = "/uapi/overseas-stock/v1/trading/inquire-period-profit",
                    trId = "TTTS3039R",
                    query = linkedMapOf(
                        "CANO" to credentials.cano,
                        "ACNT_PRDT_CD" to credentials.acntPrdtCd,
                        "OVRS_EXCG_CD" to exchange,
                        "NATN_CD" to getOverseasNationCode(exchange),
                        "CRCY_CD" to currency,
                        "PDNO" to "",
                        "INQR_STRT_DT" to startDate,
                        "INQR_END_DT" to endDate,
                        "WCRC_FRCR_DVSN_CD" to "02",
                        "CTX_AREA_FK200" to "",
                        "CTX_AREA_NK200" to "",
                    ),
                    token = token,
                    fkField = "ctx_area_fk200",
                    nkField = "ctx_area_nk200",
                )
            pages.flatMap { page ->
                    jsonArray(page, "output1").mapNotNull { element ->
                        val row = element.asJsonObject
                        val tradeDate = string(row, "trad_day")
                        val symbol = string(row, "ovrs_pdno").ifBlank { string(row, "pdno") }
                        val quantity = firstPositiveNumber(row, listOf("slcl_qty", "ccld_qty"))
                        val amount = firstPositiveNumber(row, listOf("frcr_sll_amt_smtl1", "stck_sll_amt_smtl", "frcr_sll_amt_smtl"))
                        if (tradeDate.isBlank() || symbol.isBlank() || quantity <= 0.0 || amount <= 0.0) {
                            return@mapNotNull null
                        }
                        val realizedProfit = number(row, "ovrs_rlzt_pfls_amt")
                        val fee = firstPositiveNumber(row, listOf("stck_sll_tlex", "smtl_fee1"))
                        val buyAmount = number(row, "stck_buy_amt_smtl").takeIf { it > 0.0 }
                            ?: max(amount - realizedProfit - fee, 0.0)
                        RealizedTradeProfitRow(
                            date = tradeDate,
                            symbol = symbol,
                            quantity = quantity,
                            amount = amount,
                            realizedProfitKrw = realizedProfit,
                            buyAmountKrw = buyAmount,
                            realizedReturnRate = string(row, "pftrt").takeIf { it.isNotBlank() }?.toDoubleOrNull(),
                        )
                    }
                }
        })
    }

    private suspend fun <T, R> fetchExchangeRows(
        exchanges: List<T>,
        fetch: suspend (T) -> List<R>,
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(EXCHANGE_QUERY_CONCURRENCY)
        exchanges.map { exchange ->
            async {
                semaphore.withPermit {
                    fetch(exchange)
                }
            }
        }.awaitAll().flatten()
    }

    private fun buildDashboard(domestic: DomesticBalancePayload, overseas: OverseasBalancePayload): DashboardResponse {
        val allHoldings = buildList {
            addAll(domestic.holdings.map { holding ->
                Holding(
                    symbol = holding.symbol,
                    name = holding.name,
                    market = "KOR",
                    quantity = holding.quantity,
                    currentPrice = holding.currentPrice,
                    averageCost = holding.averageCost,
                    totalValueKrw = holding.quantity * holding.currentPrice,
                    totalCostKrw = holding.quantity * holding.averageCost,
                    profitLossKrw = holding.quantity * (holding.currentPrice - holding.averageCost),
                    profitLossRate = if (holding.averageCost > 0.0) ((holding.currentPrice - holding.averageCost) / holding.averageCost) * 100.0 else 0.0,
                    currency = "KRW",
                    exchangeRate = 1.0,
                )
            })
            addAll(overseas.usHoldings.map { holding -> overseasHoldingToUi(holding, "USD") })
            addAll(overseas.jpHoldings.map { holding -> overseasHoldingToUi(holding, "JPY") })
        }.sortedByDescending { it.totalValueKrw }

        val usdRate = overseas.usdExchangeRate.takeIf { it > 0.0 } ?: 1350.0
        val jpyRate = overseas.jpyExchangeRate.takeIf { it > 0.0 } ?: 900.0
        lastKnownUsdRate = usdRate
        val totalCashKrw = domestic.cashKrw + (overseas.usdCashBalance * usdRate) + (overseas.jpyCashBalance * jpyRate / 100.0)
        val totalAssets = allHoldings.sumOf { it.totalValueKrw } + totalCashKrw
        val totalPurchase = allHoldings.sumOf { it.totalCostKrw }
        val totalProfit = allHoldings.sumOf { it.profitLossKrw }

        Log.d(
            "KisRepository",
            "cash_rollup krw=${domestic.cashKrw} usd=${overseas.usdCashBalance} usdRate=$usdRate jpy=${overseas.jpyCashBalance} jpyRate=$jpyRate totalCashKrw=$totalCashKrw holdingsEval=${allHoldings.sumOf { it.totalValueKrw }} totalAssets=$totalAssets",
        )

        return DashboardResponse(
            summary = DashboardSummary(
                totalAssetsKrw = totalAssets,
                totalPurchaseKrw = totalPurchase,
                totalProfitKrw = totalProfit,
                totalProfitRate = if (totalPurchase > 0.0) totalProfit / totalPurchase * 100.0 else 0.0,
                cashKrw = domestic.cashKrw,
                totalCashKrw = totalCashKrw,
                cashUsd = overseas.usdCashBalance,
                cashJpy = overseas.jpyCashBalance,
                usdExchangeRate = usdRate,
                domesticCount = domestic.holdings.size,
                overseasCount = overseas.usHoldings.size + overseas.jpHoldings.size,
                lastSynced = OffsetDateTime.now(ZoneOffset.ofHours(9)).format(ISO_FORMAT),
            ),
            holdings = allHoldings,
            assetDistribution = buildAssetDistribution(allHoldings),
            usMarketStatus = UsMarketStatus(),
        )
    }

    private fun overseasHoldingToUi(holding: OverseasHoldingRaw, currency: String): Holding {
        val rate = if (currency == "JPY") max(holding.exchangeRate, 0.0) / 100.0 else max(holding.exchangeRate, 0.0)
        val currentValue = holding.quantity * holding.currentPrice * rate
        val costValue = holding.quantity * holding.averageCost * rate
        val profitLoss = currentValue - costValue
        return Holding(
            symbol = holding.symbol,
            name = holding.name,
            market = marketBadgeCode(holding.exchangeCode),
            quantity = holding.quantity,
            currentPrice = holding.currentPrice,
            averageCost = holding.averageCost,
            totalValueKrw = currentValue,
            totalCostKrw = costValue,
            profitLossKrw = profitLoss,
            profitLossRate = if (costValue > 0.0) profitLoss / costValue * 100.0 else 0.0,
            currency = currency,
            exchangeRate = holding.exchangeRate,
            exchangeCode = holding.exchangeCode,
        )
    }

    private suspend fun refreshDashboardFromBase(
        baseDashboard: DashboardResponse,
        forceRetry: Boolean,
    ): DashboardResponse {
        val usHoldings = baseDashboard.holdings.filter { it.market == "USA" }
        usQuoteService.syncHoldings(usHoldings, forceRetry = forceRetry)

        val enrichedUsHoldings = usQuoteService.enrichHoldings(usHoldings).associateBy { it.symbol }
        val mergedHoldings = baseDashboard.holdings.map { holding ->
            if (holding.market == "USA") enrichedUsHoldings[holding.symbol] ?: holding else holding
        }.sortedByDescending { it.totalValueKrw }

        val totalCashKrw = baseDashboard.summary.totalCashKrw
        val totalAssets = mergedHoldings.sumOf { it.totalValueKrw } + totalCashKrw
        val totalPurchase = mergedHoldings.sumOf { it.totalCostKrw }
        val totalProfit = mergedHoldings.sumOf { it.profitLossKrw }

        return baseDashboard.copy(
            summary = baseDashboard.summary.copy(
                totalAssetsKrw = totalAssets,
                totalPurchaseKrw = totalPurchase,
                totalProfitKrw = totalProfit,
                totalProfitRate = if (totalPurchase > 0.0) totalProfit / totalPurchase * 100.0 else 0.0,
                lastSynced = OffsetDateTime.now(ZoneOffset.ofHours(9)).format(ISO_FORMAT),
            ),
            holdings = mergedHoldings,
            assetDistribution = buildAssetDistribution(mergedHoldings),
            usMarketStatus = usQuoteService.getMarketStatus(mergedHoldings.filter { it.market == "USA" }),
        )
    }

    private fun buildAssetDistribution(holdings: List<Holding>): List<AssetDistribution> {
        val total = holdings.sumOf { it.totalValueKrw }
        if (total <= 0.0) return emptyList()
        return holdings.map { holding ->
            AssetDistribution(
                symbol = holding.symbol,
                name = holding.name,
                weightPercent = holding.totalValueKrw / total * 100.0,
                valueKrw = holding.totalValueKrw,
            )
        }
    }

    private fun marketBadgeCode(code: String): String = when (code.uppercase()) {
        "KOR", "KRX", "J", "UN", "NX" -> "KOR"
        "USA", "NAS", "NASD", "NYS", "NYSE", "AMS", "AMEX" -> "USA"
        "JPN", "TSE", "TKSE", "JPX", "TYO" -> "JPN"
        else -> code
    }

    private suspend fun requireToken(): String? = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        authToken?.takeIf { now < it.expiresAtMillis - TOKEN_BUFFER_SECONDS * 1000 }?.let { return it.value }
        settingsManager.loadAuthToken(credentials)
            ?.takeIf { now < it.expiresAtMillis - TOKEN_BUFFER_SECONDS * 1000 }
            ?.let {
                authToken = it
                return it.value
            }

        val requestBody = JsonObject().apply {
            addProperty("grant_type", "client_credentials")
            addProperty("appkey", credentials.appKey)
            addProperty("appsecret", credentials.appSecret)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$BASE_URL/oauth2/tokenP")
            .post(requestBody)
            .header("content-type", "application/json")
            .build()

        client.newCall(request).execute().use { response: okhttp3.Response ->
            if (!response.isSuccessful) {
                authToken = null
                return null
            }
            val bodyString = response.body?.string().orEmpty()
            val json = parseObject(bodyString) ?: return null
            val accessToken = string(json, "access_token")
            if (accessToken.isBlank()) return null
            val expiresIn = number(json, "expires_in").takeIf { it > 0.0 }?.toLong() ?: 43200L
            authToken = AuthToken(accessToken, now, now + expiresIn * 1000)
            authToken?.let { settingsManager.saveAuthToken(credentials, it) }
            return accessToken
        }
    }

    private suspend fun getJson(
        path: String,
        trId: String,
        query: Map<String, String>,
        token: String,
        retryOnTokenError: Boolean = true,
        retryOnRateLimit: Int = 2,
    ): JsonObject? {
        val urlBuilder = "$BASE_URL$path".toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalStateException("KIS_INVALID_URL[$trId] path=$path")
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val startedAt = SystemClock.elapsedRealtime()

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .header("content-type", "application/json; charset=utf-8")
            .header("authorization", "Bearer $token")
            .header("appkey", credentials.appKey)
            .header("appsecret", credentials.appSecret)
            .header("tr_id", trId)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            val json = parseObject(bodyString) ?: JsonObject()
            if (retryOnRateLimit > 0 && isRateLimitError(response.code, json)) {
                delay((3 - retryOnRateLimit) * 400L + 400L)
                return getJson(path, trId, query, token, retryOnTokenError, retryOnRateLimit - 1)
            }
            if (retryOnTokenError && isTokenError(response.code, json)) {
                val currentToken = authToken?.value
                val refreshed = if (!currentToken.isNullOrBlank() && currentToken != token) {
                    currentToken
                } else {
                    authToken = null
                    settingsManager.clearAuthToken()
                    requireToken()
                } ?: throw IllegalStateException("KIS_TOKEN_REFRESH_FAILED[$trId] path=$path")
                return getJson(path, trId, query, refreshed, false, retryOnRateLimit)
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "KIS_HTTP_ERROR[$trId] path=$path code=${response.code} rt_cd=${string(json, "rt_cd")} msg_cd=${string(json, "msg_cd")} msg=${string(json, "msg1")} duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
                )
            }
            if (string(json, "rt_cd").takeIf { it.isNotBlank() } !in listOf("", "0")) {
                throw IllegalStateException(
                    "KIS_API_ERROR[$trId] path=$path rt_cd=${string(json, "rt_cd")} msg_cd=${string(json, "msg_cd")} msg=${string(json, "msg1")} duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
                )
            }
            json.addProperty("__tr_cont", response.header("tr_cont") ?: "")
            return json
        }
    }

    private suspend fun paginatedJson(
        path: String,
        trId: String,
        query: LinkedHashMap<String, String>,
        token: String,
        fkField: String,
        nkField: String,
        maxPages: Int = 10,
    ): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        var currentQuery = LinkedHashMap(query)
        repeat(maxPages) {
            val page = getJson(path, trId, currentQuery, token) ?: return results
            results += page
            val trCont = string(page, "__tr_cont")
            if (trCont !in setOf("F", "M")) {
                return results
            }
            val nextFk = string(page, fkField)
            val nextNk = string(page, nkField)
            if (nextFk.isBlank() && nextNk.isBlank()) {
                return results
            }
            currentQuery[fkField.uppercase()] = nextFk
            currentQuery[nkField.uppercase()] = nextNk
        }
        return results
    }

    private fun normalizeOverseasTradeRows(rows: List<JsonElement>, fallbackMarket: String): List<TradeRow> =
        rows.mapNotNull { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val date = string(row, "trad_dt")
            val symbol = string(row, "pdno")
            val quantity = firstPositiveNumber(row, listOf("ccld_qty", "tot_ccld_qty", "ord_qty"))
            val rawAmount = firstPositiveNumber(
                row,
                listOf(
                    "tr_frcr_amt2",
                    "tr_amt",
                    "frcr_sll_amt_smtl",
                    "frcr_sll_amt_smtl1",
                    "stck_sll_amt_smtl",
                    "frcr_buy_amt_smtl",
                ),
            )
            val fallbackUnitPrice = firstPositiveNumber(row, listOf("ovrs_stck_ccld_unpr", "ft_ccld_unpr2"))
            val unitPrice = if (quantity > 0.0 && rawAmount > 0.0) rawAmount / quantity else fallbackUnitPrice
            val amount = resolvedTradeAmount(rawAmount, quantity, unitPrice)
            if (date.isBlank() || symbol.isBlank() || quantity <= 0.0 || amount <= 0.0) {
                return@mapNotNull null
            }
            val currency = string(row, "crcy_cd").ifBlank {
                when (fallbackMarket.uppercase()) {
                    "NAS", "NYS", "AMS", "NASD", "NYSE", "AMEX" -> "USD"
                    "TSE", "TKSE", "JPX", "TYO" -> "JPY"
                    else -> "USD"
                }
            }
            val exchangeRate = number(row, "frst_bltn_exrt").takeIf { it > 0.0 }
                ?: number(row, "bass_exrt").takeIf { it > 0.0 }
                ?: when (currency) {
                    "JPY" -> 905.0
                    else -> 1350.0
                }
            val amountKrw = when (currency) {
                "JPY" -> amount * (exchangeRate / 100.0)
                "KRW" -> amount
                else -> amount * exchangeRate
            }
            TradeRow(
                date = date,
                market = string(row, "ovrs_excg_cd").ifBlank { fallbackMarket },
                symbol = symbol,
                name = string(row, "ovrs_item_name").ifBlank { symbol },
                side = normalizeSide(string(row, "sll_buy_dvsn_cd"), string(row, "sll_buy_dvsn_name")),
                currency = currency,
                quantity = quantity,
                unitPrice = unitPrice,
                amountNative = amount,
                amountKrw = amountKrw,
                time = "",
            )
        }

    private fun sortTradeRowsNewestFirst(rows: List<TradeRow>): List<TradeRow> = rows.sortedWith(
        compareByDescending<TradeRow> { it.date }
            .thenByDescending { normalizeTradeTime(it.time) }
            .thenByDescending { if (it.market == "KOR") 1 else 0 }
            .thenBy { it.symbol },
    )

    private fun normalizeTradeTime(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return digits.padEnd(6, '0').take(6)
    }

    private fun dedupeTradeRows(rows: List<TradeRow>): List<TradeRow> {
        val seen = mutableSetOf<String>()
        return rows.filter { row ->
            val key = listOf(row.date, row.symbol, row.side, row.quantity, row.unitPrice, row.amountKrw).joinToString("|")
            seen.add(key)
        }
    }

    private fun dedupeRealizedRows(rows: List<RealizedTradeProfitRow>): List<RealizedTradeProfitRow> {
        val seen = mutableSetOf<String>()
        return rows.filter { row ->
            val key = listOf(row.date, row.symbol, row.quantity, row.amount, row.realizedProfitKrw, row.buyAmountKrw).joinToString("|")
            seen.add(key)
        }
    }

    private fun attachRealizedProfitToSellTrades(
        trades: List<TradeRow>,
        domestic: List<RealizedTradeProfitRow>,
        overseas: List<RealizedTradeProfitRow>,
    ) {
        val domesticMatchers = buildProfitMatchers(domestic)
        val overseasMatchers = buildProfitMatchers(overseas)
        trades.forEach { trade ->
            if (trade.side != "매도") return@forEach
            val key = trade.date to trade.symbol
            val candidates = if (trade.market == "KOR") domesticMatchers[key] else overseasMatchers[key]
            if (candidates.isNullOrEmpty()) return@forEach
            val chosen = candidates.indexOfFirst { candidate ->
                val qtyDiff = abs(candidate.quantity - trade.quantity)
                val amountDiff = abs(candidate.amount - trade.amountKrw)
                qtyDiff < 0.0001 && amountDiff < max(1.0, abs(trade.amountKrw) * 0.01)
            }.takeIf { it >= 0 } ?: 0
            val matched = candidates.removeAt(chosen)
            trade.realizedProfitKrw = matched.realizedProfitKrw
            trade.realizedReturnRate = matched.realizedReturnRate ?: if (matched.buyAmountKrw > 0.0) matched.realizedProfitKrw / matched.buyAmountKrw * 100.0 else null
        }
    }

    private fun buildProfitMatchers(rows: List<RealizedTradeProfitRow>): MutableMap<Pair<String, String>, MutableList<RealizedTradeProfitRow>> {
        val map = mutableMapOf<Pair<String, String>, MutableList<RealizedTradeProfitRow>>()
        rows.forEach { row ->
            map.getOrPut(row.date to row.symbol) { mutableListOf() }.add(row)
        }
        return map
    }

    private fun hasJapanTradeRows(rows: List<TradeRow>): Boolean = rows.any {
        it.market in setOf("TSE", "TKSE", "JPX", "TYO")
    }

    private fun resolveTradeRange(raw: String): Triple<LocalDate, LocalDate, String> {
        val today = OffsetDateTime.now(ZoneOffset.ofHours(9)).toLocalDate()
        return when (raw) {
            "last_month" -> {
                val end = today.withDayOfMonth(1).minusDays(1)
                Triple(end.withDayOfMonth(1), end, "지난 달")
            }
            "3m" -> Triple(today.withDayOfMonth(1).minusMonths(2), today, "최근 3개월")
            "6m" -> Triple(today.withDayOfMonth(1).minusMonths(5), today, "지난 6개월")
            else -> Triple(today.withDayOfMonth(1), today, "이번 달")
        }
    }

    private fun isTokenError(code: Int, json: JsonObject): Boolean {
        if (code == 401) return true
        val msgCode = string(json, "msg_cd")
        val msg = string(json, "msg1").lowercase()
        return msgCode in setOf("EGW00123", "EGW00121") || "token" in msg
    }

    private fun isRateLimitError(code: Int, json: JsonObject): Boolean {
        val msg = string(json, "msg1")
        return code >= 429 || msg.contains("초당 거래건수를 초과", ignoreCase = true)
    }

    private fun getOverseasNationCode(exchangeCode: String): String = when (exchangeCode.uppercase()) {
        "NASD", "NYSE", "AMEX", "NAS", "NYS", "AMS" -> "840"
        "TKSE", "TSE", "JPX", "TYO" -> "392"
        "SEHK", "HKS" -> "344"
        "SHAA", "SHS", "SZS" -> "156"
        "HASE", "HSX", "HNX" -> "704"
        else -> ""
    }

    private fun pickForeignSellReuseFromOutput2(rows: List<JsonObject>, currencyCode: String): Double {
        val normalized = currencyCode.uppercase()
        val matchedRows = rows.filter { string(it, "crcy_cd").uppercase() == normalized }
            .ifEmpty { rows }
        val keys = listOf(
            "sll_ruse_psbl_amt",
            "sl_ruse_frcr_amt",
            "frcr_sll_amt_smtl",
        )
        keys.forEach { key ->
            val value = matchedRows.maxOfOrNull { number(it, key) } ?: 0.0
            if (value > 0.0) return value
        }
        return 0.0
    }

    private fun pickForeignBalanceFromOutput2(rows: List<JsonObject>, currencyCode: String): Double {
        val keys = listOf(
            "frcr_dncl_amt_2",
            "tot_frcr_cblc_smtl",
            "frcr_use_psbl_amt",
            "frcr_drwg_psbl_amt_1",
            "frcr_drwg_psbl_amt1",
            "frcr_ord_psbl_amt1",
            "frcr_ord_psbl_amt2",
            "frcr_ord_psbl_amt",
            "ord_psbl_frcr_amt",
            "ovrs_ord_psbl_amt",
            "ord_psbl_amt",
        )
        keys.forEach { key ->
            val value = rows.filter { string(it, "crcy_cd").uppercase() == currencyCode.uppercase() }
                .ifEmpty { rows }
                .maxOfOrNull { number(it, key) } ?: 0.0
            if (value > 0.0) return value
        }
        return 0.0
    }

    private fun pickForeignBalanceFromHoldings(rows: List<JsonObject>, currencyCode: String): Double {
        val row = rows.firstOrNull { string(it, "pdno").uppercase() == currencyCode.uppercase() } ?: return 0.0
        return pickFirstPositive(row, listOf("ccld_qty_smtl1", "frcr_dncl_amt_2", "tot_frcr_cblc_smtl", "frcr_use_psbl_amt", "frcr_drwg_psbl_amt_1"))
    }

    private fun resolveUsdExchangeRate(output1: List<JsonObject>, output2: List<JsonObject>): Double {
        return output2.firstNotNullOfOrNull { row ->
            if (string(row, "crcy_cd").uppercase() == "USD") number(row, "bass_exrt").takeIf { it > 0.0 } else null
        } ?: output1.firstNotNullOfOrNull { row ->
            number(row, "bass_exrt").takeIf { it > 0.0 }
        } ?: 1350.0
    }

    private fun resolveJpyExchangeRate(output1: List<JsonObject>, output2: List<JsonObject>): Double {
        return output1.firstNotNullOfOrNull { row ->
            if (string(row, "pdno").uppercase() == "JPY") number(row, "bass_exrt").takeIf { it > 0.0 } else null
        } ?: output2.firstNotNullOfOrNull { row ->
            if (string(row, "crcy_cd").uppercase() == "JPY") number(row, "bass_exrt").takeIf { it > 0.0 } else null
        } ?: output1.firstNotNullOfOrNull { row ->
            number(row, "bass_exrt").takeIf { it > 0.0 }
        } ?: 905.0
    }

    private fun pickFirstPositive(json: JsonObject, keys: List<String>): Double =
        keys.firstNotNullOfOrNull { key -> number(json, key).takeIf { it > 0.0 } } ?: 0.0

    private fun normalizeRows(element: JsonElement?): List<JsonObject> = when {
        element == null || element.isJsonNull -> emptyList()
        element.isJsonArray -> element.asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        element.isJsonObject -> listOf(element.asJsonObject)
        else -> emptyList()
    }

    private fun jsonObject(json: JsonObject, key: String): JsonObject? =
        json.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun jsonArray(json: JsonObject, key: String): List<JsonElement> =
        json.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList()

    private fun parseObject(body: String): JsonObject? =
        runCatching { JsonParser().parse(body).asJsonObject }.getOrNull()

    private fun string(json: JsonObject, key: String): String =
        runCatching { json.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

    private fun number(json: JsonObject, key: String): Double =
        runCatching {
            val value = json.get(key) ?: return@runCatching 0.0
            when {
                value.isJsonNull -> 0.0
                value.asJsonPrimitive.isNumber -> value.asDouble
                value.asJsonPrimitive.isString -> value.asString.replace(",", "").trim().toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }.getOrDefault(0.0)
}

internal fun firstPositiveNumber(json: JsonObject, keys: List<String>): Double =
    keys.firstNotNullOfOrNull { key -> numberFromJson(json, key).takeIf { it > 0.0 } } ?: 0.0

internal fun resolvedTradeAmount(amount: Double, quantity: Double, unitPrice: Double): Double = when {
    amount > 0.0 -> amount
    quantity > 0.0 && unitPrice > 0.0 -> quantity * unitPrice
    else -> 0.0
}

private fun numberFromJson(json: JsonObject, key: String): Double =
    runCatching {
        val value = json.get(key) ?: return@runCatching 0.0
        when {
            value.isJsonNull -> 0.0
            value.asJsonPrimitive.isNumber -> value.asDouble
            value.asJsonPrimitive.isString -> value.asString.replace(",", "").trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }.getOrDefault(0.0)

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

private data class DomesticHoldingRaw(
    val symbol: String,
    val name: String,
    val quantity: Double,
    val averageCost: Double,
    val currentPrice: Double,
)

private data class OverseasHoldingRaw(
    val symbol: String,
    val name: String,
    val exchangeCode: String,
    val quantity: Double,
    val averageCost: Double,
    val currentPrice: Double,
    val exchangeRate: Double,
    val currency: String,
)

private data class DomesticBalancePayload(
    val totalPurchaseKrw: Double = 0.0,
    val totalEvalKrw: Double = 0.0,
    val totalProfitKrw: Double = 0.0,
    val cashKrw: Double = 0.0,
    val holdings: List<DomesticHoldingRaw> = emptyList(),
)

private data class SingleOverseasBalancePayload(
    val cashBalance: Double = 0.0,
    val exchangeRate: Double = 0.0,
    val holdings: List<OverseasHoldingRaw> = emptyList(),
)

private data class OverseasBalancePayload(
    val usdCashBalance: Double = 0.0,
    val usdExchangeRate: Double = 0.0,
    val usHoldings: List<OverseasHoldingRaw> = emptyList(),
    val jpyCashBalance: Double = 0.0,
    val jpyExchangeRate: Double = 0.0,
    val jpHoldings: List<OverseasHoldingRaw> = emptyList(),
)

private data class RealizedTradeProfitRow(
    val date: String,
    val symbol: String,
    val quantity: Double,
    val amount: Double,
    val realizedProfitKrw: Double,
    val buyAmountKrw: Double,
    val realizedReturnRate: Double?,
)

    private data class TradeRow(
        val date: String,
        val market: String,
        val symbol: String,
        val name: String,
        val side: String,
        val currency: String,
        val quantity: Double,
        val unitPrice: Double,
        val amountNative: Double,
        val amountKrw: Double,
        val time: String,
        var realizedProfitKrw: Double? = null,
    var realizedReturnRate: Double? = null,
)

private fun normalizeSide(code: String, label: String): String {
    return when {
        label.contains("매도") || code == "01" -> "매도"
        label.contains("매수") || code == "02" -> "매수"
        label.isNotBlank() -> label
        code.isNotBlank() -> code
        else -> "-"
    }
}
