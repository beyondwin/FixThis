# Console Canonical State Redesign

## Summary

FixThis Studio's browser console currently spreads session, preview, draft, tool-mode, polling, recovery, and prompt readiness state across several files. A single user action can mutate multiple state fragments through ad hoc orchestration such as `state.session = ...`, `state.preview = null`, `setDraftWorkspace(...)`, `resetAnnotationComposerState(...)`, and polling start/stop calls. This makes session and annotation boundary bugs likely: the UI can lose the add-annotation affordance, overwrite a frozen preview, apply a stale async response, or mix saved and draft focus after session navigation.

This design replaces the scattered browser state model with one canonical `ConsoleAppState`. Browser event handlers dispatch commands, reducers own all state transitions, effects carry explicit context fences, and renderers consume selector view models. The implementation is a big-bang browser-console refactor: no legacy browser state compatibility layer is kept. Existing server APIs, persisted MCP session JSON contracts, and the broad console layout remain, while the UI gains explicit draft-lock and session-boundary surfaces.

## Goals

- Make session/draft/preview/tool-mode sync bugs structurally difficult, not patched one at a time.
- Enforce a single mutation path for console state.
- Preserve the existing human workflow: connect, preview, annotate, add comments, copy or save to MCP.
- Add visible state boundaries so users can see when a draft is locked to a session and frozen preview.
- Support desktop and compact/mobile layouts for all new boundary and draft-lock UI.
- Improve testability with pure reducer, selector, invariant, and action-sequence tests.
- Apply Clean Architecture and SOLID boundaries in the browser console code.

## Non-Goals

- Changing MCP tool signatures or persisted feedback-session JSON compatibility fields.
- Supporting old browser-internal state models in parallel with the new one.
- Redesigning the entire visual identity of FixThis Studio.
- Adding release-build or non-Compose support.
- Moving server-side session persistence or Android bridge behavior.

## Current Problems

The console already has several focused FSMs, but the visible state still lives in legacy globals and compatibility mirrors:

- `state.session`, `state.preview`, and `state.sessionSummaries`
- `draftWorkspace`, plus `addItemsFlow`, `pendingFeedbackItems`, `focusedPendingItemIndex`, and `currentSelection`
- `toolModeUseCases` as a separate state owner
- polling and mutation generation state in another use-case object

Files such as `history.js`, `annotations.js`, `preview.js`, `prompt.js`, `rendering.js`, and `main.js` can all trigger state changes. The repeated class of bugs is not caused by one missing guard; it is caused by unclear ownership. The same business event is reconstructed differently by different callers.

## Target Architecture

All browser state changes flow through a store:

```text
DOM event / network response / timer
  -> dispatch(command or event)
  -> reduceConsoleAppState(currentState, event)
  -> { nextState, effects }
  -> run effects through browser adapters
  -> dispatch effect results
  -> render selector view models
```

The reducer is the only code that changes canonical state. Render functions do not mutate state. Browser adapters do not inspect business rules; they execute effects and dispatch results.

### Clean Architecture Mapping

- **Domain / Core:** `ConsoleAppState`, workspace state, reducer policies, selectors, invariants.
- **Application:** command interpretation, effect planning, boundary policy, store orchestration.
- **Interface Adapters:** session API, preview API, draft storage, clipboard, timer, and DOM renderer ports.
- **Framework/UI:** DOM event binding, browser fetch/localStorage/timer implementations, asset bootstrap.

The core must not depend on DOM, fetch, localStorage, timers, or global browser state.

### SOLID Boundaries

- **Single Responsibility:** session switching, draft editing, preview ownership, boundary decisions, effect execution, and rendering projection are separate modules.
- **Open/Closed:** new transitions are added as reducer events and policy functions, not by scattering new conditionals through renderers.
- **Liskov Substitution:** fake adapters used by tests and browser adapters follow the same effect contracts.
- **Interface Segregation:** ports are narrow: `sessionApi`, `previewApi`, `draftStorage`, `clipboard`, `clock`, `timer`.
- **Dependency Inversion:** domain/application code depends on effect descriptions and port interfaces, not concrete browser APIs.

