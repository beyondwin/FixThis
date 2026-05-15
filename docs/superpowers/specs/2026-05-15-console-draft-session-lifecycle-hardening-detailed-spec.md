# Spec - Console Draft and Session Lifecycle Hardening

Status: Draft for review
Date: 2026-05-15
Scope: `:fixthis-mcp` browser feedback console JavaScript, draft recovery localStorage, console HTTP item/session routes, console regression tests
Related specs:
- [`2026-05-14-draft-workspace-state-machine-design.md`](2026-05-14-draft-workspace-state-machine-design.md)
- [`2026-05-15-console-canonical-single-source-detailed-spec.md`](2026-05-15-console-canonical-single-source-detailed-spec.md)
- [`2026-05-15-console-js-reliability-stabilization-detailed-spec.md`](2026-05-15-console-js-reliability-stabilization-detailed-spec.md)
- [`2026-05-13-session-scoped-annotation-integrity-detailed-spec.md`](2026-05-13-session-scoped-annotation-integrity-detailed-spec.md)
- [`../../reference/feedback-console-contract.md`](../../reference/feedback-console-contract.md)

## Summary

The feedback console has recurring failures around session creation, session
deletion, annotation add/delete/edit, draft save, draft recovery, and local
draft removal. The recent stale node fix prevents one server validation error,
but the broader bug class remains: lifecycle operations are still split across
multiple mutable state owners and multiple command paths.

This spec defines the structural fix. The console must have one lifecycle
protocol for session changes and one reducer-owned draft workspace for all local
annotation edits. Every operation that can save, discard, recover, switch,
delete, or hand off draft work must pass through an explicit boundary decision
with immutable `sessionId`, `previewId`, `screenId`, `workspaceId`, and
`revision` context.

The goal is not another narrow guard. The goal is to make the wrong behavior
hard to express.

## Problem Statement

The same user-visible problem appears in several forms:

- Pressing `+` from history can show a Save draft prompt and fail before a new
  session is created.
- Saving a recovered or stale node draft can hit server validation such as
  `Selected node does not exist on preview`.
- Annotation detail edits mutate draft items without advancing revision, so an
  older save response can clear newer local edits.
- Pending recovery can be visible but not actually block session navigation,
  new session creation, delete, or annotate flows.
- "Clear draft" mixes server draft item deletion with local workspace cleanup.
- Canonical reducer effects and legacy direct mutation paths can both execute
  for the same click, causing session and preview drift.

These are not independent defects. They come from the same design failure:
session and draft lifecycle ownership is not singular.

## Root Cause

The console currently has these overlapping authorities:

- visible session state in legacy `state.session`;
- session summaries in `state.sessionSummaries`;
- current preview state in legacy `state.preview`;
- active local draft in `draftWorkspace`;
- compatibility draft projections such as `draftFlowState`,
  `draftPinsState`, `draftFocusIndexState`, and `draftSelectionState`;
- pending recovery in `pendingRecovery`;
- canonical `ConsoleAppState` and `consoleStore`;
- localStorage schema v2 workspaces and schema v1 pending mirrors;
- server persisted `SessionDto` items and screens.

Several event handlers dispatch canonical commands and then continue through
the older direct mutation flow. Some code updates draft items through the
domain reducer, while other code mutates item objects directly. Some boundary
paths block on user choice; pending recovery paths only show a banner and then
continue. These differences let state drift across session, preview, and draft
ownership boundaries.

## Goals

- One visible source of truth for the active session, visible workspace,
  selected annotation, prompt readiness, and boundary state.
- One write path for draft item add, edit, delete, focus, undo, redo, save,
  recovery, recapture, and discard.
- Every draft edit increments workspace revision.
- Every async save response is fenced by the workspace id and the revision that
  produced the request.
- Every session lifecycle action resolves active draft and pending recovery
  through the same boundary protocol before making server calls.
- Browser code never sends item/session mutations without explicit context when
  a workspace or saved item context exists.
