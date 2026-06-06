package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewSnapshotCache
import io.github.beyondwin.fixthis.mcp.session.preview.ScreenshotArtifactPromoter
import io.github.beyondwin.fixthis.mcp.session.source.SourceIndexRegistry
import io.github.beyondwin.fixthis.mcp.session.target.TargetEvidenceService
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnnotationWorkflowTest {

    @Test
    fun addAreaFeedbackPersistsItem() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1", "item-1"))
        val session = fixture.openSession()
        val screen = fixture.captureScreen(session.sessionId)

        val item = fixture.repository.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = FixThisRect(1f, 2f, 3f, 4f),
            comment = "Fix spacing",
        )

        assertEquals("item-1", item.itemId)
        assertEquals("Fix spacing", item.comment)
        assertEquals(AnnotationStatusDto.READY, item.status)
    }

    @Test
    fun addAreaFeedbackWithBlankCommentStaysOpen() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1", "item-1"))
        val session = fixture.openSession()
        val screen = fixture.captureScreen(session.sessionId)

        val item = fixture.repository.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = FixThisRect(1f, 2f, 3f, 4f),
            comment = " ",
        )

        assertEquals(AnnotationStatusDto.OPEN, item.status)
    }

    @Test
    fun clearDraftItemsRemovesAllOpenItems() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1", "item-1"))
        val session = fixture.openSession()
        val screen = fixture.captureScreen(session.sessionId)
        fixture.repository.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "comment",
        )

        val updated = fixture.repository.clearDraftItems(session.sessionId)

        assertTrue(updated.items.isEmpty())
    }

    @Test
    fun deleteScreenRemovesScreen() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1"))
        val session = fixture.openSession()
        val screen = fixture.captureScreen(session.sessionId)

        val updated = fixture.repository.deleteScreen(session.sessionId, screen.screenId)

        assertTrue(updated.screens.isEmpty())
    }

    @Test
    fun resolveFeedbackUpdatesStatusAndSummary() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1", "item-1"))
        val session = fixture.openSession()
        val screen = fixture.captureScreen(session.sessionId)
        val item = fixture.repository.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "comment",
        )

        val resolved = fixture.repository.resolveFeedback(
            sessionId = session.sessionId,
            itemId = item.itemId,
            status = AnnotationStatusDto.RESOLVED,
            summary = "all fixed",
        )

        assertEquals(AnnotationStatusDto.RESOLVED, resolved.status)
        assertEquals("all fixed", resolved.agentSummary)
    }

    @Test
    fun claimFeedbackPutsItemInProgress() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1", "item-1"))
        val session = fixture.openSession()
        val screen = fixture.captureScreen(session.sessionId)
        fixture.repository.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "comment",
        )

        val claimed = fixture.repository.claimFeedback(session.sessionId, "item-1", "via repo")

        assertEquals(AnnotationStatusDto.IN_PROGRESS, claimed.status)
        assertEquals("via repo", claimed.agentSummary)
    }

    @Test
    fun updateDraftFeedbackChangesComment() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1", "item-1"))
        val session = fixture.openSession()
        val screen = fixture.captureScreen(session.sessionId)
        val item = fixture.repository.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "old",
        )

        val updated = fixture.repository.updateDraftFeedback(
            sessionId = session.sessionId,
            itemId = item.itemId,
            label = null,
            severity = null,
            comment = "new",
            status = null,
        )

        assertEquals("new", updated.items.single().comment)
    }

    @Test
    fun deleteDraftFeedbackRemovesItem() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1", "item-1"))
        val session = fixture.openSession()
        val screen = fixture.captureScreen(session.sessionId)
        val item = fixture.repository.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "comment",
        )

        val updated = fixture.repository.deleteDraftFeedback(session.sessionId, item.itemId)

        assertTrue(updated.items.isEmpty())
    }

    @Test
    fun markItemsHandedOffMarksDelivery() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1", "item-1"))
        val session = fixture.openSession()
        val screen = fixture.captureScreen(session.sessionId)
        val item = fixture.repository.addAreaFeedback(
            session.sessionId,
            screen.screenId,
            FixThisRect(1f, 2f, 3f, 4f),
            "comment",
        )

        val updated = fixture.repository.markItemsHandedOff(session.sessionId, listOf(item.itemId))

        assertTrue((updated.items.single().lastHandedOffAtEpochMillis ?: 0L) > 0L)
    }

    @Test
    fun addFeedbackItemRejectsBoundsOutsideScreenshot() = runBlocking {
        val fixture = newFixture(ids = listOf("session-1", "screen-1"))
        val session = fixture.openSession()
        fixture.captureScreen(session.sessionId)

        val error = assertFailsWith<IllegalArgumentException> {
            fixture.repository.addFeedbackItem(
                sessionId = session.sessionId,
                screenId = "screen-1",
                targetType = FeedbackTargetType.AREA,
                bounds = FixThisRect(-1f, 0f, 10f, 10f),
                nodeUid = null,
                comment = "Bad bounds",
            )
        }
        assertTrue(error.message.orEmpty().contains("Selection bounds must be inside the screenshot"))
    }

    private class Fixture(
        val repository: AnnotationWorkflow,
        val store: FeedbackSessionStore,
    ) {
        fun openSession(): SessionDto = store.openSession(
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
        )

        suspend fun captureScreen(sessionId: String): SnapshotDto = store.addScreen(
            sessionId,
            SnapshotDto(
                screenId = "pending",
                capturedAtEpochMillis = 0L,
                displayName = "Checkout",
                screenshot = SnapshotScreenshotDto(
                    width = 720,
                    height = 1600,
                    desktopFullPath = "/repo/screen.png",
                ),
            ),
        )
    }

    private fun newFixture(ids: List<String>): Fixture {
        val queue = ArrayDeque(ids)
        val store = FeedbackSessionStore(
            clock = { 100L },
            idGenerator = { queue.removeFirstOrNull() ?: error("no more ids") },
        )
        val bridge = FakeFixThisBridge()
        val previewCache = PreviewSnapshotCache(maxEntries = 3)
        val sourceIndexRegistry = SourceIndexRegistry()
        val targetEvidenceService = TargetEvidenceService(
            bridge = bridge,
            sourceIndexRegistry = sourceIndexRegistry,
            projectRoot = File("/repo"),
        )
        val draftService = FeedbackDraftService(
            store = store,
            previewCache = previewCache,
            targetEvidenceService = targetEvidenceService,
            screenshotArtifactPromoter = ScreenshotArtifactPromoter(),
        )
        val repository = AnnotationWorkflow(store = store, draftService = draftService)
        return Fixture(repository, store)
    }
}
