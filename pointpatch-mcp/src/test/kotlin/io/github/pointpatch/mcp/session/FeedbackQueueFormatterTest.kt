package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlin.test.Test
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
        assertTrue(markdown.contains("Handoff Batch: `missing-batch`"))
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
        assertTrue(markdown.contains("Missing feedback item: `missing-item`"))
    }
}
