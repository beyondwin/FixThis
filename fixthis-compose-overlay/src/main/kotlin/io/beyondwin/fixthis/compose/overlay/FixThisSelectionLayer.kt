package io.beyondwin.fixthis.compose.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

@Composable
fun FixThisSelectionLayer(
    mode: OverlayMode,
    modifier: Modifier = Modifier,
    onTap: (xInWindow: Float, yInWindow: Float) -> Unit,
    onAreaSelected: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit,
) {
    if (mode !is OverlayMode.Select) {
        Box(modifier = modifier)
        return
    }

    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    val selectionRect = normalizedRect(dragStart, dragCurrent)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.10f))
            .onGloballyPositioned { coordinates = it }
            .pointerInput(mode.requestId) {
                detectTapGestures(
                    onTap = { localOffset ->
                        val windowOffset = coordinates?.localToWindow(localOffset) ?: localOffset
                        onTap(windowOffset.x, windowOffset.y)
                    },
                )
            }
            .pointerInput(mode.requestId) {
                detectDragGestures(
                    onDragStart = { start ->
                        dragStart = start
                        dragCurrent = start
                    },
                    onDrag = { change, _ ->
                        dragCurrent = change.position
                        change.consume()
                    },
                    onDragCancel = {
                        dragStart = null
                        dragCurrent = null
                    },
                    onDragEnd = {
                        val start = dragStart
                        val end = dragCurrent
                        if (start != null && end != null) {
                            val topLeft = coordinates?.localToWindow(
                                Offset(min(start.x, end.x), min(start.y, end.y)),
                            ) ?: Offset(min(start.x, end.x), min(start.y, end.y))
                            val bottomRight = coordinates?.localToWindow(
                                Offset(max(start.x, end.x), max(start.y, end.y)),
                            ) ?: Offset(max(start.x, end.x), max(start.y, end.y))
                            onAreaSelected(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
                        }
                        dragStart = null
                        dragCurrent = null
                    },
                )
            },
    ) {
        SelectionCanvas(selectionRect = selectionRect)
        SelectionCancelButton(onCancel = onCancel)
    }
}

@Composable
private fun BoxScope.SelectionCanvas(selectionRect: Rect?) {
    Canvas(modifier = Modifier.matchParentSize()) {
        selectionRect?.let { rect ->
            drawRect(
                color = Color(0xFF00A3FF).copy(alpha = 0.18f),
                topLeft = rect.topLeft,
                size = rect.size,
            )
            drawRect(
                color = Color(0xFF00A3FF),
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun BoxScope.SelectionCancelButton(onCancel: () -> Unit) {
    Button(
        onClick = onCancel,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
            .sizeIn(minWidth = 88.dp, minHeight = 40.dp),
    ) {
        Text(text = "Cancel", maxLines = 1)
    }
}

private fun normalizedRect(start: Offset?, current: Offset?): Rect? {
    if (start == null || current == null) return null
    return Rect(
        left = min(start.x, current.x),
        top = min(start.y, current.y),
        right = max(start.x, current.x),
        bottom = max(start.y, current.y),
    )
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun FixThisSelectionLayerPreview() {
    MaterialTheme {
        FixThisSelectionLayer(
            mode = OverlayMode.Select(requestId = "preview"),
            onTap = { _, _ -> },
            onAreaSelected = { _, _, _, _ -> },
            onCancel = {},
        )
    }
}
