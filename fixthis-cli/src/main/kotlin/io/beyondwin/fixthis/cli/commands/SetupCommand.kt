package io.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.beyondwin.fixthis.cli.BridgeClient
import io.beyondwin.fixthis.cli.fixThisJson
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

class SetupCommand : CoreCliktCommand(name = "setup") {
    private val packageName by option("--package", help = "Android application id for generated MCP config")
    private val projectDir by option("--project-dir", help = "Project root containing .fixthis/project.json").default(".")
    private val write by option("--write", help = "Write MCP config to agent settings files").flag(default = false)
    private val dryRun by option("--dry-run", help = "Print planned writes without modifying files").flag(default = false)
    private val target by option("--target", help = "Agent config target").choice("codex", "claude", "all").default("all")
    private val serverName by option("--server-name", help = "MCP server name to write").default("fixthis")

    override fun run() {
        val root = File(projectDir).canonicalFile
        val client = BridgeClient(projectRoot = root)
        val resolvedPackage = failAsCliError { client.resolvePackageName(packageName) }
        if (!write) {
            val config = buildMcpClientConfig(resolvedPackage, root)
            echo(fixThisJson.encodeToString(config))
            return
        }

        val validServerName = validateMcpServerName(serverName)
        val sdk = AndroidSdkLocator.find()
        val executable = McpExecutableLocator.find()
        if (sdk == null) {
            echo(
                "Warning: Android SDK not found; writing MCP config without ANDROID_HOME. " +
                    "Run `fixthis doctor --package $resolvedPackage --project-dir ${root.absolutePath}` next.",
                err = true,
            )
        }
        if (executable == null) {
            echo(
                "Warning: fixthis-mcp executable not found; writing MCP config with `fixthis mcp`. " +
                    "Ensure `fixthis` is on PATH or run `./gradlew :fixthis-mcp:installDist`.",
                err = true,
            )
        }

        val entry = buildMcpConfigEntry(
            resolvedPackage = resolvedPackage,
            root = root,
            serverName = validServerName,
            sdk = sdk,
            executable = executable,
        )
        val plans = buildWritePlans(selectedWriters(target), root, entry)
        plans.forEach { plan ->
            applyWritePlan(plan, dryRun)
        }
    }

    private fun selectedWriters(target: String): List<AgentConfigWriter> =
        when (target) {
            "codex" -> listOf(CodexConfigWriter())
            "claude" -> listOf(ClaudeConfigWriter())
            else -> listOf(CodexConfigWriter(), ClaudeConfigWriter())
        }

    private fun buildWritePlans(
        writers: List<AgentConfigWriter>,
        projectRoot: File,
        entry: McpConfigEntry,
    ): List<AgentConfigWritePlan> =
        writers.map { writer ->
            val configFile = writer.configFile(projectRoot)
            val merged = try {
                val current = configFile.takeIf { it.isFile }?.readText()
                writer.merge(current, entry)
            } catch (e: Exception) {
                throw CliktError(
                    "Could not merge ${writer.name} MCP config at ${configFile.absolutePath}: ${e.message}",
                    cause = e,
                )
            }
            AgentConfigWritePlan(writer.name, configFile, merged)
        }

    private fun applyWritePlan(plan: AgentConfigWritePlan, dryRun: Boolean) {
        val configFile = plan.configFile
        val merged = plan.content
        if (dryRun) {
            echo("Target: ${plan.writerName}")
            echo("Path: ${configFile.absolutePath}")
            echo(merged.trimEnd())
            return
        }
        try {
            AtomicConfigFileWriter.write(configFile, merged)
        } catch (_: Exception) {
            throw CliktError("Could not write ${plan.writerName} MCP config at ${configFile.absolutePath}.")
        }
        echo("Wrote ${plan.writerName} MCP config: ${configFile.absolutePath}")
    }

    private data class AgentConfigWritePlan(
        val writerName: String,
        val configFile: File,
        val content: String,
    )
}

internal fun buildMcpClientConfig(resolvedPackage: String, root: File): JsonObject =
    buildJsonObject {
        put("command", "fixthis")
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

internal fun buildMcpConfigEntry(
    resolvedPackage: String,
    root: File,
    serverName: String,
    sdk: AndroidSdkLocator.SdkLocation?,
    executable: File?,
): McpConfigEntry {
    val command = executable?.absolutePath ?: "fixthis"
    val args = if (executable == null) {
        listOf("mcp", "--package", resolvedPackage, "--project-dir", root.absolutePath)
    } else {
        listOf("--package", resolvedPackage, "--project-dir", root.absolutePath)
    }
    return McpConfigEntry(
        serverName = serverName,
        command = command,
        args = args,
        env = buildMap {
            sdk?.let { put("ANDROID_HOME", it.home.absolutePath) }
        },
    )
}

class McpCommand : CoreCliktCommand(name = "mcp") {
    private val packageName by option("--package", help = "Android application id")
    private val projectDir by option("--project-dir", help = "Project root containing .fixthis/project.json").default(".")

    override fun run() {
        val executable = McpExecutableLocator.find()
            ?: throw com.github.ajalt.clikt.core.CliktError(
                "Could not find fixthis-mcp executable. Run :fixthis-mcp:installDist or add fixthis-mcp to PATH.",
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
                    ?.resolve("fixthis-mcp")
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
                it.resolve("fixthis-mcp")
                    .resolve("build")
                    .resolve("install")
                    .resolve("fixthis-mcp")
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
            "fixthis-mcp.bat"
        } else {
            "fixthis-mcp"
        }

    private fun File.ancestors(): Sequence<File> =
        generateSequence(if (isDirectory) this else parentFile) { it.parentFile }
}
