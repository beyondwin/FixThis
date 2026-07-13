package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RuntimeEvidenceServiceTest {
    @Test
    fun attachManualSummaryRejectsAbsoluteArtifactPath() {
        val (service, _) = service()

        assertFailsWith<IllegalArgumentException> {
            service.attachManualSummary("session-1", "item-1", RuntimeEvidenceType.LOGCAT_WINDOW, "summary", "/tmp/logcat.txt")
        }
    }

    @Test
    fun attachManualSummaryRejectsArtifactPathOutsideFixThisStorage() {
        val (service, _) = service()

        assertFailsWith<IllegalArgumentException> {
            service.attachManualSummary("session-1", "item-1", RuntimeEvidenceType.LOGCAT_WINDOW, "summary", "../logcat.txt")
        }
    }

    @Test
    fun attachManualSummaryAcceptsMissingArtifactPath() {
        val (service, _) = service()

        val updated = service.attachManualSummary("session-1", "item-1", RuntimeEvidenceType.LOGCAT_WINDOW, "summary", null)

        assertNull(updated.runtimeEvidence.single().artifactPath)
    }

    private fun service(): Pair<RuntimeEvidenceService, FeedbackSessionStore> {
        val store = FeedbackSessionStore(clock = { 1L }, idGenerator = { "unused" })
        store.replaceSessionForDomain(session())
        return RuntimeEvidenceService(store = store, idGenerator = { "e-1" }, clock = { 2L }) to store
    }

    private fun session(): SessionDto = SessionDto(
        sessionId = "session-1",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        items = listOf(
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                comment = "comment",
            ),
        ),
    )
}
