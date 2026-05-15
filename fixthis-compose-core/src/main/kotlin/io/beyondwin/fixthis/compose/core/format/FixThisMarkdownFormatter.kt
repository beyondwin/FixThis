package io.beyondwin.fixthis.compose.core.format

import io.beyondwin.fixthis.compose.core.model.FixThisAnnotation
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateSummary
import io.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.beyondwin.fixthis.compose.core.model.TargetReliability
import io.beyondwin.fixthis.compose.core.model.handoffMessage

object FixThisMarkdownFormatter {
    fun format(annotation: FixThisAnnotation): String = format(annotation, DetailMode.FULL)

    fun format(annotation: FixThisAnnotation, detailMode: DetailMode): String = when (detailMode) {
        DetailMode.COMPACT -> formatCompact(annotation)
        DetailMode.PRECISE -> formatPrecise(annotation)
        DetailMode.FULL -> formatFull(annotation)
    }

    private fun formatCompact(annotation: FixThisAnnotation): String = buildString {
        appendLine("# FixThis Feedback")
        appendLine()
        appendLine("Rule: source hints are candidates; verify screenshot, target, and code before editing.")
        appendLine()
        appendLine("Request:")
        appendFreeFormBlock(annotation.userComment)
        appendLine()
        appendLine("Target:")
        appendCompactTarget(annotation)
        appendTargetReliability(annotation.targetReliability, compact = true)
        appendLine()
        appendCompactSourceLine(annotation.sourceCandidates.firstOrNull())
    }

    private fun StringBuilder.appendCompactSourceLine(candidate: SourceCandidate?) {
        if (candidate == null) {
            appendLine("src? unknown")
            return
        }
        val confidence = candidate.confidence.name.lowercase()
        val why = candidate.matchReasons
            .mapNotNull { compactReasonToken(it) }
            .distinct()
            .joinToString("+")
        val risk = candidate.riskFlags.firstOrNull()?.name?.lowercase()?.replace('_', '-')
        val parts = mutableListOf("src? ${candidate.location()} $confidence")
        if (why.isNotBlank()) parts.add("why=$why")
        if (risk != null) parts.add("risk=$risk")
        appendLine(parts.joinToString("; "))
    }

