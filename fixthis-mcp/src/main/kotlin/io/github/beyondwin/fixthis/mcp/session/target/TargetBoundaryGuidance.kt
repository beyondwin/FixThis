package io.github.beyondwin.fixthis.mcp.session.target

import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto

internal data class TargetBoundaryGuidance(
    val compactToken: String? = null,
    val preciseLines: List<String> = emptyList(),
) {
    companion object {
        val NONE = TargetBoundaryGuidance()

        fun from(item: AnnotationDto): TargetBoundaryGuidance {
            val warnings = item.targetReliability?.warnings.orEmpty()
            return when {
                TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP in warnings -> TargetBoundaryGuidance(
                    compactToken = "interop-risk",
                    preciseLines = listOf(
                        "- Boundary: possible AndroidView/WebView target; source candidates are context only.",
                        "- Boundary source rule: source candidates are verification hints, not exact ownership.",
                        "- Boundary action: inspect-and-corroborate the Compose host first; verify native View/WebView ownership before editing.",
                    ),
                )
                item.target is AnnotationTargetDto.Area ||
                    TargetReliabilityWarning.VISUAL_AREA_ONLY in warnings -> TargetBoundaryGuidance(
                    compactToken = "visual-area",
                    preciseLines = listOf(
                        "- Boundary: visual area target; do not infer an exact Compose owner from nearby labels.",
                    ),
                )
                TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET in warnings -> TargetBoundaryGuidance(
                    compactToken = "no-compose-target",
                    preciseLines = listOf(
                        "- Boundary: no meaningful Compose node covers this target; search from surrounding labels.",
                    ),
                )
                else -> NONE
            }
        }
    }
}
