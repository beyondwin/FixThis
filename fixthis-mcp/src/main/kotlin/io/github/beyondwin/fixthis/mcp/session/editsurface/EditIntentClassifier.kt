package io.github.beyondwin.fixthis.mcp.session.editsurface

import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceKindDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceReasonDto

internal data class EditIntent(
    val primaryKind: EditSurfaceKindDto,
    val reasons: List<EditSurfaceReasonDto>,
)

internal object EditIntentClassifier {
    fun classify(comment: String): EditIntent = EditIntentAnalyzer.analyzeCommentOnly(comment)
}