- Local recovery storage has one authoritative schema and one delete path.
- Server validation remains strict, but recoverable target problems are
  normalized before save or returned as typed client-actionable errors.
- Tests encode these invariants so future changes cannot reintroduce direct
  mutation, duplicate side effects, or ambient session fallback.

## Non-Goals

- Changing MCP tool signatures.
- Renaming persisted MCP JSON fields. The compatibility fields `items`,
  `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, and `sourceCandidates` remain unchanged.
- Rewriting Android bridge protocol.
- Adding cloud synchronization or multi-user collaboration.
- Replacing the browser bundle system with TypeScript or ESM imports in this
  lifecycle hardening pass.
- Redesigning the visual layout of the feedback console.

## Definitions

### Active Session

The session currently selected in the console. Session state must be addressed
by `sessionId`, not by relying on "whatever `/api/session` currently returns"
inside item or draft mutations.

### Draft Workspace

A client-owned local annotation workspace captured from one immutable
session-preview-screen context.

Required context:

```js
{
  sessionId: "session-a",
  previewId: "preview-a",
  screenId: "screen-a",
  screenFingerprint: "fingerprint-or-null",
  deviceSerial: "device-or-null",
  frozenAtEpochMillis: 1778572260000,
  activityName: "MainActivity",
  workspaceId: "workspace-a"
}
```

The context is immutable. A draft cannot be moved to another session or preview.
It can only be saved, kept as recovery, discarded, or recaptured into a new
workspace.

### Pending Recovery

A local draft workspace stored in browser storage that is not currently active
in the visible editor. It has the same lifecycle importance as an active draft:
it must be resolved before destructive or context-changing actions.

### Boundary

A blocking decision point that prevents session or workspace changes until the
user chooses how to handle active draft or pending recovery data.

Boundary actions:

```js
"open-session"
"new-session"
"close-session"
"delete-session"
"annotate"
"clear-local-draft"
"clear-server-drafts"
```

Boundary choices:

```js
"save"
"keep"
"discard"
"resume"
"recapture"
"clear"
"cancel"
```

## Required Architecture

### Single Runtime Owner

The long-term owner is `ConsoleAppState`, as defined by the canonical single
source spec. During incremental implementation, the browser may temporarily
keep legacy projection helpers, but only one owner may perform side effects for
a user command.

Allowed:

- DOM handler dispatches one canonical command and returns.
- DOM handler invokes one legacy workflow while canonical side effects for that
  command are disabled.

Forbidden:

- DOM handler dispatches a canonical command and then also directly calls
  `requestJson`, clears preview, resets draft state, or starts polling for the
  same user action.

Concrete current risk:

- `openSession(sessionId)` dispatches `SESSION_ROW_CLICKED` before running the
  legacy session switch flow.
- `requestCanonicalPreviewCapture()` dispatches `ANNOTATE_CLICKED` before the
  legacy draft freeze path captures preview.
- `SAVE_TO_MCP_CLICKED` can generate canonical save effects while the legacy
  save path also persists the draft.

Implementation must choose one path per command and add static tests that fail
when both paths are present.

### Draft Workspace Reducer Ownership

All draft item changes go through the reducer in `draftWorkspace.js` and the
use cases in `draftUseCases.js`.

Allowed write paths:

```js
addDraftItem(workspace, selection, ports)
updateDraftItem(workspace, draftItemId, patch)
deleteDraftItem(workspace, draftItemId)
reduceDraftWorkspace(workspace, action)
```

Forbidden production patterns outside domain tests:

```js
item.comment = value
item.label = value
item.severity = value
item.status = value
draftWorkspace.items.push(item)
draftWorkspace.items.splice(index, 1)
draftPinsState = items
draftItemList()[index].comment = value
```

Every edit must:

- produce a new workspace object;
- increment `revision`;
- preserve immutable `context`;
- persist the schema v2 recovery envelope when there are local items;
- delete the matching schema v2 recovery envelope when the item list becomes
  empty;
- update undo/redo history where the user would expect undo.

### Lifecycle Boundary Coordinator

Create a single application-level resolver for active draft plus pending
recovery:

```js
async function resolveLifecycleBoundary({
  action,
  targetSessionId = null,
  activeWorkspace,
  pendingRecovery,
  ports,
})
```

It returns:

```js
{
  outcome: "continue" | "cancel",
  nextWorkspace,
  nextPendingRecovery,
  savedSession,
  targetSessionId
}
```

This replaces ad hoc checks in `history.js`, `main.js`, `annotations.js`, and
`prompt.js`.

Rules:

- If no active workspace and no pending recovery exists, return `continue`.
- If active workspace has only pin-only items and the action is not destructive,
  save it to recovery and continue.
- If active workspace has comments, prompt with save, keep, discard, or cancel.
- If pending recovery has comments, prompt with resume, recapture, clear, or
  cancel before continuing.
- If deleting a session with any local recovery for that session, prompt before
  deleting local storage.
- If switching away from a session with active draft, never silently save to
  server when target session differs. Store as recovery unless the user
  explicitly chooses save.
- If saving fails with a typed conflict, keep the workspace active and return
  `cancel`.
- If a boundary returns `cancel`, no server session mutation may be sent.

## Required Invariants

### I1 - Explicit Context

Every mutation involving a workspace or saved item includes explicit context.

Required fields by operation:

- draft save: `sessionId`, `previewId`, `workspaceId`, `items`,
  `frozenFingerprint`;
- saved item update/delete: `sessionId`, `itemId`;
- screenshot URLs: `sessionId`, `previewId` or `screenId`;
- handoff preview: `sessionId`, `itemIds`;
- mark handed off: `sessionId`, `itemIds`;
- session delete: `sessionId`;
- local recovery delete: `sessionId`, `workspaceId`.

Ambient fallback to current session is allowed only for initial console boot and
manual refresh. It is not allowed for item or draft mutation.

### I2 - Revision Fencing

Each draft mutation increments `workspace.revision`. A save request records:

```js
{
  workspaceId,
  requestRevision
}
```

The response may clear or replace the current workspace only if:

```js
current.workspaceId === workspaceId &&
current.revision === requestRevision
```

If the user edits while save is in flight, the save response is stale. The
server may persist the older item set, but the browser must not clear the newer
local workspace. It should show a non-blocking message that an older save
completed while newer edits remain local.

### I3 - Recovery Blocks Context Changes

Pending recovery with any items must participate in boundary resolution.
Displaying a recovery banner is not a substitute for a blocking boundary.

Actions that must resolve pending recovery first:

- open another session;
- start a new session;
- close the active session;
- delete any session that owns the recovery;
- enter annotate mode;
- clear local draft;
- clear server drafts.

### I4 - One Storage Namespace

Schema v2 local draft workspaces use:

```js
fixthis.workspace.<sessionId>.<workspaceId>
fixthis.workspace.index.<sessionId>
```

Schema v1 pending mirror keys are migration-only:

```js
fixthis.pending.<sessionId>
```

No production code may write new recovery state to unrelated namespaces such as
`fixthis.recovery.*` or `fixthis.draftWorkspace.*`.

### I5 - Clear Actions Are Explicit

There are two separate commands:

- `CLEAR_LOCAL_DRAFT_CLICKED`: removes active local draft workspace and matching
  local recovery storage only.
- `CLEAR_SERVER_DRAFTS_CLICKED`: deletes persisted server items whose delivery
  is `draft` for the explicit session.

The UI text and confirmation copy must name which one will happen. A single
"Clear draft" button must not mix both semantics.

### I6 - Target Validation Is Client-Normalized And Server-Strict

The server remains strict: a `targetType: "node"` item with a missing `nodeUid`
or a `nodeUid` absent from the frozen preview is invalid.

The browser save adapter must normalize known stale recovered node drafts before
calling `/api/items/batch`:

- if `targetType === "node"`;
- and `nodeUid` is not present in `workspace.screen.roots`;
- and item bounds are valid;
- then send it as `targetType: "area"` with the same bounds, label, severity,
  status, and comment.

If bounds are invalid, keep the draft local and return a typed client error
instead of sending a request that will fail generically.

### I7 - Typed Server Errors

Console HTTP routes should return JSON errors with stable codes for client
recovery decisions:

```json
{
  "error": "selected_node_missing",
  "message": "Selected node does not exist on preview: compose:0:merged:73",
  "action": "recapture_or_convert_to_area"
}
```

Required codes:

- `selected_node_missing`;
- `invalid_selection_bounds`;
- `preview_not_found`;
- `preview_save_in_progress`;
- `screen_fingerprint_mismatch`;
- `unknown_feedback_session`;
- `unknown_feedback_item`;
- `item_not_editable`;
- `no_draft_feedback`.

The browser should branch on `error`, not parse English text.

### I8 - Tests Use Production-Like Module Loading

New JS tests should use the shared console test loader when possible, or at
least include the same dependencies and order used by the bundle. Regex-only
tests are allowed for guardrails, but each user-visible lifecycle bug class
needs at least one behavioral test.

## File Ownership

### Browser Console Domain

- `fixthis-mcp/src/main/console/draftWorkspace.js`
  - Owns draft state shape, reducer, revision policy, immutable context checks,
    and item selectors.

- `fixthis-mcp/src/main/console/draftWorkspaceHistory.js`
  - Owns undo/redo stack mechanics only.

- `fixthis-mcp/src/main/console/draftUseCases.js`
  - Owns freeze, add, update, delete, persist, boundary resolution, recovery
    envelope creation, recovery restore, and recapture workflows.

- `fixthis-mcp/src/main/console/draftCommandQueue.js`
  - Owns serialization and stale response fencing. It must not contain UI copy,
    DOM access, or annotation business rules.

- `fixthis-mcp/src/main/console/draftStorageAdapter.js`
  - Owns schema v2 storage and schema v1 migration. It must be the only module
    that builds `fixthis.workspace.*` keys.

- `fixthis-mcp/src/main/console/draftApiAdapter.js`
  - Owns `/api/items/batch` request construction, target normalization, and
    typed fetch error decoding for draft save.

### Browser Console Presentation

- `fixthis-mcp/src/main/console/annotations.js`
  - Handles DOM events and rendering hooks only. It must call use cases rather
    than mutating draft item objects directly.

- `fixthis-mcp/src/main/console/history.js`
  - Handles history list interactions only. It must call the shared lifecycle
    boundary resolver before any session open, new, close, or delete effect.

- `fixthis-mcp/src/main/console/main.js`
  - Owns boot wiring and high-level event registration. It must not contain
    boundary business rules once the resolver is extracted.

- `fixthis-mcp/src/main/console/prompt.js`
  - Owns prompt, copy, handoff, and mark-handed-off workflows. It must not clear
    local draft state unless the save/handoff operation is fenced and current.

- `fixthis-mcp/src/main/console/adapters/browserPorts.js`
  - Must either match real production endpoints and storage namespaces or be
    disabled for commands still served by legacy workflows. It may not contain
    dead endpoints such as `/api/feedback/items`.

### Server

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
  - Maps draft validation failures to typed HTTP errors.

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`
  - Keeps strict validation and idempotent save behavior.

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
  - Remains the source of truth for node and bounds validation.

