# Console Canonical Runtime Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the console canonical state redesign by making `ConsoleAppState` the live browser console's single state owner and removing unsupported legacy browser-internal draft/preview globals.

**Architecture:** This is a full runtime migration with no legacy browser-state compatibility layer. DOM handlers dispatch reducer commands, reducers return fenced effect descriptions, browser ports execute IO, and renderers consume selector view models only. Server APIs, MCP tools, persisted feedback-session JSON, and local draft storage migration remain compatible.

**Tech Stack:** Plain JavaScript ES2020, Node `node:test`, existing console bundle pipeline, Playwright-backed console harness/stress scripts, Kotlin/JUnit console asset contract tests.

---

## Reference Spec

Read before implementing:

- `docs/superpowers/specs/2026-05-15-console-canonical-runtime-completion-design.md`
- `docs/superpowers/specs/2026-05-15-console-canonical-state-redesign-design.md`
- `docs/reference/feedback-console-contract.md`

## Scope Rules

- Do not support legacy browser-internal state.
- Do not preserve `activeDraftFlow`, `draftFeedbackItems`, `focusedPendingItemIndex`, or `currentSelection` as runtime globals.
- Do not reintroduce direct `state.session = ...` or `state.preview = ...` assignments.
- Do not rename persisted MCP JSON fields: `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `sourceCandidates`.
- Do not hand-edit `fixthis-mcp/src/main/resources/console/app.js`; regenerate it with `node scripts/build-console-assets.mjs`.
- Commit after each task when its focused verification passes.

## File Structure

Modify canonical domain/application/adapter files:

- `fixthis-mcp/src/main/console/domain/workspaceState.js` - add complete draft/recovery/saved-focus workspace helpers.
- `fixthis-mcp/src/main/console/domain/consoleAppState.js` - add initial runtime shape, request ids, session replacement helpers, status helpers.
- `fixthis-mcp/src/main/console/domain/consoleInvariants.js` - enforce runtime invariants and removed legacy state assumptions.
- `fixthis-mcp/src/main/console/domain/consoleReducer.js` - cover all live console commands and stale async result rejection.
- `fixthis-mcp/src/main/console/domain/consoleSelectors.js` - produce render models for toolbar, draft lock, history, canvas, inspector, prompt, boundary, and status.
- `fixthis-mcp/src/main/console/application/consoleStore.js` - enforce invariant checks, render after state changes, queue effects deterministically.
- `fixthis-mcp/src/main/console/application/consoleEffects.js` - execute all effect kinds through ports and dispatch result events.
- `fixthis-mcp/src/main/console/adapters/browserPorts.js` - wrap requestJson, draft storage, clipboard, timers, and localStorage migrations.
- `fixthis-mcp/src/main/console/adapters/browserRenderer.js` - coordinate existing DOM render helpers from selector models.

Modify runtime modules:

- `fixthis-mcp/src/main/console/main.js` - bootstrap store/ports/renderer and bind DOM events to dispatch commands.
- `fixthis-mcp/src/main/console/state.js` - remove legacy draft globals; retain only constants or DOM references that are not state.
- `fixthis-mcp/src/main/console/annotations.js` - convert draft operations to dispatch helpers and pure formatting/render helpers.
- `fixthis-mcp/src/main/console/preview.js` - convert preview capture and preview reads to canonical commands/selectors.
- `fixthis-mcp/src/main/console/history.js` - route session row actions through canonical boundary commands.
- `fixthis-mcp/src/main/console/rendering.js` - render selector models without global state reads.
- `fixthis-mcp/src/main/console/prompt.js` - save/copy from canonical prompt model and effects.
- `fixthis-mcp/src/main/console/shortcuts.js` - dispatch undo/redo/draft commands.
- `fixthis-mcp/src/main/console/undoRedo.js` - return immutable next item lists for reducer use.

Modify tests and scripts:

- `scripts/console-tests.json` - include canonical in default all group through `package.json`.
- `package.json` - add `canonical` to `console:test:all`.
- `scripts/consoleCanonicalState-test.mjs` - expand reducer coverage.
- `scripts/consoleSelectors-test.mjs` - expand selector coverage.
- `scripts/consoleEffects-test.mjs` - expand effect coverage.
- `scripts/consoleCanonicalWorkflow-test.mjs` - add end-to-end reducer workflow coverage.
- Create `scripts/consoleCanonicalRuntimeContract-test.mjs` - static no-legacy-runtime contract.
- Modify `scripts/pendingBoundaryGuard-test.mjs` and `scripts/pendingItemRecovery-test.mjs` - update expectations from legacy helper names to canonical dispatch/events.
- Modify `scripts/console-responsive-stress.mjs` - assert selector-rendered draft lock and boundary sheet remain readable.
- Regenerate `fixthis-mcp/src/main/resources/console/app.js` and `app.js.map`.
- Modify `docs/reference/feedback-console-contract.md` - document runtime canonical ownership and no browser legacy state support.

## Task 1: Put Canonical Tests On The Default Console Gate

**Files:**
- Modify: `package.json`
- Create: `scripts/consoleCanonicalRuntimeContract-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Add failing package script expectation**

