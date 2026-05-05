package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.SelectionConfidence
import io.github.pointpatch.compose.core.model.SourceCandidate
import io.github.pointpatch.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackQueueFormatterTest {
    @Test
    fun markdownIncludesScreensItemsAndWarnings() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            screens = listOf(
                CapturedScreen(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Checkout",
                    screenshot = FeedbackScreenshot(desktopFullPath = "/repo/.pointpatch/artifacts/screen-1/full.png"),
                ),
            ),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = FeedbackTarget.Area(PointPatchRect(10f, 20f, 110f, 70f)),
                    comment = "Increase button contrast",
                    status = FeedbackItemStatus.READY,
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("# PointPatch Feedback Queue"))
        assertTrue(markdown.contains("io.github.pointpatch.sample"))
        assertTrue(markdown.contains("Checkout"))
        assertTrue(markdown.contains("Increase button contrast"))
        assertTrue(markdown.contains("Screenshots are local debug artifacts"))
    }

    @Test
    fun markdownOnlyIncludesScreensReferencedByFeedbackItems() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 3L,
            screens = listOf(
                CapturedScreen(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Referenced Checkout",
                ),
                CapturedScreen(
                    screenId = "screen-2",
                    capturedAtEpochMillis = 2L,
                    displayName = "Unreferenced Settings",
                    screenshot = FeedbackScreenshot(width = 720, height = 1600),
                ),
            ),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 3L,
                    target = FeedbackTarget.Area(PointPatchRect(10f, 20f, 110f, 70f)),
                    comment = "Referenced item",
                    status = FeedbackItemStatus.READY,
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("- Screens: `2`"))
        assertTrue(markdown.contains("## Referenced Screens"))
        assertTrue(markdown.contains("### Screen 1 - Referenced Checkout"))
        assertTrue(markdown.contains("- Feedback Count: `1`"))
        assertFalse(markdown.contains("Unreferenced Settings"))
        assertFalse(markdown.contains("No referenced screens."))
    }

    @Test
    fun markdownShowsNoReferencedScreensWhenThereAreNoFeedbackItems() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            screens = listOf(
                CapturedScreen(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Captured But Unused",
                    screenshot = FeedbackScreenshot(width = 720, height = 1600),
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("- Screens: `1`"))
        assertTrue(markdown.contains("- Feedback Items: `0`"))
        assertTrue(markdown.contains("## Referenced Screens"))
        assertTrue(markdown.contains("No referenced screens."))
        assertFalse(markdown.contains("Captured But Unused"))
        assertFalse(markdown.contains("- Screenshot Size: `720x1600`"))
        assertFalse(markdown.contains("No captured screens."))
    }

    @Test
    fun markdownGroupsDraftAndSentHandoffHistory() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 5L,
            screens = listOf(CapturedScreen("screen-1", 2L, displayName = "Checkout")),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
                    comment = "Draft spacing",
                    sequenceNumber = 1,
                    delivery = FeedbackDelivery.DRAFT,
                ),
                FeedbackItem(
                    itemId = "item-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = FeedbackTarget.Area(PointPatchRect(10f, 10f, 20f, 20f)),
                    comment = "Sent copy",
                    sequenceNumber = 2,
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = "batch-1",
                    sentAtEpochMillis = 5L,
                    status = FeedbackItemStatus.READY,
                ),
            ),
            handoffBatches = listOf(
                FeedbackHandoffBatch(
                    batchId = "batch-1",
                    sequenceNumber = 1,
                    createdAtEpochMillis = 5L,
                    itemIds = listOf("item-2"),
                    markdownSnapshot = "snapshot",
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("## Draft"))
        assertTrue(markdown.contains("Draft spacing"))
        assertTrue(markdown.contains("## Sent History"))
        assertTrue(markdown.contains("Batch #1"))
        assertTrue(markdown.contains("Sent copy"))
        assertTrue(markdown.contains("Delivery: `sent`"))
        assertTrue(markdown.contains("Screen 1 - Checkout"))
    }

    @Test
    fun markdownShowsSentItemWithMissingBatchInExplicitGroup() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 5L,
            screens = listOf(CapturedScreen("screen-1", 2L, displayName = "Checkout")),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
                    comment = "Sent without a known batch",
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = "missing-batch",
                    status = FeedbackItemStatus.READY,
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("### Unbatched / missing batch"))
        assertTrue(markdown.contains("Sent without a known batch"))
        assertFalse(markdown.contains("Handoff Batch: `missing-batch`"))
    }

    @Test
    fun markdownShowsPlaceholderForMissingBatchItemIds() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 5L,
            screens = listOf(CapturedScreen("screen-1", 2L, displayName = "Checkout")),
            handoffBatches = listOf(
                FeedbackHandoffBatch(
                    batchId = "batch-1",
                    sequenceNumber = 1,
                    createdAtEpochMillis = 5L,
                    itemIds = listOf("missing-item"),
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("Batch #1"))
        assertTrue(markdown.contains("Missing feedback item metadata."))
        assertFalse(markdown.contains("missing-item"))
    }

    @Test
    fun markdownOmitsInternalIdsWhileKeepingActionableContext() {
        val selectedNode = PointPatchNode(
            uid = "compose:0:merged:42",
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = PointPatchRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
            contentDescription = listOf("Checkout CTA"),
        )
        val session = FeedbackSession(
            sessionId = "session-abcdef123456",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 5L,
            screens = listOf(
                CapturedScreen(
                    screenId = "screen-abcdef123456",
                    capturedAtEpochMillis = 2L,
                    activityName = "io.github.pointpatch.CheckoutActivity",
                    displayName = "Checkout",
                    screenshot = FeedbackScreenshot(
                        desktopFullPath = "/repo/.pointpatch/feedback-sessions/session-abcdef123456/artifacts/screens/screen-abcdef123456/screen-abcdef123456-full.png",
                        width = 720,
                        height = 1600,
                    ),
                ),
            ),
            items = listOf(
                FeedbackItem(
                    itemId = "item-abcdef123456",
                    screenId = "screen-abcdef123456",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = FeedbackTarget.Node(selectedNode.uid, selectedNode.boundsInWindow),
                    selectedNode = selectedNode,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "app/src/main/java/CheckoutScreen.kt",
                            line = 42,
                            score = 0.91,
                            confidence = SelectionConfidence.HIGH,
                        ),
                    ),
                    comment = "Increase button contrast",
                    sequenceNumber = 1,
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = "batch-abcdef123456",
                    sentAtEpochMillis = 5L,
                    status = FeedbackItemStatus.READY,
                ),
            ),
            handoffBatches = listOf(
                FeedbackHandoffBatch(
                    batchId = "batch-abcdef123456",
                    sequenceNumber = 1,
                    createdAtEpochMillis = 5L,
                    itemIds = listOf("item-abcdef123456"),
                    markdownSnapshot = "old raw session dump",
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("- Package: `io.github.pointpatch.sample`"))
        assertTrue(markdown.contains("- Status: `active`"))
        assertTrue(markdown.contains("- Screens: `1`"))
        assertTrue(markdown.contains("- Feedback Items: `1`"))
        assertTrue(markdown.contains("- Handoff Batches: `1`"))
        assertTrue(markdown.contains("- Screen: `Screen 1 - Checkout`"))
        assertTrue(markdown.contains("- Activity: `io.github.pointpatch.CheckoutActivity`"))
        assertTrue(markdown.contains("- Captured At: `2`"))
        assertTrue(markdown.contains("- Screenshot Size: `720x1600`"))
        assertTrue(markdown.contains("- Screenshot: local/debug artifact available through PointPatch tooling"))
        assertTrue(markdown.contains("- Target: `node bounds 10.0,20.0,110.0,70.0`"))
        assertTrue(markdown.contains("- Selected Text: `Pay now`"))
        assertTrue(markdown.contains("- Selected Content Description: `Checkout CTA`"))
        assertTrue(markdown.contains("- Source Candidate: `app/src/main/java/CheckoutScreen.kt:42`"))
        assertTrue(markdown.contains("Increase button contrast"))

        assertFalse(markdown.contains("Session:"))
        assertFalse(markdown.contains("Screen ID:"))
        assertFalse(markdown.contains("Item ID:"))
        assertFalse(markdown.contains("Handoff Batch:"))
        assertFalse(markdown.contains("Batch ID:"))
        assertFalse(markdown.contains("session-abcdef123456"))
        assertFalse(markdown.contains("screen-abcdef123456"))
        assertFalse(markdown.contains("item-abcdef123456"))
        assertFalse(markdown.contains("batch-abcdef123456"))
        assertFalse(markdown.contains("old raw session dump"))
        assertFalse(markdown.contains("feedback-sessions"))
        assertFalse(markdown.contains("artifacts/screens"))
        assertFalse(markdown.contains("-full.png"))
    }
}
