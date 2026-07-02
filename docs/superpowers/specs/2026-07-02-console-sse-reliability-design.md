# Console SSE Reliability Design

## Summary

FixThis should treat `/api/events` as the primary browser-console state sync
path and keep pull-based refreshes as explicit fallback or user-commanded
recovery. The current implementation already prevents steady-state session and
preview polling while EventSource is healthy, but the remaining SSE cleanup is
spread across comments, reliability tests, and defensive refresh calls.

This design adds lightweight SSE diagnostics and narrows happy-path pull
refreshes without removing the disconnected fallback path. The goal is to make
console sync behavior easier to prove, debug, and maintain while preserving the
local single-user recovery model.

## Goals

- Keep `/api/events` as the normal path for session, session-summary, device,
  connection, and preview updates.
- Preserve fallback polling for EventSource errors, disconnected browsers,
  manual refresh, and replay-overflow recovery.
- Add a small browser-side SSE diagnostics model for connect, disconnect,
  reconnect, replay-overflow, and fallback reason tracking.
- Reduce direct `refreshSessions()` use after healthy-path mutations when the
  server response or SSE event already provides authoritative state.
- Extend reliability evidence so healthy SSE paths show zero steady-state
  `/api/session`, `/api/sessions`, and `/api/preview` pull fetches.
- Keep docs aligned around "SSE primary, fallback retained, diagnostics prove
  the boundary."

## Non-Goals

- Do not remove fallback preview or session polling.
- Do not replace SSE with WebSocket.
- Do not change MCP tool signatures, persisted feedback-session JSON, compact
  handoff output, bridge protocol, or source-matching contracts.
- Do not make diagnostics part of `.fixthis/` persisted data.
- Do not broadly migrate console rendering to selectors or remove all legacy
  `state.*` projection in this pass.
- Do not change server-side session storage semantics.

## Current State

`docs/architecture/console-state-sync-design.md` describes SSE Phase 1 and
preview push as shipped. Its remaining work is to retire happy-path pull calls
and add observability. The current code reflects that state:

- `fixthis-mcp/src/main/console/sse.js` tracks whether EventSource is connected
  and gates fallback polling through `shouldUsePreviewFallbackPolling()` and
  `shouldUseSessionFallbackPolling()`.
- `fixthis-mcp/src/main/console/events.js` opens `/api/events`, applies
  snapshots and update events, starts fallback polling on EventSource error,
  and runs `refresh()` on replay overflow.
- `fixthis-mcp/src/main/console/sessions-polling.js` and
  `fixthis-mcp/src/main/console/preview.js` return immediately while SSE is
  healthy.
- `scripts/console-browser-reliability.mjs` already proves zero steady-state
  session and preview polling under a healthy SSE window.
- Some direct `refreshSessions()` calls remain in session lifecycle code, which
  is correct for manual refresh and explicit session navigation but too broad
  for healthy mutation aftermath.

The important boundary is that fallback polling is still a live recovery
feature. The improvement should make the healthy path quieter and the recovery
path more observable, not delete recovery code prematurely.

## Architecture

The browser console state flow is:

```text
server mutation
  -> ConsoleEventBus emit
  -> /api/events EventSource frame
  -> events.js handler
  -> state/session/preview update
  -> render
```

Pull refreshes remain allowed only for:

- explicit manual refresh,
- initial load and direct user session navigation,
- EventSource disconnected fallback,
- replay-overflow recovery,
- routes whose immediate response is the authoritative state required before
  the next UI action.

Everything else should use `refreshSessionsWhenEventsDisconnected()` or local
server response data so healthy SSE does not create redundant pull traffic.

## Components

### `sse.js`

Keep `consoleEventsConnected` as the source of truth for fallback gates, and add
a browser-local diagnostics object. Suggested fields:

- `connectCount`
- `disconnectCount`
- `reconnectCount`
- `lastConnectedAt`
- `lastDisconnectedAt`
- `replayOverflowCount`
- `lastFallbackReason`

Expose this through focused helpers:

- `setConsoleEventsConnected(connected, options)`
- `recordConsoleEventsOverflow()`
- `consoleEventsDiagnostics()`

The diagnostics object is not persisted and does not affect compatibility
contracts. It exists for tests, future debug UI, and local reliability reports.

### `events.js`

Update diagnostics at the existing EventSource boundaries:

