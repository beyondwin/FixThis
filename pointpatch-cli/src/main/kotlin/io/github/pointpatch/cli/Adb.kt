package io.github.pointpatch.cli

import java.io.File
import kotlin.concurrent.thread

data class AdbResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

data class AdbDevice(
    val serial: String,
    val state: String,
)

fun interface AdbCommandRunner {
    fun run(command: List<String>): AdbResult
}

interface AdbFacade {
    fun devices(): List<AdbDevice>
    fun runAsCat(packageName: String, path: String): String
    fun forward(localPort: Int, socketAddress: String)
    fun removeForward(localPort: Int)
    fun pull(androidPath: String, destination: File)
}

class Adb(
    private val adbExecutable: String = defaultAdbExecutable(),
    private val runner: AdbCommandRunner = ProcessAdbCommandRunner(),
) : AdbFacade {
    override fun devices(): List<AdbDevice> {
        val result = checkedRun(listOf("devices"))
        return result.stdout.lineSequence()
            .drop(1)
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                val serial = parts.getOrNull(0) ?: return@mapNotNull null
                val state = parts.getOrNull(1) ?: return@mapNotNull null
                AdbDevice(serial = serial, state = state)
            }
            .filter { device -> device.state == "device" }
            .toList()
    }

    fun shell(vararg arguments: String): AdbResult =
        checkedRun(listOf("shell") + arguments)

    override fun forward(localPort: Int, socketAddress: String) {
        require(localPort in 1..65535) { "Invalid local TCP port: $localPort" }
        require(socketAddress.startsWith("localabstract:")) { "Only localabstract socket forwarding is supported" }
        checkedRun(listOf("forward", "tcp:$localPort", socketAddress))
    }

    override fun removeForward(localPort: Int) {
        require(localPort in 1..65535) { "Invalid local TCP port: $localPort" }
        checkedRun(listOf("forward", "--remove", "tcp:$localPort"))
    }

    fun install(apk: File) {
        checkedRun(listOf("install", "-r", apk.path))
    }

    fun monkey(packageName: String) {
        checkedRun(listOf("shell", "monkey", "-p", packageName, "1"))
    }

    override fun runAsCat(packageName: String, path: String): String =
        checkedRun(listOf("shell", "run-as", packageName, "cat", path)).stdout

    override fun pull(androidPath: String, destination: File) {
        checkedRun(listOf("pull", androidPath, destination.path))
    }

    private fun checkedRun(arguments: List<String>): AdbResult {
        val command = listOf(adbExecutable) + arguments
        val result = runner.run(command)
        if (result.exitCode != 0) {
            throw AdbException(command, result)
        }
        return result
    }

    companion object {
        fun defaultAdbExecutable(): String {
            val androidHome = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
            if (androidHome != null) {
                val adb = File(androidHome, "platform-tools/adb")
                if (adb.exists()) return adb.absolutePath
            }
            return "adb"
        }
    }
}

class AdbException(
    val command: List<String>,
    val result: AdbResult,
) : RuntimeException(
    buildString {
        append("ADB command failed: ")
        append(command.joinToString(" "))
        append(" (exit ")
        append(result.exitCode)
        append(")")
        val stderr = result.stderr.trim()
        if (stderr.isNotEmpty()) append(": ").append(stderr)
    },
)

internal class ProcessAdbCommandRunner : AdbCommandRunner {
    override fun run(command: List<String>): AdbResult {
        val process = ProcessBuilder(command).start()
        var stdout = ""
        var stderr = ""
        var stdoutFailure: Throwable? = null
        var stderrFailure: Throwable? = null

        val stdoutThread = thread(name = "pointpatch-adb-stdout") {
            runCatching {
                process.inputStream.bufferedReader().use { reader -> reader.readText() }
            }.fold(
                onSuccess = { stdout = it },
                onFailure = { stdoutFailure = it },
            )
        }
        val stderrThread = thread(name = "pointpatch-adb-stderr") {
            runCatching {
                process.errorStream.bufferedReader().use { reader -> reader.readText() }
            }.fold(
                onSuccess = { stderr = it },
                onFailure = { stderrFailure = it },
            )
        }
        val exitCode = process.waitFor()
        stdoutThread.join()
        stderrThread.join()
        stdoutFailure?.let { throw it }
        stderrFailure?.let { throw it }
        return AdbResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }
}
