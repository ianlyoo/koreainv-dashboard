package com.koreainv.dashboard.ui

import com.koreainv.dashboard.update.ReleasePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationUpdateLogicTest {
    @Test
    fun mandatoryUpdateStaysOpenUntilInstallerLaunches() {
        assertFalse(shouldCloseUpdateDialog(ReleasePolicy.MANDATORY, launchedInstaller = false))
        assertTrue(shouldCloseUpdateDialog(ReleasePolicy.MANDATORY, launchedInstaller = true))
    }

    @Test
    fun recommendedUpdateCanCloseWithoutInstallerLaunch() {
        assertTrue(shouldCloseUpdateDialog(ReleasePolicy.RECOMMENDED, launchedInstaller = false))
    }
}
