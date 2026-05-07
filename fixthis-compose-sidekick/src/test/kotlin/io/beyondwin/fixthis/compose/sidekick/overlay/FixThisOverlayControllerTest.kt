package io.beyondwin.fixthis.compose.sidekick.overlay

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import io.beyondwin.fixthis.compose.core.model.ActivityInfo
import io.beyondwin.fixthis.compose.core.model.AppInfo
import io.beyondwin.fixthis.compose.core.model.FixThisAnnotation
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import io.beyondwin.fixthis.compose.core.model.TapPoint
import io.beyondwin.fixthis.compose.core.model.TreeKind
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.beyondwin.fixthis.compose.overlay.OverlayMode
import io.beyondwin.fixthis.compose.sidekick.capture.AnnotationCaptureController
import io.beyondwin.fixthis.compose.sidekick.export.ClipboardExportResult
import io.beyondwin.fixthis.compose.sidekick.inspect.InspectedComposeRoot
import io.beyondwin.fixthis.compose.sidekick.inspect.SemanticsInspectionResult
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FixThisOverlayControllerTest {
    @Test
    fun tapCaptureBuildsAnnotationWithScreenshotThenExportsCommentSheetActions() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        activity.setContentView(content)
        content.addView(FrameLayout(activity), ViewGroup.LayoutParams(120, 80))
        val node = node(
            uid = "pay-button",
            bounds = FixThisRect(10f, 10f, 120f, 64f),
            text = listOf("Pay now"),
            role = "Button",
        )
        val clipboard = RecordingClipboardExporter()
        val files = RecordingLocalFileExporter()
        val screenshots = RecordingScreenshotCapturer()
        val controller = FixThisOverlayController(
            activity = activity,
            inspector = RecordingSemanticsInspector(
                SemanticsInspectionResult(
                    roots = listOf(
                        InspectedComposeRoot(
                            rootIndex = 0,
                            boundsInWindow = FixThisRect(0f, 0f, 200f, 200f),
                            mergedNodes = listOf(node),
                            unmergedNodes = emptyList(),
                        ),
                    ),
                ),
            ),
            annotationController = AnnotationCaptureController(
                clock = { 1234L },
                idGenerator = { "annotation-1" },
            ),
            screenshotCapturer = screenshots,
            clipboardExporter = clipboard,
            localFileExporter = files,
            appInfoProvider = { AppInfo(packageName = "sample", versionName = "1.0", debuggable = true) },
            activityInfoProvider = { ActivityInfo(className = "MainActivity") },
        )

        controller.startSelection()
        controller.captureTap(xInWindow = 24f, yInWindow = 24f)
        controller.updateComment("Change label to Pay immediately")
        controller.copyMarkdown()
        controller.copyJson()
        controller.share()

        val mode = controller.mode
        assertTrue(mode is OverlayMode.Exported)
        assertEquals("annotation-1", (mode as OverlayMode.Exported).annotationId)
        assertEquals("annotation-1", screenshots.annotationIds.single())
        assertEquals(FixThisRect(10f, 10f, 120f, 64f), screenshots.selectedBounds.single())
        assertEquals("Change label to Pay immediately", clipboard.markdown.single().userComment)
        assertEquals("Change label to Pay immediately", clipboard.json.single().userComment)
        assertEquals("Change label to Pay immediately", files.markdown.single().userComment)
        assertNotNull(clipboard.markdown.single().screenshot?.fullPath)
        assertEquals(listOf("full"), clipboard.markdown.single().targetEvidence?.screenshotKinds)
    }

    @Test
    fun startFeedbackCaptureUsesProvidedSourceIndexAndResetsItAfterCapture() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        activity.setContentView(content)
        content.addView(FrameLayout(activity), ViewGroup.LayoutParams(120, 80))
        val node = node(
            uid = "primary",
            bounds = FixThisRect(0f, 0f, 160f, 48f),
            text = listOf("Sign In"),
            role = "Button",
            testTag = "comp:AppPrimaryButton:primary",
            actions = listOf("OnClick"),
        )
        val controller = FixThisOverlayController(
            activity = activity,
            inspector = RecordingSemanticsInspector(
                SemanticsInspectionResult(
                    roots = listOf(
                        InspectedComposeRoot(
                            rootIndex = 0,
                            boundsInWindow = FixThisRect(0f, 0f, 200f, 200f),
                            mergedNodes = listOf(node),
                            unmergedNodes = emptyList(),
                        ),
                    ),
                ),
            ),
            annotationController = AnnotationCaptureController(
                clock = { 1234L },
                idGenerator = { "annotation-1" },
            ),
            screenshotCapturer = RecordingScreenshotCapturer(),
            clipboardExporter = RecordingClipboardExporter(),
            localFileExporter = RecordingLocalFileExporter(),
            appInfoProvider = { AppInfo(packageName = "sample", versionName = "1.0", debuggable = true) },
            activityInfoProvider = { ActivityInfo(className = "MainActivity") },
        )
        val sourceFile = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt"
        val sourceIndex = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = sourceFile,
                    line = 12,
                    symbols = listOf("AppPrimaryButton"),
                ),
            ),
        )

        val capture = async {
            controller.startFeedbackCapture(
                timeoutMillis = 5_000L,
                pollMillis = 10L,
                sourceIndex = sourceIndex,
            )
        }
        delay(50L)
        controller.captureTap(xInWindow = 10f, yInWindow = 10f)
        controller.copyJson()

        val result = withTimeoutOrNull(500L) { capture.await() }
        assertNotNull(result)
        assertTrue(requireNotNull(result).submitted)
        assertEquals(sourceFile, result.annotation?.targetEvidence?.sourceInterpretation?.topCandidate?.file)

        controller.startSelection()
        controller.captureTap(xInWindow = 10f, yInWindow = 10f)

        assertNull(controller.lastAnnotation?.targetEvidence?.sourceInterpretation?.topCandidate)
    }

    @Test
    fun startFeedbackCaptureRejectsConcurrentCapture() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controller = controller(activity)

        val first = async { controller.startFeedbackCapture(timeoutMillis = 5_000L, pollMillis = 10L) }
        delay(50L)
        val second = controller.startFeedbackCapture(timeoutMillis = 5_000L, pollMillis = 10L)
        controller.cancel()

        assertTrue(second.rejected)
        assertFalse(requireNotNull(withTimeoutOrNull(500L) { first.await() }).submitted)
    }

    @Test
    fun repeatedStartSelectionRefreshesSelectMode() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controller = controller(activity)

        controller.startSelection()
        val firstRequestId = (controller.mode as OverlayMode.Select).requestId
        controller.startSelection()
        val secondRequestId = (controller.mode as OverlayMode.Select).requestId

        assertNotNull(firstRequestId)
        assertNotNull(secondRequestId)
        assertTrue(firstRequestId != secondRequestId)
    }

    @Test
    fun updateCommentAfterCaptureRefreshesCommentingMode() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(FrameLayout(activity))
        val controller = controller(activity)

        controller.startSelection()
        controller.captureTap(xInWindow = 24f, yInWindow = 24f)
        controller.updateComment("First edit")
        controller.updateComment("Second edit")

        val mode = controller.mode
        assertTrue(mode is OverlayMode.Commenting)
        assertEquals("Second edit", (mode as OverlayMode.Commenting).draft.userComment)
    }

    @Test
    fun captureTapWithoutSelectionIsRejectedByOverlayTransition() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(FrameLayout(activity))
        val controller = controller(activity)

        try {
            controller.captureTap(xInWindow = 24f, yInWindow = 24f)
            fail("Expected capture without Select mode to throw IllegalStateException")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("invalid overlay transition"))
        }
    }

    @Test
    fun cancelWakesFeedbackCaptureWaiter() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controller = controller(activity)

        val capture = async { controller.startFeedbackCapture(timeoutMillis = 5_000L, pollMillis = 10L) }
        delay(50L)
        controller.cancel()

        val result = withTimeoutOrNull(500L) { capture.await() }
        assertNotNull(result)
        assertFalse(requireNotNull(result).submitted)
        assertFalse(result.rejected)
    }

    private fun controller(activity: Activity): FixThisOverlayController =
        FixThisOverlayController(
            activity = activity,
            inspector = RecordingSemanticsInspector(SemanticsInspectionResult(roots = emptyList())),
            annotationController = AnnotationCaptureController(
                clock = { 1234L },
                idGenerator = { "annotation-1" },
            ),
            screenshotCapturer = RecordingScreenshotCapturer(),
            clipboardExporter = RecordingClipboardExporter(),
            localFileExporter = RecordingLocalFileExporter(),
            appInfoProvider = { AppInfo(packageName = "sample", versionName = "1.0", debuggable = true) },
            activityInfoProvider = { ActivityInfo(className = "MainActivity") },
        )

    private class RecordingSemanticsInspector(
        private val result: SemanticsInspectionResult,
    ) : SemanticsInspectorPort {
        override fun inspect(decorView: android.view.View): SemanticsInspectionResult = result
    }

    private class RecordingScreenshotCapturer : ScreenshotCapturerPort {
        val annotationIds = mutableListOf<String>()
        val selectedBounds = mutableListOf<FixThisRect?>()

        override suspend fun capture(
            activity: Activity,
            annotationId: String,
            selectedBounds: FixThisRect?,
        ): ScreenshotInfo {
            annotationIds += annotationId
            this.selectedBounds += selectedBounds
            return ScreenshotInfo(fullPath = "/tmp/$annotationId-full.png", width = 200, height = 200)
        }
    }

    private class RecordingClipboardExporter : ClipboardExporterPort {
        val markdown = mutableListOf<FixThisAnnotation>()
        val json = mutableListOf<FixThisAnnotation>()

        override fun copyMarkdown(annotation: FixThisAnnotation): ClipboardExportResult {
            markdown += annotation
            return ClipboardExportResult("markdown", "markdown", "warning")
        }

        override fun copyJson(annotation: FixThisAnnotation): ClipboardExportResult {
            json += annotation
            return ClipboardExportResult("json", "json", "warning")
        }
    }

    private class RecordingLocalFileExporter : LocalFileExporterPort {
        val markdown = mutableListOf<FixThisAnnotation>()

        override suspend fun exportMarkdown(annotation: FixThisAnnotation): File {
            markdown += annotation
            return File("/tmp/${annotation.id}.md")
        }
    }

    private fun node(
        uid: String,
        bounds: FixThisRect,
        text: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
        actions: List<String> = emptyList(),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        role = role,
        testTag = testTag,
        actions = actions,
    )
}
