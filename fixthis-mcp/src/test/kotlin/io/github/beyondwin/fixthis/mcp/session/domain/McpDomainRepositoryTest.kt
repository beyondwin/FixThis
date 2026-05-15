package io.github.beyondwin.fixthis.mcp.session.domain

import io.github.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.github.beyondwin.fixthis.compose.core.domain.annotation.AnnotationTarget
import io.github.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.github.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.github.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.github.beyondwin.fixthis.compose.core.domain.session.Session
import io.github.beyondwin.fixthis.compose.core.domain.session.SessionStatus
import io.github.beyondwin.fixthis.compose.core.domain.snapshot.ScreenOrientation
import io.github.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot
import io.github.beyondwin.fixthis.compose.core.domain.snapshot.WindowMode
import io.github.beyondwin.fixthis.compose.core.domain.ui.DomainRect
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpDomainRepositoryTest {
    @Test
    fun repositoriesRoundTripDomainModelsThroughFeedbackSessionStore() = runBlocking {
        val store = FeedbackSessionStore(clock = { 1_000L })
        val sessionRepository = McpSessionRepository(store)
        val snapshotRepository = McpSnapshotRepository(store) { "session-1" }
        val annotationRepository = McpAnnotationRepository(store)
        val missingSessionId = SessionId("missing-session")

        val session = Session(
            id = SessionId("session-1"),
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
        )
        val snapshot = Snapshot(
            id = SnapshotId("screen-1"),
            capturedAtEpochMillis = 30L,
            activityName = "MainActivity",
            displayName = "Checkout",
            sourceIndexAvailable = true,
            orientation = ScreenOrientation.LANDSCAPE,
            widthPx = 1920,
            heightPx = 1080,
            densityDpi = 420,
            windowMode = WindowMode.FULLSCREEN,
            systemUiVisible = true,
            systemUiKind = "gesture",
            fingerprint = "screen-fingerprint",
        )
        val annotation = Annotation(
            id = AnnotationId("item-1"),
            sessionId = session.id,
            snapshotId = snapshot.id,
            createdAtEpochMillis = 40L,
            updatedAtEpochMillis = 50L,
            target = AnnotationTarget.Area(DomainRect(1f, 2f, 30f, 40f)),
            comment = "Tighten the checkout button spacing",
            sequenceNumber = 7,
        )

        assertNull(sessionRepository.find(missingSessionId))

        assertEquals(session, sessionRepository.save(session))
        assertEquals(session, sessionRepository.find(session.id))

        assertEquals(snapshot, snapshotRepository.save(snapshot))
        assertEquals(snapshot, snapshotRepository.find(snapshot.id))

        assertEquals(annotation, annotationRepository.save(annotation))

        val roundTrippedSession = sessionRepository.find(session.id)!!
        assertEquals(listOf(snapshot), roundTrippedSession.snapshots)
        assertEquals(listOf(annotation), roundTrippedSession.annotations)
    }

    @Test
    fun savingCurrentSessionAsClosedClearsCurrentSession() = runBlocking {
        val store = FeedbackSessionStore(clock = { 1_000L })
        val sessionRepository = McpSessionRepository(store)
        val session = Session(
            id = SessionId("session-1"),
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
        )

        sessionRepository.save(session)
        sessionRepository.save(session.copy(status = SessionStatus.CLOSED))

        assertNull(store.currentSession())
    }
}
