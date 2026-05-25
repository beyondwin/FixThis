package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.format.DetailMode
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
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
        appendLikelySource(item, item.target, detailMode.sourceCandidateLimit())
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
        appendActionLines(reliability.warnings)
    }

    private fun TargetReliabilityWarning.actionLineText(): String? = when (this) {
        TargetReliabilityWarning.VISUAL_AREA_ONLY ->
            "use screenshot/bounds first, then check whether Compose source explains the pixels."
        TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP ->
            "treat source candidates as hints only; AndroidView/WebView may own the pixels."
        TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET ->
            "no Compose semantics node covers this — search by surrounding labels."
        TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED ->
            "source candidates were ranked without the redacted text — corroborate before editing."
        TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN,
        TargetReliabilityWarning.SOURCE_INDEX_STALE,
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED,
        TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE -> null
    }

    private fun StringBuilder.appendActionLines(warnings: List<TargetReliabilityWarning>) {
        TargetReliabilityWarning.entries
            .filter { it in warnings }
            .mapNotNull { it.actionLineText() }
            .forEach { appendLine("- Action: $it") }
    }

    private fun TargetReliability.preciseActionGuidance(): String = when (confidence) {
        TargetConfidence.HIGH -> "inspect the source candidate first."
        TargetConfidence.MEDIUM -> "inspect the candidate and corroborate with screenshot or surrounding code."
        TargetConfidence.LOW -> "treat source paths as hints only."
        TargetConfidence.UNKNOWN -> "verify manually before editing."
    }

    private fun StringBuilder.appendLikelySource(
        item: AnnotationDto,
        target: AnnotationTargetDto,
        maxCandidates: Int,
    ) {
        val sourceCandidates = item.sourceCandidates
        val editSurfaceCandidates = item.editSurfaceCandidates
        if (sourceCandidates.isEmpty() && editSurfaceCandidates.isEmpty()) {
            appendLine("No source candidate from current evidence; search by target labels and request.")
            return
        }
        if (sourceCandidates.isEmpty()) {
            appendLine("No source candidate; edit-surface hints:")
            val pairing = buildEditSurfacePairing(sourceCandidates, editSurfaceCandidates)
            renderEditSurfaceList(pairing.orphans)
            return
        }
        val pairing = buildEditSurfacePairing(sourceCandidates, editSurfaceCandidates)
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
            pairing.paired[index]?.let { appendEditSurfaceSubLines(it) }
        }
        sourceCandidates.firstOrNull()?.caution
            ?.takeIf { it.isNotBlank() }
            ?.let { appendLine("- note: ${it.inlineSafe()}") }
        if (pairing.orphans.isNotEmpty()) {
            appendLine("Edit Surfaces (unpaired):")
            renderEditSurfaceList(pairing.orphans)
        }
    }

    private fun StringBuilder.renderEditSurfaceList(entries: List<EditSurfaceCandidateDto>) {
        entries.forEachIndexed { index, edit ->
            val kindToken = edit.kind.token()
            val roleToken = edit.role?.let { " role=${it.token()}" }.orEmpty()
            val locator = if (edit.line != null) "${edit.file}:${edit.line}" else edit.file
            val confToken = edit.confidence.name.lowercase()
            val whyToken = edit.reasons.joinToString(",") { it.name.lowercase().replace("_", "-") }
            appendLine(
                "${index + 1}. $kindToken$roleToken -> `${locator.inlineSafe()}` (conf=$confToken, why=$whyToken)",
            )
            edit.note?.takeIf { it.isNotBlank() }?.let {
                appendLine("   - edit-note: ${it.inlineSafe()}")
            }
        }
    }

    private fun EditSurfaceKindDto.token(): String = when (this) {
        EditSurfaceKindDto.CONTAINER_COLOR -> "containerColor"
        EditSurfaceKindDto.TEXT_COLOR -> "textColor"
        EditSurfaceKindDto.TYPOGRAPHY -> "typography"
        EditSurfaceKindDto.SPACING -> "spacing"
        EditSurfaceKindDto.CHIP_COLOR -> "chipColor"
        EditSurfaceKindDto.COMPONENT_RENDERER -> "componentRenderer"
        EditSurfaceKindDto.UNKNOWN -> "unknown"
    }

    private fun EditSurfaceRoleDto.token(): String = name.lowercase().replace("_", "-")

    private fun EditSurfaceCandidateDto.markdownEditLine(): String {
        val kindToken = kind.token()
        val roleToken = role?.let { " role=${it.token()}" }.orEmpty()
        val locator = if (line != null) "$file:$line" else file
        val confToken = confidence.name.lowercase()
        val whyToken = reasons.joinToString(",") { it.name.lowercase().replace("_", "-") }
        return "edit: $kindToken$roleToken -> `${locator.inlineSafe()}` (conf=$confToken, why=$whyToken)"
    }

    private fun StringBuilder.appendEditSurfaceSubLines(paired: List<EditSurfaceCandidateDto>) {
        for (edit in paired) {
            appendLine("   - ${edit.markdownEditLine()}")
            edit.note?.takeIf { it.isNotBlank() }?.let {
                appendLine("   - edit-note: ${it.inlineSafe()}")
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

    internal data class EditSurfacePairing(
        val paired: Map<Int, List<EditSurfaceCandidateDto>>,
        val orphans: List<EditSurfaceCandidateDto>,
    )

    private const val EDIT_SURFACE_PAIR_CAP = 2
    private const val EDIT_SURFACE_ORPHAN_CAP = 2

    private fun buildEditSurfacePairing(
        sourceCandidates: List<SourceCandidate>,
        editSurfaceCandidates: List<EditSurfaceCandidateDto>,
    ): EditSurfacePairing {
        val paired = mutableMapOf<Int, MutableList<EditSurfaceCandidateDto>>()
        val orphans = mutableListOf<EditSurfaceCandidateDto>()
        for (edit in editSurfaceCandidates) {
            if (edit.file.isBlank()) {
                orphans.add(edit)
                continue
            }
            val matchIndex = sourceCandidates.indexOfFirst { it.file == edit.file }
            if (matchIndex >= 0) {
                paired.getOrPut(matchIndex) { mutableListOf() }.add(edit)
            } else {
                orphans.add(edit)
            }
        }
        val cappedPaired = paired.mapValues { (_, list) -> list.take(EDIT_SURFACE_PAIR_CAP) }
        val cappedOrphans = orphans.take(EDIT_SURFACE_ORPHAN_CAP)
        return EditSurfacePairing(cappedPaired, cappedOrphans)
    }

    // Test-only entry point. Forwards to the private helper above.
    internal fun buildEditSurfacePairingForTest(
        sourceCandidates: List<SourceCandidate>,
        editSurfaceCandidates: List<EditSurfaceCandidateDto>,
    ): EditSurfacePairing = buildEditSurfacePairing(sourceCandidates, editSurfaceCandidates)
}
