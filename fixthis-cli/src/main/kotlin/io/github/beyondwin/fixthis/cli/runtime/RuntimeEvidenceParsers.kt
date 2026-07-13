package io.github.beyondwin.fixthis.cli.runtime

import io.github.beyondwin.fixthis.cli.AdbExecutionResult
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RuntimeEvidenceFailure(val code: String?) {
    NONE(null),
    TIMEOUT("timeout"),
    PERMISSION_DENIED("permission_denied"),
    UNSUPPORTED("unsupported"),
    PROCESS_NOT_RUNNING("process_not_running"),
    PACKAGE_NOT_AVAILABLE("package_not_available"),
    COMMAND_FAILED("adb_command_failed"),
}

enum class UnsupportedLogcatOption { TIMESTAMP, PID, BOTH, UNKNOWN }

object RuntimeEvidenceParsers {
    private val lastUpdatePattern = Regex("(?m)^\\s*lastUpdateTime=(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s*$")
    private val totalPssPattern = Regex("(?im)\\bTOTAL(?:\\s+PSS)?\\s*:\\s*([0-9,]+)")
    private val totalTablePssPattern = Regex("(?im)^\\s*TOTAL\\s+([0-9,]+)\\b")
    private val jankyFramesPattern = Regex("(?im)^\\s*Janky frames:\\s*([0-9,]+)\\b")

    fun pid(output: String): Int? {
        val values = output.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (values.size != 1) return null
        return values.single().toIntOrNull()?.takeIf { it > 0 }
    }

    fun lastUpdateEpochMillis(output: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        val value = lastUpdatePattern.find(output)?.groupValues?.get(1) ?: return null
        return runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    fun totalPssKilobytes(output: String): Long? = (totalPssPattern.find(output) ?: totalTablePssPattern.find(output))
        ?.groupValues
        ?.get(1)
        ?.replace(",", "")
        ?.toLongOrNull()

    fun jankyFrameCount(output: String): Int? = jankyFramesPattern.find(output)
        ?.groupValues
        ?.get(1)
        ?.replace(",", "")
        ?.toIntOrNull()

    fun exceptionSignalCount(output: String): Int = output.lineSequence().count { line ->
        line.contains("FATAL EXCEPTION", ignoreCase = true) ||
            Regex("(?:java|kotlin)\\.[A-Za-z0-9_.]+(?:Exception|Error)\\b").containsMatchIn(line)
    }

    fun failure(result: AdbExecutionResult): RuntimeEvidenceFailure {
        if (result.timedOut) return RuntimeEvidenceFailure.TIMEOUT
        val diagnostic = "${result.stderr}\n${result.stdout}"
        if (diagnostic.contains("permission denial", ignoreCase = true) ||
            diagnostic.contains("permission denied", ignoreCase = true) ||
            diagnostic.contains("not allowed", ignoreCase = true)
        ) {
            return RuntimeEvidenceFailure.PERMISSION_DENIED
        }
        if (diagnostic.contains("unknown option", ignoreCase = true) ||
            diagnostic.contains("unsupported", ignoreCase = true) ||
            diagnostic.contains("not supported", ignoreCase = true)
        ) {
            return RuntimeEvidenceFailure.UNSUPPORTED
        }
        return if (result.exitCode == 0) RuntimeEvidenceFailure.NONE else RuntimeEvidenceFailure.COMMAND_FAILED
    }

    fun unsupportedLogcatOption(result: AdbExecutionResult): UnsupportedLogcatOption {
        val diagnostic = "${result.stderr}\n${result.stdout}"
        val pid = diagnostic.contains("--pid", ignoreCase = true)
        val timestamp = Regex("(?<!\\S)-T(?!\\S)").containsMatchIn(diagnostic)
        return when {
            pid && timestamp -> UnsupportedLogcatOption.BOTH
            pid -> UnsupportedLogcatOption.PID
            timestamp -> UnsupportedLogcatOption.TIMESTAMP
            else -> UnsupportedLogcatOption.UNKNOWN
        }
    }

    fun warnings(result: AdbExecutionResult): Set<String> = buildSet {
        if (result.stdoutTruncated || result.stderrTruncated) add("output_truncated")
        if (result.timedOut) add("command_timeout")
    }
}
