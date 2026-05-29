package io.github.beyondwin.fixthis.gradle.source

/** A composable is treated as a shared/reusable component once it is invoked at this many call sites. */
internal const val SHARED_COMPONENT_FANIN_THRESHOLD: Int = 2

private val composableCallRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val funDeclarationBeforeNameRegex = Regex("""\bfun\s+$""")

/**
 * Counts how many call sites invoke each composable definition across the given sources.
 *
 * A call site is `Name(` where `Name` is a known definition and the occurrence is not the
 * `fun Name(` declaration, not inside a string or comment, and not a qualified `receiver.Name(`
 * member call. Occurrences are distinct by source position, so each invocation counts once.
 */
internal fun composableCallSiteCounts(
    sources: List<String>,
    definitionNames: Set<String>,
): Map<String, Int> {
    if (definitionNames.isEmpty()) return emptyMap()
    val counts = mutableMapOf<String, Int>()
    sources.forEach { source ->
        val ignoredRanges = source.callSiteIgnoredRanges()
        composableCallRegex.findAll(source).forEach { match ->
            val name = match.groupValues[1]
            if (name !in definitionNames) return@forEach
            val start = match.range.first
            if (ignoredRanges.any { start in it }) return@forEach
            if (start > 0 && source[start - 1] == '.') return@forEach
            if (source.isFunctionDeclarationBefore(start)) return@forEach
            counts[name] = (counts[name] ?: 0) + 1
        }
    }
    return counts
}

private fun String.callSiteIgnoredRanges(): List<IntRange> =
    kotlinSourceQuotedStringRegex.findAll(this).map { it.range }.toList() + commentRanges()

private fun String.isFunctionDeclarationBefore(offset: Int): Boolean {
    val lineStart = lastIndexOf('\n', startIndex = offset - 1).let { if (it == -1) 0 else it + 1 }
    return funDeclarationBeforeNameRegex.containsMatchIn(substring(lineStart, offset))
}
