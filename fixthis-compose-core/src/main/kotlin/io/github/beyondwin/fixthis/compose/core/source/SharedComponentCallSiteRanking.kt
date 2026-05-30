package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef

private const val CALL_SITE_LITERAL_WEIGHT: Double = 2.0
private const val CALL_SITE_ENCLOSING_WEIGHT: Double = 1.0
private const val CALL_SITE_MOST_LIKELY_MARGIN: Double = 1.0
private const val CALL_SITE_MIN_PARTIAL_MATCH_LENGTH: Int = 3

private data class ParsedCallSite(
    val file: String,
    val line: Int?,
    val enclosing: String?,
    val literals: List<String>,
)

/** Builds the normalized selection-evidence tokens used to rank shared-component call sites. */
internal fun selectionTokensFor(selectedNode: FixThisNode, activityName: String?): Set<String> = buildSet {
    selectedNode.text.forEach { add(it) }
    selectedNode.editableText?.let { add(it) }
    selectedNode.contentDescription.forEach { add(it) }
    selectedNode.role?.let { add(it) }
    activityName?.let { add(it) }
}.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * Reorders the call-site inventory by how well each site's static context (enclosing function name
 * and string-literal arguments) overlaps the selection tokens. Ordering is a best-effort hint: ties
 * and zero-evidence cases preserve the original static order, and the top entry is marked
 * `mostLikely` only when its score clears the next site by a fixed margin.
 */
internal fun rankSharedComponentCallSites(
    callSiteSignalValues: List<String>,
    selectionTokens: Set<String>,
): List<SourceLocationRef> {
    val parsed = callSiteSignalValues.map(::parseCallSiteSignal)
    val scored = parsed.map { it to callSiteScore(it, selectionTokens) }
    val ordered = scored.sortedByDescending { it.second } // stable: ties keep static order
    val topScore = ordered.firstOrNull()?.second ?: 0.0
    val secondScore = ordered.getOrNull(1)?.second ?: 0.0
    val markTop = topScore > 0.0 && (topScore - secondScore) >= CALL_SITE_MOST_LIKELY_MARGIN
    return ordered.mapIndexed { index, (site, _) ->
        SourceLocationRef(file = site.file, line = site.line, mostLikely = markTop && index == 0)
    }
}

private fun parseCallSiteSignal(raw: String): ParsedCallSite {
    val parts = raw.split('\t')
    val location = parts[0]
    val sep = location.lastIndexOf(':')
    val file: String
    val line: Int?
    if (sep <= 0) {
        file = location
        line = null
    } else {
        file = location.substring(0, sep)
        line = location.substring(sep + 1).toIntOrNull()
    }
    val enclosing = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
    val literals = parts.getOrNull(2)?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
    return ParsedCallSite(file = file, line = line, enclosing = enclosing, literals = literals)
}

private fun callSiteScore(site: ParsedCallSite, tokens: Set<String>): Double {
    if (tokens.isEmpty()) return 0.0
    var score = 0.0
    if (site.literals.any { literal -> tokens.any { token -> tokenMatches(token, literal) } }) {
        score += CALL_SITE_LITERAL_WEIGHT
    }
    val enclosing = site.enclosing
    if (enclosing != null && tokens.any { token -> tokenMatches(token, enclosing) }) {
        score += CALL_SITE_ENCLOSING_WEIGHT
    }
    return score
}

private fun tokenMatches(token: String, candidate: String): Boolean {
    val normalizedToken = token.normalizedForCallSiteMatch()
    val normalizedCandidate = candidate.normalizedForCallSiteMatch()
    if (normalizedToken.isEmpty() || normalizedCandidate.isEmpty()) return false
    return normalizedToken == normalizedCandidate ||
        (normalizedToken.length >= CALL_SITE_MIN_PARTIAL_MATCH_LENGTH && normalizedCandidate.contains(normalizedToken)) ||
        (normalizedCandidate.length >= CALL_SITE_MIN_PARTIAL_MATCH_LENGTH && normalizedToken.contains(normalizedCandidate))
}

private fun String.normalizedForCallSiteMatch(): String = trim().lowercase().replace(Regex("\\s+"), " ")
