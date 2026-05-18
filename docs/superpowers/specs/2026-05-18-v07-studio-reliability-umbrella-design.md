# v0.7 Studio Reliability Umbrella Design

Date: 2026-05-18
Status: Ready for user review
Scope: v0.7 roadmap framing for Studio reliability, push-first sync,
draft/session safety, and browser proof evidence

## Summary

v0.7 makes FixThis Studio reliable under repeated real use.

The release is centered on Studio Reliability, not a broad feature expansion.
The main user-visible claim is:

> FixThis Studio stays reliable through repeated annotation, reconnect,
> multi-tab, session switching, Save to MCP, and draft recovery workflows.

v0.7 should land as an umbrella milestone with three independent tracks:

1. **Track A: Push-First Sync Core** - make Server-Sent Events the primary
   session and preview update path, with polling retained as fallback and
   manual recovery.
2. **Track B: Draft / Session Safety** - make durable mutation ownership
   explicit across sessions, workspaces, draft ids, deleted sessions, closed
   sessions, retries, and stale responses.
3. **Track C: Browser Proof Evidence** - promote real browser reliability
   scenarios to release evidence for multi-tab sync, reconnect, repeated save,
   stale response isolation, and closed/deleted session conflicts.

Handoff Intelligence remains a supporting concern for v0.7. The v0.7 priority
is making the Studio state pipeline trustworthy enough that handoff evidence is
not lost, duplicated, stale, or applied to the wrong workspace.

## Why Now

Recent commits show the next reliability frontier clearly:

- v0.6 shipped Handoff Intelligence, Studio Reliability, and Release Grade
  tracks, then immediately needed CI/perf hardening for cache and baseline
  drift.
- Studio fixes after v0.6 repeatedly touched preview, annotation entry,
  session counts, draft saves, session switching, recovered previews, and
  stale preview handling.
- The existing architecture document says SSE Phase 1 has shipped, but preview
  polling and lockstep pull refreshes still remain as fallback-oriented paths.
- The roadmap already calls out reducing redundant pull refreshes and
  eventually retiring preview polling once the event stream has enough
  evidence.

The gap is not missing primitives. FixThis already has SSE, draft recovery,
idempotent draft keys, session fences, closed-session rejection, release
evidence scripts, and Playwright/fake-bridge proof infrastructure. The v0.7
work is to finish the reliability boundary and prove it at browser level.

## Product Goal

v0.7 should support this release claim:

> FixThis Studio is reliable under repeated real use: preview sync is
> push-first, draft/session ownership is explicit, and browser-level proof
> covers reconnects, multi-tab updates, repeated saves, and stale response
> isolation.

This claim is intentionally about the Studio workflow. It does not promise
perfect source matching or exact source-line edits. It promises that the local
feedback workspace remains coherent while the user and agent move through the
handoff loop.

## Non-Goals

- No visual redesign of FixThis Studio.
- No persisted MCP JSON field rename.
- No release-build sidekick behavior.
- No XML/View, WebView DOM, Flutter, React Native, iOS, or cloud workflow
  expansion.
- No guaranteed exact source-line mapping.
- No automatic code edits inside FixThis itself.
- No requirement to remove every polling path in one step.
- No requirement that browser proof become a PR-time required check before its
  runtime cost is measured.

## Current Baseline

The repository already has these foundations:

- `/api/events` streams snapshot, session, sessions, device, connection, and
  preview events.
- Server events that can mutate visible session or preview state carry
  `sessionId`.
- Browser handlers already fence stale SSE and preview-poll responses by
  active session in several paths.
- Preview polling exists as a compatibility and recovery path.
- Session polling uses ETags and is paused around hidden tabs, active edits,
  and in-flight mutations.
- Draft recovery uses schema-v2 browser-local workspaces instead of pre-v0.4
  pending mirrors.
- Durable session mutations are rejected for closed sessions.
- Duplicate draft saves are idempotent by browser draft identity.
- `npm run console:reliability:test`,
  `npm run console:browser:reliability`, and focused console groups already
  exist.

