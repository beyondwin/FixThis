package io.beyondwin.fixthis.compose.core.domain.snapshot

import io.beyondwin.fixthis.compose.core.domain.annotation.SnapshotScreenshot
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect

data class Snapshot(
    val id: SnapshotId,
    val capturedAtEpochMillis: Long,
    val activityName: String? = null,
    val displayName: String,
    val screenshot: SnapshotScreenshot? = null,
    val roots: List<SnapshotRoot> = emptyList(),
    val sourceIndexAvailable: Boolean = false,
    val errors: List<FixThisError> = emptyList(),
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
    val boundsInWindow: FixThisRect,
    val mergedNodes: List<FixThisNode> = emptyList(),
    val unmergedNodes: List<FixThisNode> = emptyList(),
)
