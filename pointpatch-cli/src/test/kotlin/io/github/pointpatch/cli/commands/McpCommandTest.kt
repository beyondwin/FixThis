package io.github.pointpatch.cli.commands

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
