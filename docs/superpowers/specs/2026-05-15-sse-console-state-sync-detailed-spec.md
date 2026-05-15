# Spec - SSE Console State Sync

Status: Draft
Date: 2026-05-15
Scope: `:fixthis-mcp` feedback console HTTP server, console JavaScript state sync, preview/session polling
Related architecture record: [`../../architecture/console-state-sync-design.md`](../../architecture/console-state-sync-design.md)
Related implementation plan: [`../plans/2026-05-15-sse-console-state-sync-implementation.md`](../plans/2026-05-15-sse-console-state-sync-implementation.md)

## Summary

The feedback console currently keeps browser state fresh through lockstep
`refreshSessions()` calls and ETag-conditional polling. That design fixed the
original sidebar/panel drift bug, but it is still pull-based. Server-side
changes from another tab, agent status updates, and live preview availability
can lag by the polling interval or pause entirely while the tab is hidden, a
mutation is in flight, or saved text is being edited.

This spec promotes console state sync to a server-push model using
Server-Sent Events (SSE). The first production slice adds `/api/events`, an
event bus with replay, browser `EventSource` subscription, and event-driven
updates for session summaries, the active session, connection status, device
status, and preview availability. Existing polling and refresh calls remain as
fallbacks until the event stream has enough coverage to retire them safely.

## Goals

- Add `GET /api/events` with `text/event-stream` framing.
- Send an initial `snapshot` event containing the same state the browser
  currently collects through startup pulls.
- Emit ordered server events after session, device, connection, and preview
  state changes.
- Preserve reconnect correctness through SSE `id:` values and
  `Last-Event-ID` replay.
- Update browser state from `EventSource` without clobbering active draft
  editing or pending boundary prompts.
- Keep `refresh()`, `refreshSessions()`, ETag session polling, and live preview
  polling as explicit fallbacks in the first implementation.
- Provide tests for event bus ordering, endpoint framing, browser subscriber
  behavior, and multi-tab freshness.

## Non-Goals

- No WebSocket endpoint.
- No collaborative editing, CRDT, or optimistic mutation reconciliation.
- No public persisted MCP JSON schema changes.
- No bridge protocol bump.
- No removal of polling in the first production slice.
- No external network calls or cloud sync.

## Current State

The server is a `com.sun.net.httpserver.HttpServer` behind
`FeedbackConsoleServer`. Routes are registered through `ConsoleRouteTable`.
Current relevant routes:

- `SessionRoutes` owns `/api/sessions`, `/api/session`, session open/close,
  and ETag responses.
- `ConnectionRoutes` owns connection and heartbeat status.
- `DeviceRoutes` owns device list and selection.
- `PreviewRoutes` owns `/api/preview` and preview screenshots.
- `FeedbackItemRoutes`, `MarkHandedOffRoutes`, and `HandoffPreviewRoutes`
  mutate session state and then the browser refreshes with pull calls.

The browser initializes with `refresh()`, then starts:

- `startHeartbeatPolling()`
- `startLivePreviewPolling()`
- `startSessionsPolling()`

`sessions-polling.js` pauses while hidden, while a mutation is in flight, and
while saved annotations are being edited. `preview.js` polls independently for
live preview frames.

## Event Model

Add these serializable event models under `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/`.

```kotlin
@Serializable
internal data class ConsoleEvent(
    val id: Long,
    val name: String,
    val data: JsonObject,
    val createdAtEpochMillis: Long,
)

@Serializable
internal data class ConsoleSnapshotEventData(
    val session: SessionDto?,
    val sessions: FeedbackSessionList,
    val devices: ConsoleDeviceList,
    val connection: ConsoleConnectionStatus,
)
```

Event names:

| Event | Payload | Purpose |
| --- | --- | --- |
| `snapshot` | `ConsoleSnapshotEventData` JSON | Initial state or replay overflow recovery. |
| `session-updated` | enriched `SessionDto` JSON or `null` JSON | Active session changed. |
| `sessions-updated` | `FeedbackSessionList` JSON | Sidebar/history summaries changed. |
| `devices-updated` | `ConsoleDeviceList` JSON | Device list or selected device changed. |
| `connection-updated` | `ConsoleConnectionStatus` JSON | Bridge connection status changed. |
| `preview-ready` | `FeedbackPreviewSnapshot` JSON | A new live preview is available. |
| `events-dropped` | `{ "lastEventId": n, "oldestAvailableEventId": m }` | Client reconnect is older than ring buffer; browser must refresh or accept a following `snapshot`. |

`ConsoleEvent.name` is the SSE `event:` value. `ConsoleEvent.id` is the SSE
`id:` value. IDs are monotonic for the lifetime of a console server process.

## Event Bus

Create `ConsoleEventBus` as an in-memory process-local fan-out with a bounded
ring buffer. It does not persist events to disk.

Requirements:

- `emit(name, data)` assigns a new monotonic ID and stores the event in a ring.
- `eventsAfter(lastEventId)` returns events with `id > lastEventId` when they
  are still in the ring.
- If `lastEventId` is older than the oldest event in the ring, the endpoint
  emits `events-dropped` followed by `snapshot`.