The weakness is that these guarantees are still distributed across overlapping
paths. v0.7 should collapse the user-visible state writes behind common
application paths and make browser proof the evidence for the release claim.

## Track A: Push-First Sync Core

### Goal

Make SSE the primary state sync path for Studio preview and session state.
Polling remains only as fallback, manual refresh, and explicit recovery.

### Design

`ConsoleEventBus` and `/api/events` remain the server push source for:

- `snapshot`
- `preview-ready`
- `session-updated`
- `sessions-updated`
- `connection-updated`
- `devices-updated`

`events.js` should parse and validate events, then pass them into shared
application functions. It should not own special-case preview or session state
writes that bypass the reducer/use-case boundary.

`previewPoll.js` and `sessions-polling.js` become fallback adapters. They may
still fetch state, but successful responses must flow through the same apply
paths used by SSE.

The shared preview path owns these rules:

- Mismatched `sessionId` does not mutate the active detail pane or preview.
- Stale preview generation does not replace a newer preview.
- Draft mode and workflow policy can block live preview replacement.
- `previewAvailable: false` updates readiness UI only and never overwrites
  `state.preview`.
- Manual refresh and reconnect recovery can request an authoritative snapshot.

### Acceptance

- SSE and preview polling use one shared preview apply contract.
- Session SSE and session polling use one shared session apply contract where
  practical.
- `previewAvailable: false` is guarded in the shared apply path, not only in
  an event-specific handler.
- Fallback polling starts only when SSE is unavailable, replay overflow occurs,
  the user manually refreshes, or an explicit recovery path requests it.
- Existing launch recovery and prompt preview recovery behavior stays intact.

## Track B: Draft / Session Safety

### Goal

Make draft ownership and durable mutation identity explicit so repeated Studio
use cannot corrupt another session, workspace, or draft.

### Durable Mutation Identity

Every durable mutation captures identity at action start:

- active `sessionId`
- relevant item ids
- `workspaceId` when draft state is involved
- `draftItemId` when saving browser-local draft items
- preview/session generation when visible state can be stale

The browser sends explicit identity in the request where the route supports it.
The server validates durable ownership. The browser applies the response only
when the current active identity still matches the action identity.

### Server Boundary

Server session services own durable truth. They reject:

- closed-session durable mutations
- deleted or missing sessions
- missing items
- duplicate draft keys that are not idempotent retries
- stale draft batches that require conflict resolution

Failures should use explicit conflict codes where possible, such as
`session_closed`, rather than falling through as generic server errors.

### Browser Boundary

The browser may keep local draft state, but it is not durable truth.

The browser should:

- ignore stale responses for a no-longer-active session/workspace/generation;
- refresh summaries after stale responses when useful;
- avoid mutating the active detail pane with another session's response;
- surface clear conflict UI when the current user action needs a decision;
- keep read-only closed-session recovery separate from recapture into a
  mutable session.

### Draft Recovery Policy

Draft recovery should classify local work into explicit ownership states:

- recoverable written draft
- pin-only residual draft
- stale server draft conflict
- deleted-session draft
- closed-session draft
- mismatched-session draft

`Copy Prompt` may preserve pin-only residual work as browser-local state when
written items are copied or handed off. `Save to MCP` completion discards
pin-only residuals in that action scope because the user has completed the
handoff path.

Closed-session recovery is not a durable mutation path. It is read-only or
recapture-only.

### Acceptance

- Repeated Save to MCP does not create duplicate saved annotations.
- Adding one new written item after a retried save persists only the new item.
- Session switch, new session, close session, and delete session cannot apply
  stale draft responses to the active workspace.
- Closed/deleted session recovery never silently mutates the old durable
  session.
- Conflict UI copy distinguishes recover, recapture, discard, read-only, and
  server-version decisions.

## Track C: Browser Proof Evidence

### Goal

Prove v0.7 reliability in a real browser with the fake bridge/server fixture,
not only through source-shape, reducer, and route tests.

