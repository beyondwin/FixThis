# SSE Console State Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Server-Sent Events channel that keeps feedback console session, device, connection, and preview state fresh without waiting for polling ticks.

**Architecture:** Introduce a small server-side `ConsoleEventBus`, expose it through `GET /api/events`, and emit events from existing console route mutation boundaries. Add a browser `events.js` subscriber that updates the existing state/rendering functions while leaving current polling as a fallback.

**Tech Stack:** Kotlin/JVM 21, `com.sun.net.httpserver.HttpServer`, kotlinx.serialization, JUnit/kotlin.test, vanilla browser JavaScript, Node 20 test runner, Playwright harness, Gradle.

**Related spec:** [`../specs/2026-05-15-sse-console-state-sync-detailed-spec.md`](../specs/2026-05-15-sse-console-state-sync-detailed-spec.md)

---

## File Structure

- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventModels.kt` for serializable event payloads.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventBus.kt` for monotonic IDs, ring replay, and fan-out.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventRoutes.kt` for `/api/events`.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` to construct and register the event bus route.
- Modify `SessionRoutes.kt`, `FeedbackItemRoutes.kt`, `MarkHandedOffRoutes.kt`, `PreviewRoutes.kt`, `DeviceRoutes.kt`, and `ConnectionRoutes.kt` to emit events after successful mutations or state transitions.
- Create `fixthis-mcp/src/main/console/events.js` for browser `EventSource` subscription.
- Modify `scripts/build-console-assets.mjs` to include `events.js` after state/render helpers and before `main.js` startup.
- Modify `fixthis-mcp/src/main/console/main.js` to call `startConsoleEvents()`.
- Add `scripts/consoleEvents-test.mjs` for browser module contract tests.
- Add `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventBusTest.kt`.
- Add `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt`.

## Task 1: Event Models And Ring Buffer

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventModels.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventBus.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventBusTest.kt`

- [ ] **Step 1: Write event bus tests**

Create `ConsoleEventBusTest.kt`:

```kotlin
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
}
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleEventBusTest" --no-daemon
```

Expected: FAIL with unresolved `ConsoleEventBus`.

- [ ] **Step 3: Implement models and bus**

Create `ConsoleEventModels.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ConsoleEvent(
    val id: Long,
    val name: String,
    val data: JsonObject,
    val createdAtEpochMillis: Long,
)

internal data class ConsoleEventReplay(
    val events: List<ConsoleEvent>,
    val overflow: Boolean,
    val oldestAvailableEventId: Long?,
)
```

Create `ConsoleEventBus.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console.events

import kotlinx.serialization.json.JsonObject
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

