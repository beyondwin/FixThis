package io.github.beyondwin.fixthis.mcp.session.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeEvidenceAttachment(
    val evidenceId: String,
    val type: RuntimeEvidenceType,
    val capturedAtEpochMillis: Long,
    val deviceSerial: String? = null,
    val packageName: String,
    val timeRangeEpochMillis: EvidenceTimeRange? = null,
    val summary: String,
    val artifactPath: String? = null,
    val captureCommand: String? = null,
    val warnings: List<RuntimeEvidenceWarning> = emptyList(),
)

@Serializable
enum class RuntimeEvidenceType {
    @SerialName("logcat_window")
    LOGCAT_WINDOW,

    @SerialName("frame_summary")
    FRAME_SUMMARY,

    @SerialName("memory_summary")
    MEMORY_SUMMARY,

    @SerialName("trace_artifact")
    TRACE_ARTIFACT,
}

@Serializable
data class EvidenceTimeRange(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

@Serializable
enum class RuntimeEvidenceWarning {
    @SerialName("capture_deferred")
    CAPTURE_DEFERRED,

    @SerialName("sensitive_logs_possible")
    SENSITIVE_LOGS_POSSIBLE,

    @SerialName("artifact_missing")
    ARTIFACT_MISSING,
}
