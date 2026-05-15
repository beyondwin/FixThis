package io.github.beyondwin.fixthis.mcp.session.domain

import io.github.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.github.beyondwin.fixthis.compose.core.domain.session.Session
import io.github.beyondwin.fixthis.compose.core.domain.session.SessionRepository
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.toDomainSession
import io.github.beyondwin.fixthis.mcp.session.toSessionDto

class McpSessionRepository(
    private val store: FeedbackSessionStore,
) : SessionRepository {
    override suspend fun find(id: SessionId): Session? = try {
        store.getSession(id.value).toDomainSession()
    } catch (_: FeedbackSessionException) {
        null
    }

    override suspend fun save(session: Session): Session {
        store.replaceSessionForDomain(session.toSessionDto())
        return session
    }
}
