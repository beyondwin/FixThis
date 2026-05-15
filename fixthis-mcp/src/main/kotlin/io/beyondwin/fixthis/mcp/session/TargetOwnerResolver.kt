package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect

internal data class TargetOwner(
    val node: FixThisNode,
)

internal object TargetOwnerResolver {
    fun resolve(item: AnnotationDto, screen: SnapshotDto?): TargetOwner? {
        val selected = item.selectedNode ?: return null
        val candidates = screen?.roots.orEmpty()
            .flatMap { it.mergedNodes }
            .filter { it.uid != selected.uid }
            .filter { candidate -> candidate.contains(selected) || candidate.path.isPrefixOf(selected.path) }
            .filter {
                it.testTag?.isNotBlank() == true ||
                    it.text.isNotEmpty() ||
                    it.contentDescription.isNotEmpty() ||
                    it.role != null
            }
            .sortedWith(
                compareByDescending<FixThisNode> { it.testTag?.startsWith("comp:") == true }
                    .thenBy { it.boundsInWindow.area() },
            )
        return candidates.firstOrNull()?.let(::TargetOwner)
    }

    private fun FixThisNode.contains(other: FixThisNode): Boolean {
        val outer = boundsInWindow
        val inner = other.boundsInWindow
        return outer.left <= inner.left &&
            outer.top <= inner.top &&
            outer.right >= inner.right &&
            outer.bottom >= inner.bottom
    }

    private fun List<String>.isPrefixOf(other: List<String>): Boolean {
        val prefix = other.take(size)
        return size < other.size && prefix == this
    }
}

private fun FixThisRect.area(): Float {
    val width = (right - left).coerceAtLeast(0f)
    val height = (bottom - top).coerceAtLeast(0f)
    return width * height
}
