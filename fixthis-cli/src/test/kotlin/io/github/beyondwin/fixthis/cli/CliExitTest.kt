package io.github.beyondwin.fixthis.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class CliExitTest {
    @Test
    fun exitCodeNumbersMatchPublicContract() {
        assertEquals(0, ExitCode.OK.value)
        assertEquals(1, ExitCode.PARTIAL.value)
        assertEquals(2, ExitCode.USAGE_ERROR.value)
        assertEquals(3, ExitCode.ENV_BLOCKER.value)
        assertEquals(4, ExitCode.INTERNAL_ERROR.value)
    }

    @Test
    fun fromIntRoundTrips() {
        ExitCode.entries.forEach { code ->
            assertEquals(code, ExitCode.fromInt(code.value))
        }
    }

    @Test
    fun fromIntUnknownMapsToInternalError() {
        assertEquals(ExitCode.INTERNAL_ERROR, ExitCode.fromInt(99))
    }
}
