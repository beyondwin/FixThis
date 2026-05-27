package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetBoundaryGuidanceTest {
    @Test
    fun interopWarningProducesInteropBoundaryGuidance() {
        val guidance = TargetBoundaryGuidance.from(
            areaItem(warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)),
        )

        assertEquals("interop-risk", guidance.compactToken)
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary: possible AndroidView/WebView target; source candidates are context only.",
            ),
        )
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary action: inspect the Compose parent or native view boundary before editing.",
            ),
        )
    }

    @Test
    fun visualAreaProducesNoExactSourceGuidance() {
        val guidance = TargetBoundaryGuidance.from(areaItem(warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY)))

        assertEquals("visual-area", guidance.compactToken)
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary: visual area target; do not infer an exact Compose owner from nearby labels.",
            ),
        )
    }

    @Test
    fun noMeaningfulComposeTargetProducesSearchGuidance() {
        val guidance = TargetBoundaryGuidance.from(
            nodeItem(warnings = listOf(TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET)),
        )

        assertEquals("no-compose-target", guidance.compactToken)
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary: no meaningful Compose node covers this target; search from surrounding labels.",
            ),
        )
    }

    @Test
    fun strongNodeHasNoBoundaryGuidance() {
        assertEquals(TargetBoundaryGuidance.NONE, TargetBoundaryGuidance.from(nodeItem(warnings = emptyList())))
    }

    private fun nodeItem(warnings: List<TargetReliabilityWarning>): AnnotationDto = AnnotationDto(
        itemId = "node-item",
        screenId = "screen",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node("node", FixThisRect(0f, 0f, 100f, 80f)),
        comment = "Edit this target",
        targetReliability = reliability(warnings),
    )

    private fun areaItem(warnings: List<TargetReliabilityWarning>): AnnotationDto = AnnotationDto(
        itemId = "area-item",
        screenId = "screen",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 100f, 80f)),
        comment = "Edit this area",
        targetReliability = reliability(warnings),
    )

    private fun reliability(warnings: List<TargetReliabilityWarning>): TargetReliability = TargetReliability(
        confidence = if (warnings.isEmpty()) TargetConfidence.HIGH else TargetConfidence.LOW,
        warnings = warnings,
    )
}
