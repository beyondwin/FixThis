package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DuplicateMarkerDetectorTest {

    private fun makeKey(
        fileLine: String? = "Foo.kt:10",
        testTag: String? = null,
        pathLeaves: List<String> = listOf("root", "leaf"),
        bounds: FixThisRect = FixThisRect(0f, 0f, 100f, 100f),
    ) = DuplicateMarkerDetector.Key(
        fileLine = fileLine,
        testTag = testTag,
        pathLeaves = pathLeaves,
        bounds = bounds,
    )

    private fun makeItem(
        itemId: String,
        markerNumber: Int,
        key: DuplicateMarkerDetector.Key = makeKey(),
    ) = DuplicateMarkerDetector.Item(itemId = itemId, markerNumber = markerNumber, key = key)

    /**
     * Test 1: 2 items identical in (fileLine, testTag, pathLeaves, bounds)
     * → second item maps to first item's marker number
     */
    @Test
    fun twoIdenticalItems_secondMapsToFirstMarkerNumber() {
        val key = makeKey(fileLine = "Button.kt:42", testTag = "btn", pathLeaves = listOf("root", "node"), bounds = FixThisRect(0f, 0f, 100f, 50f))
        val items = listOf(
            makeItem("id-1", markerNumber = 1, key = key),
            makeItem("id-2", markerNumber = 2, key = key),
        )

        val result = DuplicateMarkerDetector.detect(items)

        assertEquals(1, result.size, "Only the duplicate (id-2) should be in the result map")
        assertEquals(1, result["id-2"]!!, "id-2 should refer to marker number 1 (id-1's marker)")
        assertTrue(!result.containsKey("id-1"), "id-1 (canonical) should NOT be in the result map")
    }

    /**
     * Test 2: 3 items, items 1 and 3 are identical, item 2 is different
     * → item 3 maps to marker 1 (item 1's marker); item 2 is not in the result
     */
    @Test
    fun threeItems_firstAndThirdIdentical_thirdMapsToFirstMarker() {
        val keyA = makeKey(fileLine = "Text.kt:99", testTag = "txt", pathLeaves = listOf("root", "text-node"), bounds = FixThisRect(10f, 20f, 110f, 60f))
        val keyB = makeKey(fileLine = "Other.kt:5", testTag = "other", pathLeaves = listOf("root", "other-node"), bounds = FixThisRect(0f, 0f, 50f, 50f))
        val items = listOf(
            makeItem("id-1", markerNumber = 1, key = keyA),
            makeItem("id-2", markerNumber = 2, key = keyB),
            makeItem("id-3", markerNumber = 3, key = keyA),
        )

        val result = DuplicateMarkerDetector.detect(items)

        assertEquals(1, result.size, "Only id-3 should be in result (duplicate of id-1)")
        assertEquals(1, result["id-3"]!!, "id-3 should refer to marker number 1 (id-1's marker)")
        assertTrue(!result.containsKey("id-1"), "id-1 (canonical) should NOT be in result")
        assertTrue(!result.containsKey("id-2"), "id-2 (unique) should NOT be in result")
    }

    /**
     * Test 3: Items differing only in bounds → NOT duplicates
     */
    @Test
    fun itemsDifferingOnlyInBounds_areNotDuplicates() {
        val key1 = makeKey(fileLine = "Foo.kt:10", testTag = "foo", pathLeaves = listOf("root", "leaf"), bounds = FixThisRect(0f, 0f, 100f, 100f))
        val key2 = makeKey(fileLine = "Foo.kt:10", testTag = "foo", pathLeaves = listOf("root", "leaf"), bounds = FixThisRect(0f, 0f, 200f, 200f))
        val items = listOf(
            makeItem("id-1", markerNumber = 1, key = key1),
            makeItem("id-2", markerNumber = 2, key = key2),
        )

        val result = DuplicateMarkerDetector.detect(items)

        assertTrue(result.isEmpty(), "Items differing in bounds should NOT be detected as duplicates")
    }

    /**
     * Test 4: Items differing only in path leaves → NOT duplicates
     */
    @Test
    fun itemsDifferingOnlyInPathLeaves_areNotDuplicates() {
        val key1 = makeKey(fileLine = "Foo.kt:10", testTag = "foo", pathLeaves = listOf("root", "leaf-A"), bounds = FixThisRect(0f, 0f, 100f, 100f))
        val key2 = makeKey(fileLine = "Foo.kt:10", testTag = "foo", pathLeaves = listOf("root", "leaf-B"), bounds = FixThisRect(0f, 0f, 100f, 100f))
        val items = listOf(
            makeItem("id-1", markerNumber = 1, key = key1),
            makeItem("id-2", markerNumber = 2, key = key2),
        )

        val result = DuplicateMarkerDetector.detect(items)

        assertTrue(result.isEmpty(), "Items differing in path leaves should NOT be detected as duplicates")
    }

    /**
     * Test 5: Markers are 1-indexed and follow the global appendCompactItem counter
     * (i.e., the actual markerNumber values from the items are preserved correctly)
     * Verifies that the canonical marker number is used exactly as provided, not re-indexed.
     */
    @Test
    fun markerNumbersFollowGlobalCounter_notReIndexed() {
        // Simulate a scenario where items have non-sequential marker numbers
        // (e.g., items 5 and 8 are duplicates in a session with many items)
        val key = makeKey(fileLine = "Card.kt:30", testTag = "card", pathLeaves = listOf("root", "card"), bounds = FixThisRect(5f, 5f, 95f, 95f))
        val items = listOf(
            makeItem("id-5", markerNumber = 5, key = key),
            makeItem("id-8", markerNumber = 8, key = key),
        )

        val result = DuplicateMarkerDetector.detect(items)

        assertEquals(1, result.size)
        assertEquals(5, result["id-8"]!!, "id-8 should refer to marker 5 (canonical), not re-indexed")
        assertTrue(!result.containsKey("id-5"), "id-5 (canonical) should NOT be in result")
    }
}
