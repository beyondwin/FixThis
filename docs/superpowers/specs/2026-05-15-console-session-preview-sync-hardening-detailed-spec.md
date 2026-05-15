# Spec - Console Session Preview Sync Hardening

Status: Draft
Date: 2026-05-15
Scope: `:fixthis-mcp` feedback console browser state, SSE payload handling, session-scoped annotation CRUD, local draft recovery cleanup
Related docs:
- [`../../reference/feedback-console-contract.md`](../../reference/feedback-console-contract.md)
- [`2026-05-15-sse-console-state-sync-detailed-spec.md`](2026-05-15-sse-console-state-sync-detailed-spec.md)
- [`2026-05-15-console-js-reliability-stabilization-detailed-spec.md`](2026-05-15-console-js-reliability-stabilization-detailed-spec.md)
- [`../plans/2026-05-15-console-session-preview-sync-hardening-implementation.md`](../plans/2026-05-15-console-session-preview-sync-hardening-implementation.md)

## Summary

The feedback console already captures strong draft context for normal annotation
flows: draft workspaces carry `sessionId`, `previewId`, `screenFingerprint`,
and preview/screen screenshot URLs include explicit `sessionId` query
parameters. The remaining sync risks are at boundaries that can update browser
state asynchronously or fall back to current-session defaults:

1. SSE `session-updated` and `preview-ready` browser handlers can apply
   payloads without proving they belong to the active session.
2. The legacy `/api/items` route can mutate an explicit session but emit the
   current session.
3. Copy/Save prompt readiness allows a partially commented draft, but
   `persistAndCollectItemIds()` rejects the same draft before the existing
   written-comment-only persistence path can run.
4. Closing/deleting a session does not remove browser-local draft workspaces for
   that deleted session when they are not the currently active workspace.

This hardening pass closes those gaps without changing persisted MCP JSON field
names or the bridge protocol.

## Goals

- Make all async console event application session-scoped.
- Ensure preview events cannot replace the visible preview after a session
  switch.
- Ensure session update events from non-active sessions refresh history without
  clobbering the active detail pane.
- Emit the actual updated session from legacy explicit-session item mutation.
- Align Copy Prompt / Save to MCP UI eligibility with persistence behavior for
  partially commented drafts.
- Delete browser-local draft recovery for a session when that session is
  deleted/closed from history.
- Add tests that fail on the current unscoped behavior.

## Non-Goals

- No MCP output schema changes.
- No bridge protocol changes.
- No conversion to TypeScript.
- No removal of polling fallback.
- No collaborative merge protocol for two browser tabs editing the same draft.
- No release support; FixThis remains debug-only.

## Current Evidence

The following paths already behave correctly and must stay intact:

- `draftWorkspace.js` requires draft context to include `sessionId` and
  `previewId`.
- `draftApiAdapter.js` builds batch save payloads with explicit `sessionId` and
  `previewId`.
- `preview.js` builds preview and screen screenshot URLs with explicit
  `sessionId`.
- `FeedbackDraftService` reserves preview saves by `sessionId:previewId`,
  validates pending targets against the frozen preview screen, and checks
  frozen/current fingerprints before committing.
- Console tests pass today for session-scoped URLs, draft command queue
  revision fences, undo/redo context fences, and saved overlay screen context.

The remaining gaps are not in the main draft save path. They are in event
application, legacy route emission, prompt/draft UX parity, and local recovery
cleanup.

## Required Behavior

### 1. SSE Payloads Carry Session Identity

Every server event that can mutate browser session or preview state must carry a
session identity at the top level.

`session-updated` payload:

```json
{
  "sessionId": "session-a",
  "session": {
    "sessionId": "session-a"
  }
}
```

`preview-ready` payload:

```json
{
  "sessionId": "session-a",
  "preview": {
    "previewId": "preview-a",
    "screen": {
      "screenId": "screen-a"
    }
  }
}
```

`sessions-updated` may continue to carry only `sessionId`, because the browser
already refreshes summaries when full `sessions.sessions` is not included.

### 2. Browser Applies Events Only To Matching Active Session

`events.js` must apply event payloads with these rules:

- `snapshot`: can replace `state.session` because it is an authoritative
  initial connection snapshot.
- `session-updated`:
  - If there is no active browser session, apply it.
  - If `data.sessionId === state.session.sessionId`, apply it.
  - Otherwise do not call `setConsoleSession(data.session)`. Refresh or render
    session summaries instead.
- `preview-ready`:
  - Ignore when `draftFlow()` is active.
  - Ignore when `data.sessionId !== state.session?.sessionId`.
  - Ignore when payload has no valid `preview.previewId`.
  - Only then call `setConsolePreview(...)`.

