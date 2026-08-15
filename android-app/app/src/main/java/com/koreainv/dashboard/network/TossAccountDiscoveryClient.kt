package com.koreainv.dashboard.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

data class TossAccountOption(
    val accountSeq: String,
    val maskedAccountNo: String,
    val accountType: String,
) {
    val displayName: String
        get() = if (maskedAccountNo.isBlank()) "토스증권 계좌" else "토스증권 계좌 $maskedAccountNo"
}

internal fun maskTossAccountNo(value: String): String {
    val digits = value.filter(Char::isDigit)
    return when {
        digits.isBlank() -> ""
        digits.length <= 4 -> digits
        else -> "••••${digits.takeLast(4)}"
    }
}

internal fun parseTossAccountOptions(payload: String): List<TossAccountOption> {
    val root = runCatching { JsonParser().parse(payload).asJsonObject }.getOrNull() ?: return emptyList()
    val result = root.getAsJsonArray("result") ?: return emptyList()
    return result.mapNotNull { element ->
        val account = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val accountSeq = account.get("accountSeq")?.asString.orEmpty().trim()
        val validSequence = accountSeq.toLongOrNull()?.let { it > 0 } == true
        if (!validSequence) return@mapNotNull null
        TossAccountOption(
            accountSeq = accountSeq,
            maskedAccountNo = maskTossAccountNo(account.get("accountNo")?.asString.orEmpty()),
            accountType = account.get("accountType")?.asString.orEmpty(),
        )
    }
}

object TossAccountDiscoveryClient {
    private const val BASE_URL = "https://openapi.tossinvest.com"
    private const val CACHE_MILLIS = 60_000L

    private data class CacheEntry(
        val cachedAt: Long,
        val accounts: List<TossAccountOption>,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()

    suspend fun fetchAccounts(
        clientId: String,
        clientSecret: String,
        forceRefresh: Boolean = false,
    ): List<TossAccountOption> = withContext(Dispatchers.IO) {
        val safeClientId = clientId.trim()
        val safeClientSecret = clientSecret.trim()
        require(safeClientId.isNotBlank() && safeClientSecret.isNotBlank()) {
            "CLIENT ID와 CLIENT SECRET을 모두 입력하세요."
        }
        val scope = credentialScope(safeClientId, safeClientSecret)
        mutex.withLock {
            val now = System.currentTimeMillis()
            cache[scope]?.takeIf { !forceRefresh && now - it.cachedAt < CACHE_MILLIS }?.let {
                return@withLock it.accounts
            }

            val token = issueToken(safeClientId, safeClientSecret)
            val request = Request.Builder()
                .url("$BASE_URL/api/v1/accounts")
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .build()
            val accounts = client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(tossErrorMessage(body, "토스 계좌 조회에 실패했습니다."))
                }
                parseTossAccountOptions(body)
            }
            cache[scope] = CacheEntry(now, accounts)
            accounts
        }
    }

    private fun issueToken(clientId: String, clientSecret: String): String {
        val request = Request.Builder()
            .url("$BASE_URL/oauth2/token")
            .post(
                FormBody.Builder()
                    .add("grant_type", "client_credentials")
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .build(),
            )
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(tossErrorMessage(body, "토스 인증에 실패했습니다."))
            }
            val token = runCatching {
                JsonParser().parse(body).asJsonObject.get("access_token")?.asString
            }.getOrNull().orEmpty()
            if (token.isBlank()) throw IllegalStateException("토스 인증 응답에 액세스 토큰이 없습니다.")
            token
        }
    }

    private fun tossErrorMessage(payload: String, fallback: String): String {
        val root = runCatching { JsonParser().parse(payload).asJsonObject }.getOrNull() ?: return fallback
        val errorElement = root.get("error")
        val error = errorElement?.takeIf { it.isJsonObject }?.asJsonObject
        val errorCode = errorElement?.takeIf { it.isJsonPrimitive }?.asString
        return error?.string("message")
            ?: root.string("error_description")
            ?: error?.string("code")
            ?: errorCode
            ?: fallback
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf(String::isNotBlank)

    private fun credentialScope(clientId: String, clientSecret: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$clientId::$clientSecret".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