Create `scripts/consoleCanonicalRuntimeContract-test.mjs` with:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function source(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

test('console:test:all includes the canonical group', () => {
  const pkg = JSON.parse(source('package.json'));
  assert.match(pkg.scripts['console:test:all'], /canonical/);
});

test('canonical group includes runtime contract test', () => {
  const groups = JSON.parse(source('scripts/console-tests.json'));
  assert.ok(groups.canonical.includes('scripts/consoleCanonicalRuntimeContract-test.mjs'));
});
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs
```

Expected: FAIL because `console:test:all` does not include `canonical` and the
canonical group does not include the new contract test yet.

- [ ] **Step 3: Include canonical in the default suite**

In `package.json`, change:

```json
"console:test:all": "node scripts/run-console-tests.mjs availability pending beforeunload undo activity preview draft session harness"
```

to:

```json
"console:test:all": "node scripts/run-console-tests.mjs availability canonical pending beforeunload undo activity preview draft session harness"
```

In `scripts/console-tests.json`, append the new test to the `canonical` array:

```json
"scripts/consoleCanonicalRuntimeContract-test.mjs"
```

- [ ] **Step 4: Verify**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs
npm run console:test:all
```

Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add package.json scripts/console-tests.json scripts/consoleCanonicalRuntimeContract-test.mjs
git commit -m "test(console): gate canonical runtime coverage"
```

## Task 2: Expand Canonical Reducer For Live Runtime Commands

**Files:**
- Modify: `fixthis-mcp/src/main/console/domain/consoleReducer.js`
- Modify: `fixthis-mcp/src/main/console/domain/workspaceState.js`
- Modify: `fixthis-mcp/src/main/console/domain/consoleAppState.js`
- Modify: `fixthis-mcp/src/main/console/domain/consoleInvariants.js`
- Modify: `scripts/consoleCanonicalState-test.mjs`
- Modify: `scripts/consoleCanonicalWorkflow-test.mjs`

- [ ] **Step 1: Add failing reducer tests**

Append these tests to `scripts/consoleCanonicalState-test.mjs`:

```js
test('draft item focus, comment, delete, and selection clear stay inside workspace', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [session('session-a')] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview('a') }).state;
  state = m.reduceConsoleAppState(state, {
    type: 'DRAFT_TARGET_SELECTED',
    itemId: 'item-1',
    selection: { bounds: { left: 1, top: 2, right: 30, bottom: 40 }, label: 'Button' },
  }).state;
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_COMMENT_CHANGED', itemId: 'item-1', comment: 'Fix copy' }).state;
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_SELECTION_CLEARED' }).state;

  assert.equal(state.workspace.items[0].comment, 'Fix copy');
  assert.equal(state.workspace.focusedItemId, 'item-1');
  assert.equal(state.workspace.currentSelection, null);

  state = m.reduceConsoleAppState(state, { type: 'DRAFT_ITEM_DELETED', itemId: 'item-1' }).state;
  assert.deepEqual(state.workspace.items, []);
  assert.equal(state.workspace.focusedItemId, null);
  m.assertConsoleInvariants(state);
});

test('stale draft save failure clears in-flight only when generation matches', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [session('session-a')] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview('a') }).state;
  const save = m.reduceConsoleAppState(state, { type: 'SAVE_TO_MCP_CLICKED' });
  state = save.state;

  const stale = m.reduceConsoleAppState(state, {
    type: 'DRAFT_SAVE_FAILED',
    requestId: 'old',
    sessionId: 'session-a',
    workspaceId: state.workspace.context.workspaceId,
    generation: state.effectsGeneration - 1,
    error: 'old failure',
  }).state;
  assert.equal(stale.promptAction.inFlight, true);

  const current = m.reduceConsoleAppState(state, {
    type: 'DRAFT_SAVE_FAILED',
    requestId: save.effects[0].requestId,
    sessionId: 'session-a',
    workspaceId: state.workspace.context.workspaceId,
    generation: state.effectsGeneration,
    error: 'current failure',
  }).state;
  assert.equal(current.promptAction.inFlight, false);
  assert.equal(current.status.variant, 'error');
});
```

Append this workflow test to `scripts/consoleCanonicalWorkflow-test.mjs`:

```js
test('save during pending boundary opens target session after draft save succeeds', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [{ sessionId: 'session-a' }, { sessionId: 'session-b' }],
  });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) }).state;
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_TARGET_SELECTED', itemId: 'item-1', selection: { label: 'CTA' }, comment: 'Fix' }).state;
  state = m.reduceConsoleAppState(state, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' }).state;
  const save = m.reduceConsoleAppState(state, { type: 'BOUNDARY_SAVE_DRAFT_CLICKED' });

  assert.equal(save.effects[0].kind, 'saveDraft');
  assert.equal(save.effects[0].sessionId, 'session-a');
  assert.equal(save.effects[0].targetSessionId, 'session-b');

  const saved = m.reduceConsoleAppState(save.state, {
    type: 'DRAFT_SAVE_SUCCEEDED',
    requestId: save.effects[0].requestId,
    sessionId: 'session-a',
    targetSessionId: 'session-b',
    workspaceId: save.effects[0].workspaceId,
    generation: save.effects[0].generation,
    session: { sessionId: 'session-a', items: [] },
  });
  assert.equal(saved.state.activeSessionId, 'session-b');
  assert.equal(saved.state.workspace.kind, m.WorkspaceKind.LIVE_PREVIEW);
  m.assertConsoleInvariants(saved.state);
});
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
node scripts/run-console-tests.mjs canonical
```

Expected: FAIL because `DRAFT_ITEM_FOCUSED`, `DRAFT_ITEM_DELETED`,
`DRAFT_SELECTION_CLEARED`, and `DRAFT_SAVE_FAILED` are not fully implemented.

- [ ] **Step 3: Implement reducer events**

In `consoleReducer.js`, add cases:

```js
case 'DRAFT_ITEM_FOCUSED':
  result = reduceDraftItemFocused(state, event);
  break;
