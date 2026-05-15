# Spec - Console Transport and Session Resilience

Status: Draft
Date: 2026-05-15
Scope: `:fixthis-mcp` feedback console HTTP transport, SSE stream lifecycle, browser session/preview context ownership, regression tests
Related docs:
- [`../../architecture/console-state-sync-design.md`](../../architecture/console-state-sync-design.md)
- [`../../reference/feedback-console-contract.md`](../../reference/feedback-console-contract.md)
- [`2026-05-15-console-session-preview-sync-hardening-detailed-spec.md`](2026-05-15-console-session-preview-sync-hardening-detailed-spec.md)
- [`2026-05-15-console-js-reliability-stabilization-detailed-spec.md`](2026-05-15-console-js-reliability-stabilization-detailed-spec.md)
- [`../plans/2026-05-15-console-transport-session-resilience.md`](../plans/2026-05-15-console-transport-session-resilience.md)

## Summary

The feedback console can legitimately see `java.io.IOException: Connection reset by peer` when the browser cancels a request. The most common trigger is rapid session switching: the browser replaces an image URL or tears down a fetch/EventSource while the local `HttpServer` is still writing bytes. That cancellation is normal transport behavior, not a FixThis server defect.

There is a second, user-visible failure mode in the same area: an old preview request can complete after the active session changed and then update visible preview state. That makes it look as if clicking another session did not move, even when `/api/session/open` succeeded.

This spec defines a durable fix for the whole class:

- all response writes classify browser disconnects consistently;
- error response writes are safe even when the client has already gone away;
- SSE `/api/events` does not commit stream headers until its initial snapshot can be produced;
- browser preview state is owned by an explicit session-preview context;
- session changes invalidate stale preview/draft state through one helper;
- tests encode the transport and context ownership contracts.

## Goals

- Treat client-side request cancellation as normal for every feedback console HTTP route.
- Prevent `FeedbackConsoleServer:` diagnostic logs for browser disconnects.
- Prevent catch blocks from rethrowing while trying to write error JSON to a closed socket.
- Make SSE startup failure recoverable as normal JSON error before streaming headers are sent.
- Keep SSE keep-alive disconnects quiet and close the subscription promptly.
- Prevent stale preview or draft freeze state from being applied after the active session changes.
- Make future routes and browser flows follow obvious shared helpers instead of ad hoc checks.
- Add focused tests that fail when a new direct response writer or unscoped preview update is introduced.

## Non-Goals

- No MCP tool signature changes.
- No persisted feedback session JSON field renames.
- No Android bridge protocol changes.
- No replacement of `com.sun.net.httpserver.HttpServer`.
- No conversion of console JavaScript to TypeScript or ESM modules.
- No multi-tab collaborative editing protocol.
- No change to release/debug-only support boundaries.

## Current Failure Anatomy

### Observed Stack

The reported stack fails while writing PNG bytes:

```text
FeedbackConsoleServer: java.io.IOException: Connection reset by peer
...
io.beyondwin.fixthis.mcp.console.ConsoleHttpKt.sendBytes(ConsoleHttp.kt:105)
io.beyondwin.fixthis.mcp.console.PreviewRoutes.sendPreviewScreenshot(PreviewRoutes.kt:98)
```

`sendBytes()` writes a fixed-length response body. If the browser closes the request early, `HttpServer` can throw:

- `Connection reset by peer`;
- `Broken pipe`;
- `stream is closed`;
- a suppressed `insufficient bytes written to stream` from `FixedLengthOutputStream.close()`.

The user-visible symptom can be worse than the log. If the old request belongs to session A and the user has already switched to session B, any late UI update from session A can make the console appear stuck on the previous session.

### Known Trigger Paths

1. Live preview image cancellation:
   - `preview.js` renders a new `/api/preview/<previewId>/screenshot/full?sessionId=...` URL.
   - Browser cancels the previous image request.
   - Server is still in `sendBytes()`.

