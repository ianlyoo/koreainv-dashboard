package com.koreainv.dashboard.network.insight

import kotlinx.coroutines.*
import okhttp3.Cookie
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class InsightSessionRepositoryTest {
    private open class MemoryStore : InsightSecretStore {
        override val profileId = "00000000-0000-0000-0000-000000000001"
        @Volatile var saved: InsightCredentials? = null
        @Volatile var pending = false
        @Volatile var validUntil = Long.MAX_VALUE
        var clock: () -> Long = System::currentTimeMillis
        var secure = true
        override fun hasStored() = saved != null
        override fun canRemember() = secure
        override fun read(): InsightCredentials? {
            val value = saved ?: return null
            if (pending || validUntil <= clock()) throw InsightStoreException()
            return InsightCredentials(value.email, value.password, validUntil)
        }
        override fun write(credentials: InsightCredentials) { beginUse(); saved = credentials }
        override fun beginUse() { pending = true }
        override fun finishUse(validUntilMillis: Long) { validUntil = validUntilMillis; pending = false }
        override fun delete() { beginUse(); saved = null }
    }
    private open class FakeTransport : InsightTransport {
        val calls = AtomicInteger()
        val logins = AtomicInteger()
        var offline = false
        var rejectOld = false
        var alwaysReject = false
        var limited = false
        val fixture = InsightParser.root(javaClass.getResourceAsStream("/insight/AVGO.json")!!.bufferedReader().use { it.readText() })
        override suspend fun execute(request: Request): InsightHttpResponse {
            calls.incrementAndGet()
            if (offline) throw IOException("Offline")
            if (request.url.encodedPath.endsWith("/login")) {
                val number = logins.incrementAndGet(); delay(10)
                return InsightHttpResponse(200, """{"user_info":{}}""", listOf(Cookie.Builder().name("access_token").value("test$number").hostOnlyDomain("saveticker.com").path("/").secure().httpOnly().build()), null)
            }
            delay(10)
            if (limited) return InsightHttpResponse(429, "", emptyList(), "120")
            if (alwaysReject || (rejectOld && request.header("Cookie")?.contains("test1") == true)) return InsightHttpResponse(401, "", emptyList(), null)
            val endpoint = request.url.pathSegments.last()
            val key = mapOf("key-metrics" to "key_metrics", "revenue-trend" to "revenue", "sec-insider" to "insider", "company" to "news")[endpoint] ?: endpoint
            val body = if (endpoint == "bars") """{"bars":[{"time":1757030400,"open":10,"high":12,"low":9,"close":11,"volume":0}]}""" else fixture.get(key).toString()
            return InsightHttpResponse(200, body, emptyList(), null)
        }
        override fun cancelAll() {}
        override fun close() {}
    }
    @Test fun unlockDoesNotCallNetworkRememberDefaultsOffAndLockErasesMemory() = runBlocking {
        val store = MemoryStore(); val http = FakeTransport(); val session = InsightSessionManager(store, http)
        session.unlock(); assertEquals(0, http.calls.get())
        assertTrue(session.connect("test@example.com", "test-only").success); assertNull(store.saved)
        val repository = SaveTickerInsightRepository(session)
        assertEquals(InsightSnapshotStatus.AVAILABLE, repository.fetch("AVGO").status)
        session.lock(); assertNull(repository.peek("AVGO")); assertFalse(session.state.value.hasCredentials)
        session.unlock(); assertFalse(session.state.value.hasCredentials); repository.close()
    }
    @Test fun mergedFetchAndConcurrent401OnlyLogInOnceThenCacheAndOfflineStale() = runBlocking {
        val store = MemoryStore(); val http = FakeTransport(); var now = 1000L
        val session = InsightSessionManager(store, http) { now }; session.unlock(); session.connect("test@example.com", "test-only")
        http.rejectOld = true
        val repository = SaveTickerInsightRepository(session)
        val snapshots = coroutineScope { List(4) { async { repository.fetch("AVGO") } }.awaitAll() }
        assertTrue(snapshots.all { it.status == InsightSnapshotStatus.AVAILABLE }); assertEquals(2, http.logins.get())
        val before = http.calls.get(); repository.fetch("AVGO"); assertEquals(before, http.calls.get())
        http.offline = true; now += 300001
        val stale = repository.fetch("AVGO"); assertEquals(InsightSnapshotStatus.OFFLINE, stale.status); assertTrue(stale.isStale)
        assertEquals(snapshots.first().fetchedAtMillis, stale.fetchedAtMillis)
        repository.close()
    }
    @Test fun lateLoginCannotReviveLockedSessionOrPersistSecret() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val http = object : FakeTransport() {
            override suspend fun execute(request: Request): InsightHttpResponse = withContext(NonCancellable) {
                entered.complete(Unit); finish.await(); super.execute(request)
            }
        }
        val store = MemoryStore(); val session = InsightSessionManager(store, http); session.unlock()
        val pending = async { session.connect("test@example.com", "test-only", true) }
        entered.await(); session.lock(); finish.complete(Unit)
        try { pending.await(); fail("Revoked operation returned") } catch (_: CancellationException) { }
        assertFalse(session.state.value.isUnlocked); assertFalse(session.state.value.isConnected); assertNull(store.saved)
    }
    @Test fun noDeviceLockRejectsRememberAndUnsupportedMarketDoesNotNeedLogin() = runBlocking {
        val store = MemoryStore().apply { secure = false }; val http = FakeTransport(); val session = InsightSessionManager(store, http)
        val repository = SaveTickerInsightRepository(session)
        assertEquals(InsightSnapshotStatus.UNSUPPORTED, repository.fetch("005930", "KOR").status)
        session.unlock(); assertFalse(session.connect("test@example.com", "test-only", true).success); assertEquals(0, http.calls.get())
        repository.close()
    }
    @Test fun retryAfterCannotBeBypassedWithForceRefreshAndCacheIsBounded() = runBlocking {
        val http = FakeTransport(); val session = InsightSessionManager(MemoryStore(), http) { 1000L }; session.unlock(); session.connect("test@example.com", "test-only")
        val repository = SaveTickerInsightRepository(session, 1)
        repository.fetch("AVGO"); repository.fetch("AAPL"); assertNull(repository.peek("AVGO"))
        http.limited = true; val result = repository.fetch("AAPL", forceRefresh = true)
        assertEquals(InsightSnapshotStatus.RATE_LIMITED, result.status); assertEquals(121000L, result.retryAtMillis)
        val before = http.calls.get(); repository.fetch("AAPL", forceRefresh = true); assertEquals(before, http.calls.get())
        repository.clearCache(); assertTrue(session.state.value.hasCredentials); repository.close()
    }
    @Test fun failedRefreshRevokesCredentialsAndStoredSecret() = runBlocking {
        val store = MemoryStore(); val http = FakeTransport(); val session = InsightSessionManager(store, http)
        session.unlock(); session.connect("test@example.com", "test-only", true); http.alwaysReject = true
        val repository = SaveTickerInsightRepository(session)
        try { repository.fetch("AVGO"); fail("Must expire") } catch (_: Exception) { }
        assertEquals(InsightConnectionStatus.EXPIRED, session.state.value.status); withTimeout(2000) { while (store.saved != null) delay(1) }; assertNull(store.saved)
        assertNull(repository.peek("AVGO")); assertEquals(2, http.logins.get()); repository.close()
    }
    @Test fun slowKeystoreReadNeverBlocksLockAndCannotUnlockAfterRevocation() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = object : MemoryStore() {
            override fun read(): InsightCredentials? {
                entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS)
                return InsightCredentials("test@example.com", "test-only")
            }
        }
        val session = InsightSessionManager(store, FakeTransport())
        val pending = async(Dispatchers.IO) { session.unlock() }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val start = System.nanoTime(); session.lock()
        assertTrue("Lock must not wait for store.read", (System.nanoTime() - start) / 1_000_000 < 250)
        release.countDown(); pending.await()
        assertFalse(session.state.value.isUnlocked); assertFalse(session.state.value.hasCredentials)
    }
    @Test fun slowKeystoreWriteCannotPersistAfterLock() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = object : MemoryStore() {
            override fun write(credentials: InsightCredentials) {
                entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS); super.write(credentials)
            }
        }
        val session = InsightSessionManager(store, FakeTransport()); session.unlock()
        val pending = async(Dispatchers.IO) { session.connect("test@example.com", "test-only", true) }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val start = System.nanoTime(); session.lock()
        assertTrue("Lock must not wait for store.write", (System.nanoTime() - start) / 1_000_000 < 250)
        release.countDown()
        try { pending.await(); fail("Revoked write returned") } catch (_: CancellationException) { }
        assertFalse(session.state.value.isUnlocked); assertNull(store.saved)
    }

    @Test fun rememberedCompletedRequestsRemainRecoverableAfterRestart() = runBlocking {
        val store = MemoryStore(); val session = InsightSessionManager(store, FakeTransport())
        session.unlock(); assertTrue(session.connect("test@example.com", "test-only", true).success)
        val repository = SaveTickerInsightRepository(session); repository.fetch("AVGO")
        assertFalse(store.pending)
        val restarted = InsightSessionManager(store, FakeTransport()); restarted.unlock()
        assertTrue(restarted.state.value.hasCredentials); assertFalse(restarted.state.value.isConnected)
        repository.close()
    }
    @Test fun restartedManagerRefusesExpiredSecretWhileDeletionIsStillBlocked() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = object : MemoryStore() {
            @Volatile var blockDelete = false
            override fun delete() {
                if (blockDelete) { entered.countDown(); release.await(3, java.util.concurrent.TimeUnit.SECONDS) }
                super.delete()
            }
        }
        val http = FakeTransport(); val session = InsightSessionManager(store, http)
        session.unlock(); session.connect("test@example.com", "test-only", true)
        store.blockDelete = true; http.alwaysReject = true
        val repository = SaveTickerInsightRepository(session)
        val pending = async(Dispatchers.IO) { runCatching { repository.fetch("AVGO") } }
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertNotNull("Ciphertext cleanup is deliberately unfinished", store.saved)
            assertTrue("HTTP was preceded by a durable pending record", store.pending)
            val restartedHttp = FakeTransport(); val restarted = InsightSessionManager(store, restartedHttp)
            restarted.unlock()
            assertFalse(restarted.state.value.hasCredentials); assertEquals(0, restartedHttp.calls.get())
        } finally { release.countDown(); pending.await(); repository.close() }
    }
    @Test fun restartedManagerRefusesExpiredSecretWhenCiphertextDeletionFails() = runBlocking {
        val store = object : MemoryStore() {
            @Volatile var failDelete = false
            override fun delete() {
                if (failDelete) throw IOException("Simulated persistent deletion failure")
                super.delete()
            }
        }
        val http = FakeTransport(); val session = InsightSessionManager(store, http)
        session.unlock(); session.connect("test@example.com", "test-only", true)
        store.failDelete = true; http.alwaysReject = true
        val repository = SaveTickerInsightRepository(session)
        runCatching { repository.fetch("AVGO") }
        assertNotNull(store.saved); assertTrue(store.pending)
        val restarted = InsightSessionManager(store, FakeTransport()); restarted.unlock()
        assertFalse(restarted.state.value.hasCredentials)
        repository.close()
    }
    @Test fun persistedCookieExpiryRejectsRestartWithoutWaitingForDeletion() = runBlocking {
        var now = 1000L
        val store = MemoryStore().apply { clock = { now } }
        val http = object : FakeTransport() {
            override suspend fun execute(request: Request): InsightHttpResponse {
                val response = super.execute(request)
                return if (request.url.encodedPath.endsWith("/login")) response.copy(cookies = response.cookies.map { it.newBuilderForTest(5000) }) else response
            }
        }
        val session = InsightSessionManager(store, http) { now }; session.unlock()
        assertTrue(session.connect("test@example.com", "test-only", true).success)
        now = 5001
        val restarted = InsightSessionManager(store, FakeTransport()) { now }; restarted.unlock()
        assertFalse(restarted.state.value.hasCredentials)
    }
    private fun Cookie.newBuilderForTest(expiry: Long) = Cookie.Builder().name(name).value(value).hostOnlyDomain(domain).path(path).secure().expiresAt(expiry).build()

    @Test fun failedDurableMarkPreventsAuthenticatedHttpFromStarting() = runBlocking {
        val store = object : MemoryStore() {
            var failMark = false
            override fun beginUse() { if (failMark) throw IOException("Lease write failed"); super.beginUse() }
        }
        val http = FakeTransport(); val session = InsightSessionManager(store, http)
        session.unlock(); assertTrue(session.connect("test@example.com", "test-only", true).success)
        store.failMark = true
        val before = http.calls.get(); val repository = SaveTickerInsightRepository(session)
        repository.fetch("AVGO")
        assertEquals("No HTTP without a durable pending record", before, http.calls.get())
        repository.close()
    }

    @Test fun failedExpiryCleanupNeverBlocksSubsequentBrokerPinUnlock() = runBlocking {
        val store = object : MemoryStore() {
            @Volatile var failDelete = false
            override fun delete() { if (failDelete) throw IOException("Cleanup unavailable"); super.delete() }
        }
        val http = FakeTransport(); val session = InsightSessionManager(store, http)
        session.unlock(); session.connect("test@example.com", "test-only", true)
        store.failDelete = true; http.alwaysReject = true
        val repository = SaveTickerInsightRepository(session)
        runCatching { repository.fetch("AVGO") }
        session.lock(); session.unlock()
        assertTrue("Optional SaveTicker failure must not block app unlock", session.state.value.isUnlocked)
        assertFalse(session.state.value.hasCredentials); assertNotNull(session.state.value.message)
        repository.close()
    }

}