## Lifecycle Flows

### Start New Session With Active Commented Draft

1. User clicks `+`.
2. UI emits one command: `NEW_SESSION_CLICKED`.
3. Boundary resolver sees active workspace with commented items.
4. Prompt offers Save, Keep local, Discard, Cancel.
5. Save path:
   - flush focused detail fields through `updateDraftItem`;
   - enqueue save with `workspaceId` and `requestRevision`;
   - call `/api/items/batch` with explicit context;
   - if response matches current workspace id and revision, clear local
     workspace and delete local recovery;
   - open new session;
   - refresh summaries.
6. Keep path:
   - write schema v2 recovery envelope;
   - clear active workspace projection;
   - open new session.
7. Discard path:
   - delete active workspace storage;
   - clear active workspace;
   - open new session.
8. Cancel path:
   - no server session mutation;
   - keep workspace active.

### Start New Session With Pending Recovery Only

1. User clicks `+`.
2. Boundary resolver sees pending recovery.
3. Prompt offers Resume, Recapture, Clear local draft, Cancel.
4. Resume path:
   - restore recovery into active workspace;
   - do not open a new session yet;
   - user can then save, keep, discard, or cancel through the active draft
     boundary.
5. Recapture path:
   - capture current preview for the recovery's session;
   - convert recovered items into a new workspace with a new context;
   - keep user in that workspace.