## State Model

`ConsoleAppState` owns every observable console mode.

```ts
type ConsoleAppState = {
  activeSessionId: string | null;
  sessionsById: Record<string, SessionSummaryOrDetail>;
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

`WorkspaceState` is a tagged union. A screen cannot be live, draft, saved-focused, and recovery-focused at the same time.

```ts
type WorkspaceState =
  | { kind: 'empty' }
  | { kind: 'livePreview'; sessionId: string; preview: PreviewSnapshot | null }
  | {
      kind: 'draft';
      context: DraftContext;
      screen: ScreenSnapshot;
      screenshotUrl: string;
      items: DraftItem[];
      focusedItemId: string | null;
      currentSelection: AnnotationSelection | null;
      activityDriftWarning: ActivityDriftWarning | null;
    }
  | { kind: 'savedFocus'; sessionId: string; screenId: string; itemId: string | null }
  | { kind: 'recovery'; sessionId: string; recovery: DraftRecovery };
```

`ToolState` owns only pointer interaction state:

```ts
type ToolState =
  | { mode: 'select' }
  | { mode: 'annotate'; hoveredTarget: AnnotationSelection | null; drag: DragState | null };
```

Draft ownership is immutable. Once a draft is created, its `context.sessionId`, `previewId`, `screenId`, `screenFingerprint`, `deviceSerial`, and frozen timestamp remain stable until the draft is saved, discarded, or moved into recovery.

## Invariants

These invariants are part of the design, not optional test suggestions:

1. If `workspace.kind === 'draft'`, `activeSessionId === workspace.context.sessionId`.
2. A live preview response cannot replace or mutate a draft workspace.
3. Draft focus and saved evidence focus cannot coexist.
4. A session switch is immediate only when `pendingBoundary === null` and no unsaved draft blocks the switch.
5. Clicking a different session while an unsaved draft exists creates `pendingBoundary`; it does not mutate `activeSessionId`.
6. Clicking the already active session is a no-op.
7. Async responses apply only when their request id, session id, workspace id, and generation still match current state.
8. Preview, screen, artifact, and item mutation effects carry explicit `sessionId`; they do not fall back to the currently active session when explicit context exists.
9. Prompt readiness and button enablement are selector outputs derived from state.
10. Render functions do not mutate state or enqueue network calls.

## Commands and Effects

DOM events dispatch commands:

```ts
dispatch({ type: 'SESSION_ROW_CLICKED', sessionId });
dispatch({ type: 'ANNOTATE_CLICKED' });
dispatch({ type: 'DRAFT_TARGET_SELECTED', selection });
dispatch({ type: 'DRAFT_COMMENT_CHANGED', itemId, comment });
dispatch({ type: 'SAVE_TO_MCP_CLICKED' });
```

Network/timer responses dispatch events:

```ts
dispatch({ type: 'SESSION_OPEN_SUCCEEDED', requestId, sessionId, session });
dispatch({ type: 'PREVIEW_CAPTURE_SUCCEEDED', requestId, sessionId, preview });
dispatch({ type: 'DRAFT_SAVE_SUCCEEDED', requestId, sessionId, workspaceId, session });
dispatch({ type: 'SESSIONS_POLL_SUCCEEDED', requestId, sessions });
```

Reducers return effects instead of executing browser APIs:

```ts
type ConsoleEffect =
  | { kind: 'openSession'; requestId: string; sessionId: string; generation: number }
  | { kind: 'capturePreview'; requestId: string; sessionId: string; generation: number }
  | { kind: 'saveDraft'; requestId: string; sessionId: string; workspaceId: string; generation: number }
  | { kind: 'saveHandoff'; requestId: string; sessionId: string; itemIds: string[]; generation: number }
  | { kind: 'persistRecovery'; sessionId: string; workspace: DraftRecovery };
