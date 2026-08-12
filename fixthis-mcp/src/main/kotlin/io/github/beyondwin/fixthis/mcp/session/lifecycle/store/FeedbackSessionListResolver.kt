package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto

internal object FeedbackSessionListResolver {
    fun resolve(
        persistence: FeedbackSessionPersistence?,
        store: SessionStateStore,
        packageName: String?,
        includeClosed: Boolean,
        replaySkipped: List<SkippedFeedbackSession>,
    ): FeedbackSessionList {
        val durable = store.durableEventSessions
        val durableIds = durable.mapTo(mutableSetOf()) { it.sessionId }
        val visibleDurable = durable.visibleSummaries(packageName, includeClosed)
        return persistence?.list(packageName, includeClosed)?.let { persisted ->
            persisted.copy(
                sessions = (persisted.sessions.filterNot { it.sessionId in durableIds } + visibleDurable)
                    .sortedByDescending { it.updatedAtEpochMillis },
                skippedSessions = persisted.skippedSessions + replaySkipped,
            )
        } ?: FeedbackSessionList(
            sessions = store.all().visibleSummaries(packageName, includeClosed),
            skippedSessions = replaySkipped,
        )
    }
}

private fun List<io.github.beyondwin.fixthis.mcp.session.dto.SessionDto>.visibleSummaries(
    packageName: String?,
    includeClosed: Boolean,
): List<FeedbackSessionSummary> = filter { packageName == null || it.packageName == packageName }
    .filter { includeClosed || it.status != SessionStatusDto.CLOSED }
    .map(FeedbackSessionSummary.Companion::from)
    .sortedByDescending { it.updatedAtEpochMillis }
