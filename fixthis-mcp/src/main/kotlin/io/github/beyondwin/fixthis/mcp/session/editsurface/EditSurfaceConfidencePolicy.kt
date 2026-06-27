package io.github.beyondwin.fixthis.mcp.session.editsurface

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceRoleDto

internal data class EditSurfaceConfidenceResult(
    val confidence: SelectionConfidence,
    val basis: String,
    val action: String,
)

internal object EditSurfaceConfidencePolicy {
    fun score(
        role: EditSurfaceRoleDto,
        sourceCandidate: SourceCandidate?,
    ): EditSurfaceConfidenceResult {
        val source = sourceCandidate?.confidence ?: SelectionConfidence.NONE
        val reasons = sourceCandidate?.matchReasons.orEmpty()
        val evidence = EditSurfaceEvidence.from(sourceCandidate)
        return when (role) {
            EditSurfaceRoleDto.INTEROP_RISK -> withAction(
                role,
                SelectionConfidence.LOW,
                "interop boundary: verify runtime target before editing",
            )
            EditSurfaceRoleDto.VISUAL_AREA -> withAction(
                role,
                SelectionConfidence.LOW,
                "visual-area selection: no precise semantics node",
            )
            EditSurfaceRoleDto.COMPONENT_DEFINITION -> componentDefinition(role, source, evidence)
            EditSurfaceRoleDto.COPY_OR_DATA -> copyOrData(role, source, evidence, reasons)
            EditSurfaceRoleDto.LAYOUT_OR_STYLE -> layoutOrStyle(role, source, evidence)
            EditSurfaceRoleDto.CALL_SITE -> withAction(
                role,
                cap(source, SelectionConfidence.HIGH),
                "call site matched${reasonSuffix(reasons)}",
            )
        }
    }

    private fun withAction(
        role: EditSurfaceRoleDto,
        confidence: SelectionConfidence,
        basis: String,
    ): EditSurfaceConfidenceResult = EditSurfaceConfidenceResult(
        confidence = confidence,
        basis = basis,
        action = EditSurfaceRoleContracts.forRole(role).actionGuidance,
    )

    private fun componentDefinition(
        role: EditSurfaceRoleDto,
        source: SelectionConfidence,
        evidence: EditSurfaceEvidence,
    ): EditSurfaceConfidenceResult {
        val (ceiling, label) = when {
            evidence.ambiguous -> SelectionConfidence.LOW to "ambiguous owner — verify before editing"
            !evidence.shared && evidence.strong -> SelectionConfidence.HIGH to "single-owner definition"
            else -> SelectionConfidence.MEDIUM to "editing it changes every call site"
        }
        return withAction(role, cap(source, ceiling), "shared component definition: $label")
    }

    private fun layoutOrStyle(
        role: EditSurfaceRoleDto,
        source: SelectionConfidence,
        evidence: EditSurfaceEvidence,
    ): EditSurfaceConfidenceResult {
        val ceiling = if (evidence.confidentCallSite) SelectionConfidence.MEDIUM else SelectionConfidence.LOW
        return withAction(role, cap(source, ceiling), "layout/style edit applies at the call site")
    }

    private fun copyOrData(
        role: EditSurfaceRoleDto,
        source: SelectionConfidence,
        evidence: EditSurfaceEvidence,
        reasons: List<String>,
    ): EditSurfaceConfidenceResult {
        val (ceiling, label) = when {
            evidence.ambiguous || evidence.proximityOnly -> SelectionConfidence.LOW to "nearby only — verify"
            evidence.strong && evidence.exactCopyMatch -> SelectionConfidence.HIGH to "exact literal"
            else -> SelectionConfidence.MEDIUM to "matched copy/data"
        }
        return withAction(role, cap(source, ceiling), "$label${reasonSuffix(reasons)}")
    }

    private val order = listOf(
        SelectionConfidence.NONE,
        SelectionConfidence.LOW,
        SelectionConfidence.MEDIUM,
        SelectionConfidence.HIGH,
    )

    private fun cap(value: SelectionConfidence, ceiling: SelectionConfidence): SelectionConfidence = if (order.indexOf(value) <= order.indexOf(ceiling)) value else ceiling

    private fun reasonSuffix(reasons: List<String>): String {
        val top = reasons.take(2)
        return if (top.isEmpty()) "" else ": ${top.joinToString(", ")}"
    }
}
