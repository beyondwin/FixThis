package io.github.pointpatch.cli

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTest {
    @Test
    fun exposesOnlyAllowlistedOperations() {
        val publicMethods = Adb::class.java.declaredMethods
            .filter { method -> java.lang.reflect.Modifier.isPublic(method.modifiers) }
            .filterNot { method -> method.isSynthetic }
            .map { method -> method.name }
            .toSet()

        assertEquals(
            setOf("devices", "forDevice", "shell", "forward", "removeForward", "install", "monkey", "runAsCat", "pull"),
            publicMethods,
        )
    }

    @Test
    fun buildsAllowlistedAdbCommandsWithoutShellConcatenation() {
        val runner = RecordingAdbRunner()
        val adb = Adb(adbExecutable = "/sdk/platform-tools/adb", runner = runner)

        adb.devices()
        adb.shell("pidof", "io.github.pointpatch.sample")
        adb.forward(localPort = 43210, socketAddress = "localabstract:pointpatch_io.github.pointpatch.sample")
        adb.removeForward(localPort = 43210)
        adb.install(File("sample-debug.apk"))
        adb.monkey("io.github.pointpatch.sample")
        adb.runAsCat("io.github.pointpatch.sample", "files/pointpatch/session.json")
        adb.pull("/data/user/0/io.github.pointpatch.sample/cache/pointpatch/full.png", File("full.png"))

        assertEquals(
            listOf(
                listOf("/sdk/platform-tools/adb", "devices", "-l"),
                listOf("/sdk/platform-tools/adb", "shell", "pidof", "io.github.pointpatch.sample"),
                listOf(
                    "/sdk/platform-tools/adb",
                    "forward",
                    "tcp:43210",
                    "localabstract:pointpatch_io.github.pointpatch.sample",
                ),
                listOf("/sdk/platform-tools/adb", "forward", "--remove", "tcp:43210"),
                listOf("/sdk/platform-tools/adb", "install", "-r", "sample-debug.apk"),
                listOf("/sdk/platform-tools/adb", "shell", "monkey", "-p", "io.github.pointpatch.sample", "1"),
                listOf(
                    "/sdk/platform-tools/adb",
                    "shell",
                    "run-as",
                    "io.github.pointpatch.sample",
                    "cat",
                    "files/pointpatch/session.json",
                ),
                listOf(
                    "/sdk/platform-tools/adb",
                    "pull",
                    "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/full.png",
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
    fun scopesRequestCommandsToSelectedDevice() {
        val runner = RecordingAdbRunner()
        val adb = Adb(adbExecutable = "adb", runner = runner).forDevice("emulator-5554")

        adb.devices()
        adb.forward(localPort = 43210, socketAddress = "localabstract:pointpatch_io.github.pointpatch.sample")
        adb.removeForward(localPort = 43210)
        adb.runAsCat("io.github.pointpatch.sample", "files/pointpatch/session.json")

        assertEquals(
            listOf(
                listOf("adb", "devices", "-l"),
                listOf(
                    "adb",
                    "-s",
                    "emulator-5554",
                    "forward",
                    "tcp:43210",
                    "localabstract:pointpatch_io.github.pointpatch.sample",
                ),
                listOf("adb", "-s", "emulator-5554", "forward", "--remove", "tcp:43210"),
                listOf(
                    "adb",
                    "-s",
                    "emulator-5554",
                    "shell",
                    "run-as",
                    "io.github.pointpatch.sample",
                    "cat",
                    "files/pointpatch/session.json",
                ),
            ),
            runner.commands,
        )
    }

    @Test(timeout = 2_000)
    fun processRunnerReadsStdoutAndStderrWithoutBlockingEachOther() {
        val script = File.createTempFile("pointpatch-adb-runner", ".sh").apply {
            writeText(
                """
                    #!/usr/bin/env sh
                    i=0
                    while [ "${'$'}i" -lt 2000 ]; do
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
        assertTrue(result.stderr.contains("stderr-line-1999"))
        assertFalse(result.stderr.contains("stdout-after-stderr"))
    }

    @Test(timeout = 3_000)
    fun processRunnerInterruptDestroysProcessAndRestoresInterruptStatus() {
        val marker = File.createTempFile("pointpatch-adb-runner-started", ".txt").apply {
            delete()
            deleteOnExit()
        }
        val script = File.createTempFile("pointpatch-adb-runner-sleep", ".sh").apply {
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
        val failure = AtomicReference<Throwable?>()
        val interruptStatusRestored = AtomicBoolean(false)
        val runnerThread = Thread({
            try {
                ProcessAdbCommandRunner().run(listOf(script.absolutePath))
                failure.set(AssertionError("runner returned normally after interruption"))
            } catch (error: InterruptedException) {
                interruptStatusRestored.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "pointpatch-adb-runner-interrupt-test")

        runnerThread.start()
        while (!marker.exists()) {
            Thread.sleep(10)
        }

        runnerThread.interrupt()
        runnerThread.join(2_000)

        assertFalse("runner thread should return after interruption", runnerThread.isAlive)
        failure.get()?.let { throw it }
        assertTrue("runner should restore interrupt status before throwing", interruptStatusRestored.get())
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
}
