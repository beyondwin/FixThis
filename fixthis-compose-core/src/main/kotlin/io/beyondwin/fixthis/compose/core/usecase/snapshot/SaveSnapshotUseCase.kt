package io.beyondwin.fixthis.compose.core.usecase.snapshot

import io.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot
import io.beyondwin.fixthis.compose.core.domain.snapshot.SnapshotRepository

class SaveSnapshotUseCase(
    private val snapshots: SnapshotRepository,
) {
    suspend operator fun invoke(snapshot: Snapshot): Snapshot =
        snapshots.save(snapshot)
}