### Required Browser Proof Scenarios

Browser proof should live in `scripts/console-browser-reliability.mjs` or a
new reliability proof runner if the scenarios become too large.

Minimum scenarios:

- **Two-tab update propagation:** Tab A mutates a session; Tab B receives the
  update over SSE without waiting for periodic polling.
- **EventSource reconnect:** a dropped event stream reconnects through replay
  or authoritative snapshot without losing active session state.
- **Repeated Save to MCP idempotency:** slow or repeated saves reuse draft ids
  and do not duplicate durable items.
- **Late response isolation:** stale preview, session, and save responses do
  not mutate the current active session after the user switches workspaces.
- **Closed/deleted session conflict:** durable mutation is rejected by the
  server and surfaced as a clear browser state.

The proof runner should upload screenshots or structured artifacts on failure
when it runs in CI.

### Release Evidence

v0.7 release notes may claim Studio Reliability only when these pass:

- focused reducer/use-case/session tests for Tracks A and B
- `npm run console:reliability:test`
- `npm run console:session:test`
- `npm run console:draft:test`
- `npm run console:browser:reliability`
- any new v0.7 evidence checker that binds the above to release claims

Browser proof does not need to become a PR-time required check immediately.
That decision should be based on runtime, flake rate, and whether the nightly
observation window is green.

## Data Flow

### Preview Push Flow

1. Bridge/server captures or receives a new preview.
2. Server emits `preview-ready { sessionId, preview }`.
3. `events.js` parses the event and checks basic shape.
4. Shared preview apply validates active `sessionId`, generation, workflow
   policy, and draft state.
5. If `previewAvailable === false`, readiness UI updates and existing
   `state.preview` is preserved.
6. Otherwise the live preview updates and preview UI renders.

Fallback preview polling uses steps 3 through 6 after fetching the preview.

### Durable Mutation Flow

1. Browser captures action identity at start.
2. Browser sends mutation request with explicit identity.
3. Server validates session state, item state, draft keys, and conflict rules.
4. Server writes durable state and emits events when accepted.
5. Browser receives success or conflict.
6. Browser applies success only if active identity still matches.
7. Browser surfaces conflict or refreshes summaries when identity is stale.

### Draft Recovery Flow

1. Browser finds a local draft workspace envelope.
2. Recovery policy classifies ownership.
3. Recoverable written drafts can resume when frozen preview context is valid.
4. Pin-only residual drafts stay local until explicit discard or eligible copy
   flow.
5. Deleted sessions require discard or recapture.
6. Closed sessions allow read-only inspection or recapture into a mutable
   session.
7. Stale server drafts require explicit conflict decision.

## Error Handling

- **Stale event or response:** ignore visible mutation, optionally refresh
  summaries.
- **Stale save response:** do not mutate the active detail pane; surface a
  conflict only if the user action still needs attention.
- **Closed session:** server returns explicit conflict; browser offers
  read-only, recapture, or discard where appropriate.
- **Deleted session:** browser does not auto-recover into the deleted durable
  session.
- **SSE unavailable:** show reconnecting state and enable fallback polling.
- **Replay overflow:** request authoritative snapshot/full refresh.
- **Capture unavailable:** update readiness UI only; preserve current live
  preview.

## Testing Strategy

### Contract Tests

Add or strengthen tests for:

- reducer rejects mismatched `sessionId`, `workspaceId`, and generation;
- SSE and polling use the same preview apply path;
- `previewAvailable: false` never overwrites `state.preview`;
- repeated draft saves remain idempotent by `workspaceId + draftItemId`;
- adding a new written draft item after a retry persists only the new item;
- closed/deleted sessions reject durable mutation consistently;
- recovery matrix covers written drafts, pin-only residuals, stale server
  draft, closed session, deleted session, and mismatched session;
- conflict presentation distinguishes server-version, overwrite, recapture,
  discard, and read-only paths.

### Browser Proof

Run Playwright/fake-bridge scenarios for:

