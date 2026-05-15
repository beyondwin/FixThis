package io.github.beyondwin.fixthis.mcp.console.events

import kotlinx.serialization.json.JsonObject
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class ConsoleEventBus(
    private val ringSize: Int = 256,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(ringSize > 0) { "Console event ring size must be positive" }
    }

    private val nextId = AtomicLong(0)
    private val ring = ArrayDeque<ConsoleEvent>()
    private val subscribers = CopyOnWriteArrayList<(ConsoleEvent) -> Unit>()
    private val lock = Any()

    fun emit(name: String, data: JsonObject): ConsoleEvent {
        val event = ConsoleEvent(
            id = nextId.incrementAndGet(),
            name = name,
            data = data,
            createdAtEpochMillis = clock(),
        )
        synchronized(lock) {
            ring.addLast(event)
            while (ring.size > ringSize) ring.removeFirst()
        }
        subscribers.forEach { subscriber -> runCatching { subscriber(event) } }
        return event
    }

    fun eventsAfter(lastEventId: Long): ConsoleEventReplay = synchronized(lock) {
        val events = ring.toList()
        val oldest = events.firstOrNull()?.id
        if (oldest != null && lastEventId < oldest - 1) {
            ConsoleEventReplay(emptyList(), overflow = true, oldestAvailableEventId = oldest)
        } else {
            ConsoleEventReplay(events.filter { it.id > lastEventId }, overflow = false, oldestAvailableEventId = oldest)
        }
    }

    fun subscribe(listener: (ConsoleEvent) -> Unit): AutoCloseable {
        subscribers += listener
        return AutoCloseable { subscribers -= listener }
    }
}
