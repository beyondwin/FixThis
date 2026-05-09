# MCP Handoff Workflow Completion + Sent History Removal

**Status**: Draft
**Owner**: kws
**Date**: 2026-05-10

## Summary

Remove the Sent History UI drawer, complete the agent handoff workflow so external agents (Claude Code / Codex) can claim and resolve feedback items via MCP, and reflect those status changes live in the browser console.

## Motivation

The current "Save to MCP" workflow is half-built. The button writes a `delivery=SENT` marker and a `handoffBatch` to disk, the session moves into a separate "Sent History" drawer, and the MCP exposes counters like `sentBatchesCount`. But nothing downstream consumes the handoff:

- `fixthis_read_feedback`'s Markdown output does not distinguish DRAFT vs SENT items.
- The `markdownSnapshot` written into each `FeedbackHandoffBatch` is never read by any tool — write-only dead data.
- No tool exists for an agent to mark "I am working on this item now".
- The browser console has no polling, so any change made by an external agent is invisible until the user takes some other action.

Result: users push the Save to MCP button as a session-archive gesture; the agent loop documented in `docs/mcp.md` does not actually close.

This work makes the loop real: external agent reads a focused queue, claims an item, works on it, resolves it; the user sees each transition reflected in the console within ~2 seconds.

## Goals

- Provide a single MCP tool surface that supports the full agent lifecycle: discover → claim → resolve.
- Remove the Sent History UI drawer; let the existing main History list and inspector status badges represent the same information.
- Make status changes from any source (user toggle, agent claim, agent resolve) appear in the browser console live, without user action.
- Preserve disk session JSON compatibility so existing `.fixthis/` data continues to load.
- Preserve external MCP client compatibility for fields that may already be observed (`sentBatchesCount`, `unresolvedSentItemsCount`, `handoffBatches`, status string `ready_for_agent`).

## Non-Goals

- Multi-agent contention. Single agent at a time is the assumed shape; the design is safe under contention but adds no UI for it.
- Server-Sent Events / WebSocket transport. Polling with ETag is sufficient at local-server scale.
- Migrating away from `delivery=SENT` or `READY_FOR_AGENT` enum values. Both remain in the schema for compatibility; future cleanup is out of scope.
- A "next item" auto-selection variant of claim. Item IDs are explicit.
- A bulk claim/resolve API. Agents loop one item at a time.

## Background — Current State

| Component | Today |
|---|---|
| `Save to MCP` button (`prompt.js:427`) | Calls `POST /api/agent-handoffs` with the prompt text |
| `/api/agent-handoffs` (`FeedbackItemRoutes.kt:55`) | Calls `service.sendDraftToAgent(sessionId, prompt)` |
| `sendDraftToAgent` (`FeedbackSessionStore.kt:194`) | DRAFT items → `delivery=SENT`, session `status=READY_FOR_AGENT`, new `FeedbackHandoffBatch` with `markdownSnapshot=prompt` |
| Inspector | Filters `delivery !== 'sent'` — SENT items hidden |
| Left History list | Filters `status !== 'ready_for_agent'` — sent sessions hidden |
| Sent History drawer | Re-shows sent sessions and unbatched items via `renderSentHistory()` |
| `fixthis_resolve_feedback` (`FixThisTools.kt:202`) | Accepts only RESOLVED, NEEDS_CLARIFICATION, WONT_FIX |
| IN_PROGRESS enum value | Defined in both `AnnotationStatus` (domain) and `AnnotationStatusDto`, but unused |
| `FeedbackQueueFormatter` Markdown | Does not branch on `delivery` or `status` |
| `markdownSnapshot` field on batch | Written by `sendDraftToAgent`, read by no one |
| Console polling | `preview.js` polls preview every 1s; sessions/items polled only on user action |

## Conceptual Model

The two existing axes on a feedback item are made explicit and independent:

- `delivery: DRAFT | SENT` — **user intent**. Did the user click Save to MCP for this item?
- `status: OPEN | IN_PROGRESS | RESOLVED | NEEDS_CLARIFICATION | WONT_FIX` — **work state**. Set by the user (inspector toggles) or by the agent (MCP tools).

All combinations are valid. The interesting ones:

