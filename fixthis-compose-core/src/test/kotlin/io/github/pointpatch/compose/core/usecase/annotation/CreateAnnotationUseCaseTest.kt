package io.github.pointpatch.compose.core.usecase.annotation

import io.github.pointpatch.compose.core.domain.annotation.Annotation
import io.github.pointpatch.compose.core.domain.annotation.AnnotationRepository
import io.github.pointpatch.compose.core.domain.annotation.AnnotationStatus
import io.github.pointpatch.compose.core.domain.annotation.AnnotationTarget
import io.github.pointpatch.compose.core.domain.common.AnnotationId
import io.github.pointpatch.compose.core.domain.common.SessionId
import io.github.pointpatch.compose.core.domain.common.SnapshotId
import io.github.pointpatch.compose.core.domain.session.Session
import io.github.pointpatch.compose.core.domain.session.SessionRepository
import io.github.pointpatch.compose.core.model.PointPatchRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class CreateAnnotationUseCaseTest {
    @Test
    fun createsOpenAnnotationInActiveSession() = runSuspend {
        val sessionId = SessionId("session-1")
        val snapshotId = SnapshotId("screen-1")
        val target = AnnotationTarget.Area(PointPatchRect(1f, 2f, 3f, 4f))
        val comment = "Fix spacing"
        val sessions = FakeSessionRepository(
            Session(
                id = sessionId,
                packageName = "io.github.pointpatch.sample",
                projectRoot = "/repo",
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 10L,
            ),
        )
        val annotations = FakeAnnotationRepository()
        val useCase = CreateAnnotationUseCase(
            sessions = sessions,
            annotations = annotations,
            clock = { 20L },
            idGenerator = { AnnotationId("annotation-1") },
        )

        val created = useCase(
            sessionId = sessionId,
            snapshotId = snapshotId,
            target = target,
            comment = comment,
        )
        val expected = Annotation(
            id = AnnotationId("annotation-1"),
            sessionId = sessionId,
            snapshotId = snapshotId,
            createdAtEpochMillis = 20L,
            updatedAtEpochMillis = 20L,
            target = target,
            comment = comment,
            status = AnnotationStatus.OPEN,
        )

        assertEquals(expected, created)
        assertEquals(expected, annotations.saved.single())
    }

    @Test
    fun rejectsBlankAnnotationComment() {
        val useCase = CreateAnnotationUseCase(
            sessions = FakeSessionRepository(
                Session(
                    id = SessionId("session-1"),
                    packageName = "io.github.pointpatch.sample",
                    projectRoot = "/repo",
                    createdAtEpochMillis = 10L,
                    updatedAtEpochMillis = 10L,
                ),
            ),
            annotations = FakeAnnotationRepository(),
            clock = { 20L },
            idGenerator = { AnnotationId("annotation-1") },
        )

        try {
            runSuspend {
                useCase(
                    sessionId = SessionId("session-1"),
                    snapshotId = SnapshotId("screen-1"),
                    target = AnnotationTarget.Area(PointPatchRect(1f, 2f, 3f, 4f)),
                    comment = " ",
                )
            }
            fail("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("Annotation comment must not be blank"))
        }
    }
}

private class FakeSessionRepository(private val session: Session) : SessionRepository {
    override suspend fun find(id: SessionId): Session? =
        session.takeIf { it.id == id }

    override suspend fun save(session: Session): Session = session
}

private class FakeAnnotationRepository : AnnotationRepository {
    val saved = mutableListOf<Annotation>()

    override suspend fun save(annotation: Annotation): Annotation {
        saved += annotation
        return annotation
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
