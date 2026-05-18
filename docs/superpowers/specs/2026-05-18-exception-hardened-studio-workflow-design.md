# Exception-Hardened Studio Workflow Design

Date: 2026-05-18
Status: Ready for user review
Scope: FixThis Studio workflow hardening for connection, session, draft,
handoff, claim, and resolve exceptions while preserving the current UI shape

## Summary

FixThis Studio should become more robust under real Android development
conditions without turning into a new product surface. The current Studio UI
already has the right major pieces: a preview/annotation canvas, a connection
card, an annotation and handoff panel, local draft recovery, stale-preview
handling, and MCP handoff persistence. The next improvement is to make those
pieces behave predictably when exceptions overlap.

This design chooses a workflow-state-machine approach. It does not redesign the
Studio layout. It introduces a higher-level `StudioWorkflow` model that decides
which user actions are safe, which actions are blocked, and which actions need
explicit confirmation before they mutate draft, handoff, or queue state.

The product rule is mixed recovery:

- automatic recovery for read-only or preview-level connection recovery;
- explicit confirmation for draft, handoff, claim, resolve, session switch, or
  other durable queue mutations;
- preservation of unsaved drafts, sent handoff batches, claim state, and
  persisted evidence regardless of connection churn.

## Product Goal

The user-facing goal is:

> Studio should keep the existing workflow but remain trustworthy when the
> device disconnects, the user opens another app, the phone locks, the bridge
> stalls, the preview becomes stale, a session changes, or a handoff operation
> is interrupted.

Trustworthy means the user can tell what is safe to do next, and FixThis does
not silently mutate the feedback queue under uncertain runtime context.

The engineering goal is to stop spreading exception rules across unrelated UI
paths. Connection state, preview state, draft workspace state, handoff state,
session state, and queue mutations should pass through one workflow policy
layer before UI code performs effects.

## Non-Goals

- No broad visual redesign of FixThis Studio.
- No new in-app Android annotation UI.
- No production runtime or release-build sidekick behavior.
- No XML/View, Flutter, React Native, iOS, or cloud workflow expansion.
- No rename or migration of persisted MCP JSON compatibility fields such as
  `items`, `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, or `sourceCandidates`.
- No `.fixthis/` storage migration.
- No bridge protocol bump unless implementation discovers a small additive
  capability is required to classify an exception honestly.
- No automatic agent code edits.

## Current Baseline

The repository already contains many reliability primitives:

- `ConsoleConnectionService` classifies ready devices, no-device states,
  multiple-device choice, app-open requirements, unsupported builds, and raw
  bridge failures.
- `connectionFsm.js` owns connection status, readiness history, availability,
  blocked reason, heartbeat error, and polling pause fields.
- `consoleAppState.js` and `workspaceState.js` model active session,
  live-preview workspace, draft workspace, saved focus, and recovery.
- `draftUseCases.js` owns draft freeze, item edits, persistence, recovery
  envelopes, boundary prompts, and lifecycle boundaries.
- Existing tests cover session mismatch ignore, stale draft conflict, duplicate
  draft save idempotency, draft recovery, stale-session fences, blocked-device
  behavior, reconnect choreography, preview staleness, and connection FSM
  basics.

The gap is that the current primitives are not governed by one workflow policy.
Some paths decide locally whether to reconnect, save, switch sessions, preserve
drafts, hide overlays, or ignore stale responses. That makes overlapped
exceptions harder to reason about and harder to test as a product behavior.

## Design Principles

1. **Preserve the current UI shape.** The center canvas, connection card,
   annotation panel, Copy Prompt, Save to MCP, and existing dialogs stay in
   their current roles.
2. **Centralize decisions, not rendering.** UI components should ask the
   workflow model what is allowed, blocked, or needs confirmation. They should
   not independently recreate exception policy.
3. **Separate connection from data mutation.** A connection problem can change
   whether actions are currently possible, but it must not erase draft or queue
   data.
4. **Make durable mutations explicit under risk.** Draft overwrite, Save to
   MCP, claim, resolve, stale force-save, and session switch require a clear
   safe context or a boundary confirmation.
5. **Prefer explainable no-ops over surprising recovery.** If the correct
   behavior is to drop a stale response, the drop should be silent to the user
   but visible to tests and diagnostics.

## Workflow State Model

Add a pure `StudioWorkflow` model above the existing connection, app, workspace,
and draft layers. It should compose existing state rather than duplicate every
field.

Recommended dimensions:

```text
Connection:
  initializing | ready | reconnecting | blocked | unsupported | no-device

