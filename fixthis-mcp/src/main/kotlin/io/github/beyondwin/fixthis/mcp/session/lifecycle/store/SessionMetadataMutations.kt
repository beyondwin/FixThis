package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionMutation
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.SessionReducer
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy

internal object SessionMetadataMutations {
    fun markReadyForAgent(session: SessionDto, now: Long): SessionDto = SessionReducer.reduce(session, SessionMutation.MarkReadyForAgent(now))

    fun updateRuntimeEvidencePolicy(
        session: SessionDto,
        policy: RuntimeEvidencePolicy,
        now: Long,
    ): SessionDto = session.copy(
        runtimeEvidencePolicy = policy,
        updatedAtEpochMillis = now,
    )
}
