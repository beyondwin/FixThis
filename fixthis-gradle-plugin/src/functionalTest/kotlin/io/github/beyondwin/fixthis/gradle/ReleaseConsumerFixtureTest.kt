package io.github.beyondwin.fixthis.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ReleaseConsumerFixtureTest {
    @Test
    fun `release consumer fixture assembles minified release without FixThis startup metadata`() {
        val sourceFixtureDir = File(System.getProperty("fixthis.releaseConsumerFixture.path"))
        val fixThisRootDir = File(System.getProperty("fixthis.rootDir.path"))
        assumeTrue("Android SDK required for release consumer fixture", System.getenv("ANDROID_HOME") != null)
        assertTrue("Fixture directory missing: ${sourceFixtureDir.absolutePath}", sourceFixtureDir.isDirectory)
        assertTrue("FixThis root directory missing: ${fixThisRootDir.absolutePath}", fixThisRootDir.isDirectory)

        val fixtureDir = Files.createTempDirectory("fixthis-release-consumer-fixture-").toFile()
        try {
            copyFixture(sourceFixtureDir.toPath(), fixtureDir.toPath())

            val result = GradleRunner.create()
                .withProjectDir(fixtureDir)
                .withArguments(
                    ":app:assembleRelease",
                    "-PfixthisRootDir=${fixThisRootDir.absolutePath}",
                    "--stacktrace",
                )
                .forwardOutput()
                .build()

            assertTrue(result.task(":app:assembleRelease")?.outcome == SUCCESS)

            val mergedManifest = fixtureDir.resolve(
                "app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml",
            )
            assertTrue("Merged release manifest missing: ${mergedManifest.absolutePath}", mergedManifest.isFile)
            val manifest = mergedManifest.readText()
            assertFalse(manifest.contains("FixThisInitializer"))
        } finally {
            fixtureDir.deleteRecursively()
        }
    }

    private fun copyFixture(
        source: Path,
        target: Path,
    ) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                if (relative.any { it.toString() == "build" || it.toString() == ".gradle" }) {
                    return@forEach
                }
                val destination = target.resolve(relative.toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
