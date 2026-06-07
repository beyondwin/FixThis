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
        val client = BridgeClient(projectRoot = request.projectRoot)
        val resolvedPackage = failAsCliError { client.resolvePackageName(request.packageName) }
        val validServerName = validateMcpServerName(request.serverName)
        val sdk = AndroidSdkLocator.find()
        val executable = McpExecutableLocator.find()
        warnIfMissing(sdk, executable, resolvedPackage, request.projectRoot)

        val entry = buildMcpConfigEntry(
            resolvedPackage = resolvedPackage,
            root = request.projectRoot,
            serverName = validServerName,
            sdk = sdk,
            executable = executable,
        )
        val plans = SetupPlanner.buildWritePlans(
            SetupPlanner.selectedWriters(request.target),
            request.projectRoot,
            entry,
        )
        if (request.dryRun) {
            plans.forEach { plan -> renderDryRunOutput(plan, request.fullDiff).forEach(emit) }
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
