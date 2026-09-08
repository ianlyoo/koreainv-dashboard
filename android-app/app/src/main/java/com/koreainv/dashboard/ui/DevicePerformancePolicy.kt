package com.koreainv.dashboard.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Keep the glass shape and contrast on constrained devices without recording/blurring each frame. */
data class DevicePerformancePolicy(val liveGlass: Boolean) {
    companion object {
        internal fun resolve(sdk: Int, lowRam: Boolean, memoryClassMb: Int, totalMemoryBytes: Long): DevicePerformancePolicy {
            val constrained = lowRam || memoryClassMb <= 128 ||
                (totalMemoryBytes > 0 && totalMemoryBytes <= 3L * 1024 * 1024 * 1024)
            return DevicePerformancePolicy(liveGlass = sdk >= 31 && !constrained)
        }

        fun read(context: Context): DevicePerformancePolicy {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return DevicePerformancePolicy(liveGlass = false)
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            return resolve(Build.VERSION.SDK_INT, manager.isLowRamDevice, manager.memoryClass, info.totalMem)
        }
    }
}

val LocalDevicePerformancePolicy = staticCompositionLocalOf { DevicePerformancePolicy(liveGlass = true) }

@Composable
fun rememberDevicePerformancePolicy(): DevicePerformancePolicy {
    val context = LocalContext.current.applicationContext
    return remember(context) { DevicePerformancePolicy.read(context) }
}
