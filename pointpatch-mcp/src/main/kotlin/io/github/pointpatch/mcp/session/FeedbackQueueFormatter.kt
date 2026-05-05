package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.SourceCandidate

object FeedbackQueueFormatter {
    fun toJson(session: FeedbackSession): String =
        pointPatchJson.encodeToString(FeedbackSession.serializer(), session)

    fun toMarkdown(session: FeedbackSession): String = buildString {
        appendLine("# PointPatch Feedback Handoff")
        appendLine()
        appendLine("- Package: `${session.packageName}`")
        appendLine("- Feedback Items: `${session.items.size}`")
        appendLine("- Screenshots: local debug artifacts available through PointPatch tooling")
        appendLine()

        val orderedItems = session.items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<FeedbackItem>> { it.value.sequenceNumber ?: Int.MAX_VALUE }
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

    private fun StringBuilder.appendFeedbackItem(number: Int, item: FeedbackItem) {
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

    private fun StringBuilder.appendTarget(item: FeedbackItem) {
        when (val target = item.target) {
            is FeedbackTarget.Node -> {
                appendLine("- Type: Compose semantics node")
                appendNodeEvidence(item.selectedNode)
                appendLine("- Bounds: `${target.boundsInWindow.formatBounds()}`")
            }
            is FeedbackTarget.Area -> {
                appendLine("- Type: Visual area")
                appendLine("- Bounds: `${target.boundsInWindow.formatBounds()}`")
                appendLine("- Nearby UI: `${item.nearbyNodes.nearbyUiLabel()}`")
                appendLine("- Note: area selection only; verify screenshot and source candidates.")
            }
        }
    }

    private fun StringBuilder.appendNodeEvidence(node: PointPatchNode?) {
        if (node == null) return
        node.text.takeIf { it.isNotEmpty() }?.let { appendLine("- Text: `${it.joinToString(" | ").inlineSafe()}`") }
        node.editableText?.takeIf { it.isNotBlank() }?.let { appendLine("- Editable Text: `${it.inlineSafe()}`") }
        node.contentDescription.takeIf { it.isNotEmpty() }?.let {
            appendLine("- Content Description: `${it.joinToString(" | ").inlineSafe()}`")
        }
        node.testTag?.takeIf { it.isNotBlank() }?.let { appendLine("- Test Tag: `${it.inlineSafe()}`") }
        node.role?.takeIf { it.isNotBlank() }?.let { appendLine("- Role: `${it.inlineSafe()}`") }
    }

    private fun StringBuilder.appendLikelySource(sourceCandidates: List<SourceCandidate>, target: FeedbackTarget) {
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

    private fun SourceCandidate.markdownConfidence(target: FeedbackTarget): String =
        when (target) {
            is FeedbackTarget.Area -> "low"
            is FeedbackTarget.Node -> confidence.name.lowercase()
        }

    private fun PointPatchRect.formatBounds(): String =
        "$left,$top,$right,$bottom"

    private fun List<PointPatchNode>.nearbyUiLabel(): String =
        flatMap { node -> node.semanticLabels() }
            .distinct()
            .take(6)
            .joinToString("`, `") { it.inlineSafe() }
            .ifBlank { "none captured" }

    private fun PointPatchNode.semanticLabels(): List<String> =
        text + listOfNotNull(editableText) + contentDescription + listOfNotNull(testTag, role)

    private fun String.inlineSafe(): String =
        replace("`", "'")
}
