package io.beyondwin.fixthis.compose.core.usecase.feedback

import io.beyondwin.fixthis.compose.core.domain.session.Session
import io.beyondwin.fixthis.compose.core.domain.session.SessionRepository

class SaveCapturedSnapshotUseCase(
    private val sessions: SessionRepository,
    private val clock: () -> Long,
) {
    suspend operator fun invoke(command: SaveCapturedSnapshotCommand): Session {
        val session = sessions.find(command.sessionId)
            ?: throw IllegalArgumentException("Unknown session: ${command.sessionId.value}")
        val updated = session.copy(
            snapshots = session.snapshots.filterNot { it.id == command.snapshot.id } + command.snapshot,
            updatedAtEpochMillis = clock(),
        )
        return sessions.save(updated)
    }
}
