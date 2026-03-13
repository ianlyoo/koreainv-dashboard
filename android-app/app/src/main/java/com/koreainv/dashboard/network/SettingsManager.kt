package com.koreainv.dashboard.network

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        private val SETUP_COMPLETE_KEY = booleanPreferencesKey("setup_complete")
        private val ENCRYPTED_CREDENTIALS_KEY = stringPreferencesKey("encrypted_credentials")
        private val CREDENTIAL_SALT_KEY = stringPreferencesKey("credential_salt")
        private val CREDENTIAL_IV_KEY = stringPreferencesKey("credential_iv")
        private const val KDF_ITERATIONS = 390000
        private const val KEY_LENGTH_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val SALT_LENGTH_BYTES = 16
    }

    private val gson = Gson()
    private val secureRandom = SecureRandom()

    val isSetupCompleteFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SETUP_COMPLETE_KEY] ?: false
    }

    suspend fun saveCredentials(input: SetupInput): AppCredentials {
        val credentials = AppCredentials(
            appKey = input.appKey.trim(),
            appSecret = input.appSecret.trim(),
            cano = input.cano.trim(),
            acntPrdtCd = input.acntPrdtCd.trim(),
        )
        val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val encrypted = encryptCredentials(credentials, input.pin, salt, iv)

        context.dataStore.edit { preferences ->
            preferences[SETUP_COMPLETE_KEY] = true
            preferences[ENCRYPTED_CREDENTIALS_KEY] = encrypted
            preferences[CREDENTIAL_SALT_KEY] = encodeBase64(salt)
            preferences[CREDENTIAL_IV_KEY] = encodeBase64(iv)
        }
        return credentials
    }

    suspend fun unlock(pin: String): AppCredentials? {
        val values = context.dataStore.data.map { preferences ->
            Triple(
                preferences[ENCRYPTED_CREDENTIALS_KEY],
                preferences[CREDENTIAL_SALT_KEY],
                preferences[CREDENTIAL_IV_KEY],
            )
        }.first()
        val encrypted = values.first ?: return null
        val salt = values.second ?: return null
        val iv = values.third ?: return null
        return decryptCredentials(encrypted, pin, decodeBase64(salt), decodeBase64(iv))
    }

    suspend fun clearCredentials() {
        context.dataStore.edit { preferences ->
            preferences.remove(SETUP_COMPLETE_KEY)
            preferences.remove(ENCRYPTED_CREDENTIALS_KEY)
            preferences.remove(CREDENTIAL_SALT_KEY)
            preferences.remove(CREDENTIAL_IV_KEY)
        }
    }

    private fun encryptCredentials(
        credentials: AppCredentials,
        pin: String,
        salt: ByteArray,
        iv: ByteArray,
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveAesKey(pin, salt), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val encryptedBytes = cipher.doFinal(gson.toJson(credentials).toByteArray(Charsets.UTF_8))
        return encodeBase64(encryptedBytes)
    }

    private fun decryptCredentials(
        encrypted: String,
        pin: String,
        salt: ByteArray,
        iv: ByteArray,
    ): AppCredentials? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(pin, salt), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val decryptedBytes = cipher.doFinal(decodeBase64(encrypted))
            gson.fromJson(String(decryptedBytes, Charsets.UTF_8), AppCredentials::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveAesKey(pin: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, KDF_ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeBase64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
