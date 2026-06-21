package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RuntimeEvidenceServiceTest {
    @Test
    fun attachManualSummaryRejectsAbsoluteArtifactPath() {
        val service = RuntimeEvidenceService(idGenerator = { "e-1" }, clock = { 2L })

        assertFailsWith<IllegalArgumentException> {
            service.attachManualSummary(session(), "item-1", RuntimeEvidenceType.LOGCAT_WINDOW, "summary", "/tmp/logcat.txt")
        }
    }

    @Test
    fun attachManualSummaryRejectsArtifactPathOutsideFixThisStorage() {
        val service = RuntimeEvidenceService(idGenerator = { "e-1" }, clock = { 2L })

        assertFailsWith<IllegalArgumentException> {
            service.attachManualSummary(session(), "item-1", RuntimeEvidenceType.LOGCAT_WINDOW, "summary", "../logcat.txt")
        }
    }

    @Test
    fun attachManualSummaryAcceptsMissingArtifactPath() {
        val service = RuntimeEvidenceService(idGenerator = { "e-1" }, clock = { 2L })

        val updated = service.attachManualSummary(session(), "item-1", RuntimeEvidenceType.LOGCAT_WINDOW, "summary", null)

        assertNull(updated.runtimeEvidence.single().artifactPath)
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
