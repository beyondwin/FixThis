package io.github.beyondwin.fixthis.mcp.console

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsoleHttpEtagTest {
    @Test
    fun etagFormatJoinsParts() {
        assertEquals("\"abc:42\"", etagOf("abc", 42L))
        assertEquals("\"3:1700000000000\"", etagOf("3", 1_700_000_000_000L))
    }
}
