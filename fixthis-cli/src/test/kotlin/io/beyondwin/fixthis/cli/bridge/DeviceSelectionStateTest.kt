package io.beyondwin.fixthis.cli.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSelectionStateTest {
    @Test
    fun selectStoresNonBlankSerialUntilCleared() {
        val state = DeviceSelectionState()

        state.select("device-1")
        assertEquals("device-1", state.selected())

        state.clear()
        assertNull(state.selected())
    }

    @Test
    fun selectRejectsBlankSerial() {
        val error = kotlin.runCatching {
            DeviceSelectionState().select(" ")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
