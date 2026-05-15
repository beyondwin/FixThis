# Spec — Console Canonical Single Source Completion

Status: Draft for review
Date: 2026-05-15
Scope: `:fixthis-mcp` browser console state, local console HTTP adapters, SSE handlers, console JS tests
Related specs:
- [`2026-05-15-console-canonical-state-redesign-design.md`](2026-05-15-console-canonical-state-redesign-design.md)
- [`2026-05-13-session-scoped-annotation-integrity-detailed-spec.md`](2026-05-13-session-scoped-annotation-integrity-detailed-spec.md)
- [`2026-05-15-sse-console-state-sync-detailed-spec.md`](2026-05-15-sse-console-state-sync-detailed-spec.md)

## Summary

The feedback console still has two state owners. The newer canonical store
models session, preview, draft, boundary, and prompt state, but the visible UI
still reads and mutates legacy globals such as `state.session`, `state.preview`,
`draftWorkspace`, draft focus variables, and `toolMode`. Several event handlers
dispatch canonical events and then immediately run the older direct mutation
flow. That hybrid state is the root cause behind preview/session/annotation
drift during session registration, deletion, annotation add/delete, and session
navigation.

This spec completes the canonical migration as a deliberate break from browser
legacy compatibility. The browser console will have one user-visible source of
truth: `ConsoleAppState`. DOM handlers dispatch commands only. Reducers own
state transitions. Effects perform network, storage, clipboard, and timer work
with explicit `sessionId`, `previewId`, `screenId`, `workspaceId`, and
`generation` fences. Renderers consume selector models and do not mutate state.

Server-side persisted JSON compatibility remains intact. Browser-internal
legacy state compatibility is removed.

## Problem

Three user-visible mismatch classes share the same implementation cause.

### P1 — Session update events can rebind the visible session

`session-updated` SSE currently calls `setConsoleSession(data.session)` for any
session update. If the user closes or deletes a non-active session, the event can
replace the visible session with that closed session while the preview and
selection overlays still describe the previously active session. This creates a
state where the history row, preview image, and annotation list disagree.

Required behavior: an event for a non-active session updates only that session's
cached detail/summary. It must not change `activeSessionId` or the current
workspace.

### P2 — Session navigation is handled twice

Clicking a history row dispatches `SESSION_ROW_CLICKED`, which correctly creates
a boundary when an unsaved draft exists. The same handler then runs legacy
navigation: pending recovery prompt, preview clearing, draft reset, direct
`POST /api/session/open`, refresh, and polling restart. As a result, canonical
state may say "stay on Session A with a pending boundary" while legacy state has
already switched to Session B or cleared the frozen preview.

Required behavior: session navigation has one path. A row click dispatches a
command. The reducer either opens immediately through an effect or creates a
boundary. No legacy direct mutation path remains.

### P3 — Annotation add/delete mutates a separate draft workspace

Annotation creation, deletion, focus, comments, and overlay numbering currently
mutate `draftWorkspace` and compatibility variables. The canonical reducer has
events for these actions, but the visible UI is not driven by canonical
workspace data. This lets overlay pins, inspector rows, prompt readiness, and
session badges drift after add/delete/focus operations.

Required behavior: draft annotations live only in
`ConsoleAppState.workspace.kind === "draft"`. Overlay pins, inspector rows,
prompt readiness, and history draft badges are selector outputs from that same
item list.

## Goals

- Resolve all three mismatch classes in one migration rather than adding local
  guards around each bug.
- Remove browser-internal legacy state as a supported runtime path.
- Preserve the existing user workflow: Start, preview, Annotate, select target,
  add comments, Copy Prompt, Save to MCP, resolve through MCP.
- Preserve persisted MCP/session JSON compatibility fields: `items`, `screens`,
  `itemId`, `screenId`, `targetEvidence`, `sourceCandidates`.
- Make every preview, artifact, item mutation, and handoff request explicitly
  session-scoped.
- Make stale async responses harmless through generation and context fences.
- Keep the current visual structure and responsive behavior unless required by
  the draft-lock or boundary UX.
