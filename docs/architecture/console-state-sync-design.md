# Console State Sync - SSE Phase 1 + Fallback Plan

**Status:** SSE Phase 1 shipped; ETag-conditional session polling remains as
the fallback path.
**Owners:** `fixthis-mcp` (FeedbackConsoleServer + console JS)
**Related code:** `fixthis-mcp/src/main/console/history.js`
(`refreshSessions`, `refresh`), `events.js`, `sessions-polling.js`,
`state.js`, `livePreviewPolling`, `ConsoleEventRoutes.kt`,
`ConsoleEventBus.kt`, and `SessionRoutes.kt`

---

## Background

The console keeps two pieces of session state on the client:

| Piece | Source | Drives |
| --- | --- | --- |
| `state.sessionSummaries` | SSE `snapshot` / `sessions-updated.summary`, or fallback `GET /api/sessions` | Sidebar History rows, working pips, session ordinals |
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

## Current design - SSE plus lockstep fallback

**Change 1:** `/api/events` streams server state over Server-Sent Events.
On connect, the server sends a `snapshot` containing the active session,
session summaries, devices, and connection status. Later server-side changes
emit `session-updated`, `sessions-updated`, `devices-updated`,
`connection-updated`, or `preview-ready`. Events that can mutate visible
session detail or preview state carry top-level `sessionId`; the browser
ignores them when they do not match the active session. The endpoint supports
`Last-Event-ID` replay from a 256-event ring buffer and emits
`replay-overflow` when the client needs a full refresh.

**Change 2:** `refreshSessions()` fetches `/api/sessions` and `/api/session`
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

**Change 3:** `sessions-polling.js` keeps the same two resources current only
while the event stream is unavailable or recovering. `SessionRoutes.kt`
returns ETags for `/api/sessions` and `/api/session`; the browser sends
`If-None-Match`, treats 304 as "no change", and only re-renders when the
server state changed.

Fallback polling runs every 2 seconds while the page is visible and
EventSource is disconnected. It pauses while the tab is hidden, while a local
mutation is in flight, and while saved-item text is being edited so the server
refresh cannot clobber user input. After five consecutive polling failures it
pauses and surfaces a "Reconnecting feedback updates..." state on the
connection card. Any successful mutating action or tab visibility restore can
restart the loop when fallback polling is active.

### Coverage - what the current design fixes

SSE is now the primary passive-sync path. Existing mutation call sites still
invoke `refreshSessions()` after a server mutation as a synchronous fallback:

- `applySavedSessionUpdate` (item edit/delete) — `annotations.js:436`
- `persistPendingFeedbackItems` (batch save) — `annotations.js:511`
- `sendAgentPrompt` (handoff) — `prompt.js:489`
- `openSession`, `newSession`, `closeSession`, `deleteHistorySession` — `history.js`
- `refresh()` (initial load + manual refresh button)

EventSource keeps another tab or agent claim/resolve visible without waiting
for the polling interval. Lockstep refresh keeps the panel/toolbar in sync
with the sidebar immediately after local mutations, and conditional polling
covers browsers without EventSource or temporary SSE failures without
re-render churn when the ETag is unchanged. Session-scoped event application
also prevents stale async events from session A replacing session B's detail
pane or preview after History navigation.

### Residual gap - what Phase 1 does NOT fix

The design is now push-first, but a few fallback gaps remain:

1. **Fallback polling still exists, but is not steady state.** `/api/events`
   is the normal preview and session update path. Preview and session polling
   restart when EventSource is disconnected, unavailable, or explicitly
   recovering.
2. **Fallback polling can still pause.** A hidden tab, an active edit, an
   in-flight mutation, or five consecutive polling failures intentionally stops
   the fallback loop.
3. **Manual disk edits.** A developer editing `session.json` by hand can still
   produce state the browser only learns about after the next successful
   refresh or emitted event.
4. **Multi-tab ordering during reconnect.** The SSE stream has monotonic event
   ids, but clients that reconnect after ring-buffer overflow fall back to a
   snapshot and may skip intermediate visual transitions.

For a single-user local dev tool, these are acceptable and recoverable by any
successful interaction or manual refresh. The remaining work is mostly removal
of redundant pull paths, not a new state-sync foundation.

---

## SSE migration - why and what remains

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
- Subscription filters (e.g. "only send events for session X"). Console only
  has one active session at a time; broadcasting all session events to the one
  connected client is cheap because browser handlers fence visible state
  updates by the payload `sessionId`.

### Shipped SSE Server

Endpoint:

```
GET /api/events         (text/event-stream)
```

