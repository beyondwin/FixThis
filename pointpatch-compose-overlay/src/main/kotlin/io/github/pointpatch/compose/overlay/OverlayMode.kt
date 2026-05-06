package io.github.pointpatch.compose.overlay

import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.ScopeCandidate
import io.github.pointpatch.compose.core.model.ScreenshotInfo
import io.github.pointpatch.compose.core.model.SelectionInfo

sealed interface OverlayMode {
    data object Idle : OverlayMode
    data object MenuOpen : OverlayMode
    data class Select(val requestId: String?) : OverlayMode
    data class Loading(val reason: LoadingReason) : OverlayMode
    data class ReviewingSelection(val draft: PointPatchDraft) : OverlayMode
    data class Commenting(val draft: PointPatchDraft) : OverlayMode
    data class Exported(val annotationId: String) : OverlayMode
    data class Error(val cause: OverlayError, val recoverable: Boolean) : OverlayMode

    enum class LoadingReason {
        SCREENSHOT_CAPTURING,
        INSPECTOR_QUERYING,
        BRIDGE_CONNECTING,
    }

    sealed interface OverlayError {
        data object PermissionDenied : OverlayError
        data class ScreenshotFailed(val reason: String) : OverlayError
        data class BridgeUnreachable(val reason: String) : OverlayError
        data class Timeout(val operation: String, val timeoutMillis: Long) : OverlayError
    }
}

data class PointPatchDraft(
    val selectedNode: PointPatchNode? = null,
    val selection: SelectionInfo? = null,
    val scopeCandidates: List<ScopeCandidate> = emptyList(),
    val selectedScopeNodeUid: String? = selectedNode?.uid,
    val screenshot: ScreenshotInfo? = null,
    val userComment: String = "",
    val isAiAgentWaiting: Boolean = false,
) {
    val selectedBounds: PointPatchRect?
        get() = selectedNode?.boundsInWindow ?: selection?.areaBoundsInWindow

    val selectedSummary: String
        get() {
            val selected = selectedNode
            val title = when {
                selected == null -> selection?.kind?.name?.lowercase()?.replace('_', ' ') ?: "No selection"
                selected.text.isNotEmpty() -> selected.text.joinToString(separator = " ")
                selected.contentDescription.isNotEmpty() -> selected.contentDescription.joinToString(separator = " ")
                selected.testTag != null -> selected.testTag
                selected.role != null -> selected.role
                else -> selected.uid
            }
            val role = selected?.role?.let { " - $it" }.orEmpty()
            val scopes = when (scopeCandidates.size) {
                0 -> "No scopes"
                1 -> "1 scope"
                else -> "${scopeCandidates.size} scopes"
            }
            return "$title$role ($scopes)"
        }
}
