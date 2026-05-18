package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal data class EditSurfaceRoleDecision(
    val role: EditSurfaceRoleDto,
    val confidenceCap: SelectionConfidence,
    val note: String? = null,
)

internal object EditSurfaceRoleClassifier {
    fun classify(item: AnnotationDto, intent: EditIntent): EditSurfaceRoleDecision {
        val interop = item.targetReliability?.warnings.orEmpty()
            .any { it == TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP }
        if (interop) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.INTEROP_RISK,
                confidenceCap = SelectionConfidence.LOW,
                note = "possible AndroidView/WebView area; verify runtime target before editing",
            )
        }
        if (item.target is AnnotationTargetDto.Area) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.VISUAL_AREA,
                confidenceCap = SelectionConfidence.LOW,
                note = "visual area selection has no precise semantics node",
            )
        }
        if (intent.primaryKind == EditSurfaceKindDto.SPACING) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
                confidenceCap = SelectionConfidence.LOW,
            )
        }
        if (intent.primaryKind in styleKinds() && hasComponentSignal(item)) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
                confidenceCap = SelectionConfidence.MEDIUM,
            )
        }
        if (looksLikeCopyIntent(item.comment)) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.COPY_OR_DATA,
                confidenceCap = SelectionConfidence.MEDIUM,
            )
        }
        return EditSurfaceRoleDecision(
            role = EditSurfaceRoleDto.CALL_SITE,
            confidenceCap = SelectionConfidence.MEDIUM,
        )
    }

    private fun styleKinds(): Set<EditSurfaceKindDto> = setOf(
        EditSurfaceKindDto.CONTAINER_COLOR,
        EditSurfaceKindDto.TEXT_COLOR,
        EditSurfaceKindDto.TYPOGRAPHY,
        EditSurfaceKindDto.CHIP_COLOR,
        EditSurfaceKindDto.COMPONENT_RENDERER,
    )

    private fun hasComponentSignal(item: AnnotationDto): Boolean = item.selectedNode?.testTag?.startsWith("comp:") == true ||
        item.sourceCandidates.any { candidate ->
            candidate.ownerComposable != null ||
                candidate.matchReasons.contains("selected testTag convention composable")
        }

    private fun looksLikeCopyIntent(comment: String): Boolean {
        val normalized = comment.lowercase()
        return listOf("rename", "copy", "text", "label", "wording", "문구", "텍스트", "이름").any {
            normalized.contains(it)
        }
    }
}
