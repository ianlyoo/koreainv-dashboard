package com.koreainv.dashboard.network.insight

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a secure emulator PIN. Uses real AndroidKeyStore with disposable random aliases. */
@RunWith(AndroidJUnit4::class)
class InsightCredentialStoreDeviceTest {
    @Test
    fun encryptedCredentialsRoundTripAcrossStoreInstances() = withStore { context, store ->
        val credentials = syntheticCredentials()
        val validUntil = System.currentTimeMillis() + 3_600_000L
        store.write(credentials)
        store.finishUse(validUntil)

        val bytes = File(context.noBackupFilesDir, "insight/credentials.v1").readBytes()
        val storedText = bytes.toString(Charsets.ISO_8859_1)
        assertFalse("The encrypted file must not contain the email", storedText.contains(credentials.email))
        assertFalse("The encrypted file must not contain the password", storedText.contains(credentials.password))
        val restored = InsightCredentialStore(context).read()
        assertNotNull(restored)
        assertTrue("The email must round trip", restored?.email == credentials.email)
        assertTrue("The password must round trip", restored?.password == credentials.password)
        assertTrue("The committed lease must round trip", restored?.validUntilMillis == validUntil)
    }

    @Test
    fun pendingAuthenticatedOperationRefusesRecoveryInNewStoreInstance() = withStore { context, store ->
        store.write(syntheticCredentials())
        store.finishUse(System.currentTimeMillis() + 3_600_000L)
        store.beginUse()

        assertUnreadable(InsightCredentialStore(context))
        assertFalse("Rejected credentials must be removed", store.hasStored())
        assertFalse("Rejected credentials must lose their key", keyStore().containsAlias(alias(store)))
    }

    @Test
    fun missingKeystoreKeyRefusesRecoveryAndRemovesCiphertext() = withStore { context, store ->
        store.write(syntheticCredentials())
        store.finishUse(System.currentTimeMillis() + 3_600_000L)
        keyStore().deleteEntry(alias(store))

        assertUnreadable(InsightCredentialStore(context))
        assertFalse("Key loss must remove unusable ciphertext", store.hasStored())
    }

    @Test
    fun expiredRememberedAuthenticationRefusesRecovery() = withStore { context, store ->
        store.write(syntheticCredentials())
        store.finishUse(System.currentTimeMillis() - 1L)

        assertUnreadable(InsightCredentialStore(context))
        assertFalse("Expired credentials must be removed", store.hasStored())
    }

    private fun withStore(block: (Context, InsightCredentialStore) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "This device test requires a real device PIN before running",
            target.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true,
        )
        val directory = File(target.noBackupFilesDir, "insight-device-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val isolated = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
        val store = InsightCredentialStore(isolated)
        val isolatedAlias = alias(store)
        try {
            assertTrue("The store must use the real secure keyguard", store.canRemember())
            block(isolated, store)
        } finally {
            // Delete only this test's random identity; never enumerate or clear other keys.
            try {
                keyStore().deleteEntry(isolatedAlias)
            } finally {
                assertTrue("Temporary credential files must be removed", directory.deleteRecursively())
            }
        }
    }

    private fun assertUnreadable(store: InsightCredentialStore) {
        try {
            store.read()
            fail("Unrecoverable remembered credentials must be rejected")
        } catch (_: InsightStoreException) {
            // Expected fail-closed result; no secret-bearing exception output.
        }
    }

    private fun syntheticCredentials() = InsightCredentials("device-test@example.invalid", "synthetic-device-test-password")
    private fun alias(store: InsightCredentialStore) = "koreainv.insight.v1.${store.profileId}"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
