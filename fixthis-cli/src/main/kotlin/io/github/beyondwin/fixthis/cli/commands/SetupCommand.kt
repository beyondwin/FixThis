package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.github.beyondwin.fixthis.cli.BridgeClient
import io.github.beyondwin.fixthis.cli.DiagnosticContext
import io.github.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

class SetupCommand : CoreCliktCommand(name = "setup") {
    private val packageName by option("--package", help = "Android application id for generated MCP config")
    private val projectDir by option("--project-dir", help = "Project root containing .fixthis/project.json").default(".")
    private val write by option("--write", help = "Write MCP config to agent settings files").flag(default = false)
    private val dryRun by option("--dry-run", help = "Print planned writes without modifying files").flag(default = false)
    private val fullDiff by option(
        "--full-diff",
        help = "Disable the dry-run output byte budget (may leak surrounding context — avoid in agent logs)",
    ).flag(default = false)
    private val target by option("--target", help = "Agent config target").choice("codex", "claude", "all").default("all")
    private val serverName by option("--server-name", help = "MCP server name to write").default("fixthis")
    private val verbose by option("--verbose", "-v", help = "Print full stack trace on failure").flag(default = false)

    override fun run() {
        if (verbose) {
            DiagnosticContext.verbose = true
        }
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
                "Warning: fixthis-mcp executable not found.\n" +
                    "  The written config will use `fixthis mcp` as a command fallback.\n" +
                    "  MCP clients will fail to start FixThis unless `fixthis` is on PATH.\n" +
                    "  Fix: run `./gradlew :fixthis-mcp:installDist` then re-run `fixthis setup --write`.",
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
        if (dryRun) {
            plans.forEach { plan -> applyWritePlan(plan, dryRun = true, fullDiff = fullDiff) }
            return
        }
        runWritePlansAtomic(plans)
    }

