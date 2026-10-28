package io.github.beyondwin.fixthis.mcp.session.dto

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect

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
