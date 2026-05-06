package io.github.pointpatch.compose.core.domain.snapshot

import io.github.pointpatch.compose.core.domain.common.SnapshotId

interface SnapshotRepository {
    suspend fun find(id: SnapshotId): Snapshot?
    suspend fun save(snapshot: Snapshot): Snapshot
}
