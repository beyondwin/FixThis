package io.github.beyondwin.fixthis.cli.runtime

import io.github.beyondwin.fixthis.cli.AdbExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RuntimeEvidenceParsersTest {
    @Test
    fun pidRequiresExactlyOnePositivePid() {
        assertNull(RuntimeEvidenceParsers.pid(""))
        assertNull(RuntimeEvidenceParsers.pid("123 456"))
        assertNull(RuntimeEvidenceParsers.pid("0"))
        assertEquals(123, RuntimeEvidenceParsers.pid(" 123\n"))
    }

    @Test
    fun missingLastUpdateTimeStaysUnknown() {
        assertNull(RuntimeEvidenceParsers.lastUpdateEpochMillis("Packages:"))
        assertEquals(
            1_720_779_298_000L,
            RuntimeEvidenceParsers.lastUpdateEpochMillis(
                "lastUpdateTime=2024-07-12 10:14:58",
                ZoneId.of("UTC"),
            ),
        )
    }

    @Test
    fun parsesTotalPssAndJankyFrames() {
        assertEquals(42_816L, RuntimeEvidenceParsers.totalPssKilobytes("TOTAL PSS: 42816 TOTAL RSS: 92160"))
        assertEquals(7, RuntimeEvidenceParsers.jankyFrameCount("Janky frames: 7 (11.67%)"))
        assertNull(RuntimeEvidenceParsers.totalPssKilobytes("Permission Denial"))
        assertNull(RuntimeEvidenceParsers.jankyFrameCount("Graphics info unavailable"))
    }

    @Test
    fun countsExceptionSignalsWithoutCountingOrdinaryLines() {
        val output = """
            07-12 AndroidRuntime: FATAL EXCEPTION: main
            07-12 AndroidRuntime: java.lang.IllegalStateException: broken
            07-12 ActivityTaskManager: Force finishing activity
        """.trimIndent()

        assertEquals(2, RuntimeEvidenceParsers.exceptionSignalCount(output))
    }

    @Test
    fun classifiesPermissionUnsupportedTimeoutAndTruncation() {
        assertEquals(
            RuntimeEvidenceFailure.PERMISSION_DENIED,
            RuntimeEvidenceParsers.failure(
                result(exitCode = 1, stderr = "Permission Denial: not allowed"),
            ),
        )
        assertEquals(
            RuntimeEvidenceFailure.UNSUPPORTED,
            RuntimeEvidenceParsers.failure(
                result(exitCode = 1, stderr = "Unknown option -T"),
            ),
        )
        assertEquals(RuntimeEvidenceFailure.TIMEOUT, RuntimeEvidenceParsers.failure(result(timedOut = true, exitCode = null)))
        assertTrue(RuntimeEvidenceParsers.warnings(result(stdoutTruncated = true)).contains("output_truncated"))
    }

    @Test
    fun identifiesTheSpecificUnsupportedLogcatOption() {
        assertEquals(
            UnsupportedLogcatOption.PID,
            RuntimeEvidenceParsers.unsupportedLogcatOption(result(exitCode = 1, stderr = "Unknown option --pid")),
        )
        assertEquals(
            UnsupportedLogcatOption.TIMESTAMP,
            RuntimeEvidenceParsers.unsupportedLogcatOption(result(exitCode = 1, stderr = "Unknown option -T")),
        )
        assertEquals(
            UnsupportedLogcatOption.BOTH,
            RuntimeEvidenceParsers.unsupportedLogcatOption(result(exitCode = 1, stderr = "Unsupported options: --pid, -T")),
        )
    }

    private fun result(
        exitCode: Int? = 0,
        stdout: String = "",
        stderr: String = "",
        timedOut: Boolean = false,
        stdoutTruncated: Boolean = false,
    ) = AdbExecutionResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        timedOut = timedOut,
        stdoutTruncated = stdoutTruncated,
        stderrTruncated = false,
    )
}
