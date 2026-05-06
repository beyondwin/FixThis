package io.github.pointpatch.compose.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PointPatchAnnotation(
    val schemaVersion: String = "1.0",
    val id: String,
    val createdAtEpochMillis: Long,
    val platform: String = "android-compose",
    val app: AppInfo,
    val activity: ActivityInfo,
    val tap: TapPoint,
    val selection: SelectionInfo,
    val selectedNode: PointPatchNode? = null,
    val candidatesAtPoint: List<ScoredPointPatchNode> = emptyList(),
    val scopeCandidates: List<ScopeCandidate> = emptyList(),
    val nearbyNodes: List<PointPatchNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val searchHints: List<String> = emptyList(),
    val screenshot: ScreenshotInfo? = null,
    val userComment: String,
    val errors: List<PointPatchError> = emptyList()
)

@Serializable
data class AppInfo(val packageName: String, val versionName: String? = null, val versionCode: Long? = null, val debuggable: Boolean)

@Serializable
data class ActivityInfo(val className: String)

@Serializable
data class TapPoint(val xInWindow: Float, val yInWindow: Float)

@Serializable
data class PointPatchRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

@Serializable
enum class TreeKind { MERGED, UNMERGED }

@Serializable
enum class SelectionKind { SEMANTICS_NODE, VISUAL_AREA, TAP_POINT }

@Serializable
enum class SelectionConfidence { HIGH, MEDIUM, LOW, NONE }

@Serializable
enum class SelectionSource { TAP_SELECT, SCOPE_CHIP, AREA_SELECT, FALLBACK }

@Serializable
data class SelectionInfo(
    val kind: SelectionKind,
    val confidence: SelectionConfidence,
    val selectedUid: String? = null,
    val areaBoundsInWindow: PointPatchRect? = null,
    val source: SelectionSource
)

@Serializable
data class PointPatchNode(
    val uid: String,
    val composeNodeId: Int,
    val rootIndex: Int,
    val treeKind: TreeKind,
    val boundsInWindow: PointPatchRect,
    val text: List<String> = emptyList(),
    val editableText: String? = null,
    val contentDescription: List<String> = emptyList(),
    val role: String? = null,
    val testTag: String? = null,
    val stateDescription: String? = null,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val actions: List<String> = emptyList(),
    val isPassword: Boolean = false,
    val isSensitive: Boolean = false,
    val path: List<String> = emptyList(),
    val rawProperties: Map<String, String> = emptyMap()
) {
    fun hasMeaningfulSemantic(): Boolean =
        text.isNotEmpty() || editableText != null || contentDescription.isNotEmpty() ||
            role != null || testTag != null || actions.isNotEmpty() || stateDescription != null
}

@Serializable
data class ScoredPointPatchNode(val node: PointPatchNode, val score: Double, val breakdown: Map<String, Double>)

@Serializable
data class ScopeCandidate(val label: String, val nodeUid: String, val boundsInWindow: PointPatchRect, val score: Double)

@Serializable
data class SourceCandidate(
    val file: String,
    val line: Int? = null,
    val score: Double,
    val matchedTerms: List<String> = emptyList(),
    val matchReasons: List<String> = emptyList(),
    val confidence: SelectionConfidence
)

@Serializable
data class ScreenshotInfo(
    val fullPath: String? = null,
    val cropPath: String? = null,
    val desktopFullPath: String? = null,
    val desktopCropPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val captureFailedReason: String? = null
)

@Serializable
data class PointPatchError(val code: String, val message: String, val details: Map<String, String> = emptyMap())
