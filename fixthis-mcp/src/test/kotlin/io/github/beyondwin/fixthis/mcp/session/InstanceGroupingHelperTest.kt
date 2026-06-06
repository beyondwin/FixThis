package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceGroupingHelperTest {

    // Helper to create a minimal AnnotationDto
    private fun makeItem(
        itemId: String,
        file: String = "Foo.kt",
        line: Int = 10,
        testTag: String? = null,
        path: List<String> = emptyList(),
    ): AnnotationDto = AnnotationDto(
        itemId = itemId,
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
        selectedNode = FixThisNode(
            uid = "node-$itemId",
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 1f, 1f),
            testTag = testTag,
            path = path,
        ),
        sourceCandidates = listOf(
            SourceCandidate(
                file = file,
                line = line,
                score = 1.0,
                confidence = SelectionConfidence.HIGH,
            ),
        ),
        comment = "test comment",
    )

    // Helper to create an AnnotationDto with no selectedNode
    private fun makeItemNoNode(
        itemId: String,
        file: String = "Foo.kt",
        line: Int = 10,
    ): AnnotationDto = AnnotationDto(
        itemId = itemId,
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
        selectedNode = null,
        sourceCandidates = listOf(
            SourceCandidate(
                file = file,
                line = line,
                score = 1.0,
                confidence = SelectionConfidence.HIGH,
            ),
        ),
        comment = "test comment",
    )

    // Helper to create an AnnotationDto with no sourceCandidates
    private fun makeItemNoCandidates(
        itemId: String,
        testTag: String? = null,
        path: List<String> = emptyList(),
    ): AnnotationDto = AnnotationDto(
        itemId = itemId,
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
        selectedNode = FixThisNode(
            uid = "node-$itemId",
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 1f, 1f),
            testTag = testTag,
            path = path,
        ),
        sourceCandidates = emptyList(),
        comment = "test comment",
    )

    @Test
    fun threeItemsWithSameFileLineAndTestTagGetLabeledInPathOrder() {
        // 3 items sharing same (file:line, testTag), distinct path leaves
        val items = listOf(
            makeItem("id-1", file = "Button.kt", line = 42, testTag = "btn", path = listOf("root", "node:1")),
            makeItem("id-2", file = "Button.kt", line = 42, testTag = "btn", path = listOf("root", "node:2")),
            makeItem("id-3", file = "Button.kt", line = 42, testTag = "btn", path = listOf("root", "node:3")),
        )

        val result = InstanceGroupingHelper.compute(items)

        assertEquals(3, result.labels.size, "All 3 items should be in the labels map")
        assertTrue(result.labels.containsKey("id-1"))
        assertTrue(result.labels.containsKey("id-2"))
        assertTrue(result.labels.containsKey("id-3"))

        // Ordered by path.joinToString("/")
        // "root/node:1" < "root/node:2" < "root/node:3"
        assertEquals(InstanceLabel(index = 1, total = 3), result.labels["id-1"])
        assertEquals(InstanceLabel(index = 2, total = 3), result.labels["id-2"])
        assertEquals(InstanceLabel(index = 3, total = 3), result.labels["id-3"])

        // Leader is the first item by path order
        assertEquals(setOf("id-1"), result.leaderItemIds)
    }

    @Test
    fun twoItemsWithSameFileLineAndTestTagButIdenticalPathGetLabeled() {
        // 2 items sharing same (file:line, testTag) AND same path → still grouped
        val items = listOf(
            makeItem("id-1", file = "Text.kt", line = 99, testTag = "txt", path = listOf("root", "node:5")),
            makeItem("id-2", file = "Text.kt", line = 99, testTag = "txt", path = listOf("root", "node:5")),
        )

        val result = InstanceGroupingHelper.compute(items)

        assertEquals(2, result.labels.size, "Both items should be in the labels map")
        // Both have same path, so order is stable by original list order (or either ordering)
        val label1 = result.labels["id-1"]!!
        val label2 = result.labels["id-2"]!!
        assertEquals(2, label1.total)
        assertEquals(2, label2.total)
        // indices must be 1 and 2
        val indices = setOf(label1.index, label2.index)
        assertEquals(setOf(1, 2), indices)
    }

    @Test
    fun singletonItemIsNotIncludedInLabels() {
        val items = listOf(
            makeItem("id-lone", file = "Lone.kt", line = 7, testTag = "lone", path = listOf("root")),
        )

        val result = InstanceGroupingHelper.compute(items)

        assertFalse(result.labels.containsKey("id-lone"), "Singleton item must not appear in labels")
        assertTrue(result.leaderItemIds.isEmpty(), "leaderItemIds should be empty for no groups")
    }

    @Test
    fun mixedGroupAndSingletonOnlyGroupAppearsInLabels() {
        // Group A: 2 items; lone item: 1 item
        val items = listOf(
            makeItem("group-a-1", file = "Card.kt", line = 20, testTag = "card", path = listOf("root", "node:1")),
            makeItem("group-a-2", file = "Card.kt", line = 20, testTag = "card", path = listOf("root", "node:2")),
            makeItem("lone", file = "Other.kt", line = 55, testTag = null, path = listOf("root")),
        )

        val result = InstanceGroupingHelper.compute(items)

        assertEquals(2, result.labels.size, "Only group A's 2 items should be in labels")
        assertTrue(result.labels.containsKey("group-a-1"))
        assertTrue(result.labels.containsKey("group-a-2"))
        assertFalse(result.labels.containsKey("lone"), "Lone item must not appear in labels")

        assertEquals(InstanceLabel(index = 1, total = 2), result.labels["group-a-1"])
        assertEquals(InstanceLabel(index = 2, total = 2), result.labels["group-a-2"])
        assertEquals(setOf("group-a-1"), result.leaderItemIds)
    }

    @Test
    fun itemMissingSelectedNodeIsExcludedFromGrouping() {
        // Two items that would otherwise form a group, but one has no selectedNode
        val items = listOf(
            makeItem("id-with-node", file = "Widget.kt", line = 5, testTag = "w", path = listOf("root", "a")),
            makeItemNoNode("id-no-node", file = "Widget.kt", line = 5),
        )

        val result = InstanceGroupingHelper.compute(items)

        // id-no-node is excluded, so id-with-node is a singleton → nothing in labels
        assertTrue(result.labels.isEmpty(), "Items missing selectedNode must be excluded; remaining singletons excluded too")
        assertTrue(result.leaderItemIds.isEmpty())
    }

    @Test
    fun itemMissingSourceCandidatesIsExcludedFromGrouping() {
        // Two items that would otherwise form a group, but one has no source candidates
        val items = listOf(
            makeItem("id-with-cand", file = "Widget.kt", line = 5, testTag = "w", path = listOf("root", "a")),
            makeItemNoCandidates("id-no-cand", testTag = "w", path = listOf("root", "b")),
        )

        val result = InstanceGroupingHelper.compute(items)

        // id-no-cand is excluded, so id-with-cand is a singleton → nothing in labels
        assertTrue(result.labels.isEmpty(), "Items missing source candidates must be excluded; remaining singletons excluded too")
        assertTrue(result.leaderItemIds.isEmpty())
    }
}
