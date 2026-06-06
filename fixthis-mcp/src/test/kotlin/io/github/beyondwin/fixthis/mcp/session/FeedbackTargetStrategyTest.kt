package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.target.ValidatedFeedbackTarget
import io.github.beyondwin.fixthis.mcp.session.target.strategy
import io.github.beyondwin.fixthis.mcp.session.target.targetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackTargetStrategyTest {
    @Test
    fun everyTargetTypeResolvesToAMatchingStrategy() {
        FeedbackTargetType.entries.forEach { type ->
            assertEquals(type, type.strategy().type)
        }
    }

    @Test
    fun targetKindMatchesType() {
        assertEquals(TargetKind.AREA, FeedbackTargetType.AREA.strategy().targetKind)
        assertEquals(TargetKind.NODE, FeedbackTargetType.NODE.strategy().targetKind)
    }

    @Test
    fun fallbackSummaryLineMatchesKind() {
        assertEquals("target: visual area", FeedbackTargetType.AREA.strategy().fallbackSummaryLine)
        assertEquals("target: semantics node", FeedbackTargetType.NODE.strategy().fallbackSummaryLine)
    }

    @Test
    fun dtoTargetTypeAdapterRoundTrips() {
        val rect = FixThisRect(0f, 0f, 1f, 1f)
        assertEquals(FeedbackTargetType.AREA, AnnotationTargetDto.Area(rect).targetType())
        assertEquals(FeedbackTargetType.NODE, AnnotationTargetDto.Node("n", rect).targetType())
    }

    @Test
    fun areaResolvesNoSelectedNode() {
        val strategy = FeedbackTargetType.AREA.strategy()
        assertNull(strategy.resolveSelectedNode(screenWith(), nodeUid = null, missingNodeContext = "screen"))
    }

    @Test
    fun nodeResolveRequiresUid() {
        val error = assertFailsWith<IllegalArgumentException> {
            FeedbackTargetType.NODE.strategy().resolveSelectedNode(screenWith(), nodeUid = null, missingNodeContext = "screen")
        }
        assertEquals("Node feedback requires nodeUid", error.message)
    }

    @Test
    fun nodeResolveReportsMissingNodeWithContext() {
        val error = assertFailsWith<IllegalArgumentException> {
            FeedbackTargetType.NODE.strategy().resolveSelectedNode(screenWith(), nodeUid = "missing", missingNodeContext = "preview")
        }
        assertEquals("Selected node does not exist on preview: missing", error.message)
    }

    @Test
    fun annotationTargetMatchesKind() {
        val rect = FixThisRect(5f, 5f, 50f, 50f)
        val selected = node("sel", rect, listOf("Save"))
        val area = FeedbackTargetType.AREA.strategy().annotationTarget(
            ValidatedFeedbackTarget(FeedbackTargetType.AREA, null, rect, emptyList()),
        )
        val nodeTarget = FeedbackTargetType.NODE.strategy().annotationTarget(
            ValidatedFeedbackTarget(FeedbackTargetType.NODE, selected, rect, emptyList()),
        )
        assertTrue(area is AnnotationTargetDto.Area)
        assertTrue(nodeTarget is AnnotationTargetDto.Node && nodeTarget.nodeUid == "sel")
    }

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 100L,
        displayName = "Checkout",
        screenshot = SnapshotScreenshotDto(width = 400, height = 400, desktopFullPath = "/tmp/screen.png"),
        roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 400f, 400f), mergedNodes = nodes.toList())),
    )

    private fun node(uid: String, bounds: FixThisRect, text: List<String> = emptyList()): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
    )
}
