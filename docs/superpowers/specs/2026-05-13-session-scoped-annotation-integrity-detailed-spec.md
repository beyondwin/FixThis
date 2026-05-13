# Spec — Session-Scoped Annotation Integrity

Status: Draft for review
Date: 2026-05-13
Scope: `:fixthis-mcp` HTTP console routes, console JS state, feedback session persistence, preview/screenshot artifact access
Related specs:
- [`2026-05-09-annotation-screen-mismatch-fix-design.md`](2026-05-09-annotation-screen-mismatch-fix-design.md)
- [`2026-05-12-annotation-lifecycle-hardening-design.md`](2026-05-12-annotation-lifecycle-hardening-design.md)
- [`2026-05-12-post-v0.1.0-stabilization-detailed-spec.md`](2026-05-12-post-v0.1.0-stabilization-detailed-spec.md)

## Summary

The console now has important hardening in place: pending drafts are mirrored to
localStorage with frozen preview context, fingerprint fields exist on
`SnapshotDto`, Save to MCP can surface screen fingerprint conflicts, and pending
detail comments are no longer overwritten by the hidden composer. The remaining
data-integrity failures share one root cause: several mutations and visual
decisions still depend on ambient global state instead of the exact
session-preview-screen context that created the annotation.

This spec defines a single remediation line: every annotation, preview artifact,
undo operation, and session transition must carry an explicit immutable context:

```text
sessionId + previewId + screenId + screenFingerprint + deviceSerial + createdAt
```

The implementation should preserve existing public JSON fields and add nullable
or defaulted fields only. Legacy clients may continue to work through the current
session fallback, but the browser console must use explicit session-scoped calls
for all annotation and artifact operations.

## Problem

Users report that annotation data becomes tangled when they create/delete
annotations, move between sessions, or change the device screen. Code review
found five concrete failure classes.

### P1 — Save and artifact APIs still rely on the current session

`FeedbackItemRoutes` saves preview batches by calling
`service.requireCurrentSession().sessionId`, then passes that session to
`savePreviewFeedbackItemsWithLiveFingerprintMetadata`. `PreviewRoutes` and
`ArtifactRoutes` similarly resolve screenshots from the current session. If a
session switch, close, poll refresh, preview image load, or save request overlaps,
a request created for session A can execute against session B.

Affected entry points:

- `POST /api/items/batch`
- `POST /api/items`
- `DELETE /api/items/draft`
- `POST /api/agent-handoffs`
- `GET /api/preview/:previewId/screenshot/full`
- `GET /api/screens/:screenId/screenshot/full`
- `DELETE /api/screens/:screenId`

### P2 — Session close/delete can silently discard active pending annotations

`openSession()` and `newSession()` flush pending annotations before switching.
`closeSession()` and `deleteHistorySession()` call
`resetAnnotationComposerState()`, which clears `addItemsFlow`,
`pendingFeedbackItems`, and the localStorage mirror. This makes close/delete
more destructive than open/new, and the user does not get the same save/keep
choice.

### P3 — Undo/redo history is not scoped to a session or preview

The console keeps one singleton `undoRedoHistory`. Recorded operations contain
the item payload, but not the session, preview, screen, fingerprint, or device
that created it. After a session or frozen preview changes, an old undo can
restore an item into the wrong pending list.

### P4 — Saved overlays may appear on the wrong live screen

The approved 2026-05-09 behavior says saved annotation overlays should appear
only when a saved annotation is focused, and only on that saved screenshot.
Current rendering still tries to show saved overlays on a live preview when the
visible screen contains a matching `nodeUid`. Compose node UIDs are derived from
root index, tree kind, and semantics node id; they are stable within a snapshot
but not a global screen identity. Reusing a UID on another screen can draw stale
pins on the live device image.

### P5 — Item sequence numbers can be reused or visually shifted

`FeedbackSessionStore.nextItemSequenceNumber()` computes `max(sequenceNumber)+1`.
That is better than array index numbering for normal delete/add, but it still
has no durable counter. Replay, import, missing sequence values, or future
compaction can reconstruct a state where the next value is computed from the
current item list instead of the historical sequence. UI code also has several
fallbacks that display `index + 1`, so numbering can shift after deletion or
while rendering summaries.

## Goals

- All console mutations created from the browser carry an explicit `sessionId`.
- Preview and screenshot artifact reads validate ownership against the requested
  session, not the current session.
