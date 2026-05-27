package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal object TargetBoundaryContextFormatter {
    fun compactLine(item: AnnotationDto): String? {
        if (!item.hasInteropBoundary()) return null
        val node = item.boundaryContextNode() ?: return null
        val summary = node.safeSummaryParts().joinToString("; ")
        if (summary.isBlank()) return null
        return "boundaryContext: $summary; box=${node.boundsInWindow.formatBox()}"
    }

    fun preciseLines(item: AnnotationDto): List<String> {
        if (!item.hasInteropBoundary()) return emptyList()
        val node = item.boundaryContextNode() ?: return emptyList()
        val summary = node.safeSummaryParts().joinToString("; ")
        if (summary.isBlank()) return emptyList()
        return listOf(
            "- Boundary context: nearest Compose context $summary; box=`${node.boundsInWindow.formatBounds()}`.",
            "- Boundary context note: this context helps locate the host; it does not prove Compose owns the selected pixels.",
        )
    }

    private fun AnnotationDto.hasInteropBoundary(): Boolean =
        targetReliability?.warnings.orEmpty().contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)

    private fun AnnotationDto.boundaryContextNode(): FixThisNode? = nearbyNodes
        .asSequence()
        .filter { node -> node.hasSafeContextSignal() }
        .sortedWith(
            compareByDescending<FixThisNode> { it.testTag?.startsWith("comp:") == true }
                .thenBy { it.boundsInWindow.area },
        )
        .firstOrNull()

    private fun FixThisNode.hasSafeContextSignal(): Boolean =
        !testTag.isNullOrBlank() ||
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
