# MCP Handoff Workflow — Follow-up Hardening

**Status**: Draft
**Owner**: kws
**Date**: 2026-05-10
**Related work**: `docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md` (now merged as `27645a4`)

## Summary

The MCP handoff workflow shipped in commit `27645a4` introduces ETag polling, a per-item agent claim/resolve loop, and removes the Sent History drawer. Three follow-up risks were identified during the post-merge audit:

1. **Polling tick has no failure backoff or "Reconnecting…" UI** — the implementation diverged from the original spec which called for a 5-failure pause + connection card message. As written, every failed `/api/sessions` or `/api/session` poll triggers `showError` once per tick (every 2 seconds) until the user takes action.
2. **The original plan/spec for T21 (`delivery !== 'sent'` filter removal) describes work that no longer matches the codebase** — re-running the plan would re-trigger the SPEC_BLOCKER escalation that was hand-resolved during execution.
3. **`mergeSessionIntoState` triggers per-item highlight transitions with no upper bound** — for agent-driven bulk transitions or first-load polling ticks, the user sees N simultaneous fades instead of a single visual signal that "things changed".

These are not blocking for shipping the merged work but would surface as bug reports under realistic agent traffic. This spec defines the smallest hardening that closes them.

## Motivation

**Risk 1 (polling backoff)** — Without backoff, a backend restart, network outage, or transient 500 produces a continuous error stream in the user's console. The original spec explicitly anticipated this:

> Polling network error | Skip tick. After 5 consecutive failures, pause polling silently and show "Reconnecting…" in connection card. Resume on visibilitychange or any user action.

The shipped `pollSessionsTick` does `.catch(showError)` only — no counter, no pause, no UI message. This is a documented deviation, not an edge case.

**Risk 2 (plan/spec drift)** — During T21 execution, the implementer correctly identified that the plan's instructions targeted three filter sites but the codebase already had `annotations.js` refactored into `toolbarAnnotations()` (no filter, used by display) and `currentPromptAnnotations()` (filters sent, used by send/copy buttons). Removing the latter filter would have broken the send-once invariant. The orchestrator narrowed the scope to `preview.js:75` only and execution succeeded. The plan and spec documents were never updated to reflect this; a future re-run from the same artifacts would hit the same escalation.

**Risk 3 (highlight burst)** — `mergeSessionIntoState` queues a `requestAnimationFrame` + 800ms `setTimeout` per status-changed item. The original spec acknowledged this as removable noise:

> `is-just-changed` flash is distracting | 800ms ease-out fade. If reported as noise, gate behind a setting or remove; trivial to revert.

Realistic agent traffic produces bursts (Save to MCP triggers 5–10 items into SENT, agent claims them in succession, polling tick observes them as a batch). With no upper bound, a 10-item burst paints 10 simultaneous CSS transitions — visually noisy and a contradiction of the "this changed since you last looked" UX intent.

## Goals

- Polling silently absorbs up to 5 consecutive failures, then pauses and surfaces a single "Reconnecting…" message on the existing connection card. Auto-resume on `visibilitychange` (tab becoming visible) or any user-initiated mutation.
- Plan and spec documents for the May 2026 handoff workflow are amended in-place so a future re-run (or an external reader) sees the corrected T21 scope without needing to re-derive it from git history.
- `mergeSessionIntoState` recognizes "bulk change" tick (≥6 items changed since the last tick) and skips the highlight effect for that tick. Sub-bulk ticks continue to highlight normally.
- No persisted schema or MCP tool surface change; no new HTTP endpoint; no impact on `:fixthis-mcp:test` baseline beyond the new assertions added by this work.

## Non-Goals

- Adding exponential backoff to polling intervals. The original spec and this follow-up keep the 2-second interval; only the post-failure pause is added.
- A configurable highlight threshold. The literal `> 5` is the chosen heuristic; if users report it as wrong, follow-up work can make it tunable.
- Refactoring the connection state machine in `connection.js`. The follow-up adds one new state branch ("session_polling_paused") to the existing dataset attribute — does not redesign `userConnectionState(status)`.
- Server-side change. All three follow-ups are client-side only.

## Background — Current State

### #1 — Polling failure handling

`fixthis-mcp/src/main/console/sessions-polling.js`:

