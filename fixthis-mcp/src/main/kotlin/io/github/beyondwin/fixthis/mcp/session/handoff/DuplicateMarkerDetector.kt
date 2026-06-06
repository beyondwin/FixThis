package io.github.beyondwin.fixthis.mcp.session.handoff

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect

/**
 * Detects duplicate compact handoff items by grouping them on a composite key.
 *
 * Two items are duplicates iff they share:
 *   - the same (fileLine, testTag)
 *   - the same path leaves
 *   - the same boundsInWindow
 *
 * The first item in insertion order is canonical; later items receive a reference
 * back to the canonical item's marker number.
 */
object DuplicateMarkerDetector {

    data class Key(
        val fileLine: String?,
        val testTag: String?,
        val pathLeaves: List<String>,
        val bounds: FixThisRect,
    )

    data class Item(val itemId: String, val markerNumber: Int, val key: Key)

    /**
     * Returns a map from duplicate [Item.itemId] to the canonical item's [Item.markerNumber].
     * Items that are canonical (first occurrence) are NOT included in the result map.
     */
    fun detect(items: List<Item>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        items.groupBy { it.key }.values.forEach { group ->
            if (group.size >= 2) {
                val canonical = group.first().markerNumber
                group.drop(1).forEach { dup ->
                    result[dup.itemId] = canonical
                }
            }
        }
        return result
    }
}