case 'DRAFT_ITEM_DELETED':
  result = reduceDraftItemDeleted(state, event);
  break;
case 'DRAFT_SELECTION_CLEARED':
  result = isDraftWorkspace(state.workspace)
    ? { state: replaceConsoleState(state, { workspace: draftWorkspaceWithPatch(state.workspace, { currentSelection: null }) }), effects: [] }
    : { state, effects: [] };
  break;
case 'DRAFT_DISCARDED':
  result = reduceDraftDiscarded(state);
  break;
case 'DRAFT_SAVE_FAILED':
  result = reduceDraftSaveFailed(state, event);
  break;
```

Add helper functions:

```js
function reduceDraftItemFocused(state, event) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const item = state.workspace.items.find((candidate) => candidate.itemId === event.itemId || candidate.annotationId === event.itemId);
  if (!item) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      workspace: draftWorkspaceWithPatch(state.workspace, {
        focusedItemId: item.itemId,
        currentSelection: item.selection || null,
      }),
    }),
    effects: [],
  };
}

function reduceDraftItemDeleted(state, event) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const items = state.workspace.items.filter((item) => item.itemId !== event.itemId && item.annotationId !== event.itemId);
  const focusedItemId = state.workspace.focusedItemId === event.itemId ? null : state.workspace.focusedItemId;
  return {
    state: replaceConsoleState(state, {
      workspace: draftWorkspaceWithPatch(state.workspace, {
        items,
        focusedItemId,
        currentSelection: focusedItemId ? state.workspace.currentSelection : null,
      }),
    }),
    effects: [],
  };
}

function reduceDraftDiscarded(state) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      workspace: livePreviewWorkspace(state.workspace.context.sessionId, null),
      pendingBoundary: null,
      promptAction: Object.freeze({ inFlight: false }),
      tool: Object.freeze({ mode: 'select' }),
    }),
    effects: [Object.freeze({
      kind: 'deleteRecovery',
      sessionId: state.workspace.context.sessionId,
      workspaceId: state.workspace.context.workspaceId,
    })],
  };
}

function reduceDraftSaveFailed(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  if (event.workspaceId && event.workspaceId !== state.workspace.context.workspaceId) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      promptAction: Object.freeze({ inFlight: false }),
      status: Object.freeze({ message: event.error || 'Could not save draft.', variant: 'error', assertive: true }),
    }),
    effects: [],
  };
}
```

- [ ] **Step 4: Verify**

Run:

```bash
node scripts/run-console-tests.mjs canonical
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/domain/consoleReducer.js scripts/consoleCanonicalState-test.mjs scripts/consoleCanonicalWorkflow-test.mjs
git commit -m "feat(console): complete canonical draft reducer events"
```

## Task 3: Complete Effect Execution Through Ports

**Files:**
- Modify: `fixthis-mcp/src/main/console/application/consoleEffects.js`
- Modify: `fixthis-mcp/src/main/console/adapters/browserPorts.js`
- Modify: `scripts/consoleEffects-test.mjs`

- [ ] **Step 1: Add failing effect tests**

Append to `scripts/consoleEffects-test.mjs`:

```js
test('copyPrompt effect writes clipboard and dispatches success', async () => {
  const dispatched = [];
  await m.runConsoleEffect(
    { kind: 'copyPrompt', requestId: 'copy-1', sessionId: 'session-a', workspaceId: 'ws-1', markdown: 'hello', generation: 3 },
    {
      dispatch: (event) => dispatched.push(event),
      ports: { clipboard: { writeText: async (text) => assert.equal(text, 'hello') } },
    },
  );
  assert.deepEqual(dispatched, [{
    type: 'PROMPT_COPY_SUCCEEDED',
    requestId: 'copy-1',
    sessionId: 'session-a',
    workspaceId: 'ws-1',
    generation: 3,
  }]);
});

