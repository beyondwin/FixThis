package io.github.pointpatch.compose.core.domain.session

import io.github.pointpatch.compose.core.domain.common.SessionId

interface SessionRepository {
    suspend fun find(id: SessionId): Session?
    suspend fun save(session: Session): Session
}
