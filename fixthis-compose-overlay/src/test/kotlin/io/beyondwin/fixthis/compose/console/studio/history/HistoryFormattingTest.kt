package io.beyondwin.fixthis.compose.console.studio.history

import io.beyondwin.fixthis.compose.console.studio.model.Annotation
import io.beyondwin.fixthis.compose.console.studio.model.AnnotationStatus
import io.beyondwin.fixthis.compose.console.studio.model.RectPercent
import io.beyondwin.fixthis.compose.console.studio.model.Severity
import io.beyondwin.fixthis.compose.console.studio.model.Snapshot
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HistoryFormattingTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun historyMetaUsesEnglishShortMonthDayAndTwentyFourHourTime() {
        val snapshot = Snapshot(
            id = "snap-1",
            title = "Wallet home v3",
            author = "You",
            createdAtEpochMillis = 1_746_454_920_000L,
            annotations = emptyList(),
        )

        assertEquals("You · May 5 · 14:22", formatHistoryMeta(snapshot))
    }

    @Test
    fun historyStatCountsIncludeOpenAndResolvedOnly() {
        val counts = historyStatCounts(
            listOf(
                annotation("open", AnnotationStatus.OPEN),
                annotation("progress", AnnotationStatus.IN_PROGRESS),
                annotation("resolved-1", AnnotationStatus.RESOLVED),
                annotation("resolved-2", AnnotationStatus.RESOLVED),
            )
        )

        assertEquals(1, counts.open)
        assertEquals(2, counts.resolved)
    }
}

private fun annotation(
    id: String,
    status: AnnotationStatus,
): Annotation =
    Annotation(
        id = id,
        label = id,
        severity = Severity.MED,
        status = status,
        comment = "",
        rectPercent = RectPercent(1f, 2f, 3f, 4f),
    )
