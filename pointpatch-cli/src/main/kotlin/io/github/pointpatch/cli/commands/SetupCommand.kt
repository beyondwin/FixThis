package io.github.pointpatch.cli.commands

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.pointpatch.cli.BridgeClient
import io.github.pointpatch.cli.pointPatchJson
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

class SetupCommand : CoreCliktCommand(name = "setup") {
    private val packageName by option("--package", help = "Android application id for generated MCP config")
    private val projectDir by option("--project-dir", help = "Project root containing .pointpatch/project.json").default(".")

    override fun run() {
        val root = File(projectDir).canonicalFile
        val client = BridgeClient(projectRoot = root)
        val resolvedPackage = failAsCliError { client.resolvePackageName(packageName) }
        val config = buildMcpClientConfig(resolvedPackage, root)
        echo(pointPatchJson.encodeToString(config))
    }
}

internal fun buildMcpClientConfig(resolvedPackage: String, root: File): JsonObject =
    buildJsonObject {
        put("command", "pointpatch")
        put(
            "args",
            buildJsonArray {
                add(JsonPrimitive("mcp"))
                add(JsonPrimitive("--package"))
                add(JsonPrimitive(resolvedPackage))
                add(JsonPrimitive("--project-dir"))
                add(JsonPrimitive(root.absolutePath))
            },
        )
        put("packageName", resolvedPackage)
        put("projectRoot", root.absolutePath)
    }

class McpCommand : CoreCliktCommand(name = "mcp") {
    private val packageName by option("--package", help = "Android application id")
    private val projectDir by option("--project-dir", help = "Project root containing .pointpatch/project.json").default(".")

    override fun run() {
        val executable = McpExecutableLocator.find()
            ?: throw com.github.ajalt.clikt.core.CliktError(
                "Could not find pointpatch-mcp executable. Run :pointpatch-mcp:installDist or add pointpatch-mcp to PATH.",
            )
        val command = buildList {
            add(executable.absolutePath)
            packageName?.let {
                add("--package")
                add(it)
            }
            add("--project-dir")
            add(projectDir)
        }
        val exitCode = ProcessBuilder(command)
            .inheritIO()
            .start()
            .waitFor()
        exitProcess(exitCode)
    }
}

internal object McpExecutableLocator {
    fun find(
        classpath: String = System.getProperty("java.class.path").orEmpty(),
        env: Map<String, String> = System.getenv(),
        workingDirectory: File = File("").absoluteFile,
    ): File? =
        sequence {
            yieldAll(siblingInstallCandidates(classpath))
            yieldAll(projectInstallCandidates(classpath, workingDirectory))
            yieldAll(pathCandidates(env))
        }
            .filterNotNull()
            .firstOrNull { it.isFile && it.canExecute() }

    private fun siblingInstallCandidates(classpath: String): Sequence<File> =
        classpath
            .split(File.pathSeparator)
            .asSequence()
            .mapNotNull { entry ->
                val classpathEntry = File(entry)
                val installRoot = classpathEntry.parentFile
                    ?.takeIf { it.name == "lib" }
                    ?.parentFile
                    ?: return@mapNotNull null
                installRoot.parentFile
                    ?.resolve("pointpatch-mcp")
                    ?.resolve("bin")
                    ?.resolve(executableName())
            }

    private fun projectInstallCandidates(classpath: String, workingDirectory: File): Sequence<File> =
        sequence {
            classpath
                .split(File.pathSeparator)
                .asSequence()
                .filter { it.isNotBlank() }
                .forEach { yield(File(it).absoluteFile) }
            yield(workingDirectory.absoluteFile)
        }
            .flatMap { it.ancestors() }
            .distinctBy { it.absolutePath }
            .map {
                it.resolve("pointpatch-mcp")
                    .resolve("build")
                    .resolve("install")
                    .resolve("pointpatch-mcp")
                    .resolve("bin")
                    .resolve(executableName())
            }

    private fun pathCandidates(env: Map<String, String>): Sequence<File> =
        env["PATH"]
            .orEmpty()
            .split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it).resolve(executableName()) }

    private fun executableName(): String =
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            "pointpatch-mcp.bat"
        } else {
            "pointpatch-mcp"
        }

    private fun File.ancestors(): Sequence<File> =
        generateSequence(if (isDirectory) this else parentFile) { it.parentFile }
}
