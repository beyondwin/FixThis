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
}