- Add tests that reproduce session deletion, session switching, and annotation
  add/delete mismatch sequences at the canonical workflow level.

## Non-Goals

- Supporting old browser-internal state and canonical state side by side.
- Changing MCP tool signatures.
- Changing release/debug support policy.
- Changing Android bridge protocol semantics.
- Rewriting Kotlin session persistence beyond small route adapter additions
  needed for explicit session scoping.
- Redesigning the console's visual identity.

## Decision

Use the recommended option: complete the canonical store migration and delete
legacy browser state ownership.

The key decision is not "add more guards." The console must no longer have two
ways to answer these questions:

- Which session is active?
- Which preview is visible?
- Is the canvas live, frozen draft, saved evidence, recovery, or empty?
- Which annotation item is focused?
- Which items are eligible for prompt/handoff?
- Which session owns an artifact URL?

Each answer comes from `ConsoleAppState` through selectors.

## Canonical State Model

`ConsoleAppState` remains the root model and is extended where needed rather
than mirrored into legacy globals.

```ts
type ConsoleAppState = {
  activeSessionId: string | null;
  sessionsById: Record<string, SessionDetailOrSummary>;
  sessionOrder: string[];
  workspace: WorkspaceState;
  tool: ToolState;
  connection: ConnectionState;
  polling: PollingState;
  pendingBoundary: PendingBoundary | null;
  promptAction: PromptActionState;
  effectsGeneration: number;
  status: StatusMessage | null;
};
```

`WorkspaceState` is the exclusive visible workspace:

```ts
type WorkspaceState =
  | { kind: "empty" }
  | { kind: "livePreview"; sessionId: string; preview: PreviewSnapshot | null }
  | {
      kind: "draft";
      context: DraftContext;
      screen: ScreenSnapshot;
      screenshotUrl: string;
      items: DraftItem[];
      focusedItemId: string | null;
      currentSelection: AnnotationSelection | null;
      activityDriftWarning: ActivityDriftWarning | null;
    }
  | { kind: "savedFocus"; sessionId: string; screenId: string; itemId: string | null }
  | { kind: "recovery"; sessionId: string; recovery: DraftRecovery };
```

`DraftContext` is immutable:

```ts
type DraftContext = {
  sessionId: string;
  previewId: string;
  screenId: string;
  screenFingerprint: string | null;
  deviceSerial: string | null;
  frozenAtEpochMillis: number | null;
  activityName: string | null;
  workspaceId: string;
};
```

No code may rewrite a draft context after freeze. To move a draft to a different
screen or session, the user must recapture, save, discard, or keep recovery.

## Removed Legacy Runtime State

These browser globals are removed as writeable state owners:

- `state.session`
- `state.preview`
- `state.sessionSummaries`
- `draftWorkspace`
- `draftFlowState`
- `draftPinsState`
- `draftFocusIndexState`
- `draftSelectionState`
- legacy pending mirror helpers that mutate draft state outside store
- `toolMode` state that duplicates canonical `tool`

During implementation, thin accessors may exist only as local migration helpers
inside tests or adapter seams. They must not be used by production renderers or
event handlers after the migration is complete.

Static tests must reject direct production writes to these removed state owners.

## Commands

DOM event handlers dispatch commands only. They do not mutate state, call
`requestJson`, touch localStorage, start/stop polling, or clear preview
directly.

Required command set:

