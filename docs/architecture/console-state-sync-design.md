# Console State Sync

**Status:** SSE Phase 1 shipped. ETag-conditional session polling remains as
fallback.
**Owners:** `fixthis-mcp` (server + console JS)

## What the browser keeps

| Piece | Source | Drives |
| --- | --- | --- |
| `state.sessionSummaries` | SSE `snapshot` / `sessions-updated`, or fallback `GET /api/sessions` | History rows, working pips |
| `state.session` | `GET /api/session` and mutating session routes | Inspector, overlay, Copy Prompt / Save to MCP |

Those two used to drift when a mutation refreshed summaries but not the
active session.

## Current design

`/api/events` streams server state. On connect the server sends a `snapshot`
with the active session, summaries, devices, and connection. Later changes
emit `session-updated`, `sessions-updated`, `devices-updated`,
`connection-updated`, or `preview-ready`.

Session and preview events carry top-level `sessionId`. The browser ignores
them unless they match the active session. `Last-Event-ID` replays from a
256-event ring buffer. `replay-overflow` means do a full refresh.

Fallback polling still fetches `/api/sessions` and `/api/session` in
lockstep while the event stream is down. Healthy EventSource sessions do not
rely on automatic preview or session polling.

Shared apply path: SSE and polling write the same session/preview reducers.

## Rules

- The initial `snapshot` is authoritative.
- Do not apply another session’s preview or item events.
- Keep fallback polling until local evidence shows it is unused.
- Browser DTO changes must match route tests and
  [console contract](../reference/feedback-console-contract.md).
