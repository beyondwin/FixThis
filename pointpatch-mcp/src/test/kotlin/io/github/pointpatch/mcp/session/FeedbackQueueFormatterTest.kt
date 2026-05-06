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
    fun markdownFocusesOnRequestTargetEvidenceAndLikelySource() {
        val selectedNode = PointPatchNode(
            uid = "email-label",
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = PointPatchRect(28f, 77f, 692f, 186f),
            text = listOf("Email address"),
            testTag = "emailField",
        )
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            screens = listOf(
                SnapshotDto(
                    "screen-1",
                    2L,
                    activityName = "io.github.pointpatch.sample.MainActivity",
                    displayName = "MainActivity",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = AnnotationTargetDto.Node("email-label", selectedNode.boundsInWindow),
                    selectedNode = selectedNode,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/io/github/pointpatch/sample/screens/FormScreen.kt",
                            line = 37,
                            score = 0.95,
                            matchedTerms = listOf("Email address", "emailField"),
                            matchReasons = listOf("selected text", "selected testTag"),
                            confidence = SelectionConfidence.HIGH,
                        ),
                    ),
                    comment = "Give this field 20 more px of left margin",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("# PointPatch Feedback Handoff"))
        assertTrue(markdown.contains("Request:"))
        assertTrue(markdown.contains("Give this field 20 more px of left margin"))
        assertTrue(markdown.contains("Likely Source:"))
        assertTrue(markdown.contains("FormScreen.kt:37"))
        assertTrue(markdown.contains("Text: `Email address`"))
        assertTrue(markdown.contains("Test Tag: `emailField`"))
        assertFalse(markdown.contains("Delivery:"))
        assertFalse(markdown.contains("Status:"))
        assertFalse(markdown.contains("Captured At:"))
        assertFalse(markdown.contains("Screenshot Size:"))
    }

    @Test
    fun markdownIncludesAreaEvidenceNoteAndNearbyUiLabels() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 3L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Checkout",
                    screenshot = SnapshotScreenshotDto(
                        desktopFullPath = "/repo/.pointpatch/artifacts/screen-1/full.png",
                        width = 720,
                        height = 1600,
                    ),
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 3L,
                    target = AnnotationTargetDto.Area(PointPatchRect(112f, 426f, 351f, 588f)),
                    nearbyNodes = listOf(
                        PointPatchNode(
                            uid = "email-label",
                            composeNodeId = 42,
                            rootIndex = 0,
                            treeKind = TreeKind.MERGED,
                            boundsInWindow = PointPatchRect(28f, 77f, 692f, 186f),
                            text = listOf("Email address"),
                        ),
                        PointPatchNode(
                            uid = "submit-button",
                            composeNodeId = 43,
                            rootIndex = 0,
                            treeKind = TreeKind.MERGED,
                            boundsInWindow = PointPatchRect(120f, 610f, 351f, 680f),
                            contentDescription = listOf("Submit"),
                        ),
                    ),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/io/github/pointpatch/sample/screens/FormScreen.kt",
                            line = 54,
                            score = 0.5,
                            matchedTerms = listOf("Email address"),
                            matchReasons = listOf("semantics source metadata"),
                            confidence = SelectionConfidence.LOW,
                        ),
                    ),
                    comment = "Change this visual area",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("- Type: Visual area"))
        assertTrue(markdown.contains("- Bounds: `112.0,426.0,351.0,588.0`"))
        assertTrue(markdown.contains("- Nearby UI: `Email address`, `Submit`"))
        assertTrue(markdown.contains("- Note: area selection only; verify screenshot and source candidates."))
        assertTrue(markdown.contains("FormScreen.kt:54"))
        assertFalse(markdown.contains("full.png"))
        assertFalse(markdown.contains("720x1600"))
    }

    @Test
    fun markdownCapsAreaSourceConfidenceBelowHigh() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 3L,
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 3L,
                    target = AnnotationTargetDto.Area(PointPatchRect(112f, 426f, 351f, 588f)),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/io/github/pointpatch/sample/screens/FormScreen.kt",
                            line = 54,
                            score = 0.95,
                            matchedTerms = listOf("Promo card"),
                            matchReasons = listOf("selected text"),
                            confidence = SelectionConfidence.HIGH,
                        ),
                    ),
                    comment = "Change this visual area",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("FormScreen.kt:54"))
        assertTrue(markdown.contains("low confidence"))
        assertFalse(markdown.contains("high confidence"))
    }

    @Test
    fun markdownShowsEmptyFeedbackHandoffWithoutScreenHistory() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Captured But Unused",
                    screenshot = SnapshotScreenshotDto(width = 720, height = 1600),
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("- Feedback Items: `0`"))
        assertTrue(markdown.contains("No feedback items."))
        assertFalse(markdown.contains("Captured But Unused"))
        assertFalse(markdown.contains("Referenced Screens"))
        assertFalse(markdown.contains("Screenshot Size"))
    }

    @Test
    fun markdownExportsAllItemsWithoutDeliveryOrBatchMetadata() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 5L,
            screens = listOf(SnapshotDto("screen-1", 2L, displayName = "Checkout")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = AnnotationTargetDto.Area(PointPatchRect(0f, 0f, 10f, 10f)),
                    comment = "Draft spacing",
                    sequenceNumber = 1,
                    delivery = FeedbackDelivery.DRAFT,
                ),
                AnnotationDto(
                    itemId = "item-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = AnnotationTargetDto.Area(PointPatchRect(10f, 10f, 20f, 20f)),
                    comment = "Sent copy",
                    sequenceNumber = 2,
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = "batch-1",
                    sentAtEpochMillis = 5L,
                    status = AnnotationStatusDto.READY,
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

        assertTrue(markdown.contains("## Item 1"))
        assertTrue(markdown.contains("Draft spacing"))
        assertTrue(markdown.contains("## Item 2"))
        assertTrue(markdown.contains("Sent copy"))
        assertFalse(markdown.contains("Delivery:"))
        assertFalse(markdown.contains("Sent History"))
        assertFalse(markdown.contains("Batch #1"))
        assertFalse(markdown.contains("batch-1"))
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
            editableText = "Pay now",
            contentDescription = listOf("Checkout CTA"),
            role = "Button",
        )
        val session = SessionDto(
            sessionId = "session-abcdef123456",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 5L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-abcdef123456",
                    capturedAtEpochMillis = 2L,
                    activityName = "io.github.pointpatch.CheckoutActivity",
                    displayName = "Checkout",
                    screenshot = SnapshotScreenshotDto(
                        desktopFullPath = "/repo/.pointpatch/feedback-sessions/session-abcdef123456/artifacts/screens/screen-abcdef123456/screen-abcdef123456-full.png",
                        width = 720,
                        height = 1600,
                    ),
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-abcdef123456",
                    screenId = "screen-abcdef123456",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
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
                    status = AnnotationStatusDto.READY,
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
        assertTrue(markdown.contains("- Feedback Items: `1`"))
        assertTrue(markdown.contains("- Type: Compose semantics node"))
        assertTrue(markdown.contains("- Text: `Pay now`"))
        assertTrue(markdown.contains("- Editable Text: `Pay now`"))
        assertTrue(markdown.contains("- Content Description: `Checkout CTA`"))
        assertTrue(markdown.contains("- Role: `Button`"))
        assertTrue(markdown.contains("- Bounds: `10.0,20.0,110.0,70.0`"))
        assertTrue(markdown.contains("app/src/main/java/CheckoutScreen.kt:42"))
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
        assertFalse(markdown.contains("Captured At:"))
        assertFalse(markdown.contains("Screenshot Size:"))
    }
}
