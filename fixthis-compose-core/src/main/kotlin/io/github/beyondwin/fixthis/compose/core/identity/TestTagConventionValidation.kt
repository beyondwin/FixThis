package io.github.beyondwin.fixthis.compose.core.identity

object TestTagConventionValidation {
    private const val MAX_PATTERN_LENGTH = 200

    data class Result(val isValid: Boolean, val reason: String? = null)

    fun validate(pattern: String): Result {
        if (pattern.length > MAX_PATTERN_LENGTH) {
            return Result(false, "pattern exceeds $MAX_PATTERN_LENGTH characters")
        }
        if (!pattern.startsWith("^") || !pattern.endsWith("$")) {
            return Result(false, "pattern must be anchored with ^ and $")
        }
        backtrackingReason(pattern)?.let { return Result(false, it) }
        val compiled = runCatching { Regex(pattern) }.getOrElse {
            return Result(false, "pattern is not a valid regex: ${it.message}")
        }
        val groupCount = compiled.toPattern().matcher("").groupCount()
        if (groupCount < 2) {
            return Result(false, "pattern must capture group 1 = composable name, group 2 = variant")
        }
        return Result(true)
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
    private fun backtrackingReason(pattern: String): String? {
        for (i in pattern.indices) {
            val ch = pattern[i]
            val next = pattern.getOrNull(i + 1) ?: continue
            if (ch == ')' && (isUnboundedQuantifier(next) || next == '{')) {
                return "pattern has a quantified group ')$next' (backtracking-prone)"
            }
            if (isUnboundedQuantifier(ch) && isUnboundedQuantifier(next)) {
                return "pattern has adjacent unbounded quantifiers '$ch$next' (backtracking-prone)"
            }
        }
        return null
    }
}
