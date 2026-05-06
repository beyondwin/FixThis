package io.github.pointpatch.compose.console.studio.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import io.github.pointpatch.compose.console.studio.model.RectPercent
import io.github.pointpatch.compose.console.studio.model.StudioTool
import kotlinx.coroutines.CancellationException

internal fun isPreviewGestureEnabled(
    tool: StudioTool,
    interactionMode: CanvasInteractionMode,
): Boolean =
    tool == StudioTool.ANNOTATE && interactionMode != CanvasInteractionMode.AnnotationUnavailable

internal fun Modifier.annotateDragGestures(
    tool: StudioTool,
    interactionMode: CanvasInteractionMode,
    widgetRegistry: WidgetRegistry,
    previewSize: IntSize,
    onBegin: (Offset, String?, RectPercent?) -> Unit,
    onUpdate: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
    onSelectEmpty: () -> Unit,
): Modifier =
    pointerInput(tool, interactionMode, previewSize, widgetRegistry) {
        if (!isPreviewGestureEnabled(tool, interactionMode)) {
            detectTapGestures { onSelectEmpty() }
            return@pointerInput
        }
        detectAnnotateDrag(
            widgetRegistry = widgetRegistry.takeIf {
                interactionMode == CanvasInteractionMode.WidgetSnapAndRegion
            },
            previewSize = previewSize,
            onBegin = onBegin,
            onUpdate = onUpdate,
            onEnd = onEnd,
            onCancel = onCancel,
        )
    }

private suspend fun PointerInputScope.detectAnnotateDrag(
    widgetRegistry: WidgetRegistry?,
    previewSize: IntSize,
    onBegin: (Offset, String?, RectPercent?) -> Unit,
    onUpdate: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val widget = widgetRegistry?.hitTest(down.position)
        onBegin(
            offsetToPercent(down.position, previewSize),
            widget?.tag,
            widget?.boundsInSurface?.let { rectToPercentBounds(it, previewSize) },
        )
        try {
            val completed = drag(down.id) { change ->
                onUpdate(offsetToPercent(change.position, previewSize))
                change.consume()
            }
            if (completed) {
                onEnd()
            } else {
                onCancel()
                return@awaitEachGesture
            }
        } catch (cancellation: CancellationException) {
            onCancel()
            throw cancellation
        }
    }
}
