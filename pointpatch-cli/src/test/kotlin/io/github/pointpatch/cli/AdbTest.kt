package io.github.pointpatch.cli

import java.io.File
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
            setOf("devices", "shell", "forward", "removeForward", "install", "monkey", "runAsCat", "pull"),
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
                listOf("/sdk/platform-tools/adb", "devices"),
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
    fun parsesOnlyReadyDevices() {
        val runner = RecordingAdbRunner(
            AdbResult(
                exitCode = 0,
                stdout = """
                    List of devices attached
                    emulator-5554	device
                    R5CT	unauthorized
                    offline-device	offline

                """.trimIndent(),
                stderr = "",
            ),
        )
        val devices = Adb(adbExecutable = "adb", runner = runner).devices()

        assertEquals(listOf(AdbDevice(serial = "emulator-5554", state = "device")), devices)
        assertTrue(runner.commands.single() == listOf("adb", "devices"))
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
