package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

internal fun SourceCandidate.fileWithLine(): String =
    line?.let { "$file:$it" } ?: file

internal fun FixThisRect.formatBounds(): String =
    "$left,$top,$right,$bottom"

internal fun String.inlineSafe(): String =
    lineSequence().joinToString(" ").replace("`", "'")
