package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditSurfaceConfidencePolicyTest {
    private fun candidate(
        confidence: SelectionConfidence,
        reasons: List<String> = listOf("selected owner composable"),
    ) = SourceCandidate(
        file = "Foo.kt",
        line = 10,
        score = 0.8,
        matchedTerms = emptyList(),
        matchReasons = reasons,
        confidence = confidence,
    )

    @Test
    fun `interop risk is always low with a boundary basis`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.INTEROP_RISK,
            sourceCandidate = candidate(SelectionConfidence.HIGH),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
        assertTrue(result.basis.contains("interop", ignoreCase = true))
    }

    @Test
    fun `call site keeps high source confidence under strong evidence`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.CALL_SITE,
            sourceCandidate = candidate(SelectionConfidence.HIGH),
        )
        assertEquals(SelectionConfidence.HIGH, result.confidence)
        assertTrue(result.basis.contains("selected owner composable"))
    }

    @Test
    fun `call site with low source confidence stays low (not flat medium)`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.CALL_SITE,
            sourceCandidate = candidate(SelectionConfidence.LOW),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
    }

    @Test
    fun `component definition stays capped at medium with a shared-definition basis`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            sourceCandidate = candidate(SelectionConfidence.HIGH),
        )
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.basis.contains("call site", ignoreCase = true))
    }

    @Test
    fun `copy or data with a string-resource match is medium`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(SelectionConfidence.MEDIUM, listOf("selected stringResource")),
        )
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
    }

    @Test
    fun `null source candidate yields none confidence`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.CALL_SITE,
            sourceCandidate = null,
        )
        assertEquals(SelectionConfidence.NONE, result.confidence)
    }
}
