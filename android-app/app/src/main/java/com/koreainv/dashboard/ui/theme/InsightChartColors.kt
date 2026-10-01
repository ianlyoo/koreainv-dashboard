package com.koreainv.dashboard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Insight-only accents: keep other screens and financial gain/loss text unchanged. */
@Immutable
internal data class InsightChartColors(
    val blue: Color,
    val teal: Color,
    val violet: Color,
    val amber: Color,
    val coral: Color,
)

internal val insightChartColors: InsightChartColors
    @Composable @ReadOnlyComposable get() {
        val colors = LocalDashboardColors.current
        return InsightChartColors(
            blue = lerp(colors.chartTone1, colors.primary, .3f),
            teal = lerp(colors.chartTone2, colors.primaryVariant, .2f),
            violet = lerp(colors.chartTone6, colors.marketJapanFg, .35f),
            amber = lerp(colors.chartTone4, colors.warning, .35f),
            coral = lerp(colors.chartTone5, colors.error, .35f),
        )
    }
