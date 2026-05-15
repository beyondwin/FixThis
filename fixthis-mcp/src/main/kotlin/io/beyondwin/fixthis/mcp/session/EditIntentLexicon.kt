package io.beyondwin.fixthis.mcp.session

internal enum class RawEditIntentSignal {
    COLOR_STYLE,
    BACKGROUND_STYLE,
    TEXT_STYLE,
    TYPOGRAPHY,
    SPACING,
    CONTENT_ONLY,
}

internal object EditIntentLexicon {
    fun classify(comment: String): Set<RawEditIntentSignal> {
        val normalized = comment.lowercase().trim()
        if (normalized.isEmpty()) return emptySet()

        val signals = linkedSetOf<RawEditIntentSignal>()
        if (normalized.hasAny("색", "색상", "컬러", "파란", "빨간", "초록", "보라", "color", "blue", "red", "green", "purple")) {
            signals += RawEditIntentSignal.COLOR_STYLE
        }
        if (normalized.hasAny("배경", "배경색", "카드색", "background", "container", "card color")) {
            signals += RawEditIntentSignal.BACKGROUND_STYLE
        }
        if (normalized.hasAny("글자색", "텍스트색", "텍스트컬러", "text color", "label color")) {
            signals += RawEditIntentSignal.TEXT_STYLE
        } else if (RawEditIntentSignal.COLOR_STYLE in signals && normalized.hasAny("글자", "label")) {
            signals += RawEditIntentSignal.TEXT_STYLE
        }
        if (normalized.hasAny("크게", "작게", "폰트", "글씨", "글자 크기", "텍스트 크기", "더크게", "더 크게", "font", "type", "text size", "bigger", "smaller", "larger")) {
            signals += RawEditIntentSignal.TYPOGRAPHY
        }
        if (normalized.hasAny("마진", "패딩", "간격", "바텀마진", "아래", "여백", "dp", "margin", "padding", "spacing", "gap", "bottom", "top")) {
            signals += RawEditIntentSignal.SPACING
        }
        if (normalized.hasAny("문구", "텍스트를", "이름", "바꿔", "변경", "rename", "change text", "copy", "label to", "text to")) {
            signals += RawEditIntentSignal.CONTENT_ONLY
        }
        return signals
    }

    private fun String.hasAny(vararg tokens: String): Boolean = tokens.any { contains(it.lowercase()) }
}
