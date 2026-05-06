package io.github.pointpatch.cli.commands

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import io.github.pointpatch.cli.Adb
import io.github.pointpatch.cli.BridgeClient
import java.io.File
import kotlinx.coroutines.runBlocking

class RunCommand : CoreCliktCommand(name = "run") {
    private val packageName by option("--package", help = "Android application id to launch")
    private val projectDir by option("--project-dir", help = "Project root containing .pointpatch/project.json").default(".")
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
        echo("Checking PointPatch sidekick")
        failAsCliError {
            runBlocking {
                waitForStatus(client, resolvedPackage, timeoutMillis)
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

    private suspend fun waitForStatus(client: BridgeClient, packageName: String, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastError: Throwable? = null
        while (System.currentTimeMillis() <= deadline) {
            try {
                val remainingMillis = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                client.request(packageName, "status", readTimeoutMillis = remainingMillis)
                return
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(500)
            }
        }
        throw IllegalStateException("PointPatch sidekick did not connect before timeout", lastError)
    }
}
