package com.koreainv.dashboard.ui

import org.junit.Assert.*
import org.junit.Test

class DevicePerformancePolicyTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun constrainedDevicesKeepReadableStaticGlass() {
        assertFalse(DevicePerformancePolicy.resolve(35, true, 256, 4 * gib).liveGlass)
        assertFalse(DevicePerformancePolicy.resolve(35, false, 128, 4 * gib).liveGlass)
        assertFalse(DevicePerformancePolicy.resolve(35, false, 256, 3 * gib).liveGlass)
        assertFalse(DevicePerformancePolicy.resolve(30, false, 512, 8 * gib).liveGlass)
    }

    @Test fun capableDevicesKeepApprovedLiveGlass() {
        assertTrue(DevicePerformancePolicy.resolve(31, false, 256, 4 * gib).liveGlass)
        assertTrue(DevicePerformancePolicy.resolve(35, false, 512, 8 * gib).liveGlass)
        assertTrue(DevicePerformancePolicy.resolve(35, false, 256, 0).liveGlass)
    }
}
