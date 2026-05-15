package io.beyondwin.fixthis.gradle

import io.beyondwin.fixthis.gradle.task.FixThisSetupTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FixThisSetupTaskTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `writes project metadata and agent next steps`() {
        val projectDir = temporaryFolder.newFolder("consumer")
        val project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        val task = project.tasks.register("fixthisSetup", FixThisSetupTask::class.java).get()
        task.rootProjectDirectory.set(project.layout.projectDirectory)
        task.projectPath.set(":app")
        task.variantName.set("debug")
        task.applicationId.set("com.example.agent")

        task.setup()

        val projectJson = projectDir.resolve(".fixthis/project.json")
        assertTrue(projectJson.isFile)
        val metadata = Json.parseToJsonElement(projectJson.readText()).jsonObject
        assertEquals("1.0", metadata.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("com.example.agent", metadata.getValue("applicationId").jsonPrimitive.content)
        assertEquals(":app", metadata.getValue("projectPath").jsonPrimitive.content)
        assertEquals("debug", metadata.getValue("variantName").jsonPrimitive.content)

        val agentGuide = projectDir.resolve(".fixthis/agent-setup.md")
        assertTrue(agentGuide.isFile)
        val guide = agentGuide.readText()
        assertTrue(guide.contains("fixthis init --project-dir ."))
        assertTrue(guide.contains("fixthis doctor --project-dir ."))
        assertTrue(guide.contains("fixthis_open_feedback_console"))

        val mcpTemplate = projectDir.resolve(".fixthis/mcp.json.template")
        assertTrue(mcpTemplate.isFile)
        val mcp = Json.parseToJsonElement(mcpTemplate.readText()).jsonObject
        val server = mcp.getValue("mcpServers").jsonObject.getValue("fixthis").jsonObject
        assertEquals("fixthis", server.getValue("command").jsonPrimitive.content)
        assertTrue(mcpTemplate.readText().contains("\"--project-dir\""))
    }

    @Test
    fun `uses plain fixthisSetup for the standard debug variant`() {
        assertEquals("fixthisSetup", fixThisSetupTaskName("debug"))
    }

    @Test
    fun `uses variant suffix for non-standard debug variants`() {
        assertEquals("fixthisSetupStagingDebug", fixThisSetupTaskName("stagingDebug"))
    }
}
