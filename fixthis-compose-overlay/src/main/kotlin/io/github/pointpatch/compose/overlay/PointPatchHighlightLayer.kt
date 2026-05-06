package io.github.pointpatch.compose.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.ScopeCandidate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun PointPatchHighlightLayer(
    draft: PointPatchDraft,
    modifier: Modifier = Modifier,
    onScopeSelected: (ScopeCandidate) -> Unit = {},
) {
    PointPatchHighlightLayer(
        selectedBounds = draft.selectedBounds,
        scopeCandidates = draft.scopeCandidates,
        selectedScopeNodeUid = draft.selectedScopeNodeUid,
        modifier = modifier,
        onScopeSelected = onScopeSelected,
    )
}

@Composable
fun PointPatchHighlightLayer(
    selectedBounds: PointPatchRect?,
    scopeCandidates: List<ScopeCandidate>,
    selectedScopeNodeUid: String?,
    modifier: Modifier = Modifier,
    onScopeSelected: (ScopeCandidate) -> Unit = {},
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    var chipSizes by remember { mutableStateOf<Map<String, IntSize>>(emptyMap()) }
    val windowOrigin = coordinates?.positionInWindow() ?: Offset.Zero
    val density = LocalDensity.current
    val chipMarginPx = with(density) { 8.dp.toPx() }
    val fallbackChipHeightPx = with(density) { 40.dp.toPx() }
    val maxChipWidthPx = with(density) { 220.dp.toPx() }
    val availableChipWidthPx = if (layoutSize.width > 0) {
        (layoutSize.width - chipMarginPx * 2).coerceAtLeast(0f)
    } else {
        maxChipWidthPx
    }
    val chipWidthPx = min(maxChipWidthPx, availableChipWidthPx).coerceAtLeast(0f)
    val chipMaxWidth = with(density) { chipWidthPx.toDp() }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates = it }
            .onSizeChanged { layoutSize = it },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            selectedBounds?.toLocalRect(windowOrigin)?.let { rect ->
                drawRect(
                    color = Color(0xFF00A3FF).copy(alpha = 0.16f),
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
            scopeCandidates.forEach { candidate ->
                val rect = candidate.boundsInWindow.toLocalRect(windowOrigin)
                drawRect(
                    color = Color(0xFFFFB020).copy(alpha = 0.10f),
                    topLeft = rect.topLeft,
                    size = rect.size,
                )
                drawRect(
                    color = if (candidate.nodeUid == selectedScopeNodeUid) {
                        Color(0xFFFFB020)
                    } else {
                        Color(0xFFFFB020).copy(alpha = 0.58f)
                    },
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        scopeCandidates.forEach { candidate ->
            val bounds = candidate.boundsInWindow
            val chipSize = chipSizes[candidate.nodeUid] ?: IntSize(
                width = chipWidthPx.roundToInt(),
                height = fallbackChipHeightPx.roundToInt(),
            )
            val chipX = clamp(
                value = bounds.left - windowOrigin.x,
                min = chipMarginPx,
                max = if (layoutSize.width > 0) {
                    layoutSize.width - chipSize.width - chipMarginPx
                } else {
                    Float.MAX_VALUE
                },
            )
            val chipY = clamp(
                value = bounds.top - windowOrigin.y,
                min = chipMarginPx,
                max = if (layoutSize.height > 0) {
                    layoutSize.height - chipSize.height - chipMarginPx
                } else {
                    Float.MAX_VALUE
                },
            )
            AssistChip(
                onClick = { onScopeSelected(candidate) },
                label = {
                    Text(
                        text = candidate.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier
                    .widthIn(max = chipMaxWidth)
                    .onSizeChanged { size ->
                        if (chipSizes[candidate.nodeUid] != size) {
                            chipSizes = chipSizes + (candidate.nodeUid to size)
                        }
                    }
                    .offset {
                        IntOffset(
                            x = chipX.roundToInt(),
                            y = chipY.roundToInt(),
                        )
                    }
                    .padding(2.dp),
            )
        }
    }
}

private fun clamp(value: Float, min: Float, max: Float): Float {
    if (max < min) return min
    return max(min, min(value, max))
}

private fun PointPatchRect.toLocalRect(windowOrigin: Offset): Rect =
    Rect(
        left = left - windowOrigin.x,
        top = top - windowOrigin.y,
        right = right - windowOrigin.x,
        bottom = bottom - windowOrigin.y,
    )

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PointPatchHighlightLayerPreview() {
    MaterialTheme {
        PointPatchHighlightLayer(
            selectedBounds = PointPatchRect(32f, 96f, 240f, 152f),
            scopeCandidates = listOf(
                ScopeCandidate(
                    label = "Checkout row",
                    nodeUid = "row",
                    boundsInWindow = PointPatchRect(20f, 80f, 300f, 180f),
                    score = 0.86,
                ),
            ),
            selectedScopeNodeUid = "row",
        )
    }
}