2. Persisted screen image cancellation:
   - `screenImageUrl()` switches from one `/api/screens/<screenId>/screenshot/full?sessionId=...` URL to another.
   - Same fixed-length PNG write failure can occur.

3. JSON or markdown request cancellation:
   - Browser navigation, tab close, or rapid UI transition cancels a fetch.
   - Most route bodies use `ConsoleHttp.sendText()`/`sendJson()`/`sendMarkdown()`, which route through `sendBytes()`.

4. Error response cancellation:
   - Route throws `FeedbackConsoleHttpException`, `FeedbackSessionException`, or `BridgeConnectionException`.
   - `FeedbackConsoleServer.dispatch()` catches it and calls `sendErrorJson()`.
   - If the client disconnected before the error JSON is written, that write is currently a separate risk point.

5. SSE disconnect:
   - Browser reloads, tab sleeps, or EventSource reconnects.
   - `/api/events` keep-alive write fails after the response is already streaming.
   - This should close the subscription, not produce a server defect.

## Required Behavior

### 1. Client Disconnect Classification Is Shared

There must be one classifier in `ConsoleHttp.kt`:

```kotlin
internal fun Throwable.isClientDisconnect(): Boolean
```

It must inspect:

- the thrown exception;
- suppressed exceptions;
- the cause chain.

It must classify these message fragments case-insensitively:

```text
connection reset
broken pipe
stream is closed
insufficient bytes written to stream
```

Routes and server dispatch code must not duplicate this logic.

### 2. Route Body Writes Can Fail Without Noisy Diagnostics

If a route body throws a client disconnect while writing a normal response, `FeedbackConsoleServer.dispatch()` must:

- call `exchange.close()` through a quiet helper;
- return without logging;
- avoid trying to write a JSON 500 response to the same closed socket.

This applies to:

- PNG screenshots;
- JSON session/list/device responses;
- Markdown handoff preview;
- HTML index;
- 204/304 close paths where close itself can throw.

### 3. Error Response Writes Are Also Safe

All catch blocks that send JSON errors must call a safe helper:

```kotlin
internal fun HttpExchange.trySendErrorJson(status: Int, message: String)
```

Rules:

- If `sendErrorJson()` succeeds, response behavior is unchanged.
- If it fails with `isClientDisconnect()`, close quietly and suppress.
- If it fails for another reason, rethrow so real server defects still surface.

This covers the nested failure case:

1. route fails with a normal app-level error;
2. server tries to send JSON error;
3. browser has already disconnected;
4. dispatch should not log a misleading server failure.

### 4. Direct Response Writers Are Not Allowed In Console Routes

Except for SSE streaming internals, console routes should use `ConsoleHttp` helpers.

Forbidden in route classes:

```kotlin
exchange.responseBody.use { it.write(bytes) }
exchange.sendResponseHeaders(200, bytes.size.toLong())
```

Allowed:

```kotlin
exchange.sendText(200, payload, "application/json; charset=utf-8")
exchange.sendJson(200, dto)
exchange.sendBytes(200, bytes, "image/png")
exchange.sendNoContent()
exchange.sendNotModified(etag)
```

`ServerVersionRoutes.kt` is the current direct-writer route and must be moved to `sendText()`.

### 5. SSE Startup Computes Snapshot Before Headers

`ConsoleEventRoutes.handle()` must compute these before sending `HTTP 200` streaming headers:

- initial snapshot JSON;
- `Last-Event-ID` / `lastEventId`;
- replay result from `eventBus.eventsAfter(lastEventId)`.

If snapshot construction fails, the caller must still be in normal route mode so `FeedbackConsoleServer.dispatch()` can return JSON error.

Required ordering:

```kotlin
val initialSnapshot = snapshot()
val lastEventId = ...
val replay = eventBus.eventsAfter(lastEventId)

exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
exchange.sendResponseHeaders(200, 0)
```

This avoids the half-committed failure state where the response is already `text/event-stream` but the server needs to report a non-streaming error.

### 6. SSE Keep-Alive Disconnect Closes The Subscription

