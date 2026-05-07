package io.beyondwin.fixthis.compose.core.format

enum class DetailMode {
    COMPACT,
    PRECISE,
    FULL;

    companion object {
        fun fromWire(value: String?): DetailMode = when (value?.trim()?.lowercase()) {
            null, "", "precise" -> PRECISE
            "compact" -> COMPACT
            "full" -> FULL
            else -> throw IllegalArgumentException("Unsupported detailMode: $value")
        }
    }
}
