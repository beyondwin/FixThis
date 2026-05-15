# Handoff Prompt Parity — Design

**Date:** 2026-05-10
**Status:** Approved (pending implementation)
**Owner:** kws
**Related plan:** `docs/superpowers/plans/2026-05-10-handoff-prompt-parity.md`

---

## 1. Problem

The browser console produces handoff prompt text via JS in `fixthis-mcp/src/main/console/prompt.js` (`currentAnnotationsPromptCompact()` and ~500 LoC of helpers). The MCP server has a parallel Kotlin implementation in `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` used by `fixthis_read_feedback`. Despite copy-paste origins, the two have **drifted**.

Concretely, the JS renderer is missing — relative to Kotlin:

| Field | JS | Kotlin |
|-------|----|----|
| Per-item `id: <itemId>` | ✗ | ✓ |
| Trailer `session_id: <sid>` | ✗ | ✓ |
| `agent_protocol:` block (`fixthis_claim_feedback({sessionId,itemId})` / `fixthis_resolve_feedback({sessionId,itemId,status,summary})`) | ✗ | ✓ |
| Per-item `crop: <desktopCropPath>` | ✗ | ✓ |
| `⚠ stale: <reason>` candidate suffix (added by recent staleness follow-up) | ✗ | ✓ |
| `---` separator / footer | ✗ | ✓ |

**User-visible failure mode** (observed 2026-05-10): a user clicks **Copy Prompt** in the browser, pastes the result into Claude Code (or any agent), and the agent has no `itemId` or `sessionId` to call `fixthis_claim_feedback` / `fixthis_resolve_feedback`. The status transition machinery (DRAFT → SENT → IN_PROGRESS → RESOLVED) breaks at the IN_PROGRESS hand-off — the user's console never updates from "Sent" to "In progress" / "Resolved".

The same prompt is also used by **Save to MCP** (`#sendAgentButton` in `index.html:44-47`), which currently sends the JS-rendered string to `/api/agent-handoffs` for persistence. Agents that read via `fixthis_read_feedback` get the Kotlin renderer's complete output, so they are unaffected — but the persisted "prompt" snapshot in the handoff record carries the lossy JS variant.

### 1.1 Why two renderers exist

Original architecture: the browser was the only producer of the prompt; it composed the markdown locally and either copied it to clipboard or POSTed it as a `prompt` body to `/api/agent-handoffs`. Later, Kotlin needed to produce the same prompt for `fixthis_read_feedback` (and now richer fields like `agent_protocol:`). The two implementations evolved independently.

### 1.2 Why this is more than a missing-field bug

Three earlier features added Kotlin-only fields without porting them to JS. Without architectural change, every future field becomes a parity diff. The cost of catching divergence after the fact (this incident) is high: the user pastes a stale prompt, the agent silently fails to claim, the workflow appears broken without an obvious cause.

## 2. Goals

- The **clipboard** copy of the handoff prompt and the **server-stored** handoff prompt include `id:`, `session_id:`, `agent_protocol:`, `crop:`, and `⚠ stale:` — i.e., everything `fixthis_read_feedback` already emits.
- One renderer (Kotlin `CompactHandoffRenderer`) is the single source of truth for handoff markdown. The JS renderer (`currentAnnotationsPromptCompact` and helpers) is removed.
- All clipboard / handoff click flows route through the server.
- Existing `fixthis_read_feedback` output and DRAFT/SENT state machine are unchanged.
- Future fields require touching exactly one file (`CompactHandoffRenderer.kt`) and one test.

## 3. Non-goals

- Polling → SSE / WebSocket migration in `sessions-polling.js`. Tracked for a separate plan.
- Auto-promote DRAFT items the user wrote but never sent (current intentional silent-skip behavior in `fixthis_list_feedback`).
- New prompt format / field names. The output format stays as Kotlin currently emits, with two cosmetic adjustments (see §4.3) to match what JS users currently see.
- Cross-process race hardening on `FeedbackSessionStore.commitSessionMutation` (currently in-process `synchronized(lock)` only).
- Changes to `fixthis_read_feedback`, `fixthis_claim_feedback`, `fixthis_resolve_feedback` semantics.

## 4. Architecture