```ts
// Session lifecycle
SESSION_ROW_CLICKED(sessionId)
NEW_SESSION_CLICKED
CLOSE_ACTIVE_SESSION_CLICKED
DELETE_SESSION_CLICKED(sessionId)

// Boundary sheet
BOUNDARY_SAVE_DRAFT_CLICKED
BOUNDARY_KEEP_RECOVERY_CLICKED
BOUNDARY_DISCARD_CLICKED
BOUNDARY_CANCEL_CLICKED

// Preview and navigation
ANNOTATE_CLICKED
LIVE_PREVIEW_TICK
PREVIEW_TAP(point)
PREVIEW_SWIPE(direction, distance)
BACK_CLICKED

// Draft annotation
DRAFT_TARGET_HOVERED(selection | null)
DRAFT_DRAG_STARTED(point)
DRAFT_DRAG_UPDATED(bounds)
DRAFT_DRAG_FINISHED(bounds)
DRAFT_TARGET_SELECTED(selection)
DRAFT_ITEM_FOCUSED(itemId | null)
DRAFT_ITEM_DELETED(itemId)
DRAFT_COMMENT_CHANGED(itemId, comment)
DRAFT_LABEL_CHANGED(itemId, label)
DRAFT_SEVERITY_CHANGED(itemId, severity)
DRAFT_STATUS_CHANGED(itemId, status)
DRAFT_DISCARDED

// Saved annotation
SAVED_ITEM_FOCUSED(sessionId, screenId, itemId)
SAVED_ITEM_UPDATED(sessionId, itemId, patch)
SAVED_ITEM_DELETED(sessionId, itemId)

// Handoff
COPY_PROMPT_CLICKED
SAVE_TO_MCP_CLICKED
```

Network, SSE, timer, and storage responses dispatch events:

```ts
SESSION_OPEN_SUCCEEDED(requestId, sessionId, session, generation)
SESSION_CLOSE_SUCCEEDED(requestId, sessionId, session, nextActiveSessionId, generation)
SESSION_DELETE_SUCCEEDED(requestId, sessionId, session, nextActiveSessionId, generation)
SESSIONS_LIST_SUCCEEDED(requestId, sessions, generation)
SESSION_DETAIL_UPDATED(session)
PREVIEW_CAPTURE_SUCCEEDED(requestId, sessionId, preview, generation)
PREVIEW_CAPTURE_FAILED(requestId, sessionId, error, generation)
DRAFT_SAVE_SUCCEEDED(requestId, sessionId, workspaceId, session, targetSessionId, generation)
DRAFT_SAVE_CONFLICT(requestId, sessionId, workspaceId, conflict, generation)
DRAFT_SAVE_FAILED(requestId, sessionId, workspaceId, error, generation)
HANDOFF_SUCCEEDED(requestId, sessionId, itemIds, session, generation)
HANDOFF_FAILED(requestId, sessionId, itemIds, error, generation)
SSE_SESSION_UPDATED(session)
SSE_SESSIONS_UPDATED(sessions | null)
SSE_PREVIEW_READY(sessionId, preview)
```

## Effects

Reducers return declarative effects. Effects carry context and do not inspect UI
state.

```ts
type ConsoleEffect =
  | { kind: "openSession"; requestId; sessionId?: string; newSession?: boolean; generation }
  | { kind: "closeSession"; requestId; sessionId; generation }
  | { kind: "deleteSession"; requestId; sessionId; generation }
  | { kind: "listSessions"; requestId; generation }
  | { kind: "capturePreview"; requestId; sessionId; generation }
  | { kind: "navigate"; requestId; sessionId; action; point?; direction?; distance?; generation }
  | { kind: "saveDraft"; requestId; sessionId; workspaceId; previewId; screenId; frozenFingerprint; items; targetSessionId?; generation }
  | { kind: "persistRecovery"; sessionId; workspace }
  | { kind: "deleteRecovery"; sessionId; workspaceId }
  | { kind: "copyPrompt"; requestId; sessionId; itemIds; generation }
  | { kind: "saveHandoff"; requestId; sessionId; itemIds; generation }
  | { kind: "updateSavedItem"; requestId; sessionId; itemId; patch; generation }
  | { kind: "deleteSavedItem"; requestId; sessionId; itemId; generation }
  | { kind: "showStatus"; message; variant; assertive };
```

Effect runners call the existing server endpoints where possible:

- Session open/close: `/api/session/open`, `/api/session/close`.
- Preview capture: `/api/preview?sessionId=<id>` or equivalent explicit
  session-scoped adapter around the existing route.
- Preview screenshot: `/api/preview/:previewId/screenshot/full?sessionId=<id>`.
- Screen screenshot: `/api/screens/:screenId/screenshot/full?sessionId=<id>`.
- Draft save: `/api/items/batch`.
- Copy prompt preview: `/api/sessions/:sessionId/handoff-preview`.
- Save to MCP: `/api/agent-handoffs`.
- Saved item update/delete: `/api/items/:itemId` with explicit session id.

