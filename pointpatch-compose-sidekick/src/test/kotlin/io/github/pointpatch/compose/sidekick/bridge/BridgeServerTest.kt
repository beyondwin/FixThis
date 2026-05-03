package io.github.pointpatch.compose.sidekick.bridge

import io.github.pointpatch.compose.core.model.ActivityInfo
import io.github.pointpatch.compose.core.model.AppInfo
import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.ScreenshotInfo
import io.github.pointpatch.compose.core.model.SelectionConfidence
import io.github.pointpatch.compose.core.model.SelectionInfo
import io.github.pointpatch.compose.core.model.SelectionKind
import io.github.pointpatch.compose.core.model.SelectionSource
import io.github.pointpatch.compose.core.model.TapPoint
import io.github.pointpatch.compose.core.model.TreeKind
import java.io.File
import kotlinx.coroutines.runBlocking
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
    fun readScreenshotReturnsBase64ForLastAnnotationPath() = runBlocking {
        val tempFile = File.createTempFile("pointpatch", ".png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            deleteOnExit()
        }
        val environment = RecordingBridgeEnvironment(
            lastAnnotation = annotation().copy(screenshot = ScreenshotInfo(fullPath = tempFile.absolutePath)),
        )
        val server = server(environment = environment)

        val response = server.handleRequestForTest(
            """{"id":"1","token":"token","method":"readScreenshot","params":{"kind":"full"}}""",
        )

        assertTrue(response.contains(""""base64": "AQIDBA==""""))
        assertTrue(response.contains(""""mimeType": "image/png""""))
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
                packageName = "io.github.pointpatch.sample",
                socketName = "pointpatch_io.github.pointpatch.sample",
                socketAddress = "localabstract:pointpatch_io.github.pointpatch.sample",
                token = "token",
                sidekickVersion = "0.1.0-test",
                bridgeProtocolVersion = BridgeProtocol.VERSION,
                processStartEpochMillis = 1234L,
            ),
            environment = environment,
        )

    private class RecordingBridgeEnvironment(
        private val feedbackResult: BridgeFeedbackCaptureResult = BridgeFeedbackCaptureResult.Timeout(120_000L),
        private val lastAnnotation: PointPatchAnnotation? = annotation(),
    ) : BridgeEnvironment {
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
                        boundsInWindow = PointPatchRect(0f, 0f, 200f, 100f),
                        mergedNodes = listOf(node()),
                        unmergedNodes = emptyList(),
                    ),
                ),
            )

        override suspend fun startFeedbackCapture(timeoutMillis: Long): BridgeFeedbackCaptureResult = feedbackResult

        override fun getLastAnnotation(): PointPatchAnnotation? = lastAnnotation
    }
}

private fun node(): PointPatchNode =
    PointPatchNode(
        uid = "pay-button",
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = PointPatchRect(10f, 10f, 100f, 44f),
        text = listOf("Pay now"),
        role = "Button",
    )

private fun annotation(): PointPatchAnnotation =
    PointPatchAnnotation(
        id = "annotation-1",
        createdAtEpochMillis = 1234L,
        app = AppInfo(packageName = "io.github.pointpatch.sample", versionName = "1.0", debuggable = true),
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
