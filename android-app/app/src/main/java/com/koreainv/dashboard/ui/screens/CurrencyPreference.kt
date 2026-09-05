package com.koreainv.dashboard.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

data class CurrencyPreference(
    val mode: CurrencyDisplayMode,
    val onModeChange: (CurrencyDisplayMode) -> Unit,
)

internal val LocalCurrencyPreference = compositionLocalOf<CurrencyPreference?> { null }

/** A single display preference across tabs; standalone previews still remain interactive. */
@Composable
fun rememberCurrencyPreference(): CurrencyPreference {
    val shared = LocalCurrencyPreference.current
    var localMode by rememberSaveable { mutableStateOf(CurrencyDisplayMode.KRW) }
    return shared ?: CurrencyPreference(localMode) { localMode = it }
}