- Session switch, close, delete, and new-session actions share one pending-draft
  guard with explicit user choice.
- Undo/redo operations are ignored or cleared when their captured context does
  not match the active pending flow.
- Saved annotation overlays never render on an unrelated live preview because of
  coincidental node UID reuse.
- Item sequence numbers are monotonic, durable, and used consistently for UI
  display.
- Existing persisted fields such as `items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, and `sourceCandidates` are not renamed.

## Non-Goals

- Multi-user collaboration or conflict-free replicated editing.
- External/cloud synchronization.
- View or Flutter target support.
- A bridge protocol major version change.
- Rewriting the full event-log/session store architecture.
- Removing legacy current-session fallback for older HTTP callers in this pass.

## Design Principles

1. **Capture intent once.** When the user freezes a screen, create one immutable
   client context and reuse it for pending persistence, save, copy, handoff, and
   screenshots.
2. **Prefer rejection to silent rebinding.** If an explicit `sessionId` or
   `previewId` no longer resolves, return a clear 404/409 instead of falling
   back to current session.
3. **Keep visual inference conservative.** `nodeUid` is useful evidence inside a
   captured screen, not proof that two live screens are the same screen.
4. **Preserve compatibility.** Add fields with defaults and keep legacy request
   paths working where practical, but make the browser console use the safer
   contract.

## Context Model

Introduce a single shared client-side shape, either as plain JS object or a small
helper module:

```js
const context = {
  sessionId,
  previewId,
  screenId,
  screenFingerprint,
  deviceSerial,
  frozenAtEpochMillis,
  activityName
};
```

`addItemsFlow` should own this context. The existing top-level fields
`previewId`, `screen`, `screenshotUrl`, `frozenAtEpochMillis`, and `activity`
may remain during migration, but all new code should read through the context.

Rules:

- `sessionId` is captured from `state.session.sessionId` when annotate mode
  starts.
- `previewId` is captured from `state.preview.previewId`.
- `screenId` is captured from `state.preview.screen.screenId`.
- `screenFingerprint` is captured from `state.preview.screen.fingerprint`.
- `deviceSerial` is captured from `state.selectedDeviceSerial`.
- The context is persisted in the pending recovery envelope.
- Saving with no context is invalid for schema v1 recovery. Legacy schema v0
  recovery must force recapture before save.

## API Contract

### Request model additions

Add nullable/defaulted `sessionId` to request models that mutate or read
annotation-owned data:

```kotlin
@Serializable
data class SaveSnapshotRequest(
    val sessionId: String? = null,
    val previewId: String,
    val items: List<AnnotationDraftDto>,
    val screen: SnapshotDto? = null,
    val frozenFingerprint: String? = null,
    val forceMismatchOverride: Boolean = false,
)

@Serializable
data class AddAnnotationRequest(
    val sessionId: String? = null,
    val screenId: String,
    val comment: String,
    val targetType: FeedbackTargetType = FeedbackTargetType.AREA,
    val bounds: FixThisRect,
    val nodeUid: String? = null,
)

@Serializable
data class AgentHandoffRequest(
    val sessionId: String? = null,
    val itemIds: List<String> = emptyList(),
)
```

Existing `UpdateAnnotationRequest.sessionId` remains. For `DELETE
/api/items/:itemId`, keep the existing `?sessionId=` query parameter and make
the console always send it.

### Session resolution

Add one resolver in route code:

```kotlin
private fun FeedbackSessionService.resolveRequestSessionId(explicit: String?): String =
    explicit?.takeIf { it.isNotBlank() } ?: requireCurrentSession().sessionId
