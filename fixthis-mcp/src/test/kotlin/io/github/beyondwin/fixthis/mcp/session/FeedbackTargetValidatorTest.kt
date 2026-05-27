package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeedbackTargetValidatorTest {
    private val validator = FeedbackTargetValidator()

    @Test
    fun rejectsBlankCommentWhenBlankCommentsAreNotAllowed() {
        val error = assertFailsWith<IllegalArgumentException> {
            validator.validate(
                FeedbackTargetValidationRequest(
                    screen = screenWith(node("title", bounds = FixThisRect(10f, 10f, 100f, 60f), text = listOf("Title"))),
                    selection = FeedbackTargetSelection(
                        targetType = FeedbackTargetType.AREA,
                        bounds = FixThisRect(10f, 10f, 100f, 60f),
                        nodeUid = null,
                    ),
                    options = FeedbackTargetValidationOptions(
                        comment = "",
                        allowBlankComment = false,
                    ),
                ),
            )
        }

        assertEquals("Feedback comment must not be blank", error.message)
    }

    @Test
    fun reportsMissingNodeWithProvidedContext() {
        val error = assertFailsWith<IllegalArgumentException> {
            validator.validate(
                FeedbackTargetValidationRequest(
                    screen = screenWith(),
                    selection = FeedbackTargetSelection(
                        targetType = FeedbackTargetType.NODE,
                        bounds = FixThisRect(10f, 10f, 100f, 60f),
                        nodeUid = "missing",
                    ),
                    options = FeedbackTargetValidationOptions(
                        comment = "Fix label",
                        allowBlankComment = false,
                        missingNodeContext = "preview",
                    ),
                ),
            )
        }

        assertEquals("Selected node does not exist on preview: missing", error.message)
    }

    @Test
    fun nodeTargetUsesSelectedNodeBoundsAndNearbyEvidence() {
        val selected = node("selected", bounds = FixThisRect(10f, 10f, 100f, 60f), text = listOf("Save"))
        val nearby = node("nearby", bounds = FixThisRect(120f, 10f, 220f, 60f), text = listOf("Settings"))

        val target = validator.validate(
            FeedbackTargetValidationRequest(
                screen = screenWith(selected, nearby),
                selection = FeedbackTargetSelection(
                    targetType = FeedbackTargetType.NODE,
                    bounds = FixThisRect(0f, 0f, 1f, 1f),
                    nodeUid = "selected",
                ),
                options = FeedbackTargetValidationOptions(
                    comment = "Fix save",
                    allowBlankComment = false,
                ),
            ),
        )

        assertEquals(selected, target.selectedNode)
        assertEquals(selected.boundsInWindow, target.storedBounds)
        assertEquals(listOf(nearby), target.evidenceNodes)
    }

    @Test
    fun areaTargetPrefersOverlappingEvidenceThenNearestFallback() {
        val overlap = node("overlap", bounds = FixThisRect(20f, 20f, 120f, 120f), text = listOf("Overlap"))
        val near = node("near", bounds = FixThisRect(230f, 230f, 280f, 280f), text = listOf("Near"))

        val overlappingTarget = validator.validate(
            FeedbackTargetValidationRequest(
                screen = screenWith(near, overlap),
                selection = FeedbackTargetSelection(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(30f, 30f, 90f, 90f),
                    nodeUid = null,
                ),
                options = FeedbackTargetValidationOptions(
                    comment = "Fix area",
                    allowBlankComment = false,
                ),
            ),
        )

        assertEquals(listOf(overlap), overlappingTarget.evidenceNodes)

        val fallbackTarget = validator.validate(
            FeedbackTargetValidationRequest(
                screen = screenWith(near, overlap),
                selection = FeedbackTargetSelection(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(180f, 180f, 220f, 220f),
                    nodeUid = null,
                ),
                options = FeedbackTargetValidationOptions(
                    comment = "Fix empty area",
                    allowBlankComment = false,
                ),
            ),
        )

        assertEquals(listOf(near, overlap), fallbackTarget.evidenceNodes.take(2))
    }

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 100L,
        displayName = "Checkout",
        screenshot = SnapshotScreenshotDto(width = 400, height = 400, desktopFullPath = "/tmp/screen.png"),
        roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 400f, 400f), mergedNodes = nodes.toList())),
    )

    private fun node(
        uid: String,
        bounds: FixThisRect,
        text: List<String> = emptyList(),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
    )
}
