package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal object TargetSummaryFormatter {
    fun render(item: AnnotationDto): String {
        val node = item.selectedNode
        if (node == null) {
            return when (item.target) {
                is AnnotationTargetDto.Area -> "target: visual area"
                is AnnotationTargetDto.Node -> "target: semantics node"
            }
        }

        val redacted = shouldRedact(node, item)
        val parts = mutableListOf<String>()

        node.testTag?.takeIf { it.isNotBlank() }?.let { tag ->
            parts += "tag=\"${tag.compactQuotedValue()}\""
        }

        if (!redacted) {
            node.text.firstOrNull { it.isNotBlank() }?.let { text ->
                parts += "text=\"${text.compactQuotedValue()}\""
            }
            node.contentDescription.firstOrNull { it.isNotBlank() }?.let { description ->
                parts += "contentDescription=\"${description.compactQuotedValue()}\""
            }
        }

        node.role?.takeIf { it.isNotBlank() }?.let { role ->
            parts += "role=${role.inlineSafe()}"
        }

        return when {
            parts.isNotEmpty() && redacted -> "target: redacted sensitive target; ${parts.joinToString("; ")}"
            parts.isNotEmpty() -> "target: ${parts.joinToString("; ")}"
            redacted -> "target: redacted sensitive target"
            else -> "target: semantics node"
        }
    }

    fun isRedacted(item: AnnotationDto): Boolean = item.selectedNode?.let { shouldRedact(it, item) } ?: false

    private fun shouldRedact(node: FixThisNode, item: AnnotationDto): Boolean =
        node.isPassword ||
            node.isSensitive ||
            !node.editableText.isNullOrBlank() ||
            item.targetReliability?.warnings.orEmpty().contains(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED)
}
