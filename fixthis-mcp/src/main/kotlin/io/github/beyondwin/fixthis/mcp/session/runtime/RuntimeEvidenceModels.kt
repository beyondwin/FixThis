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
    val captureId: String? = null,
    val status: RuntimeEvidenceStatus = RuntimeEvidenceStatus.COMPLETE,
    val trigger: RuntimeEvidenceTrigger = RuntimeEvidenceTrigger.MANUAL_ATTACHMENT,
    val screenCapturedAtEpochMillis: Long? = null,
    val captureStartedAtEpochMillis: Long? = null,
    val captureCompletedAtEpochMillis: Long? = null,
    val proximity: RuntimeEvidenceProximity? = null,
    val failureReason: RuntimeEvidenceFailureReason? = null,
)

@Serializable
enum class RuntimeEvidencePolicy {
    @SerialName("auto_on_handoff")
    AUTO_ON_HANDOFF,

    @SerialName("manual")
    MANUAL,

    @SerialName("off")
    OFF,
}

@Serializable
enum class RuntimeEvidenceStatus {
    @SerialName("complete")
    COMPLETE,

    @SerialName("partial")
    PARTIAL,

    @SerialName("failed")
    FAILED,

    @SerialName("unsupported")
    UNSUPPORTED,
}

@Serializable
enum class RuntimeEvidenceTrigger {
    @SerialName("handoff_auto")
    HANDOFF_AUTO,

    @SerialName("console_manual")
    CONSOLE_MANUAL,

    @SerialName("mcp_manual")
    MCP_MANUAL,

    @SerialName("manual_attachment")
    MANUAL_ATTACHMENT,
}

@Serializable
enum class RuntimeEvidenceProximity {
    @SerialName("near")
    NEAR,

    @SerialName("delayed")
    DELAYED,

    @SerialName("stale")
    STALE,
}

@Serializable
enum class RuntimeEvidenceFailureReason {
    @SerialName("device_unavailable")
    DEVICE_UNAVAILABLE,

    @SerialName("device_changed")
    DEVICE_CHANGED,

    @SerialName("package_unavailable")
    PACKAGE_UNAVAILABLE,

    @SerialName("process_not_running")
    PROCESS_NOT_RUNNING,

    @SerialName("collector_unsupported")
    COLLECTOR_UNSUPPORTED,

    @SerialName("permission_denied")
    PERMISSION_DENIED,

    @SerialName("capture_timeout")
    CAPTURE_TIMEOUT,

    @SerialName("context_changed")
    CONTEXT_CHANGED,

    @SerialName("artifact_write_failed")
    ARTIFACT_WRITE_FAILED,

    @SerialName("quota_exceeded")
    QUOTA_EXCEEDED,

    @SerialName("artifact_missing")
    ARTIFACT_MISSING,
}

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

    @SerialName("output_truncated")
    OUTPUT_TRUNCATED,

    @SerialName("redaction_applied")
    REDACTION_APPLIED,

    @SerialName("process_restarted")
    PROCESS_RESTARTED,

    @SerialName("context_changed")
    CONTEXT_CHANGED,

    @SerialName("stale_window")
    STALE_WINDOW,

    @SerialName("cumulative_not_windowed")
    CUMULATIVE_NOT_WINDOWED,

    @SerialName("timestamp_filter_unsupported")
    TIMESTAMP_FILTER_UNSUPPORTED,

    @SerialName("pid_filter_unsupported")
    PID_FILTER_UNSUPPORTED,
}
