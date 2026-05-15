package io.github.beyondwin.fixthis.compose.core.identity

object TestTagConvention {
    private val pattern = Regex("^comp:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$")

    data class Parsed(
        val composableName: String,
        val variant: String,
    )

    fun parse(testTag: String?): Parsed? = testTag
        ?.let(pattern::matchEntire)
        ?.let { match -> Parsed(composableName = match.groupValues[1], variant = match.groupValues[2]) }
}
