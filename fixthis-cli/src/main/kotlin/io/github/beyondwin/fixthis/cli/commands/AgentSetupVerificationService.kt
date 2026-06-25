package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.ExitCode
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadiness
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState
import java.io.File

internal data class AgentSetupVerificationRequest(
    val projectRoot: File,
    val target: String,
    val setup: AgentSetupSnapshot,
    val doctorReport: DoctorReport?,
    val dryRun: Boolean,
)

internal class AgentSetupVerificationService {
    fun buildReport(request: AgentSetupVerificationRequest): AgentSetupVerificationReport {
        val root = request.projectRoot.absoluteFile
        val readiness = selectReadiness(request, root)
        val readinessSource = readinessSource(request)
        val restartRequired = request.setup.mcpConfigChanged
        val readyForMcpTooling = readiness.state == FirstRunReadinessState.READY && !restartRequired
        val actions = actionsFor(request, readiness, restartRequired, readyForMcpTooling, root)
        val requiresUserAction = actions.any { it.actor == AgentSetupActionContract.USER && it.blocksProgress }
        val ok = readiness.state == FirstRunReadinessState.READY &&
            readyForMcpTooling &&
            request.setup.skipped.isEmpty() &&
            request.setup.errors.isEmpty()

        return AgentSetupVerificationReport(
            ok = ok,
            readiness = readiness,
            readinessSource = readinessSource,
            next = actions.mapNotNull { it.command ?: it.tool ?: it.reason },
            restartRequired = restartRequired,
            requiresUserAction = requiresUserAction,
            userActionReason = actions.firstOrNull {
                it.actor == AgentSetupActionContract.USER && it.blocksProgress
            }?.let { action ->
                if (action.id == "restart-agent") "restart_mcp_client" else readiness.state.name
            },
            readyForMcpTooling = readyForMcpTooling,
            actions = actions,
            setup = request.setup,
            verification = verificationSnapshot(request),
        )
    }

    fun exitCodeFor(report: AgentSetupVerificationReport): ExitCode = if (report.ok) {
        ExitCode.OK
    } else if (report.setup.errors.isNotEmpty()) {
        ExitCode.INTERNAL_ERROR
    } else {
        ExitCode.PARTIAL
    }

    private fun selectReadiness(request: AgentSetupVerificationRequest, root: File): FirstRunReadiness {
        request.setup.errors.firstOrNull()?.readiness?.let { return it }
        if (request.dryRun) {
            return FirstRunReadinessCatalog.configRecoverable(
                cause = "FixThis setup was previewed with --dry-run; verification was not executed.",
            ).copy(
                verify = "fixthis install-agent --project-dir ${root.absolutePath} --target ${request.target} --verify --json",
                fix = "Rerun without --dry-run to apply setup, then verify.",
                nextAction = "fixthis install-agent --project-dir ${root.absolutePath} --target ${request.target} --verify --json",
            )
        }
        val doctorReadiness = request.doctorReport?.readiness
        if (doctorReadiness != null && doctorReadiness.state != FirstRunReadinessState.READY) {
            return doctorReadiness
        }
        request.setup.skipped.firstOrNull()?.readiness?.let { return it }
        return doctorReadiness ?: FirstRunReadinessCatalog.configRecoverable(
            cause = "FixThis setup completed; run verification before opening the console.",
        ).copy(
            verify = "fixthis doctor --project-dir ${root.absolutePath} --json",
            fix = "Run doctor, restart the MCP client if config changed, then open FixThis Studio.",
            nextAction = "fixthis doctor --project-dir ${root.absolutePath} --json",
        )
    }

    private fun readinessSource(request: AgentSetupVerificationRequest): String = when {
        request.setup.errors.isNotEmpty() || request.setup.skipped.isNotEmpty() || request.dryRun -> "setup"
        request.doctorReport != null -> "verification"
        else -> "merged"
    }

    private fun verificationSnapshot(request: AgentSetupVerificationRequest): AgentVerificationSnapshot =
        if (request.dryRun) {
            AgentVerificationSnapshot(
                ok = false,
                packageName = null,
                checks = emptyList(),
                skippedReason = "dry_run_no_side_effects",
            )
        } else {
            AgentVerificationSnapshot(
                ok = request.doctorReport?.ok ?: false,
                packageName = request.doctorReport?.packageName,
                checks = request.doctorReport?.checks ?: emptyList(),
            )
        }

