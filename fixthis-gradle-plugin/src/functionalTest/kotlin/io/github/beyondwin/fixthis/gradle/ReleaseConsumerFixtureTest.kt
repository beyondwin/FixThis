package io.github.beyondwin.fixthis.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ReleaseConsumerFixtureTest {
    @Test
    fun `release consumer fixture assembles minified release without FixThis startup metadata`() {
        val fixtureDir = File(System.getProperty("fixthis.releaseConsumerFixture.path"))
        assumeTrue("Android SDK required for release consumer fixture", System.getenv("ANDROID_HOME") != null)
        assertTrue("Fixture directory missing: ${fixtureDir.absolutePath}", fixtureDir.isDirectory)

        val result = GradleRunner.create()
            .withProjectDir(fixtureDir)
            .withArguments(":app:assembleRelease", "--stacktrace")
            .forwardOutput()
            .build()

        assertTrue(result.task(":app:assembleRelease")?.outcome == SUCCESS)

        val mergedManifest = fixtureDir.resolve(
            "app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml",
        )
        assertTrue("Merged release manifest missing: ${mergedManifest.absolutePath}", mergedManifest.isFile)
        val manifest = mergedManifest.readText()
        assertFalse(manifest.contains("FixThisInitializer"))
    }
}
