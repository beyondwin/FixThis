package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreCliktCommand
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

class SetupCommand : CoreCliktCommand(name = "setup") {
    private val packageName by option("--package", help = "Android application id for generated MCP config")
    private val projectDir by option("--project-dir", help = "Project root containing .fixthis/project.json").default(".")
    private val write by option("--write", help = "Write MCP config to agent settings files").flag(default = false)
    private val dryRun by option("--dry-run", help = "Print planned writes without modifying files").flag(default = false)
    private val fullDiff by option(
        "--full-diff",
        help = "Disable the dry-run output byte budget (may leak surrounding context — avoid in agent logs)",
    ).flag(default = false)
    private val target by option("--target", help = "Agent config target")
        .choice("codex", "claude", "cursor", "local", "all").default("all")
    private val serverName by option("--server-name", help = "MCP server name to write").default("fixthis")
    private val verbose by option("--verbose", "-v", help = "Print full stack trace on failure").flag(default = false)

    override fun run() {
        if (verbose) {
            DiagnosticContext.verbose = true
        }
        val root = File(projectDir).canonicalFile
        if (!write) {
            val client = BridgeClient(projectRoot = root)
            val resolvedPackage = failAsCliError { client.resolvePackageName(packageName) }
            val config = buildMcpClientConfig(resolvedPackage, root)
            echo(fixThisJson.encodeToString(config))
            return
        }

        val request = SetupRequest(
            packageName = packageName,
            projectRoot = root,
            target = target,
            serverName = serverName,
            write = true,
            dryRun = dryRun,
            fullDiff = fullDiff,
            verbose = verbose,
        )
        SetupService(
            report = SetupReport(),
            emit = ::echo,
            emitWarning = { System.err.println(it) },
        ).writeConfigs(request)
    }
}

private const val DRY_RUN_BYTE_BUDGET = 4096

