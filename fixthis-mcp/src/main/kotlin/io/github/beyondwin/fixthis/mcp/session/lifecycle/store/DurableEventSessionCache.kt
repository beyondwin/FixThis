package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto

/** In-memory session state plus overlays for durable events whose snapshot save lagged. */
internal class DurableEventSessionCache {
    private val sessions = linkedMapOf<String, SessionDto>()
    private val snapshotLagging = mutableSetOf<String>()

    fun preferred(sessionId: String, persisted: () -> SessionDto?): SessionDto? = if (sessionId in snapshotLagging) sessions[sessionId] else persisted() ?: sessions[sessionId]

    fun put(session: SessionDto) {
        sessions[session.sessionId] = session
    }

    fun commit(sessionId: String, session: SessionDto) {
        sessions[sessionId] = session
        snapshotLagging -= sessionId
    }

    fun commitAfterDurableEvent(
        sessionId: String,
        session: SessionDto,
        save: (SessionDto) -> Unit,
    ): SessionDto {
        try {
            save(session)
            snapshotLagging -= sessionId
        } catch (_: FeedbackSessionException) {
            snapshotLagging += sessionId
        }
        sessions[sessionId] = session
        return session
    }

    fun all(): List<SessionDto> = sessions.values.toList()

    fun durableEventSessions(): List<SessionDto> = snapshotLagging.mapNotNull(sessions::get)

    fun ids(): List<String> = sessions.keys.toList()

    fun find(sessionId: String): SessionDto? = sessions[sessionId]
}
