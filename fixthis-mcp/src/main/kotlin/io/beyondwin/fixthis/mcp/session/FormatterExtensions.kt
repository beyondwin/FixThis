package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

internal fun SourceCandidate.fileWithLine(): String =
    line?.let { "$file:$it" } ?: file

internal fun FixThisRect.formatBounds(): String =
    "$left,$top,$right,$bottom"

internal fun FixThisRect.formatBox(): String =
    "($left,$top)-($right,$bottom)"

internal fun String.inlineSafe(): String =
    lineSequence().joinToString(" ").replace("`", "'")

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
    val files = session.items.flatMap { it.sourceCandidates }.map { it.file }.distinct()
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
    val relativeFile = if (prefix != null && file.startsWith(prefix)) file.removePrefix(prefix) else file
    return line?.let { "$relativeFile:$it" } ?: relativeFile
}

internal const val SOURCE_ROOT_MIN_LENGTH = 10
