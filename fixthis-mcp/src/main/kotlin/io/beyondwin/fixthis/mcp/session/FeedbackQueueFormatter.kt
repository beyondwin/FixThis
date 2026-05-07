package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

object FeedbackQueueFormatter {
    fun toJson(session: SessionDto): String =
        fixThisJson.encodeToString(SessionDto.serializer(), session)

    fun toMarkdown(session: SessionDto): String = buildString {
        appendLine("# FixThis Feedback Handoff")
        appendLine()
        appendLine("- Package: `${session.packageName}`")
        appendLine("- Feedback Items: `${session.items.size}`")
        appendLine("- Screenshots: local debug artifacts available through FixThis tooling")
        appendLine()

        val orderedItems = session.items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<AnnotationDto>> { it.value.sequenceNumber ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
        if (orderedItems.isEmpty()) {
            appendLine("No feedback items.")
            appendLine()
        } else {
            orderedItems.forEachIndexed { index, indexedItem ->
                appendFeedbackItem(index + 1, indexedItem.value)
            }
        }
    }

    private fun StringBuilder.appendFeedbackItem(number: Int, item: AnnotationDto) {
        appendLine("## Item $number")
        appendLine()
        appendLine("Request:")
        appendLine(item.comment.ifBlank { "(No request provided)" })
        appendLine()
        appendLine("Target:")
        appendTarget(item)
        appendLine()
        appendLine("Likely Source:")
        appendLikelySource(item.sourceCandidates, item.target)
        appendLine()
    }

    private fun StringBuilder.appendTarget(item: AnnotationDto) {
        when (val target = item.target) {
            is AnnotationTargetDto.Node -> {
                appendLine("- Type: Compose semantics node")
                appendNodeEvidence(item.selectedNode)
                appendLine("- Bounds: `${target.boundsInWindow.formatBounds()}`")
            }
            is AnnotationTargetDto.Area -> {
                appendLine("- Type: Visual area")
                appendLine("- Bounds: `${target.boundsInWindow.formatBounds()}`")
                appendLine("- Nearby UI: `${item.nearbyNodes.nearbyUiLabel()}`")
                appendLine("- Note: area selection only; verify screenshot and source candidates.")
            }
        }
    }

    private fun StringBuilder.appendNodeEvidence(node: FixThisNode?) {
        if (node == null) return
        node.text.takeIf { it.isNotEmpty() }?.let { appendLine("- Text: `${it.joinToString(" | ").inlineSafe()}`") }
        node.editableText?.takeIf { it.isNotBlank() }?.let { appendLine("- Editable Text: `${it.inlineSafe()}`") }
        node.contentDescription.takeIf { it.isNotEmpty() }?.let {
            appendLine("- Content Description: `${it.joinToString(" | ").inlineSafe()}`")
        }
        node.testTag?.takeIf { it.isNotBlank() }?.let { appendLine("- Test Tag: `${it.inlineSafe()}`") }
        node.role?.takeIf { it.isNotBlank() }?.let { appendLine("- Role: `${it.inlineSafe()}`") }
    }

    private fun StringBuilder.appendLikelySource(sourceCandidates: List<SourceCandidate>, target: AnnotationTargetDto) {
        if (sourceCandidates.isEmpty()) {
            appendLine("No source candidate from current evidence; search by target labels and request.")
            return
        }
        sourceCandidates.forEachIndexed { index, candidate ->
            appendLine("${index + 1}. `${candidate.fileWithLine()}` ${candidate.markdownConfidence(target)} confidence")
            if (candidate.matchedTerms.isNotEmpty()) {
                appendLine("   - matched: ${candidate.matchedTerms.joinToString(", ") { "`${it.inlineSafe()}`" }}")
            }
            if (candidate.matchReasons.isNotEmpty()) {
                appendLine("   - reasons: ${candidate.matchReasons.joinToString(", ")}")
            }
        }
    }

    private fun SourceCandidate.fileWithLine(): String =
        line?.let { "$file:$it" } ?: file

    private fun SourceCandidate.markdownConfidence(target: AnnotationTargetDto): String =
        when (target) {
            is AnnotationTargetDto.Area -> "low"
            is AnnotationTargetDto.Node -> confidence.name.lowercase()
        }

    private fun FixThisRect.formatBounds(): String =
        "$left,$top,$right,$bottom"

    private fun List<FixThisNode>.nearbyUiLabel(): String =
        flatMap { node -> node.semanticLabels() }
            .distinct()
            .take(6)
            .joinToString("`, `") { it.inlineSafe() }
            .ifBlank { "none captured" }

    private fun FixThisNode.semanticLabels(): List<String> =
        text + listOfNotNull(editableText) + contentDescription + listOfNotNull(testTag, role)

    private fun String.inlineSafe(): String =
        replace("`", "'")
}
