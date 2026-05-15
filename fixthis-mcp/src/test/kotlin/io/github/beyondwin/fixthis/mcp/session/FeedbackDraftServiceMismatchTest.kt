package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeedbackDraftServiceMismatchTest {
    @Test
    fun savePreviewFeedbackItemsBlocksWhenFingerprintDiffers() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1"),
            prefix = "fixthis-draft-mismatch-block-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        val error = assertFailsWith<ScreenFingerprintMismatch> {
            fixture.draftService.savePreviewFeedbackItems(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(validItem()),
                frozenFingerprint = "frozen-abc",
                currentFingerprint = "current-xyz",
            )
        }

        assertEquals("frozen-abc", error.frozenFingerprint)
        assertEquals("current-xyz", error.currentFingerprint)
    }

    @Test
    fun savePreviewFeedbackItemsSucceedsWhenFingerprintMatches() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1"),
            prefix = "fixthis-draft-mismatch-match-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        val updated = fixture.draftService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(validItem()),
            frozenFingerprint = "same-fp",
            currentFingerprint = "same-fp",
        )

        assertEquals(1, updated.items.size)
    }

    @Test
    fun savePreviewFeedbackItemsForceOverrideBypassesMismatch() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1"),
            prefix = "fixthis-draft-mismatch-force-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        val updated = fixture.draftService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(validItem()),
            frozenFingerprint = "frozen-abc",
            currentFingerprint = "current-xyz",
            forceMismatchOverride = true,
        )

        assertEquals(1, updated.items.size)
    }

    @Test
    fun savePreviewFeedbackItemsForceOverridePersistsEventMetadata() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1", "item-2"),
            prefix = "fixthis-draft-mismatch-force-event-",
            withEventLog = true,
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        fixture.draftService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(validItem()),
            frozenFingerprint = "frozen-abc",
            currentFingerprint = "current-xyz",
            forceMismatchOverride = true,
        )

        val event = fixture.addScreenWithItemsEvents(session.sessionId).single()
        assertEquals(true, event.payload["forceMismatchOverride"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun savePreviewFeedbackItemsSkipsMismatchCheckWhenEitherFingerprintIsNull() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf(
                "session-1",
                "preview-1",
                "screen-1",
                "item-1",
                "session-2",
                "preview-2",
                "screen-2",
                "item-2",
            ),
            prefix = "fixthis-draft-mismatch-null-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        // Null current fingerprint, non-null frozen: should not throw
        val updated = fixture.draftService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(validItem()),
            frozenFingerprint = "frozen-abc",
            currentFingerprint = null,
        )
        assertEquals(1, updated.items.size)

        // Null frozen fingerprint, non-null current: should not throw
        val session2 = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview2 = fixture.previewCaptureService.capturePreview(session2)
        val updated2 = fixture.draftService.savePreviewFeedbackItems(
            sessionId = session2.sessionId,
            previewId = preview2.previewId,
            items = listOf(validItem()),
            frozenFingerprint = null,
            currentFingerprint = "current-xyz",
        )
        assertEquals(1, updated2.items.size)
    }

    @Test
    fun savePreviewFeedbackItemsPersistsReasonWhenFingerprintsAreUnavailable() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1", "item-2"),
            prefix = "fixthis-draft-mismatch-null-event-",
            withEventLog = true,
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        fixture.draftService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(validItem()),
            frozenFingerprint = null,
            currentFingerprint = null,
        )

        val event = fixture.addScreenWithItemsEvents(session.sessionId).single()
        assertEquals(
            "frozen_and_current_fingerprint_unavailable",
            event.payload["fingerprintUnavailableReason"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun forceSavedMismatchAddsReliabilityWarningToSavedItems() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1"),
            prefix = "fixthis-draft-mismatch-reliability-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)
        val reservation = fixture.draftService.preparePreviewFeedbackSave(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(validItem()),
            allowBlankComments = true,
        )

        val result = fixture.draftService.commitPreviewFeedbackSaveWithMetadata(
            reservation,
            PreviewSaveFingerprintCheck(
                frozenFingerprint = "frozen",
                currentFingerprint = "current",
                forceMismatchOverride = true,
                frozenFingerprintSource = "previewCache",
            ),
        )

        val saved = result.session.items.single()
        assertTrue(
            saved.targetReliability?.warnings.orEmpty()
                .contains(TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED),
        )
    }

    private fun validItem(): AnnotationDraftDto = AnnotationDraftDto(
        targetType = FeedbackTargetType.AREA,
        bounds = FixThisRect(112f, 426f, 351f, 588f),
        comment = "Mismatch test annotation",
    )

    private fun draftFixture(ids: Array<String>, prefix: String, withEventLog: Boolean = false): DraftFixture {
        val root = tempDir(prefix)
        val eventRoot = File(root, "events")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds(*ids),
            eventLogWriterProvider = if (withEventLog) {
                { sessionId -> EventLogWriter(File(eventRoot, "$sessionId/events")) }
            } else {
                null
            },
        )
        val previewCache = PreviewSnapshotCache(3)
        val targetEvidenceService = TargetEvidenceService(
            bridge = bridge,
            sourceIndexRegistry = SourceIndexRegistry(),
        )
        val previewCaptureService = PreviewCaptureService(
            bridge = bridge,
            store = store,
            previewCache = previewCache,
            targetEvidenceService = targetEvidenceService,
        )
        val draftService = FeedbackDraftService(
            store = store,
            previewCache = previewCache,
            targetEvidenceService = targetEvidenceService,
            screenshotArtifactPromoter = ScreenshotArtifactPromoter(),
        )
        return DraftFixture(root, eventRoot, bridge, store, previewCaptureService, draftService)
    }

    private data class DraftFixture(
        val root: File,
        val eventRoot: File,
        val bridge: FakeFixThisBridge,
        val store: FeedbackSessionStore,
        val previewCaptureService: PreviewCaptureService,
        val draftService: FeedbackDraftService,
    ) {
        fun addScreenWithItemsEvents(sessionId: String) = EventLogReader(File(eventRoot, "$sessionId/events"))
            .readAll()
            .filter { it.type == "addScreenWithItems" }
    }

    private fun tempDir(prefix: String): File = kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }

    private fun sequenceClock(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: values.last() }
    }

    private fun sequenceIds(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: error("No more ids configured") }
    }
}
