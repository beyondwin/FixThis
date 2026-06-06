package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.toAnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.toDomainAnnotation
import io.github.beyondwin.fixthis.mcp.session.dto.toDomainSession
import io.github.beyondwin.fixthis.mcp.session.dto.toSessionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureCompatibilityTest {
    @Test
    fun legacyReadyAnnotationStatusStillDecodes() {
        val item = fixThisJson.decodeFromString(
            AnnotationDto.serializer(),
            """
            {
              "itemId": "item-1",
              "screenId": "screen-1",
              "createdAtEpochMillis": 10,
              "updatedAtEpochMillis": 20,
              "target": {
                "type": "visual_area",
                "boundsInWindow": {
                  "left": 1.0,
                  "top": 2.0,
                  "right": 3.0,
                  "bottom": 4.0
                }
              },
              "comment": "Ready for agent",
              "status": "ready"
            }
            """.trimIndent(),
        )

        assertEquals(AnnotationStatusDto.READY, item.status)
    }

    @Test
    fun legacyReadyStatusMapsToOpenDomainStatus() {
        val dto = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 12L,
            updatedAtEpochMillis = 13L,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "Ready from old session",
            status = AnnotationStatusDto.READY,
        )

        val domain = dto.toDomainAnnotation(sessionId = "session-1")

        assertEquals(io.github.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus.OPEN, domain.status)
    }

    @Test
    fun openDomainStatusMapsBackToOpenAnnotationStatus() {
        val domain = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 12L,
            updatedAtEpochMillis = 13L,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "Open domain item",
            status = AnnotationStatusDto.RESOLVED,
        ).toDomainAnnotation(sessionId = "session-1").copy(
            status = io.github.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus.OPEN,
        )

        val dto = domain.toAnnotationDto()

        assertEquals(AnnotationStatusDto.OPEN, dto.status)
    }

    @Test
    fun sessionWireFormatKeepsExistingFieldNames() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 11L,
                    displayName = "MainActivity",
                    screenshot = SnapshotScreenshotDto(width = 720, height = 1600),
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 12L,
                    updatedAtEpochMillis = 13L,
                    target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                    comment = "Fix spacing",
                    status = AnnotationStatusDto.READY,
                ),
            ),
            status = SessionStatusDto.READY_FOR_AGENT,
        )

        val encoded = fixThisJson.encodeToString(SessionDto.serializer(), session)

        assertTrue(encoded.contains("\"screens\""))
        assertTrue(encoded.contains("\"items\""))
        assertTrue(encoded.contains("\"screenId\""))
        assertTrue(encoded.contains("\"itemId\""))
        assertTrue(encoded.contains("\"ready\""))
        assertTrue(encoded.contains("\"ready_for_agent\""))
    }

    @Test
    fun domainSessionMapsBackToExistingWireFieldNames() {
        val dto = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            screens = listOf(SnapshotDto("screen-1", 11L, displayName = "MainActivity")),
        )

        val roundTrip = dto.toDomainSession().toSessionDto()

        assertEquals(dto.sessionId, roundTrip.sessionId)
        assertEquals(dto.screens.single().screenId, roundTrip.screens.single().screenId)
        assertEquals(dto.items, roundTrip.items)
    }
}