After streaming headers are committed, keep-alive write failure from client disconnect must:

- count down the subscriber latch;
- close the event bus subscription;
- close the output stream quietly;
- avoid diagnostics logging.

Non-client-disconnect write errors should still bubble to dispatch.

### 7. Browser Preview State Is Session-Owned

The active session must be the ownership boundary for `state.preview`.

Any code that awaits a preview request and then mutates UI must capture context before awaiting:

```js
const previewContext = capturePreviewContext();
const preview = await previewUseCases.request();
if (!previewContextStillCurrent(previewContext)) return;
```

The context shape:

```js
{
  sessionId: state.session?.sessionId || null,
  contextGeneration: previewUseCases.getState().contextGeneration
}
```

The context is current only if:

- `context.sessionId === (state.session?.sessionId || null)`;
- `context.contextGeneration === previewUseCases.getState().contextGeneration`.

### 8. Session Changes Invalidate Preview Context Once

Every server-driven or user-driven current-session change must call the same invalidation path:

```js
clearPreview();
```

`clearPreview()` already:

- bumps `previewUseCases.contextGeneration()`;
- clears `state.preview`.

Required session-change callers:

- `openSession(sessionId)` before `/api/session/open`;
- `newSession()` before `/api/session/open` with `newSession`;
- `refreshSessions()` when `/api/session` returns a different current session;
- SSE `snapshot` when the snapshot active session differs from current browser state;
- SSE `session-updated` when applying an active session replacement;
- device selection/disconnect paths that already clear preview must keep doing so.

### 9. Draft Annotation Freeze Uses The Same Preview Context

`startDraftAnnotationFlow()` must not rely only on `contextGeneration`.

It must capture `previewContext` before awaiting `previewUseCases.request()` and must verify that context before:

- calling `setConsolePreview(preview)`;
- creating the draft workspace with `startDraftFreeze(...)`.

If the session changed while preview capture was in flight, the function must return without creating a draft workspace.

## File-Level Design

### `ConsoleHttp.kt`

Add these helpers:

```kotlin
internal fun Throwable.isClientDisconnect(): Boolean {
    val messages = sequenceOf(this) + suppressed.asSequence() + generateSequence(cause) { it.cause }
    return messages.any { error ->
        val message = error.message?.lowercase().orEmpty()
        message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("stream is closed") ||
            message.contains("insufficient bytes written to stream")
    }
}

internal fun HttpExchange.closeQuietly() {
    runCatching { close() }
}

internal fun HttpExchange.trySendErrorJson(status: Int, message: String) {
    runCatching { sendErrorJson(status, message) }
        .onFailure { error ->
            if (error.isClientDisconnect()) {
                closeQuietly()
            } else {
                throw error
            }
        }
}
```

Keep `sendBytes()` strict. It should still throw when a route write fails; central dispatch should decide whether the failure is benign.

### `FeedbackConsoleServer.kt`

Use `trySendErrorJson()` in every typed catch block:

```kotlin
} catch (error: FeedbackConsoleHttpException) {
    exchange.trySendErrorJson(error.statusCode, error.message)
} catch (error: FeedbackSessionException) {
    val httpError = error.toConsoleHttpException()
    exchange.trySendErrorJson(httpError.statusCode, httpError.message)
} catch (error: BridgeConnectionException) {
    exchange.trySendErrorJson(HTTP_SERVICE_UNAVAILABLE, error.message ?: "FixThis bridge is not connected")
} catch (error: Throwable) {
    if (error.isClientDisconnect()) {
        exchange.closeQuietly()
        return
    }
    diagnosticsSink(
        "FeedbackConsoleServer: ${error::class.java.name}: ${error.message}\n${error.stackTraceToString()}",
    )
    exchange.trySendErrorJson(500, error.message ?: error::class.java.simpleName)
}
```

The diagnostic sink remains reserved for real server errors.

### `ServerVersionRoutes.kt`

Replace the handwritten byte writer:

