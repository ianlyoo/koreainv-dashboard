package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CentralOrderClient(
    private val client: OkHttpClient,
) {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun submitScheduledDomesticOrder(
        credentials: AppCredentials,
        request: ScheduledDomesticOrderRequest,
    ): ScheduledOrderSummary = withContext(Dispatchers.IO) {
        val baseUrl = credentials.centralServerBaseUrl.trim().trimEnd('/')
        val apiToken = credentials.centralServerApiToken.trim()
        if (baseUrl.isBlank() || apiToken.isBlank()) {
            throw IllegalStateException("CENTRAL_SERVER_CONFIG_MISSING")
        }

        val payload = JsonObject().apply {
            addProperty("execute_at", request.executeAt)
            addProperty("side", request.side)
            addProperty("pdno", request.pdno)
            addProperty("ord_qty", request.ordQty)
            addProperty("ord_unpr", request.ordUnpr)
            addProperty("ord_dvsn", request.ordDvsn)
            addProperty("excg_id_dvsn_cd", request.excgIdDvsnCd)
            addProperty("sll_type", request.sllType)
            addProperty("cndt_pric", request.cndtPric)
            addProperty("note", request.note)
            addProperty("source_app", "android")
            add("execution_credentials", JsonObject().apply {
                addProperty("app_key", credentials.appKey)
                addProperty("app_secret", credentials.appSecret)
                addProperty("cano", credentials.cano)
                addProperty("acnt_prdt_cd", credentials.acntPrdtCd)
            })
        }

        val requestBody = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/central-server/scheduled-orders")
            .addHeader("Authorization", "Bearer $apiToken")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            val json = runCatching { JsonParser().parse(bodyText).asJsonObject }.getOrNull()
            if (!response.isSuccessful || json == null) {
                throw IOException("CENTRAL_ORDER_HTTP_${response.code}")
            }
            val order = json.getAsJsonObject("order")
                ?: throw IOException("CENTRAL_ORDER_RESPONSE_INVALID")
            return@withContext ScheduledOrderSummary(
                id = order.string("id"),
                status = order.string("status"),
                sourceApp = order.string("source_app"),
                accountRef = order.string("account_ref"),
                createdAt = order.string("created_at"),
                updatedAt = order.string("updated_at"),
                executeAt = order.string("execute_at"),
                attemptCount = order.int("attempt_count"),
                lastError = order.string("last_error"),
                note = order.string("note"),
            )
        }
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.int(key: String): Int =
        get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0
}
