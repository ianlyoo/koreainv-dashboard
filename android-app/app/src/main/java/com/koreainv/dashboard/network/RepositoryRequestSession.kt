package com.koreainv.dashboard.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Response

/** Closing atomically discards cached data and cancels loads without waiting for I/O. */
internal class RepositoryRequestSession<T : Any>(initialCache: T) {
    private data class State<T>(val cache: T, val jobs: Set<Job> = emptySet())
    private val state = AtomicReference<State<T>?>(State(initialCache))

    fun read(): T? = state.get()?.cache

    fun ensureOpen() {
        if (state.get() == null) throw CancellationException("Repository closed")
    }

    fun update(transform: (T) -> T) {
        while (true) {
            val before = state.get() ?: throw CancellationException("Repository closed")
            if (state.compareAndSet(before, before.copy(cache = transform(before.cache)))) return
        }
    }

    suspend fun <R> run(block: suspend () -> R): R = coroutineScope {
        val job = coroutineContext[Job]!!
        job.ensureActive()
        while (true) {
            val before = state.get() ?: throw CancellationException("Repository closed")
            if (state.compareAndSet(before, before.copy(jobs = before.jobs + job))) break
        }
        try {
            job.ensureActive()
            val result = block()
            job.ensureActive()
            ensureOpen()
            result
        } finally {
            while (true) {
                val before = state.get() ?: break
                if (state.compareAndSet(before, before.copy(jobs = before.jobs - job))) break
            }
        }
    }

    /** Returns true only for the caller responsible for any further resource cleanup. */
    fun close(): Boolean {
        val previous = state.getAndSet(null) ?: return false
        previous.jobs.forEach { it.cancel(CancellationException("Repository closed")) }
        return true
    }
}

internal data class HttpTextResponse(val code: Int, val headers: Headers, val text: String) {
    val isSuccessful: Boolean get() = code in 200..299
    fun header(name: String): String? = headers[name]
}

/** Keep cancellation attached until the entire body has been consumed and closed. */
internal suspend fun Call.awaitTextResponse(): HttpTextResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    if (!continuation.isActive) return@suspendCancellableCoroutine
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            try {
                val result = response.use {
                    HttpTextResponse(it.code, it.headers, it.body?.string().orEmpty())
                }
                continuation.resume(result)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    })
}
