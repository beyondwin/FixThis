package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.ExitCode
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentSetupVerificationServiceTest {
    private val root = File("/repo").absoluteFile

    private fun assertActionContract(report: AgentSetupVerificationReport) {
        assertTrue("report should contain at least one action", report.actions.isNotEmpty())
        report.actions.forEach { action ->
            assertTrue(
                "invalid action $action",
                AgentSetupActionContract.validate(action).isEmpty(),
            )
        }
        val firstBlocked = report.actions.indexOfFirst { it.blocksProgress }
        if (firstBlocked >= 0) {
            val blockedAction = report.actions[firstBlocked]
            assertTrue(
                "blocking action must be user/manual or agent command: $blockedAction",
                (blockedAction.actor == AgentSetupActionContract.USER &&
                    blockedAction.kind == AgentSetupActionContract.MANUAL) ||
                    (blockedAction.actor == AgentSetupActionContract.AGENT &&
                        blockedAction.kind == AgentSetupActionContract.COMMAND),
            )
        }
    }

    @Test
    fun readyDoctorWithNoRestartIsReadyForMcpTooling() {
        val report = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "claude",
                setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
                doctorReport = DoctorReport(packageName = "com.example", checks = emptyList()),
                dryRun = false,
            ),
        )

        assertTrue(report.ok)
        assertEquals("READY", report.readiness.state.name)
        assertTrue(report.readyForMcpTooling)
        assertEquals("fixthis_open_feedback_console", report.actions.single().tool)
        assertEquals(ExitCode.OK, AgentSetupVerificationService().exitCodeFor(report))
    }

    @Test
    fun readyDoctorWithMcpConfigChangeBlocksUntilRestart() {
        val report = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "all",
                setup = AgentSetupSnapshot(
                    applied = listOf(
                        InstallAgentJsonReport.Applied(
                            "claude",
                            "/repo/.claude/settings.json",
                            "project-local",
                        ),
                    ),
                    skipped = emptyList(),
                    errors = emptyList(),
                    mcpConfigChanged = true,
                ),
                doctorReport = DoctorReport(packageName = "com.example", checks = emptyList()),
                dryRun = false,
            ),
        )

        assertFalse(report.ok)
        assertEquals("READY", report.readiness.state.name)
        assertFalse(report.readyForMcpTooling)
        assertTrue(report.requiresUserAction)
        assertEquals("restart_mcp_client", report.userActionReason)
        assertEquals("restart-agent", report.actions.first().id)
        assertEquals("user", report.actions.first().actor)
        assertEquals("fixthis_open_feedback_console", report.actions.last().tool)
        assertEquals(ExitCode.PARTIAL, AgentSetupVerificationService().exitCodeFor(report))
    }

    @Test
    fun deviceBlockedDoctorCreatesUserActionAndRerunVerifyAction() {
        val doctor = DoctorReport(
            packageName = "com.example",
            checks = listOf(
                DoctorCheckResult(
                    name = "device_connected",
                    label = "device connected",
                    ok = false,
                    message = "No connected Android device or emulator found",
                    fix = "Start an emulator or connect a device, then run `adb devices`.",
                    readiness = FirstRunReadinessCatalog.deviceBlocked(
                        cause = "No connected Android device or emulator found",
                        fix = "Start an emulator or connect a device, then run `adb devices`.",
                    ),
                ),
            ),
        )

        val report = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "all",
                setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
                doctorReport = doctor,
                dryRun = false,
            ),
        )

        assertEquals("DEVICE_BLOCKED", report.readiness.state.name)
        assertTrue(report.requiresUserAction)
        assertEquals("DEVICE_BLOCKED", report.userActionReason)
        assertEquals(listOf("start-device", "rerun-verify"), report.actions.map { it.id })
    }

    @Test
    fun dryRunVerifySkipsDoctorAndDoesNotClaimRuntimeReadiness() {
        val report = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "claude",
                setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
                doctorReport = null,
                dryRun = true,
            ),
        )

        assertFalse(report.ok)
        assertEquals("CONFIG_RECOVERABLE", report.readiness.state.name)
        assertEquals("dry_run_no_side_effects", report.verification.skippedReason)
        assertFalse(report.readyForMcpTooling)
    }

    @Test
    fun allServiceReportsUseClosedActionContract() {
        val ready = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "all",
                setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
                doctorReport = DoctorReport(packageName = "com.example", checks = emptyList()),
                dryRun = false,
            ),
        )
        assertActionContract(ready)

        val restart = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "all",
                setup = AgentSetupSnapshot(
                    applied = listOf(
                        InstallAgentJsonReport.Applied(
                            "codex",
                            "/Users/test/.codex/config.toml",
                            "user-global",
                        ),
                    ),
                    skipped = emptyList(),
                    errors = emptyList(),
                    mcpConfigChanged = true,
                ),
                doctorReport = DoctorReport(packageName = "com.example", checks = emptyList()),
                dryRun = false,
            ),
        )
        assertActionContract(restart)
        assertEquals("restart-agent", restart.actions.first().id)
        assertEquals(AgentSetupActionContract.USER, restart.actions.first().actor)
        assertEquals(AgentSetupActionContract.MANUAL, restart.actions.first().kind)
        assertEquals(AgentSetupActionContract.AGENT_AFTER_RESTART, restart.actions.last().actor)

        val dryRun = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "all",
                setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
                doctorReport = null,
                dryRun = true,
            ),
        )
        assertActionContract(dryRun)
        assertEquals("recover-setup", dryRun.actions.single().id)
        assertEquals(AgentSetupActionContract.AGENT, dryRun.actions.single().actor)
        assertEquals(AgentSetupActionContract.COMMAND, dryRun.actions.single().kind)
    }

    @Test
    fun unsupportedBuildDoesNotOpenConsole() {
        val readiness = FirstRunReadinessCatalog.unsupportedBuild(
            cause = "Release builds cannot expose the FixThis sidekick.",
        )
        val report = AgentSetupVerificationService().buildReport(
            AgentSetupVerificationRequest(
                projectRoot = root,
                target = "all",
                setup = AgentSetupSnapshot(emptyList(), emptyList(), emptyList(), mcpConfigChanged = false),
                doctorReport = DoctorReport(
                    packageName = "com.example",
                    checks = listOf(
                        DoctorCheckResult(
                            name = "sidekick_session_found",
                            label = "sidekick session found",
                            ok = false,
                            message = "Release builds cannot expose the FixThis sidekick.",
                            fix = "Install a debuggable app with FixThis enabled.",
                            readiness = readiness,
                        ),
                    ),
                ),
                dryRun = false,
            ),
        )

        assertActionContract(report)
        assertFalse(report.readyForMcpTooling)
        assertTrue(report.actions.none { it.tool == "fixthis_open_feedback_console" })
        assertEquals("recover-setup", report.actions.single().id)
        assertEquals(AgentSetupActionContract.COMMAND, report.actions.single().kind)
    }
}
