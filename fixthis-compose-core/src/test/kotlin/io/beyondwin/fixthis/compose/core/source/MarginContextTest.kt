package io.beyondwin.fixthis.compose.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarginContextTest {
    @Test
    fun singleScoreYieldsMarginOne() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.6), index = 0)

        assertEquals(1.0, context.scoreMargin, 0.0)
        assertFalse(context.isAmbiguous)
        assertFalse(context.isMediumCeiling)
    }

    @Test
    fun closeRaceMarginIsAmbiguous() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.5, 0.45), index = 0)

        assertTrue(context.scoreMargin < 0.15)
        assertTrue(context.isAmbiguous)
        assertFalse(context.isMediumCeiling)
    }

    @Test
    fun mediumCeilingZoneIsNotAmbiguous() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.5, 0.41), index = 0)

        assertTrue(context.scoreMargin in 0.15..0.20)
        assertFalse(context.isAmbiguous)
        assertTrue(context.isMediumCeiling)
    }

    @Test
    fun clearMarginIsNeitherAmbiguousNorCeiling() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.9, 0.5), index = 0)

        assertEquals((0.9 - 0.5) / 0.9, context.scoreMargin, 1e-9)
        assertFalse(context.isAmbiguous)
        assertFalse(context.isMediumCeiling)
    }

    @Test
    fun nonTopCandidateUsesPrevAndNextDifferenceForRanking() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.9, 0.7, 0.5), index = 1)

        // For non-top candidates, scoreMargin reuses the top-vs-next gap.
        // The rank reflects 1-based position.
        assertEquals(2, context.ranking)
        assertTrue(context.scoreMargin > 0.0)
    }

    @Test
    fun zeroTopScoreFallsBackToSafeDenominator() {
        val context = MarginContext.of(scoresHighestFirst = listOf(0.0), index = 0)

        assertEquals(1.0, context.scoreMargin, 0.0)
    }
}
