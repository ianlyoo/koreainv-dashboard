package com.koreainv.dashboard.network

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
        private val TOKEN_CACHE_KEY = stringPreferencesKey("token_cache")
        // Legacy single-record token keys, kept only to migrate pre-multi-account installs.
        private val TOKEN_SCOPE_KEY = stringPreferencesKey("token_scope")
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val TOKEN_ISSUED_AT_KEY = longPreferencesKey("token_issued_at")
        private val TOKEN_EXPIRES_AT_KEY = longPreferencesKey("token_expires_at")
        private const val KDF_ITERATIONS = 390000
        private const val KEY_LENGTH_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val SALT_LENGTH_BYTES = 16
    }

    private val secureRandom = SecureRandom()

    val isSetupCompleteFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SETUP_COMPLETE_KEY] ?: false
    }

    suspend fun saveCredentials(input: SetupInput): AppCredentials {
        val account = AccountCredential(
            id = input.id.ifBlank { stableAccountId(input.cano, input.acntPrdtCd) },
            label = input.label.ifBlank { defaultAccountLabel(input.cano) },
            appKey = input.appKey.trim(),
            appSecret = input.appSecret.trim(),
            cano = input.cano.trim(),
            acntPrdtCd = input.acntPrdtCd.trim(),
            centralServerBaseUrl = input.centralServerBaseUrl.trim(),
            centralServerApiToken = input.centralServerApiToken.trim(),
        )
        val credentials = account.toAppCredentials()
        val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val encrypted = encryptCredentials(listOf(account), input.pin, salt, iv)

        context.dataStore.edit { preferences ->
            preferences[SETUP_COMPLETE_KEY] = true
            preferences[ENCRYPTED_CREDENTIALS_KEY] = encrypted
            preferences[CREDENTIAL_SALT_KEY] = encodeBase64(salt)
            preferences[CREDENTIAL_IV_KEY] = encodeBase64(iv)
            clearAuthToken(preferences)
        }
        return credentials
    }

    suspend fun saveProfile(inputs: List<SetupInput>, pin: String): AccountProfile {
        require(inputs.isNotEmpty()) { "ACCOUNT_PROFILE_EMPTY" }
        val accounts = inputs.map { input ->
            AccountCredential(
                id = input.id.ifBlank {
                    stableAccountId(input.cano, input.acntPrdtCd)
                },
                label = input.label.ifBlank { defaultAccountLabel(input.cano) },
                appKey = input.appKey.trim(),
                appSecret = input.appSecret.trim(),
                cano = input.cano.trim(),
                acntPrdtCd = input.acntPrdtCd.trim(),
                centralServerBaseUrl = input.centralServerBaseUrl.trim(),
                centralServerApiToken = input.centralServerApiToken.trim(),
            )
        }
        val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val encrypted = encryptCredentials(accounts, pin, salt, iv)

        context.dataStore.edit { preferences ->
            preferences[SETUP_COMPLETE_KEY] = true
            preferences[ENCRYPTED_CREDENTIALS_KEY] = encrypted
            preferences[CREDENTIAL_SALT_KEY] = encodeBase64(salt)
            preferences[CREDENTIAL_IV_KEY] = encodeBase64(iv)
            clearAuthToken(preferences)
        }
        return AccountProfile(accounts = accounts)
    }

    suspend fun unlock(pin: String): AppCredentials? {
        return unlockProfile(pin)?.primary?.toAppCredentials()
    }

    suspend fun unlockProfile(pin: String): AccountProfile? {
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
        val accounts = decryptCredentials(encrypted, pin, decodeBase64(salt), decodeBase64(iv)) ?: return null
        return AccountProfile(accounts = accounts)
    }

    /**
     * Replaces the unlocked account profile after verifying the existing PIN.
     * A wrong PIN returns null without writing. Existing account ids remain
     * stable, while newly added accounts receive a deterministic id.
     */
    suspend fun updateProfile(
        accounts: List<AccountCredential>,
        pin: String,
    ): AccountProfile? {
        val existing = unlockProfile(pin) ?: return null
        val normalized = normalizeUpdatedAccounts(existing.accounts, accounts)

        val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val encrypted = encryptCredentials(normalized, pin, salt, iv)
        context.dataStore.edit { preferences ->
            preferences[SETUP_COMPLETE_KEY] = true
            preferences[ENCRYPTED_CREDENTIALS_KEY] = encrypted
            preferences[CREDENTIAL_SALT_KEY] = encodeBase64(salt)
            preferences[CREDENTIAL_IV_KEY] = encodeBase64(iv)
            clearAuthToken(preferences)
        }
        return AccountProfile(accounts = normalized)
    }

    suspend fun clearCredentials() {
        context.dataStore.edit { preferences ->
            preferences.remove(SETUP_COMPLETE_KEY)
            preferences.remove(ENCRYPTED_CREDENTIALS_KEY)
            preferences.remove(CREDENTIAL_SALT_KEY)
            preferences.remove(CREDENTIAL_IV_KEY)
            clearAuthToken(preferences)
        }
    }

    suspend fun loadAuthToken(credentials: AppCredentials): AuthToken? {
        val scope = tokenScope(credentials)
        val payload = context.dataStore.data.map { preferences -> preferences[TOKEN_CACHE_KEY] }.first()
        payload?.let { raw ->
            TokenCacheCodec.parse(raw)[scope]?.let { return it }
        }
        // Migrate a token stored by the legacy single-record format only when its
        // scope matches the requested credentials, so one account's token is never
        // reused for another account's key.
        val legacy = loadLegacyAuthToken(scope) ?: return null
        saveAuthToken(credentials, legacy)
        return legacy
    }

    suspend fun saveAuthToken(credentials: AppCredentials, token: AuthToken) {
        val scope = tokenScope(credentials)
        context.dataStore.edit { preferences ->
            val current = preferences[TOKEN_CACHE_KEY]?.let { TokenCacheCodec.parse(it) } ?: emptyMap()
            preferences[TOKEN_CACHE_KEY] = TokenCacheCodec.serialize(current + (scope to token))
            clearLegacyTokenKeys(preferences)
        }
    }

    suspend fun clearAuthToken() {
        context.dataStore.edit { preferences ->
            clearAuthToken(preferences)
        }
    }

    private fun encryptCredentials(
        accounts: List<AccountCredential>,
        pin: String,
        salt: ByteArray,
        iv: ByteArray,
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveAesKey(pin, salt), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val encryptedBytes = cipher.doFinal(AccountProfileCodec.serialize(accounts).toByteArray(Charsets.UTF_8))
        return encodeBase64(encryptedBytes)
    }

    private fun decryptCredentials(
        encrypted: String,
        pin: String,
        salt: ByteArray,
        iv: ByteArray,
    ): List<AccountCredential>? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(pin, salt), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val decryptedBytes = cipher.doFinal(decodeBase64(encrypted))
            AccountProfileCodec.parse(String(decryptedBytes, Charsets.UTF_8))
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

    private suspend fun loadLegacyAuthToken(expectedScope: String): AuthToken? {
        val values = context.dataStore.data.map { preferences ->
            TokenStoreRecord(
                scope = preferences[TOKEN_SCOPE_KEY],
                value = preferences[ACCESS_TOKEN_KEY],
                issuedAtMillis = preferences[TOKEN_ISSUED_AT_KEY],
                expiresAtMillis = preferences[TOKEN_EXPIRES_AT_KEY],
            )
        }.first()
        if (values.scope != expectedScope) return null
        val value = values.value?.trim().orEmpty()
        val issuedAtMillis = values.issuedAtMillis ?: return null
        val expiresAtMillis = values.expiresAtMillis ?: return null
        if (value.isBlank() || expiresAtMillis <= issuedAtMillis) return null
        return AuthToken(value = value, issuedAtMillis = issuedAtMillis, expiresAtMillis = expiresAtMillis)
    }

    private fun clearAuthToken(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.remove(TOKEN_CACHE_KEY)
        clearLegacyTokenKeys(preferences)
    }

    private fun clearLegacyTokenKeys(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.remove(TOKEN_SCOPE_KEY)
        preferences.remove(ACCESS_TOKEN_KEY)
        preferences.remove(TOKEN_ISSUED_AT_KEY)
        preferences.remove(TOKEN_EXPIRES_AT_KEY)
    }

    private data class TokenStoreRecord(
        val scope: String?,
        val value: String?,
        val issuedAtMillis: Long?,
        val expiresAtMillis: Long?,
    )
}