6. Clear path:
   - delete the recovery workspace and legacy pending mirror for that session;
   - continue with new session.
7. Cancel path:
   - no server session mutation.

### Edit Pending Annotation Detail

1. User edits label, comment, severity, or status.
2. UI dispatches `UPDATE_ITEM` through `updateDraftItem`.
3. Workspace revision increments.
4. Local recovery envelope is rewritten.
5. Prompt readiness and overlay render from the new workspace.
6. No direct mutation of the existing item object occurs.

### Save While Editing

1. User starts save at revision `R`.
2. Save request carries `workspaceId` and request revision `R`.
3. User edits comment before save returns; workspace becomes revision `R + 1`.
4. Save response returns.
5. Queue checks current `workspaceId` and revision.
6. Since revision no longer matches, response is stale:
   - do not clear workspace;
   - do refresh server summaries;
   - show a non-blocking status that older draft was saved and newer edits
     remain local.

### Delete Session With Local Recovery

1. User clicks delete on a session row.
2. Boundary resolver checks active workspace and stored recovery for the target
   session id.
3. If local recovery exists, prompt before any server close/delete request.
4. Discard/Clear deletes local recovery and then closes the session.
5. Cancel leaves server session and local recovery unchanged.

### Clear Local Draft

1. User chooses "Clear local draft".
2. Confirm copy says local unsaved pins/comments will be removed from this
   browser only.