```
                         BROWSER                                  SERVER
                                                          ┌──────────────────┐
  [Copy Prompt] click                                     │                  │
       │                                                  │                  │
       │  persistPendingFeedbackItems()  ──── POST ───►   │  /api/items/batch│
       │                                                  │                  │
       │  ◄─── 200 { session: {... new itemIds ...} } ──  │                  │
       │                                                  │                  │
       │  POST /api/sessions/{sid}/handoff-preview        │                  │
       │       body: { itemIds: [...] }                   │                  │
       │  ───────────────────────────────────────────►   │  CompactHandoff  │
       │                                                  │  Renderer.render │
       │  ◄─── 200 text/markdown ────────────────────────│                  │
       │                                                  │                  │
       │  copyTextToClipboard(markdown)                   │                  │
                                                          │                  │
  [Save to MCP] click                                     │                  │
       │                                                  │                  │
       │  persistPendingFeedbackItems()  ──── POST ───►   │  /api/items/batch│
       │  ◄─── 200 { session } ──────────────────────────│                  │
       │                                                  │                  │
       │  POST /api/agent-handoffs                        │                  │
       │       body: { itemIds: [...] }   ◄── changed ──  │                  │
       │  ───────────────────────────────────────────►   │  sendDraftToAgent│
       │                                                  │  + render        │
       │                                                  │  + persist       │
       │  ◄─── 200 { session, prompt } ──────────────────│                  │
                                                          └──────────────────┘
```

### 4.1 New endpoint: `POST /api/sessions/{sessionId}/handoff-preview`

- **Request body** (`application/json`):
  ```json
  { "itemIds": ["<uuid>", "<uuid>", ...] }
  ```
- **Response** (`text/markdown; charset=utf-8`): the rendered handoff prompt for the supplied items (any items not in the session are dropped silently — the server already does this in `CompactHandoffRenderer.render`).
- **Errors:**
  - `400` — empty `itemIds` array, or body is not valid JSON. Body: `{"error":"<reason>"}`.
  - `404` — `sessionId` is unknown. Body: `{"error":"session not found"}`.
- **Idempotent.** No state change. May be called repeatedly.
- **Auth:** none (consistent with other `/api/*` routes — `FeedbackConsoleServer` is localhost-only).

### 4.2 Modified endpoint: `POST /api/agent-handoffs`

- **Request body change:**
  ```diff
  - { "prompt": "<rendered markdown from JS>" }
  + { "itemIds": ["<uuid>", ...] }
  ```
- **Server flow:**
  1. Validate `itemIds` non-empty; resolve session.
  2. Build prompt via `CompactHandoffRenderer.render(session, itemIds)`.
  3. Call existing `feedbackService.sendDraftToAgent(sessionId, itemIds, prompt)` — flips matching DRAFT items to SENT, persists handoff record with rendered prompt.
  4. Return `{ session: <SessionDto>, prompt: <string> }` (response now also exposes `prompt` for clients that want to display / log it; existing JS uses only `session`).
- **Backward compatibility:** the previous body-shape `{ "prompt": "..." }` is **rejected** with `400` (no transitional period — there is exactly one client). This avoids carrying a deprecated path that nothing exercises.

### 4.3 Kotlin renderer — cosmetic adjustments

Two small changes inside `CompactHandoffRenderer.kt` to align output exactly with the format users currently see in the JS clipboard (zero user-facing format break):

1. **Screenshot path fallback.** When emitting `screenshot:`, prefer `screen.screenshot.desktopFullPath`, fall back to `screen.screenshot.fullPath`. Today Kotlin emits the `screenshot:` line only when `desktopFullPath != null`; JS always emits it.
2. **Overlap header trailing blank line removal.** After `Overlap group N (resolve one marker at a time):`, do not emit a blank line; immediately follow with the first `[N] title`. Matches the JS shape.

Everything else (per-item layout, candidate block, instance grouping, duplicate markers, source-root computation, REASON_TOKEN_MAP, `%.2f` margin formatting, max-3 candidates, max-4 matched tokens) is already byte-equivalent.

### 4.4 JS — code removal and rewrite

**Removed** (deleted, not deprecated):
- `currentAnnotationsPromptCompact`
- `currentAnnotationsPrompt`
- `compactItemLines`
- `compactScreenHeader`
- `compactCandidatesBlock`
- `compactUiLine`
- `compactDetectOverlap`
- `computeInstanceLabels`
- `computeDuplicateMarkers`
- `compactSourceRoot` / `computeSourceRoot`
- `promptItemTitle`
- All shared helpers used only by the above (token map, instance grouping, duplicate detection — confirm during implementation that no other caller depends on each helper before deletion)

Net deletion: ~500 LoC from `prompt.js` (and the bundled `app.js`).

**Rewritten** (small, single-purpose):

