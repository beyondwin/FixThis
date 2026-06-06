package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.toAnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.toDomainAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetReliabilityDtoTest {
    @Test
    fun decodesLegacyAnnotationWithoutReliability() {
        val legacy = """
            {
              "itemId": "item-1",
              "screenId": "screen-1",
              "createdAtEpochMillis": 1,
              "updatedAtEpochMillis": 1,
              "target": {
                "type": "visual_area",
                "boundsInWindow": {"left": 1.0, "top": 2.0, "right": 3.0, "bottom": 4.0}
              },
              "comment": "Fix the chart"
            }
        """.trimIndent()

        val item = fixThisJson.decodeFromString(AnnotationDto.serializer(), legacy)

        assertEquals(null, item.targetReliability)
    }

    @Test
    fun roundTripsReliabilityAsSiblingOfTargetEvidence() {
        val item = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "Fix the chart",
            targetReliability = TargetReliability(
                confidence = TargetConfidence.LOW,
                reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
            ),
        )

        val encoded = fixThisJson.encodeToString(AnnotationDto.serializer(), item)
        val decoded = fixThisJson.decodeFromString(AnnotationDto.serializer(), encoded)

        assertEquals(item.targetReliability, decoded.targetReliability)
        assertTrue(encoded.contains("\"targetReliability\""))
        assertTrue(encoded.contains("\"targetEvidence\"").not())
    }

    @Test
    fun domainMapperPreservesReliability() {
        val item = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "Fix the chart",
            targetReliability = TargetReliability(
                confidence = TargetConfidence.LOW,
                warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY),
            ),
        )

        val roundTrip = item.toDomainAnnotation("session-1").toAnnotationDto()

        assertEquals(item.targetReliability, roundTrip.targetReliability)
    }
}
