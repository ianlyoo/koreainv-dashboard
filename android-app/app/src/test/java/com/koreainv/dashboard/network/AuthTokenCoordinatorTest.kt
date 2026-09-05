package com.koreainv.dashboard.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AuthTokenCoordinatorTest {
    private val pension = account("pension", "11111111", "29")
    private val ordinary = account("ordinary", "22222222", "01")

    @Test
    fun concurrentRejectedResponsesShareOneRefreshAndDoNotInvalidateItsReplacement() = runBlocking {
        withTimeout(5_000) {
            val fixture = Fixture()
            fixture.store[scope(pension)] = fixture.token("T0")
            assertEquals("T0", fixture.coordinator.requireToken(pension))
            val issuing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.issue = {
                issuing.complete(Unit)
                release.await()
                fixture.token("T1")
            }

            val first = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.refreshToken(pension, "T0")
            }
            issuing.await()
            val sibling = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.refreshToken(pension, "T0")
            }
            val third = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.refreshToken(pension, "T0")
            }
            val reader = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.requireToken(pension)
            }

            // Siblings must wait before comparing or invalidating, not just before issuing.
            assertEquals(listOf(scope(pension)), fixture.invalidations)
            assertFalse(sibling.isCompleted)
            assertFalse(reader.isCompleted)
            release.complete(Unit)
            assertEquals(listOf("T1", "T1", "T1", "T1"), listOf(first, sibling, third, reader).awaitAll())
            assertEquals(1, fixture.issuances)
            assertEquals(listOf(scope(pension)), fixture.invalidations)
            assertEquals("T1", fixture.store[scope(pension)]?.value)

            // A response arriving after publication also reuses T1.
            assertEquals("T1", fixture.coordinator.refreshToken(pension, "T0"))
            assertEquals(1, fixture.issuances)
        }
    }

    @Test
    fun concurrentRejectionsCoalesceWhenIssuerReturnsTheSameTokenValue() = runBlocking {
        withTimeout(5_000) {
            val fixture = Fixture()
            fixture.store[scope(pension)] = fixture.token("T0")
            val issuing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.issue = {
                issuing.complete(Unit)
                release.await()
                fixture.token("T0")
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.refreshToken(pension, "T0")
            }
            issuing.await()
            val sibling = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.refreshToken(pension, "T0")
            }
            release.complete(Unit)

            assertEquals(listOf("T0", "T0"), listOf(first, sibling).awaitAll())
            assertEquals(1, fixture.issuances)
            assertEquals(listOf(scope(pension)), fixture.invalidations)
        }
    }

    @Test
    fun sameValueRecoveryExpiresAfterSixtySecondsWithoutExtendingOnReuse() = runBlocking {
        val fixture = Fixture()
        fixture.store[scope(pension)] = fixture.token("T0")
        fixture.issue = { fixture.token("T0") }

        assertEquals("T0", fixture.coordinator.refreshToken(pension, "T0"))
        fixture.now += 59_999
        assertEquals("T0", fixture.coordinator.refreshToken(pension, "T0"))
        assertEquals(1, fixture.issuances)
        fixture.now += 1
        assertEquals("T0", fixture.coordinator.refreshToken(pension, "T0"))
        assertEquals(2, fixture.issuances)
    }

    @Test
    fun sameValueRecoveryNeverReusesATokenInsideTheExpiryBuffer() = runBlocking {
        val fixture = Fixture()
        fixture.issue = { fixture.token("T0", 60_001) }
        assertEquals("T0", fixture.coordinator.refreshToken(pension, "T0"))
        fixture.now += 1

        assertEquals("T0", fixture.coordinator.refreshToken(pension, "T0"))
        assertEquals(2, fixture.issuances)
    }

    @Test
    fun refreshingPensionKeepsAnUnloadedOtherAccountsPersistedToken() = runBlocking {
        val fixture = Fixture()
        fixture.store[scope(pension)] = fixture.token("rejected")
        fixture.store[scope(ordinary)] = fixture.token("ordinary-valid")

        assertEquals("issued", fixture.coordinator.refreshToken(pension, "rejected"))
        assertEquals("ordinary-valid", fixture.store[scope(ordinary)]?.value)
        assertEquals("ordinary-valid", fixture.coordinator.requireToken(ordinary))
        assertEquals(1, fixture.issuances)
        assertEquals(listOf(scope(pension)), fixture.invalidations)
    }

    @Test
    fun lateRejectionReusesNewerPersistedTokenWithoutIssuingOrDeleting() = runBlocking {
        val fixture = Fixture()
        fixture.store[scope(pension)] = fixture.token("newer")

        assertEquals("newer", fixture.coordinator.refreshToken(pension, "older"))
        assertEquals(0, fixture.issuances)
        assertEquals(emptyList<String>(), fixture.invalidations)
    }

    @Test
    fun locallyExpiredReplacementIsNotReusedForALateRejection() = runBlocking {
        val fixture = Fixture()
        fixture.store[scope(pension)] = fixture.token("newer-but-expired", 59_999)

        assertEquals("issued", fixture.coordinator.refreshToken(pension, "older"))
        assertEquals(1, fixture.issuances)
        assertEquals(listOf(scope(pension)), fixture.invalidations)
    }

    @Test
    fun cacheUsesSixtySecondExpiryBufferAndSharesFreshlyIssuedToken() = runBlocking {
        val fixture = Fixture()
        fixture.store[scope(pension)] = fixture.token("cached", 60_001)
        assertEquals("cached", fixture.coordinator.requireToken(pension))
        assertEquals(0, fixture.issuances)

        fixture.now += 1
        assertEquals("issued", fixture.coordinator.requireToken(pension))
        assertEquals("issued", fixture.coordinator.requireToken(pension))
        assertEquals(1, fixture.issuances)
    }

    @Test
    fun failedIssuanceDoesNotRestoreRejectedTokenOrDeleteOtherAccount() = runBlocking {
        val fixture = Fixture()
        fixture.store[scope(pension)] = fixture.token("rejected")
        fixture.store[scope(ordinary)] = fixture.token("ordinary-valid")
        assertEquals("rejected", fixture.coordinator.requireToken(pension))
        fixture.issue = { null }

        assertNull(fixture.coordinator.refreshToken(pension, "rejected"))
        assertNull(fixture.store[scope(pension)])
        assertEquals("ordinary-valid", fixture.coordinator.requireToken(ordinary))
        assertEquals(1, fixture.issuances)

        fixture.issue = { fixture.token("recovered") }
        assertEquals("recovered", fixture.coordinator.requireToken(pension))
        assertEquals(2, fixture.issuances)
    }

    @Test
    fun concurrentFailedRefreshesReleaseTheLockAndNeverRestoreRejectedTokens() = runBlocking {
        withTimeout(5_000) {
            val fixture = Fixture()
            fixture.store[scope(pension)] = fixture.token("rejected")
            fixture.store[scope(ordinary)] = fixture.token("ordinary-valid")
            val issuing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.issue = {
                issuing.complete(Unit)
                release.await()
                null
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.refreshToken(pension, "rejected")
            }
            issuing.await()
            val sibling = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.refreshToken(pension, "rejected")
            }
            assertEquals(1, fixture.issuances)
            assertEquals(listOf(scope(pension)), fixture.invalidations)

            release.complete(Unit)
            assertEquals(listOf(null, null), listOf(first, sibling).awaitAll())
            // Failed grants are not negatively cached: each caller makes one bounded attempt.
            assertEquals(2, fixture.issuances)
            assertNull(fixture.store[scope(pension)])
            assertEquals("ordinary-valid", fixture.coordinator.requireToken(ordinary))
        }
    }

    private class Fixture {
        var now = 1_000_000L
        val store = mutableMapOf<String, AuthToken>()
        val invalidations = mutableListOf<String>()
        var issuances = 0
        var issue: suspend (AccountCredential) -> AuthToken? = { token("issued") }
        val coordinator = AuthTokenCoordinator(
            load = { store[scope(it)] },
            save = { account, token -> store[scope(account)] = token },
            invalidate = { account ->
                invalidations.add(scope(account))
                store.remove(scope(account))
                Unit
            },
            issue = { account ->
                issuances += 1
                issue(account)
            },
            nowMillis = { now },
            elapsedMillis = { now },
        )

        fun token(value: String, remainingMillis: Long = 3_600_000L) =
            AuthToken(value, now - 1_000, now + remainingMillis)
    }

    companion object {
        private fun scope(account: AccountCredential) = tokenScope(account.toAppCredentials())
        private fun account(id: String, cano: String, productCode: String) = AccountCredential(
            id = id,
            label = id,
            appKey = "fake-$id-key",
            appSecret = "fake-$id-secret",
            cano = cano,
            acntPrdtCd = productCode,
        )
    }
}
