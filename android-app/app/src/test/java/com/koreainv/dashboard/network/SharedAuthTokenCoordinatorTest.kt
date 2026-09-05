package com.koreainv.dashboard.network

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedAuthTokenCoordinatorTest {
    private val general = account("general", "01")
    private val pension = account("pension", "29")
    private val other = account("other", "01").copy(appKey = "different-fake-key")
    private val accounts = listOf(general, pension, other)

    @Test
    fun simultaneousEmptyCacheGeneralAndPensionIssueOnceAndSaveBothAliases() = runBlocking {
        withTimeout(5_000) {
            val fixture = Fixture(accounts)
            val issuing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.issue = {
                issuing.complete(Unit)
                release.await()
                fixture.token("shared")
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.requireToken(general) }
            issuing.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.requireToken(pension) }
            assertFalse(second.isCompleted)
            assertEquals(1, fixture.issuances)
            release.complete(Unit)

            assertEquals(listOf("shared", "shared"), listOf(first, second).awaitAll())
            assertEquals(1, fixture.issuances)
            assertEquals("shared", fixture.persisted(general)?.value)
            assertEquals("shared", fixture.persisted(pension)?.value)
            assertNull(fixture.persisted(other))
        }
    }

    @Test
    fun bothAccountsPreloadedWithT0ShareConcurrentRefreshIncludingSameValueIssuance() = runBlocking {
        withTimeout(5_000) {
            for (replacement in listOf("T1", "T0")) {
                val fixture = Fixture(accounts)
                fixture.seed(general, fixture.token("T0"))
                fixture.seed(pension, fixture.token("T0"))
                fixture.seed(other, fixture.token("other-valid"))
                // Load both account identities before rejection: grouped disk aliases alone
                // cannot prevent the old account-keyed memory from erasing a sibling refresh.
                assertEquals("T0", fixture.coordinator.requireToken(general))
                assertEquals("T0", fixture.coordinator.requireToken(pension))
                val issuing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fixture.issue = {
                    issuing.complete(Unit)
                    release.await()
                    fixture.token(replacement)
                }
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.coordinator.refreshToken(general, "T0")
                }
                issuing.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.coordinator.refreshToken(pension, "T0")
                }
                assertFalse(second.isCompleted)
                release.complete(Unit)

                assertEquals(listOf(replacement, replacement), listOf(first, second).awaitAll())
                assertEquals(1, fixture.issuances)
                assertEquals(1, fixture.invalidations)
                assertEquals(replacement, fixture.persisted(general)?.value)
                assertEquals(replacement, fixture.persisted(pension)?.value)
                assertEquals("other-valid", fixture.coordinator.requireToken(other))
                if (replacement == "T0") {
                    fixture.now += 60_000
                    assertEquals("T0", fixture.coordinator.refreshToken(pension, "T0"))
                    assertEquals(2, fixture.issuances)
                }
            }
        }
    }

    @Test
    fun usableSiblingWinsOverExpiredAndInvalidCachedAliases() = runBlocking {
        val invalid = account("invalid", "22")
        val fixture = Fixture(accounts + invalid)
        fixture.seed(general, fixture.token("expired", -1))
        fixture.seed(pension, fixture.token("usable-sibling"))
        fixture.seed(other, fixture.token("unrelated-longer-lived", 9_000_000))
        fixture.seed(invalid, fixture.token("", 7_200_000))

        assertEquals("usable-sibling", fixture.coordinator.requireToken(general))
        assertEquals("usable-sibling", fixture.coordinator.requireToken(pension))
        assertEquals(0, fixture.issuances)
    }

    @Test
    fun newestUsableSiblingWinsEvenWhenOlderTokenHasLongerExpiry() = runBlocking {
        val fixture = Fixture(accounts)
        fixture.seed(general, fixture.token("old-long-expiry", 7_200_000))
        fixture.seed(pension, fixture.token("new-short-expiry", 120_000).copy(issuedAtMillis = fixture.now - 100))

        assertEquals("new-short-expiry", fixture.coordinator.requireToken(general))
        assertEquals("new-short-expiry", fixture.coordinator.requireToken(pension))
        assertEquals(0, fixture.issuances)
    }

    @Test
    fun newerSiblingInsideExpiryBufferCannotHideAnOlderUsableToken() = runBlocking {
        val fixture = Fixture(accounts)
        fixture.seed(general, fixture.token("usable", 120_000))
        fixture.seed(pension, fixture.token("new-but-expiring", 60_000).copy(issuedAtMillis = fixture.now - 100))

        assertEquals("usable", fixture.coordinator.requireToken(pension))
        assertEquals(0, fixture.issuances)
    }

    @Test
    fun expiredAliasesTriggerOneFreshGrant() = runBlocking {
        val fixture = Fixture(accounts)
        fixture.seed(general, fixture.token("expired", -1))
        fixture.seed(pension, fixture.token("inside-buffer", 60_000))

        assertEquals("issued", fixture.coordinator.requireToken(general))
        assertEquals("issued", fixture.coordinator.requireToken(pension))
        assertEquals(1, fixture.issuances)
    }

    @Test
    fun failedRefreshClearsEveryMatchingAliasAndLegacyTokenSoOldSiblingCannotResurrect() = runBlocking {
        val fixture = Fixture(accounts)
        fixture.seed(general, fixture.token("outdated-sibling", 120_000))
        fixture.seed(pension, fixture.token("rejected", 3_600_000))
        fixture.seed(other, fixture.token("other-valid"))
        fixture.preferences[stringPreferencesKey("token_scope")] = scope(general)
        fixture.preferences[stringPreferencesKey("access_token")] = "outdated-legacy"
        fixture.preferences[longPreferencesKey("token_issued_at")] = fixture.now - 20_000
        fixture.preferences[longPreferencesKey("token_expires_at")] = fixture.now + 180_000
        assertEquals("rejected", fixture.coordinator.requireToken(general))
        assertEquals("rejected", fixture.coordinator.requireToken(pension))
        fixture.issue = { null }

        assertNull(fixture.coordinator.refreshToken(general, "rejected"))
        assertNull(fixture.persisted(general))
        assertNull(fixture.persisted(pension))
        assertNull(fixture.preferences[stringPreferencesKey("access_token")])
        assertEquals("other-valid", fixture.persisted(other)?.value)

        // A repository restart must issue again, never fall back to the older sibling.
        fixture.issue = { fixture.token("recovered") }
        assertEquals("recovered", fixture.newCoordinator().requireToken(pension))
        assertEquals(2, fixture.issuances)
        assertEquals("recovered", fixture.persisted(general)?.value)
        assertEquals("recovered", fixture.persisted(pension)?.value)
    }

    @Test
    fun legacyTokenFromASiblingIsReusableWithoutChangingPersistentScopeFormat() = runBlocking {
        val fixture = Fixture(accounts)
        fixture.preferences[stringPreferencesKey("token_scope")] = scope(pension)
        fixture.preferences[stringPreferencesKey("access_token")] = "legacy-shared"
        fixture.preferences[longPreferencesKey("token_issued_at")] = fixture.now - 1_000
        fixture.preferences[longPreferencesKey("token_expires_at")] = fixture.now + 3_600_000

        assertEquals("legacy-shared", fixture.coordinator.requireToken(general))
        assertEquals("legacy-shared", fixture.coordinator.requireToken(pension))
        assertEquals(0, fixture.issuances)
        assertNotEquals(scope(general), scope(pension))
    }

    @Test
    fun differentKeysSecretsAndBrokersKeepSeparateTokenIdentities() = runBlocking {
        val changedSecret = general.copy(id = "new-secret", appSecret = "different-fake-secret")
        val toss = general.copy(id = "toss", broker = Broker.TOSS)
        val fixture = Fixture(accounts + changedSecret + toss)
        fixture.issue = { fixture.token(it.id) }

        assertEquals(oauthTokenScope(general), oauthTokenScope(pension))
        assertEquals("general", fixture.coordinator.requireToken(general))
        assertEquals("general", fixture.coordinator.requireToken(pension))
        assertEquals("other", fixture.coordinator.requireToken(other))
        assertEquals("new-secret", fixture.coordinator.requireToken(changedSecret))
        assertEquals("toss", fixture.coordinator.requireToken(toss))
        assertEquals(4, fixture.issuances)
        assertNotEquals(oauthTokenScope(general), oauthTokenScope(other))
        assertNotEquals(oauthTokenScope(general), oauthTokenScope(changedSecret))
        assertNotEquals(oauthTokenScope(general), oauthTokenScope(toss))
    }

    private class Fixture(accounts: List<AccountCredential>) {
        var now = 1_000_000L
        val preferences = mutablePreferencesOf()
        private val aliases = AuthTokenAliases(accounts)
        var issuances = 0
        var invalidations = 0
        var issue: suspend (AccountCredential) -> AuthToken? = { token("issued") }
        val coordinator = newCoordinator()

        // These are the same alias, selection, save and invalidation helpers wired by
        // KisRepository/SettingsManager, with only DataStore IO and OAuth issuance faked.
        fun newCoordinator() = AuthTokenCoordinator(
            load = { SettingsManager.loadAuthToken(preferences, aliases.scopesFor(it), now) },
            save = { account, token -> SettingsManager.saveAuthToken(preferences, aliases.scopesFor(account), token) },
            invalidate = {
                invalidations += 1
                SettingsManager.clearAuthToken(preferences, aliases.scopesFor(it))
            },
            issue = {
                issuances += 1
                issue(it)
            },
            nowMillis = { now },
            elapsedMillis = { now },
        )

        fun seed(account: AccountCredential, token: AuthToken) =
            SettingsManager.saveAuthToken(preferences, setOf(scope(account)), token)

        fun persisted(account: AccountCredential) =
            SettingsManager.loadAuthToken(preferences, setOf(scope(account)), now)

        fun token(value: String, remainingMillis: Long = 3_600_000L) =
            AuthToken(value, now - 10_000, now + remainingMillis)
    }

    companion object {
        private fun scope(account: AccountCredential) = tokenScope(account.toAppCredentials())
        private fun account(id: String, productCode: String) = AccountCredential(
            id = id,
            label = id,
            appKey = "shared-fake-key",
            appSecret = "shared-fake-secret",
            cano = "11111111",
            acntPrdtCd = productCode,
        )
    }
}
