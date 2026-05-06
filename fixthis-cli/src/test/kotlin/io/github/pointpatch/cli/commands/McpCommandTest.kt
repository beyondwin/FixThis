package io.github.pointpatch.cli.commands

import io.github.pointpatch.cli.pointPatchJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun locatesSiblingMcpInstallNextToCliInstall() {
        val installRoot = temporaryFolder.newFolder("pointpatch")
        val mcpBin = temporaryFolder.root
            .resolve("pointpatch-mcp")
            .resolve("bin")
            .resolve("pointpatch-mcp")
        mcpBin.parentFile.mkdirs()
        mcpBin.writeText("#!/bin/sh\n")
        mcpBin.setExecutable(true)
        val classpath = installRoot.resolve("lib").resolve("pointpatch.jar").absolutePath

        assertEquals(
            mcpBin.canonicalFile,
            McpExecutableLocator.find(classpath, emptyMap(), temporaryFolder.root)?.canonicalFile,
        )
    }

    @Test
    fun prefersPathExecutableWhenSiblingInstallIsUnavailable() {
        val binDir = temporaryFolder.newFolder("bin")
        val mcpBin = binDir.resolve("pointpatch-mcp")
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
            .resolve("pointpatch-mcp")
            .resolve("build")
            .resolve("install")
            .resolve("pointpatch-mcp")
            .resolve("bin")
            .resolve("pointpatch-mcp")
        mcpBin.parentFile.mkdirs()
        mcpBin.writeText("#!/bin/sh\n")
        mcpBin.setExecutable(true)
        val classpath = root
            .resolve("pointpatch-cli")
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
            resolvedPackage = "io.github.pointpatch.sample",
            root = root,
        )

        assertEquals("pointpatch", config.getValue("command").jsonPrimitive.content)
        assertEquals(
            listOf("mcp", "--package", "io.github.pointpatch.sample", "--project-dir", root.absolutePath),
            config.getValue("args").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("io.github.pointpatch.sample", config.getValue("packageName").jsonPrimitive.content)
        assertEquals(root.absolutePath, config.getValue("projectRoot").jsonPrimitive.content)

        val encoded = pointPatchJson.encodeToString(config)
        val decoded = pointPatchJson.parseToJsonElement(encoded).jsonObject
        assertEquals(
            listOf("mcp", "--package", "io.github.pointpatch.sample", "--project-dir", root.absolutePath),
            decoded.getValue("args").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