```js
async function copyPrompt() {
  if (promptActionInFlight) return;
  await withMutationLock(async () => {
    error.textContent = '';
    promptActionInFlight = true;
    updateComposerState();
    try {
      const itemIds = await persistAndCollectItemIds();
      const markdown = await fetchHandoffPreview(state.session.sessionId, itemIds);
      await copyTextToClipboard(markdown);
      flashCopied();
    } finally {
      promptActionInFlight = false;
      updateComposerState();
    }
  });
}

async function sendAgentPrompt() {
  if (promptActionInFlight) return;
  await withMutationLock(async () => {
    error.textContent = '';
    promptActionInFlight = true;
    updateComposerState();
    let sent = false;
    try {
      const itemIds = await persistAndCollectItemIds();
      const result = await requestJson('/api/agent-handoffs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ itemIds })
      });
      state.session = result.session;
      comment.value = '';
      resetAnnotationComposerState();
      invalidatePreviewContext();
      await refreshSessions();
      render();
      startLivePreviewPolling();
      sent = true;
    } finally {
      promptActionInFlight = false;
      updateComposerState();
      if (sent) showSuccess('Saved to MCP ✓ — agent will pick up', 3000);
    }
  });
}
```

`persistAndCollectItemIds()` and `fetchHandoffPreview(sid, itemIds)` are new tiny helpers (~20 LoC combined).

`persistAndCollectItemIds()` uses the existing `currentPromptAnnotations()` filter (`delivery !== 'sent' && hasWrittenAnnotationComment`) to determine which composer items to persist, runs `persistPendingFeedbackItems({ onlyWrittenComments: true })`, then maps the freshly-persisted item identities to itemIds from the response session. Selection semantics identical to today.

## 5. Components

| Layer | File | Change kind |
|-------|------|-------------|
| Server route | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` (or routing module nearby) | Add `POST /api/sessions/{sid}/handoff-preview`; modify `POST /api/agent-handoffs` body shape and response |
| Server service | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` (`sendDraftToAgent` already exists) | Caller change only — `/api/agent-handoffs` now passes a server-rendered prompt instead of one supplied by the client |
| Renderer | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` | Two cosmetic adjustments per §4.3 |
| Browser composer | `fixthis-mcp/src/main/console/prompt.js` | Delete ~500 LoC of helpers; rewrite `copyPrompt` and `sendAgentPrompt`; add `persistAndCollectItemIds` + `fetchHandoffPreview` helpers |
| Bundled output | `fixthis-mcp/src/main/resources/console/app.js` | Rebundle via `node scripts/build-console-assets.mjs` |
| Tests (Kotlin) | `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` | New tests for §4.3 (screenshot fallback; no blank line after overlap header) |
| Tests (server) | `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` | New tests: `/api/sessions/{sid}/handoff-preview` happy path + 400/404; `/api/agent-handoffs` new shape; old shape rejected with 400 |
| Tests (JS) | `fixthis-mcp/src/test/console/prompt.test.mjs` (new) | Unit-test rewritten `copyPrompt` / `sendAgentPrompt` against `fetch`-mocked server (or document why a JS test harness is intentionally deferred — see §6.1) |

## 6. Testing strategy

### 6.1 JS test harness

The console JS modules currently have **no JavaScript test runner** — coverage is via Kotlin integration tests through `FeedbackConsoleServerTest`. This plan respects that existing convention:

- **Primary coverage:** Kotlin integration tests exercise the new endpoint by reading actual server output, decoupled from any browser.
- **Manual verification:** the smoke flow described in `docs/troubleshooting.md` ensures the round-trip works end-to-end on a connected device.
- **JS unit tests deferred** unless the rewrite introduces non-trivial logic. The rewritten `copyPrompt` / `sendAgentPrompt` are thin orchestrators (≤30 LoC each) over already-tested helpers (`requestJson`, `withMutationLock`, `persistPendingFeedbackItems`). Adding a JS test runner solely for these two functions exceeds the value.

If a future change introduces non-trivial JS logic, that change should add a JS test runner as part of its plan.

### 6.2 Acceptance tests

Each acceptance test below is a unit or integration test that fails before the change and passes after.

1. **Clipboard prompt contains itemId, session_id, agent_protocol.** Server integration test: POST `/api/sessions/{sid}/handoff-preview` with one itemId; assert response body contains `id: <itemId>`, `session_id: <sid>`, `agent_protocol:`.
2. **Save to MCP rejects old body shape.** POST `/api/agent-handoffs` with `{prompt: "..."}` returns 400.
3. **Save to MCP accepts new body shape.** POST `/api/agent-handoffs` with `{itemIds: [...]}` returns 200; response includes `session` and `prompt`; matching DRAFT items are flipped to SENT.
4. **Empty itemIds → 400.** POST `/api/sessions/{sid}/handoff-preview` with `{itemIds: []}` returns 400.
5. **Unknown sessionId → 404.** POST `/api/sessions/<random>/handoff-preview` returns 404.
6. **Screenshot fallback.** Render a session whose screen has `fullPath` but no `desktopFullPath`; output contains a `screenshot: <fullPath>` line.
7. **No blank line after Overlap header.** Render a session whose screen has overlapping markers; `Overlap group N` is followed directly by `[N] title` with no intervening empty line.
8. **`fixthis_read_feedback` output unchanged.** Existing `fixthis_read_feedback` integration tests continue to pass without modification (regression bound: the cosmetic changes in §4.3 are also visible to `fixthis_read_feedback`, so existing tests are updated to match the same two cosmetic adjustments — these are the only places existing tests change).
9. **Full 5-module regression.** Current main baseline `tests=631 failures=0`. Expected post-change: `tests=636+ failures=0` (5 new server tests + 2 new renderer tests, minus any tests deleted alongside removed JS helpers — none expected since JS has no tests today).

### 6.3 Manual verification

- Build `:fixthis-cli:installDist :fixthis-mcp:installDist`, launch console with `fixthis console --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"` (per `CLAUDE.md`).
- Add an annotation; click **Copy Prompt**; paste into a scratch buffer; verify the text contains `id:`, `session_id:`, `agent_protocol:`.
- Click **Save to MCP**; in another terminal call `fixthis_read_feedback` for that itemId; verify identical structural output.
- (Optional) on a connected device run `./scripts/fixthis-smoke.sh --check-staleness` to ensure the parent feature is unaffected.

