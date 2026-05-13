# Spec - Draft Workspace State Machine

Status: Draft for review
Date: 2026-05-14
Scope: `:fixthis-mcp` feedback console state, local pending persistence, undo/redo, session-bound HTTP mutations
Related specs:
- `2026-05-12-annotation-lifecycle-hardening-design.md`
- `2026-05-13-session-scoped-annotation-integrity-detailed-spec.md`
- `../../architecture/console-state-sync-design.md`

## Summary

The console has accumulated several hardening patches around annotation
creation, pending recovery, session-scoped requests, screen fingerprint checks,
and undo/redo. Those patches reduced specific failures, but they did not remove
the core design problem: annotation work is represented by several mutable
globals that can drift independently.

The fix is to introduce a single client-owned `DraftWorkspace` state machine.
All pending annotation edits, recovery, undo/redo, save/copy/handoff, and
session-boundary decisions go through a reducer and a serialized command queue.
The workspace captures the immutable session, preview, screen, fingerprint, and
device context at freeze time. Browser code may render from that workspace, but
must not mutate pending arrays or switch their session binding directly.

This is a console-side architectural cleanup, not a full product rewrite. MCP
session JSON compatibility remains intact, and the existing persisted fields
`items`, `screens`, `itemId`, `screenId`, `targetEvidence`, and
`sourceCandidates` are not renamed.

## Problem

The reported failures happen when users move between sessions and then create,
delete, edit, recover, copy, or save annotations. The failures have different
surface symptoms, but they share one cause:

- `state.session`, `addItemsFlow`, `pendingFeedbackItems`,
  `focusedSavedSessionId`, `undoRedoHistory`, localStorage recovery envelopes,
  and polling refresh responses are independent mutable sources of truth.
- Some code paths use the captured annotation context; other paths still fall
  back to the current active session.
- Async responses can land after a session switch and update UI state that no
  longer represents the request that created them.
- Undo/redo and recovery are partially scoped, but their storage key and restore
  behavior still depend on ambient state in places.

The correct model is not "the current session has pending annotations." The
correct model is "a draft workspace owns pending annotations for one immutable
session-preview-screen context."

## Goals

- A pending annotation can only be saved, copied, deleted, recovered, or undone
  inside the workspace context that created it.
- Session switch, new session, close, delete, reload, and recovery all use one
  boundary protocol.
- Concurrent UI commands are serialized so save/delete/session-switch races have
  deterministic results.
- The browser console never sends a mutation without an explicit `sessionId`
  when a workspace or saved item context exists.
- Stale async responses are ignored unless their workspace id and revision still
  match current state.
- Tests assert invariants over randomized annotation/session workflows instead
  of only checking individual regressions.

## Non-Goals

- Multi-user collaboration.
- CRDT or optimistic remote merge.
- External synchronization.
- Android bridge protocol major changes.
- Replacing the existing feedback session event log.
- Renaming persisted MCP session JSON fields.

## Architecture Boundaries

The implementation follows a small Clean Architecture slice inside
`:fixthis-mcp` console code. The goal is not ceremony; the goal is to stop DOM,
fetch, localStorage, polling, and draft business rules from mutating the same
state directly.

Layer order:

1. **Domain policy**: pure workspace model, draft items, context, actions,
   reducer, and invariant helpers.
2. **Application use cases**: freeze, add/update/delete, undo/redo, save, copy,
   handoff, recovery, and session-boundary orchestration.
3. **Ports**: small interfaces for preview capture, feedback API calls,
   workspace storage, clock/id generation, clipboard writes, and session
   refresh.
4. **Adapters**: browser `fetch`, `localStorage`, `navigator.clipboard`,
   existing console endpoints, and current render/refresh functions.
5. **Presentation**: DOM event handlers and rendering modules. They translate
   user events into use case calls and render view models from domain state.

Dependency rule:

- Domain policy imports nothing from application, adapters, DOM, storage, or
  network code.
- Application use cases depend only on domain policy and ports.
- Adapters depend outward on browser APIs and existing console modules.
- Presentation depends on application use cases and read-only view models, not
  on storage keys or HTTP payload construction.

This prevents a repeat of the current failure mode where `state.session`,
`addItemsFlow`, `pendingFeedbackItems`, localStorage, and HTTP responses each
act as partial sources of truth.

## SOLID Mapping

