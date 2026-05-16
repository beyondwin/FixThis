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
import io.github.beyondwin.fixthis.cli.ExitCode
import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

internal object SetupRunResults {
    val applied = ThreadLocal.withInitial { mutableListOf<InstallAgentJsonReport.Applied>() }
    val skipped = ThreadLocal.withInitial { mutableListOf<InstallAgentJsonReport.Skipped>() }
    val errors = ThreadLocal.withInitial { mutableListOf<InstallAgentJsonReport.ErrorEntry>() }
    fun reset() {
        applied.get().clear()
        skipped.get().clear()
        errors.get().clear()
    }
}

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

    private fun runWritePlansAtomic(
        plans: List<AgentConfigWritePlan>,
        move: (java.nio.file.Path, java.nio.file.Path, Array<out java.nio.file.CopyOption>) -> java.nio.file.Path = { source, target, options ->
            java.nio.file.Files.move(source, target, *options)
        },
        forceFile: (java.nio.file.Path) -> Unit = AtomicConfigFileWriter::forceRegularFile,
        forceDirectory: (java.nio.file.Path) -> Unit = AtomicConfigFileWriter::forceDirectoryBestEffort,
        copyForRollback: (File, File) -> Unit = { source, destination ->
            source.copyTo(destination, overwrite = true)
        },
        emit: (String) -> Unit = ::echo,
    ) {
        // Phase 1: stage all writes to <configFile>.fixthis-staging.
        val staged = mutableListOf<Pair<AgentConfigWritePlan, File>>()
        try {
            plans.forEach { plan ->
                val stagingFile = File(plan.configFile.absolutePath + ".fixthis-staging")
                stagingFile.parentFile?.mkdirs()
                stagingFile.writeText(plan.content)
                // P2 durability: fsync staging file before any commit can read it.
                forceFile(stagingFile.toPath())
                staged += plan to stagingFile
            }
        } catch (e: Exception) {
            staged.forEach { (_, f) -> if (f.exists()) f.delete() }
            val appliedNames = staged.joinToString { it.first.writerName }
            throw CliktError(
                "Could not stage MCP config writes (${e.message}). Staged so far: ${appliedNames.ifEmpty { "none" }} (cleaned up).",
                cause = e,
            )
        }

        // Phase 1.5: snapshot existing targets into rollback files BEFORE first move.
        // Wrapped to bound the failure surface — copyTo can fail mid-loop on permissions,
        // disk-full, or symlink edge cases (impl-details §3).
        val rollbacks = mutableMapOf<AgentConfigWritePlan, File?>()
        try {
            staged.forEach { (plan, _) ->
                rollbacks[plan] = if (plan.configFile.exists()) {
                    val rb = File(plan.configFile.absolutePath + ".fixthis-rollback")
                    copyForRollback(plan.configFile, rb)
                    rb
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            rollbacks.values.filterNotNull().forEach { if (it.exists()) it.delete() }
            staged.forEach { (_, f) -> if (f.exists()) f.delete() }
            throw CliktError(
                "Could not prepare two-phase MCP config commit (rollback snapshot failed: ${e.message}).",
                cause = e,
            )
        }

        // Phase 2: commit (ATOMIC_MOVE with fallback to copy+delete).
        val committed = mutableListOf<AgentConfigWritePlan>()
        try {
            staged.forEach { (plan, stagingFile) ->
                try {
                    move(
                        stagingFile.toPath(),
                        plan.configFile.toPath(),
                        arrayOf(
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        ),
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.copy(
                        stagingFile.toPath(),
                        plan.configFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    java.nio.file.Files.deleteIfExists(stagingFile.toPath())
                }
                // P2 durability: fsync parent dir so the rename is persisted.
                plan.configFile.toPath().parent?.let { forceDirectory(it) }
                committed += plan
                SetupRunResults.applied.get() += InstallAgentJsonReport.Applied(
                    target = plan.writerName,
                    path = plan.configFile.absolutePath,
                    scope = plan.scope,
                )
                emit("Wrote ${plan.writerName} MCP config (${plan.scope}): ${plan.configFile.absolutePath}")
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
                cause = e,
            )
        }
    }

    internal fun runWritePlansAtomicForTest(
        plans: List<Pair<Triple<String, String, File>, String>>,
        move: (java.nio.file.Path, java.nio.file.Path, Array<out java.nio.file.CopyOption>) -> java.nio.file.Path = { source, target, options ->
            java.nio.file.Files.move(source, target, *options)
        },
        forceFile: (java.nio.file.Path) -> Unit = AtomicConfigFileWriter::forceRegularFile,
        forceDirectory: (java.nio.file.Path) -> Unit = AtomicConfigFileWriter::forceDirectoryBestEffort,
        copyForRollback: (File, File) -> Unit = { source, destination ->
            source.copyTo(destination, overwrite = true)
        },
        emit: (String) -> Unit = { /* no-op: tests do not need user-facing echo */ },
    ) {
        val agentPlans = plans.map { (meta, content) ->
            AgentConfigWritePlan(
                writerName = meta.first,
                scope = meta.second,
                configFile = meta.third,
                content = content,
            )
        }
        runWritePlansAtomic(agentPlans, move, forceFile, forceDirectory, copyForRollback, emit)
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
        } catch (e: Exception) {
            throw CliktError(
                "Could not write ${plan.writerName} MCP config at ${configFile.absolutePath}.",
                cause = e,
            )
        }
        SetupRunResults.applied.get() += InstallAgentJsonReport.Applied(
            target = plan.writerName,
            path = configFile.absolutePath,
            scope = plan.scope,
        )
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
    private val allowGlobal by option(
        "--allow-global",
        help = "Permit writes to global config files (~/.codex/config.toml) even outside an Android project",
    ).flag(default = false)
    private val json by option(
        "--json",
        help = "Emit a structured JSON report on stdout",
    ).flag(default = false)

    override fun run() {
        SetupRunResults.reset()
        val root = File(projectDir).canonicalFile
        val decision = GlobalScopeGuard.decide(root, allowGlobal = allowGlobal)

        // Filter target if guard denies global writes (only codex is global).
        val effectiveTarget: String = when {
            decision == GlobalScopeGuard.Decision.PROCEED -> target
            target == "codex" -> "none"
            target == "all" -> "claude"
            else -> target
        }

        val earlySkipped: List<InstallAgentJsonReport.Skipped> =
            if (decision == GlobalScopeGuard.Decision.SKIP_GLOBAL_WRITES &&
                (target == "codex" || target == "all")
            ) {
                listOf(
                    InstallAgentJsonReport.Skipped(
                        target = "codex",
                        reason = "no-android-context",
                        fix = "Re-run from an Android project root, or pass --allow-global.",
                        readiness = FirstRunReadinessCatalog.configRecoverable(
                            cause = "Codex global config was skipped outside an Android project.",
                        ),
                    ),
                )
            } else {
                emptyList()
            }

        if (effectiveTarget == "none") {
            if (json) {
                echo(
                    InstallAgentJsonReport.render(
                        applied = emptyList(),
                        skipped = earlySkipped,
                        errors = emptyList(),
                        next = listOf("cd <android-project-root>", "fixthis install-agent --project-dir ."),
                    ),
                )
            } else {
                earlySkipped.forEach { echo("Skipped ${it.target}: ${it.reason}. ${it.fix}") }
            }
            throw CliktError(
                "install-agent aborted: no Android project detected",
                statusCode = ExitCode.PARTIAL.value,
            )
        }

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
                add(effectiveTarget)
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

        if (json) {
            val skippedAll = SetupRunResults.skipped.get() + earlySkipped
            val applied = SetupRunResults.applied.get()
            val errors = SetupRunResults.errors.get()
            echo(
                InstallAgentJsonReport.render(
                    applied = applied,
                    skipped = skippedAll,
                    errors = errors,
                    next = listOf(
                        "./gradlew fixthisSetup",
                        "fixthis doctor --project-dir ${root.absolutePath} --json",
                        "Restart Claude Code / Codex to reload MCP config",
                    ),
                ),
            )
        }

        val accumulated = SetupRunResults.skipped.get() + earlySkipped
        val hasSkipped = accumulated.isNotEmpty()
        val hasErrors = SetupRunResults.errors.get().isNotEmpty()
        if (hasErrors) {
            throw CliktError("install-agent failed", statusCode = ExitCode.INTERNAL_ERROR.value)
        }
        if (hasSkipped) {
            throw CliktError(
                "install-agent completed with skipped targets",
                statusCode = ExitCode.PARTIAL.value,
            )
        }
        // OK path: exit 0 implicit
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
