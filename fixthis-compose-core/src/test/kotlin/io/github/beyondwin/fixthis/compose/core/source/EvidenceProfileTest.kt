package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceProfileTest {
    @Test
    fun strictCompTagAndSelectedTagAreStrong() {
        val profile = EvidenceProfile.fromReasons(
            reasons = listOf(
                "selected testTag",
                "selected testTag convention composable",
                "activity",
            ),
            rawScore = 120.0,
        )

        assertEquals(SourceEvidenceStrength.STRONG, profile.strength())
        assertTrue(profile.hasStrictCompTag)
        assertTrue(profile.hasSelectedTestTag)
        assertEquals(1, profile.selectedStrongCount)
    }

    @Test
    fun selectedTextOnlyIsTextOnlyAndMedium() {
        val profile = EvidenceProfile.fromReasons(
            reasons = listOf("selected text"),
            rawScore = 45.0,
        )

        assertEquals(SourceEvidenceStrength.MEDIUM, profile.strength())
        assertTrue(profile.isTextOnly)
        assertFalse(profile.isNearbyOnly)
    }

    @Test
    fun nearbyOnlyAndActivityOnlyDetected() {
        val nearby = EvidenceProfile.fromReasons(
            reasons = listOf("nearby text", "nearby role"),
            rawScore = 30.0,
        )
        val activity = EvidenceProfile.fromReasons(
            reasons = listOf("activity"),
            rawScore = 15.0,
        )

        assertTrue(nearby.isNearbyOnly)
        assertEquals(SourceEvidenceStrength.WEAK, nearby.strength())
        assertTrue(activity.isActivityOnly)
        assertEquals(SourceEvidenceStrength.WEAK, activity.strength())
    }

    @Test
    fun arbitraryLiteralAndUntypedFallbackOnlyDetected() {
        // Realistic: the matcher always emits a bucket reason ("selected text"
        // / "selected contentDescription") alongside the origin marker.
        val literal = EvidenceProfile.fromReasons(
            reasons = listOf("selected text", "arbitrary literal"),
            rawScore = 12.0,
        )
        val untypedFallback = EvidenceProfile.fromReasons(
            reasons = listOf("selected text", "legacy fallback"),
            rawScore = 5.0,
        )

        assertTrue(literal.isArbitraryLiteralOnly)
        assertEquals(SourceEvidenceStrength.MEDIUM, literal.strength())
        assertTrue(untypedFallback.isUntypedFallbackOnly)
        assertEquals(SourceEvidenceStrength.MEDIUM, untypedFallback.strength())
    }

    @Test
    fun arbitraryLiteralWithStrongEvidenceIsNotLiteralOnly() {
        val mixed = EvidenceProfile.fromReasons(
            reasons = listOf("selected text", "selected testTag", "arbitrary literal"),
            rawScore = 80.0,
        )

        assertFalse(mixed.isArbitraryLiteralOnly)
        assertEquals(SourceEvidenceStrength.STRONG, mixed.strength())
    }

    @Test
    fun selectedTextAndContentDescriptionMakesTwoMediums() {
        val profile = EvidenceProfile.fromReasons(
            reasons = listOf("selected text", "selected contentDescription"),
            rawScore = 80.0,
        )

        assertEquals(SourceEvidenceStrength.MEDIUM, profile.strength())
        assertEquals(2, profile.distinctSelectedMediumKinds)
    }
}
