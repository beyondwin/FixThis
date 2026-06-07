package io.github.beyondwin.fixthis.compose.core.source

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSignalKindWeightTest {
    @Test
    fun `enum exposes baseMatchWeight matching legacy table`() {
        assertEquals(1.15, SourceSignalKind.STRICT_COMP_TEST_TAG.baseMatchWeight, 0.0)
        assertEquals(0.75, SourceSignalKind.LAYOUT_RENDERER.baseMatchWeight, 0.0)
        assertEquals(0.0, SourceSignalKind.SHARED_COMPONENT.baseMatchWeight, 0.0)
        assertEquals(0.35, SourceSignalKind.ARBITRARY_STRING_LITERAL.baseMatchWeight, 0.0)
    }

    @Test
    fun `enum serializes by name despite added ctor arg`() {
        val s = SourceSignal(SourceSignalKind.UI_TEXT, "x")
        val round = Json.decodeFromString<SourceSignal>(Json.encodeToString(s))
        assertEquals(SourceSignalKind.UI_TEXT, round.kind)
    }
}