The old browser effect path that posts draft items to `/api/feedback/items` is
invalid and must be removed.

## Reducer Rules

### Session click

If the clicked session is already active, no-op.

If a draft workspace is active and the clicked session is different, set
`pendingBoundary` and leave `activeSessionId`, `workspace`, and `tool` unchanged.

Otherwise, increment `effectsGeneration`, clear saved focus, set workspace to
`livePreview(clickedSessionId, null)` only after `SESSION_OPEN_SUCCEEDED`, and
emit `openSession`.

### New session

If no draft is active, emit `openSession(newSession: true)`.

If a draft is active, create `pendingBoundary` with a `targetNewSession` marker
instead of immediately opening a new session. Boundary actions decide whether
to save, keep recovery, discard, or cancel.

### Close/delete session

Closing or deleting the active session uses the same boundary policy as session
switching. If a draft exists, user choice is required.

Closing or deleting a non-active session does not change `activeSessionId` or
workspace. It updates only `sessionsById` and `sessionOrder`.

### SSE session update

`SSE_SESSION_UPDATED(session)` upserts `sessionsById[session.sessionId]`.

If `session.sessionId === activeSessionId`, it may replace the active session
detail and update saved-focus content. It must not change a draft workspace's
screen, screenshot, context, or items.

If `session.sessionId !== activeSessionId`, it must not change active session,
workspace, preview, focused item, or prompt in-flight state.

### Preview capture

`PREVIEW_CAPTURE_SUCCEEDED` applies only when all are true:

- `event.generation === state.effectsGeneration`
- `event.sessionId === state.activeSessionId`
- current workspace is not `draft`
- current workspace is not `savedFocus` unless the capture was explicitly
  requested to return to live preview

Annotate flow captures a preview and then creates a draft workspace from that
same event. There is no separate legacy freeze step that reads `state.preview`.

### Annotation add/delete

`DRAFT_TARGET_SELECTED` appends a draft item to `workspace.items`, assigns the
next local sequence number, focuses it, and clears hover/drag state.

`DRAFT_ITEM_DELETED` removes exactly that item id, clears focus if needed, and
keeps remaining item display numbers derived from either stable
`sequenceNumber` or current draft order. This is a pending draft display rule
only; persisted item numbers remain monotonic from the server.

`DRAFT_COMMENT_CHANGED`, `DRAFT_LABEL_CHANGED`, `DRAFT_SEVERITY_CHANGED`, and
`DRAFT_STATUS_CHANGED` update the item in canonical state and plan recovery
storage persistence through an effect or debounced storage adapter. They do not
mutate DOM fields directly.

### Save and handoff

Save and handoff operate on the draft workspace's immutable context. If
`workspace.kind !== "draft"` or there are no written comments, reducers either
no-op or emit a status effect.

On successful save:

- Upsert the returned session.
- Delete the draft recovery workspace.
- Clear draft workspace.
- If the save was part of a boundary action, open or activate the target
  session after the source save succeeds.
- If residual pin-only draft items remain after copy prompt, store them as a
  new recovery workspace tied to the original draft context or keep them active
  only if the user remains in that session.

## Selectors

Rendering must read selector models only.

Required selectors:

- `selectHistoryModel(state)`
- `selectCanvasModel(state)`
- `selectInspectorModel(state)`
- `selectToolbarModel(state)`
- `selectPromptReadiness(state)`
- `selectBoundarySheet(state)`
- `selectDraftLockModel(state)`
- `selectRecoveryModel(state)`

Important selector rules:

- History active row comes from `activeSessionId`.
- History draft badges come from `workspace.kind === "draft"` and draft
  `context.sessionId`.
- Canvas image URL always includes explicit `sessionId`.
- Draft overlay pins come from `workspace.items`.
- Saved overlay pins render only in `savedFocus` mode or when focused saved
  evidence explicitly selects a persisted screen. They do not render on an
  unrelated live preview because a node UID happens to match.
- Prompt readiness is derived from the active workspace and `promptAction`.
- Buttons are enabled/disabled from selectors, not direct DOM conditionals.

