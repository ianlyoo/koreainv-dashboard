package com.koreainv.dashboard.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Immutable, composition-scoped colors. Glass layers must sit over this palette's backdrop.
 * Borders are decorative; use surfaceBorderPrimary for essential control boundaries.
 * Capture colors before entering remember/draw callbacks, and key cached brushes by colors.
 */
@Immutable
data class DashboardColors(
    val isDark: Boolean,
    val primary: Color,
    val primaryVariant: Color,
    val secondary: Color,
    val background: Color,
    val backgroundRaised: Color,
    val surface: Color,
    val surfacePrimary: Color,
    val surfaceElevated: Color,
    val surfaceGlass: Color,
    val surfaceGlassLight: Color,
    val surfaceMuted: Color,
    val surfaceAccent: Color,
    val surfaceBorder: Color,
    val surfaceBorderPrimary: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val positiveSurface: Color,
    val negativeSurface: Color,
    val infoSurface: Color,
    val marketKoreaBg: Color,
    val marketKoreaFg: Color,
    val marketUsaBg: Color,
    val marketUsaFg: Color,
    val marketJapanBg: Color,
    val marketJapanFg: Color,
    val chartTone1: Color,
    val chartTone2: Color,
    val chartTone3: Color,
    val chartTone4: Color,
    val chartTone5: Color,
    val chartTone6: Color,
    val onPrimary: Color,
    val onSecondary: Color,
    val onError: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val textGold: Color,
    val glassTint: Color,
    val glassHighlight: Color,
    val glassShadow: Color,
) {
    val onBackground: Color get() = textPrimary
    val onSurface: Color get() = textPrimary
}

val DarkDashboardColors = DashboardColors(
    isDark = true,
    primary = Color(0xFF9DBEFF),
    primaryVariant = Color(0xFFC1D5F5),
    secondary = Color(0xFFB3C2D7),
    background = Color(0xFF111419),
    backgroundRaised = Color(0xFF181D24),
    surface = Color(0xFF1C222A),
    surfacePrimary = Color(0xFF232B35),
    surfaceElevated = Color(0xFF2B3541),
    surfaceGlass = Color(0xD9232B35),
    surfaceGlassLight = Color(0xB8333E4B),
    surfaceMuted = Color(0xFF171C23),
    surfaceAccent = Color(0xFF293D58),
    surfaceBorder = Color(0xFF3D4958),
    surfaceBorderPrimary = Color(0xFF8292A6),
    error = Color(0xFFFFA5A5),
    success = Color(0xFF82D7AC),
    warning = Color(0xFFE8C083),
    info = Color(0xFF9DBEFF),
    positiveSurface = Color(0xFF20392F),
    negativeSurface = Color(0xFF412A30),
    infoSurface = Color(0xFF263850),
    marketKoreaBg = Color(0xFF263850),
    marketKoreaFg = Color(0xFFB5CFFF),
    marketUsaBg = Color(0xFF3E2C38),
    marketUsaFg = Color(0xFFE8B5CE),
    marketJapanBg = Color(0xFF342F46),
    marketJapanFg = Color(0xFFCCBDEC),
    chartTone1 = Color(0xFF9DBEFF),
    chartTone2 = Color(0xFF85CED9),
    chartTone3 = Color(0xFF82D7AC),
    chartTone4 = Color(0xFFE8C083),
    chartTone5 = Color(0xFFFFA5A5),
    chartTone6 = Color(0xFFCCBDEC),
    onPrimary = Color(0xFF102442),
    onSecondary = Color(0xFF1C2B40),
    onError = Color(0xFF400B13),
    textPrimary = Color(0xFFF2F5FA),
    textSecondary = Color(0xFFB7C3D2),
    textHint = Color(0xFFA2B0C2),
    textGold = Color(0xFFC1D5F5),
    glassTint = Color(0x142E5D98),
    glassHighlight = Color(0x33FFFFFF),
    glassShadow = Color(0x6606090E),
)

