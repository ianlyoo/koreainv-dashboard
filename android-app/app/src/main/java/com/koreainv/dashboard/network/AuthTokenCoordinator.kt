package com.koreainv.dashboard.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** KIS grants belong to app credentials, independently of CANO/product. */
internal fun oauthTokenScope(account: AccountCredential): String {
    val credentials = account.toAppCredentials()
    return tokenScope(
        if (Broker.normalize(account.broker) == Broker.KIS) {
            credentials.copy(cano = "", acntPrdtCd = "")
        } else {
            credentials
        },
    )
}

/** Existing persisted account scopes remain aliases; no cache schema migration. */
internal class AuthTokenAliases(accounts: List<AccountCredential>) {
    private val scopes = accounts.groupBy(::oauthTokenScope).mapValues { (_, aliases) ->
        aliases.map { tokenScope(it.toAppCredentials()) }.toSet()
    }

    fun scopesFor(account: AccountCredential): Set<String> =
        scopes[oauthTokenScope(account)] ?: setOf(tokenScope(account.toAppCredentials()))
}

/** Owns all in-memory token access, including recovery from a rejected request. */
internal class AuthTokenCoordinator(
    private val load: suspend (AccountCredential) -> AuthToken?,
    private val save: suspend (AccountCredential, AuthToken) -> Unit,
    private val invalidate: suspend (AccountCredential) -> Unit,
    private val issue: suspend (AccountCredential) -> AuthToken?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val mutex = Mutex()
    private val tokens = mutableMapOf<String, AuthToken>()
    private val sameValueRecoveries = mutableMapOf<String, SameValueRecovery>()

    private data class SameValueRecovery(val token: String, val completedAtMillis: Long)

    suspend fun requireToken(account: AccountCredential): String? = mutex.withLock {
        usableToken(account)?.value ?: acquireToken(account)
    }

    suspend fun refreshToken(account: AccountCredential, rejectedToken: String): String? = mutex.withLock {
        val scope = oauthTokenScope(account)
        // A sibling request may already have replaced the token this response rejected.
        // Compare, invalidate, issue and publish under the same lock so a late response
        // cannot erase that replacement while another request is using it.
        val current = usableToken(account)
        if (current != null && current.value != rejectedToken) {
            return@withLock current.value
        }
        // KIS can issue the same token value again. Coalesce only a recent successful
        // recovery of that value; a later rejection must be allowed to refresh again.
        val recent = sameValueRecoveries[scope]
        if (current != null && recent?.token == rejectedToken &&
            elapsedMillis() - recent.completedAtMillis in 0L until 60_000L
        ) {
            return@withLock current.value
        }
        sameValueRecoveries.remove(scope)
        tokens.remove(scope)
        invalidate(account)
        val refreshed = acquireToken(account)
        if (refreshed != null && refreshed == rejectedToken) {
            sameValueRecoveries[scope] = SameValueRecovery(refreshed, elapsedMillis())
        }
        refreshed
    }

    // Called only while holding mutex, including when another account shares this grant.
    private suspend fun usableToken(account: AccountCredential): AuthToken? {
        val scope = oauthTokenScope(account)
        val now = nowMillis()
        tokens[scope]?.takeIf { it.isUsableAt(now) }?.let { return it }
        tokens.remove(scope)
        return load(account)?.takeIf { it.isUsableAt(now) }?.also { tokens[scope] = it }
    }

    private suspend fun acquireToken(account: AccountCredential): String? {
        val scope = oauthTokenScope(account)
        sameValueRecoveries.remove(scope)
        val token = issue(account) ?: return null
        save(account, token)
        tokens[scope] = token
        return token.value
    }

    private fun AuthToken.isUsableAt(now: Long): Boolean =
        value.isNotBlank() && now < expiresAtMillis - 60_000L
}
