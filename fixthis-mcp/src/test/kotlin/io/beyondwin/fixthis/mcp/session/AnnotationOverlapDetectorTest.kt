package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AnnotationOverlapDetectorTest {
    @Test
    fun nonOverlappingNodeBoundsAreNotGrouped() {
        val a = AnnotationOverlapDetector.Item(id = "a", bounds = FixThisRect(0f, 0f, 10f, 10f), isAreaSelection = false)
        val b = AnnotationOverlapDetector.Item(id = "b", bounds = FixThisRect(50f, 50f, 60f, 60f), isAreaSelection = false)

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(2, groups.size)
        assertFalse(groups.any { it.size > 1 })
    }

    @Test
    fun anyAreaSelectionIntersectionGroupsItems() {
        val a = AnnotationOverlapDetector.Item("a", FixThisRect(0f, 0f, 100f, 100f), isAreaSelection = true)
        val b = AnnotationOverlapDetector.Item("b", FixThisRect(99f, 99f, 200f, 200f), isAreaSelection = true)

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(1, groups.size)
        assertEquals(setOf("a", "b"), groups.first().map { it.id }.toSet())
    }

    @Test
    fun ioSAAboveQuarterCountsAsOverlapForNodes() {
        // Smaller bounds 10x10 = 100. Intersection 5x5 = 25. IoSA = 25/100 = 0.25
        val a = AnnotationOverlapDetector.Item("a", FixThisRect(0f, 0f, 10f, 10f), isAreaSelection = false)
        val b = AnnotationOverlapDetector.Item("b", FixThisRect(5f, 5f, 100f, 100f), isAreaSelection = false)

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(1, groups.size)
    }

    @Test
    fun ioSABelowQuarterDoesNotGroup() {
        val a = AnnotationOverlapDetector.Item("a", FixThisRect(0f, 0f, 10f, 10f), isAreaSelection = false)
        val b = AnnotationOverlapDetector.Item("b", FixThisRect(9f, 9f, 100f, 100f), isAreaSelection = false)

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(2, groups.size)
    }

    @Test
    fun centerDistanceFallbackUsesDefaultDensity() {
        val a = AnnotationOverlapDetector.Item("a", FixThisRect(0f, 0f, 10f, 10f), isAreaSelection = false, hasWeakLabels = true)
        val b = AnnotationOverlapDetector.Item("b", FixThisRect(20f, 0f, 30f, 10f), isAreaSelection = false, hasWeakLabels = true)
        // Center distance is ~20px. With density 1.0 the threshold is 24, so overlap.

        val groups = AnnotationOverlapDetector.detect(listOf(a, b))

        assertEquals(1, groups.size)
    }
}