| `delivery` | `status` | Meaning |
|---|---|---|
| DRAFT | OPEN | User wrote it, hasn't sent it anywhere yet |
| DRAFT | IN_PROGRESS | User used Copy Prompt; an agent claimed by itemId |
| DRAFT | RESOLVED | User used Copy Prompt; an agent finished it |
| SENT | OPEN | User clicked Save to MCP; no agent has claimed yet |
| SENT | IN_PROGRESS | Save to MCP route, agent working |
| SENT | RESOLVED | Save to MCP route, agent done |

The READY (status) and READY_FOR_AGENT (session status) values continue to be set by `sendDraftToAgent` for compatibility but no read path branches on them.

## Architecture

```
Console (browser)                MCP server (Kotlin)              External agent (Claude/Codex)
─────────────────                ──────────────────               ───────────────────────────────

[user clicks Save to MCP]
       │
       ▼ POST /api/agent-handoffs
                                 sendDraftToAgent:
                                   delivery DRAFT→SENT
                                   status →READY
                                   batch w/ markdownSnapshot
                                   session→READY_FOR_AGENT
                                   updatedAt=now            ──┐
                                                              │
[user clicks Copy Prompt]                                     │
       │ (clipboard text includes itemIds + agent_protocol footer)
       │ user pastes into agent
       │                                                      │
       ▼                                                      │
                                                              │     fixthis_list_feedback
                                                              │ ◄── (default: SENT and not finished)
                                                              │     fixthis_read_feedback
                                                              │ ◄── (itemId or default-filter)
                                                              │
                                                              │     fixthis_claim_feedback
                                                              │ ◄── itemId
                                  claimFeedback:              │
                                    status →IN_PROGRESS       │
                                    updatedAt=now             │
                                                              │
                                                              │     [agent does the code work]
                                                              │
                                                              │     fixthis_resolve_feedback
                                                              │ ◄── itemId, status, summary
                                  updateItemStatus:           │
                                    status →RESOLVED/...      │
                                    updatedAt=now             │
                                                              │
[browser polling tick every 2s]                               │
       │                                                      │
       ▼ GET /api/sessions, /api/session                      │
         (If-None-Match: <last-etag>)                         │
                                 304 if unchanged             │
                                 200 + body if changed       ◄┘
       │
       ▼ render diff into existing DOM
         (status badges, pip counts, fade highlight)
```

Two channels feed the agent (Save to MCP push vs. Copy Prompt pull). Both converge on `fixthis_claim_feedback({itemId})` followed by `fixthis_resolve_feedback({itemId, status, summary})`. The browser observes via polling. ETags keep idle traffic cheap.

## Data Model

No persisted schema changes. Read-side helpers are added.

### `FeedbackSessionSummary` (in-memory only, derived)

Add one field:

```kotlin
data class FeedbackSessionSummary(
    val sessionId: String,
    val updatedAtEpochMillis: Long,
    val status: SessionStatusDto,
    val itemsCount: Int = 0,
    val unresolvedItemsCount: Int = 0,
    val inProgressItemsCount: Int = 0,    // NEW: items with status == IN_PROGRESS
    val draftItemsCount: Int = 0,
    val sentBatchesCount: Int = 0,
    // ...
)
```

Computed in `from(session)` as `session.items.count { it.status == AnnotationStatusDto.IN_PROGRESS }`.

### Disk JSON

No new fields, no removed fields. Existing sessions load unchanged.

### `AnnotationStatusDto`

Already defines `IN_PROGRESS`. No enum change.

## MCP Tool Surface Changes

### New tool: `fixthis_claim_feedback`

```json
{
  "name": "fixthis_claim_feedback",
  "description": "Mark a feedback item as in-progress before starting work. Call AFTER reading the item and BEFORE making any code changes. Returns the updated item. The user's browser console reflects this within 2 seconds. After this call you must eventually call fixthis_resolve_feedback for the same itemId.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "sessionId": { "type": "string", "description": "Feedback session id. If omitted, the active session is used." },
      "itemId":    { "type": "string", "description": "Feedback item id to claim." },
      "agentNote": { "type": "string", "description": "Optional short note shown next to the item in the user's console." }
    },
    "required": ["itemId"]
  }
}
```

