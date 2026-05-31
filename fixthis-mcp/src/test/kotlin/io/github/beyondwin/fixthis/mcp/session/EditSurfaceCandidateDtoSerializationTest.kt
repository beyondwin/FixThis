package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditSurfaceCandidateDtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `confidenceBasis defaults to null and round-trips`() {
        val dto = EditSurfaceCandidateDto(
            kind = EditSurfaceKindDto.COMPONENT_RENDERER,
            file = "Foo.kt",
            confidence = SelectionConfidence.MEDIUM,
            confidenceBasis = "call site matched: selected owner composable",
        )
        val decoded = json.decodeFromString(
            EditSurfaceCandidateDto.serializer(),
            json.encodeToString(EditSurfaceCandidateDto.serializer(), dto),
        )
        assertEquals("call site matched: selected owner composable", decoded.confidenceBasis)

        val legacy = json.decodeFromString(
            EditSurfaceCandidateDto.serializer(),
            """{"kind":"COMPONENT_RENDERER","file":"Foo.kt","confidence":"MEDIUM"}""",
        )
        assertNull(legacy.confidenceBasis)
    }
}