```javascript
function startSessionsPolling() {
  stopSessionsPolling();
  sessionsPollingTimer = setInterval(() => {
    if (shouldPollSessions()) pollSessionsTick().catch(showError);
  }, SessionsPollIntervalMs);
}

async function pollSessionsTick() {
  // ... fetches /api/sessions and /api/session
}
```

`showError` is the project-wide error surface used for all unexpected failures. It writes to a transient toast / error region. There is no counter, no pause, no "Reconnecting" affordance.

`fixthis-mcp/src/main/console/connection.js:127-144` (`renderConnection(status)`) already supports `viewState === 'reconnect'` with a "Reconnect" headline and "The connection was interrupted. Your work is saved." message. This UI exists for bridge / device disconnection. It is NOT currently triggered by sessions polling failures.

### #2 — T21 plan/spec drift

The plan's Task 21 (`docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md` lines 1522–1599) instructs:

```diff
- return (state.session?.items || []).filter(item => item.delivery !== 'sent');
+ return (state.session?.items || []);
```

at three sites (`rendering.js:226`, `annotations.js:65`, `preview.js:75`).

Actual codebase state at the time of execution:
- `rendering.js`: filter ALREADY REMOVED in pre-existing main work
- `annotations.js:80`: filter inside `currentPromptAnnotations()` — the SEND/COPY path, not the display path
- `preview.js:75`: filter inside `latestPersistedScreen()` — display path

The corrected scope (only `preview.js:75` modified) is what shipped in commit `ba31262`. The plan and spec still describe the original 3-site scope.

### #3 — Highlight queueing

`fixthis-mcp/src/main/console/rendering.js:639-655`:

```javascript
const previousStatusById = new Map(
  (previous?.items || []).map(item => [item.itemId, item.status])
);
(fresh.items || []).forEach(item => {
  const before = previousStatusById.get(item.itemId);
  if (before && before !== item.status) {
    requestAnimationFrame(() => {
      document.querySelectorAll(`[data-item-id="${CSS.escape(item.itemId)}"]`).forEach(node => {
        node.setAttribute('data-just-changed', 'true');
        setTimeout(() => node.removeAttribute('data-just-changed'), 800);
      });
    });
  }
});
```

No batch detection, no upper bound, no opt-out.

## Conceptual Model

### Polling failure state machine

Three states, observable via `state.connection.sessionsPollingState` (new):
- `'live'` — polling normally; no recent failures
- `'recent_failure'` — 1–4 consecutive failures; polling continues, errors swallowed silently (no toast)
- `'paused'` — 5+ consecutive failures; polling timer stopped; connection card shows "Reconnecting…"

Recovery transitions:
- Any successful poll → `'live'` (reset failure counter)
- Tab becomes visible (`visibilitychange` with `!document.hidden`) AND state is `'paused'` → restart timer
- User-initiated mutation succeeds (existing `withMutationLock` path) AND state is `'paused'` → restart timer
- All three recovery paths re-enter `'live'` only after the next successful poll

The `'paused'` state surfaces in connection card as a SEPARATE branch from the existing `'reconnect'` branch — sessions polling failure is not the same as bridge/device disconnection. The existing `connectionCard.dataset.connectionState` already supports multiple values; we add `'session_polling_paused'` (or reuse `'reconnect'` if simpler — see Decision below).

### Highlight burst suppression

A "bulk change" is defined as ≥6 distinct itemIds whose status changed since the previous polling tick. When detected, `mergeSessionIntoState` skips the highlight loop entirely for that tick. Single-item updates (the typical agent claim/resolve case) continue to highlight.

The threshold of 6 is chosen because:
- A user toggling 1–5 items in quick succession should still see highlights (active interaction).
- An agent picking up a 10-item Save-to-MCP batch produces ≥6 status transitions in the next tick → bulk-mode, no highlight.
- A reconnect-after-pause polling tick may pull in many delta updates accumulated during the pause → bulk-mode, no overwhelming flash.

### T21 plan/spec correction

A standalone "Correction note (2026-05-10 follow-up)" appended to Task 21 in the plan, plus a corresponding note in the spec section that describes the filter removal. Original instructions kept verbatim so reviewers can see the historical intent; correction explicitly states which sites were actually modified.

## Architecture

### #1 — Polling backoff + Reconnecting UI

