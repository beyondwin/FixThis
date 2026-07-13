package io.github.beyondwin.fixthis.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class AdbResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

data class AdbExecutionLimits(
    val timeoutMillis: Long,
    val maxStdoutBytes: Int,
    val maxStderrBytes: Int,
) {
    init {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        require(maxStdoutBytes >= 0) { "maxStdoutBytes must not be negative" }
        require(maxStderrBytes >= 0) { "maxStderrBytes must not be negative" }
    }
}

data class AdbExecutionResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
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

    fun runBounded(command: List<String>, limits: AdbExecutionLimits): AdbExecutionResult {
        val result = run(command)
        return AdbExecutionResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = false,
            stdoutTruncated = false,
            stderrTruncated = false,
        )
    }
}

interface AdbFacade {
    fun devices(): List<AdbDevice>
    fun forDevice(serial: String?): AdbFacade = this
    fun execute(arguments: List<String>, limits: AdbExecutionLimits): AdbExecutionResult = error("Bounded ADB execution is unsupported")
    fun runAsCat(packageName: String, path: String): String
    fun currentFocusOutput(): String? = null
    fun forward(localPort: Int, socketAddress: String)
    fun removeForward(localPort: Int)
    fun pull(androidPath: String, destination: File)
    fun launchApp(packageName: String)
}

