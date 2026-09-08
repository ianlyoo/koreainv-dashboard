package com.koreainv.dashboard.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** Specify every Material role so controls and dialogs never inherit the opposite mode. */
internal fun DashboardColors.materialColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = surfaceAccent,
        onPrimaryContainer = textPrimary,
        inversePrimary = if (isDark) LightDashboardColors.primary else DarkDashboardColors.primary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = surfaceElevated,
        onSecondaryContainer = textPrimary,
        tertiary = primaryVariant,
        onTertiary = onPrimary,
        tertiaryContainer = infoSurface,
        onTertiaryContainer = textPrimary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceDim = if (isDark) background else surfaceElevated,
        surfaceBright = if (isDark) surfaceElevated else surface,
        surfaceContainerLowest = if (isDark) background else surface,
        surfaceContainerLow = if (isDark) surfaceMuted else background,
        surfaceContainer = surfacePrimary,
        surfaceContainerHigh = surfaceElevated,
        surfaceContainerHighest = if (isDark) surfaceAccent else backgroundRaised,
        surfaceVariant = surfacePrimary,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = if (isDark) LightDashboardColors.surface else DarkDashboardColors.surface,
        inverseOnSurface = if (isDark) LightDashboardColors.onSurface else DarkDashboardColors.onSurface,
        error = error,
        onError = onError,
        errorContainer = negativeSurface,
        onErrorContainer = error,
        outline = surfaceBorderPrimary,
        outlineVariant = surfaceBorder,
        scrim = Color.Black,
    )
}

@Composable
fun KoreaInvDashboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseColors = dashboardColors(darkTheme)
    // Optional system accents only: retain calm neutral surfaces and financial semantics.
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val system = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        baseColors.copy(
            primary = system.primary,
            onPrimary = system.onPrimary,
            primaryVariant = system.primary,
            secondary = system.secondary,
            onSecondary = system.onSecondary,
            textGold = system.primary,
        )
    } else {
        baseColors
    }
    val colorScheme = remember(colors) { colors.materialColorScheme() }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            context.findActivity()?.window?.let { window ->
                // Activity 1.8 does not opt into display cutouts on newer Android versions.
                // Without this, the OS leaves a black strip above the app on notched phones.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                // The host paints one continuous canvas beneath transparent system bars.
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.Transparent.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = Color.Transparent.toArgb()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(LocalDashboardColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findActivity()
    else -> null
}
