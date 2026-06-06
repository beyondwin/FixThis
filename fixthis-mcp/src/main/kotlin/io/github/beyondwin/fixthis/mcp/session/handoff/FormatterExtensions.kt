package io.github.beyondwin.fixthis.mcp.session.handoff

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto

internal fun SourceCandidate.displayFile(): String = repoFile?.takeIf { it.isNotBlank() } ?: file

internal fun SourceCandidate.fileWithLine(): String {
    val displayFile = displayFile()
    return line?.let { "$displayFile:$it" } ?: displayFile
}

internal fun SourceCandidate.fileWithLineAndOwner(): String {
    val ownerSegment = ownerComposable?.takeIf { it.isNotBlank() }?.let { " inside fun $it" }.orEmpty()
    return "${fileWithLine()}$ownerSegment"
}

internal fun SourceCandidate.staleMarkerSuffix(): String = if (stale == true) " ⚠ stale: ${staleReason ?: "unspecified"}" else ""

internal fun FixThisRect.formatBounds(): String = "$left,$top,$right,$bottom"

internal fun FixThisRect.formatBox(): String = "($left,$top)-($right,$bottom)"

internal fun String.inlineSafe(): String = lineSequence().joinToString(" ").replace("`", "'")

private const val COMPACT_TRUNCATION_SUFFIX = "..."

internal fun String.compactQuotedValue(maxLength: Int = 80): String {
    val normalized = inlineSafe().replace("\"", "'")
    return if (normalized.length <= maxLength) {
        normalized
    } else {
        normalized.take(maxLength - COMPACT_TRUNCATION_SUFFIX.length) + COMPACT_TRUNCATION_SUFFIX
    }
}

/**
 * Computes the longest directory-boundary common prefix across all candidate file paths
 * in the session, returning the prefix with a trailing "/" if it is at least
 * [SOURCE_ROOT_MIN_LENGTH] characters long and at least two distinct candidate paths exist.
 *
 * Always operates on directory boundaries (split on "/"); never produces a partial-segment
 * prefix. Always reserves at least one trailing segment per candidate so the relative path
 * keeps its filename. Returns null when the prefix would not pay for itself.
 */
internal fun computeSourceRoot(session: SessionDto): String? {
    val files = session.items.flatMap { it.sourceCandidates }.map { it.displayFile() }.distinct()
    if (files.size < 2) return null
    val splits = files.map { it.split('/') }
    val first = splits.first()
    var commonDepth = first.size
    for (other in splits.drop(1)) {
        commonDepth = minOf(commonDepth, other.size)
        for (i in 0 until commonDepth) {
            if (first[i] != other[i]) {
                commonDepth = i
                break
            }
        }
    }
    val maxDirDepth = splits.minOf { it.size - 1 }
    val depth = minOf(commonDepth, maxDirDepth)
    if (depth <= 0) return null
    val prefix = first.take(depth).joinToString("/") + "/"
    return if (prefix.length >= SOURCE_ROOT_MIN_LENGTH) prefix else null
}

internal fun SourceCandidate.relativeFileWithLine(prefix: String?): String {
    val displayFile = displayFile()
    val relativeFile = if (prefix != null && displayFile.startsWith(prefix)) {
        displayFile.removePrefix(prefix)
    } else {
        displayFile
    }
    return line?.let { "$relativeFile:$it" } ?: relativeFile
}

internal const val SOURCE_ROOT_MIN_LENGTH = 10
