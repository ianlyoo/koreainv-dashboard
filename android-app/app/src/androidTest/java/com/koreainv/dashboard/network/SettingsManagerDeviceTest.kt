package com.koreainv.dashboard.network

import android.os.Looper
import android.view.Choreographer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an isolated test emulator. Never constructs a repository or launches an Activity. */
@RunWith(AndroidJUnit4::class)
class SettingsManagerDeviceTest {
    @Test
    fun savingAndUnlockingProfileKeepMainFramesRunningDuringPinDerivation() = runBlocking<Unit> {
        withTimeout(90_000L) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val settings = SettingsManager(context)
            // The production Context.dataStore delegate is a singleton. Preserve all preferences
            // rather than redirecting it through a wrapper that could affect another test.
            val previous = context.dataStore.data.first()
            try {
                settings.clearCredentials()
                val saved = assertMainFrameDuringDerivation {
                    settings.saveProfile(listOf(syntheticInput()), "1234")
                }
                val unlocked = assertMainFrameDuringDerivation { settings.unlockProfile("1234") }
                assertTrue("The synthetic account profile must round trip", saved == unlocked)
                assertTrue("Saving the profile must complete setup", settings.isSetupCompleteFlow.first())
            } finally {
                withContext(NonCancellable) {
                    context.dataStore.edit { preferences ->
                        preferences.clear()
                        previous.asMap().forEach { (key, value) ->
                            @Suppress("UNCHECKED_CAST")
                            preferences[key as Preferences.Key<Any>] = value
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> assertMainFrameDuringDerivation(operation: suspend () -> T): T = coroutineScope {
        val result = async(Dispatchers.Main, start = CoroutineStart.LAZY) { operation() }
        val frameDuringDerivation = CompletableDeferred<Unit>()
        val mainThread = Looper.getMainLooper().thread
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val derivingThreads = Thread.getAllStackTraces().filterValues { stack ->
                    stack.any {
                        it.className == SettingsManager::class.java.name && it.methodName == "deriveAesKey"
                    }
                }.keys
                if (derivingThreads.any { it !== mainThread }) {
                    // This actual main-thread frame overlaps the real PBKDF2 operation.
                    frameDuringDerivation.complete(Unit)
                } else if (result.isCompleted) {
                    frameDuringDerivation.completeExceptionally(
                        AssertionError("No main frame ran while PIN derivation was executing on a worker thread"),
                    )
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        try {
            withContext(Dispatchers.Main) {
                result.start()
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
            withTimeout(30_000L) { frameDuringDerivation.await() }
            result.await()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
            }
            result.cancelAndJoin()
        }
    }

    private fun syntheticInput() = SetupInput(
        id = "device-test-account", label = "Synthetic device test", appKey = "synthetic-app-key",
        appSecret = "synthetic-app-secret", cano = "12345678", acntPrdtCd = "01", pin = "1234",
    )
}
