package com.koreainv.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.koreainv.dashboard.ui.theme.LocalDashboardColors
import com.koreainv.dashboard.ui.LocalDashboardNavigationSource

/**
 * A floating header above a scrolling body. Apply [content]'s padding inside the
 * scroll container (LazyColumn contentPadding or padding after verticalScroll),
 * so scrolled content remains visible through the header controls.
 */
@Composable
fun DashboardScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = LocalDashboardColors.current
    val density = LocalDensity.current
    // Every body keeps one fixed recording. Publishing a guarded source changes
    // what the bottom bar reads without moving a LayerBackdrop between nodes.
    val recording = rememberLayerBackdrop()
    val bodyBackdrop = remember(recording) { DashboardBodyBackdrop(recording) }
    val navigationBackdrop = LocalGlassNavigation.current
    val isNavigationSource = LocalDashboardNavigationSource.current
    SideEffect {
        if (isNavigationSource) navigationBackdrop?.publish(bodyBackdrop)
        else navigationBackdrop?.clear(bodyBackdrop)
    }
    DisposableEffect(navigationBackdrop, bodyBackdrop) {
        onDispose {
            bodyBackdrop.sourceCoordinates = null
            navigationBackdrop?.clear(bodyBackdrop)
        }
    }
    var topBarHeight by remember { mutableStateOf(80.dp) }
    var consumedInsets by remember { mutableStateOf(WindowInsets(0, 0, 0, 0)) }
    val bottomPadding = WindowInsets.safeDrawing.exclude(consumedInsets)
        .asPaddingValues().calculateBottomPadding()

    Box(modifier.fillMaxSize().onConsumedWindowInsetsChanged { consumedInsets = it }) {
        // The source includes its own opaque canvas, and excludes both scrim and header.
        CompositionLocalProvider(LocalGlassControls provides null) {
            Box(
                Modifier.fillMaxSize()
                    .onGloballyPositioned { bodyBackdrop.sourceCoordinates = it }
                    .layerBackdrop(recording)
                    .background(colors.background),
            ) {
                content(PaddingValues(top = topBarHeight, bottom = bottomPadding))
            }
        }
        Box(
            Modifier.fillMaxWidth().height(topBarHeight + 24.dp).drawWithCache {
                val scrim = Brush.verticalGradient(
                    0f to colors.background,
                    0.65f to colors.background.copy(alpha = 0.96f),
                    1f to Color.Transparent,
                )
                onDrawBehind { drawRect(scrim) }
            },
        )
        // Occluded rows must not receive taps through the title/empty header space.
        // Actual header controls are placed later and take precedence in hit testing.
        Box(Modifier.fillMaxWidth().height(topBarHeight).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
        })
        CompositionLocalProvider(LocalGlassControls provides bodyBackdrop) {
            Box(Modifier.fillMaxWidth().onSizeChanged {
                topBarHeight = with(density) { it.height.toDp() }
            }) {
                topBar()
            }
        }
    }
}
