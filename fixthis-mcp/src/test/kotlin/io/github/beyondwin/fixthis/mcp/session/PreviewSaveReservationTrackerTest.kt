package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewRecord
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewSaveReservationTracker
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewSnapshotCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PreviewSaveReservationTrackerTest {
    @Test
    fun reserveRejectsMissingPreviewWhenFallbackIsAbsent() {
        val tracker = PreviewSaveReservationTracker(PreviewSnapshotCache(2))

        val error = assertFailsWith<FeedbackSessionException> {
            tracker.reserve(sessionId = "session-1", previewId = "preview-missing", fallbackScreen = null)
        }

        assertEquals("PREVIEW_NOT_FOUND: Unknown preview: preview-missing", error.message)
    }

    @Test
    fun reserveRejectsSecondSaveForSamePreviewUntilReleased() {
        val cache = PreviewSnapshotCache(2)
        cache.put(record("session-1", "preview-1"))
        val tracker = PreviewSaveReservationTracker(cache)

        val slot = tracker.reserve("session-1", "preview-1", fallbackScreen = null)
        val error = assertFailsWith<FeedbackSessionException> {
            tracker.reserve("session-1", "preview-1", fallbackScreen = null)
        }

        assertEquals("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: preview-1", error.message)
        tracker.release(slot.inFlightKey)
        tracker.reserve("session-1", "preview-1", fallbackScreen = null)
    }

    @Test
    fun completeRemovesCachedPreviewAndReleasesInflightKey() {
        val cache = PreviewSnapshotCache(2)
        cache.put(record("session-1", "preview-1"))
        val tracker = PreviewSaveReservationTracker(cache)
        val slot = tracker.reserve("session-1", "preview-1", fallbackScreen = null)

        val removed = tracker.complete(sessionId = "session-1", previewId = "preview-1", inFlightKey = slot.inFlightKey)

        assertEquals("preview-1", removed?.snapshot?.previewId)
        assertNull(cache.get("session-1", "preview-1"))
        tracker.reserve("session-1", "preview-1", fallbackScreen = fallbackScreen())
    }

    private fun record(sessionId: String, previewId: String): PreviewRecord = PreviewRecord(
        sessionId = sessionId,
        projectRoot = "/repo",
        snapshot = FeedbackPreviewSnapshot(previewId = previewId, screen = fallbackScreen()),
        sourceIndex = null,
    )

    private fun fallbackScreen(): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 100L,
        displayName = "MainActivity",
    )
}
