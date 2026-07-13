package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationSeverityDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePreset
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FeedbackTargetType {
    @SerialName("area")
    AREA,

    @SerialName("node")
    NODE,
}

@Serializable
data class AddAnnotationRequest(
    val sessionId: String? = null,
    val screenId: String,
    val comment: String,
    val targetType: FeedbackTargetType = FeedbackTargetType.AREA,
    val bounds: FixThisRect,
    val nodeUid: String? = null,
)

@Serializable
data class UpdateAnnotationRequest(
    val sessionId: String? = null,
    val label: String? = null,
    val severity: AnnotationSeverityDto? = null,
    val comment: String? = null,
    val status: AnnotationStatusDto? = null,
)

@Serializable
data class RuntimeEvidenceRequest(
    val sessionId: String? = null,
    val type: RuntimeEvidenceType,
    val durationMillis: Long? = null,
    val filter: String? = null,
    val summary: String? = null,
    val artifactPath: String? = null,
)

@Serializable
data class CollectRuntimeEvidenceRequest(
    val sessionId: String? = null,
    val preset: RuntimeEvidencePreset = RuntimeEvidencePreset.BASELINE,
)

@Serializable
data class UpdateRuntimeEvidencePolicyRequest(
    val policy: RuntimeEvidencePolicy,
)

@Serializable
data class HandoffPreviewRequest(
    val itemIds: List<String> = emptyList(),
)

@Serializable
data class MarkHandedOffRequest(
    val itemIds: List<String> = emptyList(),
)
