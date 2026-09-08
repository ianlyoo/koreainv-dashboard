package com.koreainv.dashboard.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardColorsTest {
    @Test
    fun smallTextAndFinancialValuesRemainReadableAcrossSurfaces() {
        listOf(false, true).forEach { dark ->
            val colors = dashboardColors(dark)
            val backgrounds = listOf(
                colors.background, colors.backgroundRaised, colors.surface,
                colors.surfacePrimary, colors.surfaceElevated, colors.surfaceAccent,
                colors.surfaceGlass.compositeOver(colors.background),
                colors.surfaceGlassLight.compositeOver(colors.background),
            )
            val foregrounds = listOf(
                colors.textPrimary, colors.textSecondary, colors.textHint,
                colors.success, colors.error, colors.warning, colors.info, colors.textGold,
            )
            backgrounds.forEach { bg -> foregrounds.forEach { fg -> assertContrast(fg, bg) } }
            listOf(
                colors.success to colors.positiveSurface,
                colors.error to colors.negativeSurface,
                colors.info to colors.infoSurface,
                colors.marketKoreaFg to colors.marketKoreaBg,
                colors.marketUsaFg to colors.marketUsaBg,
                colors.marketJapanFg to colors.marketJapanBg,
            ).forEach { (fg, bg) -> assertContrast(fg, bg) }
        }
    }

    @Test
    fun materialControlsAndDialogsHaveReadableContentInEitherMode() {
        listOf(false, true).forEach { dark ->
            val scheme = dashboardColors(dark).materialColorScheme()
            listOf(
                scheme.onPrimary to scheme.primary,
                scheme.onSecondary to scheme.secondary,
                scheme.onTertiary to scheme.tertiary,
                scheme.onError to scheme.error,
                scheme.onErrorContainer to scheme.errorContainer,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.inverseOnSurface to scheme.inverseSurface,
                scheme.onSurface to scheme.surfaceContainerHigh,
                scheme.onSurfaceVariant to scheme.surfaceVariant,
            ).forEach { (fg, bg) -> assertContrast(fg, bg) }
            assertContrast(scheme.outline, scheme.surface, 3f)
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float = 4.5f) {
        val first = foreground.compositeOver(background).luminance()
        val second = background.luminance()
        val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
        assertTrue("Contrast $ratio < $minimum for $foreground over $background", ratio >= minimum)
    }
}