internal fun renderDryRunOutput(plan: SetupWritePlan, fullDiff: Boolean): List<String> {
    val configFile = plan.configFile
    val before = if (configFile.isFile) configFile.readText() else ""
    val format = when {
        configFile.name.endsWith(".toml") -> DryRunDiff.Format.TOML
        else -> DryRunDiff.Format.JSON
    }
    val budget = if (fullDiff) Int.MAX_VALUE else DRY_RUN_BYTE_BUDGET
    return listOf(
        "Target: ${plan.writerName} (${plan.scope})",
        "Path: ${configFile.absolutePath}",
        DryRunDiff.render(before = before, after = plan.content, format = format, byteBudget = budget),
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
        .choice("codex", "claude", "cursor", "local", "all")
        .default("all")
    private val serverName by option("--server-name", help = "MCP server name to write").default("fixthis")
    private val verbose by option("--verbose", "-v", help = "Print full stack trace on failure").flag(default = false)

    override fun run() {
        if (verbose) {
            DiagnosticContext.verbose = true
        }
        val root = File(projectDir).canonicalFile
        SetupService(
            report = SetupReport(),
            emit = ::echo,
            emitWarning = { System.err.println(it) },
        ).installAgent(
            InstallRequest(
                packageName = packageName,
                projectRoot = root,
                target = target,
                serverName = serverName,
                agent = agent,
                applyGradlePlugin = applyGradlePlugin,
                pluginVersion = pluginVersion,
                dryRun = dryRun,
                verbose = verbose,
            ),
        )
    }
}

private fun installAgentTopLevelReadiness(
    root: File,
    skipped: List<InstallAgentJsonReport.Skipped>,
    errors: List<InstallAgentJsonReport.ErrorEntry>,
) = when {
    errors.isNotEmpty() -> errors.first().readiness ?: FirstRunReadinessCatalog.configRecoverable(
        cause = "FixThis setup hit an error; inspect the error entries and rerun setup.",
    ).copy(
        verify = "fixthis install-agent --project-dir ${root.absolutePath} --target all --dry-run",
        fix = "Inspect the dry-run output, fix the reported setup error, then rerun install-agent.",
        nextAction = "fixthis install-agent --project-dir ${root.absolutePath} --target all --dry-run",
    )
    skipped.isNotEmpty() -> skipped.first().readiness ?: FirstRunReadinessCatalog.configRecoverable(
        cause = "FixThis setup completed with skipped targets.",
    ).copy(
        verify = "fixthis install-agent --project-dir ${root.absolutePath} --target all --dry-run",
        fix = "Review skipped targets and rerun setup with an explicit --target or --allow-global when safe.",
        nextAction = "fixthis install-agent --project-dir ${root.absolutePath} --target all --dry-run",
    )
    else -> FirstRunReadinessCatalog.configRecoverable(
        cause = "FixThis setup completed; verify the debug app before opening the console.",
    ).copy(
        verify = "fixthis doctor --project-dir ${root.absolutePath} --json",
        fix = "Run doctor, restart Claude Code or Codex if MCP config changed, then open the feedback console.",
        nextAction = "fixthis doctor --project-dir ${root.absolutePath} --json",
    )
}

class InstallAgentCommand : CoreCliktCommand(name = "install-agent") {
    private val packageName by option("--package", help = "Android application id for generated MCP config")
    private val projectDir by option("--project-dir", help = "Android project root").default(".")
    private val dryRun by option("--dry-run", help = "Print planned writes without modifying files")
        .flag(default = false)
    private val target by option("--target", help = "Agent config target")
        .choice("codex", "claude", "cursor", "local", "all")
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
    private val verify by option(
        "--verify",
        help = "After setup, run doctor checks and emit a unified agent setup report in --json mode",
    ).flag(default = false)

    override fun run() {
        if (verbose) {
            DiagnosticContext.verbose = true
        }
        val root = File(projectDir).canonicalFile
        val decision = GlobalScopeGuard.decide(root, allowGlobal = allowGlobal)

        // Filter target if guard denies global writes (only codex is global).
        // "all" falls back to "local" — project-local writers (claude, cursor) still
        // get written; only the global codex target is dropped.
        val effectiveTarget: String = when {
            decision == GlobalScopeGuard.Decision.PROCEED -> target
            target == "codex" -> "none"
            target == "all" -> "local"
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

        val report = SetupReport()
        report.skipped += earlySkipped
        // In JSON mode human-facing stdout echoes are suppressed by injecting a no-op sink, so that
        // only the JSON report lands on stdout. Warnings, however, ALWAYS route to stderr regardless
        // of json: the legacy code only rebound System.out (the captured discard buffer), so the two
        // setup warnings still reached the real stderr even in --json mode. Suppressing them here was
        // a behavior regression. They go straight to System.err (mirroring Main.printCliktError) so
        // they never contaminate the JSON report on stdout — clikt's terminal collapses err=true onto
        // stdout in non-TTY/captured contexts, which would pollute the report.
        val emit: (String) -> Unit = if (json) { _ -> } else ::echo
        val emitWarning: (String) -> Unit = { msg -> System.err.println(msg) }
        SetupService(
            report = report,
            emit = emit,
            emitWarning = emitWarning,
        ).installAgent(
            InstallRequest(
                packageName = packageName,
                projectRoot = root,
                target = effectiveTarget,
                serverName = serverName,
                agent = true,
                applyGradlePlugin = !skipGradlePlugin,
                pluginVersion = pluginVersion,
                dryRun = dryRun,
                verbose = verbose,
            ),
        )

        val setupSnapshot = AgentSetupSnapshot(
            applied = report.applied,
            skipped = report.skipped,
            errors = report.errors,
            mcpConfigChanged = report.applied.any {
                it.target == "claude" || it.target == "codex" || it.target == "cursor"
            },
        )
        val doctorReport = if (verify && !dryRun && report.errors.isEmpty()) {
            DoctorService().run(packageName = packageName, projectRoot = root)
        } else {
            null
        }
        val verifyReport = if (verify) {
            AgentSetupVerificationService().buildReport(
                AgentSetupVerificationRequest(
                    projectRoot = root,
                    target = effectiveTarget,
                    setup = setupSnapshot,
                    doctorReport = doctorReport,
                    dryRun = dryRun,
                ),
            )
        } else {
            null
        }

        if (json) {
            if (verifyReport != null) {
                echo(AgentSetupVerificationJsonReport.render(verifyReport))
            } else {
                val skippedAll = report.skipped
                val applied = report.applied
                val errors = report.errors
                val reportReadiness = installAgentTopLevelReadiness(root, skippedAll, errors)
                val restartRequired = applied.any { it.target == "claude" || it.target == "codex" }
                echo(
                    InstallAgentJsonReport.render(
                        applied = applied,
                        skipped = skippedAll,
                        errors = errors,
                        next = listOf(
                            "fixthis doctor --project-dir ${root.absolutePath} --json",
                            "# Restart Claude Code / Codex to reload MCP config",
                            "fixthis_open_feedback_console",
                        ),
                        readiness = reportReadiness,
                        restartRequired = restartRequired,
                    ),
                )
            }
        }

        verifyReport?.let { unified ->
            val exitCode = AgentSetupVerificationService().exitCodeFor(unified)
            if (exitCode != ExitCode.OK) {
                throw CliktError("install-agent verification requires follow-up", statusCode = exitCode.value)
            }
            return
        }

        val hasSkipped = report.skipped.isNotEmpty()
        val hasErrors = report.errors.isNotEmpty()
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
    val stableExecutable = executable?.stableHomebrewExecutable()
    val command = when {
        stableExecutable != null -> stableExecutable.absolutePath
        executable == null || executable.isVersionedHomebrewCellarExecutable() -> "fixthis"
        else -> executable.absolutePath
    }
    val args = when (command) {
        "fixthis" -> listOf("mcp", "--package", resolvedPackage, "--project-dir", root.absolutePath)
        else -> listOf("--package", resolvedPackage, "--project-dir", root.absolutePath)
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

private fun File.isVersionedHomebrewCellarExecutable(): Boolean {
    val normalized = absolutePath.replace(File.separatorChar, '/')
    return Regex("""/Cellar/fixthis/[^/]+/""").containsMatchIn(normalized)
}

private fun File.stableHomebrewExecutable(): File? {
    val normalized = absolutePath.replace(File.separatorChar, '/')
    val prefix = if (isVersionedHomebrewCellarExecutable()) {
        normalized.substringBefore("/Cellar/fixthis/", missingDelimiterValue = "")
    } else {
        ""
    }
    return prefix
        .takeIf { it.isNotBlank() }
        ?.let { File(it).resolve("bin").resolve(name) }
        ?.takeIf { it.isFile && it.canExecute() }
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
