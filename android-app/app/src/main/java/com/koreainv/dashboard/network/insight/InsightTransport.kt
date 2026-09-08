package com.koreainv.dashboard.network.insight

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val INSIGHT_ORIGIN = "https://saveticker.com"
internal data class InsightHttpResponse(val code: Int, val body: String, val cookies: List<Cookie>, val retryAfter: String?)
internal interface InsightTransport {
    suspend fun execute(request: Request): InsightHttpResponse
    fun cancelAll()
    fun close()
}
/** No shared broker client, cookie persistence, redirect, retry interceptor, cache, or HTTP logging. */
internal class InsightOkHttpTransport : InsightTransport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES)).build()
    override suspend fun execute(request: Request): InsightHttpResponse = suspendCancellableCoroutine { continuation ->
        require(request.url.scheme == "https" && request.url.host == "saveticker.com" && request.url.port == 443)
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = response.body
                        val text = if (body == null) "" else {
                            require(body.contentLength() <= InsightParser.MAX_BYTES) { "Response too large" }
                            val source = body.source()
                            require(!source.request(InsightParser.MAX_BYTES.toLong() + 1)) { "Response too large" }
                            source.readUtf8()
                        }
                        InsightHttpResponse(response.code, text, Cookie.parseAll(request.url, response.headers), response.header("Retry-After"))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    override fun cancelAll() { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    override fun close() { cancelAll(); client.dispatcher.executorService.shutdown() }
}
