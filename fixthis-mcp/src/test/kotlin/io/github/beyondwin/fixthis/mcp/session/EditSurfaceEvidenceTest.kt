package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength
import io.github.beyondwin.fixthis.mcp.session.editsurface.EditSurfaceEvidence
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditSurfaceEvidenceTest {
    private fun candidate(
        confidence: SelectionConfidence = SelectionConfidence.MEDIUM,
        matchReasons: List<String> = emptyList(),
        riskFlags: List<SourceCandidateRisk> = emptyList(),
        evidenceStrength: SourceEvidenceStrength? = null,
        ownerComposable: String? = null,
    ) = SourceCandidate(
        file = "Foo.kt",
        line = 1,
        score = 0.5,
        confidence = confidence,
        matchReasons = matchReasons,
        riskFlags = riskFlags,
        evidenceStrength = evidenceStrength,
        ownerComposable = ownerComposable,
    )

    @Test
    fun `null candidate yields all-false signals`() {
        val evidence = EditSurfaceEvidence.from(null)
        assertFalse(evidence.strong)
        assertFalse(evidence.exactCopyMatch)
        assertFalse(evidence.ambiguous)
        assertFalse(evidence.proximityOnly)
        assertFalse(evidence.shared)
        assertFalse(evidence.confidentCallSite)
    }

    @Test
    fun `strong is true only for STRONG evidence strength`() {
        assertTrue(EditSurfaceEvidence.from(candidate(evidenceStrength = SourceEvidenceStrength.STRONG)).strong)
        assertFalse(EditSurfaceEvidence.from(candidate(evidenceStrength = SourceEvidenceStrength.MEDIUM)).strong)
        assertFalse(EditSurfaceEvidence.from(candidate(evidenceStrength = null)).strong)
    }

    @Test
    fun `exactCopyMatch matches selected-literal wire labels but not nearby`() {
        assertTrue(EditSurfaceEvidence.from(candidate(matchReasons = listOf("selected text"))).exactCopyMatch)
        assertTrue(EditSurfaceEvidence.from(candidate(matchReasons = listOf("selected stringResource"))).exactCopyMatch)
        assertTrue(
            EditSurfaceEvidence.from(candidate(matchReasons = listOf("selected resolved stringResource"))).exactCopyMatch,
        )
        assertTrue(
            EditSurfaceEvidence.from(candidate(matchReasons = listOf("selected contentDescription"))).exactCopyMatch,
        )
        assertFalse(EditSurfaceEvidence.from(candidate(matchReasons = listOf("nearby text"))).exactCopyMatch)
    }

    @Test
    fun `ambiguous proximityOnly and shared read risk flags`() {
        assertTrue(EditSurfaceEvidence.from(candidate(riskFlags = listOf(SourceCandidateRisk.AMBIGUOUS))).ambiguous)
        assertTrue(EditSurfaceEvidence.from(candidate(riskFlags = listOf(SourceCandidateRisk.NEARBY_ONLY))).proximityOnly)
        assertTrue(EditSurfaceEvidence.from(candidate(riskFlags = listOf(SourceCandidateRisk.TEXT_ONLY))).proximityOnly)
        assertTrue(EditSurfaceEvidence.from(candidate(riskFlags = listOf(SourceCandidateRisk.SHARED_COMPONENT))).shared)
    }

    @Test
    fun `confidentCallSite requires HIGH confidence resolved owner and no ambiguity`() {
        assertTrue(
            EditSurfaceEvidence.from(
                candidate(confidence = SelectionConfidence.HIGH, ownerComposable = "Foo"),
            ).confidentCallSite,
        )
        assertFalse(
            EditSurfaceEvidence.from(
                candidate(confidence = SelectionConfidence.MEDIUM, ownerComposable = "Foo"),
            ).confidentCallSite,
        )
        assertFalse(
            EditSurfaceEvidence.from(
                candidate(confidence = SelectionConfidence.HIGH, ownerComposable = null),
            ).confidentCallSite,
        )
        assertFalse(
            EditSurfaceEvidence.from(
                candidate(
                    confidence = SelectionConfidence.HIGH,
                    ownerComposable = "Foo",
                    riskFlags = listOf(SourceCandidateRisk.AMBIGUOUS),
                ),
            ).confidentCallSite,
        )
    }
}
