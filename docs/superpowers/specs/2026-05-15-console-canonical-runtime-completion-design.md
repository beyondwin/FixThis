# Console Canonical Runtime Completion

## Summary

The canonical state redesign introduced `ConsoleAppState`, reducer, selectors,
effect descriptions, ports, and renderer adapters. The remaining gap is runtime
ownership: the live browser console still uses legacy global state in
`state.js`, `main.js`, `annotations.js`, `preview.js`, `history.js`,
`rendering.js`, and `prompt.js` for draft ownership, selected targets, focused
items, preview invalidation, and session boundary decisions.

This follow-up completes the redesign end to end. The browser console runtime
must bootstrap a single canonical store, route user actions and async responses
through reducer commands/events, execute all side effects through ports, and
render from selector models. Legacy browser-internal state is not supported.
There is no compatibility mode for `activeDraftFlow`, `draftFeedbackItems`,
`focusedPendingItemIndex`, `currentSelection`, direct `state.session` mutation,
or direct `state.preview` mutation.

Server APIs, MCP tool contracts, persisted feedback-session JSON, and local
draft storage schema compatibility remain stable. The unsupported legacy scope
is browser-internal JavaScript state only.

## Goals

- Make `ConsoleAppState` the sole owner of session, preview, draft, selection,
  focused item, pending boundary, prompt readiness, and draft lock state.
- Remove browser-runtime dependency on legacy draft globals.
- Replace legacy reset/invalidation helpers with reducer events.
- Connect `createConsoleStore`, `runConsoleEffect`, `createBrowserConsolePorts`,
  and `createBrowserRenderer` in the actual console bootstrap path.
- Render history, canvas, inspector, prompt, draft lock, and boundary sheet from
  selector models.
- Include canonical tests in the default console test suite and local CI mirror.
- Preserve existing human workflows: connect, select device, open session,
  capture preview, annotate, add comments, save to MCP, copy prompt, switch
  sessions, discard/recover drafts.
- Preserve MCP/server compatibility fields: `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, `sourceCandidates`.

## Non-Goals

- Supporting old browser-internal state in parallel with canonical state.
- Preserving function names that exist only for legacy state compatibility, such
  as `resetCanonicalAnnotationComposerState` or
  `invalidateCanonicalPreviewContext`.
- Changing HTTP endpoint paths or MCP tool signatures.
- Reworking Kotlin session persistence, Android bridge behavior, or Compose
  capture semantics.
- Redesigning the visual language beyond what is needed to render canonical
  draft lock and boundary states.
- Publishing artifacts or changing release-readiness status.

## Current Runtime Gap

The current code has canonical modules, but the runtime is only partially
canonical:

- `fixthis-mcp/src/main/console/application/consoleStore.js` exists but is not
  the root owner for live DOM events.
- `fixthis-mcp/src/main/console/application/consoleEffects.js` exists but most
  browser operations still call `requestJson`, localStorage, polling, and
  rendering helpers directly.
- `fixthis-mcp/src/main/console/adapters/browserRenderer.js` projects selector
  models, but existing render functions still read global variables directly.
- `fixthis-mcp/src/main/console/state.js` still declares
  `activeDraftFlow`, `draftFeedbackItems`, `focusedPendingItemIndex`, and
  `currentSelection`.
- `main.js`, `annotations.js`, `preview.js`, `history.js`, `prompt.js`,
  `devices.js`, `connection.js`, and `rendering.js` directly read or mutate
  draft/preview/session state.
- `scripts/console-tests.json` has a `canonical` group, but
  `npm run console:test:all` does not include it.

The runtime completion must close these gaps by changing ownership, not by
adding another synchronization layer.

## Target Architecture

The live console boot sequence is:

```text
DOMContentLoaded or script load
  -> createBrowserConsolePorts({ requestJson, localStorage, navigator, timer })
  -> createBrowserRenderer({ DOM binding functions })
  -> createConsoleStore({
       initialState,
       render: renderer.render,
       onEffects: queue effects through runConsoleEffect
     })
  -> bind DOM events to store.dispatch(command)
  -> dispatch initial load commands
```

All state changes follow one loop:

```text
DOM event / timer / network response
  -> store.dispatch(event)
  -> reduceConsoleAppState(currentState, event)
  -> assertConsoleInvariants(nextState)
  -> render selector view models
  -> run effect descriptions through browser ports
  -> dispatch effect result events
