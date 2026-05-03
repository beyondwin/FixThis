package io.github.pointpatch.compose.core.format

import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect

object PointPatchMarkdownFormatter {
    fun format(annotation: PointPatchAnnotation): String = buildString {
        appendLine("# PointPatch Compose Feedback")
        appendLine()
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
        if (annotation.sourceCandidates.isEmpty()) {
            appendLine("- none")
        } else {
            annotation.sourceCandidates.forEachIndexed { index, candidate ->
                appendLine("${index + 1}. ${(candidate.file + (candidate.line?.let { ":$it" } ?: "")).markdownCodeSpan()}")
                appendLine("   - score: ${candidate.score}")
                appendLine("   - confidence: ${candidate.confidence}")
                appendLine("   - matched terms: ${candidate.matchedTerms.markdownListValue()}")
                appendLine("   - reasons: ${candidate.matchReasons.markdownListValue()}")
            }
        }
        appendLine()
        appendLine("## Search hints")
        if (annotation.searchHints.isEmpty()) {
            appendLine("- none")
        } else {
            annotation.searchHints.forEach { appendLine("- \"${it.markdownInline()}\"") }
        }
        appendLine()
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
        if (annotation.errors.isNotEmpty()) {
            appendLine()
            appendLine("## Capture notes")
            annotation.errors.forEach { error ->
                appendLine("- ${error.code.markdownInline()}: ${error.message.markdownInline()}")
                if (error.details.isNotEmpty()) {
                    appendLine("  - details: ${error.details.entries.joinToString { "${it.key.markdownInline()}=${it.value.markdownInline()}" }}")
                }
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

    private fun StringBuilder.appendNode(node: PointPatchNode?) {
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

    private fun PointPatchNode.summary(): String {
        val label = text.firstOrNull() ?: contentDescription.firstOrNull() ?: role ?: testTag ?: uid
        return "${role?.markdownInline() ?: "Node"} \"${label.markdownInline()}\""
    }

    private fun PointPatchRect.format(): String = "$left,$top,$right,$bottom"

    private fun List<String>.markdownListValue(): String =
        if (isEmpty()) "none" else joinToString { it.markdownInline() }

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
        fun String.escapePrefix(prefix: String): String =
            if (startsWith(prefix)) "\\$this" else this

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
        '>'
    )
}
