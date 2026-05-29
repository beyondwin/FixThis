package io.github.beyondwin.fixthis.compose.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceCandidateMappersTest {
    @Test
    fun preservesCallSitesAcrossSourceHintRoundTrip() {
        val candidate = SourceCandidate(
            file = "ui/PrimaryButton.kt",
            line = 8,
            score = 0.5,
            confidence = SelectionConfidence.MEDIUM,
            callSites = listOf(
                SourceLocationRef(file = "ui/ScreenA.kt", line = 42),
                SourceLocationRef(file = "ui/ScreenB.kt", line = 13),
            ),
        )

        val roundTripped = candidate.toSourceHint().toSourceCandidate()

        assertEquals(candidate.callSites, roundTripped.callSites)
    }
}
