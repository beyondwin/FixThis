package io.github.pointpatch.compose.console.studio.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.pointpatch.compose.console.studio.model.RectPercent
import io.github.pointpatch.compose.console.studio.theme.StudioColors

@Composable
internal fun DragRect(
    rect: RectPercent,
    modifier: Modifier = Modifier,
) {
    val previewSize = LocalPreviewSizePx.current
    if (previewSize.width <= 0 || previewSize.height <= 0) return

    val density = LocalDensity.current
    val boundsPx = rectPercentToPixelBounds(rect, previewSize)
    val leftDp = with(density) { boundsPx.left.toDp() }
    val topDp = with(density) { boundsPx.top.toDp() }
    val widthDp = with(density) { boundsPx.width.toDp() }
    val heightDp = with(density) { boundsPx.height.toDp() }
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier
            .offset(x = leftDp, y = topDp)
            .size(width = widthDp, height = heightDp)
            .clip(shape)
            .background(StudioColors.DragRectFill)
            .drawBehind {
                drawRoundRect(
                    color = StudioColors.DragRectBorder,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                    ),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            },
    )
}
