package io.github.beyondwin.fixthis.cli.runtime

import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.AdbExecutionLimits
import io.github.beyondwin.fixthis.cli.AdbExecutionResult
import io.github.beyondwin.fixthis.cli.AdbFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

class AndroidRuntimeEvidenceCollectorTest {
    @Test
    fun contextParsesPackagePidAndInstallTimeWithContextLimits() {
        val adb = RecordingAdbFacade { arguments, _ ->
            when (arguments) {
                listOf("shell", "pm", "path", "io.example.app") -> result(stdout = "package:/data/app/base.apk\n")
                listOf("shell", "pidof", "io.example.app") -> result(stdout = "123\n")
                listOf("shell", "dumpsys", "package", "io.example.app") -> result(stdout = "lastUpdateTime=2024-07-12 10:14:58\n")
                else -> error("Unexpected command: $arguments")
            }
        }
        val collector = AndroidRuntimeEvidenceCollector(
            adb = adb,
            deviceSerial = "emulator-5554",
            clock = { 100L },
            zoneId = ZoneId.of("UTC"),
        )

        val context = collector.context("io.example.app")

        assertTrue(context.packageAvailable)
        assertEquals(123, context.pid)
        assertEquals(1_720_779_298_000L, context.installEpochMillis)
        assertTrue(adb.executions.all { it.second == AdbExecutionLimits(750, 128 * 1024, 128 * 1024) })
    }

    @Test
    fun logcatFallsBackWhenTimestampFilteringIsUnsupportedAndMergesBoundedSources() {
        val adb = RecordingAdbFacade { arguments, _ ->
            when {
                arguments == listOf("shell", "pm", "path", "io.example.app") -> result(stdout = "package:/base.apk")
                arguments == listOf("shell", "pidof", "io.example.app") -> result(stdout = "123")
                arguments.take(2) == listOf("shell", "dumpsys") && arguments.getOrNull(2) == "package" -> result(stdout = "")
                "-T" in arguments -> result(exitCode = 1, stderr = "Unknown option -T")
                arguments.take(1) == listOf("logcat") && "--pid" in arguments -> result(stdout = "app line\n")
                arguments.take(3) == listOf("logcat", "-b", "crash") -> result(stdout = "crash line\n")
                arguments.take(4) == listOf("shell", "dumpsys", "activity", "exit-info") -> result(stdout = "exit line\n")
                else -> error("Unexpected command: $arguments")
            }
        }
        val collector = AndroidRuntimeEvidenceCollector(
            adb = adb,
            deviceSerial = "emulator-5554",
            clock = sequenceOf(100L, 200L).iterator()::next,
            zoneId = ZoneId.of("UTC"),
        )

        val evidence = collector.collect(
            packageName = "io.example.app",
            kind = CliRuntimeEvidenceKind.LOGCAT_WINDOW,
            screenCapturedAtEpochMillis = Instant.parse("2024-07-12T10:15:00Z").toEpochMilli(),
        )

        assertEquals(CliRuntimeEvidenceStatus.PARTIAL, evidence.status)
        assertTrue("timestamp_filter_unsupported" in evidence.warnings)
        assertTrue(evidence.output.contains("app line"))
        assertTrue(evidence.output.contains("crash line"))
        assertTrue(evidence.output.contains("exit line"))
        assertTrue(adb.executions.any { (_, limits) -> limits == AdbExecutionLimits(1_500, 512 * 1024, 512 * 1024) })
    }

    @Test
    fun collectorMapsTimeoutAndTruncationToPartialNeutralResults() {
        val adb = RecordingAdbFacade { arguments, _ ->
            when {
                arguments == listOf("shell", "pm", "path", "io.example.app") -> result(stdout = "package:/base.apk")
                arguments == listOf("shell", "pidof", "io.example.app") -> result(stdout = "123")
                arguments.take(2) == listOf("shell", "dumpsys") && arguments.getOrNull(2) == "package" -> result(stdout = "")
                arguments.take(3) == listOf("shell", "dumpsys", "meminfo") -> result(
                    exitCode = null,
                    stdout = "TOTAL PSS: 42",
                    timedOut = true,
                    stdoutTruncated = true,
                )
                else -> error("Unexpected command: $arguments")
            }
        }
        val collector = AndroidRuntimeEvidenceCollector(adb, "emulator-5554", clock = sequenceOf(1L, 2L).iterator()::next)

        val evidence = collector.collect("io.example.app", CliRuntimeEvidenceKind.MEMORY_SUMMARY, 0L)

        assertEquals(CliRuntimeEvidenceStatus.PARTIAL, evidence.status)
        assertEquals("timeout", evidence.failureCode)
        assertTrue("output_truncated" in evidence.warnings)
        assertEquals(AdbExecutionLimits(1_250, 128 * 1024, 128 * 1024), adb.executions.last().second)
    }

