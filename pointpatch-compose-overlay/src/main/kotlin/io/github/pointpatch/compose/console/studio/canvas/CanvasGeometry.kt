package io.github.pointpatch.compose.console.studio.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import io.github.pointpatch.compose.console.studio.model.RectPercent

internal fun offsetToPercent(
    offsetPx: Offset,
    previewSize: IntSize,
): Offset {
    if (previewSize.width <= 0 || previewSize.height <= 0) return Offset.Zero
    return Offset(
        x = (offsetPx.x / previewSize.width * 100f).coerceIn(0f, 100f),
        y = (offsetPx.y / previewSize.height * 100f).coerceIn(0f, 100f),
    )
}

internal fun rectPercentToPixelBounds(
    rect: RectPercent,
    previewSize: IntSize,
): Rect {
    if (previewSize.width <= 0 || previewSize.height <= 0) return Rect.Zero
    val left = (rect.x / 100f * previewSize.width).coerceIn(0f, previewSize.width.toFloat())
    val top = (rect.y / 100f * previewSize.height).coerceIn(0f, previewSize.height.toFloat())
    val right = ((rect.x + rect.w) / 100f * previewSize.width).coerceIn(0f, previewSize.width.toFloat())
    val bottom = ((rect.y + rect.h) / 100f * previewSize.height).coerceIn(0f, previewSize.height.toFloat())
    return Rect(
        left = minOf(left, right),
        top = minOf(top, bottom),
        right = maxOf(left, right),
        bottom = maxOf(top, bottom),
    )
}

internal fun rectToPercentBounds(
    rectPx: Rect,
    previewSize: IntSize,
): RectPercent? {
    if (previewSize.width <= 0 || previewSize.height <= 0) return null
    val left = (rectPx.left / previewSize.width * 100f).coerceIn(0f, 100f)
    val top = (rectPx.top / previewSize.height * 100f).coerceIn(0f, 100f)
    val right = (rectPx.right / previewSize.width * 100f).coerceIn(0f, 100f)
    val bottom = (rectPx.bottom / previewSize.height * 100f).coerceIn(0f, 100f)
    return RectPercent(
        x = minOf(left, right),
        y = minOf(top, bottom),
        w = kotlin.math.abs(right - left),
        h = kotlin.math.abs(bottom - top),
    )
}
