package io.beyondwin.fixthis.mcp.session

internal data class EditIntent(
    val primaryKind: EditSurfaceKindDto,
    val reasons: List<EditSurfaceReasonDto>,
)

internal object EditIntentClassifier {
    fun classify(comment: String): EditIntent {
        val normalized = comment.lowercase()
        return when {
            normalized.hasAny("배경", "배경색", "카드색", "background", "container", "card color") ->
                EditIntent(EditSurfaceKindDto.CONTAINER_COLOR, listOf(EditSurfaceReasonDto.STYLE_INTENT))
            normalized.hasAny("마진", "패딩", "간격", "바텀마진", "아래", "margin", "padding", "spacing", "bottom") ->
                EditIntent(EditSurfaceKindDto.SPACING, listOf(EditSurfaceReasonDto.LAYOUT_INTENT))
            normalized.hasAny("크게", "작게", "폰트", "글씨", "더크게", "bigger", "smaller", "font", "text size", "typography") ->
                EditIntent(EditSurfaceKindDto.TYPOGRAPHY, listOf(EditSurfaceReasonDto.TYPOGRAPHY_INTENT))
            normalized.hasAny(
                "글자",
                "텍스트",
                "컬러",
                "색",
                "파란",
                "빨간",
                "보라",
                "text",
                "label",
                "color",
                "blue",
                "red",
                "purple",
            ) ->
                EditIntent(EditSurfaceKindDto.TEXT_COLOR, listOf(EditSurfaceReasonDto.STYLE_INTENT))
            else ->
                EditIntent(EditSurfaceKindDto.UNKNOWN, emptyList())
        }
    }

    private fun String.hasAny(vararg tokens: String): Boolean = tokens.any { contains(it.lowercase()) }
}