internal class ConsoleEventBus(
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
```

- [ ] **Step 4: Verify and commit**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleEventBusTest" --no-daemon
```

Expected: PASS.

Commit:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventBusTest.kt
git commit -m "feat(console): add event bus for SSE sync"
```

## Task 2: SSE Endpoint

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt`

- [ ] **Step 1: Add route tests for snapshot and headers**

Create `ConsoleEventsRoutesTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixture
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleEventsRoutesTest {
    @Test
    fun eventsEndpointStreamsInitialSnapshot() {
        val fixture = newConsoleSessionFixture()
        val bus = ConsoleEventBus(clock = { 1L })
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            server.start()
            val connection = URI("${server.url}/api/events").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            assertEquals(200, connection.responseCode)
            assertTrue(connection.contentType.startsWith("text/event-stream"))
            val text = connection.inputStream.bufferedReader().readText()
            assertTrue(text.contains("event: snapshot"))
            assertTrue(text.contains("data:"))
        } finally {
            server.stop()
        }
    }
}
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleEventsRoutesTest.eventsEndpointStreamsInitialSnapshot" --no-daemon
```

Expected: FAIL because `FeedbackConsoleServer(eventBus = ...)` and `/api/events` do not exist.

- [ ] **Step 3: Implement `ConsoleEventRoutes`**

Create `ConsoleEventRoutes.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal class ConsoleEventRoutes(
    private val service: FeedbackSessionService,
    private val eventBus: ConsoleEventBus,
) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/events"

    override fun handle(exchange: HttpExchange) {
        exchange.requireMethod("GET") {
            exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-cache")
            exchange.responseHeaders.set("Connection", "keep-alive")
            exchange.responseHeaders.set("X-Accel-Buffering", "no")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter(Charsets.UTF_8).use { writer ->
                val lastEventId = exchange.requestHeaders.getFirst("Last-Event-ID")?.toLongOrNull()
                if (lastEventId != null) {
                    val replay = eventBus.eventsAfter(lastEventId)
                    if (replay.overflow) {
                        writeEvent(writer, ConsoleEvent(0L, "events-dropped", buildJsonObject {
                            put("lastEventId", lastEventId)
                            replay.oldestAvailableEventId?.let { put("oldestAvailableEventId", it) }
                        }, System.currentTimeMillis()))
                    } else {
                        replay.events.forEach { writeEvent(writer, it) }
                        writer.flush()
                        return@use
                    }
                }
                writeEvent(writer, ConsoleEvent(0L, "snapshot", snapshotData(), System.currentTimeMillis()))
                writer.flush()
            }
        }
    }

    private fun snapshotData(): JsonObject = buildJsonObject {
                put("session", service.currentSessionOrNull()?.let { fixThisJson.encodeToJsonElement(it) } ?: kotlinx.serialization.json.JsonNull)
                put("sessions", fixThisJson.encodeToJsonElement(service.listSessions()))
                put("devices", fixThisJson.encodeToJsonElement(deviceList()))
                put("connection", fixThisJson.encodeToJsonElement(kotlinx.coroutines.runBlocking { service.connectionStatus() }))
            }

    private fun deviceList(): ConsoleDeviceList {
        val selectedSerial = service.selectedDeviceSerial()
        return ConsoleDeviceList(
            devices = service.devices().map { it.toConsoleDevice(selectedSerial) },
            selectedSerial = selectedSerial,
        )
    }

    private fun writeEvent(writer: java.io.Writer, event: ConsoleEvent) {
        if (event.id > 0) writer.append("id: ").append(event.id.toString()).append('\n')
        writer.append("event: ").append(event.name).append('\n')
        writer.append("data: ").append(fixThisJson.encodeToString(JsonObject.serializer(), event.data)).append("\n\n")
    }
}
```

The helper uses existing `FeedbackSessionService` methods:
`currentSessionOrNull()`, `listSessions()`, `devices()`,
`selectedDeviceSerial()`, and suspend `connectionStatus()`.

- [ ] **Step 4: Register the route**

Modify `FeedbackConsoleServer` constructor and route table:

```kotlin
class FeedbackConsoleServer(
    private val service: FeedbackSessionService,
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
    private val consoleAssetsDir: File? = null,
    private val eventBus: ConsoleEventBus = ConsoleEventBus(),
) {
    private val routeTable = ConsoleRouteTable(
        listOf(
            ServerVersionRoutes(),
            ConsoleEventRoutes(service, eventBus),
            SessionRoutes(service, consoleAssetsDir, consoleToken),
            // existing routes unchanged
        ),
    )
}
```

- [ ] **Step 5: Verify and commit**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleEventsRoutesTest" --no-daemon
```

Expected: PASS.

Commit:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventRoutes.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt
git commit -m "feat(console): expose SSE events endpoint"
```

## Task 3: Emit Session Events From Mutations

**Files:**
- Modify: `SessionRoutes.kt`
- Modify: `FeedbackItemRoutes.kt`
- Modify: `MarkHandedOffRoutes.kt`
- Test: `ConsoleEventsRoutesTest.kt`

- [ ] **Step 1: Add mutation emit test**

Add to `ConsoleEventsRoutesTest.kt`:

```kotlin
@Test
fun itemMutationEmitsSessionAndSummaryEvents() {
    val fixture = newConsoleSessionFixture()
    val bus = ConsoleEventBus(clock = { 1L })
    val emitted = mutableListOf<String>()
    bus.subscribe { emitted += it.name }
    val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
    try {
        server.start()
        ConsoleHttpTestClient(server.url).postJson(
            "/api/agent-handoffs",
            """{"sessionId":"${fixture.sessionId}","itemIds":["${fixture.itemId}"]}""",
        )
        assertTrue("session-updated" in emitted)
        assertTrue("sessions-updated" in emitted)
    } finally {
        server.stop()
    }
}
```

- [ ] **Step 2: Add an emit helper**

Create a small helper in a new file `ConsoleEventEmitters.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.SessionDto
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal fun ConsoleEventBus.emitSessionUpdated(session: SessionDto?) {
    val data = if (session == null) kotlinx.serialization.json.buildJsonObject {} else fixThisJson.encodeToJsonElement(session).jsonObject
    emit("session-updated", data)
}

internal fun ConsoleEventBus.emitSessionsUpdated(service: FeedbackSessionService) {
    emit("sessions-updated", fixThisJson.encodeToJsonElement(service.listSessions()).jsonObject)
}
```

- [ ] **Step 3: Pass event bus into mutating routes and emit after success**

Change constructors:

```kotlin
internal class SessionRoutes(
    private val service: FeedbackSessionService,
    private val consoleAssetsDir: File?,
    private val consoleToken: String,
    private val eventBus: ConsoleEventBus,
) : ConsoleRoute
```

After each successful mutation with an updated session:

```kotlin
exchange.sendJson(200, updated)
eventBus.emitSessionUpdated(updated)
eventBus.emitSessionsUpdated(service)
```

Apply the same pattern to `FeedbackItemRoutes` and `MarkHandedOffRoutes`.

- [ ] **Step 4: Verify and commit**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleEventsRoutesTest.itemMutationEmitsSessionAndSummaryEvents" --no-daemon
```

Expected: PASS.

Commit:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt
git commit -m "feat(console): emit session SSE events"
```

## Task 4: Browser Event Subscriber

**Files:**
- Create: `fixthis-mcp/src/main/console/events.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `scripts/build-console-assets.mjs`
- Create: `scripts/consoleEvents-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Add source contract test**

Create `scripts/consoleEvents-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync('fixthis-mcp/src/main/console/events.js', 'utf8');
const manifest = JSON.parse(readFileSync('scripts/console-tests.json', 'utf8'));

test('events subscriber uses EventSource and all required event names', () => {
  assert.match(source, /new EventSource\('\/api\/events'\)/);
  for (const name of ['snapshot', 'session-updated', 'sessions-updated', 'preview-ready', 'devices-updated', 'connection-updated']) {
    assert.match(source, new RegExp(`addEventListener\\('${name}'`));
  }
});

test('console events test is in manifest', () => {
  assert.ok(Object.values(manifest).flat().includes('scripts/consoleEvents-test.mjs'));
});
```

- [ ] **Step 2: Implement subscriber**

Create `events.js`:

```js
// @requires state.js, history.js, rendering.js, preview.js, devices.js, connection.js
            let consoleEventsSource = null;
            let consoleEventsReconnectTimer = null;

            function parseEventJson(event) {
              if (!event || !event.data) return null;
              return JSON.parse(event.data);
            }

            function startConsoleEvents() {
              if (consoleEventsSource) return;
              consoleEventsSource = new EventSource('/api/events');
              consoleEventsSource.addEventListener('snapshot', event => {
                const payload = parseEventJson(event);
                if (!payload) return;
                if (payload.session !== undefined) setConsoleSession(payload.session);
                if (payload.sessions?.sessions) renderSessionsListFromPayload(payload.sessions.sessions);
                if (payload.connection) renderConnection(payload.connection);
                renderSessionRegions();
                renderPreviewOnly();
                renderInspectorRegion();
              });
              consoleEventsSource.addEventListener('session-updated', event => {
                const payload = parseEventJson(event);
                setConsoleSession(payload);
                renderSessionRegions();
                renderPreviewOnly();
                renderInspectorRegion();
              });
              consoleEventsSource.addEventListener('sessions-updated', event => {
                const payload = parseEventJson(event);
                renderSessionsListFromPayload(payload?.sessions || []);
              });
              consoleEventsSource.addEventListener('preview-ready', event => {
                if (draftFlow()) return;
                const payload = parseEventJson(event);
                if (!payload) return;
                setConsolePreview(payload);
                renderPreviewOnly();
              });
              consoleEventsSource.addEventListener('devices-updated', () => refreshDevices().catch(showError));
              consoleEventsSource.addEventListener('connection-updated', event => {
                const payload = parseEventJson(event);
                if (payload) renderConnection(payload);
              });
              consoleEventsSource.addEventListener('events-dropped', () => refresh().catch(showError));
              consoleEventsSource.onerror = () => {
                clearTimeout(consoleEventsReconnectTimer);
                consoleEventsReconnectTimer = setTimeout(() => {
                  showWarning('Reconnecting feedback events...');
                  refresh().catch(showError);
                }, 2000);
              };
              consoleEventsSource.onopen = () => {
                clearTimeout(consoleEventsReconnectTimer);
              };
            }
```

- [ ] **Step 3: Wire bundle and startup**

Add `events.js` to `scripts/build-console-assets.mjs` sources before `main.js`.
In `main.js`, after `refresh()` succeeds and before polling starts:

```js
                startConsoleEvents();
```

Add `scripts/consoleEvents-test.mjs` to the `session` group in
`scripts/console-tests.json`.

- [ ] **Step 4: Verify and commit**

Run:

```bash
node --test scripts/consoleEvents-test.mjs
node scripts/build-console-assets.mjs
npm run console:test:all
node scripts/build-console-assets.mjs --check
```

Expected: all PASS and bundle check clean.

Commit:

```bash
git add fixthis-mcp/src/main/console/events.js fixthis-mcp/src/main/console/main.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map scripts/build-console-assets.mjs scripts/console-tests.json scripts/consoleEvents-test.mjs
git commit -m "feat(console): subscribe to SSE state events"
```

## Task 5: Preview, Device, Connection Events

**Files:**
- Modify: `PreviewRoutes.kt`
- Modify: `DeviceRoutes.kt`
- Modify: `ConnectionRoutes.kt`
- Test: `ConsoleEventsRoutesTest.kt`

- [ ] **Step 1: Add route emission tests**

Add tests that subscribe to `ConsoleEventBus`, call `/api/preview`, `/api/devices`, and `/api/connection`, then assert `preview-ready`, `devices-updated`, and `connection-updated` are emitted when responses succeed or state changes.

- [ ] **Step 2: Emit event payloads**

After successful preview capture:

```kotlin
val preview = service.capturePreview()
exchange.sendJson(200, preview)
eventBus.emit("preview-ready", fixThisJson.encodeToJsonElement(preview).jsonObject)
```

After device list or selection response:

```kotlin
val devices = service.consoleDevices()
exchange.sendJson(200, devices)
eventBus.emit("devices-updated", fixThisJson.encodeToJsonElement(devices).jsonObject)
```

After connection status response when the rendered state changes:

```kotlin
val connection = service.consoleConnectionStatus()
exchange.sendJson(200, connection)
eventBus.emit("connection-updated", fixThisJson.encodeToJsonElement(connection).jsonObject)
```

- [ ] **Step 3: Verify and commit**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleEventsRoutesTest" --no-daemon
npm run console:test:all
```

Expected: PASS.

Commit:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt
git commit -m "feat(console): emit preview device and connection events"
```

## Task 6: Final Verification And Docs

**Files:**
- Modify: `docs/architecture/console-state-sync-design.md`
- Modify: `docs/releases/unreleased.md`

- [ ] **Step 1: Update docs**

Change the architecture status from "SSE migration remains deferred" to "SSE
Phase 1 shipped; polling remains fallback". Add a short note to unreleased
docs under Highlights.

- [ ] **Step 2: Run verification**

Run:

```bash
npm run console:test:all
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
./gradlew :fixthis-mcp:test --no-daemon
git diff --check
```

Expected: all PASS.

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/console-state-sync-design.md docs/releases/unreleased.md
git commit -m "docs(console): document SSE state sync"
```

## Self-Review

- Spec coverage: event bus, endpoint, replay, browser subscription, emit points,
  fallback polling, preview/device/connection events, tests, and docs are
  covered by Tasks 1-6.
- Placeholder scan: this plan uses concrete file paths, event names, commands,
  and expected outcomes.
- Type consistency: `ConsoleEventBus`, `ConsoleEvent`, `ConsoleEventReplay`,
  and event names are consistent between server, tests, and browser subscriber.