test('deleteRecovery effect uses draft storage port', async () => {
  const calls = [];
  const dispatched = [];
  await m.runConsoleEffect(
    { kind: 'deleteRecovery', sessionId: 'session-a', workspaceId: 'ws-1' },
    {
      dispatch: (event) => dispatched.push(event),
      ports: { draftStorage: { deleteRecovery: async (...args) => calls.push(args) } },
    },
  );
  assert.deepEqual(calls, [['session-a', 'ws-1']]);
  assert.deepEqual(dispatched, [{ type: 'RECOVERY_DELETED', sessionId: 'session-a', workspaceId: 'ws-1' }]);
});
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
node --test scripts/consoleEffects-test.mjs
```

Expected: FAIL because `copyPrompt` and `deleteRecovery` are not implemented.

- [ ] **Step 3: Implement effects**

In `consoleEffects.js`, add cases:

```js
case 'copyPrompt':
  await ports.clipboard.writeText(effect.markdown || '');
  dispatch({
    type: 'PROMPT_COPY_SUCCEEDED',
    requestId: effect.requestId,
    sessionId: effect.sessionId,
    workspaceId: effect.workspaceId,
    generation: effect.generation,
  });
  return;
case 'deleteRecovery':
  await ports.draftStorage.deleteRecovery(effect.sessionId, effect.workspaceId);
  dispatch({ type: 'RECOVERY_DELETED', sessionId: effect.sessionId, workspaceId: effect.workspaceId });
  return;
case 'showStatus':
  dispatch({ type: 'STATUS_SHOWN', message: effect.message, variant: effect.variant, assertive: effect.assertive === true });
  return;
```

In `browserPorts.js`, extend `draftStorage`:

```js
deleteRecovery: async (sessionId, workspaceId) => {
  const prefix = 'fixthis.draftWorkspace.' + sessionId + '.';
  if (workspaceId) {
    localStorage_.removeItem(prefix + workspaceId);
  }
  localStorage_.removeItem('fixthis.recovery.' + sessionId);
},
```

- [ ] **Step 4: Verify**

Run:

```bash
node --test scripts/consoleEffects-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/application/consoleEffects.js fixthis-mcp/src/main/console/adapters/browserPorts.js scripts/consoleEffects-test.mjs
git commit -m "feat(console): execute canonical runtime effects"
```

## Task 4: Bootstrap The Canonical Store In Runtime

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/application/consoleStore.js`
- Modify: `fixthis-mcp/src/main/console/adapters/browserRenderer.js`
- Modify: `scripts/consoleCanonicalRuntimeContract-test.mjs`

- [ ] **Step 1: Add failing runtime bootstrap contract**

Append to `scripts/consoleCanonicalRuntimeContract-test.mjs`:

```js
test('main bootstrap creates canonical store, ports, renderer, and effect runner', () => {
  const main = source('fixthis-mcp/src/main/console/main.js');
  assert.match(main, /createBrowserConsolePorts\(/);
  assert.match(main, /createBrowserRenderer\(/);
  assert.match(main, /createConsoleStore\(/);
  assert.match(main, /runConsoleEffect\(/);
  assert.match(main, /store\.dispatch\(/);
});

test('console store checks invariants before rendering', () => {
  const store = source('fixthis-mcp/src/main/console/application/consoleStore.js');
  assert.match(store, /assertConsoleInvariants\(current\)/);
  assert.match(store, /assertConsoleInvariants\(result\.state\)/);
});
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs
```

Expected: FAIL because `main.js` does not bootstrap the canonical store.

- [ ] **Step 3: Update store invariant enforcement**

In `consoleStore.js`, change the `@requires` header:

```js
// @requires domain/consoleReducer.js, domain/consoleInvariants.js
```

Update `createConsoleStore`:

```js
function createConsoleStore(options = {}) {
  let current = options.initialState || createInitialConsoleAppState();
  const render = typeof options.render === 'function' ? options.render : () => {};
  const onEffects = typeof options.onEffects === 'function' ? options.onEffects : () => {};
  assertConsoleInvariants(current);
  function getState() {
    return current;
  }
  function dispatch(event) {
    const result = reduceConsoleAppState(current, event);
    assertConsoleInvariants(result.state);
    current = result.state;
    render(current);
    onEffects(result.effects, current);
    return result.effects;
  }
  render(current);
  return Object.freeze({ getState, dispatch });
}
```

- [ ] **Step 4: Bootstrap canonical runtime in `main.js`**

Add required modules to the `main.js` header:

```js
// @requires state.js, connection.js, devices.js, preview.js, annotations.js, history.js, prompt.js, rendering.js, sessions-polling.js, shortcuts.js, draftUseCases.js, draftCommandQueue.js, application/consoleStore.js, application/consoleEffects.js, adapters/browserPorts.js, adapters/browserRenderer.js
```

Near the top of runtime initialization, create the store with a `let` binding so
effect callbacks can dispatch back into the initialized store:

```js
const canonicalPorts = createBrowserConsolePorts({
  requestJson,
  localStorage,
  navigator,
});
const canonicalRenderer = createBrowserRenderer({
  renderHistory: renderCanonicalHistoryModel,
  renderCanvas: renderCanonicalCanvasModel,
  renderInspector: renderCanonicalInspectorModel,
  renderPrompt: renderCanonicalPromptModel,
  renderBoundary: renderCanonicalBoundaryModel,
});
let store;
store = createConsoleStore({
  render: canonicalRenderer.render,
  onEffects: (effects) => {
    for (const effect of effects) {
      runConsoleEffect(effect, { ports: canonicalPorts, dispatch: store.dispatch }).catch(showError);
    }
  },
});
```