Workspace:
  empty | live-preview | frozen-draft | saved-focus | recovery

Operation:
  idle | capturing | saving-draft | copying-prompt | saving-handoff |
  claiming | resolving

Risk:
  none | stale-preview | activity-drift | session-mismatch | dirty-draft |
  in-flight-mutation | closed-session | missing-item
```

The workflow model should expose derived decisions instead of raw booleans:

```text
decision(action, state) ->
  allow(effect)
  block(reason, displaySurface)
  confirm(boundaryKind, choices)
  ignore(reason)
```

Example actions:

- `annotateClicked`
- `captureCompleted`
- `saveDraftClicked`
- `copyPromptClicked`
- `saveToMcpClicked`
- `claimItemClicked`
- `resolveItemClicked`
- `sessionSwitchRequested`
- `heartbeatFailed`
- `connectionStatusReceived`
- `previewPollReceived`
- `handoffResponseReceived`

The first implementation does not need to move every console path at once. It
should start with the risky paths that mutate user work: annotate/capture,
draft save, Copy Prompt, Save to MCP, session switch, claim, and resolve.

## Exception Policy

Every exception should reduce to one of four decisions.

### Automatic Recovery

Automatic recovery is allowed only when it does not mutate durable user work.

Examples:

- heartbeat retry after a transient bridge failure;
- app foreground returns to the expected package/activity;
- device blocked reason clears;
- preview polling or session polling resumes;
- connection status refresh succeeds after reconnect;
- stale badge or connection card updates.

Automatic recovery can refresh preview-level state, but it must not mark items
sent, claimed, resolved, overwritten, or deleted.

### Block

Blocking is used when an action cannot be safely attempted and no user choice
would make the current request valid.

Examples:

- device offline, unauthorized, or missing;
- unsupported build or sidekick unavailable;
- no active feedback session;
- another operation is already in flight;
- session mismatch response arrives for an inactive session;
- claim or resolve target item no longer exists;
- closed session cannot receive a new draft item.

Blocked actions should not mutate workflow state beyond diagnostics. The UI can
show a disabled button, existing banner/toast, connection details, or canvas
blocked overlay.

### Confirm

Confirmation is used when FixThis can proceed but needs the user to choose the
safe data policy.

Examples:

- dirty draft exists and the user switches sessions;
- stale preview or activity drift is present during Save to MCP;
- server draft conflict occurs during batch save;
- connection changed while a handoff, claim, or resolve operation was in
  flight and the user retries;
- a user attempts to attach new work to a closed or stale session context;
- force-save would preserve user intent but reduce target confidence.

The existing boundary dialog family should be reused. Choices should be framed
around data safety, such as keep local draft, use server version, recapture,
force-save, cancel, or switch without saving.

### Ignore

Ignoring is used for stale asynchronous results.

Examples:

- late SSE event for an inactive session;
- late preview poll for a previous session;
- late save response whose generation no longer matches the current workflow;
- late connection response after the selected device changed;
- duplicate retry response already accounted for by idempotency keys.

Ignore decisions should avoid user-facing notifications. They should remain
visible in console diagnostics and deterministic tests.

## UI Preservation And Display Priority

The current Studio layout remains the visible product. The workflow model only
standardizes what each existing surface displays.

Preserved surfaces:

- preview and frozen annotation canvas;
- connection card and readiness/details slot;
- device picker and disconnect device affordance;
- preview stale badge and canvas blocked overlay;
- staleness banner and notification center;
- boundary dialogs;
- right-side annotation, draft, handoff, and queue panels;
- Copy Prompt and Save to MCP buttons.

Display priority:

1. **Hard blocker:** unsupported build, no device, unauthorized or offline
   device. Connection card and canvas blocked overlay take precedence.
2. **In-flight operation:** capture, save, copy, handoff, claim, or resolve is
   running. The relevant controls are disabled and show compact progress.
3. **Data risk:** stale preview, activity drift, session mismatch, server
   conflict, closed session, or missing target item. Boundary dialog or stale
   notice takes precedence.
4. **Recoverable connection issue:** reconnecting, app backgrounded, locked
   phone, system UI obstruction. Last preview and local draft remain visible;
   badges and connection card explain that work is preserved.
5. **Normal workflow:** annotate, add item, copy, save, claim, resolve.

Copy should prioritize safe next action over raw implementation details. For
example, "Connection paused - draft preserved" is better than "Bridge failed"
on the primary surface. Raw bridge errors stay in Details.

## Data Safety Contracts

The implementation must preserve these contracts:

- Connection loss never clears a draft workspace.
- Connection loss never mutates `delivery`, `handoffBatchId`, `sentAt`, claim
  status, or resolve status.
- Save to MCP must not proceed from a stale preview without either recapture or
  explicit confirmation.
- Claim and resolve must verify that the target item still exists in the
  active session.
- Session switch with dirty draft must use a boundary decision.
- Late async responses must not mutate a newer session or newer workspace.
- Browser recovery schema-v2 remains the only browser-local draft recovery
  format.
- Persisted session JSON remains backward-compatible and additive only.

## Testing Strategy

Testing should make exception composition explicit rather than only testing
individual happy paths.

### Pure Workflow Tests

Add focused tests for `StudioWorkflow` decisions:

- ready + live preview + annotate => allow capture;
- blocked device + annotate => block with connection surface;
- stale preview + Save to MCP => confirm recapture or force-save;
- dirty draft + session switch => confirm;
- handoff, claim, or resolve in flight + session switch => block until the
  in-flight mutation completes or fails;
- late response generation mismatch => ignore;
- missing item + resolve => block;
- connection failed + dirty draft => preserve draft and mark preview stale.

### JS Contract Tests

Extend existing console test groups rather than inventing a large new harness
first:

- `console:reliability:test` for combined blocked, recovery, idempotency, and
  stale-session cases;
- `console:session:test` for session switch and closed-session behavior;
- `console:draft:test` for dirty draft, server conflict, recovery, and stale
  force-save boundaries;
- `console:preview:test` for app foreground/activity drift and stale preview
  display priority.

Do not add a dedicated `workflow:exceptions:test` script in the first
implementation slice. Keep the new cases inside the existing console test
groups so release and contributor guidance does not gain another command until
the workflow model proves it needs a separate gate.

### Browser Smoke

Browser smoke should cover high-value flows that need DOM integration:

- reconnect while draft exists;
- user opens another app while a preview is frozen;
- blocked device clears and select mode resumes;
- Save to MCP while stale preview is present;
- handoff in flight while session polling updates;
- claim or resolve after the item has changed or disappeared.

### Kotlin Route And Service Tests

Server-side tests should cover:

- stale save conflict response shape;
- claim or resolve for missing item;
- closed session mutation rejection or confirmation surface data;
- session mismatch response semantics;
- connection status raw diagnostics preservation.

## Rollout Strategy

This is a staged internal rewrite, not a big-bang UI replacement.

1. Add the pure `StudioWorkflow` model and tests. Initially compute decisions
   in parallel with existing guards and assert parity where possible.
2. Route annotate/capture and draft save through workflow decisions.
3. Route Copy Prompt and Save to MCP through workflow decisions.
4. Route session switch, claim, and resolve through workflow decisions.
5. Remove ad hoc guards that become redundant after test coverage proves the
   workflow decisions own the policy.
6. Update docs and troubleshooting to describe automatic recovery versus
   confirmation-required mutations.

Each stage should land with its own tests. Existing console UI should not be
visually rearranged as part of these stages.

## Risks And Mitigations

### Risk: Rewrite touches too many paths at once

Mitigation: introduce the workflow model in parallel first, then migrate one
mutation path at a time.

### Risk: UI regressions from centralized policy

Mitigation: keep rendering surfaces unchanged and assert display priority with
existing DOM tests before removing old guards.

### Risk: New model duplicates existing state

Mitigation: make `StudioWorkflow` a derived model over current connection,
workspace, operation, and risk signals. It should not become a second session
store.

### Risk: Confirmation fatigue

Mitigation: confirm only durable mutations under risk. Read-only recovery stays
automatic.

### Risk: Closed-session behavior surprises users

Mitigation: make the policy conservative and explicit. Closed sessions do not
accept new draft work, Save to MCP, claim, or resolve mutations. Users must
reopen the session or create a new active session before adding work.

## Acceptance Criteria

- Existing Studio layout and primary controls remain recognizable.
- There is one documented workflow policy for automatic recovery, blocking,
  confirmation, and ignored stale responses.
- Draft, handoff, claim, and resolve mutations pass through workflow decisions.
- Exception composition has deterministic tests.
- Connection churn preserves drafts, sent handoff batches, claim state,
  resolve state, and persisted evidence.
- Docs explain which recovery actions are automatic and which require explicit
  user confirmation.
