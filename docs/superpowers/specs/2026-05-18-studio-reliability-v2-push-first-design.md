# Studio Reliability v2 Push-First Design

Date: 2026-05-18
Status: Ready for implementation; updated after post-plan console fixes
Scope: Follow-up hardening for FixThis Studio preview, recovery, durable
mutation, and browser proof reliability

## Summary

Studio Reliability v2 makes FixThis Studio stable under repeated real use by
moving preview/session synchronization toward a push-first model and tightening
the ownership boundaries around draft recovery and durable mutations.

The design follows the recent v0.6 Studio Reliability work. That work added
workflow policy, draft recovery coverage, stale-session fences, duplicate save
idempotency, and release evidence gates. This follow-up narrows the remaining
risk: Studio still has redundant preview pull paths, several recovery ownership
cases are easy to regress, and browser-level multi-tab/reconnect behavior is
not yet proven as an end-to-end user contract.

Update after commits `99acd993` and `a51cf230`: the current baseline already
adds bounded launch recovery polling, restores live preview after prompt
persistence, prevents capture-unavailable preview payloads from replacing the
live preview, allows first-capture annotation edge states, and stops new-history
annotation when new-session creation is cancelled. Studio Reliability v2 must
preserve these fixes while moving preview delivery behind the shared push-first
state path.

The preferred approach is **Push-First Studio Reliability**:

1. Use `preview-ready` Server-Sent Events as the primary live preview path.
2. Keep preview polling only as a fallback and manual recovery path.
3. Centralize visible state writes through reducer/use-case boundaries.
4. Enforce explicit identity for durable mutations.
5. Add browser proof scenarios for two-tab sync, reconnect, repeated save, and
   late-response isolation.

## Goals

- Make `preview-ready` SSE the primary source of live preview updates.
- Reduce or isolate `livePreviewPolling`, `previewRequestGeneration`, and
  preview context generation complexity.
- Define clear ownership for written drafts, pin-only residuals, closed
  sessions, deleted sessions, and stale server draft conflicts.
- Ensure Save/Edit/Delete/Claim/Resolve/Handoff mutations cannot update the
  wrong visible session or duplicate durable state.
- Prove the behavior in a browser, not only through source-shape and route
  tests.
- Preserve the post-plan annotation readiness fixes: launch recovery polling,
  prompt preview recovery, capture-unavailable readiness display, and cancelled
  new-session annotation handling.
- Preserve Clean Architecture and SOLID boundaries while making the state
  pipeline simpler.

## Non-Goals

- No handoff intelligence or edit-surface scoring changes.
- No visual redesign of FixThis Studio.
- No persisted MCP JSON field rename.
- No release-build sidekick behavior.
- No XML/View, WebView DOM, Flutter, React Native, iOS, or cloud workflow
  expansion.
- No automatic code edits inside FixThis itself.
- No requirement to remove every polling path in one implementation step.

## Clean Architecture And SOLID Constraints

All code produced from this design must preserve the existing module and layer
boundaries:

- `:fixthis-compose-core` remains independent of MCP, CLI, Android UI,
  `.fixthis/` paths, browser console state, and transport details.
- `fixthis-mcp` owns local session persistence, route behavior, event emission,
  and feedback handoff state.
- Browser console JavaScript owns presentation state, local draft recovery, and
  user interaction policy. It must not become the source of durable truth.
- Route handlers and UI adapters should delegate policy to small services or
  reducer/use-case functions instead of embedding broad conditional workflows.
- Shared state transitions should be expressed through narrow interfaces and
  tested as contracts, not inferred from global mutable variables.

SOLID application for this work:

- **Single Responsibility:** event transport, preview application, draft
  recovery, mutation idempotency, and browser rendering stay in separate units.
- **Open/Closed:** new reliability policy is added through focused reducers,
  services, and adapters without rewriting unrelated console features.
- **Liskov Substitution:** fallback polling and SSE preview delivery must feed
  the same state application contract so either source can be substituted.
- **Interface Segregation:** browser ports for storage, network, dialogs, and
  rendering expose only the methods needed by the use case under test.
- **Dependency Inversion:** workflow policy and mutation decisions depend on
  stable DTOs/domain contracts rather than concrete DOM or route
  implementation details.

## Architecture

Studio becomes push-first for preview/session state. `/api/events` and
`ConsoleEventBus` are the primary server-to-browser update channel. Preview
polling remains available only when EventSource is unavailable, reconnect
recovery needs a full refresh, or the user explicitly triggers a manual
refresh/recovery path.

The browser applies both SSE and fallback polling through the same reducer or
use-case entry point. That reducer is the final browser-side guard for active
`sessionId`, draft state, preview context generation, and stale response
handling. Server routes and session services remain the final durable-state
guard for closed sessions, missing items, duplicate draft keys, and revision
mismatches.

