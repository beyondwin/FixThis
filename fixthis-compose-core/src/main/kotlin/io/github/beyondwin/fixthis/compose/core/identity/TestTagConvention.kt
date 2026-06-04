package io.github.beyondwin.fixthis.compose.core.identity

class TestTagConventionSet internal constructor(private val patterns: List<Regex>) {
    data class Parsed(val composableName: String, val variant: String)

    fun parse(testTag: String?): Parsed? {
        if (testTag == null) return null
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.matchEntire(testTag)?.let { match ->
                Parsed(composableName = match.groupValues[1], variant = match.groupValues[2])
            }
        }
    }

    companion object {
        private val DEFAULT_PATTERNS = listOf(
            Regex("^comp:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$"),
            Regex("^screen:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$"),
            Regex("^comp\\.([A-Za-z][A-Za-z0-9]*)\\.([A-Za-z0-9_-]+)$"),
            Regex("^screen\\.([A-Za-z][A-Za-z0-9]*)\\.([A-Za-z0-9_-]+)$"),
        )

        val Default: TestTagConventionSet = TestTagConventionSet(DEFAULT_PATTERNS)

        /** Builds a set from serialized pattern strings; empty input falls back to [Default]. */
        fun fromPatternStrings(patterns: List<String>): TestTagConventionSet {
            val valid = patterns.filter { TestTagConventionValidation.validate(it).isValid }
            return if (valid.isEmpty()) Default else TestTagConventionSet(valid.map(::Regex))
        }
    }
}

object TestTagConvention {
    data class Parsed(
        val composableName: String,
        val variant: String,
    )

    /** Backward-compatible default-set parse used by identity hints. */
    fun parse(testTag: String?): Parsed? =
        TestTagConventionSet.Default.parse(testTag)?.let { Parsed(it.composableName, it.variant) }
}
