package com.koreainv.dashboard.network.insight

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.JsonObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Intentionally no data-class toString: credentials must not appear in diagnostic output. */
internal class InsightCredentials(val email: String, val password: String, val validUntilMillis: Long = Long.MAX_VALUE)
internal interface InsightSecretStore {
    val profileId: String
    fun hasStored(): Boolean
    fun canRemember(): Boolean
    fun read(): InsightCredentials?
    /** Writes ciphertext with a pending lease; finishUse commits its recoverability. */
    fun write(credentials: InsightCredentials)
    /** Must durably finish before an authenticated request starts. */
    fun beginUse()
    fun finishUse(validUntilMillis: Long)
    fun delete()
}

/** Independent random identity; neither broker account IDs nor credentials enter this file. */
internal class InsightSettingsManager(context: Context) {
    val directory = File(context.noBackupFilesDir, "insight").also { check(it.exists() || it.mkdirs()) }
    val profileId: String
    init {
        val file = AtomicFile(File(directory, "profile"))
        val old = runCatching { file.openRead().use { input -> require(input.available() <= 64); input.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        profileId = old?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: UUID.randomUUID().toString().also { id ->
            val stream = file.startWrite()
            try { stream.write(id.toByteArray()); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }
}

/** AES-GCM payload and profile live exclusively under noBackupFilesDir. App PIN gates callers. */
internal class InsightCredentialStore(private val context: Context) : InsightSecretStore {
    private val settings by lazy { InsightSettingsManager(context) }
    override val profileId get() = settings.profileId
    private val alias get() = "koreainv.insight.v1.$profileId"
    private val file by lazy { AtomicFile(File(settings.directory, "credentials.v1")) }
    private val leaseFile by lazy { AtomicFile(File(settings.directory, "auth-lease.v1")) }
    private val aad get() = "koreainv.insight|1|$profileId".toByteArray(Charsets.UTF_8)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun hasStored() = file.baseFile.exists()
    override fun canRemember() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    override fun read(): InsightCredentials? {
        if (!hasStored()) return null
        try {
            check(canRemember())
            val validity = leaseFile.openRead().use { input ->
                require(input.available() in 1..256)
                input.readBytes().toString(Charsets.UTF_8).split("|")
            }
            require(validity.size == 4 && validity[0] == "1" && validity[1] == profileId && validity[2] == "valid")
            val validUntil = validity[3].toLong()
            require(validUntil > System.currentTimeMillis()) { "Remembered authentication expired" }
            val bytes = file.openRead().use { input -> require(input.available() in 32..32768); input.readBytes() }
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.int == 1)
            val iv = ByteArray(12).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val key = keyStore().getKey(alias, null) as? SecretKey ?: error("Key missing")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
            val clear = cipher.doFinal(encrypted)
            try {
                val payload = InsightParser.root(clear.toString(Charsets.UTF_8))
                val email = payload.get("email").asString; val password = payload.get("password").asString
                require(email.length in 1..320 && password.length in 1..4096)
                return InsightCredentials(email, password, validUntil)
            } finally { clear.fill(0) }
        } catch (_: Exception) { delete(); throw InsightStoreException() }
    }
    override fun write(credentials: InsightCredentials) {
        check(canRemember()) { "Device lock required" }
        beginUse()
        val store = keyStore()
        val key = (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
        require(cipher.iv.size == 12)
        val clear = JsonObject().apply { addProperty("email", credentials.email); addProperty("password", credentials.password) }.toString().toByteArray()
        val encrypted = try { cipher.doFinal(clear) } finally { clear.fill(0) }
        val bytes = ByteBuffer.allocate(4 + 12 + encrypted.size).putInt(1).put(cipher.iv).put(encrypted).array()
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    /**
     * This contains no credentials. Pending is fail-closed across process death; a valid
     * record is written only after every authenticated operation has completed safely.
     */
    override fun beginUse() = writeLease("pending", 0)
    override fun finishUse(validUntilMillis: Long) = writeLease("valid", validUntilMillis)
    private fun writeLease(state: String, validUntilMillis: Long) {
        val stream = leaseFile.startWrite()
        try {
            stream.write("1|$profileId|$state|$validUntilMillis".toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            leaseFile.finishWrite(stream)
        } catch (e: Exception) { leaseFile.failWrite(stream); throw e }
    }
    override fun delete() {
        // Keep the pending record even after deletion: failure to delete a key or a
        // ciphertext must never turn the old remembered secret into a usable record.
        beginUse()
        try {
            file.delete()
            val store = keyStore()
            if (store.containsAlias(alias)) store.deleteEntry(alias)
            check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
                !File(file.baseFile.path + ".new").exists() && !store.containsAlias(alias))
        } catch (_: Exception) { throw InsightCleanupException() }
    }

}
/** The durable pending record is already committed, but physical cleanup failed. */
internal class InsightCleanupException : Exception("저장 정보는 사용할 수 없게 처리했지만 기기 정리가 완료되지 않았습니다.")
internal class InsightStoreException : Exception("저장된 연결 정보를 열 수 없습니다. 다시 연결해 주세요.")
