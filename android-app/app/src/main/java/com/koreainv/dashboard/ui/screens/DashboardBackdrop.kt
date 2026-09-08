package com.koreainv.dashboard.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop

/** A recording belongs to one body node for its entire lifetime; it is never reassigned. */
@Stable
internal class DashboardBodyBackdrop(val recording: LayerBackdrop) : Backdrop {
    var sourceCoordinates: LayoutCoordinates? by mutableStateOf(null)

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        // Source and consumer can detach independently when Navigation interrupts a
        // transition. Do not enter the library's coordinate conversion in that frame.
        if (sourceCoordinates?.isAttached != true || coordinates?.isAttached != true) return
        with(recording) { drawBackdrop(density, coordinates, layerBlock) }
    }
}

/** Stable bottom-bar source; snapshot reads invalidate drawing when the target changes. */
@Stable
internal class DashboardNavigationBackdrop : Backdrop {
    private var source: DashboardBodyBackdrop? by mutableStateOf(null)

    override val isCoordinatesDependent: Boolean = true

    fun publish(body: DashboardBodyBackdrop) {
        source = body
    }

    fun clear(body: DashboardBodyBackdrop) {
        // An outgoing destination must not clear a newer destination's registration.
        if (source === body) source = null
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        val current = source ?: return
        with(current) { drawBackdrop(density, coordinates, layerBlock) }
    }
}
