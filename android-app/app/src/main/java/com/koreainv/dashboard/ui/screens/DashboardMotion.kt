package com.koreainv.dashboard.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Compose animation clocks apply the system duration scale, including scale zero. */
internal object DashboardMotion {
    const val ColorDuration = 150
    fun <T> selection() = spring<T>(dampingRatio = 0.86f, stiffness = 420f)
    fun <T> release() = spring<T>(dampingRatio = 0.8f, stiffness = 650f)
}

@Composable
internal fun rememberGlassPressScale(source: MutableInteractionSource): State<Float> {
    val scale = remember(source) { Animatable(1f) }
    LaunchedEffect(source) {
        val presses = mutableSetOf<PressInteraction.Press>()
        var pressAnimation: Job? = null
        var releaseAnimation: Job? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    presses.add(interaction)
                    releaseAnimation?.cancel()
                    pressAnimation?.cancel()
                    pressAnimation = launch(start = CoroutineStart.UNDISPATCHED) {
                        scale.animateTo(0.96f, tween(90))
                    }
                }
                is PressInteraction.Release -> {
                    if (presses.remove(interaction.press) && presses.isEmpty()) {
                        val down = pressAnimation
                        releaseAnimation = launch {
                            // A same-frame tap completes its brief visual compression only.
                            // Click dispatch is entirely owned by clickable/selectable.
                            down?.join()
                            scale.animateTo(1f, DashboardMotion.release())
                        }
                    }
                }
                is PressInteraction.Cancel -> {
                    if (presses.remove(interaction.press) && presses.isEmpty()) {
                        pressAnimation?.cancel()
                        releaseAnimation?.cancel()
                        releaseAnimation = launch { scale.animateTo(1f, DashboardMotion.release()) }
                    }
                }
            }
        }
    }
    return scale.asState()
}

/** Shared elastic selection treatment for the bottom tabs and currency capsule. */
@Composable
internal fun <T> rememberSelectionStretch(selection: T, enabled: Boolean = true): State<Float> {
    val stretch = remember { Animatable(1f) }
    var previousSelection by remember { mutableStateOf(selection) }
    var previousEnabled by remember { mutableStateOf(enabled) }
    LaunchedEffect(selection, enabled) {
        val moved = previousEnabled && enabled && previousSelection != selection
        previousSelection = selection
        previousEnabled = enabled
        if (moved) stretch.animateTo(1.045f, tween(80))
        stretch.animateTo(1f, DashboardMotion.release())
    }
    return stretch.asState()
}
