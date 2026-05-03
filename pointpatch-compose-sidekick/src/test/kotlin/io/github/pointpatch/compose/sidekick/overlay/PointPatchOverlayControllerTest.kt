package io.github.pointpatch.compose.sidekick.overlay

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.pointpatch.compose.core.model.ActivityInfo
import io.github.pointpatch.compose.core.model.AppInfo
import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.ScreenshotInfo
import io.github.pointpatch.compose.core.model.TapPoint
import io.github.pointpatch.compose.core.model.TreeKind
import io.github.pointpatch.compose.overlay.OverlayMode
import io.github.pointpatch.compose.sidekick.capture.AnnotationCaptureController
import io.github.pointpatch.compose.sidekick.export.ClipboardExportResult
import io.github.pointpatch.compose.sidekick.inspect.InspectedComposeRoot
import io.github.pointpatch.compose.sidekick.inspect.SemanticsInspectionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PointPatchOverlayControllerTest {
    @Test
    fun tapCaptureBuildsAnnotationWithScreenshotThenExportsCommentSheetActions() = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        activity.setContentView(content)
        content.addView(FrameLayout(activity), ViewGroup.LayoutParams(120, 80))
        val node = node(
            uid = "pay-button",
            bounds = PointPatchRect(10f, 10f, 120f, 64f),
            text = listOf("Pay now"),
            role = "Button",
        )
        val clipboard = RecordingClipboardExporter()
        val files = RecordingLocalFileExporter()
        val screenshots = RecordingScreenshotCapturer()
        val controller = PointPatchOverlayController(
            activity = activity,
            inspector = RecordingSemanticsInspector(
                SemanticsInspectionResult(
                    roots = listOf(
                        InspectedComposeRoot(
                            rootIndex = 0,
                            boundsInWindow = PointPatchRect(0f, 0f, 200f, 200f),
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
        assertEquals(PointPatchRect(10f, 10f, 120f, 64f), screenshots.selectedBounds.single())
        assertEquals("Change label to Pay immediately", clipboard.markdown.single().userComment)
        assertEquals("Change label to Pay immediately", clipboard.json.single().userComment)
        assertEquals("Change label to Pay immediately", files.markdown.single().userComment)
        assertNotNull(clipboard.markdown.single().screenshot?.fullPath)
    }

    private class RecordingSemanticsInspector(
        private val result: SemanticsInspectionResult,
    ) : SemanticsInspectorPort {
        override fun inspect(decorView: android.view.View): SemanticsInspectionResult = result
    }

    private class RecordingScreenshotCapturer : ScreenshotCapturerPort {
        val annotationIds = mutableListOf<String>()
        val selectedBounds = mutableListOf<PointPatchRect?>()

        override suspend fun capture(
            activity: Activity,
            annotationId: String,
            selectedBounds: PointPatchRect?,
        ): ScreenshotInfo {
            annotationIds += annotationId
            this.selectedBounds += selectedBounds
            return ScreenshotInfo(fullPath = "/tmp/$annotationId-full.png", width = 200, height = 200)
        }
    }

    private class RecordingClipboardExporter : ClipboardExporterPort {
        val markdown = mutableListOf<PointPatchAnnotation>()
        val json = mutableListOf<PointPatchAnnotation>()

        override fun copyMarkdown(annotation: PointPatchAnnotation): ClipboardExportResult {
            markdown += annotation
            return ClipboardExportResult("markdown", "markdown", "warning")
        }

        override fun copyJson(annotation: PointPatchAnnotation): ClipboardExportResult {
            json += annotation
            return ClipboardExportResult("json", "json", "warning")
        }
    }

    private class RecordingLocalFileExporter : LocalFileExporterPort {
        val markdown = mutableListOf<PointPatchAnnotation>()

        override suspend fun exportMarkdown(annotation: PointPatchAnnotation): File {
            markdown += annotation
            return File("/tmp/${annotation.id}.md")
        }
    }

    private fun node(
        uid: String,
        bounds: PointPatchRect,
        text: List<String> = emptyList(),
        role: String? = null,
    ): PointPatchNode = PointPatchNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        role = role,
    )
}