Server-side handler in `FixThisTools.kt`:

```kotlin
"fixthis_claim_feedback" -> bridgeToolResult {
    val session = requestedSession(arguments)
    val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
        ?: throw FixThisToolException("fixthis_claim_feedback requires itemId")
    val agentNote = arguments.stringParam("agentNote")
    val item = feedbackService.claimFeedback(session.sessionId, itemId, agentNote)
    jsonToolResult(McpProtocol.json.encodeToJsonElement(AnnotationDto.serializer(), item).jsonObject)
}
```

Service + store:

```kotlin
// FeedbackSessionService
fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto =
    feedbackDraftService.claimFeedback(sessionId, itemId, agentNote)

// FeedbackDraftService (or directly on store)
fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto =
    store.claimFeedback(sessionId, itemId, agentNote)

// FeedbackSessionStore
fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto =
    synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        var updatedItem: AnnotationDto? = null
        val updatedItems = session.items.map { item ->
            if (item.itemId == itemId) {
                if (item.status in resolvedStatusSet) {
                    throw FeedbackSessionException("ITEM_ALREADY_RESOLVED: Cannot claim resolved item: $itemId")
                }
                item.copy(
                    status = AnnotationStatusDto.IN_PROGRESS,
                    agentSummary = agentNote ?: item.agentSummary,
                    updatedAtEpochMillis = now,
                ).also { updatedItem = it }
            } else {
                item
            }
        }
        val item = updatedItem ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
        val updated = session.copy(items = updatedItems, updatedAtEpochMillis = now)
        save(updated)
        sessions[sessionId] = updated
        item
    }

private val resolvedStatusSet = setOf(
    AnnotationStatusDto.RESOLVED,
    AnnotationStatusDto.WONT_FIX,
)
```

### Modified: `fixthis_resolve_feedback`

Description tweak (mention claim-then-resolve pairing). No new arguments. **`updateItemStatus`'s allowed-status set is unchanged** — `RESOLVED`, `NEEDS_CLARIFICATION`, `WONT_FIX` only. IN_PROGRESS is reachable only via `claimFeedback`, which is a separate store method. This keeps each path single-purpose: claim moves an item into work, resolve moves it out.

### Modified: `fixthis_list_feedback`

Behavior change: `items[]` in the response defaults to a focused queue. Counters in the response are unchanged (computed against full set).

Add input:

```json
"includeAll": { "type": "boolean", "description": "If true, returns all items including DRAFT and finished. Default false." }
```

Server pseudocode:

```kotlin
val includeAll = arguments.booleanParam("includeAll") ?: false
val visibleItems = if (includeAll) session.items else session.items.filter {
    it.delivery == FeedbackDelivery.SENT && it.status !in resolvedStatusSet
}
// emit visibleItems in items[]; counters always over session.items
```

### Modified: `fixthis_read_feedback`

Same default filter as `fixthis_list_feedback`. If `itemId` is present, that item is always returned regardless of `delivery`/`status` (Copy Prompt path). `includeAll` flag added.

### CompactHandoffRenderer footer

Compact prompt (used by Copy Prompt and `fixthis_read_feedback` Markdown) gains:

1. An `id: <itemId>` token under each item block (verify whether v2 already emits one; if so, keep; if not, add).
2. A trailing block:

```
agent_protocol:
  before_work: fixthis_claim_feedback({sessionId, itemId})
  on_complete: fixthis_resolve_feedback({sessionId, itemId, status: resolved|wont_fix|needs_clarification, summary})
  user_console_reflects_within: 2s
session_id: <sessionId>
```

This makes the Copy Prompt route self-describing: an agent who only sees the pasted text can still address items by id and call MCP tools.

## Console Polling Mechanism

### Server-side ETag

`/api/sessions` and `/api/session` add `ETag` response headers and honor `If-None-Match`:

- `/api/sessions` ETag: `"<count>:<max(updatedAtEpochMillis across summaries)>"`
- `/api/session` ETag: `"<sessionId>:<updatedAtEpochMillis>"`

`If-None-Match` match → respond `304 Not Modified` with no body.