```

Use it only at the route boundary. Service methods should receive a concrete
`sessionId` and must not re-resolve current session internally.

For explicit requests:

- Unknown session: `404 {"error":"session_not_found","sessionId":"..."}`
- Preview belongs to another session: `409 {"error":"preview_session_mismatch"}`
- Screen belongs to another session: `409 {"error":"screen_session_mismatch"}`

For legacy requests without `sessionId`, keep the current behavior for one
release cycle and include an internal log/debug header when useful:

```text
X-FixThis-Legacy-Current-Session-Fallback: true
```

### Artifact URLs

Update generated console URLs:

```text
/api/preview/:previewId/screenshot/full?sessionId=:sessionId
/api/screens/:screenId/screenshot/full?sessionId=:sessionId
/api/screens/:screenId?sessionId=:sessionId
```

Server behavior:

- If `sessionId` is present, load that session by id.
- Validate the requested preview/screen belongs to that session.
- Never search the current session for an explicitly scoped artifact.
- If `sessionId` is absent, preserve current-session fallback for legacy callers.

### Preview save fallback

`FeedbackDraftService.preparePreviewFeedbackSave()` currently can build a
fallback preview record from the request body when the preview cache misses.
Keep this only for legacy recovery paths. For explicit `sessionId` console saves:

- If `previewCache.get(sessionId, previewId)` exists, use it.
- If cache misses but `fallbackScreen.screenId` already exists in that same
  session and `screenFingerprint` matches, allow save as recovered context.
- Otherwise return `404 preview_not_found` or `409 preview_context_mismatch`.

This avoids silently attaching a client-supplied screen snapshot to whatever
session is currently active.

## Console State Flow

### Starting annotate mode

`startAddItemsFlow()` should create:

```js
addItemsFlow = {
  context,
  previewId,
  screen,
  screenshotUrl,
  frozenAtEpochMillis,
  activity,
  activityDriftWarning
};
```

`screenshotUrl` should be built with `context.sessionId`.

After context creation, call a helper:

```js
resetUndoHistoryForContext(context);
persistCurrentPendingState();
```

### Saving pending items

`persistPendingFeedbackItems()` sends:

```js
savePreviewBatchOrConflict({
  sessionId: addItemsFlow.context.sessionId,
  previewId: addItemsFlow.context.previewId,
  screen: addItemsFlow.screen,
  items: payloadItems,
  frozenFingerprint: addItemsFlow.context.screenFingerprint,
  forceMismatchOverride
});
```

On response:

- If `result.session.sessionId !== addItemsFlow.context.sessionId`, reject the
  response, show an error, and refresh sessions. This should be impossible after
  route changes; keep the client guard anyway.
- If the active session has changed while the request was in flight, update only
  the matching session summary and do not overwrite `state.session` with an old
  session.

### Mutation generation guard

Add a monotonically increasing `sessionMutationGeneration`.

- Increment it before `openSession`, `newSession`, `closeSession`,
  `deleteHistorySession`, and device selection changes.
- Each async preview/save/delete response captures the generation at request
  start.
- If the generation changed before the response resolves, the response may
  update summaries but must not overwrite `state.session`, `state.preview`,
  `addItemsFlow`, or focused item state.

This complements `withMutationLock()`: the lock pauses polling refreshes, but it
does not by itself prove the response still belongs to the visible session.

## Pending Transition Guard

Replace ad hoc calls to `flushPendingAnnotationsBeforeSessionChange()` and
`resetAnnotationComposerState()` with one function:

```js
async function resolvePendingBeforeBoundary(action) {
  // action: "open-session" | "new-session" | "close-session" | "delete-session" | "select-device"
}
```

If no active pending items and no pending recovery banner exist, return
`"continue"`.

If active pending items exist, show a modal or blocking inline panel with:

- **Save draft**: save pending items to the captured `sessionId`, allowing blank
  comments only for session-boundary transitions. If save succeeds, clear local
  pending state and continue.
- **Keep editing**: cancel the boundary action and keep the current frozen
  preview/pins.
- **Discard**: clear the pending mirror and continue.

For close/delete:

- Do not silently call `resetAnnotationComposerState()`.
- If the pending context belongs to the session being closed/deleted, require one
  of the three choices above.
- If the pending context belongs to another session, leave it alone and surface
  the recovery banner when that session is reopened.

For pending recovery:

- Existing recovery choice behavior remains, but use the same boundary function
  so active pending and recovered pending share the same UX contract.

## Undo/Redo Contract

Update operation shape:

```js
{
  kind: 'add' | 'update' | 'delete',
  before,
  after,
  index,
  context: {
    sessionId,
    previewId,
    screenId,
    screenFingerprint,
    deviceSerial
  },
  createdAtEpochMillis
}
```

Rules:

- `recordAdd`, `recordDelete`, and `recordUpdate` require the current
  `addItemsFlow.context`.
- `undo()` and `redo()` compare op context with the current `addItemsFlow`
  context before applying.
- Context mismatch is a no-op that clears the stale stack and returns a typed
  result such as `{ applied:false, reason:'context_mismatch' }`.
- Starting a new annotate flow resets both stacks.
- Restoring pending recovery creates a new history context with empty stacks.

Keyboard shortcuts should keep current input-focus behavior, but if undo is
blocked by context mismatch the user should see a short non-blocking message:

```text
Undo history was cleared because the annotation session changed.
```

## Saved Overlay Rendering

Re-align rendering with the 2026-05-09 approved behavior.

Default live preview:

- Render no saved annotation overlays.
- Render only hover/current selection/pending overlays.

Focused saved annotation:

- `latestScreen()` returns the saved screenshot for the focused annotation.
- Render saved overlays for items with the same `screenId` as the focused item.
- Do not include items just because their `nodeUid` appears in the visible
  semantics tree.

Optional future extension:

- Add an explicit "show saved pins for current screen" toggle only after screen
  fingerprint matching is reliable. This is out of scope for the first fix.

## Sequence Number Contract

Add a durable counter to `SessionDto`:

```kotlin
@Serializable
data class SessionDto(
    val schemaVersion: String = "1.0",
    val sessionId: String,
    ...
    val nextItemSequenceNumber: Int = 1,
)
```

Migration:

- When loading a session with missing/default counter, compute:

```kotlin
max(session.nextItemSequenceNumber, session.items.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1) ?: 1)
```

- Save the migrated counter on the next mutation.

Mutation behavior:

- `addItem()` assigns the current counter, then increments by 1.
- `addScreenWithItems()` assigns contiguous values from the current counter,
  then increments by `items.size`.
- Delete never decrements the counter.
- Replay must preserve the counter from event payload or recompute it by applying
  the same reducer logic.

UI behavior:

- Saved rows, pending rows after save, prompt output, and overlay labels use
  `item.sequenceNumber`.
- `index + 1` may be used only for unsaved local pending items that have no
  server sequence yet, and the label should be visually local-only.

## Implementation Phases

### Phase 1 — Explicit session-scoped mutations

Files:

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsolePreviewModels.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- `fixthis-mcp/src/main/console/annotations.js`
- `fixthis-mcp/src/main/console/prompt.js`

Deliverables:

- Add request `sessionId` fields.
- Make browser save/handoff/add/update/delete calls pass explicit session ids.
- Add route tests where current session is B but request `sessionId` is A; item
  must land in A.

### Phase 2 — Scoped preview and screenshot artifacts

Files:

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/PreviewRoutes.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt`
- `fixthis-mcp/src/main/console/preview.js`
- `fixthis-mcp/src/main/console/rendering.js`

