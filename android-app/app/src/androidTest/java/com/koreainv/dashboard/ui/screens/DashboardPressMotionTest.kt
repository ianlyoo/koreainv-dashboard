package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Device tests: exercise the interaction stream, including events within one frame. */
class DashboardPressMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sameFrameTapStillCompressesThenRestores() {
        val source = MutableInteractionSource()
        lateinit var scale: State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent { scale = rememberGlassPressScale(source) }
        val press = PressInteraction.Press(Offset.Zero)
        compose.runOnIdle {
            assertTrue(source.tryEmit(press))
            assertTrue(source.tryEmit(PressInteraction.Release(press)))
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertTrue("Quick tap must be visible", scale.value < 1f) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1f, scale.value, 0.001f) }
    }

    @Test fun cancellationAndRepressDoNotLeaveControlCompressed() {
        val source = MutableInteractionSource()
        lateinit var scale: State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent { scale = rememberGlassPressScale(source) }
        val first = PressInteraction.Press(Offset.Zero)
        compose.runOnIdle { source.tryEmit(first) }
        compose.mainClock.advanceTimeBy(64)
        val second = PressInteraction.Press(Offset.Zero)
        compose.runOnIdle {
            source.tryEmit(PressInteraction.Cancel(first))
            source.tryEmit(second)
        }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { assertEquals(0.96f, scale.value, 0.001f) }
        compose.runOnIdle { source.tryEmit(PressInteraction.Cancel(second)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1f, scale.value, 0.001f) }
    }
}
