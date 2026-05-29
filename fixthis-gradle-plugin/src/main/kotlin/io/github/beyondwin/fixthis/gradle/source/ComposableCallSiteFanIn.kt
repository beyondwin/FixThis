package io.github.beyondwin.fixthis.gradle.source

/** A composable is treated as a shared/reusable component once it is invoked at this many call sites. */
internal const val SHARED_COMPONENT_FANIN_THRESHOLD: Int = 2

private val composableCallRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val funDeclarationBeforeNameRegex = Regex("""\bfun\s+$""")

/** A source file's relative path plus its full text, used for call-site scanning. */
internal data class CallSiteSource(val path: String, val content: String)

/** A single resolved call site of a composable definition. */
internal data class ComposableCallSite(val file: String, val line: Int)

/**
 * Finds the call sites of each composable definition across the given sources.
 *
 * A call site is `Name(` where `Name` is a known definition and the occurrence is not the
 * `fun Name(` declaration, not inside a string or comment, and not a qualified `receiver.Name(`
 * member call. Occurrences are distinct by source position, so each invocation counts once.
 * The definition's own declaration is excluded.
 */
internal fun composableCallSites(
    sources: List<CallSiteSource>,
    definitionNames: Set<String>,
): Map<String, List<ComposableCallSite>> {
    if (definitionNames.isEmpty()) return emptyMap()
    val sites = linkedMapOf<String, MutableList<ComposableCallSite>>()
    sources.forEach { source ->
        val text = source.content
        val ignoredRanges = text.callSiteIgnoredRanges()
        composableCallRegex.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (name !in definitionNames) return@forEach
            val start = match.range.first
            if (ignoredRanges.any { start in it }) return@forEach
            if (start > 0 && text[start - 1] == '.') return@forEach
            if (text.isFunctionDeclarationBefore(start)) return@forEach
            val line = text.lineNumberAt(start)
            sites.getOrPut(name) { mutableListOf() }.add(ComposableCallSite(file = source.path, line = line))
        }
    }
    return sites
}

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
): Map<String, Int> = composableCallSites(
    sources = sources.map { CallSiteSource(path = "", content = it) },
    definitionNames = definitionNames,
).mapValues { it.value.size }

private fun String.lineNumberAt(offset: Int): Int {
    var line = 1
    for (i in 0 until offset) {
        if (this[i] == '\n') line += 1
    }
    return line
}

private fun String.callSiteIgnoredRanges(): List<IntRange> = kotlinSourceQuotedStringRegex.findAll(this).map { it.range }.toList() + commentRanges()

private fun String.isFunctionDeclarationBefore(offset: Int): Boolean {
    val lineStart = lastIndexOf('\n', startIndex = offset - 1).let { if (it == -1) 0 else it + 1 }
    return funDeclarationBeforeNameRegex.containsMatchIn(substring(lineStart, offset))
}
