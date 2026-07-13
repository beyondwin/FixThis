package io.github.beyondwin.fixthis.mcp.session.runtime

data class RuntimeEvidenceRedactionResult(
    val text: String,
    val redacted: Boolean,
)

internal class RuntimeEvidenceRedactor(
    additionalPatterns: List<String> = emptyList(),
) {
    private val projectPatterns: List<Regex> = validateAdditionalPatterns(additionalPatterns)

    fun redact(text: String): RuntimeEvidenceRedactionResult {
        var redactedText = text
        defaultRules.forEach { rule -> redactedText = rule.apply(redactedText) }
        projectPatterns.forEach { pattern -> redactedText = pattern.replace(redactedText, REDACTED) }
        return RuntimeEvidenceRedactionResult(
            text = redactedText,
            redacted = redactedText != text,
        )
    }

    private data class RedactionRule(
        val pattern: Regex,
        val replacement: String,
    ) {
        fun apply(input: String): String = pattern.replace(input, replacement)
    }

    private companion object {
        const val REDACTED = "[REDACTED]"
        const val MAX_ADDITIONAL_PATTERNS = 32
        const val MAX_PATTERN_LENGTH = 256

        val forbiddenBackReference = Regex("""\\(?:[1-9]|k[<{'])|\(\?P=""")
        val sensitiveKeyValue = Regex(
            "(?i)(\\b(?:password|passwd|pwd|api[_-]?key|client[_-]?secret|secret|" +
                "access[_-]?token|refresh[_-]?token|session[_-]?token|auth[_-]?token|" +
                "fixthis[_-](?:bridge[_-]|console[_-]|session[_-])?token)\\s*[:=]\\s*)" +
                "(?:\"[^\"]*\"|'[^']*'|[^\\s&,;]+)",
        )

        val defaultRules = listOf(
            RedactionRule(
                Regex("""(?im)(\bauthorization\s*:\s*)(?:(?:bearer|basic)\s+)?[^\s\r\n]+"""),
                "$1$REDACTED",
            ),
            RedactionRule(
                Regex("""(?im)(\b(?:cookie|set-cookie)\s*:\s*)[^\r\n]*"""),
                "$1$REDACTED",
            ),
            RedactionRule(
                Regex("""(?im)(\bx-fixthis-(?:(?:bridge|console)-)?token\s*:\s*)[^\s\r\n]+"""),
                "$1$REDACTED",
            ),
            RedactionRule(
                Regex(
                    """(?i)([?&](?:access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|session[_-]?token|api[_-]?key|code)=)[^&#\s]*""",
                ),
                "$1$REDACTED",
            ),
            RedactionRule(
                sensitiveKeyValue,
                "$1$REDACTED",
            ),
            RedactionRule(
                Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}(?![A-Za-z0-9_-])"""),
                REDACTED,
            ),
        )

        fun validateAdditionalPatterns(patterns: List<String>): List<Regex> {
            require(patterns.size <= MAX_ADDITIONAL_PATTERNS) {
                "At most $MAX_ADDITIONAL_PATTERNS runtime-evidence redaction patterns are allowed"
            }
            return patterns.map { source ->
                require(source.isNotEmpty()) { "Runtime-evidence redaction patterns must not be empty" }
                require(source.length <= MAX_PATTERN_LENGTH) {
                    "Runtime-evidence redaction patterns must be at most $MAX_PATTERN_LENGTH characters"
                }
                require("(?<=" !in source && "(?<!" !in source) {
                    "Runtime-evidence redaction patterns must not use lookbehind"
                }
                require(!forbiddenBackReference.containsMatchIn(source)) {
                    "Runtime-evidence redaction patterns must not use backreferences"
                }
                require(!containsNestedQuantifier(source)) {
                    "Runtime-evidence redaction patterns must not use nested quantifiers"
                }
                runCatching { Regex(source) }.getOrElse { cause ->
                    throw IllegalArgumentException("Invalid runtime-evidence redaction pattern", cause)
                }
            }
        }

        @Suppress(
            "CyclomaticComplexMethod",
            "LongMethod",
            "LoopWithTooManyJumpStatements",
            "NestedBlockDepth",
            "ReturnCount",
        ) // A finite regex-syntax scan keeps project rules from reaching the regex engine unsafely.
        fun containsNestedQuantifier(source: String): Boolean {
            val groups = mutableListOf<Boolean>()
            var lastClosedGroupContainedQuantifier = false
            var previousTokenWasClosedGroup = false
            var inCharacterClass = false
            var index = 0
            while (index < source.length) {
                val character = source[index]
                if (character == '\\') {
                    index += 2
                    previousTokenWasClosedGroup = false
                    continue
                }
                if (character == '[') {
                    inCharacterClass = true
                    previousTokenWasClosedGroup = false
                    index += 1
                    continue
                }
                if (character == ']' && inCharacterClass) {
                    inCharacterClass = false
                    index += 1
                    continue
                }
                if (inCharacterClass) {
                    index += 1
                    continue
                }
                when (character) {
                    '(' -> {
                        groups += false
                        previousTokenWasClosedGroup = false
                    }
                    ')' -> {
                        lastClosedGroupContainedQuantifier = groups.removeLastOrNull() ?: false
                        previousTokenWasClosedGroup = true
                    }
                    '*', '+' -> {
                        if (previousTokenWasClosedGroup && lastClosedGroupContainedQuantifier) return true
                        groups.indices.forEach { groups[it] = true }
                        previousTokenWasClosedGroup = false
                    }
                    '?' -> {
                        if (index == 0 || source[index - 1] != '(') {
                            if (previousTokenWasClosedGroup && lastClosedGroupContainedQuantifier) return true
                            groups.indices.forEach { groups[it] = true }
                        }
                        previousTokenWasClosedGroup = false
                    }
                    '{' -> {
                        val closingBrace = source.indexOf('}', startIndex = index + 1)
                        val quantifierBody = if (closingBrace > index) source.substring(index + 1, closingBrace) else ""
                        if (quantifierBody.matches(Regex("""\d+(?:,\d*)?"""))) {
                            if (previousTokenWasClosedGroup && lastClosedGroupContainedQuantifier) return true
                            groups.indices.forEach { groups[it] = true }
                            index = closingBrace
                        }
                        previousTokenWasClosedGroup = false
                    }
                    else -> previousTokenWasClosedGroup = false
                }
                index += 1
            }
            return false
        }
    }
}
