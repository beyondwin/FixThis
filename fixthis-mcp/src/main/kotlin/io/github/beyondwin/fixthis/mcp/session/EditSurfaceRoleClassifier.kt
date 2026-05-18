package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal data class EditSurfaceRoleDecision(
    val role: EditSurfaceRoleDto,
    val confidenceCap: SelectionConfidence,
    val note: String? = null,
)

internal object EditSurfaceRoleClassifier {
    fun classify(item: AnnotationDto, intent: EditIntent): EditSurfaceRoleDecision = when {
        hasInteropRisk(item) -> decision(
            role = EditSurfaceRoleDto.INTEROP_RISK,
            confidenceCap = SelectionConfidence.LOW,
            note = "possible AndroidView/WebView area; verify runtime target before editing",
        )
        item.target is AnnotationTargetDto.Area -> decision(
            role = EditSurfaceRoleDto.VISUAL_AREA,
            confidenceCap = SelectionConfidence.LOW,
            note = "visual area selection has no precise semantics node",
        )
        intent.primaryKind == EditSurfaceKindDto.SPACING -> decision(
            role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
            confidenceCap = SelectionConfidence.LOW,
        )
        intent.primaryKind in styleKinds() && hasComponentSignal(item) -> decision(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            confidenceCap = SelectionConfidence.MEDIUM,
        )
        looksLikeCopyIntent(item.comment) -> decision(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            confidenceCap = SelectionConfidence.MEDIUM,
        )
        else -> decision(
            role = EditSurfaceRoleDto.CALL_SITE,
            confidenceCap = SelectionConfidence.MEDIUM,
        )
    }

    private fun decision(
        role: EditSurfaceRoleDto,
        confidenceCap: SelectionConfidence,
        note: String? = null,
    ): EditSurfaceRoleDecision = EditSurfaceRoleDecision(role, confidenceCap, note)

    private fun hasInteropRisk(item: AnnotationDto): Boolean = item.targetReliability
        ?.warnings
        .orEmpty()
        .any { it == TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP }

    private fun styleKinds(): Set<EditSurfaceKindDto> = setOf(
        EditSurfaceKindDto.CONTAINER_COLOR,
        EditSurfaceKindDto.TEXT_COLOR,
        EditSurfaceKindDto.TYPOGRAPHY,
        EditSurfaceKindDto.CHIP_COLOR,
        EditSurfaceKindDto.COMPONENT_RENDERER,
    )

    private fun hasComponentSignal(item: AnnotationDto): Boolean {
        val selectedNodeHasComponentTag = item.selectedNode?.testTag?.startsWith("comp:") == true
        return selectedNodeHasComponentTag ||
            item.sourceCandidates.any { candidate ->
                candidate.ownerComposable != null ||
                    candidate.matchReasons.contains("selected testTag convention composable")
            }
    }

    private fun looksLikeCopyIntent(comment: String): Boolean {
        val normalized = comment.lowercase()
        return listOf("rename", "copy", "text", "label", "wording", "문구", "텍스트", "이름").any {
            normalized.contains(it)
        }
    }
}
