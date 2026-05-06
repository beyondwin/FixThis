package io.github.pointpatch.cli

import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class AdbResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

data class AdbDevice(
    val serial: String,
    val state: String,
    val model: String? = null,
    val product: String? = null,
    val deviceName: String? = null,
)

fun interface AdbCommandRunner {
    fun run(command: List<String>): AdbResult
}

interface AdbFacade {
    fun devices(): List<AdbDevice>
    fun forDevice(serial: String?): AdbFacade = this
    fun runAsCat(packageName: String, path: String): String
    fun forward(localPort: Int, socketAddress: String)
    fun removeForward(localPort: Int)
    fun pull(androidPath: String, destination: File)
}

class Adb(
    private val adbExecutable: String = defaultAdbExecutable(),
    private val runner: AdbCommandRunner = ProcessAdbCommandRunner(),
    private val selectedSerial: String? = null,
) : AdbFacade {
    override fun forDevice(serial: String?): AdbFacade =
        Adb(adbExecutable = adbExecutable, runner = runner, selectedSerial = serial?.takeIf { it.isNotBlank() })

    override fun devices(): List<AdbDevice> {
        val result = checkedRun(listOf("devices", "-l"), includeSelectedSerial = false)
        return result.stdout.lineSequence()
            .drop(1)
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .mapNotNull { line -> parseDeviceLine(line) }
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

    private fun checkedRun(arguments: List<String>, includeSelectedSerial: Boolean = true): AdbResult {
        val serialArgs = if (includeSelectedSerial && !selectedSerial.isNullOrBlank()) {
            listOf("-s", selectedSerial)
        } else {
            emptyList()
        }
        val command = listOf(adbExecutable) + serialArgs + arguments
        val result = runner.run(command)
        if (result.exitCode != 0) {
            throw AdbException(command, result)
        }
        return result
    }

    private fun parseDeviceLine(line: String): AdbDevice? {
        val parts = line.split(Regex("\\s+"))
        val serial = parts.getOrNull(0) ?: return null
        val state = parts.getOrNull(1) ?: return null
        val metadata = parts.drop(2).mapNotNull { token ->
            val pieces = token.split(":", limit = 2)
            if (pieces.size == 2) pieces[0] to pieces[1] else null
        }.toMap()
        return AdbDevice(
            serial = serial,
            state = state,
            model = metadata["model"],
            product = metadata["product"],
            deviceName = metadata["device"],
        )
    }

    companion object {
        fun forProject(projectRoot: File): Adb =
            Adb(adbExecutable = defaultAdbExecutable(projectRoot = projectRoot))

        fun defaultAdbExecutable(
            projectRoot: File? = null,
            environment: Map<String, String> = System.getenv(),
        ): String {
            listOfNotNull(
                environment["ANDROID_HOME"]?.takeIf { it.isNotBlank() },
                environment["ANDROID_SDK_ROOT"]?.takeIf { it.isNotBlank() },
                projectRoot?.localPropertiesSdkDir(),
            ).forEach { sdkDir ->
                val adb = File(sdkDir, "platform-tools/${adbExecutableName()}")
                if (adb.exists()) return adb.absolutePath
            }
            return "adb"
        }

        private fun File.localPropertiesSdkDir(): String? {
            val localProperties = resolve("local.properties")
            if (!localProperties.exists()) return null
            val properties = Properties()
            localProperties.inputStream().use { input -> properties.load(input) }
            return properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
        }

        private fun adbExecutableName(): String =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "adb.exe" else "adb"
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

        val stdoutThread = thread(name = "pointpatch-adb-stdout", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().use { reader -> reader.readText() }
            }.fold(
                onSuccess = { stdout = it },
                onFailure = { stdoutFailure = it },
            )
        }
        val stderrThread = thread(name = "pointpatch-adb-stderr", isDaemon = true) {
            runCatching {
                process.errorStream.bufferedReader().use { reader -> reader.readText() }
            }.fold(
                onSuccess = { stderr = it },
                onFailure = { stderrFailure = it },
            )
        }
        val exitCode = try {
            process.waitFor()
        } catch (error: InterruptedException) {
            cleanupInterruptedProcess(process, stdoutThread, stderrThread)
            Thread.currentThread().interrupt()
            throw error
        }
        try {
            stdoutThread.join()
            stderrThread.join()
        } catch (error: InterruptedException) {
            cleanupInterruptedProcess(process, stdoutThread, stderrThread)
            Thread.currentThread().interrupt()
            throw error
        }
        stdoutFailure?.let { throw it }
        stderrFailure?.let { throw it }
        return AdbResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }

    private fun cleanupInterruptedProcess(process: Process, stdoutThread: Thread, stderrThread: Thread) {
        var interruptedAgain = false
        runCatching { process.destroy() }
        process.closeStreams()
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(500, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            interruptedAgain = true
            process.destroyForcibly()
        }
        listOf(stdoutThread, stderrThread).forEach { thread ->
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                interruptedAgain = true
            }
        }
        if (interruptedAgain) Thread.currentThread().interrupt()
    }

    private fun Process.closeStreams() {
        runCatching { inputStream.close() }
        runCatching { errorStream.close() }
        runCatching { outputStream.close() }
    }
}
