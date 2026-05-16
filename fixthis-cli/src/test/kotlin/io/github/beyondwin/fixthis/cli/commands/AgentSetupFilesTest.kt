package io.github.beyondwin.fixthis.cli.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    }
}
