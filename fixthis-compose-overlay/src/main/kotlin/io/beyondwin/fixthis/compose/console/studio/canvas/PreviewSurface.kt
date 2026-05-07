package io.beyondwin.fixthis.compose.console.studio.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.beyondwin.fixthis.compose.console.studio.StudioViewModel
import io.beyondwin.fixthis.compose.console.studio.model.StudioTool

internal val LocalPreviewSizePx = compositionLocalOf { IntSize.Zero }

@Composable
internal fun PreviewSurface(
    vm: StudioViewModel,
    previewState: FullPreviewDecodeState,
    interactionMode: CanvasInteractionMode,
    modifier: Modifier = Modifier,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val registry = remember(interactionMode == CanvasInteractionMode.WidgetSnapAndRegion) { WidgetRegistry() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sizePx = it }
            .onGloballyPositioned { registry.updateSurfaceCoordinates(it) }
            .annotateDragGestures(
                tool = vm.tool,
                interactionMode = interactionMode,
                widgetRegistry = registry,
                previewSize = sizePx,
                onBegin = { percent, widget, widgetBounds ->
                    vm.beginDrag(
                        percent = percent,
                        widgetTag = widget,
                        widgetBoundsPercent = widgetBounds,
                    )
                },
                onUpdate = { percent -> vm.updateDrag(percent) },
                onEnd = vm::endDrag,
                onCancel = vm::cancelDrag,
                onSelectEmpty = { vm.selectAnnotation(null) },
            ),
    ) {
        CompositionLocalProvider(
            LocalPreviewSizePx provides sizePx,
            LocalWidgetRegistry provides registry,
        ) {
            PreviewScreenshotContent(
                previewState = previewState,
                tool = vm.tool,
                modifier = Modifier.fillMaxSize(),
            )

            AnnotationOverlay(
                annotations = vm.annotations,
                selectedId = vm.selectedId,
                enabled = vm.tool == StudioTool.SELECT,
                onSelect = vm::selectAnnotation,
            )

            if (vm.dragMoved) {
                vm.draggingRect?.let { rect -> DragRect(rect = rect) }
            }
        }
    }
}
