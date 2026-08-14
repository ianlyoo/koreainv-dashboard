package com.koreainv.dashboard.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest

internal const val ACCOUNT_PROFILE_VERSION = 2

internal data class AccountProfileEnvelope(
    val version: Int,
    val accounts: List<AccountCredential>,
)

internal data class TokenCacheEnvelope(
    val tokens: Map<String, AuthToken>,
)

internal object AccountProfileCodec {
    private val gson = Gson()

    fun serialize(accounts: List<AccountCredential>): String =
        gson.toJson(AccountProfileEnvelope(version = ACCOUNT_PROFILE_VERSION, accounts = accounts))

    /**
     * Parses an encrypted credential payload. Supports the current multi-account
     * envelope and migrates legacy single-account AppCredentials JSON.
     */
    fun parse(payload: String): List<AccountCredential>? {
        val json = runCatching { JsonParser().parse(payload).asJsonObject }.getOrNull() ?: return null
        val accountsElement = json.get("accounts")
        if (accountsElement != null && accountsElement.isJsonArray) {
            val envelope = runCatching {
                gson.fromJson(payload, AccountProfileEnvelope::class.java)
            }.getOrNull() ?: return null
            if (envelope.accounts.isEmpty()) return null
            return envelope.accounts
        }
        if (json.has("appKey") || json.has("appkey")) {
            val legacy = runCatching {
                gson.fromJson(payload, AppCredentials::class.java)
            }.getOrNull() ?: return null
            return listOf(legacy.toAccountCredential())
        }
        return null
    }
}

internal object TokenCacheCodec {
    private val gson = Gson()

    fun serialize(tokens: Map<String, AuthToken>): String =
        gson.toJson(TokenCacheEnvelope(tokens = tokens))

    fun parse(payload: String): Map<String, AuthToken> {
        val envelope = runCatching {
            gson.fromJson(payload, TokenCacheEnvelope::class.java)
        }.getOrNull() ?: return emptyMap()
        return envelope.tokens.filterValues { token ->
            token.value.isNotBlank() && token.expiresAtMillis > token.issuedAtMillis
        }
    }
}

internal fun AppCredentials.toAccountCredential(): AccountCredential {
    val id = stableAccountId(cano, acntPrdtCd)
    return AccountCredential(
        id = id,
        label = defaultAccountLabel(cano),
        appKey = appKey,
        appSecret = appSecret,
        cano = cano,
        acntPrdtCd = acntPrdtCd,
        // Gson does not apply Kotlin constructor defaults when a field is
        // absent from legacy JSON, so normalize platform-null strings here.
        centralServerBaseUrl = centralServerBaseUrl.orEmpty(),
        centralServerApiToken = centralServerApiToken.orEmpty(),
    )
}

internal fun stableAccountId(cano: String, acntPrdtCd: String): String {
    val raw = "${cano.trim()}:${acntPrdtCd.trim().ifBlank { "01" }}"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
    return "acct_$digest"
}

internal fun defaultAccountLabel(cano: String): String =
    if (cano.isNotBlank()) "계좌 ${cano.takeLast(4)}" else "메인 계좌"