## 7. Compatibility

| Surface | Risk | Outcome |
|---|---|---|
| `fixthis_read_feedback` (Kotlin renderer) | Two cosmetic changes (§4.3) | Existing tests updated to match. Agent-side substring matching on `id:` / `session_id:` continues to pass. Substring matching on the previously-extra blank line could fail; no agents observed to do this. |
| `/api/agent-handoffs` body shape | Hard break (`prompt` no longer accepted) | Sole client is `prompt.js` which is updated in the same change. No external integrations on this endpoint exist (it is documented only as an internal localhost route). |
| `/api/items/batch`, `/api/sessions`, `/api/session` | Unchanged | None |
| Persisted JSON field names (`itemId`, `sessionId`, `screenId`, `delivery`, `status`) | Unchanged | None |
| DRAFT / SENT / IN_PROGRESS / RESOLVED state machine | Unchanged | None |
| Bridge protocol | Unchanged | None |

No version bump needed. The single coupled rollout is one PR (or one merge into main) where Kotlin server + JS console change together.

## 8. Risks introduced by this change

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| HTTP round-trip on every Copy Prompt / Save to MCP click | certain | low (localhost <50ms) | Acceptable. UI shows "Copying…" / "Saving…" states already exist for the persist step; the new fetch is comparable. |
| Server unreachable mid-click | low | medium (clipboard not written) | Existing `requestJson` surfaces fetch errors via `error.textContent` and `showError`. The clipboard is left untouched on failure (matches today's behavior on `persistPendingFeedbackItems` failure). |
| Stale `state.session` — itemIds the JS believes exist but the server has GC'd | very low | low (404) | Returned 404 surfaces clear error; user refreshes session list. |
| Removed JS helpers turn out to have a non-prompt caller | low | low (build break) | Implementation gate: grep for each helper name across `console/` and `resources/console/` before deletion; abort deletion if a non-prompt caller surfaces. |
| Two cosmetic Kotlin changes break a currently-passing test | low | low | Existing tests updated in the same PR. |
| Kotlin renderer error (e.g., NPE on a malformed session) breaks Copy Prompt where JS would have rendered partial output | low | medium (no clipboard) | Server returns 500 with error body; JS surfaces it via `showError`. Tests cover the malformed-session case (§6.2 #4–5). |

## 9. Future work (intentionally deferred)

- **Polling → SSE/WS** for `sessions-polling.js` — separate plan.
- **JS test runner** if non-trivial JS logic is added later.
- **Schema versioning on `/api/sessions/{sid}/handoff-preview`** — current design returns plain markdown; if multiple prompt variants emerge (e.g., precise / full mode in browser), re-add a `mode` query parameter or move to a JSON envelope.

## 10. Open questions

1. Should `/api/sessions/{sid}/handoff-preview` accept a `detailMode` (compact / precise / full) parameter today, anticipating future variants? — **Decision:** no. Browser composer only renders compact today; adding the parameter now is YAGNI. Re-add when a second variant ships.
2. Should the rejected old `/api/agent-handoffs` body shape return a deprecation message hint (e.g., `400 {"error":"prompt body is no longer accepted; pass {itemIds:[...]} instead"}`)? — **Decision:** yes; the cost is negligible and helps any out-of-tree script that may have been mirroring the body shape.
3. Should `crop:` and `⚠ stale:` be hidden when the prompt is shown in the browser preview, but kept in the clipboard? — **Decision:** no. Single source of truth is the goal; if the browser ever previews the prompt inline, it shows the same text the agent sees.
