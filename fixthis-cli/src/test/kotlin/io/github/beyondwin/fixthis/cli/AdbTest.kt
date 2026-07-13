package io.github.beyondwin.fixthis.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AdbTest {
    @Test
    fun exposesOnlyAllowlistedOperations() {
        val publicMethods = Adb::class.java.declaredMethods
            .filter { method -> java.lang.reflect.Modifier.isPublic(method.modifiers) }
            .filterNot { method -> method.isSynthetic }
            .map { method -> method.name }
            .toSet()

        assertEquals(
            setOf(
                "devices",
                "forDevice",
                "shell",
                "forward",
                "removeForward",
                "install",
                "monkey",
                "launchApp",
                "runAsCat",
                "pull",
                "currentFocusOutput",
                "execute",
            ),
            publicMethods,
        )
    }

    @Test
    fun buildsAllowlistedAdbCommandsWithoutShellConcatenation() {
        val runner = RecordingAdbRunner()
        val adb = Adb(adbExecutable = "/sdk/platform-tools/adb", runner = runner)

        adb.devices()
        adb.shell("pidof", "io.github.beyondwin.fixthis.sample")
        adb.forward(localPort = 43210, socketAddress = "localabstract:fixthis_io.github.beyondwin.fixthis.sample")
        adb.removeForward(localPort = 43210)
        adb.install(File("sample-debug.apk"))
        adb.monkey("io.github.beyondwin.fixthis.sample")
        adb.launchApp("io.github.beyondwin.fixthis.sample")
        adb.runAsCat("io.github.beyondwin.fixthis.sample", "files/fixthis/session.json")
        adb.pull("/data/user/0/io.github.beyondwin.fixthis.sample/cache/fixthis/full.png", File("full.png"))

        assertEquals(
            listOf(
                listOf("/sdk/platform-tools/adb", "devices", "-l"),
                listOf("/sdk/platform-tools/adb", "shell", "pidof", "io.github.beyondwin.fixthis.sample"),
                listOf(
                    "/sdk/platform-tools/adb",
                    "forward",
                    "tcp:43210",
                    "localabstract:fixthis_io.github.beyondwin.fixthis.sample",
                ),
                listOf("/sdk/platform-tools/adb", "forward", "--remove", "tcp:43210"),
                listOf("/sdk/platform-tools/adb", "install", "-r", "sample-debug.apk"),
                listOf("/sdk/platform-tools/adb", "shell", "monkey", "-p", "io.github.beyondwin.fixthis.sample", "1"),
                listOf("/sdk/platform-tools/adb", "shell", "monkey", "-p", "io.github.beyondwin.fixthis.sample", "1"),
                listOf(
                    "/sdk/platform-tools/adb",
                    "shell",
                    "run-as",
                    "io.github.beyondwin.fixthis.sample",
                    "cat",
                    "files/fixthis/session.json",
                ),
                listOf(
                    "/sdk/platform-tools/adb",
                    "pull",
                    "/data/user/0/io.github.beyondwin.fixthis.sample/cache/fixthis/full.png",
                    "full.png",
                ),
            ),
            runner.commands,
        )
    }

    @Test
    fun parsesDeviceStatesAndMetadata() {
        val runner = RecordingAdbRunner(
            AdbResult(
                exitCode = 0,
                stdout = """
                    List of devices attached
                    emulator-5554	device product:sdk_phone64 model:sdk_gphone64 device:emu64
                    R5CT	unauthorized product:y2qksx model:SM_G986N device:y2q
                    offline-device	offline

                """.trimIndent(),
                stderr = "",
            ),
        )
        val devices = Adb(adbExecutable = "adb", runner = runner).devices()

        assertEquals(
            listOf(
                AdbDevice(
                    serial = "emulator-5554",
                    state = "device",
                    model = "sdk_gphone64",
                    product = "sdk_phone64",
                    deviceName = "emu64",
                ),
                AdbDevice(
                    serial = "R5CT",
                    state = "unauthorized",
                    model = "SM_G986N",
                    product = "y2qksx",
                    deviceName = "y2q",
                ),
                AdbDevice(serial = "offline-device", state = "offline"),
            ),
            devices,
        )
        assertTrue(runner.commands.single() == listOf("adb", "devices", "-l"))
    }

    @Test
    fun defaultExecutableUsesProjectLocalPropertiesSdkDirWhenEnvironmentIsMissing() {
        val projectRoot = Files.createTempDirectory("fixthis-adb-project-").toFile().apply {
            deleteOnExit()
        }
        val sdkRoot = Files.createTempDirectory("fixthis-android-sdk-").toFile().apply {
            deleteOnExit()
        }
        val adb = File(sdkRoot, "platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText("")
            setExecutable(true)
            deleteOnExit()
        }
        File(projectRoot, "local.properties").writeText("sdk.dir=${sdkRoot.absolutePath}\n")

        assertEquals(
            adb.absolutePath,
            Adb.defaultAdbExecutable(projectRoot = projectRoot, environment = emptyMap()),
        )
    }

    @Test
    fun defaultExecutableUsesAndroidHomeBeforeProjectAndDefaultSdkDirs() {
        val projectRoot = Files.createTempDirectory("fixthis-adb-project-").toFile().apply {
            deleteOnExit()
        }
        val userHome = Files.createTempDirectory("fixthis-user-home-").toFile().apply {
            deleteOnExit()
        }
        val environmentSdkRoot = Files.createTempDirectory("fixthis-env-android-sdk-").toFile().apply {
            deleteOnExit()
        }
        val localPropertiesSdkRoot = Files.createTempDirectory("fixthis-local-android-sdk-").toFile().apply {
            deleteOnExit()
        }
        val expectedAdb = File(environmentSdkRoot, "platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText("")
            setExecutable(true)
            deleteOnExit()
        }
        File(localPropertiesSdkRoot, "platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText("")
            setExecutable(true)
            deleteOnExit()
        }
        File(userHome, "Library/Android/sdk/platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText("")
            setExecutable(true)
            deleteOnExit()
        }
        File(projectRoot, "local.properties").writeText("sdk.dir=${localPropertiesSdkRoot.absolutePath}\n")

        assertEquals(
            expectedAdb.absolutePath,
            Adb.defaultAdbExecutable(
                projectRoot = projectRoot,
                environment = mapOf(
                    "ANDROID_HOME" to environmentSdkRoot.absolutePath,
                    "HOME" to userHome.absolutePath,
                ),
                osName = "Mac OS X",
            ),
        )
    }

    @Test
    fun defaultExecutableUsesMacOsDefaultSdkDirWhenEnvironmentAndLocalPropertiesAreMissing() {
        val projectRoot = Files.createTempDirectory("fixthis-adb-project-").toFile().apply {
            deleteOnExit()
        }
        val userHome = Files.createTempDirectory("fixthis-user-home-").toFile().apply {
            deleteOnExit()
        }
        val adb = File(userHome, "Library/Android/sdk/platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText("")
            setExecutable(true)
            deleteOnExit()
        }

        assertEquals(
            adb.absolutePath,
            Adb.defaultAdbExecutable(
                projectRoot = projectRoot,
                environment = mapOf("HOME" to userHome.absolutePath),
                osName = "Mac OS X",
            ),
        )
    }

    @Test
    fun defaultExecutableUsesLinuxDefaultSdkDirWhenEnvironmentAndLocalPropertiesAreMissing() {
        val projectRoot = Files.createTempDirectory("fixthis-adb-project-").toFile().apply {
            deleteOnExit()
        }
        val userHome = Files.createTempDirectory("fixthis-user-home-").toFile().apply {
            deleteOnExit()
        }
        val adb = File(userHome, "Android/Sdk/platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText("")
            setExecutable(true)
            deleteOnExit()
        }

        assertEquals(
            adb.absolutePath,
            Adb.defaultAdbExecutable(
                projectRoot = projectRoot,
                environment = mapOf("HOME" to userHome.absolutePath),
                osName = "Linux",
            ),
        )
    }

    @Test
    fun scopesRequestCommandsToSelectedDevice() {
        val runner = RecordingAdbRunner()
        val adb = Adb(adbExecutable = "adb", runner = runner).forDevice("emulator-5554")

        adb.devices()
        adb.forward(localPort = 43210, socketAddress = "localabstract:fixthis_io.github.beyondwin.fixthis.sample")
        adb.removeForward(localPort = 43210)
        adb.runAsCat("io.github.beyondwin.fixthis.sample", "files/fixthis/session.json")

        assertEquals(
            listOf(
                listOf("adb", "devices", "-l"),
                listOf(
                    "adb",
                    "-s",
                    "emulator-5554",
                    "forward",
                    "tcp:43210",
                    "localabstract:fixthis_io.github.beyondwin.fixthis.sample",
                ),
                listOf("adb", "-s", "emulator-5554", "forward", "--remove", "tcp:43210"),
                listOf(
                    "adb",
                    "-s",
                    "emulator-5554",
                    "shell",
                    "run-as",
                    "io.github.beyondwin.fixthis.sample",
                    "cat",
                    "files/fixthis/session.json",
                ),
            ),
            runner.commands,
        )
    }

    @Test
    fun boundedExecutionScopesSerialAndReportsTruncation() {
        val runner = RecordingBoundedRunner(
            AdbExecutionResult(
                exitCode = 0,
                stdout = "1234",
                stderr = "",
                timedOut = false,
                stdoutTruncated = true,
                stderrTruncated = false,
            ),
        )
        val adb = Adb("adb", runner).forDevice("emulator-5554")

        val actual = adb.execute(
            listOf("shell", "pidof", "io.example"),
            AdbExecutionLimits(timeoutMillis = 250, maxStdoutBytes = 4, maxStderrBytes = 4),
        )

        assertEquals(
            listOf("adb", "-s", "emulator-5554", "shell", "pidof", "io.example"),
            runner.command,
        )
        assertTrue(actual.stdoutTruncated)
    }

    @Test(timeout = 5_000)
    fun boundedProcessRunnerTruncatesStdoutAndStderrWhileDraining() {
        val script = File.createTempFile("fixthis-adb-runner-bounded", ".sh").apply {
            writeText(
                """
                    #!/usr/bin/env sh
                    printf 'abcdefghij'
                    printf 'ABCDEFGHIJ' >&2
                """.trimIndent(),
            )
            setExecutable(true)
            deleteOnExit()
        }

        val actual = ProcessAdbCommandRunner().runBounded(
            listOf(script.absolutePath),
            AdbExecutionLimits(timeoutMillis = 1_000, maxStdoutBytes = 4, maxStderrBytes = 6),
        )

        assertEquals(0, actual.exitCode)
        assertEquals("abcd", actual.stdout)
        assertEquals("ABCDEF", actual.stderr)
        assertFalse(actual.timedOut)
        assertTrue(actual.stdoutTruncated)
        assertTrue(actual.stderrTruncated)
    }

    @Test(timeout = 5_000)
    fun processRunnerDestroysTimedOutProcess() {
        val pidFile = File.createTempFile("fixthis-adb-runner-pid", ".txt").apply {
            delete()
            deleteOnExit()
        }
        val blockingScript = File.createTempFile("fixthis-adb-runner-blocking", ".sh").apply {
            writeText(
                """
                    #!/usr/bin/env sh
                    printf '%s' "${'$'}${'$'}" > "${pidFile.absolutePath}"
                    while true; do :; done
                """.trimIndent(),
            )
            setExecutable(true)
            deleteOnExit()
        }

        val actual = ProcessAdbCommandRunner().runBounded(
            listOf(blockingScript.absolutePath),
            AdbExecutionLimits(timeoutMillis = 1_000, maxStdoutBytes = 64, maxStderrBytes = 64),
        )

        assertTrue(actual.timedOut)
        assertNull(actual.exitCode)
        assertTrue("timed-out process should have started", pidFile.exists())
        val pid = pidFile.readText().toLong()
        assertFalse(
            "timed-out process should be destroyed before returning",
            ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
        )
    }

    @Test(timeout = 10_000)
    fun boundedProcessRunnerInterruptedTimeoutCleanupWaitsForForcedTerminationAndReaders() {
        val process = InterruptedTimeoutProcess()
        val failure = AtomicReference<Throwable?>()
        val interruptStatusRestored = AtomicBoolean(false)
        val runnerThread = Thread({
            try {
                ProcessAdbCommandRunner(processStarter = { process }).runBounded(
                    listOf("adb", "shell", "ignored"),
                    AdbExecutionLimits(timeoutMillis = 50, maxStdoutBytes = 64, maxStderrBytes = 64),
                )
                failure.set(AssertionError("bounded runner returned normally after cleanup interruption"))
            } catch (_: InterruptedException) {
                interruptStatusRestored.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "fixthis-adb-bounded-interrupt-test")

        runnerThread.start()
        try {
            assertTrue("stdout reader should start", process.stdout.readStarted.await(2, TimeUnit.SECONDS))
            assertTrue("stderr reader should start", process.stderr.readStarted.await(2, TimeUnit.SECONDS))
            assertTrue("graceful termination wait should start", process.gracefulWaitStarted.await(2, TimeUnit.SECONDS))

            runnerThread.interrupt()

            assertTrue("stdout should close after forced termination", process.stdout.closeObserved.await(2, TimeUnit.SECONDS))
            assertTrue("stderr should close after forced termination", process.stderr.closeObserved.await(2, TimeUnit.SECONDS))
            assertTrue("runner should still be joining readers before rethrowing", runnerThread.isAlive)
        } finally {
            if (runnerThread.isAlive) runnerThread.interrupt()
            process.releaseReaders.countDown()
            runnerThread.join(2_000)
        }

        assertFalse("runner should finish interrupted cleanup", runnerThread.isAlive)
        failure.get()?.let { throw it }
        assertTrue("graceful destruction should be attempted", process.destroyCalled.get())
        assertTrue("forced destruction should be attempted", process.destroyForciblyCalled.get())
        assertTrue("forced termination should be waited for", process.forcedWaitCompleted.get())
        assertTrue("stdout reader should finish", process.stdout.readFinished.await(0, TimeUnit.MILLISECONDS))
        assertTrue("stderr reader should finish", process.stderr.readFinished.await(0, TimeUnit.MILLISECONDS))
        assertTrue("runner should restore interrupt status before throwing", interruptStatusRestored.get())
    }

    @Test(timeout = 5_000)
    fun processRunnerReadsStdoutAndStderrWithoutBlockingEachOther() {
        val script = File.createTempFile("fixthis-adb-runner", ".sh").apply {
            writeText(
                """
                    #!/usr/bin/env sh
                    i=0
                    while [ "${'$'}i" -lt 512 ]; do
                      printf 'stderr-line-%04d %080d\n' "${'$'}i" "${'$'}i" >&2
                      i=${'$'}((i + 1))
                    done
                    printf 'stdout-after-stderr\n'
                """.trimIndent(),
            )
            setExecutable(true)
            deleteOnExit()
        }

        val result = ProcessAdbCommandRunner().run(listOf(script.absolutePath))

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("stdout-after-stderr"))
        assertTrue(result.stderr.contains("stderr-line-0511"))
        assertFalse(result.stderr.contains("stdout-after-stderr"))
    }

    @Test(timeout = 15_000)
    fun processRunnerInterruptDestroysProcessAndRestoresInterruptStatus() {
        val marker = File.createTempFile("fixthis-adb-runner-started", ".txt").apply {
            delete()
            deleteOnExit()
        }
        val script = File.createTempFile("fixthis-adb-runner-sleep", ".sh").apply {
            writeText(
                """
                    #!/usr/bin/env sh
                    printf started > "${marker.absolutePath}"
                    while true; do
                      sleep 1
                    done
                """.trimIndent(),
            )
            setExecutable(true)
            deleteOnExit()
        }
        var runnerThread: Thread? = null
        try {
            val failure = AtomicReference<Throwable?>()
            val interruptStatusRestored = AtomicBoolean(false)
            runnerThread = Thread({
                try {
                    ProcessAdbCommandRunner().run(listOf(script.absolutePath))
                    failure.set(AssertionError("runner returned normally after interruption"))
                } catch (error: InterruptedException) {
                    interruptStatusRestored.set(Thread.currentThread().isInterrupted)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }, "fixthis-adb-runner-interrupt-test")

            runnerThread.start()
            awaitMarker(marker, TimeUnit.SECONDS.toNanos(5))

            runnerThread.interrupt()
            runnerThread.join(2_000)

            assertFalse("runner thread should return after interruption", runnerThread.isAlive)
            failure.get()?.let { throw it }
            assertTrue("runner should restore interrupt status before throwing", interruptStatusRestored.get())
        } finally {
            runnerThread?.takeIf { it.isAlive }?.interrupt()
        }
    }

    private fun awaitMarker(
        marker: File,
        timeoutNanos: Long,
    ) {
        val deadline = System.nanoTime() + timeoutNanos
        while (!marker.exists()) {
            val remaining = deadline - System.nanoTime()
            assertTrue("marker did not arrive within timeout", remaining > 0)
            Thread.sleep(minOf(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1), 10L))
        }
    }

    private class RecordingAdbRunner(
        private val result: AdbResult = AdbResult(exitCode = 0, stdout = "", stderr = ""),
    ) : AdbCommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>): AdbResult {
            commands += command
            return result
        }
    }

    private class RecordingBoundedRunner(
        private val result: AdbExecutionResult,
    ) : AdbCommandRunner {
        var command: List<String>? = null

        override fun run(command: List<String>): AdbResult = error("unexpected unbounded execution")

        override fun runBounded(command: List<String>, limits: AdbExecutionLimits): AdbExecutionResult {
            this.command = command
            return result
        }
    }

    private class InterruptedTimeoutProcess : Process() {
        val releaseReaders = CountDownLatch(1)
        val stdout = GatedInputStream(releaseReaders)
        val stderr = GatedInputStream(releaseReaders)
        val gracefulWaitStarted = CountDownLatch(1)
        val destroyCalled = AtomicBoolean(false)
        val destroyForciblyCalled = AtomicBoolean(false)
        val forcedWaitCompleted = AtomicBoolean(false)
        private val timedWaitCalls = AtomicInteger(0)

        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()

        override fun getInputStream(): java.io.InputStream = stdout

        override fun getErrorStream(): java.io.InputStream = stderr

        override fun waitFor(): Int {
            forcedWaitCompleted.set(true)
            return 137
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = when (timedWaitCalls.incrementAndGet()) {
            1 -> false
            2 -> {
                gracefulWaitStarted.countDown()
                CountDownLatch(1).await()
                false
            }
            else -> {
                forcedWaitCompleted.set(true)
                true
            }
        }

        override fun exitValue(): Int = 137

        override fun destroy() {
            destroyCalled.set(true)
        }

        override fun destroyForcibly(): Process {
            destroyForciblyCalled.set(true)
            return this
        }

        override fun isAlive(): Boolean = !forcedWaitCompleted.get()
    }

    private class GatedInputStream(private val releaseReaders: CountDownLatch) : java.io.InputStream() {
        val readStarted = CountDownLatch(1)
        val closeObserved = CountDownLatch(1)
        val readFinished = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            closeObserved.await()
            releaseReaders.await()
            readFinished.countDown()
            return -1
        }

        override fun close() {
            closeObserved.countDown()
        }
    }
}
