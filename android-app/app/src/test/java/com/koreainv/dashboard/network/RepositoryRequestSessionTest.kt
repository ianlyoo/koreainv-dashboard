package com.koreainv.dashboard.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RepositoryRequestSessionTest {
    @Test
    fun closeCancelsEveryActiveLoadAndClearsCachedData() = runBlocking<Unit> {
        withTimeout(5_000) {
            val session = RepositoryRequestSession("cached")
            val calls = List(3) { FakeCall() }
            val loads = calls.map { call ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    session.run { call.awaitTextResponse() }
                }
            }
            assertEquals("cached", session.read())
            assertTrue(session.close())
            assertFalse(session.close())
            assertNull(session.read())
            calls.forEach { assertTrue(it.isCanceled()) }
            loads.forEach { assertCancelled { it.await() } }
        }
    }

    @Test
    fun cancelledCallerCancelsItsHttpCallWithoutClosingOtherLoads() = runBlocking<Unit> {
        withTimeout(5_000) {
            val session = RepositoryRequestSession("cached")
            val cancelledCall = FakeCall()
            val survivingCall = FakeCall()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                session.run { cancelledCall.awaitTextResponse() }
            }
            val survivor = async(start = CoroutineStart.UNDISPATCHED) {
                session.run { survivingCall.awaitTextResponse() }
            }
            cancelled.cancel()
            assertTrue(cancelledCall.isCanceled())
            assertFalse(survivingCall.isCanceled())
            survivingCall.respond("ok".toResponseBody())
            assertEquals("ok", survivor.await().text)
            assertEquals("cached", session.read())
            session.close()
        }
    }

    @Test
    fun closedSessionRejectsNewLoadsBeforeTheyEnqueueAndRejectsCacheWrites() = runBlocking<Unit> {
        val session = RepositoryRequestSession("cached")
        val call = FakeCall()
        session.close()
        assertCancelled { session.run { call.awaitTextResponse() } }
        assertFalse(call.isExecuted())
        assertCancelled { session.update { "late" } }
        assertNull(session.read())
    }

    @Test
    fun closeWinsWhenAnAlreadyStartedCacheUpdateFinishesLate() = runBlocking<Unit> {
        val session = RepositoryRequestSession("cached")
        assertCancelled {
            session.update {
                // Models shutdown between reading a cache generation and publishing it.
                session.close()
                "late response"
            }
        }
        assertNull(session.read())
    }

    @Test
    fun lateHttpSuccessAfterCloseCannotPublishAndStillClosesResponseBody() = runBlocking<Unit> {
        withTimeout(5_000) {
            val session = RepositoryRequestSession("cached")
            val call = FakeCall()
            val body = TrackingBody()
            val load = async(start = CoroutineStart.UNDISPATCHED) {
                session.run {
                    val response = call.awaitTextResponse()
                    session.update { response.text }
                }
            }
            session.close()
            call.respond(body)
            assertCancelled { load.await() }
            assertTrue(body.closed.get())
            assertNull(session.read())
        }
    }

    @Test
    fun cancellationDuringResponseBodyReadCancelsCallAndReleasesBody() = runBlocking<Unit> {
        withTimeout(5_000) {
            val call = FakeCall()
            val reading = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val body = TrackingBody {
                reading.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS))
                throw IOException("cancelled body read")
            }
            call.onCancel = { cancelled.countDown() }
            val load = async(start = CoroutineStart.UNDISPATCHED) { call.awaitTextResponse() }
            val callbackThread = thread { call.respond(body) }
            try {
                assertTrue(reading.await(5, TimeUnit.SECONDS))
                load.cancel()
                assertCancelled { load.await() }
            } finally {
                cancelled.countDown()
                callbackThread.join(5_000)
            }
            assertFalse(callbackThread.isAlive)
            assertTrue(call.isCanceled())
            assertTrue(body.closed.get())
        }
    }

    @Test
    fun tokenIssuedLateAfterCloseIsNotPersisted() = runBlocking<Unit> {
        withTimeout(5_000) {
            val session = RepositoryRequestSession("cached")
            val release = CompletableDeferred<Unit>()
            var saved = false
            val account = AccountCredential(
                id = "fake", label = "fake", appKey = "fake-key", appSecret = "fake-secret",
                cano = "12345678", acntPrdtCd = "01",
            )
            val coordinator = AuthTokenCoordinator(
                load = { null },
                save = { _, _ -> session.ensureOpen(); saved = true },
                invalidate = {},
                issue = {
                    // Model an issuer that has already committed remotely and returns late.
                    withContext(NonCancellable) { release.await() }
                    AuthToken("fake-token", 0L, Long.MAX_VALUE)
                },
            )
            val load = async(start = CoroutineStart.UNDISPATCHED) {
                session.run { coordinator.requireToken(account) }
            }
            session.close()
            release.complete(Unit)
            assertCancelled { load.await() }
            assertFalse(saved)
        }
    }

    @Test
    fun httpFailureRetainsItsCauseAndDoesNotClearSessionCache() = runBlocking<Unit> {
        withTimeout(5_000) {
            val session = RepositoryRequestSession("cached")
            val call = FakeCall()
            val load = async(start = CoroutineStart.UNDISPATCHED) {
                session.run { runCatching { call.awaitTextResponse() } }
            }
            val failure = IOException("network unavailable", IOException("connection reset"))
            call.fail(failure)
            val received = load.await().exceptionOrNull()
            // Coroutine stack-trace recovery can copy the exception and wrap its original.
            assertTrue(received is IOException)
            assertEquals("network unavailable", received?.message)
            assertEquals("connection reset", generateSequence(received) { it.cause }.last().message)
            assertEquals("cached", session.read())
            session.close()
        }
    }

    private suspend fun assertCancelled(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected; cancellation is not converted into an empty success or retry.
        }
    }

    private class FakeCall : Call {
        private val request = Request.Builder().url("https://example.invalid/test").build()
        private lateinit var callback: Callback
        private var executed = false
        private val cancelled = AtomicBoolean(false)
        var onCancel: () -> Unit = {}
        override fun request(): Request = request
        override fun execute(): Response = error("Blocking execution must not be used")
        override fun enqueue(responseCallback: Callback) {
            check(!executed)
            executed = true
            callback = responseCallback
        }
        override fun cancel() {
            cancelled.set(true)
            onCancel()
        }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall()
        fun respond(body: ResponseBody) {
            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build())
        }
        fun fail(error: IOException) = callback.onFailure(this, error)
    }

    private class TrackingBody(onRead: (() -> Unit)? = null) : ResponseBody() {
        val closed = AtomicBoolean(false)
        private val buffer = Buffer().writeUtf8("late")
        private val trackedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                onRead?.invoke()
                return buffer.read(sink, byteCount)
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed.set(true) }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = trackedSource
    }
}
