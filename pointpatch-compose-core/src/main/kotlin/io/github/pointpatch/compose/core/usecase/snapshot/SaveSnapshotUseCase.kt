package io.github.pointpatch.compose.core.usecase.snapshot

import io.github.pointpatch.compose.core.domain.snapshot.Snapshot
import io.github.pointpatch.compose.core.domain.snapshot.SnapshotRepository

class SaveSnapshotUseCase(
    private val snapshots: SnapshotRepository,
) {
    suspend operator fun invoke(snapshot: Snapshot): Snapshot =
        snapshots.save(snapshot)
}