```kotlin
exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
exchange.responseHeaders.add("Cache-Control", "no-store")
exchange.sendResponseHeaders(200, bytes.size.toLong())
exchange.responseBody.use { it.write(bytes) }
```

with:

```kotlin
exchange.responseHeaders.set("Cache-Control", "no-store")
exchange.sendText(200, payload, "application/json; charset=utf-8")
```

### `ConsoleEventRoutes.kt`

Target structure:

```kotlin
override fun handle(exchange: HttpExchange) {
    exchange.requireMethod("GET") {
        val initialSnapshot = snapshot()
        val lastEventId = exchange.requestHeaders.getFirst("Last-Event-ID")?.toLongOrNull()
            ?: exchange.queryParameter("lastEventId")?.toLongOrNull()
            ?: INITIAL_EVENT_ID
        val replay = eventBus.eventsAfter(lastEventId)

        exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Connection", "keep-alive")
        exchange.sendResponseHeaders(HTTP_OK, 0)

        val output = exchange.responseBody
        val subscriberClosed = CountDownLatch(1)
        fun send(event: ConsoleEvent) {
            output.writeSseEvent(event.name, event.id, event.data)
        }

        try {
            output.writeSseEvent("snapshot", null, initialSnapshot)
            if (replay.overflow) {
                output.writeSseEvent(
                    "replay-overflow",
                    null,
                    buildJsonObject {
                        replay.oldestAvailableEventId?.let { put("oldestAvailableEventId", it) }
                    },
                )
            } else {
                replay.events.forEach(::send)
            }

            val subscription = eventBus.subscribe { event ->
                runCatching { send(event) }.onFailure { subscriberClosed.countDown() }
            }
            try {
                while (!subscriberClosed.await(KEEP_ALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    runCatching {
                        output.write(": keep-alive\n\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                    }.onFailure { error ->
                        if (error.isClientDisconnect()) {
                            subscriberClosed.countDown()
                        } else {
                            throw error
                        }
                    }
                }
            } finally {
                subscription.close()
            }
        } finally {
            runCatching { output.close() }
        }
    }
}
```

This preserves the existing replay model. It does not attempt to solve the pre-existing non-atomic gap between replay lookup and subscription; polling and replay-overflow continue to provide fallback. A later event-bus plan can add buffered subscription if needed.

### `preview.js`

Add:

```js
function capturePreviewContext() {
  return {
    sessionId: state.session?.sessionId || null,
    contextGeneration: previewUseCases.getState().contextGeneration,
  };
}

function previewContextStillCurrent(context) {
  return Boolean(context) &&
    context.sessionId === (state.session?.sessionId || null) &&
    context.contextGeneration === previewUseCases.getState().contextGeneration;
}
```

Use it in `refreshPreview()` instead of hand-rolled `previewSessionId` and `previewContextGeneration` locals.

### `annotations.js`

Update `startDraftAnnotationFlow()` to use `capturePreviewContext()` before awaiting preview. It must call `previewContextStillCurrent()` before setting preview or creating a draft workspace.

### `history.js`

Update `refreshSessions()`:

```js
async function refreshSessions() {
  const previousSessionId = state.session?.sessionId || null;
  const [response, currentSession] = await Promise.all([
    requestJson('/api/sessions'),
    requestJson('/api/session'),
  ]);
  const nextSessionId = currentSession?.sessionId || null;
  if (previousSessionId !== nextSessionId) clearPreview();
  setConsoleSession(currentSession || null);
  renderSessionsListFromPayload(response.sessions || []);
  return response.sessions || [];
}
```

`openSession()` and `newSession()` already clear preview before mutation. Tests must keep that contract encoded.

### `events.js`

Add inside `startConsoleEvents()`:

```js
function applySessionFromServer(session) {
  const previousSessionId = state.session?.sessionId || null;
  const nextSessionId = session?.sessionId || null;
  if (previousSessionId !== nextSessionId) clearPreview();
  setConsoleSession(session || null);
}
```

Use it wherever SSE applies an authoritative active session payload.