    private fun compactReasonToken(reason: String): String? = when (reason) {
        "selected text" -> "text"
        "selected contentDescription" -> "contentDescription"
        "selected testTag" -> "tag"
        "selected testTag convention composable" -> "compTag"
        "selected role" -> "role"
        "selected resolved stringResource" -> "resolvedStringRes"
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

    private fun formatPrecise(annotation: FixThisAnnotation): String = buildString {
        appendLine("# FixThis Feedback")
        appendLine()
        appendLine("## Request")
        appendFreeFormBlock(annotation.userComment)
        appendLine()
        appendLine("## Target Evidence")
        appendTargetEvidence(annotation, includeEmpty = true)
        appendTargetReliability(annotation.targetReliability, compact = false)
        appendLine()
        appendLine("## Selected UI")
        appendNode(annotation.selectedNode)
        appendLine()
        appendLine("## Source Candidates")
        appendSourceCandidates(annotation.sourceCandidates, maxCandidates = 3)
        appendLine()
        appendScreenshot(annotation)
        appendErrors(annotation)
    }

    private fun formatFull(annotation: FixThisAnnotation): String = buildString {
        appendLine("# FixThis Compose Feedback")
        appendLine()
        if (annotation.targetEvidence != null || annotation.targetReliability != null) {
            appendLine("## Target Evidence")
            appendTargetEvidence(annotation, includeEmpty = false)
            appendTargetReliability(annotation.targetReliability, compact = false)
            appendLine()
        }
        appendLine("## User request")
        appendFreeFormBlock(annotation.userComment)
        appendLine()
        appendLine("## Selection")
        appendLine("- Kind: ${annotation.selection.kind}")
        appendLine("- Confidence: ${annotation.selection.confidence}")
        appendLine("- Source: ${annotation.selection.source}")
        annotation.selection.selectedUid?.let { appendLine("- Selected UID: ${it.markdownInline()}") }
        annotation.selection.areaBoundsInWindow?.let { appendLine("- Area: ${it.format()}") }
        appendLine("- Tap: ${annotation.tap.xInWindow},${annotation.tap.yInWindow}")
        appendLine()
        appendLine("## Selected UI")
        appendNode(annotation.selectedNode)
        appendLine()
        appendLine("## Nearby context")
        if (annotation.nearbyNodes.isEmpty()) {
            appendLine("- none")
        } else {
            annotation.nearbyNodes.forEach { node ->
                appendLine("- ${node.summary()} (${node.uid.markdownInline()}, ${node.treeKind}, ${node.boundsInWindow.format()})")
            }
        }
        appendLine()
        appendLine("## Source candidates")
        appendSourceCandidates(annotation.sourceCandidates, maxCandidates = Int.MAX_VALUE)
        appendLine()
        appendLine("## Search hints")
        if (annotation.searchHints.isEmpty()) {
            appendLine("- none")
        } else {
            annotation.searchHints.forEach { appendLine("- \"${it.markdownInline()}\"") }
        }
        appendLine()
        appendScreenshot(annotation)
        appendErrors(annotation)
    }

    private fun StringBuilder.appendTargetEvidence(annotation: FixThisAnnotation, includeEmpty: Boolean) {
        val evidence = annotation.targetEvidence
        if (evidence == null) {
            if (includeEmpty) appendLine("- Evidence: basic semantics only")
            return
        }
        evidence.identityHint?.let { hint ->
            val identity = listOfNotNull(hint.composableNameHint, hint.variantHint)
                .joinToString(":")
                .ifBlank { "none" }
            appendLine("- Identity: ${identity.markdownInline()} (${hint.source}, ${hint.confidence})")
            hint.stableLabel?.let { appendLine("- Label: ${it.markdownInline()}") }
        } ?: appendLine("- Identity: none")
        evidence.occurrence?.let { occurrence ->
            appendLine(
                "- Occurrence: ${occurrence.selectedOrdinal}/${occurrence.count} " +
                    "(${occurrence.signature.type}, ${occurrence.basis.markdownInline()})",
            )
        } ?: appendLine("- Occurrence: not available")
        evidence.sourceInterpretation?.topCandidate?.let { candidate ->
            appendLine("- Source: ${candidate.location().markdownCodeSpan()} (${candidate.confidence})")
        }
        evidence.sourceInterpretation?.reasonSummary?.takeIf { it.isNotEmpty() }?.let { reasons ->
            appendLine("- Source reasons: ${reasons.markdownListValue()}")
        }
        evidence.sourceInterpretation?.caution?.let { appendLine("- Caution: ${it.markdownInline()}") }
        if (evidence.warnings.isNotEmpty()) {
            appendLine("- Warnings: ${evidence.warnings.markdownListValue()}")
        }
        if (evidence.screenshotKinds.isNotEmpty()) {
            appendLine("- Screenshot evidence: ${evidence.screenshotKinds.markdownListValue()}")
        }
        appendLine("- Quality: ${evidence.evidenceQuality}")
    }

    private fun StringBuilder.appendTargetReliability(reliability: TargetReliability?, compact: Boolean) {
        if (reliability == null) return
        if (reliability.confidence == TargetConfidence.UNKNOWN && reliability.warnings.isEmpty()) return
        appendLine("- Target confidence: ${reliability.confidence.name.lowercase()}${reliability.reasonSummary()}")
        reliability.warnings.forEach { warning ->
            appendLine("- Warning: ${warning.handoffMessage().markdownInline()}")
        }
        if (!compact && reliability.reasons.isNotEmpty()) {
            appendLine(
                "- Reliability reasons: ${
                    reliability.reasons.map { it.name.lowercase().replace('_', '-') }.markdownListValue()
                }",
            )
        }
    }

