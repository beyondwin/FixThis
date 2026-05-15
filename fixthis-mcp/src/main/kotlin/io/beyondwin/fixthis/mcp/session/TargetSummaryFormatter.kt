package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal object TargetSummaryFormatter {
    fun render(item: AnnotationDto, owner: TargetOwner? = null): String {
        val node = item.selectedNode
        if (node == null) {
            return fallbackTargetLine(item.target)
        }

        val redacted = shouldRedact(node, item)
        val parts = nodeTargetParts(node, owner, redacted)
        return targetLine(parts, redacted)
    }

    fun isRedacted(item: AnnotationDto): Boolean = item.selectedNode?.let { shouldRedact(it, item) } ?: false

    private fun fallbackTargetLine(target: AnnotationTargetDto): String = when (target) {
        is AnnotationTargetDto.Area -> "target: visual area"
        is AnnotationTargetDto.Node -> "target: semantics node"
    }

    private fun nodeTargetParts(
        node: FixThisNode,
        owner: TargetOwner?,
        redacted: Boolean,
    ): List<String> = buildList {
        node.testTag?.takeIf { it.isNotBlank() }?.let { tag ->
            add("tag=\"${tag.compactQuotedValue()}\"")
        }

        if (!redacted) {
            node.text.firstOrNull { it.isNotBlank() }?.let { text ->
                add("text=\"${text.compactQuotedValue()}\"")
            }
            node.contentDescription.firstOrNull { it.isNotBlank() }?.let { description ->
                add("contentDescription=\"${description.compactQuotedValue()}\"")
            }
        }

        node.role?.takeIf { it.isNotBlank() }?.let { role ->
            add("role=${role.inlineSafe()}")
        }

        owner?.node?.testTag?.takeIf { it.isNotBlank() && it != node.testTag }?.let { tag ->
            add("inside tag=\"${tag.compactQuotedValue()}\"")
        }
    }

    private fun targetLine(parts: List<String>, redacted: Boolean): String = when {
        parts.isNotEmpty() && redacted -> "target: redacted sensitive target; ${parts.joinToString("; ")}"
        parts.isNotEmpty() -> "target: ${parts.joinToString("; ")}"
        redacted -> "target: redacted sensitive target"
        else -> "target: semantics node"
    }

    private fun shouldRedact(node: FixThisNode, item: AnnotationDto): Boolean = node.isPassword ||
        node.isSensitive ||
        !node.editableText.isNullOrBlank() ||
        item.targetReliability?.warnings.orEmpty().contains(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED)
}