- Multiple subscribers can attach concurrently.
- Emitting does not block session mutation paths on a slow browser connection.
- Keep-alive comment frames are sent every 15 seconds from the endpoint.

The default ring size is 256 events.

## Endpoint Contract

`GET /api/events`:

- Method: `GET` only.
- Content-Type: `text/event-stream; charset=utf-8`.
- Headers:
  - `Cache-Control: no-cache`
  - `Connection: keep-alive`
  - `X-Accel-Buffering: no`
- The endpoint does not require the console mutation token because it is read
  only, but it must only be served from the local console server.
- On connect:
  1. Read `Last-Event-ID`.
  2. Replay ring events if possible.
  3. Emit `snapshot` if no usable replay exists or replay overflow happened.
  4. Stream live events and keep-alives until the client disconnects.

Frame format:

```text
id: 42
event: sessions-updated
data: {"sessions":[]}

```

Multiline data is not used. Payloads are compact one-line JSON.

## Server Emit Points

The first slice emits after successful state reads/mutations, not before.

- `SessionRoutes`
  - `/api/session/open`: emit `session-updated` and `sessions-updated`
  - `/api/session/close`: emit `session-updated` and `sessions-updated`
  - `/api/sessions` delete/close style route handlers: emit `sessions-updated`
- `FeedbackItemRoutes`
  - batch save, draft update, item delete, resolve/claim: emit
    `session-updated` and `sessions-updated`
- `MarkHandedOffRoutes`
  - emit `session-updated` and `sessions-updated`
- `PreviewRoutes`
  - successful `/api/preview`: emit `preview-ready`
- `DeviceRoutes`
  - device selection/clear/list refresh: emit `devices-updated`
- `ConnectionRoutes`
  - heartbeat/status responses that detect a state transition: emit
    `connection-updated`

If a route returns an updated session object, emit from that value rather than
re-reading the session where possible. Summary events may call
`service.listSessions()` after the mutation.

## Browser Subscriber

Add a dedicated console module `events.js`.

Responsibilities:

- Create one `EventSource('/api/events')` during startup.
- Apply `snapshot` by updating:
  - `state.session`
  - `state.sessionSummaries`
  - device state
  - connection state
  - preview state when present in future payloads
- Apply `session-updated` by calling `setConsoleSession(...)` and rendering
  session-owned regions.
- Apply `sessions-updated` by updating `state.sessionSummaries` and rendering
  current session list.
- Apply `devices-updated` through the existing device rendering path.
- Apply `connection-updated` through the existing connection rendering path.
- Apply `preview-ready` only when no draft flow is active and the preview
  belongs to the active session.
- Show a small reconnecting status when the stream is connecting for more than
  2 seconds.
- On stream error, run `refresh()` as fallback and keep `EventSource`
  reconnect enabled.

The subscriber must not mutate draft workspace state, pending boundary state,
or comment text currently being edited.

## Phases

### Phase 1 - Event Bus And Endpoint

Add the event bus, `/api/events`, snapshot emission on connect, keep-alive
frames, ring replay, and endpoint tests. Browser behavior is unchanged.

### Phase 2 - Session Event Subscription

Emit session/session-list events from mutation routes and subscribe in the
browser. Keep `refreshSessions()` and ETag polling active.

### Phase 3 - Preview And Device Events

Emit `preview-ready`, `devices-updated`, and `connection-updated`; use events
to update browser regions. Keep polling fallback active.

### Phase 4 - Harness Coverage

Add browser tests for two tabs observing an item mutation within 500 ms and for
stream reconnect with replay. Document that polling remains fallback-only.

### Phase 5 - Pull Path Reduction

After Phases 1-4 are stable, remove happy-path `refreshSessions()` calls from
mutation handlers that already receive event updates. Manual refresh and error
fallback remain.

## Acceptance Criteria

- `GET /api/events` streams valid SSE frames and includes `snapshot` on a fresh
  connection.
- Reconnecting with `Last-Event-ID` replays missed events in order.
- Reconnecting with an event ID older than the ring emits `events-dropped` and
  then `snapshot`.
- A session mutation in one browser tab updates another tab without waiting for
  the 2 second polling interval.
- A preview capture can surface through `preview-ready` without an additional
  live preview polling tick.
- Active draft editing and pending boundary prompts are not reset by incoming
  events.
- Existing local checks continue to pass:

```bash
npm run console:test:all
node scripts/build-console-assets.mjs --check
./gradlew :fixthis-mcp:test --no-daemon
```

## Risks

| Risk | Mitigation |
| --- | --- |
| Slow client blocks route thread | Subscriber writes run outside mutation paths; failed writes unsubscribe. |
| Missed emit point | Keep polling fallback and add route mutation tests for emitted event types. |
| Browser reconnect loops noisily | Use native `EventSource` reconnect and show reconnect status only after 2 seconds. |
| Event payload grows too broad | Use full session/session list only for session events; preview event remains the current preview snapshot. |
| Hidden tab receives events while editing | Subscriber checks active editing and uses existing render guards. |