`com.sun.net.httpserver.HttpExchange` supports both via `responseHeaders.set("ETag", ...)` and `requestHeaders.getFirst("If-None-Match")`. No new dependency.

### Client-side polling loop

Mirror `preview.js`'s structure. Add to `state.js`:

```javascript
let sessionsPollingTimer = null;
let lastSessionsEtag = null;
let lastSessionEtag = null;
let pendingMutationCount = 0;

const SessionsPollIntervalMs = 2000;
```

In a new module `sessions-polling.js` (concatenated by `scripts/build-console-assets.mjs`):

```javascript
function shouldPollSessions() {
  return !document.hidden && pendingMutationCount === 0;
}

function startSessionsPolling() {
  stopSessionsPolling();
  sessionsPollingTimer = setInterval(() => {
    if (shouldPollSessions()) pollSessionsTick().catch(showError);
  }, SessionsPollIntervalMs);
}

function stopSessionsPolling() {
  if (sessionsPollingTimer) clearInterval(sessionsPollingTimer);
  sessionsPollingTimer = null;
}

async function pollSessionsTick() {
  const listResp = await fetch('/api/sessions', {
    headers: lastSessionsEtag ? { 'If-None-Match': lastSessionsEtag } : {}
  });
  if (listResp.status === 200) {
    lastSessionsEtag = listResp.headers.get('ETag');
    const data = await listResp.json();
    state.sessionSummaries = data.sessions || [];
    renderSessionsList();
  }

  if (state.session?.sessionId) {
    const resp = await fetch('/api/session', {
      headers: lastSessionEtag ? { 'If-None-Match': lastSessionEtag } : {}
    });
    if (resp.status === 200) {
      lastSessionEtag = resp.headers.get('ETag');
      const newSession = await resp.json();
      mergeSessionIntoState(newSession);
      renderInspectorRegion();
    }
  }
}
```

`mergeSessionIntoState` updates `state.session` while preserving:
- `comment` textarea value (if user is editing)
- focused item (`focusedPendingItemIndex`, `focusedSavedItemId`)
- pending in-flight items (`pendingFeedbackItems`)
- selection state (`currentSelection`)

Items whose `status` changed since last tick get a `data-just-changed` attribute briefly (800ms) for the visual highlight.

Wire-up in `main.js`:

```javascript
refresh()
  .then(...)
  .then(() => {
    startHeartbeatPolling();
    startLivePreviewPolling();
    startSessionsPolling();   // NEW
  })
  .catch(showError);
```

`document.visibilitychange` already fires `startLivePreviewPolling()`; add `startSessionsPolling()` too. Stop on errors > 5 consecutive (silent backoff with reconnect on visibility/manual action).

### Mutation counter

User-initiated mutations wrap their requests:

```javascript
async function withMutationLock(fn) {
  pendingMutationCount++;
  try { return await fn(); } finally { pendingMutationCount--; }
}
```

Apply in `prompt.js` (`sendAgentPrompt`, `copyPrompt`), `annotations.js` (status toggle, persist, delete), `history.js` (open/new/close/delete session). Prevents the polling tick from racing user requests.

### Visual highlight

```css
.ann-row.is-just-changed {
  background-color: var(--bg-success-soft);
  transition: background-color 800ms ease-out;
}
```

JS sets the class, `setTimeout(800)` removes it, CSS handles the fade.

## Console UI Changes

### Sent History drawer removal

Delete from `index.html` (lines 73–81):

```html
<details class="sent-history-drawer">…</details>
```

Delete from `styles.css`: `.sent-history-drawer`, `.sent-history-drawer summary`, `.sent-history-drawer .history-list`, `.sent-clear-button`, `.sent-clear-button svg` (~37 lines).

Delete from `state.js`: `sentHistory`, `clearSentHistoryButton` declarations.
Delete from `main.js`: `clearSentHistoryButton.addEventListener('click', …)`.
Delete from `history.js`: `sentHistorySummaries()`, `renderSentHistory()`, `clearSentHistory()`.
Delete from `rendering.js`: the `renderSentHistory()` call inside `renderSessionRegions()`.

### Inspector filter removal

`rendering.js:226` and `annotations.js:65`:

