package io.github.pointpatch.compose.console.studio.canvas

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import io.github.pointpatch.compose.console.studio.StudioTestTags
import io.github.pointpatch.compose.console.studio.model.StudioTool
import io.github.pointpatch.compose.console.studio.theme.StudioColors

internal data class WidgetEntry(
    val tag: String,
    val boundsInSurface: Rect,
)

internal class WidgetRegistry {
    private val entries = mutableStateListOf<WidgetEntry>()
    private var surfaceCoordinates: LayoutCoordinates? = null

    fun updateSurfaceCoordinates(coordinates: LayoutCoordinates) {
        surfaceCoordinates = coordinates
    }

    fun register(tag: String, boundsInSurface: Rect) {
        val entry = WidgetEntry(tag = tag, boundsInSurface = boundsInSurface)
        val index = entries.indexOfFirst { it.tag == tag }
        if (index >= 0) {
            entries[index] = entry
        } else {
            entries.add(entry)
        }
    }

    fun register(tag: String, coordinates: LayoutCoordinates) {
        val surface = surfaceCoordinates ?: return
        val surfaceTopLeft = surface.localToRoot(Offset.Zero)
        val widgetTopLeft = coordinates.localToRoot(Offset.Zero)
        val topLeft = widgetTopLeft - surfaceTopLeft
        register(tag, Rect(offset = topLeft, size = coordinates.size.toSize()))
    }

    fun hitTest(point: Offset): WidgetEntry? =
        entries
            .asSequence()
            .filter { it.boundsInSurface.contains(point) }
            .minByOrNull { it.boundsInSurface.width * it.boundsInSurface.height }
}

internal val LocalWidgetRegistry = compositionLocalOf { WidgetRegistry() }

internal fun Modifier.studioWidget(
    tag: String,
    tool: StudioTool,
): Modifier = composed {
    val registry = LocalWidgetRegistry.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val outline by animateColorAsState(
        targetValue = if (tool == StudioTool.ANNOTATE && hovered) StudioColors.WidgetHoverOutline else Color.Transparent,
        animationSpec = tween(120),
        label = "widgetOutline",
    )
    this
        .onGloballyPositioned { coordinates -> registry.register(tag, coordinates) }
        .testTag(StudioTestTags.widget(tag))
        .hoverable(interaction)
        .border(2.dp, outline, RoundedCornerShape(4.dp))
}
