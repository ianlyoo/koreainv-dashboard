package com.koreainv.dashboard.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.koreainv.dashboard.ui.theme.LocalDashboardColors
import com.koreainv.dashboard.ui.LocalDevicePerformancePolicy
import com.koreainv.dashboard.ui.rememberDevicePerformancePolicy
import androidx.compose.material3.LocalContentColor

internal val LocalGlassNavigation = staticCompositionLocalOf<DashboardNavigationBackdrop?> { null }
// Provided only around floating top controls, never around their recorded source.
internal val LocalGlassControls = staticCompositionLocalOf<Backdrop?> { null }

internal enum class GlassRole { Panel, Control, Navigation }

/** One body recording feeds floating controls and navigation; ordinary surfaces stay flat. */
@Composable
fun DashboardGlassHost(
    background: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val navigation = remember { DashboardNavigationBackdrop() }
    val colors = LocalDashboardColors.current
    val performance = rememberDevicePerformancePolicy()
    CompositionLocalProvider(
        LocalGlassNavigation provides navigation,
        LocalDevicePerformancePolicy provides performance,
        LocalContentColor provides colors.textPrimary,
    ) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            background()
            // Paint through the status bar, while keeping controls clear of system UI/cutouts.
            Box(Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding()) {
                content()
            }
        }
    }
}

@Composable
internal fun Modifier.liquidGlass(
    radius: Dp = 28.dp,
    role: GlassRole = GlassRole.Panel,
    tint: Color = Color.Unspecified,
): Modifier {
    val colors = LocalDashboardColors.current
    val liveGlass = LocalDevicePerformancePolicy.current.liveGlass
    val backdrop: Backdrop? = when (role) {
        GlassRole.Navigation -> LocalGlassNavigation.current
        GlassRole.Control -> LocalGlassControls.current
        GlassRole.Panel -> null
    }
    val shape = RoundedCornerShape(radius)
    val surface = if (tint != Color.Unspecified) tint else colors.surfaceGlassLight.copy(
        alpha = when (role) {
            GlassRole.Panel -> if (colors.isDark) 0.58f else 0.64f
            GlassRole.Control -> if (colors.isDark) 0.42f else 0.36f
            GlassRole.Navigation -> if (colors.isDark) 0.42f else 0.38f
        },
    )
    val edge = Brush.linearGradient(
        listOf(colors.glassHighlight, colors.surfaceBorder.copy(alpha = 0.28f), colors.surfaceBorder.copy(alpha = 0.65f)),
    )
    // Body controls remain cheap; only scoped top controls and navigation get effects.
    if (!liveGlass || backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        val fill = if (!liveGlass || role == GlassRole.Navigation || backdrop != null) {
            (if (tint != Color.Unspecified) tint else colors.surfaceGlassLight).copy(alpha = 1f)
        } else surface
        val flat = this.clip(shape).background(fill)
        return if (role == GlassRole.Control) flat.border(0.5.dp, edge, shape) else flat
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(if (role == GlassRole.Navigation) 12.dp.toPx() else 10.dp.toPx())
            // Library guards runtime-shader refraction on API 33; API 31–32 retain blur.
            lens(
                if (role == GlassRole.Navigation) 18.dp.toPx() else 8.dp.toPx(),
                if (role == GlassRole.Navigation) 18.dp.toPx() else 12.dp.toPx(),
            )
        },
        highlight = { Highlight(width = 0.45.dp, alpha = if (colors.isDark) 0.38f else 0.5f) },
        shadow = { Shadow(radius = 8.dp, color = colors.glassShadow) },
        onDrawSurface = {
            drawRect(surface)
            drawRect(colors.glassTint)
        },
    ).border(0.5.dp, edge, shape)
}

/** Place after the input modifier so the visual never changes the touch target. */
@Composable
internal fun Modifier.glassPressFeedback(interactionSource: MutableInteractionSource): Modifier {
    val scale = rememberGlassPressScale(interactionSource)
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