- **Single Responsibility**: `draftWorkspace.js` owns pure state transitions;
  `draftUseCases.js` owns workflows; `draftCommandQueue.js` owns ordering;
  `draftStorageAdapter.js` owns localStorage; `draftApiAdapter.js` owns HTTP
  shapes; rendering modules only render.
- **Open/Closed**: new workspace behavior is added as a new action or use case
  without letting UI modules mutate workspace internals directly.
- **Liskov Substitution**: each port has fake and browser implementations with
  the same behavior contract, so reducer/use case tests can replace adapters
  without changing application code.
- **Interface Segregation**: ports stay narrow. For example, a save use case
  receives `feedbackApi.saveDraftWorkspace`, not a broad console service object
  with unrelated session, device, and rendering methods.
- **Dependency Inversion**: use cases depend on abstract ports. Browser `fetch`,
  localStorage, clipboard, and session refresh are injected adapters.

## File Ownership

Planned console modules:

- `draftWorkspace.js`: pure domain state, action constants, reducer, selectors,
  and invariant helpers.
- `draftWorkspaceHistory.js`: pure undo/redo stack operations if history becomes
  large enough to split from `draftWorkspace.js`.
- `draftUseCases.js`: application workflows such as `startDraftFreeze`,
  `addDraftItem`, `updateDraftItem`, `deleteDraftItem`, `persistDraftWorkspace`,
  `copyDraftHandoff`, `saveDraftToMcp`, and `resolveDraftBoundary`.
- `draftCommandQueue.js`: one-at-a-time command serialization and stale
  workspace/revision response fencing. It must not contain annotation business
  rules.
- `draftPorts.js`: documented port shapes using JSDoc comments.
- `draftStorageAdapter.js`: schema-v2 localStorage read/write plus schema-v1
  migration.
- `draftApiAdapter.js`: explicit-session HTTP payload construction for
  annotation-owned routes.
- Existing `annotations.js`, `prompt.js`, `history.js`, `preview.js`, and
  `rendering.js`: presentation and compatibility glue during migration. Direct
  writes to workspace internals are removed before the migration is complete.

## Core Model

Add a small client-side state module, `draftWorkspace.js`, that owns this shape:

```js
const workspace = {
  workspaceId: "uuid-or-local-monotonic-id",
  revision: 0,
  lifecycle: "empty" | "capturing" | "editing" | "saving" | "recovering" | "conflict" | "closed",
  context: {
    sessionId,
    previewId,
    screenId,
    screenFingerprint,
    deviceSerial,
    frozenAtEpochMillis,
    activityName
  },
  screen,
  screenshotUrl,
  items: [],
  history: {
    undoStack: [],
    redoStack: []
  },
  focusedItemId: null,
  currentSelection: null,
  activityDriftWarning: null,
  lastError: null
};
```

`items` are browser draft items before persistence. Each draft item gets a local
stable id (`draftItemId`) at creation time. Persisted server ids remain separate
and are attached only after save. UI ordering and undo/redo use `draftItemId`,
not array index.

The existing globals become derived compatibility shims during migration:

- `addItemsFlow` is replaced by `workspace.lifecycle !== "empty"`.
- `pendingFeedbackItems` is replaced by `workspace.items`.
- `focusedPendingItemIndex` is replaced by `workspace.focusedItemId`.
- `currentSelection` moves into the workspace.
- `undoRedoHistory` becomes `workspace.history`.

## Reducer

All workspace changes go through:

```js
function reduceDraftWorkspace(workspace, action) {
  switch (action.type) {
    case "FREEZE_STARTED":
    case "FREEZE_SUCCEEDED":
    case "ADD_ITEM":
    case "UPDATE_ITEM":
    case "DELETE_ITEM":
    case "FOCUS_ITEM":
    case "CLEAR_FOCUS":
    case "RESTORE_RECOVERY":
    case "RECOVER_WITH_RECAPTURE":
    case "SAVE_STARTED":
    case "SAVE_SUCCEEDED":
    case "SAVE_CONFLICT":
    case "SAVE_FAILED":
    case "DISCARD":
    case "SESSION_BOUNDARY_CLOSED":
      return nextWorkspace;
  }
}
```

Reducer rules:

- Every successful user-visible mutation increments `revision`.
- Actions carrying `workspaceId` must match the current workspace or be ignored.
- Actions carrying `expectedRevision` must match when they represent async
  responses.
- `SAVE_STARTED` moves lifecycle to `saving`; add/update/delete/session boundary
  commands cannot mutate items until it resolves or is cancelled.
