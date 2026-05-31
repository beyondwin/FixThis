package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal object TargetBoundaryContextFormatter {
    private const val MAX_CONTEXT_NODES = 3

    internal data class BoundaryContextRow(
        val kind: BoundaryContextKind,
        val node: FixThisNode,
        val summary: String,
    )

    internal enum class BoundaryContextKind(
        val compactToken: String,
        val preciseLabel: String,
        val sortRank: Int,
    ) {
        HOST("boundaryHost", "Boundary host", 0),
        ANCESTOR("boundaryAncestor", "Boundary ancestor", 1),
        CONTEXT("boundaryContext", "Boundary context", 2),
    }

    fun compactLine(item: AnnotationDto): String? = item.boundaryContextRows().firstOrNull()
        ?.let { row -> "${row.kind.compactToken}: ${row.summary}; box=${row.node.boundsInWindow.formatBox()}" }

    fun preciseLines(item: AnnotationDto): List<String> {
        val rows = item.boundaryContextRows()
        if (rows.isEmpty()) return emptyList()
        return buildList {
            rows.forEach { row ->
                add(
                    "- ${row.kind.preciseLabel}: ${row.summary}; " +
                        "box=`${row.node.boundsInWindow.formatBounds()}`.",
                )
            }
            add(
                "- Boundary context note: this context helps locate the host; it does not prove Compose owns the selected pixels.",
            )
        }
    }

    internal fun structuredRows(item: AnnotationDto): List<BoundaryContextRow> = item.boundaryContextRows()

    private fun AnnotationDto.boundaryContextRows(): List<BoundaryContextRow> {
        if (!hasInteropBoundary()) return emptyList()
        val targetBounds = target.bounds()
        return nearbyNodes
            .asSequence()
            .filter { node -> node.hasSafeContextSignal() }
            .mapNotNull { node ->
                val summary = node.safeSummaryParts().joinToString("; ").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                BoundaryContextRow(
                    kind = node.boundaryContextKind(targetBounds),
                    node = node,
                    summary = summary,
                )
            }
            .sortedWith(
                compareBy<BoundaryContextRow> { it.kind.sortRank }
                    .thenByDescending { it.node.testTag?.startsWith("comp:") == true }
                    .thenBy { it.node.boundsInWindow.area },
            )
            .take(MAX_CONTEXT_NODES)
            .toList()
    }

    private fun AnnotationDto.hasInteropBoundary(): Boolean =
        targetReliability?.warnings.orEmpty().contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)

    private fun AnnotationTargetDto.bounds(): FixThisRect = when (this) {
        is AnnotationTargetDto.Area -> boundsInWindow
        is AnnotationTargetDto.Node -> boundsInWindow
    }

    private fun FixThisNode.boundaryContextKind(targetBounds: FixThisRect): BoundaryContextKind {
        val compTagged = testTag?.startsWith("comp:") == true
        val containsTarget = boundsInWindow.contains(targetBounds)
        val muchLargerThanTarget = boundsInWindow.area > targetBounds.area * 6f
        return when {
            compTagged && containsTarget && muchLargerThanTarget -> BoundaryContextKind.ANCESTOR
            compTagged && boundsInWindow.intersects(targetBounds) -> BoundaryContextKind.HOST
            containsTarget && boundsInWindow.area > targetBounds.area -> BoundaryContextKind.ANCESTOR
            else -> BoundaryContextKind.CONTEXT
        }
    }

    private fun FixThisRect.contains(other: FixThisRect): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    private fun FixThisRect.intersects(other: FixThisRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun FixThisNode.hasSafeContextSignal(): Boolean = !testTag.isNullOrBlank() ||
        !role.isNullOrBlank() ||
        (!isSensitive && !isPassword && editableText.isNullOrBlank() && text.any { it.isNotBlank() }) ||
        (!isSensitive && !isPassword && contentDescription.any { it.isNotBlank() })

    private fun FixThisNode.safeSummaryParts(): List<String> = buildList {
        testTag?.takeIf { it.isNotBlank() }?.let { add("tag=\"${it.compactQuotedValue()}\"") }
        role?.takeIf { it.isNotBlank() }?.let { add("role=${it.inlineSafe()}") }
        if (!isSensitive && !isPassword && editableText.isNullOrBlank()) {
            text.firstOrNull { it.isNotBlank() }?.let { add("text=\"${it.compactQuotedValue()}\"") }
            contentDescription.firstOrNull { it.isNotBlank() }?.let {
                add("contentDescription=\"${it.compactQuotedValue()}\"")
            }
        }
    }
}
