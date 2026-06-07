package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRiskClassifierTest {
    private val sharedProfile = EvidenceProfile.fromMatchReasons(
        listOf(
            SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE,
            SourceMatchReason.SHARED_COMPONENT_DEFINITION,
        ),
        rawScore = 90.0,
    )

    @Test
    fun sharedComponentDefinitionCapsAtMediumEvenWithRecommendedCallSite() {
        val result = SourceRiskClassifier.applyCaps(
            sharedProfile,
            SelectionConfidence.HIGH,
        )

        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.flags.contains(SourceCandidateRisk.SHARED_COMPONENT))
    }

    @Test
    fun ambiguousSharedComponentStillCapsAtMedium() {
        val result = SourceRiskClassifier.applyCaps(
            sharedProfile,
            SelectionConfidence.HIGH,
        )

        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.flags.contains(SourceCandidateRisk.SHARED_COMPONENT))
    }
}