This prevents an old preview capture from session A replacing session B's
screen after navigation.

### 3. Legacy Explicit-Session Mutation Emits The Mutated Session

`POST /api/items` accepts `sessionId` in the request body. After mutating that
session, it must emit the same session through `eventBus.emitSessionUpdated`.

The route must not emit `service.requireCurrentSession()` after an explicit
session mutation. That pattern is unsafe when current session differs from the
request session.

### 4. Partially Commented Drafts Can Persist Written Items

The console already has a safe path for written-comment-only persistence:

- `persistPendingFeedbackItems({ onlyWrittenComments: true })`
- `saveResidualPinOnlyDraft(...)`

Copy Prompt and Save to MCP must use that path consistently:

- If at least one draft item has a written comment, prompt actions can proceed.
- Written items are persisted and handed off.
- Pin-only residual items remain recoverable for Copy Prompt.
- Pin-only residual items are discarded for Save to MCP because
  `sendAgentPrompt()` calls `persistAndCollectItemIds({ keepResidualDraftActive:
  false })`.

The preflight check in `persistAndCollectItemIds()` must not reject a draft
only because some items are still pin-only.

### 5. Deleted Session Local Drafts Are Removed

When `deleteHistorySession(sessionId)` successfully closes/deletes a session,
the browser must remove local recovery for that `sessionId`:

- schema v2 workspace keys under
  `fixthis.workspace.<sessionId>.<workspaceId>`;
- schema v2 index key `fixthis.workspace.index.<sessionId>`;
- legacy schema v1 key `fixthis.pending.<sessionId>`;
- `activePendingMirrorSessions` membership for the deleted session.

This cleanup must run whether the deleted session is currently active or not.

## File-Level Design

### `ConsoleEventEmitters.kt`

Change `emitSessionUpdated(session)` to include top-level `sessionId`.

Add a dedicated preview emitter:

```kotlin
internal fun ConsoleEventBus.emitPreviewReady(
    sessionId: String,
    preview: FeedbackPreviewSnapshot,
) {
    emit(
        "preview-ready",
        buildJsonObject {
            put("sessionId", sessionId)
            put(
                "preview",
                fixThisJson.encodeToJsonElement(
                    FeedbackPreviewSnapshot.serializer(),
                    preview,
                ).jsonObject,
            )
        },
    )
}
```

### `PreviewRoutes.kt`

Use `eventBus.emitPreviewReady(session.sessionId, preview)` after
`service.capturePreview(session.sessionId)` succeeds.

### `events.js`

Add small local guards rather than a broad refactor:

```js
function activeSessionId() {
  return state.session?.sessionId || null;
}

function eventSessionMatches(data) {
  return Boolean(data?.sessionId && data.sessionId === activeSessionId());
}
```

Use them in `session-updated` and `preview-ready`.

### `FeedbackItemRoutes.kt`

For `POST /api/items`, keep returning the created item for compatibility, but
emit `service.getSession(sessionId)` after the mutation.

### `prompt.js`

Remove the check that throws when any draft item has a blank comment before
`persistPendingFeedbackItems({ onlyWrittenComments: true })` runs.

Keep the check that requires at least one written draft item.

### `draftStorageAdapter.js`

Add `deleteWorkspacesForSession(sessionId)`:

```js
function deleteWorkspacesForSession(sessionId) {
  const ids = readIndex(sessionId);
  ids.forEach((workspaceId) => {
    localStorageLike.removeItem(draftWorkspaceKey(sessionId, workspaceId));
  });
  localStorageLike.removeItem(draftWorkspaceIndexKey(sessionId));
}
```

Return it from the adapter.

### `history.js`

After a successful `deleteHistorySession(sessionId)` mutation, call:

```js
createBrowserDraftPorts().storage.deleteWorkspacesForSession(sessionId);
clearPendingMirror(sessionId);
activePendingMirrorSessions.delete(sessionId);
```

Run this for both active and inactive deleted sessions.

## Acceptance Criteria

- `preview-ready` for session A is ignored while session B is active.
- `session-updated` for session A does not replace the active session B detail
  pane.
- Initial `snapshot` still hydrates all browser state.
- `POST /api/items` with explicit session A emits session A even when session B
  is current.
- A draft with one commented item and one pin-only item can be copied/saved; the
  commented item is persisted and the residual pin-only item follows the
  existing Copy Prompt / Save to MCP behavior.
- Deleting an inactive session removes its local draft workspace and legacy
  pending mirror.
- These checks pass:

```bash
npm run console:session:test
npm run console:pending:test
npm run console:draft:test
npm run console:fsm:test
./gradlew :fixthis-mcp:test --no-daemon
```

