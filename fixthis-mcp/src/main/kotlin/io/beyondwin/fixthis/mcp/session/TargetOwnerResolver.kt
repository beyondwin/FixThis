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
            .filter { it.testTag?.isNotBlank() == true || it.text.isNotEmpty() || it.contentDescription.isNotEmpty() || it.role != null }
            .sortedWith(
                compareByDescending<FixThisNode> { it.testTag?.startsWith("comp:") == true }
                    .thenBy { it.boundsInWindow.area() },
            )
        return candidates.firstOrNull()?.let(::TargetOwner)
    }

    private fun FixThisNode.contains(other: FixThisNode): Boolean =
        boundsInWindow.left <= other.boundsInWindow.left &&
            boundsInWindow.top <= other.boundsInWindow.top &&
            boundsInWindow.right >= other.boundsInWindow.right &&
            boundsInWindow.bottom >= other.boundsInWindow.bottom

    private fun List<String>.isPrefixOf(other: List<String>): Boolean =
        size < other.size && other.take(size) == this
}

private fun FixThisRect.area(): Float =
    ((right - left).coerceAtLeast(0f)) * ((bottom - top).coerceAtLeast(0f))
