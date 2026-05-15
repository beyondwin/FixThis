package io.beyondwin.fixthis.mcp.session

internal data class EditIntent(
    val primaryKind: EditSurfaceKindDto,
    val reasons: List<EditSurfaceReasonDto>,
)

internal object EditIntentClassifier {
    fun classify(comment: String): EditIntent = EditIntentAnalyzer.analyzeCommentOnly(comment)
}
