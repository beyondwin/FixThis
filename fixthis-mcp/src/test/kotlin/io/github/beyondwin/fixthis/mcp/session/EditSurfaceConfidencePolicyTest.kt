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
        riskFlags: List<io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk> = emptyList(),
        evidenceStrength: io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength? = null,
        ownerComposable: String? = null,
    ) = SourceCandidate(
        file = "Foo.kt",
        line = 10,
        score = 0.8,
        matchedTerms = emptyList(),
        matchReasons = reasons,
        confidence = confidence,
        riskFlags = riskFlags,
        evidenceStrength = evidenceStrength,
        ownerComposable = ownerComposable,
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
    fun `component definition promotes to high for non-shared strong definition`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
            ),
        )
        assertEquals(SelectionConfidence.HIGH, result.confidence)
    }

    @Test
    fun `component definition stays medium for shared definition even when strong`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.SHARED_COMPONENT),
            ),
        )
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.basis.contains("call site", ignoreCase = true))
    }

    @Test
    fun `component definition demotes to low under ambiguity`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.AMBIGUOUS),
            ),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
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
    fun `copy or data promotes to high under strong exact-literal evidence`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                reasons = listOf("selected text"),
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
            ),
        )
        assertEquals(SelectionConfidence.HIGH, result.confidence)
        assertTrue(result.basis.contains("exact", ignoreCase = true))
    }

    @Test
    fun `copy or data demotes to low under ambiguity even with exact literal`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                reasons = listOf("selected text"),
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.AMBIGUOUS),
            ),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
    }

    @Test
    fun `copy or data demotes to low under proximity-only evidence`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(
                SelectionConfidence.MEDIUM,
                reasons = listOf("nearby text"),
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.NEARBY_ONLY),
            ),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
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
