package io.github.beyondwin.fixthis.cli.runtime

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
    val baselineBytes: Int = 2 * 1024 * 1024,
    val logcatBytes: Int = 512 * 1024,
    val summaryBytes: Int = 128 * 1024,
)

data class CliRuntimeEvidenceCapabilities(
    val baselineAvailable: Boolean,
    val supportedCollectors: Set<CliRuntimeEvidenceKind>,
    val traceAvailable: Boolean = false,
    val limits: CliRuntimeEvidenceLimits = CliRuntimeEvidenceLimits(),
)