The shared preview path must also own readiness-only preview payloads. When a
preview reports `previewAvailable: false`, the console updates the connection
readiness UI and does not replace `state.preview` or freeze an unavailable
screen. SSE and polling must preserve that behavior through the same function.

The design is intentionally incremental. It should allow an implementation to
first add tests around the current behavior, then route preview updates through
one state path, then shrink polling scope, then add browser proof scenarios.

## Components And Boundaries

### `ConsoleEventBus` and `/api/events`

This is the server push source. It emits `preview-ready`, `session-updated`,
`sessions-updated`, `connection-updated`, and `devices-updated`. Events that
can mutate visible session detail or preview state must carry top-level
`sessionId`.

The event bus owns event ids, replay, overflow behavior, and subscriber
fan-out. It does not own browser draft recovery or presentation policy.

### `events.js`

This is the browser SSE subscriber. It parses server events, fences them by
active session and draft state, then dispatches to the shared preview/session
state application path.

It may trigger fallback refresh on disconnect, replay overflow, or invalid
payloads. It must not directly clear drafts, handoff batches, claim state, or
resolve state.

The existing `preview-ready` capture-unavailable guard should move into the
shared preview application path when `events.js` stops writing preview state
directly. The guard is a user-visible readiness contract, not an implementation
detail of the SSE subscriber.

### Preview fallback polling

The existing `livePreviewPolling` behavior should be narrowed into a fallback
adapter. It should run only when SSE is unavailable or when an explicit recovery
path asks for it. Its successful preview response must use the same reducer
action as `preview-ready`.

The implementation may keep the old name during migration, but the end state
should make its fallback-only role clear in code and tests.

### `consoleReducer.js`

The reducer is the final browser-side defense for visible state. It rejects
late preview events, late polling responses, late save responses, and generation
mismatches when they do not belong to the current session/workspace.

Reducer tests should protect behavior, not incidental source shape.

### `draftWorkspace*` and `draftUseCases.js`

These files own browser-local draft and recovery policy. They decide whether
browser-local work is recoverable, discarded, recaptured, or confirmation
gated.

Written comments are recoverable. Pin-only residuals may survive `Copy Prompt`
as browser-local work, but `Save to MCP` completion discards residual pin-only
state for that action. Deleted sessions do not auto-recover; closed sessions
are read-only or require explicit recapture.

### `FeedbackSessionStore` and route services

Server-side session services own durable truth. They reject closed-session
mutations, preserve idempotent batch save semantics, and require explicit
session/item identity for item-level mutation where that identity is available.

The browser may optimistically prepare UI state, but durable state changes are
accepted only by these server boundaries.

### Browser proof scripts

Browser proof should live in an existing or new console reliability script,
such as `scripts/console-browser-reliability.mjs`, depending on implementation
fit. These tests validate user-visible behavior with real browser tabs and a
fake bridge/server fixture rather than relying only on unit tests.

## Data Flow

### Preview Push Flow

1. Bridge/server captures or receives a new preview frame.
2. Server emits `preview-ready { sessionId, preview }`.
3. `events.js` checks that the event belongs to the active session and is not
   blocked by draft mode or workflow policy.
4. The shared preview reducer action validates `sessionId` and preview context
   generation again.
5. If `previewAvailable === false`, readiness UI updates and the current live
   preview is preserved.
6. Otherwise `state.preview` updates and preview UI renders.

Fallback polling uses steps 3 through 5 after fetching preview data. There must
not be a separate state write path for SSE and polling.

### Draft Recovery Flow

- A draft with written comments is recoverable when its frozen preview context
  is present.
- Pin-only residual work is local-only. It can remain after `Copy Prompt`, but
  it is discarded after `Save to MCP` completes.
- Deleted session recovery is not automatic. The user can discard or recapture
  into a current session.
- Closed session recovery does not allow mutation of the closed durable
  session. It can be inspected read-only or explicitly recaptured into a
  mutable session.
- Stale server draft conflicts require a confirmation boundary before durable
  mutation.

### Durable Mutation Flow

Every durable action follows the same ownership pattern:

1. Capture `sessionId`, relevant item ids, `workspaceId` when present,
   `generation`, and `expectedRevision` at action start.
2. Send explicit identity in the request.
3. Server validates closed-session state, item existence, duplicate client draft
   keys, and revision mismatch.
4. Browser receives success or failure and updates visible state only if the
   active session/workspace/generation still matches.
5. Mismatch is treated as a stale response. The browser may refresh summaries,
   but it must not mutate the active detail pane with another session's result.

### Two-Tab Flow

When Tab A saves, edits, or resolves feedback, the server store emits an event.
Tab B receives the event and updates visible state without waiting for the
polling interval. If Tab B reconnects after replay overflow, it recovers via
snapshot or full refresh.

## Error Handling

- **SSE disconnect or EventSource unsupported:** show reconnecting state and
  start fallback polling. Do not clear drafts, handoff batches, claim state, or
  resolve state.