Behavior:
- Long-lived response. Server holds the connection open and writes SSE frames.
- Each frame: `event: <name>\ndata: <json>\nid: <monotonic-int>\n\n`.
- On connect, server sends a `snapshot` event with the current `{ session, sessions, devices, connection }` payload.
- On subsequent server-side state changes, server emits one of:
  - `session-updated` — payload: `{ sessionId, session }`, where `session`
    is the mutated `SessionDto`.
  - `sessions-updated` — payload: `{ sessionId, summary }` for a changed
    session, or a full summary list when supplied by snapshot/fallback refresh
    paths.
  - `preview-ready` — payload: `{ sessionId, preview }`.
  - `devices-updated`, `connection-updated` — fold in existing polling
    concerns.
- `id:` is a monotonic counter assigned per-event; client stores last-seen id.
- Standard SSE auto-reconnect: browser sends `Last-Event-ID: <n>` header on reconnect; server replays any events with `id > n` from a small ring buffer (~256 events) before resuming live stream. Events older than the ring force a `snapshot` event instead.

Server emit points:
- `FeedbackSessionStore` mutation methods (item add/edit/delete, batch save, status change, handoff, close, open)
- `LivePreviewService` when a new preview frame is ready
- `DeviceService` when device list or selection changes
- `ConnectionService` when connection state changes

Concurrency: `ConsoleEventBus` keeps a process-local 256-event ring and a
copy-on-write subscriber list. The `/api/events` route writes keep-alive
comments every 15 seconds while the connection is open.

### Shipped Client Subscriber

In `events.js`:

```js
const eventsSource = new EventSource('/api/events');

eventsSource.addEventListener('snapshot', (e) => {
  const payload = JSON.parse(e.data);
  if ('session' in payload) setConsoleSession(payload.session || null);
  if (payload.sessions?.sessions) renderSessionsListFromPayload(payload.sessions.sessions);
  if (payload.devices) renderDeviceList(payload.devices);
  if (payload.connection) applyConnectionStatus(payload.connection);
  render();
});

eventsSource.addEventListener('session-updated', (e) => {
  const payload = JSON.parse(e.data);
  if (!payload.session) return;
  if (state.session?.sessionId && payload.sessionId !== state.session.sessionId) {
    refreshSessions().catch(showError);
    return;
  }
  setConsoleSession(payload.session);
  loadPendingRecoveryForCurrentSession();
  render();
});

eventsSource.addEventListener('sessions-updated', (e) => {
  const payload = JSON.parse(e.data);
  if (payload.summary) applySessionSummaryFromPayload(payload.summary);
  else if (payload.sessions?.sessions) renderSessionsListFromPayload(payload.sessions.sessions);
  else refreshSessionsWhenEventsDisconnected().catch(showError);
});

eventsSource.addEventListener('preview-ready', (e) => {
  const payload = JSON.parse(e.data);
  if (!payload.preview || draftFlow()) return;
  if (payload.sessionId !== state.session?.sessionId) return;
  setConsolePreview({
    ...payload.preview,
    activity: state.connection?.availability?.activity ?? null,
    frozenAtEpochMillis: Date.now(),
    stale: false,
  });
  renderPreviewOnly();
});

// devices-updated, connection-updated ...
```

`refresh()`, `refreshSessions()`, and ETag polling are now fallback-oriented:
they remain for the manual refresh button, SSE failure recovery, and the brief
window after a mutation when the client wants the response synchronously (e.g.
`sendAgentPrompt` needs `state.session` to update before it shows the success
toast). Lockstep refresh stays as redundant defense-in-depth.

`EventSource` handles reconnection automatically. The current error handler
marks session polling paused so the existing "Reconnecting feedback updates..."
surface remains the visible fallback indicator.

### Migration phases

**Phase 0 — prep (shipped)**
- Add `ConsoleEventBus` in `fixthis-mcp` with a process-local ring buffer,
  monotonic event ids, and subscriber fan-out.
- Add `ConsoleEventRoutes` for `/api/events`, including initial snapshot,
  replay, overflow notification, and keep-alive comments.

**Phase 1 — server emit, client subscribe (shipped, parallel pull retained)**
- Wire emit calls into `FeedbackSessionStore`, `DeviceService`, `ConnectionService`, `LivePreviewService`.
- Client opens `EventSource` on init; handlers update `state.*` and re-render.
- `session-updated` and `preview-ready` payloads carry top-level `sessionId`;
  mismatched session events refresh summaries without clobbering the active
  detail pane, and mismatched preview events are ignored.
- Pull paths (`refreshSessions` and ETag polling) stay. If both fire,
  last-writer-wins; ordering is ensured because mutations always wait for the
  server response (which has already emitted) before calling the local
  `refreshSessions`.
- Acceptance: the original 2026-05-10 bug stays fixed; manual `Cmd-R` no longer required after server-side async closes; multi-tab consoles stay in sync.

