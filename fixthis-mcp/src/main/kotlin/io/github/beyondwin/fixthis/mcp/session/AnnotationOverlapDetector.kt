package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.math.hypot

object AnnotationOverlapDetector {
    data class Item(
        val id: String,
        val bounds: FixThisRect,
        val isAreaSelection: Boolean,
        val hasWeakLabels: Boolean = false,
    )

    private const val IOSA_THRESHOLD = 0.25f
    private const val WEAK_LABEL_CENTER_DISTANCE_DP = 24f

    fun detect(items: List<Item>, density: Float = 1.0f): List<List<Item>> {
        if (items.size < 2) return items.map { listOf(it) }

        val groupOf = IntArray(items.size) { it }
        fun root(i: Int): Int {
            var x = i
            while (groupOf[x] != x) {
                groupOf[x] = groupOf[groupOf[x]]
                x = groupOf[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) groupOf[rb] = ra
        }

        for (i in items.indices) {
            for (j in (i + 1) until items.size) {
                if (overlaps(items[i], items[j], density)) {
                    union(i, j)
                }
            }
        }

        return items.indices.groupBy { root(it) }.values.map { idxs -> idxs.map { items[it] } }
    }

    private fun overlaps(a: Item, b: Item, density: Float): Boolean {
        val intersection = intersectionArea(a.bounds, b.bounds)
        if (a.isAreaSelection && b.isAreaSelection) {
            return intersection > 0f
        }
        val aArea = (a.bounds.right - a.bounds.left) * (a.bounds.bottom - a.bounds.top)
        val bArea = (b.bounds.right - b.bounds.left) * (b.bounds.bottom - b.bounds.top)
        val smaller = minOf(aArea, bArea)
        if (smaller > 0f && (intersection / smaller) >= IOSA_THRESHOLD) return true

        if (a.hasWeakLabels || b.hasWeakLabels) {
            val centerDistance = centerDistance(a.bounds, b.bounds)
            val threshold = WEAK_LABEL_CENTER_DISTANCE_DP * density
            if (centerDistance <= threshold) return true
        }
        return false
    }

    private fun intersectionArea(a: FixThisRect, b: FixThisRect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val w = (right - left).coerceAtLeast(0f)
        val h = (bottom - top).coerceAtLeast(0f)
        return w * h
    }

    private fun centerDistance(a: FixThisRect, b: FixThisRect): Float {
        val cx = ((a.left + a.right) / 2f) - ((b.left + b.right) / 2f)
        val cy = ((a.top + a.bottom) / 2f) - ((b.top + b.bottom) / 2f)
        return hypot(cx, cy)
    }
}