- [ ] **Step 5: Add temporary canonical renderer bridge functions**

In `browserRenderer.js`, keep the existing `createBrowserRenderer` signature.
In `rendering.js`, add these no-op-safe bridge functions:

```js
function renderCanonicalHistoryModel(model) {
  if (model) renderCurrentSessionList();
}

function renderCanonicalCanvasModel(model) {
  if (model) renderPreviewOnly();
}

function renderCanonicalInspectorModel(model) {
  if (model) renderInspectorRegion();
}

function renderCanonicalPromptModel(model) {
  if (model) renderPromptPreview();
}

function renderCanonicalBoundaryModel(model) {
  renderSessionBoundarySheet(model);
}
```

These bridge functions are temporary within this task sequence. Later tasks
replace their internals with model-only rendering.

- [ ] **Step 6: Verify**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs
node --test scripts/build-console-assets-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/main.js fixthis-mcp/src/main/console/application/consoleStore.js fixthis-mcp/src/main/console/adapters/browserRenderer.js fixthis-mcp/src/main/console/rendering.js scripts/consoleCanonicalRuntimeContract-test.mjs
git commit -m "feat(console): bootstrap canonical runtime store"
```

## Task 5: Route Draft Editing Through Canonical Dispatch

**Files:**
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `fixthis-mcp/src/main/console/shortcuts.js`
- Modify: `fixthis-mcp/src/main/console/undoRedo.js`
- Modify: `scripts/consoleCanonicalRuntimeContract-test.mjs`
- Modify: `scripts/draftPresentationContract-test.mjs`

- [ ] **Step 1: Add failing no-legacy draft contract**

Append to `scripts/consoleCanonicalRuntimeContract-test.mjs`:

```js
test('runtime modules do not mutate legacy draft globals', () => {
  const files = [
    'fixthis-mcp/src/main/console/main.js',
    'fixthis-mcp/src/main/console/annotations.js',
    'fixthis-mcp/src/main/console/preview.js',
    'fixthis-mcp/src/main/console/history.js',
    'fixthis-mcp/src/main/console/rendering.js',
    'fixthis-mcp/src/main/console/prompt.js',
    'fixthis-mcp/src/main/console/shortcuts.js',
  ];
  for (const file of files) {
    const text = source(file);
    assert.doesNotMatch(text, /\bactiveDraftFlow\b/, file);
    assert.doesNotMatch(text, /\bdraftFeedbackItems\b/, file);
    assert.doesNotMatch(text, /\bfocusedPendingItemIndex\b/, file);
    assert.doesNotMatch(text, /\bcurrentSelection\b/, file);
    assert.doesNotMatch(text, /resetCanonicalAnnotationComposerState\(/, file);
    assert.doesNotMatch(text, /invalidateCanonicalPreviewContext\(/, file);
  }
});
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs
```

Expected: FAIL because runtime modules still reference legacy globals.

- [ ] **Step 3: Replace draft add/focus/delete/comment calls**

In `annotations.js`, convert draft mutations to dispatch helpers:

```js
function dispatchDraftTargetSelected(selection, targetEvidence = null) {
  store.dispatch({ type: 'DRAFT_TARGET_SELECTED', selection, targetEvidence });
}

function dispatchDraftCommentChanged(itemId, value) {
  store.dispatch({ type: 'DRAFT_COMMENT_CHANGED', itemId, comment: value });
}

function dispatchDraftItemFocused(itemId) {
  store.dispatch({ type: 'DRAFT_ITEM_FOCUSED', itemId });
}

function dispatchDraftItemDeleted(itemId) {
  store.dispatch({ type: 'DRAFT_ITEM_DELETED', itemId });
}

function dispatchDraftSelectionCleared() {
  store.dispatch({ type: 'DRAFT_SELECTION_CLEARED' });
}
```

Replace direct mutations:

```js
currentSelection = selection;
focusedPendingItemIndex = index;
draftFeedbackItems.push(item);
draftFeedbackItems.splice(index, 1);
```

with the dispatch helpers above.

- [ ] **Step 4: Convert undo/redo helpers to immutable operations**

In `undoRedo.js`, add pure helpers:

```js
function applyUndoToItems(history, items, context) {
  const holder = { draftFeedbackItems: items.slice() };
  const result = undo(history, holder, context);
  return Object.freeze({ ...result, items: holder.draftFeedbackItems.slice() });
}

function applyRedoToItems(history, items, context) {
  const holder = { draftFeedbackItems: items.slice() };
  const result = redo(history, holder, context);
  return Object.freeze({ ...result, items: holder.draftFeedbackItems.slice() });
}
```

Then route keyboard handlers through `store.dispatch({ type: 'UNDO_CLICKED' })`
and `store.dispatch({ type: 'REDO_CLICKED' })`.

In `consoleReducer.js`, add explicit no-op reducer handling for the keyboard
events so runtime dispatch is stable before richer undo history moves into
canonical state:

```js
case 'UNDO_CLICKED':
case 'REDO_CLICKED':
  result = { state, effects: [] };
  break;
```

- [ ] **Step 5: Verify**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs
node scripts/run-console-tests.mjs canonical draft undo
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/console/rendering.js fixthis-mcp/src/main/console/shortcuts.js fixthis-mcp/src/main/console/undoRedo.js scripts/consoleCanonicalRuntimeContract-test.mjs scripts/draftPresentationContract-test.mjs
git commit -m "refactor(console): route draft editing through canonical state"
```

## Task 6: Route Preview, Prompt, And Session Boundaries Through Canonical Dispatch

**Files:**
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/devices.js`
- Modify: `fixthis-mcp/src/main/console/connection.js`
- Modify: `scripts/pendingBoundaryGuard-test.mjs`
- Modify: `scripts/pendingItemRecovery-test.mjs`
- Modify: `scripts/consoleCanonicalWorkflow-test.mjs`

- [ ] **Step 1: Add failing canonical workflow tests**

Append to `scripts/consoleCanonicalWorkflow-test.mjs`:

```js
test('annotate click plans preview capture and capture success starts draft', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  const annotate = m.reduceConsoleAppState(state, { type: 'ANNOTATE_CLICKED' });
  assert.equal(annotate.effects[0].kind, 'capturePreview');
  state = m.reduceConsoleAppState(annotate.state, {
    type: 'PREVIEW_CAPTURE_SUCCEEDED',
    requestId: annotate.effects[0].requestId,
    sessionId: 'session-a',
    generation: annotate.effects[0].generation,
    preview: preview(1),
  }).state;
  const draft = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) }).state;
  assert.equal(draft.workspace.kind, m.WorkspaceKind.DRAFT);
});

