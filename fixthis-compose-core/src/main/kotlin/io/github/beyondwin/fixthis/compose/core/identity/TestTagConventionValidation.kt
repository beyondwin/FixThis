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
        val compiled = runCatching { Regex(pattern) }.getOrElse {
            return Result(false, "pattern is not a valid regex: ${it.message}")
        }
        val groupCount = compiled.toPattern().matcher("").groupCount()
        if (groupCount < 2) {
            return Result(false, "pattern must capture group 1 = composable name, group 2 = variant")
        }
        return Result(true)
    }
}
