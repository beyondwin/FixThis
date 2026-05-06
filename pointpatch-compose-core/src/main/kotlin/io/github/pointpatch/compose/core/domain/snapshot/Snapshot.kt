package io.github.pointpatch.compose.core.domain.snapshot

import io.github.pointpatch.compose.core.domain.annotation.SnapshotScreenshot
import io.github.pointpatch.compose.core.domain.common.SnapshotId
import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect

data class Snapshot(
    val id: SnapshotId,
    val capturedAtEpochMillis: Long,
    val activityName: String? = null,
    val displayName: String,
    val screenshot: SnapshotScreenshot? = null,
    val roots: List<SnapshotRoot> = emptyList(),
    val sourceIndexAvailable: Boolean = false,
    val errors: List<PointPatchError> = emptyList(),
)

data class SnapshotRoot(
    val rootIndex: Int,
    val boundsInWindow: PointPatchRect,
    val mergedNodes: List<PointPatchNode> = emptyList(),
    val unmergedNodes: List<PointPatchNode> = emptyList(),
)