```

The reducer is the only write path for canonical state. Render code can read
selector models only. Ports can execute IO only. Event-binding modules can
translate DOM events into command objects only.

## Canonical State Ownership

`ConsoleAppState` owns:

- `activeSessionId`
- `sessionsById`
- `sessionOrder`
- `workspace`
- `tool`
- `connection`
- `polling`
- `pendingBoundary`
- `promptAction`
- `effectsGeneration`
- `status`

`WorkspaceState` owns all draft and preview surfaces:

- `empty`
- `livePreview`
- `draft`
- `savedFocus`
- `recovery`

Draft ownership is immutable once a draft starts. A draft context includes:

- `workspaceId`
- `sessionId`
- `previewId`
- `screenId`
- `screenFingerprint`
- `deviceSerial`
- `frozenAtEpochMillis`

The following runtime globals are removed from `state.js`:

- `activeDraftFlow`
- `draftFeedbackItems`
- `focusedPendingItemIndex`
- `currentSelection`

The following direct mutations are forbidden outside canonical domain helpers:

- `state.session = ...`
- `state.preview = ...`
- `draftFeedbackItems = ...`
- `draftFeedbackItems.push(...)`
- `draftFeedbackItems.splice(...)`
- `focusedPendingItemIndex = ...`
- `currentSelection = ...`
- `activeDraftFlow = ...`

## Command and Event Surface

The reducer must cover the workflow events needed by the live runtime.

Session and preview:

```js
{ type: 'CONSOLE_BOOTSTRAPPED' }
{ type: 'SESSION_ROW_CLICKED', sessionId }
{ type: 'SESSION_OPEN_SUCCEEDED', requestId, sessionId, generation, session }
{ type: 'SESSION_OPEN_FAILED', requestId, sessionId, generation, error }
{ type: 'PREVIEW_CAPTURE_REQUESTED' }
{ type: 'PREVIEW_CAPTURE_SUCCEEDED', requestId, sessionId, generation, preview }
{ type: 'PREVIEW_CAPTURE_FAILED', requestId, sessionId, generation, error }
```

Draft editing:

```js
{ type: 'ANNOTATE_CLICKED' }
{ type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId, preview }
{ type: 'DRAFT_TARGET_SELECTED', selection, targetEvidence }
{ type: 'DRAFT_COMMENT_CHANGED', itemId, comment }
{ type: 'DRAFT_ITEM_FOCUSED', itemId }
{ type: 'DRAFT_ITEM_DELETED', itemId }
{ type: 'DRAFT_SELECTION_CLEARED' }
{ type: 'DRAFT_DISCARDED' }
```

Prompt and persistence:

```js
{ type: 'SAVE_TO_MCP_CLICKED' }
{ type: 'COPY_PROMPT_CLICKED' }
{ type: 'DRAFT_SAVE_SUCCEEDED', requestId, sessionId, targetSessionId, workspaceId, generation, session }
{ type: 'DRAFT_SAVE_FAILED', requestId, sessionId, workspaceId, generation, error }
{ type: 'PROMPT_COPY_SUCCEEDED', requestId, generation }
{ type: 'PROMPT_COPY_FAILED', requestId, generation, error }
```

Session boundary:

```js
{ type: 'BOUNDARY_CANCEL_CLICKED' }
{ type: 'BOUNDARY_KEEP_RECOVERY_CLICKED' }
{ type: 'BOUNDARY_DISCARD_CLICKED' }
{ type: 'BOUNDARY_SAVE_DRAFT_CLICKED' }
```

Effects are the only way to do IO:

```js
{ kind: 'openSession', requestId, sessionId, generation }
{ kind: 'capturePreview', requestId, sessionId, generation }
{ kind: 'saveDraft', requestId, sessionId, targetSessionId, workspaceId, items, generation }
{ kind: 'copyPrompt', requestId, sessionId, workspaceId, markdown, generation }
{ kind: 'persistRecovery', sessionId, workspace }
{ kind: 'deleteRecovery', sessionId, workspaceId }
{ kind: 'showStatus', message, variant, assertive }
```

## Rendering Boundaries

Selector modules produce stable view models:

- `selectHistoryModel(state)`
- `selectCanvasModel(state)`
- `selectInspectorModel(state)`
- `selectPromptReadiness(state)`
- `selectBoundarySheet(state)`
- `selectDraftLockModel(state)`
- `selectToolbarModel(state)`
- `selectStatusModel(state)`

Rendering functions accept a view model and DOM dependencies. They must not read
or mutate global draft/session/preview state.

Example target shape:

```js
function renderInspectorRegion(model, handlers) {
  // model only: no direct reads from state, draftFeedbackItems, or activeDraftFlow.
}
```

Handlers dispatch commands:

```js
handlers.onCommentChanged(itemId, comment)
  -> store.dispatch({ type: 'DRAFT_COMMENT_CHANGED', itemId, comment })
