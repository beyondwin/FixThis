package io.github.beyondwin.fixthis.mcp.session.preview

import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.mcp.console.FeedbackPreviewSnapshot

data class PreviewRecord(
    val sessionId: String,
    val projectRoot: String,
    val snapshot: FeedbackPreviewSnapshot,
    val sourceIndex: SourceIndex?,
)

class PreviewSnapshotCache(
    private val maxEntries: Int,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<PreviewCacheKey, PreviewRecord>(maxEntries, 0.75f, true)

    init {
        require(maxEntries > 0) { "Preview cache size must be positive" }
    }

    fun put(record: PreviewRecord): List<PreviewRecord> = synchronized(lock) {
        entries[PreviewCacheKey(record.sessionId, record.snapshot.previewId)] = record
        buildList {
            while (entries.size > maxEntries) {
                val eldestKey = entries.keys.first()
                entries.remove(eldestKey)?.let(::add)
            }
        }
    }

    fun get(sessionId: String, previewId: String): PreviewRecord? = synchronized(lock) {
        entries[PreviewCacheKey(sessionId, previewId)]
    }

    fun remove(sessionId: String, previewId: String): PreviewRecord? = synchronized(lock) {
        entries.remove(PreviewCacheKey(sessionId, previewId))
    }
}

private data class PreviewCacheKey(
    val sessionId: String,
    val previewId: String,
)