- `SAVE_SUCCEEDED` clears only the matching workspace and mirror key.
- `SAVE_CONFLICT` keeps the workspace intact and exposes recapture, force-save,
  or cancel choices.

## Application Use Cases and Command Queue

The command queue is only an ordering primitive. It must not become a new
god-object that knows annotation rules, storage schema, HTTP payloads, and DOM
rendering. Business behavior lives in application use cases that receive ports.

Use case examples:

```js
async function persistDraftWorkspace(workspace, ports) {
  const request = buildPersistRequest(workspace);
  const result = await ports.feedbackApi.saveDraftWorkspace(request);
  return result;
}

async function resolveDraftBoundary(workspace, boundaryAction, ports) {
  const choice = await ports.boundaryPrompt.choose(workspace, boundaryAction);
  return applyBoundaryChoice(workspace, choice, ports);
}
```

Introduce a single console command runner:

```js
enqueueCommand({ kind, workspaceId, expectedRevision }, async () => {
  // invoke one application use case
});
```

Rules:

- Mutating commands run one at a time.
- Commands that target a workspace verify `workspaceId` and `revision` before
  and after awaiting network work.
- Session boundary commands first call `resolveDraftBoundary(workspace, action)`.
- Polling and refresh may update saved session data, but they cannot mutate an
  active workspace.
- If a command completes for an old workspace, the response is discarded and a
  fresh session refresh is scheduled.

This replaces scattered uses of `pendingMutationCount`,
`sessionMutationGeneration`, and ad hoc stale-response checks for annotation
flows. Those variables may remain for unrelated connection/polling behavior
during migration, but workspace commands do not depend on them.

Use cases should return data, not mutate DOM. Presentation code decides which
regions to re-render after a successful command.

## Persistence

Pending recovery is handled by a storage port:

```js
const draftStoragePort = {
  saveWorkspace(envelope) {},
  loadWorkspacesForSession(sessionId) {},
  deleteWorkspace(sessionId, workspaceId) {},
  migrateLegacyPending(sessionId) {}
};
```

The browser adapter stores workspaces under workspace-keyed localStorage keys:

```text
localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]
localStorage["fixthis.workspace.index.<sessionId>"]
```

The value is a schema-v2 envelope:

```js
{
  schemaVersion: 2,
  workspaceId,
  revision,
  lifecycle,
  context,
  screen,
  screenshotUrl,
  items,
  history,
  updatedAtEpochMillis
}
```

Migration:

- Existing schema-v1 `fixthis.pending.<sessionId>` envelopes are read as legacy
  recoveries and immediately wrapped in a new workspace after explicit user
  choice.
- Schema-v0 item-only arrays still require recapture or discard.
- The console must never auto-restore a workspace into a different active
  session. Recovery UI is shown only after matching the envelope context to the
  selected session.

## Server Contract

The application layer must reject annotation-owned server commands without an
explicit `sessionId`. The browser console must send explicit session ids for
all annotation-owned mutations:

- `POST /api/items/batch`
- `POST /api/items`
- `PUT /api/items/:itemId`
- `DELETE /api/items/:itemId`
- `DELETE /api/items/draft`
- `POST /api/agent-handoffs`
- `POST /api/sessions/:sessionId/handoff-preview`
- `POST /api/sessions/:sessionId/items/mark-handed-off`
- `GET /api/preview/:previewId/screenshot/full?sessionId=...`
- `GET /api/screens/:screenId/screenshot/full?sessionId=...`
- `DELETE /api/screens/:screenId?sessionId=...`

Legacy current-session fallback can remain for non-console or old callers for
one release cycle. Console JS should treat missing explicit context as a local
programming error and ask the user to recapture rather than relying on fallback.

Optional follow-up for stronger protection: add `workspaceId` and
`clientRevision` to console-only request bodies and log them in event metadata.
The server does not need to persist those fields in public MCP JSON.

## Session Boundary Behavior

Every boundary action uses the same decision function:

```js
resolveDraftBoundary(workspace, boundaryAction)
```

Outcomes:

- `save`: persist draft items with blank comments allowed, then continue.
- `keep`: leave the workspace recoverable and switch sessions.
- `discard`: clear only this workspace and its mirror key, then continue.
- `cancel`: keep editing.

Close and delete are no longer special. They use the same protocol as open and
new session. If the boundary targets a different session than the workspace,
the workspace is preserved in its original session recovery index instead of
being silently cleared.

## Rendering