    @Test
    fun pidFilterUnsupportedRetriesWithoutPidButKeepsTimestamp() {
        val adb = evidenceAdb(pid = "123") { arguments ->
            when {
                arguments.firstOrNull() == "logcat" && "--pid" in arguments -> result(exitCode = 1, stderr = "Unknown option --pid")
                arguments.firstOrNull() == "logcat" && "-b" !in arguments -> result(stdout = "bounded app line")
                arguments.take(3) == listOf("logcat", "-b", "crash") -> result(stdout = "crash line")
                arguments.take(4) == listOf("shell", "dumpsys", "activity", "exit-info") -> result(stdout = "exit line")
                else -> error("Unexpected evidence command: $arguments")
            }
        }

        val evidence = collector(adb).collect("io.example.app", CliRuntimeEvidenceKind.LOGCAT_WINDOW, 1_720_779_300_000L)
        val appCommands = adb.executions.map { it.first }.filter { it.firstOrNull() == "logcat" && "-b" !in it }

        assertEquals(2, appCommands.size)
        assertTrue("--pid" in appCommands.first())
        assertFalse("--pid" in appCommands.last())
        assertTrue("-T" in appCommands.last())
        assertTrue("pid_filter_unsupported" in evidence.warnings)
        assertFalse("timestamp_filter_unsupported" in evidence.warnings)
    }

    @Test
    fun combinedUnsupportedLogcatOptionsConvergeWithoutLooping() {
        var appAttempts = 0
        val adb = evidenceAdb(pid = "123") { arguments ->
            when {
                arguments.firstOrNull() == "logcat" && "-b" !in arguments -> {
                    appAttempts += 1
                    when (appAttempts) {
                        1 -> result(exitCode = 1, stderr = "Unknown option --pid")
                        2 -> result(exitCode = 1, stderr = "Unknown option -T")
                        else -> result(stdout = "bounded tail")
                    }
                }
                arguments.take(3) == listOf("logcat", "-b", "crash") -> result(stdout = "crash line")
                arguments.take(4) == listOf("shell", "dumpsys", "activity", "exit-info") -> result(stdout = "exit line")
                else -> error("Unexpected evidence command: $arguments")
            }
        }

        val evidence = collector(adb).collect("io.example.app", CliRuntimeEvidenceKind.LOGCAT_WINDOW, 1_720_779_300_000L)
        val finalAppCommand = adb.executions.map { it.first }.filter { it.firstOrNull() == "logcat" && "-b" !in it }.last()

        assertEquals(3, appAttempts)
        assertFalse("--pid" in finalAppCommand)
        assertFalse("-T" in finalAppCommand)
        assertTrue("pid_filter_unsupported" in evidence.warnings)
        assertTrue("timestamp_filter_unsupported" in evidence.warnings)
    }

    @Test
    fun missingPidFailsClosedForAppLogButKeepsCrashAndExitEvidence() {
        val adb = evidenceAdb(pid = "") { arguments ->
            when {
                arguments.take(3) == listOf("logcat", "-b", "crash") -> result(stdout = "crash line")
                arguments.take(4) == listOf("shell", "dumpsys", "activity", "exit-info") -> result(stdout = "exit line")
                else -> error("Unsafe or unexpected evidence command: $arguments")
            }
        }

        val evidence = collector(adb).collect("io.example.app", CliRuntimeEvidenceKind.LOGCAT_WINDOW, 1_720_779_300_000L)

        assertTrue(adb.executions.none { (arguments, _) -> arguments.firstOrNull() == "logcat" && "-b" !in arguments })
        assertEquals(CliRuntimeEvidenceStatus.PARTIAL, evidence.status)
        assertEquals("process_not_running", evidence.failureCode)
        assertTrue(evidence.output.contains("crash line"))
        assertTrue(evidence.output.contains("exit line"))
    }

