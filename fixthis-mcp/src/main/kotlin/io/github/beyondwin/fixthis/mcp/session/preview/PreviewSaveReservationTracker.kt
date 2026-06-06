package io.github.beyondwin.fixthis.mcp.session.preview

import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionException

internal data class PreviewSaveSlot(
    val inFlightKey: String,
    val cachedPreview: PreviewRecord?,
)

internal class PreviewSaveReservationTracker(
    private val previewCache: PreviewSnapshotCache,
) {
    private val lock = Any()
    private val previewSavesInFlight = mutableSetOf<String>()

    fun reserve(
        sessionId: String,
        previewId: String,
        fallbackScreen: SnapshotDto?,
    ): PreviewSaveSlot {
        val inFlightKey = "$sessionId:$previewId"
        return synchronized(lock) {
            val record = previewCache.get(sessionId, previewId)
            if (record == null && fallbackScreen == null) {
                throw FeedbackSessionException("PREVIEW_NOT_FOUND: Unknown preview: $previewId")
            }
            if (!previewSavesInFlight.add(inFlightKey)) {
                throw FeedbackSessionException("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: $previewId")
            }
            PreviewSaveSlot(inFlightKey = inFlightKey, cachedPreview = record)
        }
    }

    fun release(inFlightKey: String) {
        synchronized(lock) {
            previewSavesInFlight.remove(inFlightKey)
        }
    }

    fun complete(sessionId: String, previewId: String, inFlightKey: String): PreviewRecord? = synchronized(lock) {
        previewSavesInFlight.remove(inFlightKey)
        previewCache.remove(sessionId, previewId)
    }
}
