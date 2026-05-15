package io.github.beyondwin.fixthis.mcp.session.domain

import io.github.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.github.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot
import io.github.beyondwin.fixthis.compose.core.domain.snapshot.SnapshotRepository
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.toDomainSnapshot
import io.github.beyondwin.fixthis.mcp.session.toSnapshotDto

class McpSnapshotRepository(
    private val store: FeedbackSessionStore,
    private val sessionIdProvider: () -> String,
) : SnapshotRepository {
    override suspend fun find(id: SnapshotId): Snapshot? = store
        .getSession(sessionIdProvider())
        .screens
        .firstOrNull { it.screenId == id.value }
        ?.toDomainSnapshot()

    override suspend fun save(snapshot: Snapshot): Snapshot {
        store.addOrReplaceScreenForDomain(sessionIdProvider(), snapshot.toSnapshotDto())
        return snapshot
    }
}