test('copy prompt is planned from draft workspace only', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  assert.deepEqual(m.reduceConsoleAppState(state, { type: 'COPY_PROMPT_CLICKED' }).effects, []);
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) }).state;
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_TARGET_SELECTED', itemId: 'item-1', selection: { label: 'CTA' }, comment: 'Fix' }).state;
  const copy = m.reduceConsoleAppState(state, { type: 'COPY_PROMPT_CLICKED' });
  assert.equal(copy.effects[0].kind, 'copyPrompt');
  assert.match(copy.effects[0].markdown, /Fix/);
});
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
node scripts/run-console-tests.mjs canonical pending
```

Expected: FAIL until prompt copy and boundary paths are canonicalized.

- [ ] **Step 3: Implement prompt copy reducer**

In `consoleReducer.js`, add:

```js
case 'COPY_PROMPT_CLICKED':
  result = reduceCopyPromptClicked(state);
  break;
case 'PROMPT_COPY_SUCCEEDED':
  result = reducePromptCopySucceeded(state, event);
  break;
case 'PROMPT_COPY_FAILED':
  result = reducePromptCopyFailed(state, event);
  break;
```

Add helpers:

```js
function reduceCopyPromptClicked(state) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const generation = nextGeneration(state);
  const markdown = state.workspace.items
    .map((item, index) => `${index + 1}. ${item.comment || ''}`.trim())
    .join('\n');
  return {
    state: replaceConsoleState(state, {
      effectsGeneration: generation,
      promptAction: Object.freeze({ inFlight: true }),
    }),
    effects: [Object.freeze({
      kind: 'copyPrompt',
      requestId: nextRequestId('copy-prompt', generation),
      sessionId: state.workspace.context.sessionId,
      workspaceId: state.workspace.context.workspaceId,
      markdown,
      generation,
    })],
  };
}

function reducePromptCopySucceeded(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      promptAction: Object.freeze({ inFlight: false }),
      status: Object.freeze({ message: 'Prompt copied.', variant: 'success', assertive: false }),
    }),
    effects: [],
  };
}

function reducePromptCopyFailed(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      promptAction: Object.freeze({ inFlight: false }),
      status: Object.freeze({ message: event.error || 'Could not copy prompt.', variant: 'error', assertive: true }),
    }),
    effects: [],
  };
}
```

- [ ] **Step 4: Convert session and prompt modules**

In `history.js`, replace session switch entry points with:

```js
function openSession(sessionId) {
  store.dispatch({ type: 'SESSION_ROW_CLICKED', sessionId });
}
```

In `preview.js`, replace annotate/capture entry points with:

```js
function requestCanonicalPreviewCapture() {
  store.dispatch({ type: 'ANNOTATE_CLICKED' });
}
```

In `prompt.js`, replace copy/save button handlers with:

```js
function copyPrompt() {
  store.dispatch({ type: 'COPY_PROMPT_CLICKED' });
  return Promise.resolve();
}