3. Delete active workspace storage and legacy pending mirror for the explicit
   session.
4. Clear active workspace.
5. Do not call `/api/items/draft`.

### Clear Server Drafts

1. User chooses "Clear saved draft items".
2. Confirm copy says persisted draft feedback items in the selected session
   will be removed.
3. Resolve local boundary first if active workspace or pending recovery exists.
4. Call `DELETE /api/items/draft?sessionId=<explicit>`.
5. Refresh session detail and summaries.

## API Contract

### Draft Batch Save Request

Endpoint: `POST /api/items/batch`

Request:

```json
{
  "sessionId": "session-a",
  "previewId": "preview-a",
  "workspaceId": "workspace-a",
  "screen": { "screenId": "screen-a" },
  "items": [
    {
      "draftItemId": "draft-1",
      "targetType": "area",
      "bounds": { "left": 10, "top": 20, "right": 100, "bottom": 140 },
      "nodeUid": null,
      "label": "Metric card",
      "severity": "med",
      "status": "open",
      "comment": "Make this easier to scan"
    }
  ],
  "frozenFingerprint": "fingerprint-or-null",
  "forceMismatchOverride": false
}
```

Success response remains `SessionDto`.

Conflict response:

```json
{
  "error": "screen_fingerprint_mismatch",
  "frozenFingerprint": "frozen",
  "currentFingerprint": "current"
}
```

Validation response:

```json
{
  "error": "selected_node_missing",
  "message": "Selected node does not exist on preview: compose:0:merged:73",
  "action": "recapture_or_convert_to_area"
}
```

### Saved Item Update

Endpoint: `PUT /api/items/<itemId>`

Request must include `sessionId`. Browser callers must not rely on the server's
current session fallback.

```json
{
  "sessionId": "session-a",
  "label": "CTA",
  "severity": "med",
  "comment": "Update button label",
  "status": "open"
}
```