    private fun runWritePlansAtomic(plans: List<AgentConfigWritePlan>) {
        // Phase 1: stage all writes to <configFile>.fixthis-staging.
        val staged = mutableListOf<Pair<AgentConfigWritePlan, File>>()
        try {
            plans.forEach { plan ->
                val stagingFile = File(plan.configFile.absolutePath + ".fixthis-staging")
                stagingFile.parentFile?.mkdirs()
                stagingFile.writeText(plan.content)
                staged += plan to stagingFile
            }
        } catch (e: Exception) {
            staged.forEach { (_, f) -> if (f.exists()) f.delete() }
            val appliedNames = staged.joinToString { it.first.writerName }
            throw CliktError(
                "Could not stage MCP config writes (${e.message}). Staged so far: ${appliedNames.ifEmpty { "none" }} (cleaned up).",
            )
        }

        // Save rollback copies for any existing targets BEFORE first move.
        val rollbacks = mutableMapOf<AgentConfigWritePlan, File?>()
        staged.forEach { (plan, _) ->
            rollbacks[plan] = if (plan.configFile.exists()) {
                val rb = File(plan.configFile.absolutePath + ".fixthis-rollback")
                plan.configFile.copyTo(rb, overwrite = true)
                rb
            } else {
                null
            }
        }

        // Phase 2: commit (ATOMIC_MOVE with fallback to copy+delete).
        val committed = mutableListOf<AgentConfigWritePlan>()
        try {
            staged.forEach { (plan, stagingFile) ->
                try {
                    java.nio.file.Files.move(
                        stagingFile.toPath(),
                        plan.configFile.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.copy(
                        stagingFile.toPath(),
                        plan.configFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    java.nio.file.Files.delete(stagingFile.toPath())
                }
                committed += plan
                echo("Wrote ${plan.writerName} MCP config (${plan.scope}): ${plan.configFile.absolutePath}")
            }
            // Clean up rollback files on success.
            rollbacks.values.filterNotNull().forEach { it.delete() }
        } catch (e: Exception) {
            // Restore from rollback for any committed (already-moved) targets.
            committed.forEach { plan ->
                val rb = rollbacks[plan]
                if (rb != null && rb.exists()) {
                    rb.copyTo(plan.configFile, overwrite = true)
                } else {
                    plan.configFile.delete()
                }
            }
            // Clean up staging files that didn't move yet + rollback files.
            staged.forEach { (_, f) -> if (f.exists()) f.delete() }
            rollbacks.values.filterNotNull().forEach { if (it.exists()) it.delete() }
            val appliedNames = committed.joinToString { it.writerName }
            throw CliktError(
                "Atomic commit failed: ${e.message}. Applied so far: ${appliedNames.ifEmpty { "none" }} (rolled back).",
            )
        }
    }

    internal fun runWritePlansAtomicForTest(
        plans: List<Pair<Triple<String, String, File>, String>>,
    ) {
        val agentPlans = plans.map { (meta, content) ->
            AgentConfigWritePlan(
                writerName = meta.first,
                scope = meta.second,
                configFile = meta.third,
                content = content,
            )
        }
        runWritePlansAtomic(agentPlans)
    }

    private fun selectedWriters(target: String): List<AgentConfigWriter> = when (target) {
        "codex" -> listOf(CodexConfigWriter())
        "claude" -> listOf(ClaudeConfigWriter())
        else -> listOf(CodexConfigWriter(), ClaudeConfigWriter())
    }

    private fun buildWritePlans(
        writers: List<AgentConfigWriter>,
        projectRoot: File,
        entry: McpConfigEntry,
    ): List<AgentConfigWritePlan> = writers.map { writer ->
        val configFile = writer.configFile(projectRoot)
        val merged = try {
            val current = configFile.takeIf { it.isFile }?.readText()
            writer.merge(current, entry)
        } catch (e: Exception) {
            throw CliktError(
                renderMergeFailure(writer.name, configFile, e),
                cause = e,
            )
        }
        AgentConfigWritePlan(writer.name, writer.scope, configFile, merged)
    }

    private fun applyWritePlan(plan: AgentConfigWritePlan, dryRun: Boolean, fullDiff: Boolean = false) {
        val configFile = plan.configFile
        val merged = plan.content
        if (dryRun) {
            renderDryRunOutput(plan, fullDiff).forEach { echo(it) }
            return
        }
        try {
            AtomicConfigFileWriter.write(configFile, merged)
        } catch (_: Exception) {
            throw CliktError("Could not write ${plan.writerName} MCP config at ${configFile.absolutePath}.")
        }
        echo("Wrote ${plan.writerName} MCP config (${plan.scope}): ${configFile.absolutePath}")
    }

    internal fun applyWritePlanForTest(
        writerName: String,
        scope: String,
        configFile: File,
        content: String,
        dryRun: Boolean,
        fullDiff: Boolean = false,
    ) {
        val plan = AgentConfigWritePlan(writerName, scope, configFile, content)
        if (dryRun) {
            renderDryRunOutput(plan, fullDiff).forEach { println(it) }
            return
        }
        AtomicConfigFileWriter.write(configFile, content)
        println("Wrote ${plan.writerName} MCP config (${plan.scope}): ${configFile.absolutePath}")
    }

    private fun renderDryRunOutput(plan: AgentConfigWritePlan, fullDiff: Boolean): List<String> {
        val configFile = plan.configFile
        val before = if (configFile.isFile) configFile.readText() else ""
        val format = when {
            configFile.name.endsWith(".toml") -> DryRunDiff.Format.TOML
            else -> DryRunDiff.Format.JSON
        }
        val budget = if (fullDiff) Int.MAX_VALUE else 4096
        return listOf(
            "Target: ${plan.writerName} (${plan.scope})",
            "Path: ${configFile.absolutePath}",
            DryRunDiff.render(before = before, after = plan.content, format = format, byteBudget = budget),
        )
    }

    private data class AgentConfigWritePlan(
        val writerName: String,
        val scope: String,
        val configFile: File,
        val content: String,
    )
}

class InitCommand : CoreCliktCommand(name = "init") {
    private val packageName by option("--package", help = "Android application id for generated MCP config")
    private val projectDir by option("--project-dir", help = "Android project root").default(".")
    private val agent by option(
        "--agent",
        help = "Write project-scoped agent handoff files under .fixthis",
    ).flag(default = false)
    private val applyGradlePlugin by option(
        "--apply-gradle-plugin",
        help = "Apply the FixThis Gradle plugin to the detected Android app module",
    ).flag(default = false)
    private val pluginVersion by option(
        "--plugin-version",
        help = "FixThis Gradle plugin version to apply",
    ).default(GradlePluginInstaller.DefaultPluginVersion)
    private val dryRun by option("--dry-run", help = "Print planned writes without modifying files")
        .flag(default = false)
    private val target by option("--target", help = "Agent config target")
        .choice("codex", "claude", "all")
        .default("all")
    private val serverName by option("--server-name", help = "MCP server name to write").default("fixthis")
    private val verbose by option("--verbose", "-v", help = "Print full stack trace on failure").flag(default = false)

    override fun run() {
        SetupCommand().parse(
            buildList {
                packageName?.let {
                    add("--package")
                    add(it)
                }
                add("--project-dir")
                add(projectDir)
                add("--write")
                add("--target")
                add(target)
                add("--server-name")
                add(serverName)
                if (dryRun) {
                    add("--dry-run")
                }
                if (verbose) {
                    add("--verbose")
                }
            },
        )
        if (agent || applyGradlePlugin) {
            val root = File(projectDir).canonicalFile
            val resolvedPackage = failAsCliError {
                BridgeClient(projectRoot = root).resolvePackageName(packageName)
            }
            if (applyGradlePlugin) {
                GradlePluginInstaller.apply(
                    projectRoot = root,
                    packageName = resolvedPackage,
                    pluginVersion = pluginVersion,
                    dryRun = dryRun,
                    echo = ::echo,
                )
            }
            if (agent) {
                AgentSetupFiles.write(
                    projectRoot = root,
                    packageName = resolvedPackage,
                    serverName = validateMcpServerName(serverName),
                    dryRun = dryRun,
                    echo = ::echo,
                )
            }
        }
        echo("")
        echo("Next for agents:")
        echo("  1. Restart Claude Code or Codex so the MCP config is reloaded.")
        echo("  2. Run `fixthis doctor --project-dir ${File(projectDir).canonicalFile.absolutePath}`.")
        echo("  3. Open the console with `fixthis_open_feedback_console`.")
    }
}

class InstallAgentCommand : CoreCliktCommand(name = "install-agent") {
    private val packageName by option("--package", help = "Android application id for generated MCP config")
    private val projectDir by option("--project-dir", help = "Android project root").default(".")
    private val dryRun by option("--dry-run", help = "Print planned writes without modifying files")
        .flag(default = false)
    private val target by option("--target", help = "Agent config target")
        .choice("codex", "claude", "all")
        .default("all")
    private val serverName by option("--server-name", help = "MCP server name to write").default("fixthis")
    private val skipGradlePlugin by option(
        "--skip-gradle-plugin",
        help = "Do not modify the detected Android app module build file",
    ).flag(default = false)
    private val pluginVersion by option(
        "--plugin-version",
        help = "FixThis Gradle plugin version to apply",
    ).default(GradlePluginInstaller.DefaultPluginVersion)
    private val verbose by option("--verbose", "-v", help = "Print full stack trace on failure").flag(default = false)

    override fun run() {
        InitCommand().parse(
            buildList {
                add("--agent")
                if (!skipGradlePlugin) {
                    add("--apply-gradle-plugin")
                    add("--plugin-version")
                    add(pluginVersion)
                }
                packageName?.let {
                    add("--package")
                    add(it)
                }
                add("--project-dir")
                add(projectDir)
                add("--target")
                add(target)
                add("--server-name")
                add(serverName)
                if (dryRun) {
                    add("--dry-run")
                }
                if (verbose) {
                    add("--verbose")
                }
            },
        )
    }
}

internal fun buildMcpClientConfig(resolvedPackage: String, root: File): JsonObject = buildJsonObject {
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
    ): File? = sequence {
        yieldAll(siblingInstallCandidates(classpath))
        yieldAll(projectInstallCandidates(classpath, workingDirectory))
        yieldAll(pathCandidates(env))
    }
        .filterNotNull()
        .firstOrNull { it.isFile && it.canExecute() }

    private fun siblingInstallCandidates(classpath: String): Sequence<File> = classpath
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

    private fun projectInstallCandidates(classpath: String, workingDirectory: File): Sequence<File> = sequence {
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

    private fun pathCandidates(env: Map<String, String>): Sequence<File> = env["PATH"]
        .orEmpty()
        .split(File.pathSeparator)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { File(it).resolve(executableName()) }

    private fun executableName(): String = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        "fixthis-mcp.bat"
    } else {
        "fixthis-mcp"
    }

    private fun File.ancestors(): Sequence<File> = generateSequence(if (isDirectory) this else parentFile) { it.parentFile }
}
