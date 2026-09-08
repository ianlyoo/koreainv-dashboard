package com.koreainv.dashboard.ui.appearance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Non-sensitive preferences, deliberately independent of the encrypted account store. */
class AppearancePreference(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dashboard_appearance", Context.MODE_PRIVATE,
    )

    // Read before the first themed composition, rather than rendering SYSTEM while loading.
    private var selectedMode by mutableStateOf(ThemeMode.fromStoredValue(
        runCatching { preferences.getString("theme_mode", null) }.getOrNull(),
    ))

    val themeMode: ThemeMode get() = selectedMode

    fun setThemeMode(mode: ThemeMode) {
        if (mode == themeMode) return
        preferences.edit().putString("theme_mode", mode.name).apply()
        selectedMode = mode
    }
}

/** Hosts should remember one instance above their theme and pass its state/callback to the UI. */
@Composable
fun rememberAppearancePreference(): AppearancePreference {
    val context = LocalContext.current.applicationContext
    return remember(context) { AppearancePreference(context) }
}