### Saved Item Delete

Endpoint: `DELETE /api/items/<itemId>?sessionId=<sessionId>`

The UI must keep the focused saved context until the response returns and then
select a deterministic fallback item or screen from that same session.

## Required Tests

### JS Unit Tests

Add or update these tests:

- `scripts/draftWorkspace-test.mjs`
  - `UPDATE_ITEM` increments revision.
  - `UPDATE_ITEM` preserves immutable context.
  - deleting the last item returns an empty item list and clears focus.

- `scripts/draftCommandQueue-test.mjs`
  - save response at revision `R` does not clear workspace after a local edit
    advances to `R + 1`.
  - stale response refresh hook runs, but `setWorkspace` is not called with
    empty workspace.

- `scripts/draftUseCases-test.mjs`
  - lifecycle boundary blocks new session when pending recovery has comments.
  - lifecycle boundary deletes local recovery only after explicit clear choice.
  - save conflict returns `cancel` and keeps active workspace.

- `scripts/draftApiAdapter-test.mjs`
  - stale node draft converts to area before save.
  - invalid area bounds are rejected client-side.
  - typed validation response is decoded into an error object with `error`.

- `scripts/pendingBoundaryGuard-test.mjs`
  - `newSession`, `openSession`, `closeSession`, `deleteHistorySession`, and
    `enterAnnotateMode` all call the same lifecycle boundary resolver.
  - pending recovery path no longer returns `continue` just because active
    workspace is empty.

- `scripts/consoleCanonicalRuntimeContract-test.mjs`
  - production console modules do not contain direct draft item mutations.
  - production console modules do not dispatch canonical command and run legacy
    side effects for the same user action.
  - `browserPorts.js` does not reference nonexistent endpoints or obsolete
    recovery namespaces.

- `scripts/sessionScopedRequests-test.mjs`
  - saved item update and delete always carry explicit `sessionId`.
  - screenshot URLs for saved, preview, and draft screens include explicit
    `sessionId`.

### Browser Smoke Tests

Add a scenario to `scripts/console-browser-smoke.mjs` or the console harness:

1. Create a draft annotation.
2. Edit its comment.
3. Trigger save and edit again before the save resolves.
4. Resolve save response.
5. Assert the newer local edit remains visible and recoverable.

Add a second scenario:

1. Store pending recovery for Session 1.
2. Open console on Session 2.
3. Click delete for Session 1.
4. Assert boundary blocks deletion until clear or cancel.

### Kotlin Tests

Add or update:

- `ConsoleFeedbackItemRoutesTest`
  - validation exceptions from target evidence return typed 400 JSON.
  - `screen_fingerprint_mismatch` remains 409 JSON.

- `FeedbackDraftServiceTest`
  - stale node as `targetType: NODE` remains invalid server-side.
  - area fallback with same bounds is accepted.
  - duplicate client draft keys remain idempotent.

## Verification Commands

Run focused JS tests:

```bash
node --test \
  scripts/draftWorkspace-test.mjs \
  scripts/draftCommandQueue-test.mjs \
  scripts/draftUseCases-test.mjs \
  scripts/draftApiAdapter-test.mjs \
  scripts/pendingBoundaryGuard-test.mjs \
  scripts/consoleCanonicalRuntimeContract-test.mjs \
  scripts/sessionScopedRequests-test.mjs
```

Run broader console draft tests:

```bash
npm run console:draft:test
```

Check bundle reproducibility:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Run focused Kotlin console tests:

```bash
./gradlew :fixthis-mcp:test \
  --tests 'io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest' \
  --tests 'io.beyondwin.fixthis.mcp.session.FeedbackDraftServiceTest'
```

Run whitespace check:

```bash
git diff --check
```

## Acceptance Criteria

- Pressing `+` with an active commented draft never changes session until the
  boundary choice completes.
- Pressing `+` with pending recovery never silently continues past the recovery
  banner.