- multi-tab session updates;
- EventSource reconnect and replay/snapshot recovery;
- repeated Save to MCP idempotency;
- stale preview/session/save response isolation;
- closed/deleted session conflicts;
- capture-unavailable preview preservation.

### Release Checks

Add a v0.7 release evidence checker only if it prevents false release claims
without adding brittle duplication. The checker should verify that release
notes naming Studio Reliability also name the required evidence commands.

## Implementation Sequencing

1. Add failing contract tests around shared preview/session apply paths and
   stale response identity.
2. Route SSE and fallback preview responses through the shared preview apply
   function.
3. Narrow fallback polling start/stop conditions and document them.
4. Strengthen durable mutation identity capture and stale response handling in
   browser use cases.
5. Strengthen server conflicts for closed/deleted/missing durable mutation
   paths.
6. Expand draft recovery classification and conflict presentation tests.
7. Add browser proof scenarios and failure artifacts.
8. Add release evidence documentation and claim checks.

Each track can land independently, but the v0.7 Studio Reliability claim needs
all three tracks or release notes must narrow the claim.

## Risks

- **CI latency:** browser proof may be too slow for PR-time gating. Mitigation:
  use it as release evidence first, then promote only after observation.
- **Over-centralized reducer:** shared apply paths can become too broad.
  Mitigation: keep preview, session, and draft apply contracts separate.
- **False confidence from tests:** unit tests may overfit source shape.
  Mitigation: browser proof must cover user-visible scenarios.
- **Polling removal regression:** removing pull paths too aggressively may hurt
  reconnect recovery. Mitigation: narrow polling before removing it.
- **Conflict UI complexity:** too many recovery states can confuse users.
  Mitigation: classify internally but keep user choices short and explicit.

## Open Decisions

- Whether v0.7 browser proof remains release-only evidence or graduates to a
  required PR check after observation. (Still open — gate on observed runtime
  and flake rate after v0.7 ships.)
- v0.7 uses a dedicated `release:v07:evidence:test` script so Studio
  Reliability claims can evolve without weakening the v0.6 release claim
  checker. (Resolved 2026-05-18 — implemented by the plan's Task 7.)
- Whether fallback polling should keep its current filenames during migration
  or be renamed after behavior narrows. (Still open — defer rename until the
  behavior-narrowing decisions in Tracks A/B settle and there is a clear
  fallback-only call surface.)

## Audit Notes (2026-05-18)

A doc-vs-source audit at `HEAD` (`af252a03`) confirmed the source baseline
this spec describes:

- `events.js` `preview-ready` already routes through `applyLivePreview` and
  drops only inline `setConsolePreview` writes; the residual session/snapshot
  handlers still inline session-ownership branching that Track A collapses.
- `applyLivePreview` already gates `previewAvailable: false` against
  `setConsolePreview`, but the gate is duplicated in the SSE handler and is
  not yet a named decision; Track A's shared decision helper closes that gap.
- `pollingBrowserAdapter.js` currently treats a mismatched mid-poll session
  response by switching the displayed session via the `else` branch. The
  plan's `serverSessionApplyDecision` adds a `refresh_summaries` outcome with
  an explicit no-op branch so late polls cannot mutate the active detail
  pane.
- `consoleReducer.js` fences draft save success/failure on
  `effectsGeneration` and `workspaceId`, but does not yet fence
  `reducePromptCopySucceeded` / `reducePromptCopyFailed`. The plan extends
  Track B to cover prompt-copy responses, matching this spec's "Durable
  Mutation Identity" section.
- `draftRecoveryOwnership` does not yet model a `mismatched` session mode.
  The plan adds it and wires the two production callers
  (`main.js`, `pendingRecoveryUi.js`) so the new mode actually surfaces.

## Approval Notes

The user approved:

1. A v0.7 umbrella roadmap centered on Studio Reliability.
2. The Reliability Core + Evidence Tracks approach.
3. The track architecture for push-first sync, draft/session safety, and
   browser proof evidence.
4. The data flow, error handling, testing, and non-goal boundaries above.
