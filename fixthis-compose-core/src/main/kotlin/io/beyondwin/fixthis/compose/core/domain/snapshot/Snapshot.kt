package io.beyondwin.fixthis.compose.core.domain.snapshot

import io.beyondwin.fixthis.compose.core.domain.annotation.SnapshotScreenshot
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.ui.DomainRect
import io.beyondwin.fixthis.compose.core.domain.ui.SemanticsNodeSnapshot

data class Snapshot(
    val id: SnapshotId,
    val capturedAtEpochMillis: Long,
    val activityName: String? = null,
    val displayName: String,
    val screenshot: SnapshotScreenshot? = null,
    val roots: List<SnapshotRoot> = emptyList(),
    val sourceIndexAvailable: Boolean = false,
    val errors: List<DomainError> = emptyList(),
    val orientation: ScreenOrientation? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val densityDpi: Int? = null,
    val windowMode: WindowMode? = null,
    val systemUiVisible: Boolean? = null,
    val systemUiKind: String? = null,
    val fingerprint: String? = null,
)

data class SnapshotRoot(
    val rootIndex: Int,
    val boundsInWindow: DomainRect,
    val mergedNodes: List<SemanticsNodeSnapshot> = emptyList(),
    val unmergedNodes: List<SemanticsNodeSnapshot> = emptyList(),
)
