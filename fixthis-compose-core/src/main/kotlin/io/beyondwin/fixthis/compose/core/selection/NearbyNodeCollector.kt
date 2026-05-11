package io.beyondwin.fixthis.compose.core.selection

import io.beyondwin.fixthis.compose.core.model.FixThisNode

object NearbyNodeCollector {
    fun collect(
        nodes: List<FixThisNode>,
        anchor: FixThisNode,
        maxNodes: Int = 12,
        radiusPx: Float = 480f,
    ): List<FixThisNode> {
        if (maxNodes <= 0 || radiusPx < 0f) return emptyList()

        val seen = mutableSetOf<String>()
        return nodes.asSequence()
            .filter { it.uid != anchor.uid }
            .filter { it.rootIndex == anchor.rootIndex }
            .filter { it.hasMeaningfulSemantic() }
            .map { node -> node to centerDistance(anchor, node) }
            .filter { (_, distance) -> distance <= radiusPx }
            .sortedWith(
                compareBy<Pair<FixThisNode, Float>> { it.second }
                    .thenBy { it.first.boundsInWindow.area }
                    .thenBy { it.first.uid },
            )
            .filter { (node, _) -> seen.add(node.semanticIdentity()) }
            .take(maxNodes)
            .map { it.first }
            .toList()
    }

    private fun centerDistance(first: FixThisNode, second: FixThisNode): Float {
        val dx = first.boundsInWindow.centerX - second.boundsInWindow.centerX
        val dy = first.boundsInWindow.centerY - second.boundsInWindow.centerY
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

internal val io.beyondwin.fixthis.compose.core.model.FixThisRect.centerX: Float
    get() = (left + right) / 2f

internal val io.beyondwin.fixthis.compose.core.model.FixThisRect.centerY: Float
    get() = (top + bottom) / 2f

internal fun FixThisNode.semanticIdentity(): String {
    val parts = listOf(
        role.orEmpty(),
        text.joinToString("\u001f"),
        contentDescription.joinToString("\u001f"),
        testTag.orEmpty(),
    )
    return parts.joinToString("\u001e").lowercase()
}
