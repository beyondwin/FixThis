package io.beyondwin.fixthis.compose.sidekick.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.beyondwin.fixthis.compose.core.model.ActivityInfo
import io.beyondwin.fixthis.compose.core.model.AppInfo
import io.beyondwin.fixthis.compose.core.model.FixThisAnnotation
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SelectionInfo
import io.beyondwin.fixthis.compose.core.model.SelectionKind
import io.beyondwin.fixthis.compose.core.model.SelectionSource
import io.beyondwin.fixthis.compose.core.model.TapPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalFileExporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val exporter = LocalFileExporter(
        context = context,
        dateProvider = { "2026-05-04" },
    )

    @Test
    fun exportsMarkdownUnderFixThisDateDirectory() = runBlocking {
        val file = exporter.exportMarkdown(annotation())

        assertPathEndsWith("fixthis/2026-05-04/annotation-1.md", file)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("# FixThis Compose Feedback"))
        assertTrue(file.readText().contains("Change label to Pay immediately"))
    }

    @Test
    fun exportsJsonUnderFixThisDateDirectory() = runBlocking {
        val file = exporter.exportJson(annotation())

        assertPathEndsWith("fixthis/2026-05-04/annotation-1.json", file)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("\"id\": \"annotation-1\""))
        assertTrue(file.readText().contains("\"userComment\": \"Change label to Pay immediately\""))
    }

    private fun annotation(): FixThisAnnotation =
        FixThisAnnotation(
            id = "annotation-1",
            createdAtEpochMillis = 1234L,
            app = AppInfo(packageName = "io.beyondwin.fixthis.sample", versionName = "1.0", debuggable = true),
            activity = ActivityInfo(className = "io.beyondwin.fixthis.sample.MainActivity"),
            tap = TapPoint(xInWindow = 42f, yInWindow = 24f),
            selection = SelectionInfo(
                kind = SelectionKind.VISUAL_AREA,
                confidence = SelectionConfidence.MEDIUM,
                areaBoundsInWindow = FixThisRect(left = 0f, top = 0f, right = 100f, bottom = 100f),
                source = SelectionSource.AREA_SELECT,
            ),
            userComment = "Change label to Pay immediately",
        )

    private fun assertPathEndsWith(expectedSuffix: String, file: File) {
        assertTrue(
            "Expected path to end with $expectedSuffix but was ${file.absolutePath}",
            file.absolutePath.replace(File.separatorChar, '/').endsWith(expectedSuffix),
        )
    }
}
