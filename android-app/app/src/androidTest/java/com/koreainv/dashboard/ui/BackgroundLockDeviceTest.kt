package com.koreainv.dashboard.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.koreainv.dashboard.MainActivity
import com.koreainv.dashboard.network.SettingsManager
import com.koreainv.dashboard.network.SetupInput
import com.koreainv.dashboard.network.dataStore
import com.koreainv.dashboard.network.insight.InsightSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real navigation/ON_STOP boundary, with a synthetic vault on an offline test emulator only. */
@OptIn(ExperimentalTestApi::class)
class BackgroundLockDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun leavingDuringPinSubmissionCannotPublishAnUnlockedSessionLater() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assumeTrue("Disable emulator Wi-Fi and mobile data; this test must not contact brokers", connectivity.activeNetwork == null)
        val previous = context.dataStore.data.first()
        val settings = SettingsManager(context)
        val insight = InsightSessionManager.getInstance(context)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            settings.saveProfile(listOf(SetupInput(
                id="background-lock-test", label="Synthetic test", appKey="synthetic-key",
                appSecret="synthetic-secret", cano="12345678", acntPrdtCd="01", pin="1234",
            )), "1234")
            insight.lock()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("잠금번호 입력").fetchSemanticsNodes().isNotEmpty() }
            listOf("1", "2", "3").forEach { compose.onNodeWithText(it).performClick() }
            compose.onNodeWithText("4").performSemanticsAction(SemanticsActions.OnClick) { it() }
            scenario.moveToState(Lifecycle.State.CREATED)
            withTimeout(30_000) {
                while (Thread.getAllStackTraces().values.any { stack -> stack.any { it.methodName == "deriveAesKey" } }) delay(10)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithContentDescription("잠금번호 입력").assertIsDisplayed()
            assertFalse(insight.state.value.isUnlocked)
            compose.onAllNodesWithText("보유 종목").assertCountEquals(0)
        } finally {
            scenario?.close()
            insight.lock()
            context.dataStore.edit { target ->
                target.clear()
                previous.asMap().forEach { (key,value) ->
                    @Suppress("UNCHECKED_CAST")
                    target[key as Preferences.Key<Any>] = value
                }
            }
        }
    }
}
