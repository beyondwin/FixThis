package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.SessionStateStore
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.SkippedFeedbackSession

internal data class PersistedSessionPreloadResult(
    val currentSessionId: String?,
    val runtimeEvidenceReferencesComplete: Boolean,
    val loadFailures: List<SkippedFeedbackSession>,
)

internal class PersistedSessionPreloader(
    private val persistence: FeedbackSessionPersistence?,
) {
    fun preload(stateStore: SessionStateStore): PersistedSessionPreloadResult {
        val persistence = persistence ?: return PersistedSessionPreloadResult(null, true, emptyList())
        val listed = persistence.list(includeClosed = true)
        var currentSessionId: String? = null
        val loadFailures = mutableListOf<SkippedFeedbackSession>()
        listed.sessions.sortedBy { it.updatedAtEpochMillis }.forEach { summary ->
            runCatching { persistence.load(summary.sessionId) }
                .onSuccess { session ->
                    stateStore.put(session)
                    if (session.status != SessionStatusDto.CLOSED) currentSessionId = session.sessionId
                }
                .onFailure { error ->
                    loadFailures += SkippedFeedbackSession(
                        path = persistence.artifactPaths().sessionFile(summary.sessionId).absolutePath,
                        message = "Could not preload feedback session ${summary.sessionId}: ${error.message}",
                    )
                }
        }
        return PersistedSessionPreloadResult(
            currentSessionId = currentSessionId,
            runtimeEvidenceReferencesComplete = listed.skippedSessions.isEmpty() && loadFailures.isEmpty(),
            loadFailures = loadFailures,
        )
    }
}