```
state.js (additions)
  let consecutivePollFailures = 0;
  // Optional: a small named constant export for the threshold.

sessions-polling.js (modifications)
  - pollSessionsTick wrapped in try/catch; on success → reset counter + ensure state is 'live';
    on failure → increment counter; if >=5 → enter 'paused' state.
  - startSessionsPolling clears the failure counter and entry state.
  - Stop polling timer when entering 'paused'.

connection.js (additions)
  - renderConnection branches on a new boolean `state.connection.sessionsPollingPaused`
    (or string state) to overlay a "Reconnecting feedback updates…" sub-line on the existing
    connection card without disturbing the bridge/device state.
  - Helper setSessionsPollingPaused(paused: boolean) toggles the flag and re-renders.

main.js (modifications)
  - visibilitychange handler: when !document.hidden and sessionsPollingPaused → restart.
  - Existing withMutationLock callers are unchanged; the polling module's mutation-lock
    integration already pauses ticks during user mutations. We add: if mutation completes
    AND state is 'paused', call restart.
```

### #2 — Plan/spec correction

Two file edits, no code changes.

`docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md` — append a clearly-marked note at the END of Task 21:

```markdown
> **Correction note (2026-05-10 follow-up)**: This task's instructions
> were written against an earlier code state. At execution time, the
> orchestrator narrowed scope to ONLY `preview.js:75` (`latestPersistedScreen`)
> because:
>
> - `rendering.js`: the filter had already been removed in pre-existing
>   main work; no changes needed.
> - `annotations.js:80`: the filter is inside `currentPromptAnnotations()`,
>   the SEND/COPY path. Removing it would break the send-once invariant
>   and the Send/Copy button enable/disable logic.
> - `preview.js:75`: the only display-side filter still in place — this
>   is what was actually changed (commit `ba31262`).
>
> The original three-site instruction is retained above for historical
> context. A future re-run of this plan should follow the narrowed scope.
```

`docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md` — append a similar note in the "Console UI Changes / Inspector filter removal" section (around line 417–426):

```markdown
> **Implementation correction (2026-05-10 follow-up)**: At execution time
> only `preview.js:75` was modified. The `rendering.js:226` filter had
> already been removed; the `annotations.js:65` reference moved to
> `annotations.js:80` inside `currentPromptAnnotations()` where the
> filter is intentional (send-once invariant). See follow-up plan
> `2026-05-10-mcp-handoff-followups.md` for context.
```

### #3 — Highlight burst guard

```
rendering.js (modifications to mergeSessionIntoState)
  - Compute `changed` array first (currently inline forEach).
  - If changed.length >= 6 → return without scheduling any highlight.
  - Otherwise → existing per-item highlight loop unchanged.
```

The threshold constant `BULK_CHANGE_HIGHLIGHT_THRESHOLD = 6` lives at the top of the module-bundled scope (or inline as a named const inside the function).

## Data Model

No persisted-schema, MCP-tool, or HTTP-payload changes.

Console-only additions to `state` (in-memory):
- `state.connection.sessionsPollingPaused: boolean` — false until the 5th consecutive failure, true while paused, reset to false on any recovery path.
- `consecutivePollFailures: number` (module-local counter in `sessions-polling.js` or hoisted to `state.js`).

## Testing Strategy

### Backend / module tests

None for #1, #2, or #3 directly — the work is client-side and the existing module test suite verifies bundled HTML strings.

### Console / HTTP regression tests (FeedbackConsoleServerTest.kt)

Three new assertion clusters added in the same TDD pattern as the original work:

- `sessionsPollingPausesAfterConsecutiveFailures` — asserts the bundled JS contains the failure-counter increment and the threshold constant (`5` or named const).
- `sessionsPollingResumesOnVisibilityChange` — asserts the bundled JS visibility handler restarts polling when paused.
- `connectionCardSurfacesPollingPause` — asserts `renderConnection` (or sibling) consults `state.connection.sessionsPollingPaused` and sets a corresponding text or dataset attribute.
- `mergeSessionIntoStateSuppressesBulkHighlight` — asserts `mergeSessionIntoState` body contains a length-comparison guard against the threshold (`>= 6` or named const).

### Manual / E2E (no harness change)