```

Every effect result is fenced. A stale `PREVIEW_CAPTURE_SUCCEEDED` for Session 1 cannot mutate a draft now locked to Session 2. A delayed draft-save result cannot clear the visible workspace unless the workspace id and generation still match.

## Session Boundary UX

When the user clicks another session while a draft is unsaved, the reducer creates a boundary state:

```ts
type PendingBoundary = {
  kind: 'session-switch';
  fromSessionId: string;
  targetSessionId: string;
  draftSummary: { itemCount: number; missingCommentCount: number; previewId: string | null };
};
```

The UI renders a boundary sheet with:

- **Save draft:** persist written draft annotations, then switch.
- **Keep in recovery:** store draft in local recovery and switch.
- **Discard:** delete local draft and switch.
- **Cancel:** stay in the current session and keep editing.

This sheet is not cosmetic. It is the only reducer-approved transition from a draft workspace to a different active session.

## Draft Lock UX

When `workspace.kind === 'draft'`, the canvas shows a lock bar:

```text
Locked: Session N · Preview <short-id> · Live preview paused
```

The history row for the active session shows draft badges such as `2 draft pins` and `live paused`. The right panel title becomes `Draft Annotations`; saved annotation empty states are not mixed with draft state. The `Add annotation` and `Exit Annotate` controls are derived from `workspace.kind === 'draft'`.

## Desktop and Mobile Behavior

The new UX must work on desktop, tablet, and narrow mobile widths.

### Desktop

- Preserve the three-column shell: history, canvas, inspector.
- The draft lock bar sits in the canvas toolbar beside Select/Annotate.
- The boundary sheet is centered over the canvas/workspace with the rest of the console dimmed.
- The inspector shows draft rows and actions without nesting cards inside cards.

### Tablet / Compact Desktop

- History may collapse behind the existing History control, but the active session and draft lock state remain visible in the top/canvas area.
- The lock bar may wrap into two rows, but it must not overlap canvas controls or zoom controls.
- Boundary sheet width is constrained and scroll-safe.

### Mobile

- The shell becomes a vertical workflow: top bar, workflow steps, canvas, inspector, with history in a drawer.
- Draft lock state remains visible above the canvas even when history is hidden.
- Boundary sheet becomes a bottom sheet or full-width modal with large tap targets.
- The four boundary actions stack vertically: Save draft, Keep in recovery, Discard, Cancel.
- Prompt readiness remains visible near handoff actions and must not rely on hover text.
- Long session names, device names, and status text wrap without overlapping buttons.

Responsive behavior is part of acceptance. Desktop-only correctness is not enough.

## Rendering and Selectors

Rendering reads selector view models:

```ts
render({
  history: selectHistoryModel(state),
  toolbar: selectToolbarModel(state),
  canvas: selectCanvasModel(state),
  inspector: selectInspectorModel(state),
  prompt: selectPromptReadiness(state),
  boundary: selectBoundarySheet(state),
});
```

Selectors, not DOM code, decide visible controls:

- `selectCanvasModel` chooses live preview, frozen draft, saved evidence, recovery, or empty state.
- `selectInspectorModel` chooses Draft Annotations, Saved Annotations, Recovery, or Empty inspector state.
- `selectPromptReadiness` returns readiness label, disabled states, and explanation text.
- `selectHistoryModel` returns active row state, draft badges, counts, and drawer content.

Event handlers dispatch commands only:

```js
sessionRow.onclick = () => dispatch({ type: 'SESSION_ROW_CLICKED', sessionId });
annotateButton.onclick = () => dispatch({ type: 'ANNOTATE_CLICKED' });
saveButton.onclick = () => dispatch({ type: 'SAVE_TO_MCP_CLICKED' });
```

## Module Layout

The final browser-console code should be organized by architectural role:

```text
fixthis-mcp/src/main/console/domain/
  consoleAppState.js
  consoleReducer.js
  consoleSelectors.js
  consoleInvariants.js
  workspaceState.js

fixthis-mcp/src/main/console/application/
  consoleStore.js
  consoleEffects.js
  sessionCommands.js
  draftCommands.js
  previewCommands.js
  boundaryPolicy.js

