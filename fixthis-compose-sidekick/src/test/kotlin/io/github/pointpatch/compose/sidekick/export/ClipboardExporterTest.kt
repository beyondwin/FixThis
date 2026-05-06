package io.github.pointpatch.compose.sidekick.export

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardExporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val exporter = ClipboardExporter(context)

    @Test
    fun copiesMarkdownWithStableLabelAndWarningContract() {
        val result = exporter.copyMarkdown(annotation(userComment = "Change label to Pay immediately"))

        assertEquals("PointPatch Markdown", result.label)
        assertEquals(ClipboardExporter.SCREENSHOT_WARNING, result.warning)
        assertTrue(result.content.contains("# PointPatch Compose Feedback"))
        assertTrue(result.content.contains("Change label to Pay immediately"))

        val clip = clipboard().primaryClip
        assertEquals("PointPatch Markdown", clip?.description?.label?.toString())
        assertEquals(result.content, clip?.getItemAt(0)?.coerceToText(context).toString())
    }

    @Test
    fun copiesJsonWithStableLabelAndFormatterContent() {
        val result = exporter.copyJson(annotation(userComment = "Change label to Pay immediately"))

        assertEquals("PointPatch JSON", result.label)
        assertEquals(ClipboardExporter.SCREENSHOT_WARNING, result.warning)
        assertTrue(result.content.contains("\"id\": \"annotation-1\""))
        assertTrue(result.content.contains("\"userComment\": \"Change label to Pay immediately\""))

        val clip = clipboard().primaryClip
        assertEquals("PointPatch JSON", clip?.description?.label?.toString())
        assertEquals(result.content, clip?.getItemAt(0)?.coerceToText(context).toString())
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun annotation(userComment: String): PointPatchAnnotation =
        PointPatchAnnotation(
            id = "annotation-1",
            createdAtEpochMillis = 1234L,
            app = AppInfo(packageName = "io.github.pointpatch.sample", versionName = "1.0", debuggable = true),
            activity = ActivityInfo(className = "io.github.pointpatch.sample.MainActivity"),
            tap = TapPoint(xInWindow = 42f, yInWindow = 24f),
            selection = SelectionInfo(
                kind = SelectionKind.SEMANTICS_NODE,
                confidence = SelectionConfidence.HIGH,
                selectedUid = "pay-button",
                source = SelectionSource.TAP_SELECT,
            ),
            selectedNode = PointPatchNode(
                uid = "pay-button",
                composeNodeId = 1,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = PointPatchRect(left = 10f, top = 20f, right = 110f, bottom = 64f),
                text = listOf("Pay now"),
                role = "Button",
            ),
            screenshot = ScreenshotInfo(
                fullPath = "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/2026-05-04/annotation-1-full.png",
                cropPath = "/data/user/0/io.github.pointpatch.sample/cache/pointpatch/2026-05-04/annotation-1-crop.png",
                width = 400,
                height = 800,
            ),
            userComment = userComment,
        )
}
