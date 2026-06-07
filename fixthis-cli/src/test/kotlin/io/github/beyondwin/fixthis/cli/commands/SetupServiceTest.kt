package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SetupServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `writeConfigs records applied targets in injected report`() {
        val projectRoot = createAndroidProjectRoot()
        val report = SetupReport()
        val emitted = mutableListOf<String>()

        withUserHome(temporaryFolder.newFolder("home")) {
            SetupService(report = report, emit = { emitted += it }).writeConfigs(
                SetupRequest(
                    packageName = "com.example.app",
                    projectRoot = projectRoot,
                    target = "claude",
                    serverName = "fixthis",
                    write = true,
                    dryRun = false,
                ),
            )
        }

        assertEquals(
            "expected exactly one applied target for --target claude",
            listOf("claude"),
            report.applied.map { it.target },
        )
        assertTrue(
            "applied path must be the written config file",
            report.applied.single().path.endsWith(".claude${File.separator}settings.json") ||
                report.applied.single().path.endsWith(".claude/settings.json"),
        )
        assertTrue("dry-run was disabled, so the config file must exist", File(report.applied.single().path).isFile)
    }

    @Test
    fun `writeConfigs dry-run records nothing and emits diff headers`() {
        val projectRoot = createAndroidProjectRoot()
        val report = SetupReport()
        val emitted = mutableListOf<String>()

        withUserHome(temporaryFolder.newFolder("home")) {
            SetupService(report = report, emit = { emitted += it }).writeConfigs(
                SetupRequest(
                    packageName = "com.example.app",
                    projectRoot = projectRoot,
                    target = "claude",
                    serverName = "fixthis",
                    write = true,
                    dryRun = true,
                ),
            )
        }

        assertTrue("dry-run must record nothing in applied", report.applied.isEmpty())
        assertTrue("dry-run must emit a Target: header", emitted.any { it.startsWith("Target:") })
        assertTrue("dry-run must emit a Path: header", emitted.any { it.startsWith("Path:") })
        assertFalse(
            "dry-run must not write the config file",
            File(projectRoot, ".claude/settings.json").isFile,
        )
    }

    private fun createAndroidProjectRoot(): File {
        val root = temporaryFolder.newFolder("project").canonicalFile
        File(root, "settings.gradle.kts").writeText("""include(":app")""")
        val app = File(root, "app").apply { mkdirs() }
        File(app, "build.gradle.kts").writeText(
            """android { defaultConfig { applicationId = "com.example.app" } }""",
        )
        return root
    }

    private fun <T> withUserHome(userHome: File, block: () -> T): T {
        val original = System.getProperty("user.home")
        System.setProperty("user.home", userHome.absolutePath)
        return try {
            block()
        } finally {
            if (original == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", original)
            }
        }
    }
}
