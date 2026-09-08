package com.koreainv.dashboard.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.koreainv.dashboard.UiPreviewActivity
import org.junit.Rule
import org.junit.Test

/** Uses the offline host's real NavHost and glass layers; never opens a brokerage client. */
class DashboardNavigationMotionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<UiPreviewActivity>()

    @Test
    fun interruptedTabTransitionsKeepTheirBackdropAttached() {
        compose.onNodeWithText("보유 종목").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        repeat(6) {
            listOf("자산 현황", "거래내역", "포트폴리오", "설정", "거래내역", "포트폴리오").forEach { tab ->
                compose.onAllNodesWithText(tab).onLast().performClick()
                // Retarget before the outgoing screen's transition has completed.
                compose.mainClock.advanceTimeBy(48)
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("보유 종목").assertIsDisplayed()
    }
}
