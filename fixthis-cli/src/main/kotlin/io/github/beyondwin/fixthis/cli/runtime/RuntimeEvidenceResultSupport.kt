package io.github.beyondwin.fixthis.cli.runtime

import io.github.beyondwin.fixthis.cli.AdbExecutionResult

internal object RuntimeEvidenceResultSupport {
    fun logcatSince(screenCapturedAtEpochMillis: Long): String? {
        if (screenCapturedAtEpochMillis <= 0) return null
        val timestampMillis = screenCapturedAtEpochMillis - LOGCAT_LOOKBACK_MILLIS
        val seconds = Math.floorDiv(timestampMillis, MILLIS_PER_SECOND)
        val millis = Math.floorMod(timestampMillis, MILLIS_PER_SECOND)
        return "$seconds.${millis.toString().padStart(MILLIS_FRACTION_WIDTH, '0')}"
    }

    fun aggregateStatus(
        failures: List<RuntimeEvidenceFailure>,
        warnings: Set<String>,
    ): CliRuntimeEvidenceStatus {
        val successful = failures.count { it == RuntimeEvidenceFailure.NONE }
        return when {
            successful > 0 && (successful < failures.size || warnings.isNotEmpty()) -> CliRuntimeEvidenceStatus.PARTIAL
            successful == failures.size -> CliRuntimeEvidenceStatus.COMPLETE
            failures.all { it == RuntimeEvidenceFailure.UNSUPPORTED } -> CliRuntimeEvidenceStatus.UNSUPPORTED
            else -> CliRuntimeEvidenceStatus.FAILED
        }
    }

    fun status(
        failure: RuntimeEvidenceFailure,
        warnings: Set<String>,
        hasUsableOutput: Boolean,
    ): CliRuntimeEvidenceStatus = when {
        failure == RuntimeEvidenceFailure.UNSUPPORTED -> CliRuntimeEvidenceStatus.UNSUPPORTED
        failure == RuntimeEvidenceFailure.NONE && warnings.isEmpty() -> CliRuntimeEvidenceStatus.COMPLETE
        failure == RuntimeEvidenceFailure.NONE ||
            (failure == RuntimeEvidenceFailure.TIMEOUT && hasUsableOutput) -> CliRuntimeEvidenceStatus.PARTIAL
        else -> CliRuntimeEvidenceStatus.FAILED
    }

    fun mergeBounded(parts: List<Pair<String, AdbExecutionResult>>, maxBytes: Int): String {
        val merged = parts.joinToString("\n") { (label, result) -> "[$label]\n${output(result)}" }
        return boundedUtf8(merged, maxBytes)
    }

    fun output(result: AdbExecutionResult): String = when {
        result.stdout.isBlank() -> result.stderr
        result.stderr.isBlank() -> result.stdout
        else -> "${result.stdout}\n${result.stderr}"
    }

    fun boundedUtf8(value: String, maxBytes: Int): String = when {
        maxBytes <= 0 -> ""
        value.toByteArray(Charsets.UTF_8).size <= maxBytes -> value
        else -> truncateUtf8(value, maxBytes)
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        val output = StringBuilder()
        var index = 0
        var used = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            output.append(text)
            used += bytes
            index += Character.charCount(codePoint)
        }
        return output.toString()
    }

    private const val LOGCAT_LOOKBACK_MILLIS = 2_000L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val MILLIS_FRACTION_WIDTH = 3
}
