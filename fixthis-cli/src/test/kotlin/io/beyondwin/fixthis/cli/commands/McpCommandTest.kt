package io.beyondwin.fixthis.cli.commands

import io.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun locatesSiblingMcpInstallNextToCliInstall() {
        val installRoot = temporaryFolder.newFolder("fixthis")
        val mcpBin = temporaryFolder.root
            .resolve("fixthis-mcp")
            .resolve("bin")
            .resolve("fixthis-mcp")
        mcpBin.parentFile.mkdirs()
        mcpBin.writeText("#!/bin/sh\n")
        mcpBin.setExecutable(true)
        val classpath = installRoot.resolve("lib").resolve("fixthis.jar").absolutePath

        assertEquals(
            mcpBin.canonicalFile,
            McpExecutableLocator.find(classpath, emptyMap(), temporaryFolder.root)?.canonicalFile,
        )
    }

    @Test
    fun prefersPathExecutableWhenSiblingInstallIsUnavailable() {
        val binDir = temporaryFolder.newFolder("bin")
        val mcpBin = binDir.resolve("fixthis-mcp")
        mcpBin.writeText("#!/bin/sh\n")
        mcpBin.setExecutable(true)
        val env = mapOf("PATH" to binDir.absolutePath)
        val classpath = temporaryFolder.root.resolve("missing.jar").absolutePath

        assertEquals(
            mcpBin.canonicalFile,
            McpExecutableLocator.find(classpath, env, temporaryFolder.root)?.canonicalFile,
        )
    }

    @Test
    fun locatesProjectInstallFromGradleRunClasspath() {
        val root = temporaryFolder.newFolder("repo")
        val mcpBin = root
            .resolve("fixthis-mcp")
            .resolve("build")
            .resolve("install")
            .resolve("fixthis-mcp")
            .resolve("bin")
            .resolve("fixthis-mcp")
        mcpBin.parentFile.mkdirs()
        mcpBin.writeText("#!/bin/sh\n")
        mcpBin.setExecutable(true)
        val classpath = root
            .resolve("fixthis-cli")
            .resolve("build")
            .resolve("classes")
            .resolve("kotlin")
            .resolve("main")
            .absolutePath

        assertEquals(
            mcpBin.canonicalFile,
            McpExecutableLocator.find(classpath, emptyMap(), temporaryFolder.root)?.canonicalFile,
        )
    }

    @Test
    fun setupConfigUsesArgsArraySoPathsWithSpacesAreSafe() {
        val root = temporaryFolder.newFolder("project with spaces").canonicalFile

        val config = buildMcpClientConfig(
            resolvedPackage = "io.beyondwin.fixthis.sample",
            root = root,
        )

        assertEquals("fixthis", config.getValue("command").jsonPrimitive.content)
        assertEquals(
            listOf("mcp", "--package", "io.beyondwin.fixthis.sample", "--project-dir", root.absolutePath),
            config.getValue("args").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("io.beyondwin.fixthis.sample", config.getValue("packageName").jsonPrimitive.content)
        assertEquals(root.absolutePath, config.getValue("projectRoot").jsonPrimitive.content)

        val encoded = fixThisJson.encodeToString(config)
        val decoded = fixThisJson.parseToJsonElement(encoded).jsonObject
        assertEquals(
            listOf("mcp", "--package", "io.beyondwin.fixthis.sample", "--project-dir", root.absolutePath),
            decoded.getValue("args").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
