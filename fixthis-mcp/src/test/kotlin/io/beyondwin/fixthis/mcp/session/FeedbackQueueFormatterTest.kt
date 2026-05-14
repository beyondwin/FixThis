package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.format.DetailMode
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.Occurrence
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignature
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.beyondwin.fixthis.compose.core.model.TargetReliability
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.beyondwin.fixthis.compose.core.model.TreeKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackQueueFormatterTest {
    @Test
    fun preciseMarkdownRendersTargetReliabilityWarnings() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Diagnostics",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 200f, 120f)),
                    comment = "Fix the native chart spacing",
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                    ),
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)

        assertTrue(markdown.contains("Target confidence: low"))
        assertTrue(markdown.contains("possible AndroidView/WebView area"))
    }

    @Test
    fun markdownFocusesOnRequestTargetEvidenceAndLikelySource() {
        val selectedNode = FixThisNode(
            uid = "email-label",
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(28f, 77f, 692f, 186f),
            text = listOf("Email address"),
            testTag = "emailField",
        )
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            screens = listOf(
                SnapshotDto(
                    "screen-1",
                    2L,
                    activityName = "io.beyondwin.fixthis.sample.MainActivity",
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
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt",
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

        assertTrue(markdown.contains("# FixThis Feedback Handoff"))
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
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 3L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Checkout",
                    screenshot = SnapshotScreenshotDto(
                        desktopFullPath = "/repo/.fixthis/artifacts/screen-1/full.png",
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
                    target = AnnotationTargetDto.Area(FixThisRect(112f, 426f, 351f, 588f)),
                    nearbyNodes = listOf(
                        FixThisNode(
                            uid = "email-label",
                            composeNodeId = 42,
                            rootIndex = 0,
                            treeKind = TreeKind.MERGED,
                            boundsInWindow = FixThisRect(28f, 77f, 692f, 186f),
                            text = listOf("Email address"),
                        ),
                        FixThisNode(
                            uid = "submit-button",
                            composeNodeId = 43,
                            rootIndex = 0,
                            treeKind = TreeKind.MERGED,
                            boundsInWindow = FixThisRect(120f, 610f, 351f, 680f),
                            contentDescription = listOf("Submit"),
                        ),
                    ),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt",
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
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 3L,
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 3L,
                    target = AnnotationTargetDto.Area(FixThisRect(112f, 426f, 351f, 588f)),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt",
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
            packageName = "io.beyondwin.fixthis.sample",
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
            packageName = "io.beyondwin.fixthis.sample",
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
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                    comment = "Draft spacing",
                    sequenceNumber = 1,
                    delivery = FeedbackDelivery.DRAFT,
                ),
                AnnotationDto(
                    itemId = "item-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 3L,
                    updatedAtEpochMillis = 4L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 20f, 20f)),
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
        val selectedNode = FixThisNode(
            uid = "compose:0:merged:42",
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
            editableText = "Pay now",
            contentDescription = listOf("Checkout CTA"),
            role = "Button",
        )
        val session = SessionDto(
            sessionId = "session-abcdef123456",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 5L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-abcdef123456",
                    capturedAtEpochMillis = 2L,
                    activityName = "io.beyondwin.fixthis.CheckoutActivity",
                    displayName = "Checkout",
                    screenshot = SnapshotScreenshotDto(
                        desktopFullPath = "/repo/.fixthis/feedback-sessions/session-abcdef123456/artifacts/screens/screen-abcdef123456/screen-abcdef123456-full.png",
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

        assertTrue(markdown.contains("- Package: `io.beyondwin.fixthis.sample`"))
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

    @Test
    fun defaultMarkdownUsesPreciseDetailMode() {
        val session = sessionWithTargetEvidenceAndSources()

        assertEquals(
            FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE),
            FeedbackQueueFormatter.toMarkdown(session),
        )
    }

    @Test
    fun preciseAndFullModesLimitSourceCandidateCounts() {
        val session = sessionWithTargetEvidenceAndSources()
        val precise = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)
        val full = FeedbackQueueFormatter.toMarkdown(session, DetailMode.FULL)

        assertTrue(precise.contains("AppPrimaryButton.kt:42"))
        assertTrue(precise.contains("CheckoutScreen.kt:88"))
        assertTrue(precise.contains("PaymentSummary.kt:12"))
        assertFalse(precise.contains("PaymentFooter.kt:24"))

        assertTrue(full.contains("AppPrimaryButton.kt:42"))
        assertTrue(full.contains("CheckoutScreen.kt:88"))
        assertTrue(full.contains("PaymentSummary.kt:12"))
        assertTrue(full.contains("PaymentFooter.kt:24"))
    }

    @Test
    fun markdownEscapesTargetIdentityHintsBeforeWritingInlineMarkdown() {
        val selectedNode = FixThisNode(
            uid = "compose:0:merged:42",
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
                    selectedNode = selectedNode,
                    targetEvidence = TargetEvidence(
                        identityHint = IdentityHint(
                            composableNameHint = "AppPrimaryButton\n# Injected Heading `compose`",
                            variantHint = "primary\n- Injected list `variant`",
                            source = IdentityHintSource.TEST_TAG_CONVENTION,
                            confidence = IdentityHintConfidence.HIGH,
                        ),
                    ),
                    comment = "Increase button contrast",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)
        val outsideCodeFences = markdownOutsideCodeFences(markdown)

        assertTrue(outsideCodeFences.contains("- Identity: `"))
        assertFalse(Regex("(?m)^# Injected Heading").containsMatchIn(outsideCodeFences))
        assertFalse(Regex("(?m)^- Injected list").containsMatchIn(outsideCodeFences))
    }

    @Test
    fun jsonDoesNotChangeWithDetailMode() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                    comment = "Increase button contrast",
                    sequenceNumber = 1,
                ),
            ),
        )

        val before = FeedbackQueueFormatter.toJson(session)
        FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
        FeedbackQueueFormatter.toMarkdown(session, DetailMode.FULL)

        assertEquals(before, FeedbackQueueFormatter.toJson(session))
    }

    @Test
    fun compactMarkdownEmitsTopLevelVerificationRule() {
        val markdown = FeedbackQueueFormatter.toMarkdown(sessionWithTargetEvidenceAndSources(), DetailMode.COMPACT)

        assertTrue(
            markdown.contains(
                "Rule: source hints are candidates; verify screenshot, target, and code before editing.",
            ),
        )
    }

    @Test
    fun compactMarkdownEmitsCandidatesBlock() {
        val markdown = FeedbackQueueFormatter.toMarkdown(sessionWithTargetEvidenceAndSources(), DetailMode.COMPACT)

        val lines = markdown.lines()
        assertTrue(
            lines.any { it.startsWith("  ") && it.contains("AppPrimaryButton.kt:42") && it.contains("conf=high") },
            "Expected a candidate line for AppPrimaryButton.kt:42 with conf=high in COMPACT markdown but got:\n$markdown",
        )
        assertFalse(
            lines.any { it.trim().startsWith("src?") },
            "Expected no 'src?' line in v2 COMPACT markdown but got:\n$markdown",
        )
        assertFalse(markdown.contains("matched:"))
        assertFalse(markdown.contains("reasons:"))
    }

    @Test
    fun compactMarkdownConfidenceTokenIsLowercase() {
        val markdown = FeedbackQueueFormatter.toMarkdown(sessionWithTargetEvidenceAndSources(), DetailMode.COMPACT)

        assertFalse(markdown.contains(" HIGH "))
        assertFalse(markdown.contains(" MEDIUM "))
        assertFalse(markdown.contains(" LOW "))
    }

    @Test
    fun preciseMarkdownPreservesLikelySourceWireFormat() {
        val markdown = FeedbackQueueFormatter.toMarkdown(sessionWithTargetEvidenceAndSources(), DetailMode.PRECISE)

        assertTrue(markdown.contains("Likely Source:"))
        assertTrue(markdown.contains("matched:"))
        assertTrue(markdown.contains("reasons:"))
        assertFalse(markdown.contains("src?"))
        assertFalse(
            markdown.contains(
                "Rule: source hints are candidates; verify screenshot, target, and code before editing.",
            ),
        )
    }

    @Test
    fun compactMarkdownIncludesScreenshotAndOverlayWhenAvailable() {
        val session = sessionWithScreenshotAndOverlay()
        val markdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

        assertTrue(markdown.contains("screenshot:"))
        assertTrue(markdown.contains("Checkout"))
    }

    @Test
    fun compactMarkdownEmitsCandidatesUnknownWhenNoSourceCandidates() {
        val session = sessionWithNoSourceCandidates()
        val markdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

        val lines = markdown.lines()
        assertTrue(
            lines.any { it == "  unknown" },
            "Expected '  unknown' when item has no source candidates but got:\n$markdown",
        )
    }

    @Test
    fun compactMarkdownSplitsOverlappingItemsIntoExplicitGroups() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 5L,
            screens = listOf(SnapshotDto("screen-1", 2L, displayName = "Checkout")),
            items = listOf(
                AnnotationDto(
                    itemId = "a",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 100f, 100f)),
                    comment = "Resize",
                    sequenceNumber = 1,
                ),
                AnnotationDto(
                    itemId = "b",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = AnnotationTargetDto.Area(FixThisRect(99f, 99f, 200f, 200f)),
                    comment = "Recolor",
                    sequenceNumber = 2,
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

        assertTrue(markdown.contains("Overlap group"))
        assertTrue(markdown.contains("Resize"))
        assertTrue(markdown.contains("Recolor"))
        assertTrue(markdown.contains("targetRisk=overlap") || markdown.contains("resolve one marker at a time"))
    }

    @Test
    fun compactPromptIsShorterThanPreciseForRepresentativeSession() {
        val session = sessionWithTargetEvidenceAndSources()
        val precise = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)
        val compact = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
        assertTrue(
            compact.length < precise.length,
            "expected COMPACT (${compact.length}) shorter than PRECISE (${precise.length})",
        )
    }

    /**
     * Token-budget regression guard for the v2 compact handoff prompt.
     *
     * Baseline measured 2026-05-10 (post-agent_protocol-footer): 1660 chars for
     * expected-prompt-v2.txt (rendered from session-v2.json, 4-item fixture).
     * Budget = ceil(1660 × 1.2) rounded to nearest 100 = 2000 chars.
     *
     * If the v2 fixtures change and the baseline legitimately grows, update
     * both the measured comment and the budget constant below.
     */
    @Test
    fun compactPromptForSessionV2StaysUnderBudget() {
        val sessionFile = File("src/test/resources/parity/session-v2.json")
        org.junit.Assume.assumeTrue("session-v2.json fixture present", sessionFile.exists())
        val session = fixThisJson.decodeFromString(SessionDto.serializer(), sessionFile.readText())
        val rendered = CompactHandoffRenderer.render(session)
        val budget = 2000 // baseline measured 2026-05-10 (post-agent_protocol-footer): 1660 chars
        assertTrue(
            rendered.length <= budget,
            "v2 compact prompt (${rendered.length} chars) exceeded budget of $budget",
        )
    }

    private fun sessionWithScreenshotAndOverlay(): SessionDto = SessionDto(
        sessionId = "session-1",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 5L,
        screens = listOf(
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 1L,
                displayName = "Checkout",
                screenshot = SnapshotScreenshotDto(
                    desktopFullPath = "/repo/.fixthis/feedback-sessions/session-1/artifacts/screens/screen-1/screen-1-full.png",
                    width = 720,
                    height = 1600,
                ),
            ),
        ),
        items = listOf(
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 2L,
                updatedAtEpochMillis = 2L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "Make this bigger",
                sequenceNumber = 1,
            ),
        ),
    )

    private fun sessionWithNoSourceCandidates(): SessionDto {
        val base = sessionWithTargetEvidenceAndSources()
        val item = base.items.first()
        val itemNoSources = item.copy(sourceCandidates = emptyList())
        return base.copy(items = listOf(itemNoSources))
    }

    private fun markdownOutsideCodeFences(markdown: String): String = buildString {
        var inFence = false
        markdown.lineSequence().forEach { line ->
            if (line.startsWith("```")) {
                inFence = !inFence
            } else if (!inFence) {
                appendLine(line)
            }
        }
    }

    private fun sessionWithTargetEvidenceAndSources(): SessionDto {
        val selectedNode = FixThisNode(
            uid = "compose:0:merged:42",
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
            role = "Button",
            testTag = "comp:AppPrimaryButton:primary",
        )
        return SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
                    selectedNode = selectedNode,
                    targetEvidence = TargetEvidence(
                        identityHint = IdentityHint(
                            composableNameHint = "AppPrimaryButton",
                            variantHint = "primary",
                            stableLabel = "Button Pay now",
                            source = IdentityHintSource.TEST_TAG_CONVENTION,
                            confidence = IdentityHintConfidence.HIGH,
                        ),
                        occurrence = Occurrence(
                            signature = OccurrenceSignature(
                                type = OccurrenceSignatureType.IDENTITY_HINT,
                                value = "AppPrimaryButton:primary",
                            ),
                            count = 2,
                            selectedOrdinal = 1,
                        ),
                    ),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                            line = 42,
                            score = 0.95,
                            matchedTerms = listOf("AppPrimaryButton"),
                            matchReasons = listOf("selected testTag convention composable"),
                            confidence = SelectionConfidence.HIGH,
                        ),
                        SourceCandidate(
                            file = "sample/src/main/java/io/beyondwin/fixthis/sample/screens/CheckoutScreen.kt",
                            line = 88,
                            score = 0.75,
                            matchedTerms = listOf("Pay now"),
                            matchReasons = listOf("selected text"),
                            confidence = SelectionConfidence.MEDIUM,
                        ),
                        SourceCandidate(
                            file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/PaymentSummary.kt",
                            line = 12,
                            score = 0.6,
                            matchedTerms = listOf("Button"),
                            matchReasons = listOf("selected role"),
                            confidence = SelectionConfidence.LOW,
                        ),
                        SourceCandidate(
                            file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/PaymentFooter.kt",
                            line = 24,
                            score = 0.4,
                            matchedTerms = listOf("primary"),
                            matchReasons = listOf("nearby source"),
                            confidence = SelectionConfidence.LOW,
                        ),
                    ),
                    comment = "Increase button contrast",
                    sequenceNumber = 1,
                ),
            ),
        )
    }

    // ---- Task 3 (C6): stale marker rendering ----

    private fun sessionWithStaleCandidate(stale: Boolean?, staleReason: String?): SessionDto {
        val selectedNode = FixThisNode(
            uid = "node-stale",
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 10f, 10f),
            text = listOf("Pay now"),
        )
        return SessionDto(
            sessionId = "session-stale",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-stale",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
                    selectedNode = selectedNode,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "HomeScreen.kt",
                            line = 10,
                            score = 0.9,
                            matchedTerms = emptyList(),
                            matchReasons = emptyList(),
                            confidence = SelectionConfidence.HIGH,
                            stale = stale,
                            staleReason = staleReason,
                        ),
                    ),
                    comment = "fix",
                    sequenceNumber = 1,
                ),
            ),
        )
    }

    @Test
    fun `render emits stale marker on rank 1 when candidate is stale`() {
        val markdown = FeedbackQueueFormatter.toMarkdown(
            sessionWithStaleCandidate(stale = true, staleReason = "excerpt mismatch"),
        )
        assertTrue(
            markdown.contains("⚠ stale: excerpt mismatch"),
            "Expected stale marker '⚠ stale: excerpt mismatch' in:\n$markdown",
        )
    }

    @Test
    fun `render omits stale marker when candidate is fresh`() {
        val markdown = FeedbackQueueFormatter.toMarkdown(
            sessionWithStaleCandidate(stale = false, staleReason = null),
        )
        assertFalse(
            markdown.contains("⚠ stale"),
            "Expected no stale marker when stale=false, but got:\n$markdown",
        )
    }

    @Test
    fun `render omits stale marker when candidate stale is null`() {
        val markdown = FeedbackQueueFormatter.toMarkdown(
            sessionWithStaleCandidate(stale = null, staleReason = null),
        )
        assertFalse(
            markdown.contains("⚠ stale"),
            "Expected no stale marker when stale=null, but got:\n$markdown",
        )
    }
}
