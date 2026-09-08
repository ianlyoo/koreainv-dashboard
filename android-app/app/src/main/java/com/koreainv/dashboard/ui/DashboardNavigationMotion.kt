package com.koreainv.dashboard.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset

/** Shared by the real NavHost and the offline motion preview. */
object DashboardNavigationMotion {
    private val tabs = listOf("portfolio", "asset_status", "trade_history", "settings")
    private val details = setOf("holding_detail", "trade_detail", "account_management", "stock_insight", "insight_connection")

    private fun routeKey(route: String?): String? = route?.substringBefore('/')?.substringBefore('?')

    internal fun direction(fromRoute: String?, toRoute: String?, isPop: Boolean): Int {
        val from = routeKey(fromRoute)
        val to = routeKey(toRoute)
        // Security boundaries must dispose outgoing content without a visual transition.
        if (from == to || from !in tabs + details || to !in tabs + details) return 0
        if (from in tabs && to in tabs) {
            return if (tabs.indexOf(to) > tabs.indexOf(from)) 1 else -1
        }
        return if (isPop) -1 else 1
    }

    fun enter(fromRoute: String?, toRoute: String?, isPop: Boolean = false): EnterTransition {
        val direction = direction(fromRoute, toRoute, isPop)
        if (direction == 0) return EnterTransition.None
        val divisor = if (routeKey(fromRoute) in tabs && routeKey(toRoute) in tabs) 20 else 12
        return fadeIn(tween(170)) + slideInHorizontally(
            animationSpec = spring(dampingRatio = 1f, stiffness = 650f, visibilityThreshold = IntOffset(1, 1)),
            initialOffsetX = { width -> direction * (width / divisor) },
        )
    }

    fun exit(fromRoute: String?, toRoute: String?, isPop: Boolean = false): ExitTransition {
        val direction = direction(fromRoute, toRoute, isPop)
        if (direction == 0) return ExitTransition.None
        return fadeOut(tween(130)) + slideOutHorizontally(
            animationSpec = spring(dampingRatio = 1f, stiffness = 650f, visibilityThreshold = IntOffset(1, 1)),
            targetOffsetX = { width -> -direction * (width / 24) },
        )
    }
}

internal val LocalDashboardNavigationSource = staticCompositionLocalOf { true }

/** Only the current target may record the persistent bottom bar's source. */
@Composable
fun DashboardNavigationScene(isNavigationSource: Boolean, content: @Composable () -> Unit) {
    val inputGuard = if (isNavigationSource) Modifier else Modifier
        .clearAndSetSemantics { }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    CompositionLocalProvider(LocalDashboardNavigationSource provides isNavigationSource) {
        Box(Modifier.fillMaxSize().then(inputGuard)) { content() }
    }
}
