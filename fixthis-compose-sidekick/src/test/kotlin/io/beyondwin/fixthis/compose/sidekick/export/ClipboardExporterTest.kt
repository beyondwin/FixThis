package io.beyondwin.fixthis.compose.sidekick.export

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

        assertEquals("FixThis Markdown", result.label)
        assertEquals(ClipboardExporter.SCREENSHOT_WARNING, result.warning)
        assertTrue(result.content.contains("# FixThis Compose Feedback"))
        assertTrue(result.content.contains("Change label to Pay immediately"))

        val clip = clipboard().primaryClip
        assertEquals("FixThis Markdown", clip?.description?.label?.toString())
        assertEquals(result.content, clip?.getItemAt(0)?.coerceToText(context).toString())
    }

    @Test
    fun copiesJsonWithStableLabelAndFormatterContent() {
        val result = exporter.copyJson(annotation(userComment = "Change label to Pay immediately"))

        assertEquals("FixThis JSON", result.label)
        assertEquals(ClipboardExporter.SCREENSHOT_WARNING, result.warning)
        assertTrue(result.content.contains("\"id\": \"annotation-1\""))
        assertTrue(result.content.contains("\"userComment\": \"Change label to Pay immediately\""))

        val clip = clipboard().primaryClip
        assertEquals("FixThis JSON", clip?.description?.label?.toString())
        assertEquals(result.content, clip?.getItemAt(0)?.coerceToText(context).toString())
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun annotation(userComment: String): FixThisAnnotation =
        FixThisAnnotation(
            id = "annotation-1",
            createdAtEpochMillis = 1234L,
            app = AppInfo(packageName = "io.beyondwin.fixthis.sample", versionName = "1.0", debuggable = true),
            activity = ActivityInfo(className = "io.beyondwin.fixthis.sample.MainActivity"),
            tap = TapPoint(xInWindow = 42f, yInWindow = 24f),
            selection = SelectionInfo(
                kind = SelectionKind.SEMANTICS_NODE,
                confidence = SelectionConfidence.HIGH,
                selectedUid = "pay-button",
                source = SelectionSource.TAP_SELECT,
            ),
            selectedNode = FixThisNode(
                uid = "pay-button",
                composeNodeId = 1,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = FixThisRect(left = 10f, top = 20f, right = 110f, bottom = 64f),
                text = listOf("Pay now"),
                role = "Button",
            ),
            screenshot = ScreenshotInfo(
                fullPath = "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/2026-05-04/annotation-1-full.png",
                cropPath = "/data/user/0/io.beyondwin.fixthis.sample/cache/fixthis/2026-05-04/annotation-1-crop.png",
                width = 400,
                height = 800,
            ),
            userComment = userComment,
        )
}