```

## Recovery and Local Storage

Local draft storage compatibility is preserved at the storage boundary:

- Existing schema v2 workspaces remain readable.
- Legacy pending-key migration may remain inside `draftStorageAdapter.js` until
  old localStorage keys have been migrated by a current browser session.
- Runtime state must not restore legacy globals after reading storage. Recovery
  data is converted into `WorkspaceState.recovery` or `WorkspaceState.draft`
  through reducer events.

This keeps user data migration without supporting legacy browser state.

## Undo and Redo

Undo/redo currently mutates a passed shape with `draftFeedbackItems`. Runtime
completion must move undo/redo to command-based state transitions:

- `UNDO_CLICKED` or keyboard shortcut dispatches a reducer event.
- `REDO_CLICKED` dispatches a reducer event.
- Undo history entries identify draft workspace context and item ids.
- The reducer rejects undo/redo when the workspace context does not match.

The standalone `undoRedo.js` helpers may remain pure, but their input/output
must become values returned to the reducer, not mutable runtime state.

## Invariants

Runtime completion must enforce these invariants in tests and in
`createConsoleStore`:

1. A draft workspace implies `activeSessionId === workspace.context.sessionId`.
2. A live preview response cannot replace a draft workspace.
3. Draft focus and saved focus cannot coexist.
4. A session switch with unsaved draft creates `pendingBoundary` and does not
   mutate `activeSessionId`.
5. Clicking the active session is a no-op.
6. Async effect results apply only when `requestId`, `sessionId`,
   `workspaceId`, and `generation` match current state.
7. Prompt readiness is derived by selectors, not written by renderers.
8. Renderers do not dispatch network calls directly.
9. Browser adapters do not inspect business rules.
10. No runtime source file reads removed legacy globals.

## Testing Strategy

Unit tests:

- Reducer tests for draft creation, edit, delete, save, discard, recovery, and
  stale effect rejection.
- Selector tests for history, canvas, inspector, prompt, draft lock, and
  boundary sheet models.
- Store tests for invariant enforcement and effect queue ordering.
- Effect tests for every effect kind and failure result event.
- Static contract tests that forbid legacy global reads/mutations.

Integration harness:

- Fake bridge/session workflow that opens a session, captures preview, starts a
  draft, adds an item, edits a comment, saves, and verifies session list update.
- Boundary workflow that starts a draft, clicks another session, cancels,
  keeps recovery, discards, and saves.
- Responsive stress for draft lock and boundary sheet on compact and desktop
  viewports.

Default local test coverage:

- `node scripts/run-console-tests.mjs canonical`
- `npm run console:test:all` includes `canonical`
- `node scripts/console-responsive-stress.mjs`
- `node scripts/build-console-assets.mjs --check`
- `./gradlew :fixthis-mcp:test --tests '*Console*' --tests '*BuildInfoTest'`

## Migration Strategy

This is a big-bang runtime migration. The intermediate commits may leave
focused tests failing until a task completes, but each committed task must pass
its stated focused tests.

Recommended sequence:

1. Make canonical tests part of the default console suite.
2. Expand reducer/selectors/effects to cover all live workflow commands.
3. Bootstrap canonical store in `main.js`.
4. Convert event handlers to dispatch commands.
5. Convert renderers to selector models.
6. Remove legacy globals from `state.js`.
7. Convert undo/redo and recovery paths to reducer events.
8. Regenerate console bundle.
9. Run full console, responsive, and Kotlin contract verification.

## Risks

- **Large blast radius:** Many console modules read the same globals. Mitigation:
  split work by workflow and keep focused tests per task.
- **Hidden render mutation:** Some render paths currently update comments,
  focus, and persistence. Mitigation: static tests forbid legacy writes and
  render-to-network coupling.
- **Async stale response regressions:** Mitigation: generation/request/session
  fences are tested at reducer and effect levels.
- **Local recovery data loss:** Mitigation: keep storage schema migration at
  adapter boundary and test schema v2 recovery.
- **Bundle drift:** Mitigation: always run build check and committed generated
  bundle check after source edits.

## Acceptance Criteria

- `npm run console:test:all` runs and passes the `canonical` group.
- Static tests fail if any runtime module references removed legacy globals.
- `state.js` no longer declares `activeDraftFlow`, `draftFeedbackItems`,
  `focusedPendingItemIndex`, or `currentSelection`.
- Live bootstrap creates and uses `createConsoleStore`.
- Effects are executed through `runConsoleEffect`; direct runtime network calls
  are limited to port implementations and unrelated existing connection/device
  flows that have their own bounded FSMs.
- Draft lock and boundary sheet render from selector models.
- Save-to-MCP and Copy Prompt use canonical workspace state.
- Session switching with unsaved draft is handled only by reducer boundary
  state.
- Console bundle is regenerated and reproducible check passes.
- Documentation describes that browser-internal legacy state is unsupported.
