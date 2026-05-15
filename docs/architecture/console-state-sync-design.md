# Console State Sync - Current Design + SSE Migration Plan

**Status:** SSE Phase 1 shipped; ETag-conditional session polling remains as
the fallback path.
**Owners:** `fixthis-mcp` (FeedbackConsoleServer + console JS)
**Related code:** `fixthis-mcp/src/main/console/history.js`
(`refreshSessions`, `refresh`), `sessions-polling.js`, `state.js`,
`livePreviewPolling`, and `fixthis-mcp/src/main/kotlin/.../console/SessionRoutes.kt`

---

## Background

The console keeps two pieces of session state on the client:

| Piece | Source | Drives |
| --- | --- | --- |
| `state.sessionSummaries` | `GET /api/sessions` (server-aggregated `FeedbackSessionSummary` list) | Sidebar History rows, working pips, session ordinals |
| `state.session` | `GET /api/session`, `POST /api/session/open`, `POST /api/agent-handoffs`, `POST /api/items/...` | Top-toolbar counter, right ANNOTATIONS panel, preview overlay, Copy Prompt / Save to MCP payload |

These two are written by separate code paths and historically went out of sync.

### The bug that prompted this work (2026-05-10)

User screenshot showed:

- Sidebar `Session 1`: 3 open · 0 done · 3 pts · 2 screens
- Top toolbar: 9 open · 1 resolved
- Right ANNOTATIONS panel: 10 items across 4 screens

Audit of `.fixthis/feedback-sessions/` confirmed the sidebar matched server truth (3 active sessions: 3 / 1 / 2 items respectively, **zero items with `delivery=='sent'` anywhere on disk**). The toolbar and panel were displaying a `state.session` whose 4-screen / 10-item shape did not exist in any session JSON on disk — i.e. the in-memory `state.session` had drifted from server truth and no client code path was triggering a re-fetch of `/api/session`.

Root cause: `refreshSessions()` only refreshed `state.sessionSummaries`. It was the natural choke-point after every mutation but did nothing for `state.session`. Any code path that mutated server state and then called only `refreshSessions()` (e.g. `applySavedSessionUpdate` on item edits/deletes, post-handoff recoveries, certain failure branches) left `state.session` stale.

---

## Current design - lockstep refresh plus conditional polling

**Change 1:** `refreshSessions()` fetches `/api/sessions` and `/api/session`
in parallel and writes both `state.sessionSummaries` and `state.session`
before re-rendering session-owned UI.

```js
async function refreshSessions() {
  const [response, currentSession] = await Promise.all([
    requestJson('/api/sessions'),
    requestJson('/api/session'),
  ]);
  state.session = currentSession || null;
  renderSessionsListFromPayload(response.sessions || []);
  return response.sessions || [];
}
```

`refresh()` no longer fetches `/api/session` itself — `refreshSessions()` owns that responsibility.

**Change 2:** `sessions-polling.js` keeps the same two resources current while
the console is idle. `SessionRoutes.kt` returns ETags for `/api/sessions` and
`/api/session`; the browser sends `If-None-Match`, treats 304 as "no change",
and only re-renders when the server state changed.

Polling runs every 2 seconds while the page is visible. It pauses while the
tab is hidden, while a local mutation is in flight, and while saved-item text
is being edited so the server refresh cannot clobber user input. After five
consecutive polling failures it pauses and surfaces a "Reconnecting feedback
updates..." state on the connection card. Any successful mutating action or
tab visibility restore restarts the loop.

### Coverage - what the current design fixes

Every existing call site already invoked `refreshSessions()` after a server mutation:

- `applySavedSessionUpdate` (item edit/delete) — `annotations.js:436`
- `persistPendingFeedbackItems` (batch save) — `annotations.js:511`
- `sendAgentPrompt` (handoff) — `prompt.js:489`
- `openSession`, `newSession`, `closeSession`, `deleteHistorySession` — `history.js`
- `refresh()` (initial load + manual refresh button)

Lockstep refresh keeps the panel/toolbar in sync with the sidebar after
mutations. Conditional polling covers passive updates such as another tab or
agent claiming/resolving an item, and it does that without re-render churn when
the ETag is unchanged.

### Residual gap - what the current design does NOT fix

The design is still **pull-based**. It is much harder to drift than the
original Option 1, but the client still learns about server changes on a timer
or after a local action rather than through authoritative push.

1. **Up-to-poll-interval lag.** Another tab or agent mutation can take up to
   the polling interval to appear.
