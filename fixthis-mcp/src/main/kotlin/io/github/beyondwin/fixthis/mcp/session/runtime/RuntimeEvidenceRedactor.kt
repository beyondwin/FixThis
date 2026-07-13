package io.github.beyondwin.fixthis.mcp.session.runtime

data class RuntimeEvidenceRedactionResult(
    val text: String,
    val redacted: Boolean,
)

internal class RuntimeEvidenceRedactor(
    additionalPatterns: List<String> = emptyList(),
) {
    private val projectPatterns: List<Regex> = RuntimeEvidencePatternValidator.validate(additionalPatterns)

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

        const val SENSITIVE_KEYS =
            "password|passwd|pwd|api[_-]?key|client[_-]?secret|secret|access[_-]?token|" +
                "refresh[_-]?token|session[_-]?token|auth[_-]?token|" +
                "fixthis[_-](?:bridge[_-]|console[_-]|session[_-])?token"
        val sensitiveKeyValue = Regex(
            "(?i)(\\b(?:$SENSITIVE_KEYS)\\s*[:=]\\s*)" +
                "(?:\"[^\"]*\"|'[^']*'|[^\\s&,;]+)",
        )
        val sensitiveJsonKeyValue = Regex(
            "(?i)(\"(?:$SENSITIVE_KEYS)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"",
        )

        val defaultRules = listOf(
            RedactionRule(
                Regex("""(?im)(\bauthorization\s*:\s*)[^\r\n]*"""),
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
                sensitiveJsonKeyValue,
                "$1\"$REDACTED\"",
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
    }
}
