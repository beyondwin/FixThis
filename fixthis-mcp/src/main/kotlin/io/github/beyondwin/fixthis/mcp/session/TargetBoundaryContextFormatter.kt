package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal object TargetBoundaryContextFormatter {
    private const val MAX_CONTEXT_NODES = 3

    fun compactLine(item: AnnotationDto): String? = item.boundaryContextSummaries().firstOrNull()
        ?.let { context -> "boundaryContext: ${context.summary}; box=${context.node.boundsInWindow.formatBox()}" }

    fun preciseLines(item: AnnotationDto): List<String> {
        val summaries = item.boundaryContextSummaries()
        if (summaries.isEmpty()) return emptyList()
        return buildList {
            summaries.forEach { context ->
                add(
                    "- Boundary context: nearest Compose context ${context.summary}; " +
                        "box=`${context.node.boundsInWindow.formatBounds()}`.",
                )
            }
            add(
                "- Boundary context note: this context helps locate the host; it does not prove Compose owns the selected pixels.",
            )
        }
    }

    private data class BoundaryContextSummary(
        val node: FixThisNode,
        val summary: String,
    )

    private fun AnnotationDto.boundaryContextSummaries(): List<BoundaryContextSummary> {
        if (!hasInteropBoundary()) return emptyList()
        return boundaryContextNodes(MAX_CONTEXT_NODES).mapNotNull { node ->
            node.safeSummaryParts().joinToString("; ")
                .takeIf { summary -> summary.isNotBlank() }
                ?.let { summary -> BoundaryContextSummary(node, summary) }
        }
    }

    private fun AnnotationDto.hasInteropBoundary(): Boolean = targetReliability?.warnings.orEmpty().contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)

    private fun AnnotationDto.boundaryContextNodes(limit: Int): List<FixThisNode> = nearbyNodes
        .asSequence()
        .filter { node -> node.hasSafeContextSignal() }
        .sortedWith(
            compareByDescending<FixThisNode> { it.testTag?.startsWith("comp:") == true }
                .thenBy { it.boundsInWindow.area },
        )
        .take(limit)
        .toList()

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