function savePendingFeedbackItems() {
  store.dispatch({ type: 'SAVE_TO_MCP_CLICKED' });
  return Promise.resolve();
}
```

Update tests that asserted legacy helper names so they assert dispatch events
instead:

```js
assert.match(sourceText, /store\.dispatch\(\{\s*type:\s*'SESSION_ROW_CLICKED'/);
assert.doesNotMatch(sourceText, /resetCanonicalAnnotationComposerState\(/);
assert.doesNotMatch(sourceText, /invalidateCanonicalPreviewContext\(/);
```

- [ ] **Step 5: Verify**

Run:

```bash
node scripts/run-console-tests.mjs canonical pending session
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/preview.js fixthis-mcp/src/main/console/prompt.js fixthis-mcp/src/main/console/history.js fixthis-mcp/src/main/console/devices.js fixthis-mcp/src/main/console/connection.js fixthis-mcp/src/main/console/domain/consoleReducer.js scripts/pendingBoundaryGuard-test.mjs scripts/pendingItemRecovery-test.mjs scripts/consoleCanonicalWorkflow-test.mjs
git commit -m "refactor(console): route preview prompt and boundaries through canonical state"
```

## Task 7: Render From Selector Models Only

**Files:**
- Modify: `fixthis-mcp/src/main/console/domain/consoleSelectors.js`
- Modify: `fixthis-mcp/src/main/console/adapters/browserRenderer.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `scripts/consoleSelectors-test.mjs`
- Modify: `scripts/consoleCanonicalRuntimeContract-test.mjs`
- Modify: `scripts/console-responsive-stress.mjs`

- [ ] **Step 1: Add failing selector/render contracts**

Append to `scripts/consoleSelectors-test.mjs`:

```js
test('draft lock model describes canonical draft context', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview() }).state;
  const model = m.selectDraftLockModel(state);
  assert.equal(model.visible, true);
  assert.equal(model.sessionId, 'session-a');
  assert.equal(model.previewId, 'preview-a');
});

test('toolbar model disables live preview actions while draft is locked', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview() }).state;
  const model = m.selectToolbarModel(state);
  assert.equal(model.previewLocked, true);
  assert.equal(model.canAnnotate, false);
});
```

Append to `scripts/consoleCanonicalRuntimeContract-test.mjs`:

```js
test('rendering bridge functions accept models and do not read legacy globals', () => {
  const rendering = source('fixthis-mcp/src/main/console/rendering.js');
  for (const fn of [
    'renderCanonicalHistoryModel',
    'renderCanonicalCanvasModel',
    'renderCanonicalInspectorModel',
    'renderCanonicalPromptModel',
    'renderCanonicalBoundaryModel',
  ]) {
    assert.match(rendering, new RegExp('function ' + fn + '\\\\(model'));
  }
});
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
node scripts/run-console-tests.mjs canonical
```

Expected: FAIL because `selectDraftLockModel` and `selectToolbarModel` are
missing or incomplete.

- [ ] **Step 3: Implement selectors**

In `consoleSelectors.js`, add:

```js
function selectDraftLockModel(state) {
  if (!isDraftWorkspace(state.workspace)) return Object.freeze({ visible: false });
  return Object.freeze({
    visible: true,
    sessionId: state.workspace.context.sessionId,
    previewId: state.workspace.context.previewId,
    screenId: state.workspace.context.screenId,
    itemCount: state.workspace.items.length,
    missingCommentCount: state.workspace.items.filter((item) => !String(item.comment || '').trim()).length,
    frozenAtEpochMillis: state.workspace.context.frozenAtEpochMillis || null,
  });
}

function selectToolbarModel(state) {
  const draft = isDraftWorkspace(state.workspace);
  return Object.freeze({
    activeSessionId: state.activeSessionId,
    previewLocked: draft,
    canAnnotate: Boolean(state.activeSessionId) && !draft,
    canSave: draft && state.workspace.items.length > 0 && !state.promptAction.inFlight,
    canCopy: draft && state.workspace.items.length > 0 && !state.promptAction.inFlight,
  });
}
```

Ensure the test factory in `scripts/consoleSelectors-test.mjs` returns these
functions.

- [ ] **Step 4: Replace bridge render internals**

In `rendering.js`, change canonical render functions to use model data only.
For example:

```js
function renderCanonicalBoundaryModel(model) {
  const sheet = document.getElementById('sessionBoundarySheet');
  if (!sheet) return;
  sheet.hidden = !model?.visible;
  if (!model?.visible) return;
  sheet.querySelector('[data-boundary-count]').textContent = String(model.itemCount);
}

function renderCanonicalPromptModel(model) {
  copyPromptButton.disabled = !model.canCopy;
  sendAgentButton.disabled = !model.canSave;
}
```

Remove model bridge calls that immediately fall back to global-state renderers.

- [ ] **Step 5: Verify**

Run:

```bash
node scripts/run-console-tests.mjs canonical
node scripts/console-responsive-stress.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/domain/consoleSelectors.js fixthis-mcp/src/main/console/adapters/browserRenderer.js fixthis-mcp/src/main/console/rendering.js scripts/consoleSelectors-test.mjs scripts/consoleCanonicalRuntimeContract-test.mjs scripts/console-responsive-stress.mjs
git commit -m "refactor(console): render canonical selector models"
```

## Task 8: Remove Legacy Runtime State Declarations

**Files:**
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `scripts/consoleCanonicalRuntimeContract-test.mjs`
- Modify: `scripts/consoleFsmContract-test.mjs`

- [ ] **Step 1: Add failing state declaration contract**

Append to `scripts/consoleCanonicalRuntimeContract-test.mjs`:

```js
test('state module no longer declares legacy draft runtime state', () => {
  const state = source('fixthis-mcp/src/main/console/state.js');
  assert.doesNotMatch(state, /\blet\s+activeDraftFlow\b/);
  assert.doesNotMatch(state, /\blet\s+draftFeedbackItems\b/);
  assert.doesNotMatch(state, /\blet\s+focusedPendingItemIndex\b/);
  assert.doesNotMatch(state, /\blet\s+currentSelection\b/);
  assert.doesNotMatch(state, /function\s+setDraftWorkspace\(/);
});
```

- [ ] **Step 2: Run and verify failure**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs
```

Expected: FAIL because `state.js` still declares legacy state.

- [ ] **Step 3: Remove legacy declarations and setters**

In `state.js`, delete:

```js
let activeDraftFlow = null;
let draftFeedbackItems = [];
let focusedPendingItemIndex = null;
let currentSelection = null;
```

Delete legacy draft setters that mutate these variables. Keep constants, DOM
element references, and generic helpers that do not own state.

- [ ] **Step 4: Update obsolete FSM contract comments**

In `scripts/consoleFsmContract-test.mjs`, remove comments that describe
`activeDraftFlow`, `draftFeedbackItems`, `focusedPendingItemIndex`, or
`currentSelection` as tolerated runtime holders. Replace them with:

```js
// Canonical runtime completion removed legacy draft globals. Strict assertions
// below now apply to runtime modules as well as reducer/selector modules.
```

- [ ] **Step 5: Verify**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs
node scripts/run-console-tests.mjs canonical
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/state.js scripts/consoleCanonicalRuntimeContract-test.mjs scripts/consoleFsmContract-test.mjs
git commit -m "refactor(console): remove legacy draft runtime state"
```

## Task 9: Regenerate Bundle And Update Contract Docs

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/main/resources/console/app.js.map`
- Modify: `docs/reference/feedback-console-contract.md`

- [ ] **Step 1: Regenerate console bundle**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
```

Expected: generated bundle and source map update if source changed.

- [ ] **Step 2: Add docs section**

In `docs/reference/feedback-console-contract.md`, add or update a section named
`Canonical Runtime State`:

```markdown
### Canonical Runtime State

The browser console runtime uses `ConsoleAppState` as the single owner of
session, preview, draft, selected target, focused item, pending boundary, and
prompt readiness state. Browser-internal legacy state holders such as
`activeDraftFlow`, `draftFeedbackItems`, `focusedPendingItemIndex`, and
`currentSelection` are not supported.

DOM handlers dispatch commands to the canonical store. Reducers return effect
descriptions. Browser ports execute those effects and dispatch result events.
Renderers consume selector models and do not mutate session, preview, or draft
state directly.

This does not change MCP tool contracts, HTTP route payloads, persisted
feedback-session JSON, or local draft storage migration compatibility.
```

- [ ] **Step 3: Verify generated bundle**

Run:

```bash
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: both PASS.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map docs/reference/feedback-console-contract.md
git commit -m "docs(console): document canonical runtime state"
```

## Task 10: Final Verification Matrix

**Files:**
- No source edits expected unless verification exposes a defect.

- [ ] **Step 1: Run console unit suites**

Run:

```bash
npm run console:test:all
node scripts/run-console-tests.mjs canonical
node --test scripts/build-console-assets-test.mjs
```

Expected: PASS.

- [ ] **Step 2: Run browser stress and bundle checks**

Run:

```bash
node scripts/console-responsive-stress.mjs
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: PASS and no uncommitted generated drift after the reproducible build.

- [ ] **Step 3: Run Kotlin console contract tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*Console*' --tests '*BuildInfoTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 4: Run whitespace checks**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Inspect final legacy scan**

Run:

```bash
rg -n "activeDraftFlow|draftFeedbackItems|focusedPendingItemIndex|currentSelection|resetCanonicalAnnotationComposerState|invalidateCanonicalPreviewContext|state\\.session\\s*=|state\\.preview\\s*=" fixthis-mcp/src/main/console scripts
```

Expected: no runtime source matches. Test fixture matches are allowed only in
tests that assert absence; if matches remain in tests, verify they are inside
`assert.doesNotMatch` or explanatory comments.

- [ ] **Step 6: Record final status**

If verification exposes a defect, return to the task that owns the affected
file, fix it there, rerun that task's focused verification, and commit with
that task's commit message pattern. Do not create an empty final commit.

## Completion Criteria

- `npm run console:test:all` passes and includes `canonical`.
- `node scripts/run-console-tests.mjs canonical` passes.
- `node scripts/console-responsive-stress.mjs` passes.
- `node scripts/build-console-assets.mjs --check` passes.
- `./gradlew :fixthis-mcp:test --tests '*Console*' --tests '*BuildInfoTest' --no-daemon` passes.
- `state.js` no longer declares legacy draft globals.
- Runtime modules do not read or write removed legacy draft globals.
- Save/copy/session-boundary workflows route through canonical reducer/effects.
- Bundle is regenerated and committed.
- Contract docs describe no browser-internal legacy state support.
