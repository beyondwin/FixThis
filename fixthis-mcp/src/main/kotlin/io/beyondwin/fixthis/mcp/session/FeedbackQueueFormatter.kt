package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.format.DetailMode
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

object FeedbackQueueFormatter {
    fun toJson(session: SessionDto): String =
        fixThisJson.encodeToString(SessionDto.serializer(), session)

    fun toMarkdown(session: SessionDto): String =
        toMarkdown(session, DetailMode.PRECISE)

    fun toMarkdown(session: SessionDto, detailMode: DetailMode): String =
        when (detailMode) {
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

    private fun toCompactMarkdown(session: SessionDto): String = buildString {
        appendLine("# FixThis Feedback Handoff")
        appendLine()
        appendLine("Rule: source hints are candidates; verify screenshot, target, and code before editing.")
        appendLine()
        appendLine("- Package: `${session.packageName}`")
        appendLine("- Feedback Items: `${session.items.size}`")
        appendLine()

        val orderedItems = session.items.withIndex()
            .sortedWith(
                compareBy<IndexedValue<AnnotationDto>> { it.value.sequenceNumber ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
        if (orderedItems.isEmpty()) {
            appendLine("No feedback items.")
            appendLine()
            return@buildString
        }

        val itemsByScreen = orderedItems.groupBy { it.value.screenId }
        var globalCounter = 0
        itemsByScreen.forEach { (screenId, indexedItems) ->
            val screen = session.screens.firstOrNull { it.screenId == screenId }
            val displayName = screen?.displayName ?: "Screen"
            appendLine("Screen ${screenId}: ${displayName.inlineSafe()}")
            screen?.screenshot?.desktopFullPath?.let {
                appendLine("screenshot: ${it.inlineSafe()}")
            }
            appendLine()
            indexedItems.forEach { entry ->
                globalCounter += 1
                appendCompactItem(globalCounter, entry.value)
            }
        }
    }

    private fun StringBuilder.appendCompactItem(number: Int, item: AnnotationDto) {
        val title = item.comment.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "(No request provided)"
        appendLine("${number}. [marker $number] ${title.inlineSafe()}")
        val targetSummary = compactTargetSummary(item)
        appendLine("target: $targetSummary")
        item.screenshotCrop?.desktopCropPath?.let { appendLine("crop: ${it.inlineSafe()}") }
        appendLine(compactSourceLine(item))
        appendLine()
    }

    private fun compactTargetSummary(item: AnnotationDto): String {
        val node = item.selectedNode
        val role = node?.role?.takeIf { it.isNotBlank() } ?: when (item.target) {
            is AnnotationTargetDto.Area -> "Area"
            is AnnotationTargetDto.Node -> "Node"
        }
        val label = node?.text?.firstOrNull()
            ?: node?.contentDescription?.firstOrNull()
            ?: node?.testTag
            ?: "(unlabelled)"
        val bounds = when (val target = item.target) {
            is AnnotationTargetDto.Area -> target.boundsInWindow.formatBounds()
            is AnnotationTargetDto.Node -> target.boundsInWindow.formatBounds()
        }
        return "${role.inlineSafe()} \"${label.inlineSafe()}\" bounds=$bounds"
    }

    private fun compactSourceLine(item: AnnotationDto): String {
        val candidate = item.sourceCandidates.firstOrNull() ?: return "src? unknown"
        val confidence = candidate.confidence.name.lowercase()
        val why = candidate.matchReasons.mapNotNull { reasonTokenFor(it) }.distinct().joinToString("+")
        val risk = candidate.riskFlags.firstOrNull()?.name?.lowercase()?.replace('_', '-')
        val parts = mutableListOf("src? ${candidate.fileWithLine()} $confidence")
        if (why.isNotBlank()) parts.add("why=$why")
        if (risk != null) parts.add("risk=$risk")
        return parts.joinToString("; ")
    }

    private fun reasonTokenFor(reason: String): String? = when (reason) {
        "selected text" -> "text"
        "selected contentDescription" -> "contentDescription"
        "selected testTag" -> "tag"
        "selected testTag convention composable" -> "compTag"
        "selected role" -> "role"
        "nearby text" -> "nearbyText"
        "nearby contentDescription" -> "nearbyContentDescription"
        "nearby testTag" -> "nearbyTag"
        "nearby role" -> "nearbyRole"
        "activity" -> "activity"
        "selected stringResource" -> "stringRes"
        "arbitrary literal" -> "literal"
        "legacy fallback" -> "legacy"
        else -> null
    }

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
                appendLine("- Bounds: `${target.boundsInWindow.formatBounds()}`")
            }
            is AnnotationTargetDto.Area -> {
                appendLine("- Type: Visual area")
                appendLine("- Bounds: `${target.boundsInWindow.formatBounds()}`")
                appendLine("- Nearby UI: `${item.nearbyNodes.nearbyUiLabel()}`")
                appendTargetEvidence(item)
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
            appendLine("${index + 1}. `${candidate.fileWithLine()}` ${candidate.markdownConfidence(target)} confidence")
            if (candidate.matchedTerms.isNotEmpty()) {
                appendLine("   - matched: ${candidate.matchedTerms.joinToString(", ") { "`${it.inlineSafe()}`" }}")
            }
            if (candidate.matchReasons.isNotEmpty()) {
                appendLine("   - reasons: ${candidate.matchReasons.joinToString(", ")}")
            }
        }
    }

    private fun DetailMode.sourceCandidateLimit(): Int =
        when (this) {
            DetailMode.PRECISE -> 3
            else -> Int.MAX_VALUE
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
        lineSequence().joinToString(" ").replace("`", "'")
}
