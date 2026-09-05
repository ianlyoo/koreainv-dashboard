package com.koreainv.dashboard.network

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthTokenPersistenceTest {
    private val cacheKey = stringPreferencesKey("token_cache")
    private val legacyScopeKey = stringPreferencesKey("token_scope")
    private val legacyValueKey = stringPreferencesKey("access_token")
    private val issuedKey = longPreferencesKey("token_issued_at")
    private val expiresKey = longPreferencesKey("token_expires_at")
    private val token = AuthToken("fake-token", 1_000, 100_000)

    @Test
    fun scopedInvalidationKeepsOtherTokensAndUnrelatedPreferences() {
        val unrelatedKey = stringPreferencesKey("encrypted_credentials")
        val preferences = mutablePreferencesOf(
            cacheKey to TokenCacheCodec.serialize(mapOf("pension" to token, "ordinary" to token.copy(value = "other"))),
            unrelatedKey to "unchanged",
        )

        SettingsManager.clearAuthToken(preferences, "pension")

        assertEquals(mapOf("ordinary" to token.copy(value = "other")), TokenCacheCodec.parse(preferences[cacheKey]!!))
        assertEquals("unchanged", preferences[unrelatedKey])
    }

    @Test
    fun matchingLegacyTokenIsRemovedSoItCannotBeMigratedBack() {
        val preferences = mutablePreferencesOf(
            legacyScopeKey to "pension",
            legacyValueKey to "rejected",
            issuedKey to 1_000L,
            expiresKey to 100_000L,
        )

        SettingsManager.clearAuthToken(preferences, "pension")

        assertNull(preferences[legacyScopeKey])
        assertNull(preferences[legacyValueKey])
        assertNull(preferences[issuedKey])
        assertNull(preferences[expiresKey])
        assertEquals(emptyMap<String, AuthToken>(), TokenCacheCodec.parse(preferences[cacheKey]!!))
    }

    @Test
    fun unrelatedLegacyTokenSurvivesScopedInvalidation() {
        val preferences = mutablePreferencesOf(
            legacyScopeKey to "ordinary",
            legacyValueKey to "other",
            issuedKey to 1_000L,
            expiresKey to 100_000L,
        )

        SettingsManager.clearAuthToken(preferences, "pension")

        assertEquals("ordinary", preferences[legacyScopeKey])
        assertEquals("other", preferences[legacyValueKey])
        assertEquals(1_000L, preferences[issuedKey])
        assertEquals(100_000L, preferences[expiresKey])
    }
}