- Stop the local fixthis-mcp server while the console is open; observe ≤1 failure toast then the connection card switches to "Reconnecting feedback updates…"; restart the server, observe automatic resume on next user action or tab refocus.
- Use a script to bulk-resolve 10 items via `fixthis_resolve_feedback`; observe NO highlight cascade after the next polling tick.
- Single `fixthis_claim_feedback`/`fixthis_resolve_feedback` call still produces a single highlight as before.

## Phased Rollout Order

Each phase ships independently; phase order matches risk severity (highest first).

### Phase A — Polling backoff (#1)

A1. Add failure counter + threshold constant in `sessions-polling.js`; wrap `pollSessionsTick` in try/catch that increments on failure and resets on success. Pause when threshold hit. + tests.
A2. Add `state.connection.sessionsPollingPaused` flag to `state.js`; helper `setSessionsPollingPaused(paused)`. + integrate into `applyConnectionStatus` flow (re-render on toggle).
A3. Extend `connection.js`'s `renderConnection` to surface the polling-paused state on the existing connection card (sub-line under the headline; does not replace bridge/device messaging). + test.
A4. Add `visibilitychange` and post-mutation recovery hooks in `main.js` and `state.js`'s `withMutationLock` (or its caller pattern). + test.

### Phase B — Highlight burst guard (#3)

B1. Modify `mergeSessionIntoState` in `rendering.js` to compute the `changed` array and return early if `changed.length >= BULK_CHANGE_HIGHLIGHT_THRESHOLD` (constant value 6). + test.

### Phase C — Plan/spec correction notes (#2)

C1. Edit `docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md` Task 21 — append correction note.
C2. Edit `docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md` — append corresponding note in the inspector filter removal section.

### Phase D — Final build + validation

D1. `node scripts/build-console-assets.mjs`, `./gradlew :fixthis-mcp:installDist`, full module sweep.
D2. CHANGELOG entry under `## Unreleased / ### Changed`:

```markdown
- Sessions polling now silently absorbs up to 5 consecutive failures, then pauses and surfaces a "Reconnecting feedback updates…" message on the connection card; resumes automatically when the tab becomes visible again or the user takes any mutating action.
- Bulk status changes (≥6 items in a single polling tick) skip the per-item highlight effect to avoid visual noise; single-item updates highlight as before.
```

## Risks & Mitigation

| Risk | Mitigation |
|---|---|
| Threshold of 6 is wrong (too low → user-driven multi-toggle suppressed; too high → noise still slips through) | Named constant; first complaint upgrades to a config or threshold tweak. Documented in CHANGELOG so users know the heuristic exists. |
| Connection card visual collision between bridge state and polling-paused state | Polling-paused renders as a sub-line, never replaces the headline. If it ever needs to win priority, that's a separate UX call. |
| Failure counter not reset across visibility changes (stale "paused" state) | The `visibilitychange` recovery resets the counter to 0 BEFORE restarting the timer, so the next tick starts clean. Test asserts the reset. |
| User-initiated mutation succeeds while paused but the polling timer isn't restarted | `withMutationLock` wrapper inspects `sessionsPollingPaused` after `pendingMutationCount` decrements to 0, calls restart if needed. Test for this hook. |
| Threshold-based highlight skip masks a legitimate single-item batch (user adds 6 items rapidly) | 6 status TRANSITIONS, not 6 items present. Adding new DRAFT items does not count (no previous status to compare). |
| Plan/spec edit conflicts with future regenerated plan from same source | The correction note is a markdown blockquote; regeneration tooling (if any) treats it as content. Manual edits to plans are acceptable per the existing repo convention. |

## Rollback

Each phase is git-revertable independently:
- Revert Phase A → polling reverts to per-tick `showError`.
- Revert Phase B → highlight burst returns; UX-only.
- Revert Phase C → docs revert; no behavior impact.

Phase D is a build commit; safe to revert in isolation.

## Open Questions / Future Work

- Should the threshold be configurable via a localStorage key (`fixthis.console.bulkHighlightThreshold`)? Likely yes if a second user reports the value is wrong; defer until that signal arrives.
- Should the "Reconnecting feedback updates…" sub-line distinguish "polling paused" from "polling actively retrying"? The current spec says "paused" — if telemetry shows users misinterpret that as "broken", switch to a retry indicator with a spinner.
- Should `withMutationLock` wrap a polling-restart attempt unconditionally on success, or only when paused? Current spec: only when paused, to avoid wasted work.
