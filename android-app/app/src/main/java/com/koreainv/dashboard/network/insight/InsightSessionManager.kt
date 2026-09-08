package com.koreainv.dashboard.network.insight

import android.content.Context
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class InsightLease(val profileId: String, val connectionId: String, val generation: Long)
internal class InsightExpiredException : Exception("SaveTicker 연결이 만료되었습니다. 다시 연결해 주세요.")
internal class InsightRateLimitException(val retryAt: Long) : Exception("요청이 많습니다. 잠시 후 다시 시도해 주세요.")

class InsightSessionManager internal constructor(private val store: InsightSecretStore, private val transport: InsightTransport, internal val now: () -> Long = System::currentTimeMillis) {
    private constructor(context: Context) : this(InsightCredentialStore(context.applicationContext), InsightOkHttpTransport())
    private val gate = Any()
    private val authentication = Mutex()
    private val persistence = Mutex()
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persisted = false
    private var secretRevision = 0L
    private var pendingForget = false
    private var rememberedValidUntil = Long.MAX_VALUE
    private val credentialUses = mutableMapOf<Long, Int>()
    private val rejectedProofRevisions = mutableSetOf<Long>()
    private val forbiddenProofRevisions = mutableSetOf<Long>()
    private val mutableState = MutableStateFlow(InsightConnectionState())
    val state: StateFlow<InsightConnectionState> = mutableState.asStateFlow()
    private var generation = 0L
    private var profileId: String? = null
    private var connectionId = UUID.randomUUID().toString()
    private var credentials: InsightCredentials? = null
    private var authEpoch = 0L
    private var authenticating = false
    private var retryAt = 0L
    private var authFailureUntil = 0L
    private val cookies = mutableListOf<Cookie>()
    private val jobs = mutableSetOf<Job>()
    private val invalidators = mutableSetOf<() -> Unit>()

