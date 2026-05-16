package io.github.beyondwin.fixthis.cli

private const val EXIT_ENV_BLOCKER = 3
private const val EXIT_INTERNAL_ERROR = 4

enum class ExitCode(val value: Int) {
    OK(0),
    PARTIAL(1),
    USAGE_ERROR(2),
    ENV_BLOCKER(EXIT_ENV_BLOCKER),
    INTERNAL_ERROR(EXIT_INTERNAL_ERROR),
    ;

    companion object {
        fun fromInt(value: Int): ExitCode = entries.firstOrNull { it.value == value } ?: INTERNAL_ERROR
    }
}