```diff
- return (state.session?.items || []).filter(item => item.delivery !== 'sent');
+ return (state.session?.items || []);
```

`preview.js:75` — remove the `.filter(item => item.delivery !== 'sent')` call inside `latestPersistedScreen()`. SENT items can also be the latest persisted screen.

### History list filter loosening

`history.js:205`:

```diff
- const activeSummaries = sessionSummaries.filter(session =>
-   session.status !== 'ready_for_agent' && session.status !== 'closed');
+ const activeSummaries = sessionSummaries.filter(session =>
+   session.status !== 'closed');
```

`history.js:138-145` `hasActiveHistorySessionForAnnotating` similarly drops the `ready_for_agent` check. Sessions with the historical READY_FOR_AGENT status remain visible.

### Pip representation

`history.js:209-225` renders three pips: open, done, points. Replace points with a working pip that hides at zero:

```javascript
const open      = historyOpenCount(session);    // OPEN + NEEDS_CLARIFICATION + pending unresolved
const working   = session.inProgressItemsCount; // NEW
const done      = historyDoneCount(session);    // RESOLVED + WONT_FIX
return [
  open    > 0 ? '<span class="hi-pip open">'    + open    + ' open</span>'    : '',
  working > 0 ? '<span class="hi-pip working">' + working + ' working</span>' : '',
  done    > 0 ? '<span class="hi-pip done">'    + done    + ' done</span>'    : '',
].join('');
```

Add `.hi-pip.working` style (a third color, e.g., amber).

The `points` pip is dropped. Total can be inferred from open+working+done; the explicit total was visual noise.

### Save to MCP toast

`prompt.js:455`:

```diff
- showSuccess('Saved to MCP ✓', 3000);
+ showSuccess('Saved to MCP ✓ — agent will pick up', 3000);
```

### "Clear sent history" button gone

The drawer's clear button is removed entirely. Per-session delete button (`×` in each row) already calls `/api/session/close`. No replacement needed.

## Error Handling & Concurrency

| Scenario | Resolution |
|---|---|
| User toggles status while agent updates same item | Last write wins. `synchronized(lock)` ensures atomicity. Polling reflects final state. |
| User attempts to delete a SENT item | Existing `deleteDraftItem` rejects with `ITEM_NOT_EDITABLE`. Hide the row's `×` button when `delivery === 'sent'`. |
| Agent claims an already-RESOLVED item | `ITEM_ALREADY_RESOLVED` error from store, surfaced as MCP error. |
| Agent re-claims an IN_PROGRESS item | Idempotent. `agentNote` is updated; no error. |
| User reopens a RESOLVED item via the inspector toggle | Allowed. Polling will reflect on the agent's next tool call. |
| Polling tick runs concurrently with user mutation | `pendingMutationCount > 0` skips the tick. |
| Polling network error | Skip tick. After 5 consecutive failures, pause polling silently and show "Reconnecting…" in connection card. Resume on visibilitychange or any user action. |
| 304 response | Treat as success, do not touch state. |
| Session deleted while agent calls a tool | `SESSION_NOT_FOUND` propagates; agent receives the error and stops working on that session. Compact prompt footer carries `session_id` so agent can target a specific id. |
| `delivery=SENT` item with missing `handoffBatchId` (legacy data) | Already handled in current code; counters are based on `delivery==SENT`, not on batch presence. |

## Migration & Backwards Compatibility

- **Disk JSON**: no schema change. Existing `READY_FOR_AGENT` session status, `delivery=SENT`, `handoffBatches`, `markdownSnapshot`, `handoffBatchId` all continue to be read and written.
- **MCP external clients**: `fixthis_list_feedback` / `fixthis_read_feedback` now default to a focused view. Clients depending on the old "all items" behavior must pass `includeAll: true`. Documented as a behavior change in `docs/mcp.md`.
- **Console**: serving static HTML/JS; users get the new UI on next browser refresh. Old browser tabs continue to work against the new server because the removed endpoints (`/api/sessions`, `/api/session`, `/api/agent-handoffs`) keep their existing response shapes (only adding ETag headers).
- **Phased rollout** (below) lets backend changes ship before UI changes, so a partial deploy never breaks a live console.

## Testing Strategy

