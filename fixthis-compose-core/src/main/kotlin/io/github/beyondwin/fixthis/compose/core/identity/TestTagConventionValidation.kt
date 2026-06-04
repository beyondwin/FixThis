package io.github.beyondwin.fixthis.compose.core.identity

object TestTagConventionValidation {
    private const val MAX_PATTERN_LENGTH = 200

    data class Result(val isValid: Boolean, val reason: String? = null)

    fun validate(pattern: String): Result {
        val reason = sequenceOf(
            { lengthReason(pattern) },
            { anchorReason(pattern) },
            { backtrackingReason(pattern) },
            { regexReason(pattern) },
        ).firstNotNullOfOrNull { it() }
        return Result(reason == null, reason)
    }

    private fun lengthReason(pattern: String): String? = if (pattern.length > MAX_PATTERN_LENGTH) "pattern exceeds $MAX_PATTERN_LENGTH characters" else null

    private fun anchorReason(pattern: String): String? = if (!pattern.startsWith("^") || !pattern.endsWith("$")) "pattern must be anchored with ^ and $" else null

    private fun regexReason(pattern: String): String? {
        val compiled = runCatching { Regex(pattern) }.getOrElse {
            return "pattern is not a valid regex: ${it.message}"
        }
        val groupCount = compiled.toPattern().matcher("").groupCount()
        return if (groupCount < 2) {
            "pattern must capture group 1 = composable name, group 2 = variant"
        } else {
            null
        }
    }

    private fun isUnboundedQuantifier(ch: Char): Boolean = ch == '*' || ch == '+'

    /**
     * Rejects catastrophic-backtracking (ReDoS) precursors by scanning the raw pattern:
     *  - a quantified group: group-close `)` immediately followed by `*`, `+`, or `{`
     *    (the classic nested-quantifier shape, e.g. `(a+)+`, `(...)*`, `(...){2,}`);
     *  - adjacent unbounded quantifiers: two `*`/`+` chars in a row (e.g. `a++`, `.*+`).
     *
     * Returns a human-readable reason when the pattern is rejected, or `null` when safe.
     */
    private fun backtrackingReason(pattern: String): String? = pattern.indices.firstNotNullOfOrNull { i ->
        val ch = pattern[i]
        when (val next = pattern.getOrNull(i + 1)) {
            null -> null
            else -> when {
                ch == ')' && (isUnboundedQuantifier(next) || next == '{') ->
                    "pattern has a quantified group ')$next' (backtracking-prone)"
                isUnboundedQuantifier(ch) && isUnboundedQuantifier(next) ->
                    "pattern has adjacent unbounded quantifiers '$ch$next' (backtracking-prone)"
                else -> null
            }
        }
    }
}