    private fun TargetReliability.reasonSummary(): String {
        val labels = reasons.take(2).map { reason -> reason.name.lowercase().replace('_', '-') }
        return if (labels.isEmpty()) "" else " - ${labels.joinToString(" + ")}"
    }

    private fun StringBuilder.appendCompactTarget(annotation: FixThisAnnotation) {
        val evidence = annotation.targetEvidence
        val hint = evidence?.identityHint
        val identity = listOfNotNull(hint?.composableNameHint, hint?.variantHint)
            .joinToString(":")
            .takeUnless { it.isBlank() }
        if (identity != null) appendLine("- Identity: ${identity.markdownInline()}")
        hint?.stableLabel?.let { appendLine("- Label: ${it.markdownInline()}") }
        evidence?.occurrence?.let { appendLine("- Occurrence: ${it.selectedOrdinal}/${it.count}") }
        evidence?.sourceInterpretation?.caution?.let { appendLine("- Caution: ${it.markdownInline()}") }
        if (evidence?.warnings?.isNotEmpty() == true) {
            appendLine("- Warnings: ${evidence.warnings.markdownListValue()}")
        }
        appendNodeEvidence(annotation.selectedNode)
    }

    private fun StringBuilder.appendNodeEvidence(node: FixThisNode?) {
        if (node == null) {
            appendLine("- Node: none")
            return
        }
        appendLine("- UID: ${node.uid.markdownInline()}")
        appendLine("- Role: ${node.role?.markdownInline() ?: "none"}")
        appendLine("- Text: ${node.text.markdownListValue()}")
        appendLine("- Content description: ${node.contentDescription.markdownListValue()}")
        appendLine("- Test tag: ${node.testTag?.markdownInline() ?: "none"}")
        appendLine("- Bounds: ${node.boundsInWindow.format()}")
    }

    private fun StringBuilder.appendSourceCandidates(candidates: List<SourceCandidate>, maxCandidates: Int) {
        val visibleCandidates = candidates.take(maxCandidates)
        if (visibleCandidates.isEmpty()) {
            appendLine("- none")
            return
        }
        visibleCandidates.forEachIndexed { index, candidate ->
            appendLine("${index + 1}. ${candidate.location().markdownCodeSpan()}")
            appendLine("   - score: ${candidate.score}")
            appendLine("   - confidence: ${candidate.confidence}")
            appendLine("   - matched terms: ${candidate.matchedTerms.markdownListValue()}")
            appendLine("   - reasons: ${candidate.matchReasons.markdownListValue()}")
        }
    }

    private fun StringBuilder.appendScreenshot(annotation: FixThisAnnotation) {
        appendLine("## Screenshot")
        val screenshot = annotation.screenshot
        if (screenshot == null) {
            appendLine("- none")
        } else {
            appendLine("- full: ${(screenshot.desktopFullPath ?: screenshot.fullPath)?.markdownInline() ?: "none"}")
            appendLine("- crop: ${(screenshot.desktopCropPath ?: screenshot.cropPath)?.markdownInline() ?: "none"}")
            screenshot.width?.let { width ->
                screenshot.height?.let { height -> appendLine("- size: ${width}x$height") }
            }
            screenshot.captureFailedReason?.let { appendLine("- capture failed: ${it.markdownInline()}") }
        }
    }

    private fun StringBuilder.appendErrors(annotation: FixThisAnnotation) {
        if (annotation.errors.isEmpty()) return
        appendLine()
        appendLine("## Capture notes")
        annotation.errors.forEach { error ->
            appendLine("- ${error.code.markdownInline()}: ${error.message.markdownInline()}")
            if (error.details.isNotEmpty()) {
                appendLine("  - details: ${error.details.entries.joinToString { "${it.key.markdownInline()}=${it.value.markdownInline()}" }}")
            }
        }
    }

