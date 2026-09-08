package com.koreainv.dashboard.ui.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test fun missingOrInvalidPreferenceDefaultsToSystem() {
        listOf(null, "", "dark", "unknown").forEach {
            assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue(it))
        }
    }

    @Test fun everyModeRoundTripsThroughItsPersistedName() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStoredValue(it.name)) }
    }

    @Test fun systemChangesOnlyAffectSystemMode() {
        assertFalse(ThemeMode.SYSTEM.isDark(false))
        assertTrue(ThemeMode.SYSTEM.isDark(true))
        listOf(false, true).forEach { systemIsDark ->
            assertFalse(ThemeMode.LIGHT.isDark(systemIsDark))
            assertTrue(ThemeMode.DARK.isDark(systemIsDark))
        }
    }
}
