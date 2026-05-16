package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradlePluginInstallerMessageTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun noAppModuleFailureUsesTemplatedMessage() {
        val root = tempFolder.newFolder("empty-project")
        val captured = mutableListOf<String>()
        try {
            GradlePluginInstaller.apply(
                projectRoot = root,
                packageName = "com.example.missing",
                pluginVersion = "0.2.3",
                dryRun = true,
                echo = { line -> captured += line },
            )
        } catch (e: Exception) {
            captured += e.message.orEmpty()
        }
        val joined = captured.joinToString("\n")
        assertTrue(
            "Expected templated failure, got:\n$joined",
            joined.contains("verify: ./gradlew projects") &&
                joined.contains("fix:    pass --package"),
        )
    }
}
