package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallAgentJsonReportTest {
    @Test
    fun verifyReportCarriesSetupVerificationActionsAndMcpReadiness() {
        val readiness = FirstRunReadinessCatalog.deviceBlocked(
            cause = "No connected Android device or emulator found",
            fix = "Start an emulator or connect a device, then run `adb devices`.",
        )
        val rendered = AgentSetupVerificationJsonReport.render(
            AgentSetupVerificationReport(
                ok = false,
                readiness = readiness,
                readinessSource = "verification",
                next = listOf("Start an emulator or connect a device, then run `adb devices`."),
                restartRequired = true,
                requiresUserAction = true,
                userActionReason = "DEVICE_BLOCKED",
                readyForMcpTooling = false,
                actions = listOf(
                    AgentSetupAction(
                        id = "start-device",
                        actor = "user",
                        kind = "manual",
                        reason = "A connected unlocked Android device or emulator is required.",
                        blocksProgress = true,
                    ),
                ),
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
                verification = AgentVerificationSnapshot(
                    ok = false,
                    packageName = "com.example.app",
                    checks = emptyList(),
                ),
            ),
        )

        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("1.1", obj.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("false", obj.getValue("ok").jsonPrimitive.content)
        assertEquals("verification", obj.getValue("readinessSource").jsonPrimitive.content)
        assertEquals("false", obj.getValue("readyForMcpTooling").jsonPrimitive.content)
        assertEquals("true", obj.getValue("setup").jsonObject.getValue("mcpConfigChanged").jsonPrimitive.content)
        assertEquals("start-device", obj.getValue("actions").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("com.example.app", obj.getValue("verification").jsonObject.getValue("packageName").jsonPrimitive.content)
    }

    @Test
    fun jsonReportHasSchemaAndFields() {
        val rendered = InstallAgentJsonReport.render(
            applied = listOf(
                InstallAgentJsonReport.Applied("claude", "/tmp/.claude/settings.json", "project-local"),
            ),
            skipped = listOf(
                InstallAgentJsonReport.Skipped("gradle-plugin", "no-app-module", "pass --package <id>"),
            ),
            errors = emptyList(),
            next = listOf("./gradlew fixthisSetup", "fixthis doctor --json"),
        )
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("1.0", obj.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("false", obj.getValue("ok").jsonPrimitive.content)
        assertEquals(1, obj.getValue("applied").jsonArray.size)
        assertEquals(
            "claude",
            obj.getValue("applied").jsonArray[0].jsonObject
                .getValue("target").jsonPrimitive.content,
        )
        assertEquals(
            "no-app-module",
            obj.getValue("skipped").jsonArray[0].jsonObject
                .getValue("reason").jsonPrimitive.content,
        )
        assertEquals(2, obj.getValue("next").jsonArray.size)
    }

    @Test
    fun skippedEntriesCarryReadinessAndTopLevelNextAction() {
        val rendered = InstallAgentJsonReport.render(
            applied = emptyList(),
            skipped = listOf(
                InstallAgentJsonReport.Skipped(
                    target = "codex",
                    reason = "no-android-context",
                    fix = "Re-run from an Android project root, or pass --allow-global.",
                    readiness = FirstRunReadinessCatalog.configRecoverable(
                        cause = "Codex global config was skipped outside an Android project.",
                    ),
                ),
            ),
            errors = emptyList(),
            next = listOf("cd <android-project-root>", "fixthis install-agent --project-dir ."),
        )

        val root = Json.parseToJsonElement(rendered).jsonObject
        val skipped = root.getValue("skipped").jsonArray.single().jsonObject
        val readiness = skipped.getValue("readiness").jsonObject
        val state = readiness.getValue("state").jsonPrimitive.content
        assertEquals("CONFIG_RECOVERABLE", state)
        assertEquals("cd <android-project-root>", root.getValue("nextAction").jsonPrimitive.content)
    }

    @Test
    fun reportCanCarryTopLevelReadinessAndRestartMetadata() {
        val rendered = InstallAgentJsonReport.render(
            applied = listOf(
                InstallAgentJsonReport.Applied("claude", "/tmp/.claude/settings.json", "project-local"),
            ),
            skipped = emptyList(),
            errors = emptyList(),
            next = listOf("fixthis doctor --project-dir /repo --json"),
            readiness = FirstRunReadinessCatalog.configRecoverable(
                cause = "FixThis setup completed; verify the debug app before opening the console.",
            ).copy(
                nextAction = "fixthis doctor --project-dir /repo --json",
            ),
            restartRequired = true,
        )

        val obj = Json.parseToJsonElement(rendered).jsonObject
        val readiness = obj.getValue("readiness").jsonObject
        assertEquals("CONFIG_RECOVERABLE", readiness.getValue("state").jsonPrimitive.content)
        assertEquals(
            "fixthis doctor --project-dir /repo --json",
            readiness.getValue("nextAction").jsonPrimitive.content,
        )
        assertEquals("true", obj.getValue("restartRequired").jsonPrimitive.content)
    }

    @Test
    fun topLevelNextActionPrefersReadinessNextActionWhenProvided() {
        val rendered = InstallAgentJsonReport.render(
            applied = emptyList(),
            skipped = emptyList(),
            errors = emptyList(),
            next = listOf("restart your agent", "fixthis doctor --project-dir /repo --json"),
            readiness = FirstRunReadinessCatalog.configRecoverable(
                cause = "Setup completed; verify with doctor.",
            ).copy(
                nextAction = "fixthis doctor --project-dir /repo --json",
            ),
            restartRequired = true,
        )

        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals(
            "fixthis doctor --project-dir /repo --json",
            obj.getValue("nextAction").jsonPrimitive.content,
        )
    }

    @Test
    fun successfulInstallAgentJsonPointsAtDoctorBeforeConsole() {
        val rendered = InstallAgentJsonReport.render(
            applied = listOf(
                InstallAgentJsonReport.Applied("claude", "/repo/.claude/settings.json", "project-local"),
                InstallAgentJsonReport.Applied("gradle-plugin", "/repo/app/build.gradle.kts", "project-local"),
            ),
            skipped = emptyList(),
            errors = emptyList(),
            next = listOf(
                "fixthis doctor --project-dir /repo --json",
                "# Restart Claude Code / Codex to reload MCP config",
                "fixthis_open_feedback_console",
            ),
            readiness = FirstRunReadinessCatalog.configRecoverable(
                cause = "FixThis setup completed; verify the debug app before opening the console.",
            ).copy(
                verify = "fixthis doctor --project-dir /repo --json",
                fix = "Run doctor, restart Claude Code or Codex if MCP config changed, then open the feedback console.",
                nextAction = "fixthis doctor --project-dir /repo --json",
            ),
            restartRequired = true,
        )

        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals(
            "fixthis doctor --project-dir /repo --json",
            obj.getValue("nextAction").jsonPrimitive.content,
        )
        assertEquals(
            "fixthis doctor --project-dir /repo --json",
            obj.getValue("readiness").jsonObject
                .getValue("nextAction").jsonPrimitive.content,
        )
    }

    @Test
    fun verifyReportRendersCommandToolAndManualActions() {
        val rendered = AgentSetupVerificationJsonReport.render(commandToolManualActionReport())

        val obj = Json.parseToJsonElement(rendered).jsonObject
        val actions = obj.getValue("actions").jsonArray.map { it.jsonObject }
        assertEquals("manual", actions[0].getValue("kind").jsonPrimitive.content)
        assertEquals("command", actions[1].getValue("kind").jsonPrimitive.content)
        assertEquals("mcp_tool", actions[2].getValue("kind").jsonPrimitive.content)
        assertEquals(
            "fixthis install-agent --project-dir /repo --target all --verify --json",
            actions[1].getValue("command").jsonPrimitive.content,
        )
        assertEquals(
            "fixthis_open_feedback_console",
            actions[2].getValue("tool").jsonPrimitive.content,
        )
    }

    private fun commandToolManualActionReport() = AgentSetupVerificationReport(
        ok = false,
        readiness = FirstRunReadinessCatalog.configRecoverable(
            cause = "FixThis setup needs another command.",
        ).copy(
            nextAction = "fixthis install-agent --project-dir /repo --target all --verify --json",
        ),
        readinessSource = "setup",
        next = listOf(
            "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
            "fixthis install-agent --project-dir /repo --target all --verify --json",
            "fixthis_open_feedback_console",
        ),
        restartRequired = true,
        requiresUserAction = true,
        userActionReason = "restart_mcp_client",
        readyForMcpTooling = false,
        actions = setupActionFixture(),
        setup = AgentSetupSnapshot(
            applied = emptyList(),
            skipped = emptyList(),
            errors = emptyList(),
            mcpConfigChanged = true,
        ),
        verification = AgentVerificationSnapshot(
            ok = false,
            packageName = null,
            checks = emptyList(),
        ),
    )

    private fun setupActionFixture() = listOf(
        AgentSetupAction(
            id = "restart-agent",
            actor = AgentSetupActionContract.USER,
            kind = AgentSetupActionContract.MANUAL,
            reason = "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
            blocksProgress = true,
        ),
        AgentSetupAction(
            id = "rerun-verify",
            actor = AgentSetupActionContract.AGENT,
            kind = AgentSetupActionContract.COMMAND,
            command = "fixthis install-agent --project-dir /repo --target all --verify --json",
            reason = "Verify setup after restart.",
            blocksProgress = false,
        ),
        AgentSetupAction(
            id = "open-feedback-console",
            actor = AgentSetupActionContract.AGENT_AFTER_RESTART,
            kind = AgentSetupActionContract.MCP_TOOL,
            tool = "fixthis_open_feedback_console",
            reason = "Open FixThis Studio after setup verification succeeds.",
            blocksProgress = false,
        ),
    )

    @Test
    fun verifyReportRejectsManualActionsWithExecutablePayloads() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentSetupVerificationJsonReport.render(
                AgentSetupVerificationReport(
                    ok = false,
                    readiness = FirstRunReadinessCatalog.configRecoverable(
                        cause = "FixThis setup needs a manual step.",
                    ),
                    readinessSource = "setup",
                    next = listOf("Restart Claude Code or Codex."),
                    restartRequired = true,
                    requiresUserAction = true,
                    userActionReason = "restart_mcp_client",
                    readyForMcpTooling = false,
                    actions = listOf(
                        AgentSetupAction(
                            id = "restart-agent",
                            actor = AgentSetupActionContract.USER,
                            kind = AgentSetupActionContract.MANUAL,
                            command = "fixthis install-agent --project-dir /repo --target all --verify --json",
                            tool = "fixthis_open_feedback_console",
                            reason = "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
                            blocksProgress = true,
                        ),
                    ),
                    setup = AgentSetupSnapshot(
                        applied = emptyList(),
                        skipped = emptyList(),
                        errors = emptyList(),
                        mcpConfigChanged = true,
                    ),
                    verification = AgentVerificationSnapshot(
                        ok = false,
                        packageName = null,
                        checks = emptyList(),
                    ),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("Manual action must not include command"))
        assertTrue(error.message.orEmpty().contains("Manual action must not include tool"))
    }

    @Test
    fun emptyReportIsOk() {
        val rendered = InstallAgentJsonReport.render(
            applied = listOf(
                InstallAgentJsonReport.Applied("claude", "/tmp/x", "project-local"),
            ),
            skipped = emptyList(),
            errors = emptyList(),
            next = emptyList(),
        )
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("true", obj.getValue("ok").jsonPrimitive.content)
    }
}
