package com.koreainv.dashboard.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.security.MessageDigest

internal const val ACCOUNT_PROFILE_VERSION = 3

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
            return envelope.accounts.map { account ->
                val broker = Broker.normalize(account.broker)
                val cano = account.cano.orEmpty()
                val productCode = if (broker == Broker.KIS) {
                    account.acntPrdtCd.orEmpty().ifBlank { "01" }
                } else {
                    ""
                }
                account.copy(
                    id = account.id.orEmpty().ifBlank { stableAccountId(cano, productCode, broker) },
                    label = account.label.orEmpty().ifBlank { defaultAccountLabel(cano, broker) },
                    appKey = account.appKey.orEmpty(),
                    appSecret = account.appSecret.orEmpty(),
                    cano = cano,
                    broker = broker,
                    acntPrdtCd = productCode,
                    centralServerBaseUrl = account.centralServerBaseUrl.orEmpty(),
                    centralServerApiToken = account.centralServerApiToken.orEmpty(),
                )
            }
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
        broker = Broker.KIS,
    )
}

internal fun stableAccountId(cano: String, acntPrdtCd: String, broker: String = Broker.KIS): String {
    val normalizedBroker = Broker.normalize(broker)
    val raw = if (normalizedBroker == Broker.KIS) {
        "${cano.trim()}:${acntPrdtCd.trim().ifBlank { "01" }}"
    } else {
        "$normalizedBroker:${cano.trim()}"
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
    return "acct_$digest"
}

internal fun defaultAccountLabel(cano: String, broker: String = Broker.KIS): String =
    when {
        Broker.normalize(broker) == Broker.TOSS && cano.isNotBlank() -> "토스 계좌 #$cano"
        cano.isNotBlank() -> "계좌 ${cano.takeLast(4)}"
        else -> "메인 계좌"
    }

internal fun normalizeUpdatedAccounts(
    existing: List<AccountCredential>,
    updates: List<AccountCredential>,
): List<AccountCredential> {
    require(updates.isNotEmpty()) { "ACCOUNT_PROFILE_EMPTY" }
    val existingById = existing.associateBy { it.id }
    val explicitIds = updates.map(AccountCredential::id).filter(String::isNotBlank)
    require(explicitIds.distinct().size == explicitIds.size) { "ACCOUNT_ID_DUPLICATE" }
    val usedIds = explicitIds.toMutableSet()
    val normalized = updates.map { account ->
        val previous = existingById[account.id]
        val broker = Broker.normalize(account.broker)
        val cano = account.cano.trim()
        val productCode = if (broker == Broker.KIS) account.acntPrdtCd.trim() else ""
        if (broker == Broker.KIS) {
            require(cano.length == 8 && cano.all(Char::isDigit)) {
                "ACCOUNT_NUMBER_INVALID"
            }
            require(productCode.length == 2 && productCode.all(Char::isDigit)) {
                "ACCOUNT_PRODUCT_CODE_INVALID"
            }
        } else {
            require(cano.isNotBlank() && cano.all(Char::isDigit) && cano.toLongOrNull()?.let { it > 0 } == true) {
                "TOSS_ACCOUNT_SEQUENCE_INVALID"
            }
        }
        val appKey = account.appKey.trim().ifBlank { previous?.appKey.orEmpty() }
        val appSecret = account.appSecret.trim().ifBlank { previous?.appSecret.orEmpty() }
        require(appKey.isNotBlank() && appSecret.isNotBlank()) {
            "ACCOUNT_CREDENTIALS_REQUIRED"
        }
        val accountId = if (account.id.isNotBlank()) {
            account.id
        } else {
            val baseId = stableAccountId(cano, productCode, broker)
            var candidate = baseId
            var suffix = 2
            while (!usedIds.add(candidate)) {
                candidate = "$baseId-$suffix"
                suffix += 1
            }
            candidate
        }
        account.copy(
            id = accountId,
            label = account.label.trim().ifBlank { defaultAccountLabel(cano, broker) },
            appKey = appKey,
            appSecret = appSecret,
            cano = cano,
            acntPrdtCd = productCode,
            broker = broker,
            centralServerBaseUrl = if (broker == Broker.TOSS) {
                account.centralServerBaseUrl.trim()
            } else {
                account.centralServerBaseUrl.trim().ifBlank { previous?.centralServerBaseUrl.orEmpty() }
            },
            centralServerApiToken = if (broker == Broker.TOSS) {
                account.centralServerApiToken.trim()
            } else {
                account.centralServerApiToken.trim().ifBlank { previous?.centralServerApiToken.orEmpty() }
            },
        )
    }
    require(
        normalized.map { "${Broker.normalize(it.broker)}:${it.cano}:${it.acntPrdtCd}" }.distinct().size == normalized.size,
    ) { "ACCOUNT_PROFILE_DUPLICATE" }
    return normalized
}