    private fun StringBuilder.appendFreeFormBlock(text: String) {
        if (text.isBlank()) {
            appendLine("(No comment)")
            return
        }
        val fence = "`".repeat(text.maxBacktickRun().coerceAtLeast(2) + 1)
        appendLine("${fence}text")
        appendLine(text)
        appendLine(fence)
    }

    private fun StringBuilder.appendNode(node: FixThisNode?) {
        if (node == null) {
            appendLine("- none")
            return
        }
        appendLine("- UID: ${node.uid.markdownInline()}")
        appendLine("- Tree: ${node.treeKind}")
        appendLine("- Role: ${node.role?.markdownInline() ?: "none"}")
        appendLine("- Text: ${node.text.markdownListValue()}")
        appendLine("- Editable text: ${node.editableText?.markdownInline() ?: "none"}")
        appendLine("- Content description: ${node.contentDescription.markdownListValue()}")
        appendLine("- Test tag: ${node.testTag?.markdownInline() ?: "none"}")
        appendLine("- State description: ${node.stateDescription?.markdownInline() ?: "none"}")
        appendLine("- Bounds: ${node.boundsInWindow.format()}")
        appendLine("- Actions: ${node.actions.markdownListValue()}")
        appendLine("- Enabled: ${node.enabled}")
        node.selected?.let { appendLine("- Selected: $it") }
    }

    private fun FixThisNode.summary(): String {
        val label = text.firstOrNull() ?: contentDescription.firstOrNull() ?: role ?: testTag ?: uid
        return "${role?.markdownInline() ?: "Node"} \"${label.markdownInline()}\""
    }

    private fun SourceCandidate.location(): String {
        val ownerSegment = ownerComposable?.takeIf { it.isNotBlank() }?.let { " inside fun $it" }.orEmpty()
        return file + (line?.let { ":$it" } ?: "") + ownerSegment
    }

    private fun SourceCandidateSummary.location(): String = file + (line?.let { ":$it" } ?: "")

    private fun FixThisRect.format(): String = "$left,$top,$right,$bottom"

    private fun List<String>.markdownListValue(): String = if (isEmpty()) "none" else joinToString { it.markdownInline() }

    private fun String.markdownInline(): String {
        val normalized = replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .escapeMarkdownLineStarts()
        return buildString {
            normalized.forEach { char ->
                if (char in markdownSpecialCharacters) append('\\')
                append(char)
            }
        }
    }

    private fun String.escapeMarkdownLineStarts(): String {
        fun String.escapePrefix(prefix: String): String = if (startsWith(prefix)) "\\$this" else this

        return escapePrefix("-")
            .escapePrefix("+")
            .escapePrefix(">")
            .replace("\\n-", "\\n\\-")
            .replace("\\n+", "\\n\\+")
            .replace("\\n>", "\\n\\>")
            .replace(Regex("""(^|\\n)(\d+)\.""")) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}\\."
            }
    }

    private fun String.markdownCodeSpan(): String {
        val normalized = replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
        val delimiter = "`".repeat(normalized.maxBacktickRun() + 1)
        val padding = if (normalized.startsWith("`") || normalized.endsWith("`")) " " else ""
        return "$delimiter$padding$normalized$padding$delimiter"
    }

    private fun String.maxBacktickRun(): Int {
        var maxRun = 0
        var currentRun = 0
        forEach { char ->
            if (char == '`') {
                currentRun += 1
                maxRun = maxOf(maxRun, currentRun)
            } else {
                currentRun = 0
            }
        }
        return maxRun
    }

    private val markdownSpecialCharacters = setOf(
        '`',
        '*',
        '_',
        '{',
        '}',
        '[',
        ']',
        '(',
        ')',
        '#',
        '+',
        '!',
        '|',
        '>',
    )
}
