package io.github.beyondwin.fixthis.compose.core.domain.ui

data class DomainRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}