TDD for every Phase. Concrete test additions:

### Backend

- `FeedbackSessionStoreTest`
  - `claimFeedback` happy path: DRAFT → IN_PROGRESS, SENT → IN_PROGRESS, sets `agentSummary`
  - `claimFeedback` idempotent on already-IN_PROGRESS
  - `claimFeedback` rejects RESOLVED with `ITEM_ALREADY_RESOLVED`
  - `claimFeedback` rejects unknown itemId with `Unknown feedback item`
  - `updateItemStatus` still rejects IN_PROGRESS (regression guard — claim is the only IN_PROGRESS path)
  - `updatedAtEpochMillis` advances on every mutation (regression guard for ETag)

- `FeedbackSessionServiceTest`
  - service-layer claim delegates to store

- `FixThisToolsTest`
  - `fixthis_claim_feedback` schema present with required `itemId`
  - call returns `AnnotationDto` JSON; missing itemId → 400-style error
  - `fixthis_resolve_feedback` description mentions claim pairing
  - `fixthis_list_feedback` default response excludes DRAFT and resolved items, counters intact
  - `fixthis_list_feedback` with `includeAll: true` returns all items
  - `fixthis_read_feedback` default filtered; itemId always returns regardless of delivery/status

- `McpProtocolTest`
  - `tools/list` includes `fixthis_claim_feedback`
  - description text contains "before starting work" guidance

- `CompactHandoffRendererTest` / `FeedbackQueueFormatterTest`
  - rendered Markdown contains `id: <itemId>` for each item
  - Markdown ends with `agent_protocol:` block including `before_work`, `on_complete`, `session_id`

- `FeedbackSessionPersistenceTest`
  - existing fixture with `READY_FOR_AGENT` + `delivery=SENT` + `handoffBatches` decodes unchanged

### Console / HTTP

- `FeedbackConsoleServerTest`
  - `/api/sessions` and `/api/session` responses include `ETag` header
  - `If-None-Match` matching → 304 with empty body
  - mutation (e.g., add item) advances the ETag
  - `inProgressItemsCount` field present on session summaries
  - HTML no longer contains `class="sent-history-drawer"`, `id="clearSentHistoryButton"`, `id="sentHistory"`
  - HTML no longer contains the `'ready_for_agent'` filter literal in `renderSessionsListFromPayload`
  - HTML no longer contains the `delivery !== 'sent'` filter (any of the four sites)
  - HTML contains `function startSessionsPolling`, `pendingMutationCount`, `mergeSessionIntoState`
  - HTML contains the `.hi-pip.working` CSS rule

### Tests to remove (intentionally)

- `FeedbackConsoleServerTest:734, 782-785, 1043, 1602, 1619` — assertions that the drawer/button/ID exist. Delete in the same commit that removes the UI.
- `FeedbackSessionStoreTest:383` `clearDraftItemsKeepsSentHistory` — keep; backend behavior unchanged.
- `McpProtocolTest:612`, `FeedbackQueueFormatterTest:278` — `assertFalse(content.contains("Sent History"))` already guard string absence; keep.

### Manual / E2E (no harness change)

- Open console, click Save to MCP, observe that the session stays in the History list with a `working` pip after triggering an external `fixthis_claim_feedback` call.
- Trigger `fixthis_resolve_feedback` from a separate process; observe the inspector badge transitioning within ~2s and the row briefly highlighting.
- Hide the browser tab; confirm polling pauses (no network requests in DevTools). Reveal; confirm polling resumes immediately.

## Phased Rollout Order

Each phase is independently shippable to main; new backend never depends on new frontend.

### Phase A — Backend, agent loop closes

A1. Add `FeedbackSessionStore.claimFeedback` + tests (DRAFT/SENT → IN_PROGRESS, idempotent on IN_PROGRESS, rejects RESOLVED).
A2. Add `FeedbackSessionService.claimFeedback` (and draft-service if used) delegate + tests.
A3. Register `fixthis_claim_feedback` tool definition and handler in `FixThisTools` + tests.
A4. Update `fixthis_resolve_feedback` description (claim-then-resolve pairing). No API change; allowed statuses unchanged.
A5. Add `includeAll` parameter and default-filter behavior to `fixthis_list_feedback` and `fixthis_read_feedback` + tests.
A6. Extend `CompactHandoffRenderer` with per-item `id:` token and `agent_protocol:` footer + tests.