val LightDashboardColors = DashboardColors(
    isDark = false,
    primary = Color(0xFF285DAB),
    primaryVariant = Color(0xFF365B88),
    secondary = Color(0xFF475E7B),
    background = Color(0xFFF3F5F8),
    backgroundRaised = Color(0xFFE9EEF4),
    surface = Color(0xFFFFFFFF),
    surfacePrimary = Color(0xFFEDF1F6),
    surfaceElevated = Color(0xFFE3EAF2),
    surfaceGlass = Color(0xD9FFFFFF),
    surfaceGlassLight = Color(0xB8F8FBFF),
    surfaceMuted = Color(0xFFE8EDF3),
    surfaceAccent = Color(0xFFE2ECFB),
    surfaceBorder = Color(0xFFC9D3DF),
    surfaceBorderPrimary = Color(0xFF718198),
    error = Color(0xFFA92F3C),
    success = Color(0xFF176C48),
    warning = Color(0xFF815408),
    info = Color(0xFF285DAB),
    positiveSurface = Color(0xFFE0F1E7),
    negativeSurface = Color(0xFFFBE7E9),
    infoSurface = Color(0xFFE2ECFB),
    marketKoreaBg = Color(0xFFE2ECFB),
    marketKoreaFg = Color(0xFF285DAB),
    marketUsaBg = Color(0xFFF6E7ED),
    marketUsaFg = Color(0xFF893D60),
    marketJapanBg = Color(0xFFEDE8F7),
    marketJapanFg = Color(0xFF654B8E),
    chartTone1 = Color(0xFF285DAB),
    chartTone2 = Color(0xFF216E7A),
    chartTone3 = Color(0xFF176C48),
    chartTone4 = Color(0xFF815408),
    chartTone5 = Color(0xFFA92F3C),
    chartTone6 = Color(0xFF654B8E),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onError = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF192431),
    textSecondary = Color(0xFF485A70),
    textHint = Color(0xFF566980),
    textGold = Color(0xFF285DAB),
    glassTint = Color(0x0D648AC0),
    glassHighlight = Color(0xCCFFFFFF),
    glassShadow = Color(0x1F23354D),
)

/** Pure resolution for explicit previews and callers that cannot read composition locals. */
fun dashboardColors(darkTheme: Boolean): DashboardColors =
    if (darkTheme) DarkDashboardColors else LightDashboardColors

/** Read inside composition. Theme instances never mutate a shared palette. */
val LocalDashboardColors = staticCompositionLocalOf { DarkDashboardColors }

// Compatibility tokens: existing composable screens adapt without changing imports.
val Primary: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.primary

val PrimaryVariant: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.primaryVariant

val Secondary: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.secondary

val Background: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.background

val BackgroundRaised: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.backgroundRaised

val Surface: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.surface

val SurfacePrimary: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.surfacePrimary

val SurfaceElevated: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.surfaceElevated

val SurfaceGlass: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.surfaceGlass

val SurfaceGlassLight: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.surfaceGlassLight

val SurfaceMuted: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.surfaceMuted

val SurfaceAccent: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.surfaceAccent

val SurfaceBorder: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.surfaceBorder

val SurfaceBorderPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.surfaceBorderPrimary

val Error: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.error

val Success: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.success

val Warning: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.warning

val Info: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.info

val PositiveSurface: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.positiveSurface

val NegativeSurface: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.negativeSurface

val InfoSurface: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.infoSurface

val MarketKoreaBg: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.marketKoreaBg

val MarketKoreaFg: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.marketKoreaFg

val MarketUsaBg: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.marketUsaBg

val MarketUsaFg: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.marketUsaFg

val MarketJapanBg: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.marketJapanBg

val MarketJapanFg: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.marketJapanFg

val ChartTone1: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.chartTone1

val ChartTone2: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.chartTone2

val ChartTone3: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.chartTone3

val ChartTone4: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.chartTone4

val ChartTone5: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.chartTone5

val ChartTone6: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.chartTone6

val OnPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.onPrimary

val OnSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.onSecondary

val OnError: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.onError

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.textPrimary

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.textSecondary

val TextHint: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.textHint

val TextGold: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.textGold

val GlassTint: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.glassTint

val GlassHighlight: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.glassHighlight

val GlassShadow: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.glassShadow

val OnBackground: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.onBackground

val OnSurface: Color
    @Composable @ReadOnlyComposable get() = LocalDashboardColors.current.onSurface
