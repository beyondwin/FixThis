package io.github.beyondwin.fixthis.cli.commands

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
