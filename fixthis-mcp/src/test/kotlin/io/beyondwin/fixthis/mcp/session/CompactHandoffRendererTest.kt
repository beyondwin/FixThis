package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.assertTrue
import org.junit.Test

class CompactHandoffRendererTest {
    @Test
    fun renderEmitsViewportLineWhenScreenshotHasDimensions() {
        val session = SessionDto(
            sessionId = "session-vp",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    screenshot = SnapshotScreenshotDto(
                        desktopFullPath = "/some/path/screen.png",
                        width = 720,
                        height = 1480,
                    ),
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()
        val screenshotIdx = lines.indexOfFirst { it.startsWith("screenshot:") }
        assertTrue(screenshotIdx >= 0, "Expected a screenshot: line")
        val viewportIdx = lines.indexOfFirst { it.startsWith("viewport:") }
        assertTrue(viewportIdx >= 0, "Expected a viewport: line")
        assertTrue(viewportIdx > screenshotIdx, "viewport: line should come after screenshot: line")
        assertTrue(lines[viewportIdx].contains("720×1480"), "Expected 'viewport: 720×1480' but got: '${lines[viewportIdx]}'")
    }

    @Test
    fun renderOmitsViewportLineWhenDimensionsMissing() {
        val baseAnnotation = AnnotationDto(
            itemId = "i",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
            comment = "x",
            sequenceNumber = 1,
        )

        // width null → no viewport line
        val sessionWidthNull = SessionDto(
            sessionId = "session-wn",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    screenshot = SnapshotScreenshotDto(desktopFullPath = "/some/path/screen.png", width = null, height = 1480),
                ),
            ),
            items = listOf(baseAnnotation),
        )
        assertTrue(!CompactHandoffRenderer.render(sessionWidthNull).lines().any { it.startsWith("viewport:") },
            "Expected no viewport: line when width is null")

        // height null → no viewport line
        val sessionHeightNull = SessionDto(
            sessionId = "session-hn",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    screenshot = SnapshotScreenshotDto(desktopFullPath = "/some/path/screen.png", width = 720, height = null),
                ),
            ),
            items = listOf(baseAnnotation),
        )
        assertTrue(!CompactHandoffRenderer.render(sessionHeightNull).lines().any { it.startsWith("viewport:") },
            "Expected no viewport: line when height is null")

        // both null → no viewport line
        val sessionBothNull = SessionDto(
            sessionId = "session-bn",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    screenshot = SnapshotScreenshotDto(desktopFullPath = "/some/path/screen.png", width = null, height = null),
                ),
            ),
            items = listOf(baseAnnotation),
        )
        assertTrue(!CompactHandoffRenderer.render(sessionBothNull).lines().any { it.startsWith("viewport:") },
            "Expected no viewport: line when both width and height are null")
    }

    @Test
    fun renderEmitsTopLevelRuleAndScreenHeader() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(markdown.contains("Rule: source hints are candidates"))
        assertTrue(markdown.contains("Screen "))
    }
}