- Saving a stale recovered node draft does not show raw
  `Selected node does not exist on preview` to the user; it is converted to an
  area target when valid or reported as a typed recoverable error.
- Editing annotation details while save is in flight cannot cause newer local
  edits to be cleared by the older save response.
- Deleting a session with local recovery requires an explicit local recovery
  decision before server close/delete.
- Clear local draft and clear server drafts are separate commands with separate
  confirmation copy and separate effects.
- No production console module directly mutates draft item fields outside the
  reducer/use case path.
- No production click handler both dispatches a canonical lifecycle command and
  also runs a legacy server mutation for the same action.
- All item, screenshot, handoff, and draft save requests include explicit
  `sessionId`.
- Browser storage writes new recovery only to `fixthis.workspace.*`.

## Migration Plan

### Phase 1 - Guardrail Tests First

Add failing tests for direct mutation, duplicate command side effects, recovery
boundary blocking, and stale save response fencing. Do not change behavior until
the failures prove the current defects.

Commit message:

```bash
git commit -m "Add lifecycle regression tests"
```

### Phase 2 - Stop Duplicate Runtime Side Effects

Either disable canonical effects for commands still handled by legacy workflows,
or complete those commands in canonical state and remove legacy side effects.
Do not leave both active.

Commands affected:

- session row click;
- annotate click;
- save to MCP;
- copy prompt;
- boundary sheet actions.

Commit message:

```bash
git commit -m "Use one console command path for lifecycle actions"
```

### Phase 3 - Route Draft Edits Through Reducer

Replace direct item mutations in pending annotation detail and focused comment
flush with `updateDraftItem` plus `replaceDraftWorkspace`.

Commit message:

```bash
git commit -m "Route pending draft edits through workspace reducer"
```

### Phase 4 - Extract Lifecycle Boundary Resolver

Move active draft and pending recovery boundary rules into one use case. Replace
ad hoc checks in history, main, annotations, and prompt flows.

Commit message:

```bash
git commit -m "Unify draft and recovery lifecycle boundaries"
```

### Phase 5 - Split Clear Local And Server Drafts

Introduce separate UI commands and implementation paths for local recovery
cleanup and persisted server draft item deletion.

Commit message:

```bash
git commit -m "Split local and server draft clearing"
```

### Phase 6 - Typed Server Validation Errors

Map target validation failures and session/item exceptions to typed JSON errors.
Update browser request adapters to branch on error codes.

Commit message:

```bash
git commit -m "Return typed console draft validation errors"
```

### Phase 7 - Bundle And Smoke Verification

Rebuild console resources, run focused tests, run browser smoke scenarios, and
verify no `.fixthis/` artifacts are staged.

Commit message:

```bash
git commit -m "Verify console draft lifecycle hardening"
```

## Open Risks

- Completing canonical single-source ownership may touch more UI rendering code
  than a small hotfix. The implementation should keep commits small and stop
  after each phase for focused verification.
- Some current tests encode permissive pending recovery behavior. Those tests
  must be updated intentionally because the new desired behavior is stricter.
- The browser prompt UX currently uses `window.confirm`, which cannot express
  all choices well. A small modal boundary sheet may be required for
  `resume`, `recapture`, `clear`, and `cancel`.
- If save succeeds on the server but becomes stale in the browser because the
  user edited locally, duplicate server draft items may appear. Existing
  client draft key dedupe should prevent re-save duplication, but this scenario
  needs an explicit test.

## Review Checklist

- Does every lifecycle action call the same boundary resolver?
- Can a local draft item be edited without changing workspace revision?
- Can any save response clear a workspace whose revision changed after request?
- Can any item mutation omit `sessionId`?
- Can any production path write `fixthis.recovery.*` or
  `fixthis.draftWorkspace.*`?
- Can a click handler both dispatch canonical command and run legacy mutation?
- Can pending recovery be ignored by session open/new/delete/annotate?
- Are recoverable target validation failures typed and user-actionable?
- Do focused tests fail if direct object mutation is reintroduced?