2. **Paused or failed polling.** A hidden tab, an active edit, an in-flight
   mutation, or five consecutive polling failures intentionally stops refresh.
3. **Manual disk edits.** A developer editing `session.json` by hand can still
   produce state the browser only learns about after the next successful
   refresh.
4. **Multi-tab ordering.** Two tabs can still observe changes in different
   orders. The server remains authoritative, but the client does not have an
   event ordering contract.

For a single-user local dev tool, these are acceptable and recoverable by any
successful interaction or manual refresh. The remaining event-ordering and
latency gaps are what motivate SSE.

---

## SSE Migration — why and how

### Why SSE (and why not the alternatives)

| Approach | Verdict | Reason |
| --- | --- | --- |
| **Pull on every interaction** (Option 1 foundation) | shipped | Simple and robust for local mutations, but insufficient alone for passive updates. |
| **ETag-conditional polling** (fallback) | shipped | Low churn fallback for SSE failure, manual refresh, and passive updates when EventSource is unavailable. |
| **Blind periodic polling** | rejected | Constant idle load + visible refresh churn + races with in-flight user mutations. UX feels twitchy. |
| **WebSocket** | rejected | Bidirectional; we only need server→client. Adds connection upgrade and framing complexity for no benefit over SSE. |
| **Server-Sent Events (SSE)** | Phase 1 shipped | One-way server-push over plain HTTP/1.1. Browser `EventSource` auto-reconnects with `Last-Event-ID`; the console still keeps polling as fallback. |

### Goals

- `state.session` and `state.sessionSummaries` reflect server truth within ~one network hop of any change, regardless of which code path or which client triggered it.
- Existing pull-based call sites can be removed or kept as fallbacks (defense-in-depth).
- `livePreviewPolling` should fold into the same channel — preview frame availability is just another server-side event.
- Reconnection and ordering guarantees are explicit, not implicit.

### Non-goals

- Optimistic mutation reconciliation (CRDT, OT). The console is single-user; last-writer-wins via authoritative server state is fine.
- Multi-user collaboration. Out of scope; SSE design simply doesn't preclude it.
- Subscription filters (e.g. "only send events for session X"). Console only has one active session at a time; broadcasting all session events to the one connected client is cheap.

### Server design sketch

New endpoint:

```
GET /api/events         (text/event-stream)
```

Behavior:
- Long-lived response. Server holds the connection open and writes SSE frames.
- Each frame: `event: <name>\ndata: <json>\nid: <monotonic-int>\n\n`.
- On connect, server sends a `snapshot` event with the current `{ session, sessions, devices, connection }` payload — equivalent to one-shot `refresh()`. Eliminates the need for an initial pull.
- On any subsequent server-side state change, server emits one of:
  - `session-updated` — payload: full `SessionDto` of the now-active session (or `null`).
  - `sessions-updated` — payload: full `FeedbackSessionSummary[]`.
  - `devices-updated`, `connection-updated`, `preview-ready` — fold in existing polling concerns.
- `id:` is a monotonic counter assigned per-event; client stores last-seen id.
- Standard SSE auto-reconnect: browser sends `Last-Event-ID: <n>` header on reconnect; server replays any events with `id > n` from a small ring buffer (~256 events) before resuming live stream. Events older than the ring force a `snapshot` event instead.

Server emit points (where to call `events.emit(...)`):
- `FeedbackSessionStore` mutation methods (item add/edit/delete, batch save, status change, handoff, close, open)
- `LivePreviewService` when a new preview frame is ready
- `DeviceService` when device list or selection changes
- `ConnectionService` when connection state changes

Concurrency: the existing Kotlin server is coroutine-based; emit is a single suspending call to a `MutableSharedFlow<Event>` per connection.

### Client design sketch

In `state.js`:

```js
const eventsSource = new EventSource('/api/events');

eventsSource.addEventListener('snapshot', (e) => {
  const payload = JSON.parse(e.data);
  state.session = payload.session;
  state.sessionSummaries = payload.sessions;
  state.devices = payload.devices;
  state.connection = payload.connection;
  render();
});

eventsSource.addEventListener('session-updated', (e) => {
  state.session = JSON.parse(e.data);
  renderInspectorRegion();
  renderPreviewOnly();
  updateComposerState();
});

eventsSource.addEventListener('sessions-updated', (e) => {
  state.sessionSummaries = JSON.parse(e.data);
  renderCurrentSessionList();
  renderSessionRegions();
});

// devices-updated, connection-updated, preview-ready ...
```

