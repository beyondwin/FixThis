package io.beyondwin.fixthis.compose.sidekick.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeConnectionStateTest {
    @Test
    fun connectedOnlyAfterRecentAuthorizedRequest() {
        var now = 1_000L
        val state = BridgeConnectionState(clock = { now }, connectedWindowMillis = 500L)

        assertFalse(state.isConnected())

        state.markAuthorizedRequest()

        assertTrue(state.isConnected())

        now += 501L

        assertFalse(state.isConnected())
    }

    @Test
    fun defaultWindowStaysConnectedAcrossShortConsolePollingGaps() {
        var now = 1_000L
        val state = BridgeConnectionState(clock = { now })

        state.markAuthorizedRequest()
        now += 5_000L

        assertTrue(state.isConnected())

        now += 1_001L

        assertFalse(state.isConnected())
    }
}
