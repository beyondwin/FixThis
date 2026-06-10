package io.github.beyondwin.fixthis.cli.commands

import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallAgentJsonReportTest {
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