fixthis-mcp/src/main/console/adapters/
  browserApis.js
  browserStorage.js
  browserEffects.js
  browserRenderer.js
```

Existing top-level files are either removed or reduced to thin wrappers:

- `main.js`: boot store, adapters, event bindings.
- `history.js`: history DOM rendering and dispatch binding only.
- `annotations.js`: annotation DOM rendering and dispatch binding only.
- `preview.js`: preview DOM rendering and dispatch binding only.
- `prompt.js`: prompt/handoff DOM rendering and dispatch binding only.
- `state.js`: removed or reduced to store bootstrap constants.

## Testing Strategy

### Pure Reducer Tests

Reducer tests cover deterministic transitions:

- Same-session row click is a no-op.
- Different-session row click with unsaved draft creates `pendingBoundary`.
- Cancel boundary choice preserves draft state.
- Keep-in-recovery boundary choice persists recovery and opens target session.
- Annotate from live preview creates draft workspace and pauses live preview ownership.
- Late preview response with old generation is ignored.
- Draft save success with wrong workspace id is ignored.
- Save to MCP persists written draft items and clears draft only after a matching response.

### Invariant and Property Tests

Action-sequence tests generate realistic workflows:

```text
open session A
enter annotate
add draft target
click session A again
click session B
cancel boundary
save draft
late preview response from session A arrives
recover draft
save to MCP
```

After every step, `assertConsoleInvariants(state)` runs.

### Selector Tests

Selector tests verify UI view models without DOM:

- Draft workspace returns Draft Annotations inspector and Add Annotation action.
- Saved focus returns saved evidence model and no draft actions.
- Recovery returns Recover / Recapture / Discard actions.
- Mobile/compact selector variants expose history drawer and draft lock status.

### Browser and Bundle Tests

Existing console groups are updated for the new store:

- `session`
- `pending`
- `preview`
- `draft`
- `harness`
- `build`

The specific regression from this investigation becomes a browser-level or harness test:

```text
start annotate
add draft pin
click active session row
assert draft pins remain
assert Add annotation remains visible
assert live preview remains paused
```

Responsive QA must cover at least desktop, tablet-width, and mobile-width screenshots or browser checks.

## Acceptance Criteria

- No direct writes to `state.session` or `state.preview` outside store internals.
- No `addItemsFlow` / `pendingFeedbackItems` compatibility mirror remains.
- No orchestration functions such as `resetAnnotationComposerState(...)` or `invalidatePreviewContext()` are used as cross-module reset mechanisms.
- All session/draft/preview/tool transitions pass through the canonical reducer.
- All async effects carry request id, session id, and generation context.
- Rendering consumes selectors and does not mutate state.
- Draft lock bar appears whenever a draft workspace is active.
- Boundary sheet appears before cross-session navigation with unsaved draft work.
- Desktop, compact, and mobile layouts show draft lock and boundary actions without overlap.
- Console bundle is regenerated and build contract tests pass.
- Reducer, invariant, selector, session, draft, preview, and responsive smoke tests pass.

## Risks and Mitigations

- **Large blast radius:** Use a single architectural cut, but keep external APIs unchanged and validate with reducer/property/browser tests.
- **Reducer becoming too large:** Split policy by domain: session, draft, preview, boundary, prompt readiness, and effect planning.
- **Responsive regressions:** Treat mobile/compact rendering as acceptance criteria, not polish.
- **Async race regressions:** Make context fencing mandatory in effect result handling and test stale response scenarios.
- **Bundle growth:** Keep selectors/policies small and continue enforcing bundle size budgets.

## Implementation Notes

- The module names in this document are the target names for planning. If implementation discovers an existing build-pipeline constraint, rename only with the same domain/application/adapter ownership.
- The boundary sheet uses the same `pendingBoundary` state on every viewport. Desktop renders it as a centered modal; mobile renders it as a bottom sheet or full-width modal with the same four actions in the same order.