### Phase B — Polling infrastructure

B1. Add `inProgressItemsCount` to `FeedbackSessionSummary` + tests.
B2. Add ETag emission and `If-None-Match` handling on `/api/sessions`, `/api/session` + tests.
B3. (If needed) tweak `FeedbackConsoleHttpException` / response helpers to support empty 304 bodies cleanly.

### Phase C — Console UI removal + polling client

C1. Remove Sent History drawer (HTML, CSS, JS DOM refs, listeners, render functions) and update `FeedbackConsoleServerTest` accordingly.
C2. Remove `delivery !== 'sent'` filter from `rendering.js`, `annotations.js`, `preview.js`.
C3. Remove `ready_for_agent` filter from `history.js` (two sites).
C4. Replace points pip with `working` pip; add CSS.
C5. Add new `sessions-polling.js` module; concatenate via `scripts/build-console-assets.mjs`. Implement `startSessionsPolling`, `pollSessionsTick`, `mergeSessionIntoState`.
C6. Introduce `pendingMutationCount` and apply `withMutationLock` around mutating fetches in `prompt.js`, `annotations.js`, `history.js`.
C7. Add `is-just-changed` highlight CSS + JS triggers in `mergeSessionIntoState`.

### Phase D — Build + docs

D1. Run `node scripts/build-console-assets.mjs`.
D2. Run `./gradlew :fixthis-mcp:installDist` (and `:fixthis-cli:installDist` if affected).
D3. Update `docs/mcp.md`: remove Sent History references, document `fixthis_claim_feedback`, document filter change with `includeAll` workaround.
D4. Update `docs/design-feedback-console-ux.md` and `docs/troubleshooting.md` "Send Agent" wording to match the new lifecycle.
D5. Add CHANGELOG entry summarizing the workflow completion and UI removal.

## Risks & Mitigation

| Risk | Mitigation |
|---|---|
| Polling tick clobbers an actively-edited textarea | `mergeSessionIntoState` preserves `comment` textarea, focused item, selection. Covered by HTML test asserting these fields are read before merge. |
| External MCP client relied on default-all `fixthis_list_feedback` | Document as breaking; provide `includeAll: true` opt-in. |
| ETag mis-calculation lets a stale 304 hide a real change | Use `updatedAtEpochMillis` which is updated on every store mutation. Regression test: every mutation advances the value. |
| Multi-agent contention | Out of scope but safe under `synchronized(lock)`. Last write wins. |
| User loses access to "clear all sent" bulk action | YAGNI; per-session delete is one click per row. Reintroduce only if a user reports needing it. |
| Polling drains laptop battery | 2s interval, ETag 304 keeps payload near zero, paused on hidden tab. Comparable cost to the existing 1s preview polling. |
| `is-just-changed` flash is distracting | 800ms ease-out fade. If reported as noise, gate behind a setting or remove; trivial to revert. |
| Console UI tests assert on removed strings | Delete those assertions in the same commit as the UI removal. Listed explicitly in Testing Strategy. |

## Rollback

Each Phase is git-revertable independently:

- Revert Phase C → console regains drawer; backend (Phase A/B) is harmless.
- Revert Phase A → external agents lose claim/resolve workflow; user-facing console keeps working with the original Save-to-MCP-as-archive behavior.
- Revert Phase B → ETag goes away; client polling falls back to always-200 (still functional, slightly more bandwidth).

## Open Questions / Future Work

- Should `markdownSnapshot` and `READY_FOR_AGENT` be deprecated and removed in a follow-up once disk migration tooling exists? Out of scope here; flagged for a future cleanup.
- Should the agent be able to attach a richer "fix summary" with file diffs? Today `summary` is plain text. Could become structured later.
- A "stale IN_PROGRESS" indicator (e.g., row dimmed if `IN_PROGRESS` for > 30 min with no further updates) would help recover from agents that crash mid-task. Not required for v1.
- Bulk operations (claim/resolve multiple) — defer until a clear use case appears.