    @Test
    fun missingPidFailsClosedForMemoryAndFrameCollectors() {
        CliRuntimeEvidenceKind.entries
            .filter { it == CliRuntimeEvidenceKind.MEMORY_SUMMARY || it == CliRuntimeEvidenceKind.FRAME_SUMMARY }
            .forEach { kind ->
                val adb = evidenceAdb(pid = "") { arguments -> error("Unsafe evidence command: $arguments") }

                val evidence = collector(adb).collect("io.example.app", kind, 0L)

                assertEquals(CliRuntimeEvidenceStatus.FAILED, evidence.status)
                assertEquals("process_not_running", evidence.failureCode)
                assertTrue(adb.executions.none { (arguments, _) -> "meminfo" in arguments || "gfxinfo" in arguments })
            }
    }

    @Test
    fun timeoutWithoutUsableOutputFailsWhileBoundedOutputIsPartial() {
        listOf("" to CliRuntimeEvidenceStatus.FAILED, "TOTAL PSS: 42" to CliRuntimeEvidenceStatus.PARTIAL).forEach { (stdout, expected) ->
            val adb = evidenceAdb(pid = "123") { arguments ->
                when {
                    arguments.take(3) == listOf("shell", "dumpsys", "meminfo") -> result(exitCode = null, stdout = stdout, timedOut = true)
                    else -> error("Unexpected evidence command: $arguments")
                }
            }

            val evidence = collector(adb).collect("io.example.app", CliRuntimeEvidenceKind.MEMORY_SUMMARY, 0L)

            assertEquals(expected, evidence.status)
            assertEquals("timeout", evidence.failureCode)
        }
    }

    @Test
    fun logcatSinceUsesEpochMillisIndependentlyOfHostZone() {
        val timestamps = listOf(ZoneId.of("Pacific/Honolulu"), ZoneId.of("Asia/Seoul")).map { zone ->
            val adb = evidenceAdb(pid = "123") { arguments ->
                when {
                    arguments.firstOrNull() == "logcat" && "-b" !in arguments -> result(stdout = "app line")
                    arguments.take(3) == listOf("logcat", "-b", "crash") -> result(stdout = "crash line")
                    arguments.take(4) == listOf("shell", "dumpsys", "activity", "exit-info") -> result(stdout = "exit line")
                    else -> error("Unexpected evidence command: $arguments")
                }
            }
            AndroidRuntimeEvidenceCollector(adb, "emulator-5554", zoneId = zone)
                .collect("io.example.app", CliRuntimeEvidenceKind.LOGCAT_WINDOW, 1_720_779_300_000L)
            val command = adb.executions.map { it.first }.first { it.firstOrNull() == "logcat" && "-b" !in it }
            command[command.indexOf("-T") + 1]
        }

        assertEquals(listOf("1720779298.000", "1720779298.000"), timestamps)
    }

    private fun evidenceAdb(
        pid: String,
        evidenceHandler: (List<String>) -> AdbExecutionResult,
    ): RecordingAdbFacade = RecordingAdbFacade { arguments, _ ->
        when {
            arguments == listOf("shell", "pm", "path", "io.example.app") -> result(stdout = "package:/base.apk")
            arguments == listOf("shell", "pidof", "io.example.app") -> result(stdout = pid)
            arguments.take(3) == listOf("shell", "dumpsys", "package") -> result(stdout = "")
            else -> evidenceHandler(arguments)
        }
    }

    private fun collector(adb: AdbFacade): AndroidRuntimeEvidenceCollector = AndroidRuntimeEvidenceCollector(
        adb = adb,
        deviceSerial = "emulator-5554",
        clock = sequenceOf(1L, 2L).iterator()::next,
    )

    private class RecordingAdbFacade(
        private val handler: (List<String>, AdbExecutionLimits) -> AdbExecutionResult,
    ) : AdbFacade {
        val executions = mutableListOf<Pair<List<String>, AdbExecutionLimits>>()

        override fun devices(): List<AdbDevice> = emptyList()
        override fun execute(arguments: List<String>, limits: AdbExecutionLimits): AdbExecutionResult {
            executions += arguments to limits
            return handler(arguments, limits)
        }
        override fun runAsCat(packageName: String, path: String): String = error("not used")
        override fun forward(localPort: Int, socketAddress: String) = error("not used")
        override fun removeForward(localPort: Int) = error("not used")
        override fun pull(androidPath: String, destination: File) = error("not used")
        override fun launchApp(packageName: String) = error("not used")
    }

    private companion object {
        fun result(
            exitCode: Int? = 0,
            stdout: String = "",
            stderr: String = "",
            timedOut: Boolean = false,
            stdoutTruncated: Boolean = false,
        ) = AdbExecutionResult(exitCode, stdout, stderr, timedOut, stdoutTruncated, false)
    }
}