## Browser Adapter Rules

The browser bootstrap owns framework concerns only:

- Bind DOM events to `dispatch`.
- Start/stop timers by dispatching timer commands/events.
- Open SSE and dispatch SSE events.
- Run effects through port adapters.
- Render selector models.

It must not:

- Assign session, preview, or draft state directly.
- Read draft items from DOM.
- Use `requestJson` directly from UI event handlers.
- Decide whether a session boundary is allowed.
- Clear preview as a side effect of a click without reducer approval.

## SSE Contract

SSE is advisory state sync, not a second state owner.

Required event handling:

- `snapshot`: dispatch one `CONSOLE_SNAPSHOT_RECEIVED` event containing optional
  current session, sessions, devices, and connection. The reducer/adapters split
  it into canonical updates.
- `session-updated`: dispatch `SSE_SESSION_UPDATED`.
- `sessions-updated`: dispatch `SSE_SESSIONS_UPDATED` when payload contains a
  list; otherwise emit a `listSessions` effect.
- `preview-ready`: include or infer an explicit session id before dispatch.
  If no session id can be proven, ignore the event while another preview capture
  request is active and request a scoped refresh.

Non-active session events must not replace the active workspace.

## Server Adapter Requirements

The Kotlin routes mostly support explicit session ids already. The browser
adapter must use them consistently.

Required route/adapter adjustments:

- `GET /api/preview` accepts optional `sessionId`. When present, it captures
  preview for that session. The browser console must always send `sessionId`.
  Any current-session fallback is outside the browser runtime and is not part
  of this migration's compatibility contract.
- `POST /api/navigation` accepts optional `sessionId`. Browser console sends it.
- `ConsoleEventBus.emitSessionUpdated` may keep its existing payload, but the
  browser treats it as a session detail update, not an active-session command.
- No browser code may call a mutation endpoint without explicit session context
  when the relevant command has a known session id.

Persisted output schemas are unchanged.

## Recovery Storage

Recovery storage remains local, but is driven from canonical draft state.

Schema v2 recovery envelope stays:

