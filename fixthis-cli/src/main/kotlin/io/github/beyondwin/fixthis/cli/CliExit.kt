package io.github.beyondwin.fixthis.cli

enum class ExitCode(val value: Int) {
    OK(0),
    PARTIAL(1),
    USAGE_ERROR(2),
    ENV_BLOCKER(3),
    INTERNAL_ERROR(4),
    ;

    companion object {
        fun fromInt(value: Int): ExitCode = entries.firstOrNull { it.value == value } ?: INTERNAL_ERROR
    }
}
