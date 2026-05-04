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
}
