package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.mcp.session.handoff.formatBox
import io.github.beyondwin.fixthis.mcp.session.handoff.staleMarkerSuffix
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatterExtensionsTest {

    @Test
    fun formatBoxRendersExplicitShape() {
        val rect = FixThisRect(left = 28f, top = 212f, right = 692f, bottom = 419f)
        assertEquals("(28.0,212.0)-(692.0,419.0)", rect.formatBox())
    }

    @Test
    fun formatBoxRendersDegenerateRectAsCoordinates() {
        val rect = FixThisRect(left = 100f, top = 100f, right = 50f, bottom = 50f)
        assertEquals("(100.0,100.0)-(50.0,50.0)", rect.formatBox())
    }

    @Test
    fun staleMarkerSuffixEmitsReasonWhenStale() {
        assertEquals(
            " ⚠ stale: excerpt mismatch",
            candidate(stale = true, reason = "excerpt mismatch").staleMarkerSuffix(),
        )
    }

    @Test
    fun staleMarkerSuffixFallsBackToUnspecifiedWhenReasonNull() {
        assertEquals(
            " ⚠ stale: unspecified",
            candidate(stale = true, reason = null).staleMarkerSuffix(),
        )
    }

    @Test
    fun staleMarkerSuffixIsEmptyWhenFresh() {
        assertEquals("", candidate(stale = false, reason = null).staleMarkerSuffix())
    }

    @Test
    fun staleMarkerSuffixIsEmptyWhenStaleFlagIsNull() {
        assertEquals("", candidate(stale = null, reason = null).staleMarkerSuffix())
    }

    private fun candidate(stale: Boolean?, reason: String?): SourceCandidate = SourceCandidate(
        file = "app/src/main/kotlin/Foo.kt",
        line = 12,
        score = 1.0,
        confidence = SelectionConfidence.HIGH,
        stale = stale,
        staleReason = reason,
    )
}
