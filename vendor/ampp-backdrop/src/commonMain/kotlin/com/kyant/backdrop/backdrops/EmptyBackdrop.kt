package dev.amenhancer.vendor.backdrop.backdrops

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import dev.amenhancer.vendor.backdrop.Backdrop

@Stable
fun emptyBackdrop(): Backdrop = EmptyBackdrop

@Immutable
private object EmptyBackdrop : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
    }
}