`preview-ready` remains fenced:

```js
if (!data.preview || draftFlow()) return;
if (data.sessionId !== state.session?.sessionId) return;
```

## Test Strategy

### Kotlin Tests

`FeedbackConsoleServerErrorLoggingTest.kt`

- `clientDisconnectsDuringResponseWriteAreNotLoggedAsServerErrors`
- `clientDisconnectsDuringErrorResponseWriteAreNotRethrownOrLogged`
- source contract that `ServerVersionRoutes.kt` uses `sendText()` and not `responseBody.use`

`ConsoleEventsRoutesTest.kt`

- `eventsEndpointReturnsJsonErrorWhenSnapshotFailsBeforeStreamStarts`
- `eventsRouteTreatsKeepAliveClientDisconnectAsNormalClosure`
- existing `eventsEndpointStreamsInitialSnapshot`
- existing session/preview event tests

Focused command:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerErrorLoggingTest" --tests "io.beyondwin.fixthis.mcp.console.ConsoleEventsRoutesTest" --no-daemon
```

### Browser Source Tests

Create `scripts/transportSessionResilience-test.mjs` and add it to the `session` group in `scripts/console-tests.json`.

Required assertions:

- `preview.js` exposes `capturePreviewContext()` and `previewContextStillCurrent()`.
- `refreshPreview()` uses those helpers.
- `startDraftAnnotationFlow()` uses those helpers.
- `refreshSessions()` clears preview when current session changes.
- `events.js` uses an `applySessionFromServer()` helper that clears preview when active session changes.

Focused command:

```bash
npm run console:session:test
```

### Bundle Verification

Because browser source changes require committed bundle resources:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs --check
```

### Full Relevant Verification

```bash
npm run console:session:test
npm run console:preview:test
npm run console:fsm:test
./gradlew :fixthis-mcp:test --no-daemon
```

## Acceptance Criteria

- Rapid session switching cannot apply an old preview response to the new active session.
- Starting annotation mode cannot create a draft workspace for a session that is no longer active.
- Browser cancellation of PNG, JSON, Markdown, HTML, error JSON, or SSE keep-alive responses does not produce `FeedbackConsoleServer:` diagnostics.
- `/api/events` returns a normal JSON error if initial snapshot construction fails before stream start.
- `/api/events` closes subscriptions quietly on client disconnect.
- Console route classes, except SSE stream internals, no longer write directly to `responseBody`.
- Console bundle check passes after source changes.
- Existing MCP schema compatibility tests remain unchanged.

## Rollout Notes

This is a local developer-tool hardening change. No migration is required for `.fixthis/` sessions or persisted MCP output.

Developers running an already-open console tab must hard refresh or restart the MCP console to load the regenerated `app.js`. The existing staleness banner should catch stale client/server bundles.

## Risks And Mitigations

- Risk: Suppressing too many IO errors could hide real server defects.  
  Mitigation: `isClientDisconnect()` matches only known client-disconnect message fragments and rethrows all other response-write failures.

- Risk: Clearing preview on every session refresh could remove useful preview during harmless polling.  
  Mitigation: `refreshSessions()` compares previous and next current `sessionId` and calls `clearPreview()` only when it changes.

- Risk: SSE snapshot-before-headers increases time before the EventSource receives HTTP 200.  
  Mitigation: snapshot work already happened at connection start; this only moves it before header commit so failures remain recoverable. Normal successful path remains the same.

- Risk: Source-level JavaScript tests can become brittle.  
  Mitigation: tests assert helper contracts and call-site usage, not minified bundle formatting.

## Future Extensions

- Add an atomic `ConsoleEventBus.subscribeFrom(lastEventId)` API that returns replay plus a live buffered subscription without a replay/subscribe gap.
- Add structured transport metrics for client disconnects, SSE reconnect count, and preview race discards.
- Move session/preview context helpers into a small pure module once the console test loader covers browser globals cleanly.
- Add Playwright harness coverage for rapid session switching with a deliberately delayed preview response.