    private fun actionsFor(
        request: AgentSetupVerificationRequest,
        readiness: FirstRunReadiness,
        restartRequired: Boolean,
        readyForMcpTooling: Boolean,
        root: File,
    ): List<AgentSetupAction> = buildList {
        if (restartRequired) {
            add(
                AgentSetupAction(
                    id = "restart-agent",
                    actor = AgentSetupActionContract.USER,
                    kind = AgentSetupActionContract.MANUAL,
                    reason = "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
                    blocksProgress = true,
                ),
            )
        }
        when (readiness.state) {
            FirstRunReadinessState.READY -> {
                add(
                    AgentSetupAction(
                        id = "open-feedback-console",
                        actor = if (readyForMcpTooling) {
                            AgentSetupActionContract.AGENT
                        } else {
                            AgentSetupActionContract.AGENT_AFTER_RESTART
                        },
                        kind = AgentSetupActionContract.MCP_TOOL,
                        tool = "fixthis_open_feedback_console",
                        reason = "Open FixThis Studio after setup verification succeeds.",
                        blocksProgress = false,
                    ),
                )
            }
            FirstRunReadinessState.DEVICE_BLOCKED -> {
                add(
                    AgentSetupAction(
                        id = "start-device",
                        actor = AgentSetupActionContract.USER,
                        kind = AgentSetupActionContract.MANUAL,
                        reason = readiness.fix,
                        blocksProgress = true,
                    ),
                )
                add(rerunVerify(root, request.target))
            }
            FirstRunReadinessState.CONFIG_RECOVERABLE,
            FirstRunReadinessState.STALE_PREVIEW,
            FirstRunReadinessState.SESSION_MISMATCH,
            FirstRunReadinessState.CAPTURE_UNAVAILABLE,
            FirstRunReadinessState.UNKNOWN_ERROR -> {
                val command = readiness.nextAction.asRunnableShellCommand()
                if (command == null) {
                    add(manualRecoveryAction(readiness))
                } else {
                    add(
                        AgentSetupAction(
                            id = "recover-setup",
                            actor = AgentSetupActionContract.AGENT,
                            kind = AgentSetupActionContract.COMMAND,
                            command = command,
                            reason = readiness.cause,
                            blocksProgress = true,
                        ),
                    )
                }
            }
            FirstRunReadinessState.NEEDS_INSTALL -> {
                add(
                    AgentSetupAction(
                        id = "recover-setup",
                        actor = AgentSetupActionContract.AGENT,
                        kind = AgentSetupActionContract.COMMAND,
                        command = "fixthis install-agent --project-dir ${root.absolutePath} --target ${request.target} --verify --json",
                        reason = readiness.cause,
                        blocksProgress = true,
                    ),
                )
            }
            FirstRunReadinessState.NEEDS_APP_LAUNCH -> {
                add(manualRecoveryAction(readiness))
                add(rerunVerify(root, request.target))
            }
            FirstRunReadinessState.UNSUPPORTED_BUILD,
            FirstRunReadinessState.ENV_BLOCKER -> {
                add(manualRecoveryAction(readiness))
            }
        }
    }

    private fun manualRecoveryAction(readiness: FirstRunReadiness) = AgentSetupAction(
        id = "recover-setup",
        actor = AgentSetupActionContract.USER,
        kind = AgentSetupActionContract.MANUAL,
        reason = readiness.fix,
        blocksProgress = true,
    )

    private fun String.asRunnableShellCommand(): String? {
        val trimmed = trim()
        val runBacktick = Regex("""^Run `([^`]+)`\.?$""").matchEntire(trimmed)
        val command = runBacktick?.groupValues?.get(1) ?: trimmed
        return command.takeIf {
            it.startsWith("fixthis ") ||
                it.startsWith("./") ||
                it.startsWith("adb ")
        }
    }

    private fun rerunVerify(root: File, target: String) = AgentSetupAction(
        id = "rerun-verify",
        actor = AgentSetupActionContract.AGENT,
        kind = AgentSetupActionContract.COMMAND,
        command = "fixthis install-agent --project-dir ${root.absolutePath} --target $target --verify --json",
        reason = "Verify setup after the blocking condition is resolved.",
        blocksProgress = false,
    )
}