    /** Call only after the host's app PIN was verified. Never starts HTTP. */
    suspend fun unlock() = withContext(Dispatchers.IO) {
        val expected = synchronized(gate) {
            if (mutableState.value.isUnlocked) return@withContext
            revoke(); generation
        }
        var message: String? = null
        var loadedProfile: String? = null
        val loaded = persistence.withLock {
            try {
                loadedProfile = store.profileId
                val forgetRevision = synchronized(gate) { secretRevision.takeIf { pendingForget } }
                if (forgetRevision != null) {
                    store.delete()
                    synchronized(gate) { if (secretRevision == forgetRevision) pendingForget = false }
                }
                store.read()
            } catch (_: Exception) {
                message = "SaveTicker 저장 정보를 열거나 정리할 수 없습니다. 앱은 사용할 수 있으며 SaveTicker는 다시 연결해야 합니다."
                null
            }
        }
        currentCoroutineContext().ensureActive()
        synchronized(gate) {
            if (generation != expected) return@synchronized
            profileId = loadedProfile; credentials = loaded; persisted = loaded != null; rememberedValidUntil = loaded?.validUntilMillis ?: Long.MAX_VALUE
            mutableState.value = InsightConnectionState(isUnlocked = true, hasCredentials = loaded != null, remember = persisted,
                emailMasked = loaded?.email?.let(::maskEmail), status = if (loaded != null) InsightConnectionStatus.READY else InsightConnectionStatus.DISCONNECTED, message = message)
        }
    }
    /** Synchronous revocation never performs or waits for disk/Keystore work. */
    fun lock() = synchronized(gate) { revoke(); mutableState.value = InsightConnectionState(remember = persisted) }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val expected = synchronized(gate) {
            val unlocked = mutableState.value.isUnlocked; revoke(); persisted = false; secretRevision++; pendingForget = true
            mutableState.value = InsightConnectionState(isUnlocked = unlocked, status = if (unlocked) InsightConnectionStatus.DISCONNECTED else InsightConnectionStatus.LOCKED)
            secretRevision
        }
        deleteStored(expected)
    }
    suspend fun reset() = disconnect()

    suspend fun connect(email: String, password: String, remember: Boolean = false): InsightConnectResult = withContext(Dispatchers.IO) {
        val normalized = email.trim()
        if (normalized.length !in 3..320 || !normalized.contains('@') || password.length !in 1..4096) return@withContext InsightConnectResult(false, "이메일과 비밀번호를 확인해 주세요.")
        if (remember && !store.canRemember()) return@withContext InsightConnectResult(false, "연결 정보를 저장하려면 기기 화면 잠금을 설정해 주세요.")
        val lease = synchronized(gate) {
            if (!mutableState.value.isUnlocked) return@withContext InsightConnectResult(false, "앱 잠금을 먼저 해제해 주세요.")
            if (profileId == null) return@withContext InsightConnectResult(false, "기기 연결 저장소를 준비할 수 없습니다. 앱을 잠근 후 다시 시도해 주세요.")
            if (now() < retryAt) return@withContext InsightConnectResult(false, "요청이 많습니다. 잠시 후 다시 시도해 주세요.")
            revoke(); persisted = false; secretRevision++; pendingForget = true; credentials = InsightCredentials(normalized, password)
            mutableState.value = InsightConnectionState(true, true, false, false, maskEmail(normalized), InsightConnectionStatus.CONNECTING)
            leaseUnsafe()
        }
        try {
            persistence.withLock {
                adopt(lease) {}
                try { store.delete() } catch (_: InsightCleanupException) { /* Old secret is durably unusable; session-only connection remains possible. */ }
                adopt(lease) { pendingForget = false }
            }
            authenticate(lease, null)
            if (remember) {
                val saved = persistence.withLock {
                    val secret = adopt(lease) { requireNotNull(credentials) }
                    var committed = false
                    try {
                        store.write(secret)
                        currentCoroutineContext().ensureActive()
                        val expiry = adopt(lease) { currentCookieExpiry() }
                        if (synchronized(gate) { credentialUses[secretRevision].orZero() == 0 }) store.finishUse(expiry)
                        adopt(lease) { persisted = true; rememberedValidUntil = expiry; mutableState.value = mutableState.value.copy(remember = true) }
                        committed = true
                        true
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { false }
                    finally { if (!committed) try { store.delete() } catch (_: InsightCleanupException) { /* Pending record prevents recovery. */ } }
                }
                if (!saved) {
                    val message = "연결되었습니다. 기기 저장에 실패하여 이번 잠금 해제 동안만 유지합니다."
                    adopt(lease) { mutableState.value = mutableState.value.copy(remember = false, message = message) }
                    return@withContext InsightConnectResult(true, message)
                }
            }
            adopt(lease) {}
            InsightConnectResult(true)
        } catch (e: CancellationException) { throw e }
        catch (_: InsightRateLimitException) { InsightConnectResult(false, "요청이 많습니다. 잠시 후 다시 시도해 주세요.") }
        catch (_: Exception) {
            synchronized(gate) {
                if (valid(lease)) {
                    // A failure before HTTP (for example an unwritable store) is not
                    // evidence that the previously saved login expired or was deleted.
                    revoke(); persisted = false
                    mutableState.value = InsightConnectionState(isUnlocked = true, status = InsightConnectionStatus.ERROR,
                        message = "연결을 완료하지 못했습니다. 입력 정보와 기기 저장 상태를 확인해 주세요.")
                }
            }
            InsightConnectResult(false, "SaveTicker 연결에 실패했습니다. 입력 정보와 기기 저장 상태를 확인하고 다시 연결해 주세요.")
        }
    }
    private suspend fun deleteStored(expected: Long) = withContext(NonCancellable + Dispatchers.IO) {
        persistence.withLock {
            if (synchronized(gate) { secretRevision == expected }) {
                store.delete()
                synchronized(gate) { if (secretRevision == expected) pendingForget = false }
            }
        }
    }
    internal fun lease(): InsightLease = synchronized(gate) {
        if (!authenticating && ((persisted && rememberedValidUntil <= now()) || (mutableState.value.isConnected && cookies.none { it.name == "access_token" && it.expiresAt > now() }))) { expire(); throw InsightExpiredException() }
        if (!mutableState.value.isUnlocked || credentials == null) throw InsightExpiredException(); leaseUnsafe() }
    private fun leaseUnsafe() = InsightLease(requireNotNull(profileId), connectionId, generation)
    internal fun valid(lease: InsightLease): Boolean = synchronized(gate) { mutableState.value.isUnlocked && credentials != null && lease == leaseUnsafe() }
    internal fun <T> adopt(lease: InsightLease, action: () -> T): T = synchronized(gate) { checkLease(lease); action() }
    private fun checkLease(lease: InsightLease) { if (!valid(lease)) throw CancellationException("Insight session revoked") }
    internal fun onInvalidated(callback: () -> Unit): () -> Unit { synchronized(gate) { invalidators.add(callback) }; return { synchronized(gate) { invalidators.remove(callback) } } }
    private fun revoke() {
        generation++; connectionId = UUID.randomUUID().toString(); authEpoch++; authenticating = false; credentials = null; cookies.clear(); retryAt = 0; authFailureUntil = 0
        jobs.toList().forEach { it.cancel(CancellationException("Insight session revoked")) }; jobs.clear(); transport.cancelAll()
        invalidators.toList().forEach { it() }
    }
    private fun expire() {
        revoke(); persisted = false; secretRevision++; pendingForget = true
        mutableState.value = InsightConnectionState(isUnlocked = true, status = InsightConnectionStatus.EXPIRED, message = "연결이 만료되었습니다. 다시 연결해 주세요.")
        val expected = secretRevision
        storageScope.launch {
            try { deleteStored(expected) } catch (_: Exception) {
                synchronized(gate) {
                    if (secretRevision == expected) mutableState.value = mutableState.value.copy(message = "연결이 만료되었습니다. 저장 정보 정리가 완료되지 않아 다시 연결해야 합니다.")
                }
            }
        }
    }
    internal fun retryAtMillis(): Long = synchronized(gate) { retryAt }
    private fun currentCookieExpiry() = cookies.filter { it.name == "access_token" }.minOfOrNull { it.expiresAt } ?: 0L
    private fun Int?.orZero() = this ?: 0
    private fun cookieHeader(url: okhttp3.HttpUrl): String {
        cookies.removeAll { it.expiresAt <= now() }
        return cookies.filter { it.matches(url) }.joinToString("; ") { "${it.name}=${it.value}" }
    }
    private fun adoptCookies(incoming: List<Cookie>) {
        cookies.removeAll { it.expiresAt <= now() }
        incoming.take(32).filter { it.domain == "saveticker.com" && it.secure && it.name.length <= 128 && it.value.length <= 4096 }.forEach { c ->
            cookies.removeAll { it.name == c.name && it.path == c.path && it.domain == c.domain }
            if (c.expiresAt > now()) cookies.add(c)
        }
        while (cookies.size > 32) cookies.removeAt(0)
    }
    private suspend fun send(lease: InsightLease, path: String, login: InsightCredentials? = null): Pair<InsightHttpResponse, Long> = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        val (request, epoch) = synchronized(gate) {
            checkLease(lease)
            if (now() < retryAt) throw InsightRateLimitException(retryAt)
            val url = (INSIGHT_ORIGIN + path).toHttpUrl()
            require(url.host == "saveticker.com" && url.isHttps && url.port == 443)
            val builder = Request.Builder().url(url).header("Accept", "application/json").header("Origin", INSIGHT_ORIGIN)
                .header("Referer", "$INSIGHT_ORIGIN/login").header("User-Agent", "KoreaInvDashboard-Android/Insight")
            if (login != null) builder.post(JsonObject().apply { addProperty("email", login.email); addProperty("password", login.password) }.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            else cookieHeader(url).takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            jobs.add(job); builder.build() to authEpoch
        }
        try {
            currentCoroutineContext().ensureActive()
            val response = transport.execute(request)
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                checkLease(lease)
                if (response.code == 403) forbiddenProofRevisions.add(secretRevision)
                if (response.code == 401 && epoch == authEpoch) rejectedProofRevisions.add(secretRevision)
                if (response.code == 429) {
                    retryAt = parseRetryAfter(response.retryAfter, now())
                    mutableState.value = mutableState.value.copy(status = InsightConnectionStatus.RATE_LIMITED, message = "요청이 많습니다. 잠시 후 다시 시도해 주세요.")
                    throw InsightRateLimitException(retryAt)
                }
                if (epoch == authEpoch && response.code in 200..299) {
                    adoptCookies(response.cookies)
                    if (persisted) rememberedValidUntil = currentCookieExpiry()
                    if (mutableState.value.isConnected) mutableState.value = mutableState.value.copy(status = InsightConnectionStatus.CONNECTED, message = null)
                }
            }
            response to epoch
        } finally { synchronized(gate) { jobs.remove(job) } }
    }
    private suspend fun authenticate(lease: InsightLease, failedEpoch: Long?) = authentication.withLock {
        val login = synchronized(gate) {
            checkLease(lease)
            if (failedEpoch != null && authEpoch != failedEpoch && mutableState.value.isConnected) return@withLock
            if (failedEpoch == null && mutableState.value.isConnected) return@withLock
            if (now() < authFailureUntil) throw java.io.IOException("Authentication temporarily unavailable")
            authenticating = true; cookies.clear(); requireNotNull(credentials)
        }
        val response = try { send(lease, "/api/auth/login", login).first } catch (e: java.io.IOException) {
            synchronized(gate) { if (valid(lease)) authFailureUntil = now() + 60_000 }
            throw e
        } finally { synchronized(gate) { if (valid(lease)) authenticating = false } }
        val validBody = response.code == 200 && runCatching { InsightParser.root(response.body).get("user_info")?.isJsonObject == true }.getOrDefault(false)
        synchronized(gate) {
            checkLease(lease)
            if (!validBody || cookies.none { it.name == "access_token" && it.expiresAt > now() && it.matches((INSIGHT_ORIGIN + "/api/stocks/api/v1/tickers/AAPL/header").toHttpUrl()) }) { expire(); throw InsightExpiredException() }
            authEpoch++; rejectedProofRevisions.remove(secretRevision)
            mutableState.value = mutableState.value.copy(isConnected = true, status = InsightConnectionStatus.CONNECTED, message = null)
        }
    }
    internal suspend fun get(lease: InsightLease, path: String): InsightHttpResponse = withCredentialUse(lease) {
        authenticate(lease, null)
        var (response, epoch) = send(lease, path)
        if (response.code == 401) {
            try { authenticate(lease, epoch) } catch (e: CancellationException) { throw e } catch (e: InsightRateLimitException) { throw e } catch (e: Exception) { synchronized(gate) { if (valid(lease)) expire() }; throw e }
            response = send(lease, path).first
        }
        if (response.code == 401 || response.code == 403) { synchronized(gate) { if (valid(lease)) expire() }; throw InsightExpiredException() }
        synchronized(gate) {
            checkLease(lease)
            if (currentCookieExpiry() <= now()) { expire(); throw InsightExpiredException() }
        }
        response
    }
    /**
     * A durable pending record precedes every remembered authenticated operation. An
     * interrupted process may require reconnect; it can never reload known-expired
     * credentials merely because asynchronous ciphertext/key cleanup was interrupted.
     * File work is serialized on IO and never holds the synchronous lifecycle gate.
     */
    private suspend fun <T> withCredentialUse(lease: InsightLease, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val revision = persistence.withLock {
            val revision = adopt(lease) { secretRevision }
            val mustMark = adopt(lease) { persisted && credentialUses[revision].orZero() == 0 }
            if (mustMark) store.beginUse()
            adopt(lease) { credentialUses[revision] = credentialUses[revision].orZero() + 1 }
            revision
        }
        try { block() }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                persistence.withLock {
                    val validUntil = synchronized(gate) {
                        val remaining = credentialUses[revision].orZero() - 1
                        if (remaining <= 0) { credentialUses.remove(revision); if (revision != secretRevision) { rejectedProofRevisions.remove(revision); forbiddenProofRevisions.remove(revision) } } else credentialUses[revision] = remaining
                        if (remaining <= 0 && secretRevision == revision && persisted && !pendingForget && revision !in rejectedProofRevisions && revision !in forbiddenProofRevisions) rememberedValidUntil else null
                    }
                    if (validUntil != null) {
                        try { store.finishUse(validUntil) } catch (_: Exception) {
                            synchronized(gate) {
                                if (secretRevision == revision) mutableState.value = mutableState.value.copy(message = "저장 상태를 확인할 수 없어 다음 앱 실행 시 다시 연결해야 할 수 있습니다.")
                            }
                        }
                    }
                }
            }
        }
    }
    companion object {
        @Volatile private var applicationInstance: InsightSessionManager? = null
        /**
         * Activity recreation must share one persistence coordinator and generation.
         * Do not create independent production managers for the same credential files.
         */
        fun getInstance(context: Context): InsightSessionManager = applicationInstance ?: synchronized(this) {
            applicationInstance ?: InsightSessionManager(context.applicationContext).also { applicationInstance = it }
        }
        internal fun parseRetryAfter(value: String?, now: Long): Long {
            val delay = value?.trim()?.toLongOrNull()?.coerceIn(1, 86400)?.times(1000)
            val date = if (delay == null) runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() else null
            return date?.coerceIn(now + 1000, now + 86_400_000) ?: (now + (delay ?: 60_000))
        }
        private fun maskEmail(email: String): String = email.substringBefore('@').take(1) + "***@" + email.substringAfter('@', "")
    }
}