Rendering is derived from either an active workspace or a persisted session.

- Presentation receives a view model from selectors; it does not inspect
  localStorage, build HTTP payloads, or mutate `workspace.items` directly.
- Pending overlays render from `workspace.items`.
- Saved overlays render only for the focused saved item and its persisted
  screen, never from live-preview node UID coincidence.
- Pending marker labels use stable local order within the workspace.
- Persisted marker labels use `sequenceNumber`; array-index fallback is allowed
  only for legacy JSON that lacks sequence numbers and should be visually marked
  as legacy in tests, not treated as normal behavior.

## Error Handling

- Missing workspace context: local error, prompt recapture.
- Session not found: keep workspace recoverable and refresh session list.
- Preview not found: offer recapture or discard; do not save against current
  session fallback.
- Fingerprint mismatch: keep existing recapture, force-save, cancel choices.
- Command rejected because workspace changed: discard response and refresh saved
  state without changing the active workspace.
- Storage quota/localStorage failure: show warning but keep in-memory workspace.

## Testing

Add focused reducer tests:

- add/update/delete preserves item ids and increments revision.
- stale async action with old revision is ignored.
- save conflict preserves workspace and items.
- session boundary keep/discard/save affects only the matching workspace.
- domain tests run without DOM, fetch, localStorage, timers, or global
  `state.session`.

Add command queue tests:

- save and session switch are serialized.
- stale save response after session switch cannot clear the new workspace.
- delete followed by undo during save is blocked or queued deterministically.
- queue tests use fake use cases and prove the queue contains no business
  branching beyond ordering and stale-response rejection.

Add storage tests:

- schema-v1 pending recovery migrates into schema-v2 workspace.
- schema-v0 item-only recovery requires recapture.
- workspaces are keyed by captured session, not active session.

Add port/adapter tests:

- `draftApiAdapter` emits explicit `sessionId` on every annotation-owned route.
- fake ports can replace browser adapters for all use case tests.
- presentation smoke tests assert DOM handlers call use cases instead of
  mutating workspace arrays directly.

Add property-style workflow tests with a deterministic pseudo-random runner:

- Generate operations: freeze, add item, edit item, delete item, undo, redo,
  switch session, close session, reload, recover, save, copy, handoff, poll
  refresh.
- After every step assert invariants:
  - no item crosses into a session different from its captured context;
  - no save without explicit session id;
  - no cleared workspace unless save/discard succeeded for that workspace;
  - no duplicate persisted sequence number within a session;
  - no stale async response changes a newer workspace.

Add a browser smoke scenario:

- Freeze session A, add two annotations, start Save to MCP.
- Before response returns, switch to session B and start a new annotation.
- Let session A save response finish.
- Assert session A has only A items, session B workspace remains intact, and
  the visible console is still bound to B.

## Migration Plan

1. Add pure `draftWorkspace.js` reducer and tests without wiring UI.
2. Add `draftPorts.js` with narrow port contracts and fake test
   implementations.
3. Add `draftUseCases.js` for freeze, edit, save, handoff, recovery, and
   boundary workflows.
4. Add `draftCommandQueue.js` and prove it only serializes use cases and fences
   stale workspace revisions.
5. Move localStorage recovery to `draftStorageAdapter.js` schema v2 and migrate
   schema v1.
6. Route pending item add/update/delete through use cases while keeping
   compatibility shims.
7. Route save/copy/handoff through workspace commands and explicit session ids.
8. Route session boundary actions through `resolveDraftBoundary`.
9. Remove direct writes to `pendingFeedbackItems`, `focusedPendingItemIndex`,
   and `addItemsFlow` from console modules.
10. Add randomized invariant tests and browser smoke.
11. Regenerate `fixthis-mcp/src/main/resources/console/app.js`.

## Decisions

- `keep` behavior is a first-class boundary choice. Preserving a workspace while
  switching sessions is safer than forcing save or discard. The implementation
  should label it as "Keep draft in this session" or equivalent concise copy.
- Server-side `workspaceId` metadata is deferred for the first implementation
  pass. Client-side command fencing plus explicit `sessionId` is the correctness
  boundary; server metadata can be added later for diagnostics.
- Clean Architecture boundaries are required for implementation acceptance:
  reducer tests must run without browser APIs, use case tests must use fake
  ports, and presentation modules must not directly mutate workspace arrays.
- SSE remains a later state-sync improvement. The workspace model is required
  regardless of polling or SSE.
