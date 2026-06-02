package io.github.beyondwin.fixthis.compose.core.usecase.feedback

import io.github.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.github.beyondwin.fixthis.compose.core.domain.annotation.AnnotationDelivery
import io.github.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.github.beyondwin.fixthis.compose.core.domain.annotation.AnnotationTarget
import io.github.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.github.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.github.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.github.beyondwin.fixthis.compose.core.domain.session.Session
import io.github.beyondwin.fixthis.compose.core.domain.session.SessionRepository
import io.github.beyondwin.fixthis.compose.core.domain.session.SessionStatus
import io.github.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot
import io.github.beyondwin.fixthis.compose.core.domain.ui.DomainRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class FeedbackUseCasesTest {
    @Test
    fun savePreviewFeedbackRejectsEmptyDrafts() {
        val sessions = InMemorySessionRepository(sessionWithSnapshot())
        val useCase = SavePreviewFeedbackUseCase(sessions = sessions, clock = { 100L })

        try {
            runSuspend {
                useCase(
                    SavePreviewFeedbackCommand(
                        sessionId = SessionId("session-1"),
                        snapshot = snapshot("screen-1"),
                        drafts = emptyList(),
                    ),
                )
            }
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("drafts must not be empty"))
        }
    }

    @Test
    fun savePreviewFeedbackAssignsStableMonotonicSequences() = runSuspend {
        val sessions = InMemorySessionRepository(
            sessionWithSnapshot().copy(
                annotations = listOf(
                    annotation("existing", sequenceNumber = 4),
                ),
            ),
        )
        val useCase = SavePreviewFeedbackUseCase(sessions = sessions, clock = { 100L })

        val updated = useCase(
            SavePreviewFeedbackCommand(
                sessionId = SessionId("session-1"),
                snapshot = snapshot("screen-1"),
                drafts = listOf(
                    annotation("draft-a", sequenceNumber = null),
                    annotation("draft-b", sequenceNumber = null),
                ),
            ),
        )

        assertEquals(listOf(4, 5, 6), updated.annotations.map { it.sequenceNumber })
        assertEquals(100L, updated.updatedAtEpochMillis)
    }

    @Test
    fun handoffMarksOnlySelectedDraftAnnotationsAsSent() = runSuspend {
        val sessions = InMemorySessionRepository(
            sessionWithSnapshot().copy(
                annotations = listOf(
                    annotation("draft-a", sequenceNumber = 1),
                    annotation("draft-b", sequenceNumber = 2),
                ),
            ),
        )
        val useCase = CreateHandoffBatchUseCase(
            sessions = sessions,
            clock = { 200L },
            idGenerator = { "batch-1" },
        )

        val updated = useCase(
            CreateHandoffBatchCommand(
                sessionId = SessionId("session-1"),
                annotationIds = listOf(AnnotationId("draft-b")),
                markdownSnapshot = "handoff",
            ),
        )

        val unchanged = updated.annotations.first { it.id.value == "draft-a" }
        val handedOff = updated.annotations.first { it.id.value == "draft-b" }
        assertEquals(AnnotationDelivery.DRAFT, unchanged.delivery)
        assertEquals(AnnotationDelivery.SENT, handedOff.delivery)
        assertEquals("batch-1", handedOff.handoffBatchId)
        assertEquals(AnnotationStatus.OPEN, handedOff.status)
        assertEquals(SessionStatus.READY_FOR_AGENT, updated.status)
    }

    private fun sessionWithSnapshot(): Session = Session(
        id = SessionId("session-1"),
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        snapshots = listOf(snapshot("screen-1")),
    )

    private fun snapshot(id: String): Snapshot = Snapshot(
        id = SnapshotId(id),
        capturedAtEpochMillis = 2L,
        displayName = "MainActivity",
    )

    private fun annotation(
        id: String,
        snapshotId: SnapshotId = SnapshotId("screen-1"),
        sequenceNumber: Int? = 1,
    ): Annotation = Annotation(
        id = AnnotationId(id),
        sessionId = SessionId("session-1"),
        snapshotId = snapshotId,
        createdAtEpochMillis = 3L,
        updatedAtEpochMillis = 3L,
        target = AnnotationTarget.Area(DomainRect(1f, 2f, 3f, 4f)),
        comment = "Fix spacing",
        sequenceNumber = sequenceNumber,
    )
}

private class InMemorySessionRepository(initial: Session) : SessionRepository {
    private var session: Session = initial

    override suspend fun find(id: SessionId): Session? = session.takeIf { it.id == id }

    override suspend fun save(session: Session): Session {
        this.session = session
        return session
    }
}

private fun runSuspend(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                outcome = result
            }
        },
    )
    outcome!!.getOrThrow()
}