**Phase 2 — fold `livePreviewPolling` into SSE (shipped)**
- `preview-ready` SSE events are the normal automatic preview update path.
- `livePreviewPolling` remains only as a fallback adapter while EventSource is disconnected or unavailable.
- Manual refresh and explicit recovery paths still call `/api/preview` directly.
- Acceptance: connected consoles receive preview updates through SSE without waiting for the polling interval, while disconnected consoles still recover through fallback polling.

**Phase 3 — retire happy-path pull calls (current hardening line)**
- Keep `refreshSessions()` for manual refresh, initial load, explicit session
  navigation, and replay-overflow recovery.
- Route healthy mutation aftermath through response data,
  `sessions-updated` events, or `refreshSessionsWhenEventsDisconnected()`.
- Acceptance: under healthy EventSource, Save to MCP, preview push, and
  session-summary updates perform zero steady-state `/api/session`,
  `/api/sessions`, and `/api/preview` pull fetches.

**Phase 4 — observability (current hardening line)**
- Track browser-local connect, disconnect, reconnect, replay-overflow, and last
  fallback reason diagnostics.
- Expose diagnostics through the debug object for tests and local reports only.
- Acceptance: browser reliability proof observes reconnect diagnostics after an
  EventSource drop and replay-overflow diagnostics before the documented
  recovery refresh.

### Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| SSE blocked by reverse proxy buffering | Send keep-alive comment frames every 15 s (`: keepalive\n\n`); document required nginx `proxy_buffering off;` if proxy is in front. |
| Client misses events during long disconnect | Ring buffer + `Last-Event-ID` replay; on overflow force a `snapshot` event. |
| Server emit point missed | Phase 1 retains pull paths as defense-in-depth; route and event-bus tests cover framing, replay, and overflow. Add mutation-level emit assertions when retiring pull paths. |
| `EventSource` behavior on Safari/Firefox quirks | Console is also exposed via `claude.ai/code` and IDE extensions — those use Chromium. Native browser support for `EventSource` is universal; test on Safari for the mac-only console launches. |

### Test surface

- Server: `ConsoleEventBusTest` covers monotonic ids, ring replay, and overflow.
- Server: `ConsoleEventsRoutesTest` covers initial snapshot, SSE framing,
  replay behavior, and top-level `sessionId` on `preview-ready`.
- Server: `ConsoleFeedbackItemRoutesTest` verifies legacy explicit-session
  item creation emits the mutated request session.
- Client: `consoleEvents-test.mjs` verifies that the browser subscriber opens
  `/api/events`, handles expected event names, is started during console
  initialization, and fences `session-updated` / `preview-ready` by active
  session.
- Browser follow-up: extend `console-browser-smoke.mjs` to assert that opening two tabs and mutating from one updates the other within ~500 ms.

### Remaining effort

- Fold `livePreviewPolling` into `preview-ready` events.
- Remove happy-path `refreshSessions()` calls once SSE and fallback coverage are strong enough.
- Add lightweight event-stream observability before relying on SSE as the only passive sync path.

## Transport And Session Resilience Contract

The console treats browser request cancellation as a normal transport event. Server routes must route response writes through `ConsoleHttp` helpers so `connection reset`, `broken pipe`, closed streams, and fixed-length close errors are closed quietly rather than logged as server defects.

The active session is the ownership boundary for preview state. Any server-driven current-session change, user session switch, device switch, or draft context reset must invalidate `state.preview` through `clearPreview()`. Async preview completions may update UI only when both the captured `sessionId` and preview `contextGeneration` still match the current console state.

SSE `/api/events` must compute its initial snapshot before sending streaming headers. After headers are committed, keep-alive write failures caused by client disconnect close the subscription quietly; server-side snapshot failures remain normal JSON errors before the stream begins.

---

## Decision log

| Date | Decision | Rationale |
| --- | --- | --- |
| 2026-05-10 | Ship lockstep refresh foundation | Fixes the observed sidebar/panel drift after local mutations with a small, low-risk change. |
| 2026-05-11 | Add ETag-conditional session polling | Covers passive agent/tab updates without blind polling churn; pauses around edits and mutations. |
| 2026-05-15 | Ship SSE Phase 1 | Added `/api/events`, ring replay, browser `EventSource` subscription, and route emit points for session, device, connection, and preview state. Polling remains as fallback. |
| 2026-05-15 | Fence session and preview SSE events by active session | Prevents stale async updates from replacing the visible session detail or preview after explicit session CRUD or History navigation. |
| 2026-05-15 | Treat browser disconnects as normal transport and snapshot SSE before headers | Prevents client cancellations from looking like server defects while keeping initial snapshot failures recoverable as ordinary HTTP errors. |
