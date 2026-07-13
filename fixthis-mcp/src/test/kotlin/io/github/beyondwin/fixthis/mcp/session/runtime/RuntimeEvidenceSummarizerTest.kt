package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceKind
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceResult
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEvidenceSummarizerTest {
    private val summarizer = RuntimeEvidenceSummarizer(RuntimeEvidenceRedactor())

    @Test
    fun logcatSummaryReportsExceptionClassTagCountAndDistanceWithoutClaimingCausality() {
        val actual = summarizer.summarize(
            result(
                kind = CliRuntimeEvidenceKind.LOGCAT_WINDOW,
                output = """
                    07-14 10:00:00.100 E/Checkout: java.lang.IllegalStateException: boom
                    07-14 10:00:00.200 E/Checkout: java.lang.IllegalStateException: again
                """.trimIndent(),
            ),
            screenCapturedAtEpochMillis = 9_000,
        )

        assertTrue(actual.summary.contains("IllegalStateException"))
        assertTrue(actual.summary.contains("Checkout"))
        assertTrue(actual.summary.contains("2"))
        assertTrue(actual.summary.contains("1000 ms"))
        assertTrue(actual.summary.contains("causal link unproven"))
    }

    @Test
    fun emptyLogSignalUsesHonestSelectedWindowLanguage() {
        val actual = summarizer.summarize(result(CliRuntimeEvidenceKind.LOGCAT_WINDOW, "ordinary info"), 9_000)

        assertTrue(actual.summary.contains("no matching error pattern in the selected window"))
        assertFalse(actual.summary.contains("no error", ignoreCase = true))
    }

    @Test
    fun memoryAndFrameSummariesReportPssAndJankyFrames() {
        val memory = summarizer.summarize(
            result(CliRuntimeEvidenceKind.MEMORY_SUMMARY, "TOTAL PSS: 12,345"),
            9_000,
        )
        val frame = summarizer.summarize(
            result(CliRuntimeEvidenceKind.FRAME_SUMMARY, "Janky frames: 7 (10.0%)"),
            9_000,
        )

        assertTrue(memory.summary.contains("12345 KiB"))
        assertTrue(frame.summary.contains("7"))
        assertTrue(RuntimeEvidenceWarning.CUMULATIVE_NOT_WINDOWED in frame.warnings)
    }

    @Test
    fun summaryIsRedactedAgainAndRecordsWarning() {
        val actual = summarizer.summarize(
            result(CliRuntimeEvidenceKind.LOGCAT_WINDOW, "E/Auth: api_key=raw-secret java.lang.IllegalArgumentException"),
            9_000,
        )

        assertFalse(actual.summary.contains("raw-secret"))
        assertTrue(RuntimeEvidenceWarning.REDACTION_APPLIED in actual.warnings)
    }

    @Test
    fun cliWarningsAndFailureCodesMapToStableMcpContracts() {
        val actual = summarizer.summarize(
            result(
                kind = CliRuntimeEvidenceKind.LOGCAT_WINDOW,
                output = "partial",
                status = CliRuntimeEvidenceStatus.PARTIAL,
                warnings = setOf("output_truncated", "timestamp_filter_unsupported"),
                failureCode = "permission_denied",
            ),
            9_000,
        )

        assertEquals(RuntimeEvidenceStatus.PARTIAL, actual.status)
        assertEquals(RuntimeEvidenceFailureReason.PERMISSION_DENIED, actual.failureReason)
        assertTrue(RuntimeEvidenceWarning.OUTPUT_TRUNCATED in actual.warnings)
        assertTrue(RuntimeEvidenceWarning.TIMESTAMP_FILTER_UNSUPPORTED in actual.warnings)
    }

    private fun result(
        kind: CliRuntimeEvidenceKind,
        output: String,
        status: CliRuntimeEvidenceStatus = CliRuntimeEvidenceStatus.COMPLETE,
        warnings: Set<String> = emptySet(),
        failureCode: String? = null,
    ) = CliRuntimeEvidenceResult(
        kind = kind,
        status = status,
        startedAtEpochMillis = 10_000,
        completedAtEpochMillis = 10_100,
        command = listOf("shell", "bounded"),
        output = output,
        warnings = warnings,
        failureCode = failureCode,
    )
}