```js
{
  schemaVersion: 2,
  sessionId,
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

Rules:

- Recovery key includes `sessionId` and `workspaceId`.
- Restoring recovery dispatches a canonical recovery/draft event.
- Recovery from another session never changes active session silently.
- Pin-only recovery can auto-restore only when its context matches the active
  session and the user has no active draft.
- Commented recovery requires explicit Recover, Recapture, or Discard unless it
  is the active draft workspace already known to canonical state.

## UI Behavior

### Draft lock

When a draft exists, the canvas shows a lock bar:

```text
Locked: Session <label> · Preview <short id> · Live preview paused
```

The lock bar is selector-driven and remains visible after annotation add/delete,
session list refresh, and SSE updates.

### Boundary sheet

When a draft blocks session switch, new session, close, or delete, show a
boundary sheet with:

- Save draft
- Keep in recovery
- Discard
- Cancel

The sheet is the only path out of a draft to a different session. It must be
keyboard reachable and mobile safe.

### History

History rows can show:

- active
- busy
- draft locked
- open/resolved counts
- pending recovery summary

The active row is never derived from a closed/deleted non-active session update.

### Canvas and overlay

Canvas mode is one of:

- live preview
- frozen draft
- saved focus
- recovery
- empty

Overlay pins are rendered from the selected canvas mode only. Draft pins and
saved pins cannot render at the same time unless a selector explicitly returns
a combined mode, which this spec does not require.

### Prompt readiness

Copy and Save buttons read readiness from selectors. A pending network action
sets `promptAction.inFlight` in canonical state. Button labels and disabled
states must not be mutated by independent legacy timers except for a
selector-approved transient status event.

## Testing Strategy

### Reducer tests

Add deterministic reducer tests for:

- Non-active `SSE_SESSION_UPDATED` does not change active workspace.
- Active `SSE_SESSION_UPDATED` updates saved detail but does not overwrite a
  draft workspace.
- Session delete for a non-active session leaves active preview and draft
  unchanged.
- Active session delete with draft creates a boundary.
- `DRAFT_ITEM_DELETED` keeps overlay/inspector source list consistent.
- Late preview capture for Session B cannot replace a Session A draft.
- Save during boundary saves source session and only then opens target session.

### Selector tests

Add selector tests for:

- Draft overlay pins and inspector rows are the same item ids.
- Prompt readiness derives from draft item comments.
- Saved overlay renders only in saved-focus mode.
- Canvas image URLs include explicit session id.
- History active row ignores non-active session updates.

### Effect tests

Add effect tests for:

- `capturePreview` sends explicit session id.
- `navigate` sends explicit session id.
- `saveDraft` calls `/api/items/batch`, not removed `/api/feedback/items`.
- `saveHandoff` includes session id and item ids.
- `deleteSavedItem` includes session id.
- Stale effect responses dispatch events but reducers drop them.

### Browser harness tests

Add scenario-level harness tests for:

1. Create session A, freeze preview, add two annotations, delete one, verify
   overlay pin count, inspector row count, and prompt readiness all match.
2. While draft exists in session A, click session B, verify boundary appears and
   preview/session/annotations remain A until a boundary action succeeds.
3. Delete or close session B while viewing session A, receive
   `session-updated` for B, verify active workspace remains A.
4. Capture preview for A, start a delayed capture for B, switch back to A,
   verify late B response does not mutate visible canvas.
5. Copy Prompt with one written item and one pin-only item, verify saved item is
   tied to source session and residual draft/recovery remains source-scoped.

### Static guard tests

Keep or add static tests that fail if production console files contain:

- `state.session =`
- `state.preview =`
- direct writes to `draftWorkspace`
- direct UI handler calls to `requestJson`
- references to `/api/feedback/items`
- renderers that call network/storage APIs

The tests should allow isolated fixture files and migration tests to construct
plain objects.

## Implementation Plan Outline

The implementation plan will be written separately after this spec is approved.
It should follow this order:

1. Expand canonical state, reducer events, effects, selectors, and invariant
   tests.
2. Build browser effect adapters for session, preview, navigation, draft save,
   handoff, saved item mutation, and recovery storage.
3. Convert SSE handlers into dispatch-only adapters.
4. Convert history/session DOM handlers into dispatch-only handlers.
5. Convert preview/annotation DOM handlers into dispatch-only handlers.
6. Convert prompt/handoff handlers into dispatch-only handlers.
7. Remove legacy state globals and compatibility rendering.
8. Add browser harness scenarios for the three reported mismatch classes.
9. Rebuild console assets and run JS/Kotlin console route tests.

## Acceptance Criteria

- There is only one production source of visible console state:
  `ConsoleAppState`.
- Browser-internal legacy state support is removed.
- Session registration, deletion, annotation registration/deletion, and
  session navigation cannot produce preview/session/annotation mismatches in
  reducer or harness tests.
- All browser-originated preview, navigation, artifact, annotation, and handoff
  requests carry explicit session context.
- Non-active SSE session updates do not change the active workspace.
- Draft overlay, inspector, prompt readiness, and history draft badge derive
  from the same canonical item list.
- Saved overlays do not appear on unrelated live previews.
- Existing persisted MCP/session JSON compatibility fields are not renamed.
- Console JS tests, targeted Kotlin console route tests, and asset contract
  tests pass.

## Risks

- This is a broad browser-console migration. The main risk is missing a legacy
  read path that still drives a visible control.
- Existing tests include static checks that mention legacy globals. They must be
  updated to assert removal rather than compatibility.
- SSE preview events may not currently include enough session context. The safe
  fallback is to ignore ambiguous preview events and issue a scoped preview
  refresh for the active session.
- Recovery UX can become noisy if every pin-only draft requires a modal. The
  design keeps pin-only auto-restore only when context matches active session.

## Rollback Strategy

Because browser-internal legacy compatibility is intentionally removed, rollback
is at the branch level. Keep the migration behind normal PR review and do not
ship partial states where canonical and legacy runtimes are both active. If a
blocking issue appears during implementation, revert the migration branch and
fall back to the current console bundle while preserving this spec for the next
attempt.
