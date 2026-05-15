package io.github.beyondwin.fixthis.compose.core.domain.session

import io.github.beyondwin.fixthis.compose.core.domain.common.SessionId

interface SessionRepository {
    suspend fun find(id: SessionId): Session?
    suspend fun save(session: Session): Session
}
