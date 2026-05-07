package io.beyondwin.fixthis.compose.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DetailModeTest {
    @Test
    fun fromWireDefaultsNullBlankAndPreciseToPrecise() {
        assertEquals(DetailMode.PRECISE, DetailMode.fromWire(null))
        assertEquals(DetailMode.PRECISE, DetailMode.fromWire(""))
        assertEquals(DetailMode.PRECISE, DetailMode.fromWire("   "))
        assertEquals(DetailMode.PRECISE, DetailMode.fromWire("precise"))
    }

    @Test
    fun fromWireTrimsAndIgnoresCase() {
        assertEquals(DetailMode.COMPACT, DetailMode.fromWire(" COMPACT "))
        assertEquals(DetailMode.PRECISE, DetailMode.fromWire("\tPrecise\n"))
        assertEquals(DetailMode.FULL, DetailMode.fromWire(" Full "))
    }

    @Test
    fun fromWireAcceptsSupportedModes() {
        assertEquals(DetailMode.COMPACT, DetailMode.fromWire("compact"))
        assertEquals(DetailMode.PRECISE, DetailMode.fromWire("precise"))
        assertEquals(DetailMode.FULL, DetailMode.fromWire("full"))
    }

    @Test
    fun fromWireRejectsUnsupportedModes() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            DetailMode.fromWire("verbose")
        }

        assertEquals("Unsupported detailMode: verbose", error.message)
    }
}
