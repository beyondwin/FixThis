package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge

/**
 * Owns session lifecycle and persistence-side queries.
 *
 * Split out of `FeedbackSessionService` (CH-4): open/close/list semantics live here.
 * The façade and other collaborators (annotation repository, evidence coordinator)
 * read from this class rather than touching `FeedbackSessionStore` directly.
 */
class FeedbackSessionRegistry(
    private val bridge: FixThisBridge,
    private val store: FeedbackSessionStore,
    private val projectRoot: String,
    private val defaultPackageName: String? = null,
) {
    private val sessionLock = Any()

    fun openSession(
        packageNameOverride: String?,
        sessionId: String? = null,
        newSession: Boolean = false,
    ): SessionDto = synchronized(sessionLock) {
        sessionId?.takeIf { it.isNotBlank() }?.let { return@synchronized store.openExistingSession(it) }
        val packageName = bridge.resolvePackageName(
            packageNameOverride?.takeIf { it.isNotBlank() } ?: defaultPackageName,
        )
        if (!newSession) {
            store.currentSession()
                ?.takeIf {
                    it.packageName == packageName &&
                        it.projectRoot == projectRoot &&
                        it.status != SessionStatusDto.CLOSED
                }
                ?.let { return@synchronized it }
            store.listSessions(packageName = packageName)
                .sessions
                .firstOrNull { it.projectRoot == projectRoot }
                ?.let { return@synchronized store.openExistingSession(it.sessionId) }
        }
        store.openSession(packageName = packageName, projectRoot = projectRoot)
    }

    fun currentSession(): SessionDto = store.currentSession() ?: openSession(null)

    fun currentSessionOrNull(): SessionDto? = store.currentSession()

    fun requireCurrentSession(): SessionDto = currentSessionOrNull()
        ?: throw FeedbackSessionException("NO_ACTIVE_SESSION: Start a feedback session first")

    fun getSession(sessionId: String): SessionDto = store.getSession(sessionId)

    fun findSession(sessionId: String): SessionDto? = try {
        store.getSession(sessionId)
    } catch (_: FeedbackSessionException) {
        null
    }

    fun listSessions(
        packageNameOverride: String? = null,
        includeClosed: Boolean = false,
    ): FeedbackSessionList {
        val packageName = packageNameOverride
            ?.takeIf { it.isNotBlank() }
            ?.let { bridge.resolvePackageName(it) }
        return store.listSessions(packageName = packageName, includeClosed = includeClosed)
    }

    fun closeSession(sessionId: String): SessionDto = store.closeSession(sessionId)

    /**
     * Builds a transient SessionDto for the welcome console flow when no real session
     * has been opened yet. Used by connection-status / launch flows on the façade.
     */
    fun transientConsoleSession(): SessionDto = SessionDto(
        sessionId = "",
        packageName = bridge.resolvePackageName(defaultPackageName),
        projectRoot = projectRoot,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
    )
}
