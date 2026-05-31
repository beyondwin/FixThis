package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate

internal data class EditSurfaceConfidenceResult(
    val confidence: SelectionConfidence,
    val basis: String,
)

internal object EditSurfaceConfidencePolicy {
    fun score(
        role: EditSurfaceRoleDto,
        sourceCandidate: SourceCandidate?,
    ): EditSurfaceConfidenceResult {
        val source = sourceCandidate?.confidence ?: SelectionConfidence.NONE
        val reasons = sourceCandidate?.matchReasons.orEmpty()
        return when (role) {
            EditSurfaceRoleDto.INTEROP_RISK -> EditSurfaceConfidenceResult(
                SelectionConfidence.LOW,
                "interop boundary: verify runtime target before editing",
            )
            EditSurfaceRoleDto.VISUAL_AREA -> EditSurfaceConfidenceResult(
                SelectionConfidence.LOW,
                "visual-area selection: no precise semantics node",
            )
            EditSurfaceRoleDto.COMPONENT_DEFINITION -> EditSurfaceConfidenceResult(
                cap(source, SelectionConfidence.MEDIUM),
                "shared component definition: editing it changes every call site",
            )
            EditSurfaceRoleDto.COPY_OR_DATA -> EditSurfaceConfidenceResult(
                cap(source, SelectionConfidence.MEDIUM),
                "matched copy/data source${reasonSuffix(reasons)}",
            )
            EditSurfaceRoleDto.LAYOUT_OR_STYLE -> EditSurfaceConfidenceResult(
                cap(source, SelectionConfidence.LOW),
                "layout/style edit applies at the call site",
            )
            EditSurfaceRoleDto.CALL_SITE -> EditSurfaceConfidenceResult(
                cap(source, SelectionConfidence.MEDIUM),
                "call site matched${reasonSuffix(reasons)}",
            )
        }
    }

    private val order = listOf(
        SelectionConfidence.NONE,
        SelectionConfidence.LOW,
        SelectionConfidence.MEDIUM,
        SelectionConfidence.HIGH,
    )

    private fun cap(value: SelectionConfidence, ceiling: SelectionConfidence): SelectionConfidence =
        if (order.indexOf(value) <= order.indexOf(ceiling)) value else ceiling

    private fun reasonSuffix(reasons: List<String>): String {
        val top = reasons.take(2)
        return if (top.isEmpty()) "" else ": ${top.joinToString(", ")}"
    }
}
