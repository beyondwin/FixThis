package io.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import io.beyondwin.fixthis.cli.Adb
import io.beyondwin.fixthis.cli.BridgeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

class RunCommand : CoreCliktCommand(name = "run") {
    private val packageName by option("--package", help = "Android application id to launch")
    private val projectDir by option("--project-dir", help = "Project root containing .fixthis/project.json").default(".")
    private val installTask by option("--install-task", help = "Gradle install task to run before launch").default(":app:installDebug")
    private val timeoutMillis by option("--timeout-millis", help = "Bridge status timeout after launch").long().default(30_000L)

    override fun run() {
        val root = File(projectDir).canonicalFile
        val adb = Adb.forProject(root)
        val client = BridgeClient(adb = adb, projectRoot = root)
        val resolvedPackage = failAsCliError { client.resolvePackageName(packageName) }

        echo("Installing debug app with ./gradlew $installTask")
        runGradle(root, installTask)
        echo("Launching $resolvedPackage")
        failAsCliError { adb.monkey(resolvedPackage) }
        echo("Checking FixThis sidekick")
        failAsCliError {
            runBlocking {
                waitForStatus(timeoutMillis) { remaining ->
                    client.request(resolvedPackage, "status", readTimeoutMillis = remaining)
                }
            }
        }
        echo("sidekick: connected")
    }

    private fun runGradle(root: File, task: String) {
        val gradlew = root.resolve("gradlew")
        val command = if (gradlew.exists()) listOf(gradlew.absolutePath, task) else listOf("gradle", task)
        val process = ProcessBuilder(command)
            .directory(root)
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) error("Gradle install failed with exit code $exitCode")
    }
}

/**
 * Polls [probe] until it succeeds or [timeoutMillis] elapses, backing off between attempts.
 *
 * Backoff schedule (milliseconds): 200, 400, 800, 1500, then capped at 1500. Each sleep is
 * clamped to the time remaining before the deadline, so we never overshoot. Uses [delay] so
 * that cancellation of the enclosing coroutine returns within one scheduler tick instead of
 * blocking the calling thread.
 *
 * [probe] receives the remaining time before the deadline (millis), suitable for use as a
 * per-attempt I/O timeout.
 */
internal suspend fun waitForStatus(
    timeoutMillis: Long,
    probe: suspend (remainingMillis: Long) -> Unit,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    val backoff = backoffSequence()
    var lastError: Throwable? = null
    while (true) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) break
        try {
            probe(remaining.coerceAtLeast(1L))
            return
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            lastError = error
        }
        val afterAttempt = deadline - System.currentTimeMillis()
        if (afterAttempt <= 0L) break
        val sleep = minOf(backoff.next(), afterAttempt)
        delay(sleep)
    }
    throw IllegalStateException("FixThis sidekick did not connect before timeout", lastError)
}

private fun backoffSequence(): Iterator<Long> = iterator {
    var current = 200L
    while (true) {
        yield(current)
        current = (current * 2).coerceAtMost(1_500L)
    }
}
