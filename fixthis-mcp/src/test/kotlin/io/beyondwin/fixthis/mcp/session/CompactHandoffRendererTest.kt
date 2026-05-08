package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.assertTrue
import org.junit.Test

class CompactHandoffRendererTest {
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