- **Launch transient states after opening the app:** use bounded connection
  refresh retry while the state remains `STARTING`, `OPEN_APP`, or `RECONNECT`.
  Stop the retry once the connection becomes ready or leaves launch-transient
  states.
- **Replay overflow:** recover through snapshot or full refresh. Intermediate
  visual transitions may be skipped, but final state must match server truth.
- **Late preview event or late polling response:** discard when `sessionId`,
  context generation, or draft state no longer matches.
- **Capture unavailable preview:** update the readiness slot and Capture action,
  but do not replace the existing preview or begin draft freeze.
- **Prompt persistence completion:** after Copy Prompt or Save to MCP clears or
  changes draft state, restart preview recovery only when the session is live
  and no draft flow is active.
- **Dirty draft during session switch:** keep the existing confirmation
  boundary. Do not discard draft workspace on session switch without consent.
- **Deleted session with local recovery:** do not auto-recover. Offer discard
  or recapture into a current session.
- **Closed session mutation:** reject on the server; ignore any stale browser
  success response that no longer matches active state.
- **Duplicate Save to MCP or network retry:** continue using
  `workspaceId + draftItemId` idempotency. Full duplicate is no-op; partial
  duplicate appends only new items.
- **Stale screen fingerprint:** require recapture, force-save, or cancel
  confirmation before durable save continues.
- **Browser request cancellation:** keep the existing `ConsoleHttp`
  quiet-close policy so client disconnect is not logged as a server defect.

## Testing Strategy

### Reducer And JavaScript Contract Tests

- `preview-ready` and fallback polling dispatch the same state application
  action.
- Mismatched `sessionId`, stale generation, and active draft state prevent
  preview updates.
- Capture-unavailable readiness does not replace `state.preview` for either SSE
  or fallback polling.
- Launch recovery polling and prompt preview recovery remain covered by
  `scripts/launchRecoveryPolling-test.mjs` and
  `scripts/promptPreviewRecovery-test.mjs`.
- Annotation edge cases remain covered by `scripts/silentReturnFeedback-test.mjs`,
  `scripts/pendingBoundaryGuard-test.mjs`, `scripts/studioWorkflow-test.mjs`,
  and `scripts/consoleCanonicalRuntimeContract-test.mjs`.
- Recovery matrix covers written draft, pin-only residual, deleted session,
  closed session, saved completion, and stale conflict.
- Durable mutation success/failure does not change visible state when active
  session, workspace, or generation no longer matches.

### Kotlin Route And Session Tests

- Closed session claim/resolve/item mutation is rejected.
- Batch save idempotency covers full duplicate no-op and partial duplicate
  append-only behavior.
- Session mutation events include top-level `sessionId`.
- Preview-ready event emission preserves session ownership.
- Route failures remain explicit JSON errors unless they are browser transport
  cancellations handled by `ConsoleHttp`.

### Browser Proof Suite

- Two browser tabs stay in sync after Tab A mutates server state.
- EventSource reconnect recovers through replay or snapshot.
- Session switch during late preview/save response does not contaminate the new
  active session UI.
- Repeated `Save to MCP` does not create duplicate saved items.
- Stale preview `Save to MCP` cannot proceed without the confirmation boundary.

## Acceptance Criteria

- `livePreviewPolling` is no longer the normal live preview loop.
- SSE and fallback preview delivery share one visible state application path.
- Draft recovery ownership is documented in tests for written, pin-only,
  deleted, closed, saved, and stale-conflict cases.
- Durable mutations require explicit identity and are fenced by session,
  workspace or item identity, generation, and expected revision where
  applicable.
- Browser proof covers two-tab sync, reconnect recovery, repeated save
  idempotency, stale preview confirmation, and late-response isolation.
- Existing post-plan console edge tests still pass after the shared preview path
  refactor.
- `npm run console:reliability:test` passes.
- Relevant `:fixthis-mcp` route/session tests pass.
- Browser reliability proof passes in the local console harness.
- Persisted MCP JSON compatibility fields are unchanged.
- The implementation preserves Clean Architecture and SOLID constraints listed
  in this document.

## Rollout Plan

Implementation should be split into small, independently reviewable steps:

1. Add failing contract tests for the intended preview/recovery/mutation
   behavior.
2. Route SSE and fallback preview responses through a shared state path.
3. Narrow preview polling to fallback conditions.
4. Strengthen draft recovery ownership tests and any missing use-case logic.
5. Strengthen durable mutation fences in browser and server boundaries.
6. Add browser proof scenarios.
7. Update docs and release evidence commands only where behavior has changed.

Each step should prefer behavior tests over broad source-shape assertions and
should avoid unrelated refactoring.

## Open Design Decisions Resolved

- The feature is scoped to Studio stability, not source-matching intelligence.
- All four reliability risks are in scope: preview sync, session/draft
  recovery, mutation safety, and browser-level proof.
- The primary architectural choice is push-first SSE with fallback polling.
- Clean Architecture and SOLID compliance is a hard implementation constraint,
  not a review afterthought.