- `onopen`: mark connected, stop preview/session fallback polling, and count
  reconnects when this is not the first successful open.
- `onerror`: mark disconnected, record `eventsource_error`, and re-arm preview
  and session fallback polling exactly as today.
- `replay-overflow`: record overflow and allow `refresh()` because this is an
  explicit recovery path, not healthy-path polling.

Session and preview event fences stay unchanged: stale active-session events do
not mutate visible session detail or preview.

### `history.js`, `annotations.js`, `prompt.js`

Keep direct `refreshSessions()` in explicit navigation and manual refresh
flows:

- `refresh()`
- opening a selected history session,
- creating a new session,
- closing or deleting a session when the visible list must be authoritative
  before the next render,
- starting annotation when no active session exists and the browser must open
  one immediately.

Prefer `refreshSessionsWhenEventsDisconnected()` or direct response data for
healthy mutation aftermath:

- saved item edit/delete aftermath,
- batch draft persistence aftermath,
- Copy Prompt and Save to MCP aftermath,
- session-summary-only updates that already arrive over `sessions-updated`.

This preserves synchronous user-visible transitions while eliminating redundant
pulls from healthy EventSource mutation paths.

### Tests And Docs

Update:

- `scripts/console-browser-reliability.mjs`
- `scripts/studioReliabilityContract-test.mjs`
- `scripts/consoleEvents-test.mjs` if needed for the diagnostics helper shape
- `docs/reference/feedback-console-contract.md`
- `docs/architecture/console-state-sync-design.md`

Docs must describe the same contract as tests: healthy SSE performs no
steady-state pull polling; disconnected fallback and replay-overflow recovery
remain intentional.

## Data Flow

Healthy EventSource:

```text
mutation response returns
  -> event already emitted or response contains mutated state
  -> browser applies response/event
  -> refreshSessionsWhenEventsDisconnected() returns current summaries
  -> no /api/session or /api/sessions pull fetch
```

Preview push:

```text
LivePreviewService captures frame
  -> preview-ready event with sessionId
  -> applyLivePreview(source=sse)
  -> no steady-state /api/preview poll fetch
```

Disconnected fallback:

```text
EventSource error
  -> diagnostics record eventsource_error
  -> consoleEventsConnected=false
  -> startLivePreviewPolling()
  -> startSessionsPolling()
  -> pull refreshes resume until EventSource opens again
```

Replay overflow:

```text
replay-overflow event
  -> diagnostics replayOverflowCount += 1
  -> refresh()
  -> browser rebuilds state from authoritative HTTP endpoints
```

## Error Handling

- EventSource errors are normal transport failures, not console defects.
- Fallback polling must remain quiet on transient failures and continue using
  the existing paused/reconnecting affordance.
- Replay overflow is a recovery event. A pull refresh is allowed and should be
  counted separately from healthy-path polling.
- Diagnostics must be best-effort. If a debug consumer is missing, state sync
  must continue normally.
- Stale `sessionId` events stay fenced and must not clobber the active session
  or preview.

## Testing

Focused verification:

```bash
node --test scripts/studioReliabilityContract-test.mjs scripts/consoleEvents-test.mjs
npm run console:browser:reliability
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected coverage:

- `sse.js` diagnostics increment on connect, error, reconnect, and overflow.
- Healthy SSE keeps fallback gates closed.
- Healthy preview push performs zero steady-state `/api/preview` pull fetches.
- Healthy Save to MCP and session-summary updates perform zero extra
  `/api/session` or `/api/sessions` pull fetches.
- EventSource disconnect re-arms fallback polling.
- Reconnect increments diagnostics and resumes SSE-driven updates.
- Replay overflow permits exactly the documented recovery refresh path.

## Compatibility

This design is additive for diagnostics and subtractive only for redundant
browser pull calls. It does not alter:

- MCP tools,
- bridge protocol,
- feedback-session persisted JSON,
- compact prompt grammar,
- source candidate or target evidence schemas,
- Android runtime behavior.

The only externally visible change should be a quieter browser/server request
profile under healthy EventSource sessions and clearer local evidence when SSE
falls back.

## Implementation Boundaries

The first implementation pass should stop after diagnostics, targeted
happy-path pull cleanup, tests, and docs. It should not start the larger
console selector migration. If a direct `refreshSessions()` call is ambiguous,
keep it and document why rather than risking a stale visible session transition.
