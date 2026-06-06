package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewRecord
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewSnapshotCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewSnapshotCacheTest {
    @Test
    fun evictsOldestPreviewWhenCapacityIsExceeded() {
        val cache = PreviewSnapshotCache(maxEntries = 2)

        assertEquals(emptyList(), cache.put(previewRecord("preview-1")))
        assertEquals(emptyList(), cache.put(previewRecord("preview-2")))
        val evicted = cache.put(previewRecord("preview-3"))

        assertEquals(listOf("preview-1"), evicted.map { it.snapshot.previewId })
        assertNull(cache.get(sessionId = "session-1", previewId = "preview-1"))
        assertEquals("preview-2", cache.get("session-1", "preview-2")!!.snapshot.previewId)
        assertEquals("preview-3", cache.get("session-1", "preview-3")!!.snapshot.previewId)
    }

    @Test
    fun getMarksPreviewAsRecentlyUsed() {
        val cache = PreviewSnapshotCache(maxEntries = 2)
        cache.put(previewRecord("preview-1"))
        cache.put(previewRecord("preview-2"))

        cache.get(sessionId = "session-1", previewId = "preview-1")
        val evicted = cache.put(previewRecord("preview-3"))

        assertEquals(listOf("preview-2"), evicted.map { it.snapshot.previewId })
        assertEquals("preview-1", cache.get("session-1", "preview-1")!!.snapshot.previewId)
        assertNull(cache.get(sessionId = "session-1", previewId = "preview-2"))
    }

    @Test
    fun rejectsPreviewFromDifferentSession() {
        val cache = PreviewSnapshotCache(maxEntries = 2)
        cache.put(previewRecord("preview-1"))

        assertNull(cache.get(sessionId = "session-2", previewId = "preview-1"))
    }

    @Test
    fun wrongSessionGetDoesNotMarkPreviewAsRecentlyUsed() {
        val cache = PreviewSnapshotCache(maxEntries = 2)
        cache.put(previewRecord("preview-1"))
        cache.put(previewRecord("preview-2"))

        assertNull(cache.get(sessionId = "session-2", previewId = "preview-1"))
        val evicted = cache.put(previewRecord("preview-3"))

        assertEquals(listOf("preview-1"), evicted.map { it.snapshot.previewId })
        assertNull(cache.get(sessionId = "session-1", previewId = "preview-1"))
        assertEquals("preview-2", cache.get("session-1", "preview-2")!!.snapshot.previewId)
        assertEquals("preview-3", cache.get("session-1", "preview-3")!!.snapshot.previewId)
    }

    @Test
    fun removesPreviewOnlyForMatchingSession() {
        val cache = PreviewSnapshotCache(maxEntries = 2)
        cache.put(previewRecord("preview-1"))

        assertNull(cache.remove(sessionId = "session-2", previewId = "preview-1"))
        assertEquals("preview-1", cache.remove(sessionId = "session-1", previewId = "preview-1")!!.snapshot.previewId)
        assertNull(cache.get(sessionId = "session-1", previewId = "preview-1"))
    }
}

private fun previewRecord(previewId: String): PreviewRecord = PreviewRecord(
    sessionId = "session-1",
    projectRoot = "/repo",
    snapshot = FeedbackPreviewSnapshot(
        previewId = previewId,
        screen = SnapshotDto(
            screenId = "screen-$previewId",
            capturedAtEpochMillis = 10L,
            displayName = "Draft screen",
        ),
    ),
    sourceIndex = null,
)
