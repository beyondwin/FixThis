package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureCompatibilityTest {
    @Test
    fun legacyReadyFeedbackItemStatusStillDecodes() {
        val item = pointPatchJson.decodeFromString(
            FeedbackItem.serializer(),
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

        assertEquals(FeedbackItemStatus.READY, item.status)
    }

    @Test
    fun legacyReadyStatusMapsToOpenDomainStatus() {
        val dto = FeedbackItem(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 12L,
            updatedAtEpochMillis = 13L,
            target = FeedbackTarget.Area(PointPatchRect(1f, 2f, 3f, 4f)),
            comment = "Ready from old session",
            status = FeedbackItemStatus.READY,
        )

        val domain = dto.toDomainAnnotation(sessionId = "session-1")

        assertEquals(io.github.pointpatch.compose.core.domain.annotation.AnnotationStatus.OPEN, domain.status)
    }

    @Test
    fun openDomainStatusMapsBackToOpenFeedbackItemStatus() {
        val domain = FeedbackItem(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 12L,
            updatedAtEpochMillis = 13L,
            target = FeedbackTarget.Area(PointPatchRect(1f, 2f, 3f, 4f)),
            comment = "Open domain item",
            status = FeedbackItemStatus.RESOLVED,
        ).toDomainAnnotation(sessionId = "session-1").copy(
            status = io.github.pointpatch.compose.core.domain.annotation.AnnotationStatus.OPEN,
        )

        val dto = domain.toFeedbackItemDto()

        assertEquals(FeedbackItemStatus.OPEN, dto.status)
    }

    @Test
    fun sessionWireFormatKeepsExistingFieldNames() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            screens = listOf(
                CapturedScreen(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 11L,
                    displayName = "MainActivity",
                    screenshot = FeedbackScreenshot(width = 720, height = 1600),
                ),
            ),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 12L,
                    updatedAtEpochMillis = 13L,
                    target = FeedbackTarget.Area(PointPatchRect(1f, 2f, 3f, 4f)),
                    comment = "Fix spacing",
                    status = FeedbackItemStatus.READY,
                ),
            ),
            status = FeedbackSessionStatus.READY_FOR_AGENT,
        )

        val encoded = pointPatchJson.encodeToString(FeedbackSession.serializer(), session)

        assertTrue(encoded.contains("\"screens\""))
        assertTrue(encoded.contains("\"items\""))
        assertTrue(encoded.contains("\"screenId\""))
        assertTrue(encoded.contains("\"itemId\""))
        assertTrue(encoded.contains("\"ready\""))
        assertTrue(encoded.contains("\"ready_for_agent\""))
    }

    @Test
    fun domainSessionMapsBackToExistingWireFieldNames() {
        val dto = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            screens = listOf(CapturedScreen("screen-1", 11L, displayName = "MainActivity")),
        )

        val roundTrip = dto.toDomainSession().toFeedbackSessionDto()

        assertEquals(dto.sessionId, roundTrip.sessionId)
        assertEquals(dto.screens.single().screenId, roundTrip.screens.single().screenId)
        assertEquals(dto.items, roundTrip.items)
    }
}
