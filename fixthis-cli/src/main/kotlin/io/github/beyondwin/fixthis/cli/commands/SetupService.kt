package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.BridgeClient

/**
 * Pure domain action behind `fixthis setup --write` / `--dry-run`.
 *
 * Side effects (file writes) stay here, but the human-facing echo and the accumulated
 * report are injected collaborators so callers can capture both. `SetupCommand.run()`
 * is a thin adapter over this service.
 */
internal class SetupService(
    private val report: SetupReport,
    private val emit: (String) -> Unit = {},
    private val emitWarning: (String) -> Unit = {},
) {
    fun writeConfigs(request: SetupRequest) {
        writeMcpConfigs(
            packageName = request.packageName,
            projectRoot = request.projectRoot,
            target = request.target,
            serverName = request.serverName,
            dryRun = request.dryRun,
            fullDiff = request.fullDiff,
        )
    }

    /**
     * Consolidated `fixthis init` / `fixthis install-agent` action.
     *
     * Collaborators are called directly — no argv re-parse and no global state. All human-facing
     * output goes through the injected [emit] (callers pass a no-op in JSON mode instead of hijacking
     * `System.out`), and `applied` accumulates into the injected [report].
     *
     * Mirrors the legacy ordering: resolve package → gradle preflight → write MCP configs →
     * apply Gradle plugin → write agent files → emit the "Next for agents" trailer.
     */
    fun installAgent(request: InstallRequest) {
        val resolvedPackage = if (request.agent || request.applyGradlePlugin) {
            failAsCliError { BridgeClient(projectRoot = request.projectRoot).resolvePackageName(request.packageName) }
        } else {
            null
        }
        if (request.applyGradlePlugin) {
            GradlePluginInstaller.preflight(
                projectRoot = request.projectRoot,
                packageName = checkNotNull(resolvedPackage),
                pluginVersion = request.pluginVersion,
            )
        }
        writeMcpConfigs(
            packageName = resolvedPackage ?: request.packageName,
            projectRoot = request.projectRoot,
            target = request.target,
            serverName = request.serverName,
            dryRun = request.dryRun,
            fullDiff = false,
        )
        if (request.agent || request.applyGradlePlugin) {
            val packageForSetup = resolvedPackage ?: failAsCliError {
                BridgeClient(projectRoot = request.projectRoot).resolvePackageName(request.packageName)
            }
            if (request.applyGradlePlugin) {
                GradlePluginInstaller.apply(
                    projectRoot = request.projectRoot,
                    packageName = packageForSetup,
                    pluginVersion = request.pluginVersion,
                    dryRun = request.dryRun,
                    echo = emit,
                    onApplied = { report.applied += it },
                )
            }
            if (request.agent) {
                AgentSetupFiles.write(
                    projectRoot = request.projectRoot,
                    packageName = packageForSetup,
                    serverName = validateMcpServerName(request.serverName),
                    dryRun = request.dryRun,
                    echo = emit,
                )
            }
        }
        emit("")
        emit("Next for agents:")
        emit("  1. Restart Claude Code or Codex so the MCP config is reloaded.")
        emit("  2. Run `fixthis doctor --project-dir ${request.projectRoot.absolutePath}`.")
        emit("  3. Open the console with `fixthis_open_feedback_console`.")
    }

    @Suppress("LongParameterList")
    private fun writeMcpConfigs(
        packageName: String?,
        projectRoot: java.io.File,
        target: String,
        serverName: String,
        dryRun: Boolean,
        fullDiff: Boolean,
    ) {
        val client = BridgeClient(projectRoot = projectRoot)
        val resolvedPackage = failAsCliError { client.resolvePackageName(packageName) }
        val validServerName = validateMcpServerName(serverName)
        val sdk = AndroidSdkLocator.find()
        val executable = McpExecutableLocator.find()
        warnIfMissing(sdk, executable, resolvedPackage, projectRoot)

        val entry = buildMcpConfigEntry(
            resolvedPackage = resolvedPackage,
            root = projectRoot,
            serverName = validServerName,
            sdk = sdk,
            executable = executable,
        )
        val plans = SetupPlanner.buildWritePlans(
            SetupPlanner.selectedWriters(target),
            projectRoot,
            entry,
        )
        if (dryRun) {
            plans.forEach { plan -> renderDryRunOutput(plan, fullDiff).forEach(emit) }
            return
        }
        val committed = TwoPhaseConfigCommit(emit = emit).commit(plans)
        committed.forEach { plan ->
            report.applied += InstallAgentJsonReport.Applied(
                target = plan.writerName,
                path = plan.configFile.absolutePath,
                scope = plan.scope,
            )
        }
    }

    private fun warnIfMissing(
        sdk: AndroidSdkLocator.SdkLocation?,
        executable: java.io.File?,
        resolvedPackage: String,
        projectRoot: java.io.File,
    ) {
        if (sdk == null) {
            emitWarning(
                "Warning: Android SDK not found; writing MCP config without ANDROID_HOME. " +
                    "Run `fixthis doctor --package $resolvedPackage --project-dir ${projectRoot.absolutePath}` next.",
            )
        }
        if (executable == null) {
            emitWarning(
                "Warning: fixthis-mcp executable not found.\n" +
                    "  The written config will use `fixthis mcp` as a command fallback.\n" +
                    "  MCP clients will fail to start FixThis unless `fixthis` is on PATH.\n" +
                    "  Fix: run `./gradlew :fixthis-mcp:installDist` then re-run `fixthis setup --write`.",
            )
        }
    }
}
