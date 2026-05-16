# v0.3 First-Run Trust Program — Follow-up Design

Date: 2026-05-17
Status: Planned for implementation
Parent: [2026-05-16-v03-first-run-trust-program-design.md](2026-05-16-v03-first-run-trust-program-design.md)

## Goal

Close the six items the v0.3 First-Run Trust Program intentionally deferred:
the five **Edge Case Coverage Map → Deferred** rows and the pre-existing
`console:smoke` failure at `previewStaleBadge` transition (line 768).

The v0.3 readiness contract and NotificationCenter policy layer landed in
the merge commit `826277b4`. Each item below now has a single wiring step
(or one small new state) because that contract is in place.

## Non-Goals

- New readiness states beyond `CAPTURE_UNAVAILABLE` (screenshot 404 /
  semantics-only).
- Server-side conflict resolution beyond optimistic etags
  (`If-Match` + `412 Precondition Failed`). Merge UX presents the user with
  a choice; no automatic three-way merge.
- Changes to the Android sidekick bridge protocol.
- Sessions polling cadence changes (SESSION_MISMATCH ignore is purely
  client-side filtering of late responses).
- Refactor of `console-browser-smoke.mjs` beyond the minimum needed to
  unblock line 768.

## Success Criteria

- `requestJson` rejection with `action === 'reload_console'` causes a single
  banner notification with a primary action that reloads the console window.
- `copyTextToClipboard` failure surfaces via `NotificationCenter` (not
  legacy `showError`) and tells the user to select the prompt and copy it
  manually.
- A new readiness state `CAPTURE_UNAVAILABLE` is emitted from server and
  rendered by console when the screenshot endpoint returns 404 or the
  capture has no rasterized preview (semantics-only).
- Late SSE / preview-poll responses whose `sessionId` no longer matches the
  active session are ignored (logged, not surfaced); user sees only the
  current session's data.
- Saving a draft against a stale snapshot (server already advanced) returns
  HTTP 412; console opens a boundary dialog with three options (use server,
  keep local + force save, cancel).
- `npm run console:smoke` passes end to end — line 768 `previewStaleBadge`
  becomes visible after `OPEN_APP` transition.

## Design

### 1. 403 reload CTA consumer (`fixthis-mcp/src/main/console/api.js`,
   `fixthis-mcp/src/main/console/state.js`)

Task 4 of v0.3 made `requestJson` throw `ConsoleRequestError` with optional
`.action` and `.details`. Today the catch-all `showError(err)` collapses
this to a toast. Add at every `.catch(showError)` site relevant to network
calls: branch on `err instanceof ConsoleRequestError && err.action ===
'reload_console'`, fire

```js
notificationCenter.notify({
  severity: 'error',
  surface: 'banner',
  dedupeKey: 'reload_console_403',
  title: 'Reload required',
  detail: 'Your session token expired or origin changed.',
  primaryAction: { label: 'Reload console', onSelect: () => window.location.reload() },
});
```

Only one site needs to fire — keep the helper in `state.js` named
`surfaceReloadConsoleNotice(err)` and call it before falling back to
`showError`.

### 2. Clipboard fallback → NotificationCenter
   (`fixthis-mcp/src/main/console/clipboard.js` or wherever
   `copyTextToClipboard` is defined; `prompt.js` / `main.js` callers)

Locate every `copyTextToClipboard(...).catch(showError)`. Replace with:

```js
.catch((err) => notificationCenter.notify({
  severity: 'warning',
  surface: 'banner',
  dedupeKey: 'clipboard_fallback',
  title: 'Copy failed',
  detail: 'Select the prompt and copy it manually.',
}));
```

Remove the now-unused `showError(err)` import if it was only for this path.

### 3. Screenshot 404 / semantics-only capture classification
   (server: `ConsolePreviewService.kt`, `ConsoleHttp.kt`,
   `FirstRunReadiness.kt`; client: `connection.js` or `preview.js`)

Add a new `FirstRunReadinessState`: `CAPTURE_UNAVAILABLE`. Canned entry:

