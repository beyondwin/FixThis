package io.beyondwin.fixthis.compose.sidekick.bridge

import io.beyondwin.fixthis.compose.core.model.ActivityInfo
import io.beyondwin.fixthis.compose.core.model.AppInfo
import io.beyondwin.fixthis.compose.core.model.FixThisAnnotation
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SelectionInfo
import io.beyondwin.fixthis.compose.core.model.SelectionKind
import io.beyondwin.fixthis.compose.core.model.SelectionSource
import io.beyondwin.fixthis.compose.core.model.TapPoint
import io.beyondwin.fixthis.compose.core.model.TreeKind
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BridgeServerTest {
    @Test
    fun rejectsRequestWithMissingOrMismatchedToken() = runBlocking {
        val server = server()

        val missing = server.handleRequestForTest("""{"id":"1","method":"status"}""")
        val mismatched = server.handleRequestForTest("""{"id":"2","token":"wrong","method":"status"}""")

        assertTrue(missing.contains(""""ok": false"""))
        assertTrue(missing.contains("UNAUTHORIZED"))
        assertTrue(mismatched.contains(""""ok": false"""))
        assertTrue(mismatched.contains("UNAUTHORIZED"))
    }

    @Test
    fun statusAndInspectCurrentScreenUseEnvironment() = runBlocking {
        val server = server()

        val status = server.handleRequestForTest("""{"id":"1","token":"token","method":"status"}""")
        val inspection = server.handleRequestForTest(
            """{"id":"2","token":"token","method":"inspectCurrentScreen"}""",
        )

        assertTrue(status.contains(""""activity": "MainActivity""""))
        assertTrue(status.contains(""""rootsCount": 1"""))
        assertTrue(status.contains(""""sourceIndexAvailable": true"""))
        assertTrue(inspection.contains(""""uid": "pay-button""""))
        assertTrue(inspection.contains("Pay now"))
    }

    @Test
    fun statusReportsTargetEvidenceCapabilities() = runBlocking {
        val server = server()

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"status"}""",
        )
        val result = BridgeProtocol.json.parseToJsonElement(response).jsonObject.getValue("result").jsonObject
        val capabilities = result.getValue("capabilities").jsonObject

        assertTrue(capabilities.getValue("targetEvidence").jsonPrimitive.boolean)
        assertEquals(
            listOf("compact", "precise", "full"),
            capabilities.getValue("detailModes").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(capabilities.getValue("composableIdentity").jsonPrimitive.boolean)
        assertEquals(BridgeProtocol.VERSION, result.getValue("bridgeProtocolVersion").jsonPrimitive.content)
    }

    @Test
    fun startFeedbackCaptureDoesNotFakeSuccessOnTimeout() = runBlocking {
        val server = server(environment = RecordingBridgeEnvironment(feedbackResult = BridgeFeedbackCaptureResult.Timeout(25L)))

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"startFeedbackCapture","params":{"timeoutMillis":25}}""",
        )

        assertTrue(response.contains(""""ok": true"""))
        assertTrue(response.contains(""""submitted": false"""))
        assertTrue(response.contains(""""timedOut": true"""))
    }

    @Test
    fun captureScreenSnapshotReturnsSnapshotResult() = runBlocking {
        val server = server(
            environment = RecordingBridgeEnvironment(
                screenSnapshot = BridgeScreenSnapshot(
                    activity = "MainActivity",
                    inspection = BridgeScreenInspection(activity = "MainActivity"),
                    screenshot = ScreenshotInfo(fullPath = "/cache/screen.png"),
                    sourceIndexAvailable = true,
                ),
            ),
        )

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"captureScreenSnapshot","params":{}}""",
        )

        assertTrue(response.contains("MainActivity"))
        assertTrue(response.contains("/cache/screen.png"))
        assertTrue(response.contains("sourceIndexAvailable"))
        assertFalse(response.contains("sourceIndex\":"))
        assertFalse(response.contains("FormScreen.kt"))
    }

    @Test
    fun readSourceIndexReturnsBoundedResultSeparatelyFromSnapshot() = runBlocking {
        val server = server(
            environment = RecordingBridgeEnvironment(
                sourceIndexResult = BridgeSourceIndexResult(
                    sourceIndexAvailable = true,
                    sourceIndex = SourceIndex(
                        entries = listOf(
                            SourceIndexEntry(
                                file = "sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt",
                                line = 37,
                                text = listOf("Email address"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readSourceIndex","params":{}}""",
        )

        assertTrue(response.contains("sourceIndexAvailable"))
        assertTrue(response.contains("sourceIndex"))
        assertTrue(response.contains("FormScreen.kt"))
        assertFalse(response.contains("sourceIndexError"))
    }

    @Test
    fun readSourceIndexCanReportUnavailableWithError() = runBlocking {
        val server = server(
            environment = RecordingBridgeEnvironment(
                sourceIndexResult = BridgeSourceIndexResult(
                    sourceIndexAvailable = false,
                    sourceIndexError = "Source index asset is too large",
                ),
            ),
        )

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readSourceIndex","params":{}}""",
        )

        assertTrue(response.contains(""""sourceIndexAvailable": false"""))
        assertTrue(response.contains("Source index asset is too large"))
        assertFalse(response.contains(""""sourceIndex":"""))
    }

    @Test
    fun performNavigationRoutesToEnvironment() = runBlocking {
        val environment = RecordingBridgeEnvironment()
        val server = server(environment = environment)
        val request = BridgeRequest(
            id = "nav-1",
            token = "token",
            method = "performNavigation",
            params = BridgeProtocol.json.encodeToJsonElement(
                BridgeNavigationRequest.serializer(),
                BridgeNavigationRequest(action = BridgeNavigationAction.BACK),
            ).jsonObject,
        )

        val response = server.handleRequestForTest(
            BridgeProtocol.json.encodeToString(BridgeRequest.serializer(), request),
        )

        assertTrue(response.contains(""""performed": true"""))
        assertEquals(BridgeNavigationAction.BACK, environment.navigationRequests.single().action)
    }

    @Test
    fun readScreenshotReturnsBase64ForLastAnnotationPath() = runBlocking {
        val cacheDirectory = tempDirectory(prefix = "fixthis-cache")
        val tempFile = screenshotFile(cacheDirectory, "annotation-1-full.png", PngHeader)
        val screenFile = screenshotFile(cacheDirectory, "screen-1-full.png", PngHeader + byteArrayOf(1, 2, 3))
        val environment = RecordingBridgeEnvironment(
            lastAnnotation = annotation().copy(screenshot = ScreenshotInfo(fullPath = tempFile.absolutePath)),
            screenSnapshot = BridgeScreenSnapshot(
                inspection = BridgeScreenInspection(activity = "MainActivity"),
                screenshot = ScreenshotInfo(fullPath = screenFile.absolutePath),
            ),
            screenshotCacheDirectory = cacheDirectory,
        )
        val server = server(environment = environment)

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readScreenshot","params":{"kind":"full"}}""",
        )

        assertTrue(response.contains(""""base64": "iVBORw0KGgo=""""))
        assertTrue(response.contains(""""mimeType": "image/png""""))
        assertTrue(response.contains(tempFile.absolutePath))
        assertFalse(response.contains(screenFile.absolutePath))
    }

    @Test
    fun readScreenshotReturnsBase64ForLastScreenSnapshotPathWhenRequested() = runBlocking {
        val cacheDirectory = tempDirectory(prefix = "fixthis-cache")
        val screenFile = screenshotFile(cacheDirectory, "screen-1-full.png", PngHeader)
        val environment = RecordingBridgeEnvironment(
            screenSnapshot = BridgeScreenSnapshot(
                inspection = BridgeScreenInspection(activity = "MainActivity"),
                screenshot = ScreenshotInfo(fullPath = screenFile.absolutePath),
            ),
            screenshotCacheDirectory = cacheDirectory,
        )
        val server = server(environment = environment)

        server.handleRequestForTest(
            """{"id":"1","token":"token","method":"captureScreenSnapshot","params":{}}""",
        )
        val response = server.handleRequestForTest(
            """{"id":"2","token":"token","method":"readScreenshot","params":{"source":"screenSnapshot","kind":"full"}}""",
        )

        assertTrue(response.contains(""""base64": "iVBORw0KGgo=""""))
        assertTrue(response.contains(""""mimeType": "image/png""""))
        assertTrue(response.contains(screenFile.absolutePath))
    }

    @Test
    fun readScreenshotRejectsCallerSuppliedPath() = runBlocking {
        val cacheDirectory = tempDirectory(prefix = "fixthis-cache")
        val allowed = screenshotFile(cacheDirectory, "annotation-1-full.png", PngHeader)
        val secret = File.createTempFile("fixthis-secret", ".png").apply {
            writeBytes(PngHeader + byteArrayOf(9, 9, 9))
            deleteOnExit()
        }
        val environment = RecordingBridgeEnvironment(
            lastAnnotation = annotation().copy(screenshot = ScreenshotInfo(fullPath = allowed.absolutePath)),
            screenshotCacheDirectory = cacheDirectory,
        )
        val server = server(environment = environment)

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readScreenshot","params":{"path":"${secret.absolutePath}"}}""",
        )

        assertTrue(response.contains(""""ok": false"""))
        assertTrue(response.contains("METHOD_FAILED"))
        assertFalse(response.contains("CQkJ"))
    }

    @Test
    fun readScreenshotRejectsAnnotationPathOutsideScreenshotCache() = runBlocking {
        val cacheDirectory = tempDirectory(prefix = "fixthis-cache")
        val outside = File.createTempFile("fixthis-outside", ".png").apply {
            writeBytes(PngHeader)
            deleteOnExit()
        }
        val environment = RecordingBridgeEnvironment(
            lastAnnotation = annotation().copy(screenshot = ScreenshotInfo(fullPath = outside.absolutePath)),
            screenshotCacheDirectory = cacheDirectory,
        )
        val server = server(environment = environment)

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readScreenshot","params":{"kind":"full"}}""",
        )

        assertTrue(response.contains(""""ok": false"""))
        assertTrue(response.contains("METHOD_FAILED"))
    }

    @Test
    fun readScreenshotRejectsNonPngAnnotationPath() = runBlocking {
        val cacheDirectory = tempDirectory(prefix = "fixthis-cache")
        val textFile = screenshotFile(cacheDirectory, "annotation-1-full.txt", PngHeader)
        val environment = RecordingBridgeEnvironment(
            lastAnnotation = annotation().copy(screenshot = ScreenshotInfo(fullPath = textFile.absolutePath)),
            screenshotCacheDirectory = cacheDirectory,
        )
        val server = server(environment = environment)

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readScreenshot","params":{"kind":"full"}}""",
        )

        assertTrue(response.contains(""""ok": false"""))
        assertTrue(response.contains("METHOD_FAILED"))
    }

    @Test
    fun readScreenshotRejectsOversizedAnnotationPath() = runBlocking {
        val cacheDirectory = tempDirectory(prefix = "fixthis-cache")
        val oversized = screenshotFile(cacheDirectory, "annotation-1-full.png", PngHeader).apply {
            RandomAccessFile(this, "rw").use { file -> file.setLength(16L * 1024L * 1024L + 1L) }
        }
        val environment = RecordingBridgeEnvironment(
            lastAnnotation = annotation().copy(screenshot = ScreenshotInfo(fullPath = oversized.absolutePath)),
            screenshotCacheDirectory = cacheDirectory,
        )
        val server = server(environment = environment)

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readScreenshot","params":{"kind":"full"}}""",
        )

        assertTrue(response.contains(""""ok": false"""))
        assertTrue(response.contains("METHOD_FAILED"))
    }

    @Test
    fun verifyUiChangeReportsFalseWhenExpectedTextIsAbsent() = runBlocking {
        val server = server()

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"verifyUiChange","params":{"expectedText":"Refund now"}}""",
        )

        assertTrue(response.contains(""""verified": false"""))
        assertFalse(response.contains(""""verified": true"""))
    }

    private fun server(environment: BridgeEnvironment = RecordingBridgeEnvironment()): BridgeServer =
        BridgeServer(
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

    private class RecordingBridgeEnvironment(
        private val feedbackResult: BridgeFeedbackCaptureResult = BridgeFeedbackCaptureResult.Timeout(120_000L),
        private val lastAnnotation: FixThisAnnotation? = annotation(),
        private val screenshotCacheDirectory: File = tempDirectory(prefix = "fixthis-cache"),
        private val screenSnapshot: BridgeScreenSnapshot = BridgeScreenSnapshot(
            inspection = BridgeScreenInspection(activity = "MainActivity"),
        ),
        private val sourceIndexResult: BridgeSourceIndexResult = BridgeSourceIndexResult(sourceIndexAvailable = true),
    ) : BridgeEnvironment {
        private var lastScreenSnapshot: BridgeScreenSnapshot? = null
        val navigationRequests: MutableList<BridgeNavigationRequest> = mutableListOf()

        override suspend fun status(): BridgeStatus =
            BridgeStatus(
                activity = "MainActivity",
                rootsCount = 1,
                sidekickVersion = "0.1.0-test",
                bridgeProtocolVersion = BridgeProtocol.VERSION,
                sourceIndexAvailable = true,
            )

        override suspend fun inspectCurrentScreen(): BridgeScreenInspection =
            BridgeScreenInspection(
                activity = "MainActivity",
                roots = listOf(
                    BridgeInspectedRoot(
                        rootIndex = 0,
                        boundsInWindow = FixThisRect(0f, 0f, 200f, 100f),
                        mergedNodes = listOf(node()),
                        unmergedNodes = emptyList(),
                    ),
                ),
            )

        override suspend fun startFeedbackCapture(timeoutMillis: Long): BridgeFeedbackCaptureResult = feedbackResult

        override suspend fun captureScreenSnapshot(): BridgeScreenSnapshot =
            screenSnapshot.also { lastScreenSnapshot = it }

        override suspend fun readSourceIndex(): BridgeSourceIndexResult = sourceIndexResult

        override suspend fun getLastScreenSnapshot(): BridgeScreenSnapshot? = lastScreenSnapshot

        override suspend fun getLastAnnotation(): FixThisAnnotation? = lastAnnotation

        override suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult {
            navigationRequests += request
            return BridgeNavigationResult(
                performed = true,
                action = request.action,
                activity = "MainActivity",
            )
        }

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

private fun tempDirectory(prefix: String): File =
    createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }

private fun node(): FixThisNode =
    FixThisNode(
        uid = "pay-button",
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(10f, 10f, 100f, 44f),
        text = listOf("Pay now"),
        role = "Button",
    )

private fun annotation(): FixThisAnnotation =
    FixThisAnnotation(
        id = "annotation-1",
        createdAtEpochMillis = 1234L,
        app = AppInfo(packageName = "io.beyondwin.fixthis.sample", versionName = "1.0", debuggable = true),
        activity = ActivityInfo(className = "MainActivity"),
        tap = TapPoint(xInWindow = 24f, yInWindow = 24f),
        selection = SelectionInfo(
            kind = SelectionKind.SEMANTICS_NODE,
            confidence = SelectionConfidence.HIGH,
            selectedUid = "pay-button",
            source = SelectionSource.TAP_SELECT,
        ),
        selectedNode = node(),
        userComment = "Make the pay button clearer",
    )
