package io.github.beyondwin.fixthis.cli.commands

import com.github.ajalt.clikt.core.CliktError
import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.ExitCode
import io.github.beyondwin.fixthis.cli.ProjectConfig
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.system.measureTimeMillis

class RunCommandTest {
    @Test
    fun defaultInstallTaskUsesVariantMetadataWhenProjectConfigExists() {
        val root = Files.createTempDirectory("fixthis-run-flavored").toFile()
        root.resolve(".fixthis").mkdirs()
        root.resolve(".fixthis/project.json").writeText(
            """
            {
              "schemaVersion": "1.0",
              "applicationId": "com.example.demo",
              "projectPath": ":app",
              "variantName": "demoDebug"
            }
            """.trimIndent(),
        )

        val config = ProjectConfig.resolve(root, packageOverride = null)

        assertEquals(":app:installDemoDebug", defaultInstallTask(config))
    }

    @Test
    fun defaultInstallTaskIgnoresVariantMetadataWhenPackageOverrideDiffers() {
        val root = Files.createTempDirectory("fixthis-run-override").toFile()
        root.resolve(".fixthis").mkdirs()
        root.resolve(".fixthis/project.json").writeText(
            """
            {
              "schemaVersion": "1.0",
              "applicationId": "com.example.demo",
              "projectPath": ":app",
              "variantName": "demoDebug"
            }
            """.trimIndent(),
        )

        val config = ProjectConfig.resolve(root, packageOverride = "com.example.full")

        assertEquals(":app:installDebug", defaultInstallTask(config))
    }

    @Test
    fun defaultInstallTaskFallsBackToAppInstallDebugWithoutVariantMetadata() {
        val root = Files.createTempDirectory("fixthis-run-unflavored").toFile()
        root.resolve(".fixthis").mkdirs()
        root.resolve(".fixthis/project.json").writeText(
            """
            {
              "schemaVersion": "1.0",
              "applicationId": "com.example"
            }
            """.trimIndent(),
        )

        val config = ProjectConfig.resolve(root, packageOverride = null)

        assertEquals(":app:installDebug", defaultInstallTask(config))
    }

    @Test
    fun defaultInstallTaskNormalizesProjectPathWithoutLeadingColon() {
        assertEquals(
            ":app:installDemoDebug",
            defaultInstallTask(
                io.github.beyondwin.fixthis.cli.ResolvedProjectConfig(
                    applicationId = "com.example.demo",
                    projectPath = "app",
                    variantName = "demoDebug",
                ),
            ),
        )
    }

    @Test
    fun defaultInstallTaskHandlesRootProjectPath() {
        assertEquals(
            ":installDemoDebug",
            defaultInstallTask(
                io.github.beyondwin.fixthis.cli.ResolvedProjectConfig(
                    applicationId = "com.example.demo",
                    projectPath = ":",
                    variantName = "demoDebug",
                ),
            ),
        )
    }

    @Test
    fun runPreflightStopsBeforeGradleWhenAndroidSdkIsMissing() {
        val root = Files.createTempDirectory("fixthis-run-missing-sdk").toFile()

        try {
            requireAndroidRunEnvironment(
                root = root,
                sdkLookup = { null },
                devicesLookup = {
                    fail("run preflight should not call adb when the SDK was not found")
                    emptyList()
                },
            )
            fail("expected env blocker")
        } catch (error: CliktError) {
            assertEquals(ExitCode.ENV_BLOCKER.value, error.statusCode)
            assertTrue(
                "expected Android SDK fix hint, got: ${error.message}",
                error.message!!.contains("Install Android SDK platform-tools or set ANDROID_HOME"),
            )
        }
    }

    @Test
    fun runPreflightRequiresAConnectedAndroidDevice() {
        val root = Files.createTempDirectory("fixthis-run-no-device").toFile()
        val adb = fakeAdb(root)

        try {
            requireAndroidRunEnvironment(
                root = root,
                sdkLookup = { AndroidSdkLocator.SdkLocation(root, adb, "test") },
                devicesLookup = { listOf(AdbDevice("emulator-5554", "offline")) },
            )
            fail("expected env blocker")
        } catch (error: CliktError) {
            assertEquals(ExitCode.ENV_BLOCKER.value, error.statusCode)
            assertTrue(
                "expected connected-device fix hint, got: ${error.message}",
                error.message!!.contains("Start an emulator or connect a device"),
            )
        }
    }

    @Test
    fun runPreflightReturnsAdbExecutableWhenEnvironmentIsReady() {
        val root = Files.createTempDirectory("fixthis-run-ready").toFile()
        val adb = fakeAdb(root)

        val executable = requireAndroidRunEnvironment(
            root = root,
            sdkLookup = { AndroidSdkLocator.SdkLocation(root, adb, "test") },
            devicesLookup = { listOf(AdbDevice("emulator-5554", "device")) },
        )

        assertEquals(adb.absolutePath, executable)
    }

    @Test
    fun waitForStatusCancellationReturnsWithin50ms() = runBlocking {
        val job = launch {
            waitForStatus(timeoutMillis = 10_000L) {
                throw IOException("sidekick not ready")
            }
        }
        // Let it enter at least one backoff sleep.
        delay(50)
        val elapsed = measureTimeMillis { job.cancelAndJoin() }
        assertTrue("cancellation took ${elapsed}ms, expected < 50ms", elapsed < 50)
    }

    @Test
    fun waitForStatusReturnsOnFirstSuccess() = runBlocking {
        var calls = 0
        waitForStatus(timeoutMillis = 1_000L) {
            calls++
        }
        assertTrue("probe should be invoked at least once, was $calls", calls >= 1)
    }

    @Test
    fun waitForStatusThrowsOnTimeoutWithLastErrorAsCause() = runBlocking {
        val expected = IOException("sidekick offline")
        try {
            waitForStatus(timeoutMillis = 50L) {
                throw expected
            }
            fail("expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertTrue(
                "expected last error to be wrapped as cause but was ${error.cause}",
                error.cause === expected,
            )
        }
    }

    private fun fakeAdb(root: File): File = root.resolve("platform-tools/adb").apply {
        parentFile.mkdirs()
        writeText("")
        setExecutable(true)
    }
}
