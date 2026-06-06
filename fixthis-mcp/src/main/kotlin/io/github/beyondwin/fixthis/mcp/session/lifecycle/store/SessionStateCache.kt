@file:Suppress("MaxLineLength")

package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto

internal class SessionStateCache {
    private val sessions = linkedMapOf<String, SessionDto>()
    private var currentSessionId: String? = null

    fun put(session: SessionDto) {
        sessions[session.sessionId] = session
        if (session.status != SessionStatusDto.CLOSED) currentSessionId = session.sessionId
        if (session.status == SessionStatusDto.CLOSED && currentSessionId == session.sessionId) currentSessionId = null
    }

    fun putLoaded(session: SessionDto) {
        sessions[session.sessionId] = session
    }

    fun get(sessionId: String): SessionDto = sessions[sessionId] ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")

    fun find(sessionId: String): SessionDto? = sessions[sessionId]

    fun current(): SessionDto? = currentSessionId?.let(::get)

    fun all(): List<SessionDto> = sessions.values.toList()

    fun ids(): List<String> = sessions.keys.toList()

    fun setCurrent(sessionId: String?) {
        currentSessionId = sessionId
    }

    fun removeCurrentIf(sessionId: String) {
        if (currentSessionId == sessionId) currentSessionId = null
    }
}