Deliverables:

- Add `?sessionId=` to preview/screen screenshot URLs.
- Validate artifact ownership server-side.
- Remove current-session lookup for explicitly scoped artifact requests.

### Phase 3 — Pending boundary guard and generation fencing

Files:

- `fixthis-mcp/src/main/console/main.js`
- `fixthis-mcp/src/main/console/history.js`
- `fixthis-mcp/src/main/console/annotations.js`
- `fixthis-mcp/src/main/console/sessions-polling.js`

Deliverables:

- Add `resolvePendingBeforeBoundary()`.
- Replace close/delete silent reset paths.
- Add `sessionMutationGeneration` and guard async response application.

### Phase 4 — Context-scoped undo/redo

Files:

- `fixthis-mcp/src/main/console/undoRedo.js`
- `fixthis-mcp/src/main/console/state.js`
- `fixthis-mcp/src/main/console/annotations.js`
- `scripts/undoRedo-test.mjs`

Deliverables:

- Add context-bearing operations.
- Clear/reject stale undo stacks on context mismatch.
- Add tests for session switch, preview recapture, and recovery restore.

### Phase 5 — Overlay and sequence hardening

Files:

- `fixthis-mcp/src/main/console/rendering.js`
- `fixthis-mcp/src/main/console/preview.js`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionReplayEngine.kt`

Deliverables:

- Remove live-screen saved overlay inference via `nodeUid`.
- Add durable `nextItemSequenceNumber`.
- Update renderers to prefer `item.sequenceNumber`.

## Acceptance Criteria

### Session switch/save race

1. Open session A.
2. Freeze preview and add two pending annotations.
3. Trigger Save to MCP.
4. Before the HTTP response resolves, open session B.
5. Expected:
   - Saved items are attached to session A.
   - Visible `state.session` remains B.
   - A session summary refresh shows A updated.
   - No pending items from A appear in B.

### Close/delete pending guard

1. Open a session and add one pending annotation.
2. Click close session.
3. Expected:
   - User is offered Save draft, Keep editing, Discard.
   - Keep editing leaves frozen preview and pending item intact.
   - Save draft saves to the original session before closing.
   - Discard clears only after explicit confirmation.

### Undo scope

1. Add pending annotation in session A and delete it.
2. Switch to session B and start a new annotate flow.
3. Press Cmd/Ctrl+Z.
4. Expected:
   - No item from A appears in B.
   - Undo stack is cleared or ignored with a context mismatch message.

### Artifact ownership

1. Create preview `p1` in session A.
2. Open session B.
3. Request `/api/preview/p1/screenshot/full?sessionId=A`.
4. Expected: returns A's preview.
5. Request `/api/preview/p1/screenshot/full?sessionId=B`.
6. Expected: 404 or 409; never returns A's artifact.

### Saved overlay behavior

1. Save a node annotation on screen A.
2. Navigate device to screen B where a semantics node happens to reuse the same
   UID shape.
3. Expected:
   - Live preview shows no saved overlay.
   - Clicking the saved annotation swaps to screen A's saved screenshot and shows
     only screen A pins.

### Sequence stability

1. Add annotations #1, #2, #3.
2. Delete #2.
3. Add another annotation.
4. Expected:
   - New annotation is #4.
   - Reopening/replaying the session keeps #1, #3, #4.
   - No UI list falls back to #1, #2, #3 based on array index.

## Test Plan

### Kotlin tests

- `FeedbackItemRoutesSessionScopeTest`
  - `batch save honors explicit sessionId over current session`
  - `agent handoff honors explicit sessionId`
  - `legacy batch save still uses current session when sessionId missing`
- `PreviewRoutesSessionScopeTest`
  - `preview screenshot with explicit sessionId reads matching session`
  - `preview screenshot rejects mismatched sessionId`
- `ArtifactRoutesSessionScopeTest`
  - `screen screenshot rejects screen from different session`
  - `delete screen requires matching sessionId`
- `FeedbackSessionSequenceTest`
  - `sequence counter is monotonic across add delete add`
  - `legacy session migrates nextItemSequenceNumber from existing max`
  - `event replay preserves nextItemSequenceNumber`

### JavaScript pure tests

- `pendingBoundaryGuard-test.mjs`
  - close/delete session cannot reset active pending state without a choice.
- `sessionScopedRequests-test.mjs`
  - save, handoff, update, delete, and screenshot URLs include captured
    `sessionId`.
- `undoRedoContext-test.mjs`
  - undo rejects mismatched session/preview/screen context.
- `savedOverlayScope-test.mjs`
  - live preview does not render saved overlays without focused saved item.

### Browser smoke

Extend `scripts/console-browser-smoke.mjs`:

- Save in session A while opening session B; verify item remains in A.
- Close session with pending annotation; verify guard appears.
- Focus saved annotation; verify screenshot URL includes `sessionId`.

## Rollout and Compatibility

- Ship server request model additions first. They are backwards-compatible due
  to nullable/default fields.
- Update console JS to always send explicit context.
- Keep legacy current-session fallback for one release cycle.
- Add a debug log or response header when fallback is used, then remove fallback
  in a later breaking-console cleanup only if no internal callers depend on it.
- Do not migrate or rename existing persisted JSON fields. Adding
  `nextItemSequenceNumber` to `SessionDto` is safe because older payloads load
  with the default.

## Open Risks

- A recovered pending draft may reference a preview cache entry that has been
  evicted. The spec handles this by allowing same-session existing-screen
  fallback only when fingerprint matches; otherwise the user must recapture.
- Sequence counter replay must be implemented in the same reducer path as live
  mutations. If live mutation and replay compute counters differently, replay
  can still drift.
- Explicit session IDs prevent cross-session writes, but they do not by
  themselves prove the Android device is still on the same screen. The existing
  fingerprint live check remains required.

## Verification Commands

Targeted verification for this work:

```bash
node scripts/pendingItemRecovery-test.mjs
node scripts/undoRedo-test.mjs
node scripts/build-console-assets.mjs --check
npm run console:smoke
./gradlew :fixthis-mcp:test
```

Full pre-PR verification should follow `CONTRIBUTING.md`.
