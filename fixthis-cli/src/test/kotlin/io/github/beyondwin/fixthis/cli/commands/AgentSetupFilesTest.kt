package io.github.beyondwin.fixthis.cli.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSetupFilesTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun agentSetupJsonContainsStateNextAndRecoverySections() {
        val root = tempFolder.newFolder("proj")
        AgentSetupFiles.write(
            projectRoot = root,
            packageName = "com.example.app",
            serverName = "fixthis",
            dryRun = false,
            echo = {},
        )
        val jsonFile = java.io.File(root, ".fixthis/agent-setup.json")
        assertTrue("agent-setup.json missing", jsonFile.isFile)
        val obj = Json.parseToJsonElement(jsonFile.readText()).jsonObject
        assertTrue("missing 'state'", "state" in obj)
        assertTrue("missing 'next'", "next" in obj)
        assertTrue("missing 'recovery'", "recovery" in obj)
        assertTrue(
            "next must be a non-empty array of runnable command strings",
            obj.getValue("next").jsonArray.isNotEmpty(),
        )
        assertTrue("missing 'readiness'", "readiness" in obj)
        assertTrue("missing readiness recovery map", "readinessRecovery" in obj.getValue("recovery").jsonObject)
        val readiness = obj.getValue("readiness").jsonObject
        assertTrue(readiness.getValue("nextAction").jsonPrimitive.content.contains("fixthis doctor"))
    }

    @Test
    fun agentSetupWritesProjectJsonForReadmeFirstDoctorPath() {
        val root = tempFolder.newFolder("proj")
        AgentSetupFiles.write(
            projectRoot = root,
            packageName = "com.example.app",
            serverName = "fixthis",
            dryRun = false,
            echo = {},
        )

        val projectJson = java.io.File(root, ".fixthis/project.json")
        assertTrue("project.json missing", projectJson.isFile)
        val obj = Json.parseToJsonElement(projectJson.readText()).jsonObject
        assertTrue("missing applicationId", obj.getValue("applicationId").jsonPrimitive.content == "com.example.app")
    }

    @Test
    fun setupGuideUsesDoctorJsonBeforeConsole() {
        val root = tempFolder.newFolder("proj")
        AgentSetupFiles.write(
            projectRoot = root,
            packageName = "com.example.app",
            serverName = "fixthis",
            dryRun = false,
            echo = {},
        )

        val text = java.io.File(root, ".fixthis/agent-setup.md").readText()
        val doctorIndex = text.indexOf("fixthis doctor --project-dir . --json")
        val consoleIndex = text.indexOf("fixthis_open_feedback_console")
        assertTrue("doctor command missing", doctorIndex >= 0)
        assertTrue("console tool missing", consoleIndex >= 0)
        assertTrue("doctor should come before console", doctorIndex < consoleIndex)
        assertTrue(text.contains("Restart Claude Code or Codex"))
    }
}