```kotlin
fun captureUnavailable(cause: String, details: Map<String, String> = emptyMap()) =
  FirstRunReadiness(
    state = FirstRunReadinessState.CAPTURE_UNAVAILABLE,
    cause = cause,
    verify = "Open the app foreground and tap Capture, or open doctor for the bridge log.",
    primaryAction = "Retry capture",
    details = details,
  )
```

Server: when `/api/preview` or `/api/screenshot` returns 404 or the capture
contains only semantics with no image bytes, attach
`readiness = captureUnavailable(...)` to the response body (alongside the
existing `state` field) and return HTTP 200 with `payload.previewAvailable
= false`. Existing happy path remains unchanged.

Client: when `payload.previewAvailable === false`, show the readiness via
the existing connection-card readiness slot (the same surface
`/api/connection` populated in v0.3 task 2). Capture button text becomes
"Retry capture".

### 4. Late SSE / SESSION_MISMATCH ignore path
   (`fixthis-mcp/src/main/console/sse.js`,
   `fixthis-mcp/src/main/console/previewPoll.js`)

Both consumers receive responses asynchronously. Add a filter at message
receipt:

```js
if (msg.sessionId && msg.sessionId !== state.session?.sessionId) {
  console.warn('[sse] dropping stale response for session', msg.sessionId);
  return;
}
```

For `previewPoll.js`, the same gate applies to the JSON response of the
poll. Add a unit test for each consumer that asserts a mismatched
`sessionId` is dropped silently (no state mutation, no notification).

### 5. Older save / newer edits conflict policy
   (`fixthis-mcp/src/main/kotlin/.../session/DraftSaveService.kt`,
   `fixthis-mcp/src/main/console/draftSync.js`, new boundary variant)

Server: each save response includes `ETag: "<monotonic-rev>"`. The next save
must include `If-Match: "<previous-rev>"`. Server compares; mismatch → HTTP
412 with body
`{state: 'STALE_PREVIEW', readiness: ..., serverDraft: <current draft snapshot>}`.

Client: on 412, open boundary dialog `staleDraftConflict` (new variant)
with three buttons:
- Primary: "Keep mine (overwrite)" → resend save with `If-Match: *`
- Secondary: "Use server's version" → discard local, load `serverDraft`
- Cancel

No automatic merge. Local pending pins are preserved on Cancel.

### 6. `console:smoke` previewStaleBadge fix
   (`fixthis-mcp/src/main/console/connection.js` or
   `scripts/console-browser-smoke.mjs`)

Root cause: at the OPEN_APP transition, `markPreviewStale(true)` is gated
by `(draftItemList().length || state.preview)`. By the time the smoke clicks
`#refreshDevicesButton`, the preview reference may have been cleared by an
earlier state transition or `state.preview` may not be populated. Two
possible fixes (Implementer to pick the simpler):

- Drop the gate when `viewState !== 'ready'`: any draft surface seen so far
  qualifies (use `hasEverConnected` as the trigger instead).
- OR, if the smoke expectation is wrong, change the smoke to seed
  `state.preview` before the OPEN_APP click, then the gate naturally passes.

Reviewer should evaluate which path keeps production behavior correct
without weakening the assertion.

## Edge Cases (extension to v0.3 map)

| Edge case | Classification (post-followup) | UX surface (post-followup) | Covered by |
| --- | --- | --- | --- |
| 403 token/origin | ✅ (already) | ✅ banner + reload CTA | Task 0 |
| clipboard failure | ✅ (already) | ✅ banner with manual-copy detail | Task 1 |
| screenshot 404 / semantics-only | ✅ `CAPTURE_UNAVAILABLE` | ✅ readiness slot + retry | Task 2 |
| late SSE / cross-session | n/a (logical) | n/a (silent drop) | Task 3 |
| older save / newer edits | ✅ 412 + STALE_PREVIEW | ✅ staleDraftConflict variant | Task 4 |
| smoke OPEN_APP → stale badge | n/a | ✅ smoke passes line 768 | Task 5 |