class Adb(
    private val adbExecutable: String = defaultAdbExecutable(),
    private val runner: AdbCommandRunner = ProcessAdbCommandRunner(),
    private val selectedSerial: String? = null,
) : AdbFacade {
    override fun forDevice(serial: String?): AdbFacade = Adb(adbExecutable = adbExecutable, runner = runner, selectedSerial = serial?.takeIf { it.isNotBlank() })

    override fun execute(arguments: List<String>, limits: AdbExecutionLimits): AdbExecutionResult {
        val serialArgs = if (!selectedSerial.isNullOrBlank()) listOf("-s", selectedSerial) else emptyList()
        return runner.runBounded(listOf(adbExecutable) + serialArgs + arguments, limits)
    }

    override fun devices(): List<AdbDevice> {
        val result = checkedRun(listOf("devices", "-l"), includeSelectedSerial = false)
        return result.stdout.lineSequence()
            .drop(1)
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .mapNotNull { line -> parseDeviceLine(line) }
            .toList()
    }

    fun shell(vararg arguments: String): AdbResult = checkedRun(listOf("shell") + arguments)

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

    override fun launchApp(packageName: String) {
        monkey(packageName)
    }

    override fun runAsCat(packageName: String, path: String): String = checkedRun(listOf("shell", "run-as", packageName, "cat", path)).stdout

    override fun currentFocusOutput(): String? = runCatching {
        shell("dumpsys", "window", "windows").stdout
            .lineSequence()
            .firstOrNull { line -> line.contains("mCurrentFocus=") }
            ?.trim()
    }.getOrNull()

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
        fun forProject(projectRoot: File): Adb = Adb(adbExecutable = defaultAdbExecutable(projectRoot = projectRoot))

        fun defaultAdbExecutable(
            projectRoot: File? = null,
            environment: Map<String, String> = System.getenv(),
            osName: String = System.getProperty("os.name"),
        ): String {
            sdkDirCandidates(projectRoot, environment, osName).forEach { sdkDir ->
                val adb = File(sdkDir, "platform-tools/${adbExecutableName(osName)}")
                if (adb.exists()) return adb.absolutePath
            }
            return "adb"
        }

        private fun sdkDirCandidates(
            projectRoot: File?,
            environment: Map<String, String>,
            osName: String,
        ): List<File> {
            val home = environment["HOME"]?.takeIf { it.isNotBlank() }
                ?: environment["USERPROFILE"]?.takeIf { it.isNotBlank() }
            return listOfNotNull(
                environment["ANDROID_HOME"]?.takeIf { it.isNotBlank() }?.let(::File),
                environment["ANDROID_SDK_ROOT"]?.takeIf { it.isNotBlank() }?.let(::File),
                projectRoot?.localPropertiesSdkDir()?.let(::File),
                defaultSdkDir(home, osName),
            ).distinct()
        }

        private fun defaultSdkDir(home: String?, osName: String): File? = when {
            home.isNullOrBlank() -> null
            osName.startsWith("Mac", ignoreCase = true) -> File(home, "Library/Android/sdk")
            osName.startsWith("Linux", ignoreCase = true) -> File(home, "Android/Sdk")
            else -> null
        }

        private fun File.localPropertiesSdkDir(): String? {
            val localProperties = resolve("local.properties")
            if (!localProperties.exists()) return null
            val properties = Properties()
            localProperties.inputStream().use { input -> properties.load(input) }
            return properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
        }

        private fun adbExecutableName(osName: String): String = if (osName.startsWith("Windows", ignoreCase = true)) "adb.exe" else "adb"
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

internal class ProcessAdbCommandRunner(
    private val processStarter: (List<String>) -> Process = { command -> ProcessBuilder(command).start() },
) : AdbCommandRunner {
    override fun run(command: List<String>): AdbResult {
        val process = processStarter(command)
        var stdout = ""
        var stderr = ""
        var stdoutFailure: Throwable? = null
        var stderrFailure: Throwable? = null

        val stdoutThread = thread(name = "fixthis-adb-stdout", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().use { reader -> reader.readText() }
            }.fold(
                onSuccess = { stdout = it },
                onFailure = { stdoutFailure = it },
            )
        }
        val stderrThread = thread(name = "fixthis-adb-stderr", isDaemon = true) {
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

    override fun runBounded(command: List<String>, limits: AdbExecutionLimits): AdbExecutionResult {
        val process = processStarter(command)
        val stdout = BoundedOutput(limits.maxStdoutBytes)
        val stderr = BoundedOutput(limits.maxStderrBytes)
        var stdoutFailure: Throwable? = null
        var stderrFailure: Throwable? = null

        val stdoutThread = thread(name = "fixthis-adb-bounded-stdout", isDaemon = true) {
            runCatching { process.inputStream.use(stdout::drain) }
                .onFailure { stdoutFailure = it }
        }
        val stderrThread = thread(name = "fixthis-adb-bounded-stderr", isDaemon = true) {
            runCatching { process.errorStream.use(stderr::drain) }
                .onFailure { stderrFailure = it }
        }

        val completed = try {
            process.waitFor(limits.timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            cleanupInterruptedProcess(process, stdoutThread, stderrThread)
            Thread.currentThread().interrupt()
            throw error
        }
        val cleanupInterrupted = if (!completed) {
            destroyTimedOutProcess(process)
        } else {
            false
        }
        if (cleanupInterrupted) {
            joinReadersUninterruptibly(stdoutThread, stderrThread)
            Thread.currentThread().interrupt()
            throw InterruptedException("Interrupted while destroying timed-out ADB process")
        }

        try {
            stdoutThread.join()
            stderrThread.join()
        } catch (error: InterruptedException) {
            cleanupInterruptedProcess(process, stdoutThread, stderrThread)
            Thread.currentThread().interrupt()
            throw error
        } finally {
            process.closeStreams()
        }
        if (completed) {
            stdoutFailure?.let { throw it }
            stderrFailure?.let { throw it }
        }
        return AdbExecutionResult(
            exitCode = if (completed) process.exitValue() else null,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !completed,
            stdoutTruncated = stdout.truncated,
            stderrTruncated = stderr.truncated,
        )
    }

    private fun destroyTimedOutProcess(process: Process): Boolean {
        var interrupted = false
        runCatching { process.destroy() }
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                interrupted = waitForForcedProcess(process) || interrupted
            }
        } catch (_: InterruptedException) {
            interrupted = true
            process.destroyForcibly()
            interrupted = waitForForcedProcess(process) || interrupted
        } finally {
            process.closeStreams()
        }
        return interrupted
    }

    private fun waitForForcedProcess(process: Process): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        var interrupted = false
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return interrupted
            try {
                process.waitFor(remaining, TimeUnit.NANOSECONDS)
                return interrupted
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }

    private fun joinReadersUninterruptibly(vararg readers: Thread) {
        readers.forEach { reader ->
            while (reader.isAlive) {
                try {
                    reader.join()
                } catch (_: InterruptedException) {
                    // Preserve the original cleanup interruption after every reader has stopped.
                }
            }
        }
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

    private class BoundedOutput(private val maxBytes: Int) {
        private val retained = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        var truncated: Boolean = false
            private set

        fun drain(input: java.io.InputStream) {
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return
                val remaining = maxBytes - retained.size()
                if (remaining > 0) {
                    retained.write(buffer, 0, minOf(read, remaining))
                }
                if (read > remaining.coerceAtLeast(0)) truncated = true
            }
        }

        fun text(): String = retained.toByteArray().toString(Charsets.UTF_8)
    }
}
