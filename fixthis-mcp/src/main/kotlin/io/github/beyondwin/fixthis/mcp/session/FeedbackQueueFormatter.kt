package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.format.DetailMode
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.handoffMessage
import io.github.beyondwin.fixthis.mcp.console.enrichSessionWithStaleness
import kotlinx.serialization.json.JsonObject

object FeedbackQueueFormatter {
    fun toJson(session: SessionDto): String = fixThisJson.encodeToString(JsonObject.serializer(), enrichSessionWithStaleness(session))

    fun toMarkdown(session: SessionDto): String = toMarkdown(session, DetailMode.PRECISE)

    fun toMarkdown(session: SessionDto, detailMode: DetailMode): String = when (detailMode) {
        DetailMode.COMPACT -> toCompactMarkdown(session)
        DetailMode.PRECISE, DetailMode.FULL -> toLegacyMarkdown(session, detailMode)
    }

    private fun toLegacyMarkdown(session: SessionDto, detailMode: DetailMode): String = buildString {
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
                appendFeedbackItem(index + 1, indexedItem.value, detailMode)
            }
        }
    }

    private fun toCompactMarkdown(session: SessionDto): String = CompactHandoffRenderer.render(session)

    private fun StringBuilder.appendFeedbackItem(number: Int, item: AnnotationDto, detailMode: DetailMode) {
        appendLine("## Item $number")
        appendLine()
        appendLine("Request:")
        appendLine(item.comment.ifBlank { "(No request provided)" })
        appendLine()
        appendLine("Target:")
        appendTarget(item)
        appendLine()
        appendLine("Likely Source:")
        appendLikelySource(item.sourceCandidates, item.target, detailMode.sourceCandidateLimit())
        appendLine()
    }

    private fun StringBuilder.appendTarget(item: AnnotationDto) {
        when (val target = item.target) {
            is AnnotationTargetDto.Node -> {
                appendLine("- Type: Compose semantics node")
                appendNodeEvidence(item.selectedNode)
                appendTargetEvidence(item)
                appendTargetReliability(item.targetReliability)
                appendLine("- Bounds: `${target.boundsInWindow.formatBounds()}`")
            }
            is AnnotationTargetDto.Area -> {
                appendLine("- Type: Visual area")
                appendLine("- Bounds: `${target.boundsInWindow.formatBounds()}`")
                appendLine("- Nearby UI: `${item.nearbyNodes.nearbyUiLabel()}`")
                appendTargetEvidence(item)
                appendTargetReliability(item.targetReliability)
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

    private fun StringBuilder.appendTargetEvidence(item: AnnotationDto) {
        item.targetEvidence?.occurrence?.let { occurrence ->
            appendLine("- Occurrence: `${occurrence.selectedOrdinal}/${occurrence.count}`")
        }
        item.targetEvidence?.identityHint?.let { hint ->
            val identity = listOfNotNull(hint.composableNameHint, hint.variantHint).joinToString(":")
            if (identity.isNotBlank()) appendLine("- Identity: `${identity.inlineSafe()}`")
        }
    }

    private fun StringBuilder.appendTargetReliability(reliability: TargetReliability?) {
        if (reliability == null) return
        if (reliability.confidence == TargetConfidence.UNKNOWN && reliability.warnings.isEmpty()) {
            appendLine("- Target confidence: unknown - verify manually before editing.")
            return
        }
        appendLine("- Target confidence: ${reliability.confidence.name.lowercase()} - ${reliability.preciseActionGuidance()}")
        reliability.warnings.forEach { warning ->
            appendLine("- Warning: ${warning.handoffMessage().inlineSafe()}")
        }
    }

    private fun TargetReliability.preciseActionGuidance(): String = when (confidence) {
        TargetConfidence.HIGH -> "inspect the source candidate first."
        TargetConfidence.MEDIUM -> "inspect the candidate and corroborate with screenshot or surrounding code."
        TargetConfidence.LOW -> "treat source paths as hints only."
        TargetConfidence.UNKNOWN -> "verify manually before editing."
    }

    private fun StringBuilder.appendLikelySource(
        sourceCandidates: List<SourceCandidate>,
        target: AnnotationTargetDto,
        maxCandidates: Int,
    ) {
        if (sourceCandidates.isEmpty()) {
            appendLine("No source candidate from current evidence; search by target labels and request.")
            return
        }
        sourceCandidates.take(maxCandidates).forEachIndexed { index, candidate ->
            appendLine(
                "${index + 1}. `${candidate.fileWithLineAndOwner()}` " +
                    "${candidate.markdownConfidence(target)} confidence${candidate.staleMarkerSuffix()}",
            )
            if (candidate.matchedTerms.isNotEmpty()) {
                appendLine("   - matched: ${candidate.matchedTerms.joinToString(", ") { "`${it.inlineSafe()}`" }}")
            }
            if (candidate.matchReasons.isNotEmpty()) {
                appendLine("   - reasons: ${candidate.matchReasons.joinToString(", ")}")
            }
        }
    }

    private fun DetailMode.sourceCandidateLimit(): Int = when (this) {
        DetailMode.PRECISE -> 3
        DetailMode.FULL -> Int.MAX_VALUE
        DetailMode.COMPACT -> error("COMPACT dispatches to CompactHandoffRenderer before sourceCandidateLimit is called")
    }

    private fun SourceCandidate.markdownConfidence(target: AnnotationTargetDto): String = when (target) {
        is AnnotationTargetDto.Area -> "low"
        is AnnotationTargetDto.Node -> confidence.name.lowercase()
    }

    private fun List<FixThisNode>.nearbyUiLabel(): String = flatMap { node -> node.semanticLabels() }
        .distinct()
        .take(6)
        .joinToString("`, `") { it.inlineSafe() }
        .ifBlank { "none captured" }

    private fun FixThisNode.semanticLabels(): List<String> = text + listOfNotNull(editableText) + contentDescription + listOfNotNull(testTag, role)
}
