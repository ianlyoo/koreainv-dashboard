package com.koreainv.dashboard.ui.screens

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.koreainv.dashboard.ui.theme.LocalDashboardColors

private val LocalGlassBackground = staticCompositionLocalOf<LayerBackdrop?> { null }
private val LocalGlassNavigation = staticCompositionLocalOf<LayerBackdrop?> { null }

internal enum class GlassRole { Panel, Control, Navigation }

/** Two independent recordings keep the floating bar out of its own backdrop. */
@Composable
fun DashboardGlassHost(content: @Composable () -> Unit) {
    val background = rememberLayerBackdrop()
    val navigation = rememberLayerBackdrop()
    val colors = LocalDashboardColors.current
    CompositionLocalProvider(
        LocalGlassBackground provides background,
        LocalGlassNavigation provides navigation,
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().layerBackdrop(background)) {
                drawRect(Brush.verticalGradient(listOf(colors.background, colors.backgroundRaised)))
                // A quiet light source gives glass edges depth without competing with values.
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(colors.primary.copy(alpha = if (colors.isDark) 0.09f else 0.06f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.18f),
                        radius = size.width * 0.95f,
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.18f),
                    radius = size.width * 0.95f,
                )
            }
            content()
        }
    }
}

/** Apply only to screen content, never to a container that includes the bottom bar. */
@Composable
fun Modifier.recordNavigationBackdrop(): Modifier =
    LocalGlassNavigation.current?.let { layerBackdrop(it) } ?: this

@Composable
internal fun Modifier.liquidGlass(
    radius: Dp = 28.dp,
    role: GlassRole = GlassRole.Panel,
    tint: Color = Color.Unspecified,
): Modifier {
    val colors = LocalDashboardColors.current
    val backdrop = if (role == GlassRole.Navigation) LocalGlassNavigation.current else LocalGlassBackground.current
    val shape = RoundedCornerShape(radius)
    val surface = if (tint != Color.Unspecified) tint else colors.surfaceGlassLight.copy(
        alpha = when (role) {
            GlassRole.Panel -> if (colors.isDark) 0.58f else 0.64f
            GlassRole.Control -> if (colors.isDark) 0.48f else 0.54f
            GlassRole.Navigation -> if (colors.isDark) 0.58f else 0.68f
        },
    )
    val edge = Brush.linearGradient(
        listOf(colors.glassHighlight, colors.surfaceBorder.copy(alpha = 0.28f), colors.surfaceBorder.copy(alpha = 0.65f)),
    )
    // RenderEffect blur starts at API 31; refraction is guarded internally at API 33.
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return this.clip(shape)
            .background(colors.surfaceGlassLight.copy(alpha = if (role == GlassRole.Navigation) 1f else 0.94f))
            .border(0.75.dp, edge, shape)
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(if (role == GlassRole.Navigation) 14.dp.toPx() else 7.dp.toPx())
            lens(8.dp.toPx(), if (role == GlassRole.Navigation) 16.dp.toPx() else 10.dp.toPx())
        },
        highlight = { Highlight(width = 0.45.dp, alpha = if (colors.isDark) 0.38f else 0.5f) },
        shadow = { Shadow(radius = if (role == GlassRole.Navigation) 18.dp else 10.dp, color = colors.glassShadow) },
        onDrawSurface = {
            drawRect(surface)
            drawRect(colors.glassTint)
        },
    ).border(0.5.dp, edge, shape)
}

@Composable
internal fun Modifier.glassPressFeedback(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "glass press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
