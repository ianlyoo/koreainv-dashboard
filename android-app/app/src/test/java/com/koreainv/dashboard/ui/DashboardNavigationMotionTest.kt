package com.koreainv.dashboard.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardNavigationMotionTest {
    @Test
    fun securityBoundariesNeverRetainAnAnimatedScreen() {
        val protected = listOf("portfolio", "settings", "trade_detail", "holding_detail/005930?accountId=test", "account_management")
        val boundaries = listOf(null, "splash", "setup", "unlock")
        protected.forEach { screen ->
            boundaries.forEach { boundary ->
                listOf(false, true).forEach { pop ->
                    assertEquals(EnterTransition.None, DashboardNavigationMotion.enter(screen, boundary, pop))
                    assertEquals(ExitTransition.None, DashboardNavigationMotion.exit(screen, boundary, pop))
                    assertEquals(EnterTransition.None, DashboardNavigationMotion.enter(boundary, screen, pop))
                    assertEquals(ExitTransition.None, DashboardNavigationMotion.exit(boundary, screen, pop))
                }
            }
        }
    }

    @Test
    fun tabOrderDefinesDirectionEvenWhenRestoringOrPoppingState() {
        assertEquals(1, DashboardNavigationMotion.direction("portfolio", "trade_history", false))
        assertEquals(-1, DashboardNavigationMotion.direction("settings", "asset_status", false))
        assertEquals(-1, DashboardNavigationMotion.direction("settings", "portfolio", true))
        assertEquals(0, DashboardNavigationMotion.direction("settings", "settings", false))
    }

    @Test
    fun detailBackReversesForwardMotionIncludingParameterizedRoutes() {
        assertEquals(1, DashboardNavigationMotion.direction("portfolio", "holding_detail/005930?accountId=test", false))
        assertEquals(-1, DashboardNavigationMotion.direction("holding_detail/{symbol}?accountId={accountId}", "portfolio", true))
        assertEquals(1, DashboardNavigationMotion.direction("settings", "account_management", false))
        assertEquals(-1, DashboardNavigationMotion.direction("trade_detail", "trade_history", true))
    }
}
