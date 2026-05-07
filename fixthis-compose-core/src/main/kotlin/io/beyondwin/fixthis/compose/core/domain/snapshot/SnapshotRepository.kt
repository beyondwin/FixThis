package io.beyondwin.fixthis.compose.core.domain.snapshot

import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId

interface SnapshotRepository {
    suspend fun find(id: SnapshotId): Snapshot?
    suspend fun save(snapshot: Snapshot): Snapshot
}
