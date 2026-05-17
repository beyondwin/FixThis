package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceCandidateRiskPrecedenceTest {
    @Test
    fun pickHighestPrecedenceFlag() {
        val flags = listOf(
            SourceCandidateRisk.TEXT_ONLY,
            SourceCandidateRisk.AMBIGUOUS,
            SourceCandidateRisk.UNTYPED_FALLBACK,
        )
        assertEquals(SourceCandidateRisk.AMBIGUOUS, SourceCandidateRiskPrecedence.highest(flags))
    }

    @Test
    fun emptyFlagsReturnsNull() {
        assertNull(SourceCandidateRiskPrecedence.highest(emptyList()))
    }

    @Test
    fun precedenceOrderIsAmbiguousAreaTextNearbyLiteralActivityLegacy() {
        assertEquals(
            listOf(
                SourceCandidateRisk.AMBIGUOUS,
                SourceCandidateRisk.AREA_SELECTION,
                SourceCandidateRisk.TEXT_ONLY,
                SourceCandidateRisk.NEARBY_ONLY,
                SourceCandidateRisk.ARBITRARY_LITERAL,
                SourceCandidateRisk.ACTIVITY_ONLY,
                SourceCandidateRisk.UNTYPED_FALLBACK,
            ),
            SourceCandidateRiskPrecedence.orderedHighestFirst,
        )
    }

    @Test
    fun orderedReturnsFlagsInPrecedenceOrder() {
        val flags = listOf(
            SourceCandidateRisk.ACTIVITY_ONLY,
            SourceCandidateRisk.TEXT_ONLY,
            SourceCandidateRisk.NEARBY_ONLY,
        )
        assertEquals(
            listOf(
                SourceCandidateRisk.TEXT_ONLY,
                SourceCandidateRisk.NEARBY_ONLY,
                SourceCandidateRisk.ACTIVITY_ONLY,
            ),
            SourceCandidateRiskPrecedence.ordered(flags),
        )
    }
}
