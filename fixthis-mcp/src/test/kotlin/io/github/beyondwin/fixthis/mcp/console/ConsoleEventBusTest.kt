package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleEventBusTest {
    @Test
    fun emitsMonotonicEventsAndReplaysAfterId() {
        val bus = ConsoleEventBus(ringSize = 4, clock = { 123L })
        val first = bus.emit("sessions-updated", buildJsonObject { put("value", 1) })
        val second = bus.emit("session-updated", buildJsonObject { put("value", 2) })

        assertEquals(1L, first.id)
        assertEquals(2L, second.id)
        assertEquals(listOf(second), bus.eventsAfter(1L).events)
        assertEquals(false, bus.eventsAfter(1L).overflow)
    }

    @Test
    fun reportsOverflowWhenLastEventIdIsOlderThanRing() {
        val bus = ConsoleEventBus(ringSize = 2, clock = { 123L })
        bus.emit("a", buildJsonObject { put("value", 1) })
        bus.emit("b", buildJsonObject { put("value", 2) })
        bus.emit("c", buildJsonObject { put("value", 3) })

        val replay = bus.eventsAfter(0L)
        assertTrue(replay.overflow)
        assertEquals(2L, replay.oldestAvailableEventId)
    }

    @Test
    fun statsTrackEmitsSubscribersReplayAndOverflow() {
        val bus = ConsoleEventBus(ringSize = 1, clock = { 123L })
        val subscription = bus.subscribe { }
        bus.emit("a", buildJsonObject { put("value", 1) })
        bus.emit("b", buildJsonObject { put("value", 2) })
        val replay = bus.eventsAfter(0L)
        subscription.close()

        assertTrue(replay.overflow)
        val stats = bus.stats()
        assertEquals(2L, stats.emittedEvents)
        assertEquals(1L, stats.openedSubscriptions)
        assertEquals(1L, stats.closedSubscriptions)
        assertEquals(0, stats.activeSubscriptions)
        assertEquals(1L, stats.replayRequests)
        assertEquals(1L, stats.replayOverflowCount)
    }
}
