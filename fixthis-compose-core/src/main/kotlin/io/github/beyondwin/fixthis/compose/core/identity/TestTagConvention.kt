package io.github.beyondwin.fixthis.compose.core.identity

object TestTagConvention {
    // Enumerated convention set: comp:<Name>:<id>, screen:<Name>:<id>,
    // comp.<Name>.<id>, screen.<Name>.<id>. Name = [A-Za-z][A-Za-z0-9]*,
    // id = [A-Za-z0-9_-]+. Closed set — no other prefix/delimiter is accepted.
    // Kept in sync with the gradle-side strictCompTestTagRegex.
    private val patterns = listOf(
        Regex("^comp:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$"),
        Regex("^screen:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$"),
        Regex("^comp\\.([A-Za-z][A-Za-z0-9]*)\\.([A-Za-z0-9_-]+)$"),
        Regex("^screen\\.([A-Za-z][A-Za-z0-9]*)\\.([A-Za-z0-9_-]+)$"),
    )

    data class Parsed(
        val composableName: String,
        val variant: String,
    )

    fun parse(testTag: String?): Parsed? {
        if (testTag == null) return null
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(testTag)?.let { match ->
                Parsed(composableName = match.groupValues[1], variant = match.groupValues[2])
            }
        }
    }
}
