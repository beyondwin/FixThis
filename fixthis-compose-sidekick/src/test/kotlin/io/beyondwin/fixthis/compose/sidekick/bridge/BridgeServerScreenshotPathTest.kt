package io.beyondwin.fixthis.compose.sidekick.bridge

import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

/**
 * Higher-level tests covering the screenshot path validation contract in
 * [BridgeServer.readScreenshot]. Complements the lower-level
 * [PathSafetyTest] by exercising the full request path including the
 * `require(...)` guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeServerScreenshotPathTest {
    @Test
    fun acceptsLegitimatePathInsideCache() = runBlocking {
        val cacheDirectory = tempDirectory("fixthis-cache")
        val screenFile = screenshotFile(cacheDirectory, "screen-1-full.png", PngHeader)
        val server = serverWithScreenshot(cacheDirectory, screenFile)

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readScreenshot","params":{"kind":"full"}}""",
        )

        assertTrue(response.contains(""""mimeType": "image/png""""))
        assertTrue(response.contains(screenFile.absolutePath))
        assertFalse(response.contains(""""ok": false"""))
    }

    @Test
    fun rejectsDotDotEscapeOutsideCache() = runBlocking {
        // The screenshot is stored OUTSIDE the cache (escapes via the
        // filesystem itself rather than a literal `..` in the path string —
        // canonicalFile would normalise the latter anyway).
        val cacheDirectory = tempDirectory("fixthis-cache")
        val outsideRoot = tempDirectory("fixthis-outside")
        val outside = File(outsideRoot, "escape.png").apply { writeBytes(PngHeader) }

        val server = serverWithScreenshot(cacheDirectory, outside)

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readScreenshot","params":{"kind":"full"}}""",
        )

        assertTrue(response.contains(""""ok": false"""))
        assertTrue(response.contains("METHOD_FAILED"))
    }

    @Test
    fun rejectsSymlinkInCachePointingOutside() = runBlocking {
        val cacheDirectory = tempDirectory("fixthis-cache")
        val outsideRoot = tempDirectory("fixthis-outside")
        val outsideTarget = File(outsideRoot, "secret.png").apply { writeBytes(PngHeader) }
        val symlink = File(cacheDirectory, "link.png")
        Files.createSymbolicLink(symlink.toPath(), outsideTarget.toPath())

        val server = serverWithScreenshot(cacheDirectory, symlink)

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readScreenshot","params":{"kind":"full"}}""",
        )

        assertTrue(response.contains(""""ok": false"""))
        assertTrue(response.contains("METHOD_FAILED"))
    }

    private fun serverWithScreenshot(cacheDirectory: File, screenshotFile: File): BridgeServer {
        val environment = FakeBridgeEnvironment(
            screenshotCacheDirectory = cacheDirectory,
            screenSnapshot = BridgeScreenSnapshot(
                inspection = BridgeScreenInspection(activity = "MainActivity"),
                screenshot = ScreenshotInfo(fullPath = screenshotFile.absolutePath),
            ),
        )
        return BridgeServer(
            session = SidekickSession(
                packageName = "io.beyondwin.fixthis.sample",
                socketName = "fixthis_io.beyondwin.fixthis.sample",
                socketAddress = "localabstract:fixthis_io.beyondwin.fixthis.sample",
                token = "token",
                sidekickVersion = "0.1.0-test",
                bridgeProtocolVersion = BridgeProtocol.VERSION,
                createdAtEpochMillis = 1234L,
                processStartEpochMillis = 1234L,
            ),
            environment = environment,
        )
    }

    private class FakeBridgeEnvironment(
        private val screenshotCacheDirectory: File,
        private val screenSnapshot: BridgeScreenSnapshot,
    ) : BridgeEnvironment {
        override suspend fun status(): BridgeStatus = BridgeStatus(
            activity = "MainActivity",
            rootsCount = 0,
            sidekickVersion = "0.1.0-test",
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            sourceIndexAvailable = false,
        )

        override suspend fun inspectCurrentScreen(): BridgeScreenInspection = BridgeScreenInspection(activity = "MainActivity")

        override suspend fun captureScreenSnapshot(currentFocusOutput: String?): BridgeScreenSnapshot = screenSnapshot

        override suspend fun readSourceIndex(): BridgeSourceIndexResult = BridgeSourceIndexResult(sourceIndexAvailable = false)

        override suspend fun getLastScreenSnapshot(): BridgeScreenSnapshot? = screenSnapshot

        override suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult = BridgeNavigationResult(performed = false, action = request.action)

        override fun screenshotCacheDirectory(): File = screenshotCacheDirectory
    }

    private companion object {
        val PngHeader: ByteArray = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}

private fun screenshotFile(cacheDirectory: File, name: String, bytes: ByteArray): File {
    val directory = File(cacheDirectory, "2026-05-04").also { check(it.mkdirs() || it.exists()) }
    return File(directory, name).apply {
        writeBytes(bytes)
        deleteOnExit()
    }
}

private fun tempDirectory(prefix: String): File = createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }
