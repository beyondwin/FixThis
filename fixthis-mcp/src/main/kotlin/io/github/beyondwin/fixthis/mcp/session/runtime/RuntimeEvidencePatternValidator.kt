package io.github.beyondwin.fixthis.mcp.session.runtime

internal object RuntimeEvidencePatternValidator {
    private const val MAX_PATTERNS = 32
    private const val MAX_PATTERN_LENGTH = 256
    private val forbiddenBackReference = Regex("""\\(?:[1-9]|k[<{'])|\(\?P=""")
    private val forbiddenLookaround = Regex("""\(\?(?:[=!]|<[=!])""")

    fun validate(patterns: List<String>): List<Regex> {
        require(patterns.size <= MAX_PATTERNS) { "At most $MAX_PATTERNS runtime-evidence redaction patterns are allowed" }
        return patterns.map(::validateOne)
    }

    private fun validateOne(source: String): Regex {
        require(source.isNotEmpty()) { "Runtime-evidence redaction patterns must not be empty" }
        require(source.length <= MAX_PATTERN_LENGTH) {
            "Runtime-evidence redaction patterns must be at most $MAX_PATTERN_LENGTH characters"
        }
        require(!forbiddenBackReference.containsMatchIn(source)) {
            "Runtime-evidence redaction patterns must not use backreferences"
        }
        require(!forbiddenLookaround.containsMatchIn(source)) {
            "Runtime-evidence redaction patterns must not use lookaround"
        }
        val compiled = runCatching { Regex(source) }.getOrElse { cause ->
            throw IllegalArgumentException("Invalid runtime-evidence redaction pattern", cause)
        }
        require(!compiled.containsMatchIn("")) {
            "Runtime-evidence redaction patterns must not match empty text"
        }
        require(!RegexRiskScanner(source).containsRiskyQuantifiedGroup()) {
            "Runtime-evidence redaction patterns must not quantify nested or alternating groups"
        }
        return compiled
    }
}

private class RegexRiskScanner(
    private val source: String,
) {
    private data class GroupRisk(
        var containsQuantifier: Boolean = false,
        var containsAlternation: Boolean = false,
    ) {
        val risky: Boolean get() = containsQuantifier || containsAlternation
    }

    private val groups = mutableListOf<GroupRisk>()
    private var lastClosedGroup: GroupRisk? = null
    private var previousTokenWasClosedGroup = false
    private var index = 0

    fun containsRiskyQuantifiedGroup(): Boolean {
        while (index < source.length) {
            if (consumeToken()) return true
            index += 1
        }
        return false
    }

    private fun consumeToken(): Boolean = when (source[index]) {
        '\\' -> consumeEscape()
        '[' -> consumeCharacterClass()
        '(' -> openGroup()
        ')' -> closeGroup()
        '|' -> markAlternation()
        '*', '+' -> consumeQuantifier()
        '?' -> consumeQuestionMark()
        '{' -> consumeBraceQuantifier()
        else -> {
            previousTokenWasClosedGroup = false
            false
        }
    }

    private fun consumeEscape(): Boolean {
        index += 1
        previousTokenWasClosedGroup = false
        return false
    }

    private fun consumeCharacterClass(): Boolean {
        var cursor = index + 1
        while (cursor < source.length) {
            if (source[cursor] == '\\') {
                cursor += 1
            } else if (source[cursor] == ']') {
                index = cursor
                previousTokenWasClosedGroup = false
                return false
            }
            cursor += 1
        }
        index = source.lastIndex
        previousTokenWasClosedGroup = false
        return false
    }

    private fun openGroup(): Boolean {
        groups += GroupRisk()
        previousTokenWasClosedGroup = false
        return false
    }

    private fun closeGroup(): Boolean {
        lastClosedGroup = groups.removeLastOrNull()
        previousTokenWasClosedGroup = true
        return false
    }

    private fun markAlternation(): Boolean {
        groups.forEach { it.containsAlternation = true }
        previousTokenWasClosedGroup = false
        return false
    }

    private fun consumeQuantifier(): Boolean {
        val risky = previousTokenWasClosedGroup && lastClosedGroup?.risky == true
        groups.forEach { it.containsQuantifier = true }
        previousTokenWasClosedGroup = false
        return risky
    }

    private fun consumeQuestionMark(): Boolean {
        if (index > 0 && source[index - 1] == '(') {
            previousTokenWasClosedGroup = false
            return false
        }
        return consumeQuantifier()
    }

    private fun consumeBraceQuantifier(): Boolean {
        val closingBrace = source.indexOf('}', startIndex = index + 1)
        val body = if (closingBrace > index) source.substring(index + 1, closingBrace) else ""
        if (!body.matches(Regex("""\d+(?:,\d*)?"""))) {
            previousTokenWasClosedGroup = false
            return false
        }
        index = closingBrace
        return consumeQuantifier()
    }
}
