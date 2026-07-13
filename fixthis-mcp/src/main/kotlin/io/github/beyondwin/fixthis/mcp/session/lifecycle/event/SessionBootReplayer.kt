package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.SessionStateStore
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.SkippedFeedbackSession

/**
 * Owns the boot-time state reconstruction extracted from
 * [io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStoreDelegate]
 * (target 1-E). Performs the two-part boot sequence the delegate's `init` block used to do
 * inline and tracks sessions that were skipped during event-log replay.
 *
 * Lock-free, like the delegate's `init`: [replayAll] is invoked at delegate construction
 * time before the delegate's lock is meaningfully held. The replayer adds no locking of its
 * own; the observable concurrency semantics remain entirely the delegate's.
 *
 * [SessionStateStore] is a live, mutable object: its contents keep changing after boot, and
 * [skippedList] must filter against the CURRENT store state. The replayer therefore retains
 * the store reference passed to [replayAll] so later [skippedList] calls see the latest state.
 *
 * Note: this collaborator does NOT own `currentSessionId`. That remains a delegate field (a
 * session-lifecycle concern). [replayAll] returns a [ReplayResult] carrying the derived id and
 * the delegate sets its own field from it.
 *
 * @param hasEventLog mirrors the delegate's `eventLogReaderProvider != null` gate. It selects
 *  which of the two behaviorally-distinct `currentSessionId` derivation paths runs (see
 *  [replayAll]); the two paths are intentionally NOT unified because they can differ on
 *  updatedAt ties.
 */
internal class SessionBootReplayer(
    private val replayEngine: SessionReplayEngine,
    private val persistence: FeedbackSessionPersistence?,
    private val hasEventLog: Boolean = true,
) {
    private val replaySkippedSessions = mutableMapOf<String, SkippedFeedbackSession>()
    private val preloadSkippedSessions = mutableListOf<SkippedFeedbackSession>()
    private var stateStore: SessionStateStore? = null

    /**
     * Carries what the delegate needs to finish boot. [currentSessionId] is the session the
     * delegate should select as current after reconstruction (or null when none qualifies).
     */
    data class ReplayResult(
        val currentSessionId: String?,
        val runtimeEvidenceReferencesComplete: Boolean,
    )

    /**
     * Performs the two-part boot reconstruction that previously lived in the delegate's `init`.
     *
     * Part (A) — persistence preload (always runs when [persistence] is wired): loads every
     * persisted session in ascending `updatedAtEpochMillis` order into [stateStore], assigning
     * the running current-session pointer to the LAST non-closed session encountered. Because
     * the iteration is ascending, the final assignment is the highest-`updatedAt` non-closed
     * session (ties resolve to the last in iteration order).
     *
     * Part (B) — event boot-replay (only when [hasEventLog] is true): replays events for every
     * known session id, then RE-DERIVES the current-session pointer as
     * `store.all().filter { !CLOSED }.maxByOrNull { updatedAtEpochMillis }?.sessionId`.
     *
     * The two paths are preserved exactly (not unified) because `maxByOrNull` (path B) and
     * the ascending iterate-and-assign (path A) can resolve updatedAt ties differently.
     *
     * Retains [stateStore] for later [skippedList] filtering.
     */
    fun replayAll(stateStore: SessionStateStore, journal: SessionEventJournal): ReplayResult {
        this.stateStore = stateStore
        replaySkippedSessions.clear()
        preloadSkippedSessions.clear()
        journal.beginReplayPass()

        val preload = PersistedSessionPreloader(persistence).preload(stateStore)
        preloadSkippedSessions += preload.loadFailures
        var currentSessionId = preload.currentSessionId

        if (hasEventLog) {
            for (sid in stateStore.ids()) {
                replaySessionEvents(stateStore, journal, sid)
            }
            currentSessionId = stateStore.all()
                .filter { it.status != SessionStatusDto.CLOSED }
                .maxByOrNull { it.updatedAtEpochMillis }
                ?.sessionId
        }

        return ReplayResult(
            currentSessionId,
            preload.runtimeEvidenceReferencesComplete && replaySkippedSessions.isEmpty(),
        )
    }

    /**
     * Replays all events for [sessionId] from the event log.
     *
     * Simplification (A.4): the [SessionReplayEngine] resets items, screens, and handoffBatches
     * to empty before applying events, so the snapshot and event log don't double-count.
     * Session identity fields (id, packageName, projectRoot, createdAt, status) are preserved
     * from the persistence snapshot if available.
     *
     * Lock-free: only called from [replayAll] at construction time.
     */
    private fun replaySessionEvents(stateStore: SessionStateStore, journal: SessionEventJournal, sessionId: String) {
        val reader = journal.reader(sessionId)
        val shell = stateStore.find(sessionId)
        if (reader != null && shell != null) {
            val replayed = replayEngine.replay(
                sessionId = sessionId,
                shell = shell,
                reader = reader,
                recordSkipped = ::recordReplaySkippedSession,
            )
            stateStore.put(replayed)
        }
    }

    private fun recordReplaySkippedSession(sessionId: String, path: String, message: String) {
        replaySkippedSessions[sessionId] = SkippedFeedbackSession(path = path, message = message)
    }

    /**
     * Filters the recorded skipped sessions against the CURRENT [stateStore] state, matching the
     * delegate's former `replaySkippedSessionList`: keep only entries whose session still exists
     * AND (packageName == null || matches) AND (includeClosed || not CLOSED).
     */
    fun skippedList(
        packageName: String?,
        includeClosed: Boolean,
    ): List<SkippedFeedbackSession> {
        val store = stateStore ?: return emptyList()
        val replaySkipped = replaySkippedSessions
            .filter { (sessionId, _) ->
                val session = store.find(sessionId)
                session != null &&
                    (packageName == null || session.packageName == packageName) &&
                    (includeClosed || session.status != SessionStatusDto.CLOSED)
            }
            .values
        return preloadSkippedSessions + replaySkipped
    }
}