`refresh()`, `refreshSessions()`, and ETag polling become **fallback-only**:
they remain for the manual refresh button, SSE failure recovery, and the brief
window after a mutation when the client wants the response synchronously (e.g.
`sendAgentPrompt` needs `state.session` to update before it shows the success
toast). Lockstep refresh stays as redundant defense-in-depth.

`EventSource` handles reconnection automatically, but we should:
- Show a small "Reconnecting…" indicator if `eventsSource.readyState === CONNECTING` for >2 s.
- On `error` after exhausting reconnect attempts, fall back to a single explicit `refresh()` and try `EventSource` again.

### Migration phases

**Phase 0 — prep (no behavior change)**
- Add an `EventBus` abstraction in `fixthis-mcp` server: a per-connection `MutableSharedFlow<Event>` plus a top-level fan-out so multiple connections receive the same events.
- Add a hidden `?devEvents=1` query param on `/api/events` that returns a no-op stream — prove plumbing works without affecting production paths.

**Phase 1 — server emit, client subscribe (parallel pull retained)**
- Wire emit calls into `FeedbackSessionStore`, `DeviceService`, `ConnectionService`, `LivePreviewService`.
- Client opens `EventSource` on init; handlers update `state.*` and re-render.
- Pull paths (`refreshSessions` and ETag polling) stay. If both fire,
  last-writer-wins; ordering is ensured because mutations always wait for the
  server response (which has already emitted) before calling the local
  `refreshSessions`.
- Acceptance: the original 2026-05-10 bug stays fixed; manual `Cmd-R` no longer required after server-side async closes; multi-tab consoles stay in sync.

**Phase 2 — fold `livePreviewPolling` into SSE**
- Replace `setInterval`-based preview polling with `preview-ready` event subscription.
- Removes one whole subsystem (`livePreviewPolling`, `previewRequestGeneration`, `previewRequestContextGeneration`) — net code deletion.
- Acceptance: preview latency drops from poll-interval-bounded to push-bounded; preview no longer fights with explicit refresh paths.

**Phase 3 — retire pull paths**
- Remove `refreshSessions()` calls from happy-path mutation handlers, leaving only the manual refresh button and the SSE-failure fallback.
- `refreshSessions()` itself stays as the fallback implementation but becomes called rarely.
- Acceptance: server load drops (fewer `/api/sessions` + `/api/session` GETs per user action); UX feel unchanged.

**Phase 4 — observability**
- Log per-connection event count and reconnect rate.
- Surface `events_dropped` if ring buffer overflows during reconnect (would indicate a bug or extreme client lag).

### Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| SSE blocked by reverse proxy buffering | Send keep-alive comment frames every 15 s (`: keepalive\n\n`); document required nginx `proxy_buffering off;` if proxy is in front. |
| Client misses events during long disconnect | Ring buffer + `Last-Event-ID` replay; on overflow force a `snapshot` event. |
| Server emit point missed | Phase 1 retains pull paths as defense-in-depth; integration tests assert that mutating a session via API emits the corresponding event. |
| `EventSource` behavior on Safari/Firefox quirks | Console is also exposed via `claude.ai/code` and IDE extensions — those use Chromium. Native browser support for `EventSource` is universal; test on Safari for the mac-only console launches. |

### Test surface (what to add when implementing)

- Server: `FeedbackEventBusTest` — emit on every mutation entry point, ring-buffer overflow, multi-subscriber fan-out, last-event-id replay.
- Server: `FeedbackConsoleEventsEndpointTest` — actual SSE framing, keep-alive, reconnect with Last-Event-ID.
- Client: `console-events-test.mjs` — stub `EventSource`, verify `state.*` updates and re-render calls.
- Browser smoke: extend `console-browser-smoke.mjs` to assert that opening two tabs and mutating from one updates the other within ~500 ms.

### Estimated effort

- Server: ~200–300 LOC (event bus, endpoint, emit calls), ~150 LOC tests.
- Client: ~80–120 LOC (subscriber + fallback), ~60 LOC tests.
- Migration phases gated independently; can ship Phase 1 and stop if Phases 2–3 are not motivated.

---

## Decision log

| Date | Decision | Rationale |
| --- | --- | --- |
| 2026-05-10 | Ship lockstep refresh foundation | Fixes the observed sidebar/panel drift after local mutations with a small, low-risk change. |
| 2026-05-11 | Add ETag-conditional session polling | Covers passive agent/tab updates without blind polling churn; pauses around edits and mutations. |
| 2026-05-15 | Ship SSE Phase 1 | Added `/api/events`, ring replay, browser `EventSource` subscription, and route emit points for session, device, connection, and preview state. Polling remains as fallback. |
