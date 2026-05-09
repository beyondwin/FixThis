package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatterExtensionsTest {

    @Test
    fun formatBoxRendersExplicitShape() {
        val rect = FixThisRect(left = 28f, top = 212f, right = 692f, bottom = 419f)
        assertEquals("(28.0,212.0)-(692.0,419.0) [664×207]", rect.formatBox())
    }

    @Test
    fun formatBoxClampsNegativeDimensionsToZero() {
        val rect = FixThisRect(left = 100f, top = 100f, right = 50f, bottom = 50f)
        assertTrue(rect.formatBox().endsWith("[0×0]"))
    }
}
