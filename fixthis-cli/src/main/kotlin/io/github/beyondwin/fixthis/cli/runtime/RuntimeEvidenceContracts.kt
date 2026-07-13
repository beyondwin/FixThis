package io.github.beyondwin.fixthis.cli.runtime

internal const val RUNTIME_EVIDENCE_BASELINE_BYTES = 2_097_152
internal const val RUNTIME_EVIDENCE_LOGCAT_BYTES = 524_288
internal const val RUNTIME_EVIDENCE_SUMMARY_BYTES = 131_072

enum class CliRuntimeEvidenceKind { CONTEXT, LOGCAT_WINDOW, MEMORY_SUMMARY, FRAME_SUMMARY }

enum class CliRuntimeEvidenceStatus { COMPLETE, PARTIAL, FAILED, UNSUPPORTED }

data class CliRuntimeEvidenceContext(
    val deviceSerial: String,
    val packageName: String,
    val packageAvailable: Boolean,
    val pid: Int?,
    val installEpochMillis: Long?,
    val currentActivity: String?,
    val bridgeProtocolVersion: String?,
    val currentScreenFingerprint: String?,
)

data class CliRuntimeEvidenceResult(
    val kind: CliRuntimeEvidenceKind,
    val status: CliRuntimeEvidenceStatus,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val command: List<String>,
    val output: String,
    val warnings: Set<String> = emptySet(),
    val failureCode: String? = null,
)

data class CliRuntimeEvidenceLimits(
    val baselineBytes: Int = RUNTIME_EVIDENCE_BASELINE_BYTES,
    val logcatBytes: Int = RUNTIME_EVIDENCE_LOGCAT_BYTES,
    val summaryBytes: Int = RUNTIME_EVIDENCE_SUMMARY_BYTES,
)

data class CliRuntimeEvidenceCapabilities(
    val baselineAvailable: Boolean,
    val supportedCollectors: Set<CliRuntimeEvidenceKind>,
    val traceAvailable: Boolean = false,
    val limits: CliRuntimeEvidenceLimits = CliRuntimeEvidenceLimits(),
)
