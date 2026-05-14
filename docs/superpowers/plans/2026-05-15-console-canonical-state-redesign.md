# Console Canonical State Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace FixThis Studio's scattered browser console state with one canonical `ConsoleAppState` so session, draft annotation, preview, and handoff transitions cannot drift out of sync.

**Architecture:** This is a big-bang browser-console refactor with no legacy browser state compatibility layer. Domain reducers and selectors are pure, application effects are fenced by request/session/workspace/generation context, and browser modules dispatch commands and render selector view models only.

**Tech Stack:** Plain JavaScript ES2020, Node `node:test`, existing console bundle pipeline (`scripts/build-console-assets.mjs`), existing browser console assets in `fixthis-mcp/src/main/console`, and existing local HTTP APIs.

---

## Reference Spec

Read this before starting implementation:

- `docs/superpowers/specs/2026-05-15-console-canonical-state-redesign-design.md`

The implementation must preserve MCP tool contracts and server API routes. The old browser-internal state model does not need compatibility.

## File Structure

Create these new domain/application/adapter files:

- `fixthis-mcp/src/main/console/domain/workspaceState.js`  
  Owns workspace tags, constructors, and workspace helper predicates.
- `fixthis-mcp/src/main/console/domain/consoleAppState.js`  
  Owns initial app state, request id generation shape, and session normalization helpers.
- `fixthis-mcp/src/main/console/domain/consoleInvariants.js`  
  Throws when canonical state invariants are violated.
- `fixthis-mcp/src/main/console/domain/consoleReducer.js`  
  Pure reducer. Returns `{ state, effects }`.
- `fixthis-mcp/src/main/console/domain/consoleSelectors.js`  
  Pure view-model selectors for history, toolbar, canvas, inspector, prompt, and boundary UI.
- `fixthis-mcp/src/main/console/application/consoleStore.js`  
  Dispatch loop. Applies reducer, checks invariants, runs render callback, queues effects.
- `fixthis-mcp/src/main/console/application/consoleEffects.js`  
  Runs effect descriptions through browser ports and dispatches result events.
- `fixthis-mcp/src/main/console/adapters/browserPorts.js`  
  Browser implementations for session API, preview API, draft storage, clipboard, timers, and clock.
- `fixthis-mcp/src/main/console/adapters/browserRenderer.js`  
  DOM rendering coordinator that consumes selector models.

Modify these existing files:

- `scripts/build-console-assets.mjs`  
  Include nested console source files in the `// @requires` graph.
- `scripts/build-console-assets-test.mjs`  
  Test nested module discovery and nested `// @requires` validation.
- `fixthis-mcp/src/main/console/main.js`  
  Bootstrap store, ports, renderer, and event binding.
- `fixthis-mcp/src/main/console/history.js`  
  Reduce to history rendering helpers and dispatch binding.
- `fixthis-mcp/src/main/console/annotations.js`  
  Reduce to annotation rendering helpers and dispatch binding.
- `fixthis-mcp/src/main/console/preview.js`  
  Reduce to preview rendering helpers and dispatch binding.
- `fixthis-mcp/src/main/console/prompt.js`  
  Reduce to prompt rendering helpers and dispatch binding.
- `fixthis-mcp/src/main/console/state.js`  
  Remove legacy mutable state or reduce to constants consumed by bootstrap.
- `fixthis-mcp/src/main/console/rendering.js`  
  Remove state mutation from render paths; render from selector models.
- `fixthis-mcp/src/main/resources/console/app.js` and `app.js.map`  
  Regenerate with reproducible build.
- `docs/reference/feedback-console-contract.md`  
  Update the console state-machine section to describe canonical state, selectors, and boundary sheet.

Create or modify these tests:

- `scripts/consoleCanonicalState-test.mjs`
- `scripts/consoleSelectors-test.mjs`
- `scripts/consoleEffects-test.mjs`
- `scripts/consoleCanonicalWorkflow-test.mjs`
- `scripts/build-console-assets-test.mjs`
- `scripts/pendingBoundaryGuard-test.mjs`
- `scripts/console-responsive-stress.mjs`
- `scripts/console-tests.json`

## Task 1: Make Console Bundle Source Discovery Recursive

**Files:**
- Modify: `scripts/build-console-assets.mjs`
- Modify: `scripts/build-console-assets-test.mjs`

- [ ] **Step 1: Write failing nested module discovery tests**

Add these tests to `scripts/build-console-assets-test.mjs`:

```js
test('build graph can include nested console modules', async () => {
  const { consoleSourceFiles } = await import('../scripts/build-console-assets.mjs');
  const files = consoleSourceFiles(resolve(root, 'fixthis-mcp/src/main/console'));
  assert.ok(files.includes('main.js'), 'main.js should be discovered');
  assert.ok(
    files.some((name) => name.includes('/')) || files.every((name) => !name.includes('/')),
    'discovery must return stable slash-normalized relative paths',
  );
});

test('parseRequires accepts nested module paths', async () => {
  const { parseRequires } = await import('../scripts/build-console-assets.mjs');
  const deps = parseRequires('// @requires domain/consoleReducer.js, adapters/browserPorts.js\n');
  assert.deepEqual(deps, ['domain/consoleReducer.js', 'adapters/browserPorts.js']);
});
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
node --test scripts/build-console-assets-test.mjs
```

Expected: FAIL because `consoleSourceFiles` is not exported.

- [ ] **Step 3: Implement recursive source discovery**

In `scripts/build-console-assets.mjs`, update imports:

```js
import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
```

Add this exported helper after `sourceDir`:

```js
export function consoleSourceFiles(rootDir) {
  const files = [];
  function walk(dir) {
    for (const entry of readdirSync(dir).sort()) {
      const absolute = join(dir, entry);
      const stat = statSync(absolute);
      if (stat.isDirectory()) {
        walk(absolute);
      } else if (entry.endsWith('.js')) {
        files.push(relative(rootDir, absolute).split('/').join('/'));
      }
    }
  }
  walk(rootDir);
  return files.sort();
}
```

Replace:

```js
const onDisk = readdirSync(sourceDir).filter((name) => name.endsWith('.js'));
```

with:

```js
const onDisk = consoleSourceFiles(sourceDir);
```

Replace all `resolve(sourceDir, name)` calls used to read source modules with `resolve(sourceDir, name)`. This remains correct because `name` is a slash-normalized relative path.

- [ ] **Step 4: Run build tests**

Run:

```bash
node --test scripts/build-console-assets-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/build-console-assets.mjs scripts/build-console-assets-test.mjs
git commit -m "build: support nested console modules"
```

## Task 2: Add Canonical State Domain and Reducer

**Files:**
- Create: `fixthis-mcp/src/main/console/domain/workspaceState.js`
- Create: `fixthis-mcp/src/main/console/domain/consoleAppState.js`
- Create: `fixthis-mcp/src/main/console/domain/consoleInvariants.js`
- Create: `fixthis-mcp/src/main/console/domain/consoleReducer.js`
- Create: `scripts/consoleCanonicalState-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write failing reducer and invariant tests**

Create `scripts/consoleCanonicalState-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'domain/workspaceState.js',
  'domain/consoleAppState.js',
  'domain/consoleInvariants.js',
  'domain/consoleReducer.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');

const factory = new Function(`${sources}; return {
  createInitialConsoleAppState,
  reduceConsoleAppState,
  assertConsoleInvariants,
  WorkspaceKind
};`);
const m = factory();

function session(id) {
  return {
    sessionId: id,
    status: 'active',
    items: [],
    screens: [],
    updatedAtEpochMillis: 1,
  };
}

function preview(id, screenId = 'screen-1') {
  return {
    previewId: id,
    screen: { screenId, fingerprint: 'fp-' + screenId, roots: [] },
    screenshotUrl: `/api/preview/${id}/screenshot/full?sessionId=session-a`,
    frozenAtEpochMillis: 10,
  };
}

test('same active session click is a no-op', () => {
  const initial = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [session('session-a')],
  });
  const before = JSON.stringify(initial);
  const result = m.reduceConsoleAppState(initial, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-a' });
  assert.equal(JSON.stringify(result.state), before);
  assert.deepEqual(result.effects, []);
  m.assertConsoleInvariants(result.state);
});

test('different session click with draft creates boundary without switching active session', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [session('session-a'), session('session-b')],
  });
  state = m.reduceConsoleAppState(state, {
    type: 'DRAFT_STARTED_FROM_PREVIEW',
    sessionId: 'session-a',
    preview: preview('preview-a'),
  }).state;
  const result = m.reduceConsoleAppState(state, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' });
  assert.equal(result.state.activeSessionId, 'session-a');
  assert.equal(result.state.workspace.kind, m.WorkspaceKind.DRAFT);
  assert.equal(result.state.pendingBoundary?.targetSessionId, 'session-b');
  assert.deepEqual(result.effects, []);
  m.assertConsoleInvariants(result.state);
});

test('late preview response cannot overwrite draft workspace', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [session('session-a')],
  });
  state = m.reduceConsoleAppState(state, {
    type: 'DRAFT_STARTED_FROM_PREVIEW',
    sessionId: 'session-a',
    preview: preview('preview-a'),
  }).state;
  const result = m.reduceConsoleAppState(state, {
    type: 'PREVIEW_CAPTURE_SUCCEEDED',
    requestId: 'stale',
    sessionId: 'session-a',
    generation: state.effectsGeneration - 1,
    preview: preview('preview-stale'),
  });
  assert.equal(result.state.workspace.kind, m.WorkspaceKind.DRAFT);
  assert.equal(result.state.workspace.context.previewId, 'preview-a');
  m.assertConsoleInvariants(result.state);
});
```

Add this group to `scripts/console-tests.json`:

```json
"canonical": [
  "scripts/consoleCanonicalState-test.mjs"
]
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
node --test scripts/consoleCanonicalState-test.mjs
```

Expected: FAIL because the domain files do not exist.

- [ ] **Step 3: Implement workspace state helpers**

Create `fixthis-mcp/src/main/console/domain/workspaceState.js`:

```js
// @requires (none)
const WorkspaceKind = Object.freeze({
  EMPTY: 'empty',
  LIVE_PREVIEW: 'livePreview',
  DRAFT: 'draft',
  SAVED_FOCUS: 'savedFocus',
  RECOVERY: 'recovery',
});

function emptyWorkspace() {
  return Object.freeze({ kind: WorkspaceKind.EMPTY });
}

function livePreviewWorkspace(sessionId, preview = null) {
  return Object.freeze({ kind: WorkspaceKind.LIVE_PREVIEW, sessionId: sessionId || null, preview: cloneConsoleValue(preview) });
}

function draftWorkspaceFromPreview(sessionId, preview, options = {}) {
  if (!sessionId) throw new Error('Draft workspace requires sessionId');
  if (!preview?.previewId) throw new Error('Draft workspace requires previewId');
  const screen = preview.screen || null;
  if (!screen?.screenId) throw new Error('Draft workspace requires screenId');
  return Object.freeze({
    kind: WorkspaceKind.DRAFT,
    context: Object.freeze({
      sessionId,
      previewId: preview.previewId,
      screenId: screen.screenId,
      screenFingerprint: screen.fingerprint ?? null,
      deviceSerial: options.deviceSerial || null,
      frozenAtEpochMillis: preview.frozenAtEpochMillis || options.frozenAtEpochMillis || null,
      activityName: preview.activity || options.activityName || null,
    }),
    screen: cloneConsoleValue(screen),
    screenshotUrl: preview.screenshotUrl || options.screenshotUrl || '',
    items: Object.freeze([...(options.items || [])].map(cloneConsoleValue)),
    focusedItemId: options.focusedItemId || null,
    currentSelection: cloneConsoleValue(options.currentSelection || null),
    activityDriftWarning: cloneConsoleValue(options.activityDriftWarning || null),
  });
}

function savedFocusWorkspace(sessionId, screenId, itemId = null) {
  return Object.freeze({ kind: WorkspaceKind.SAVED_FOCUS, sessionId: sessionId || null, screenId: screenId || null, itemId: itemId || null });
}

function recoveryWorkspace(sessionId, recovery) {
  return Object.freeze({ kind: WorkspaceKind.RECOVERY, sessionId: sessionId || null, recovery: cloneConsoleValue(recovery) });
}

function isDraftWorkspace(workspace) {
  return workspace?.kind === WorkspaceKind.DRAFT;
}

function cloneConsoleValue(value) {
  if (value == null) return value;
  return JSON.parse(JSON.stringify(value));
}
```

- [ ] **Step 4: Implement initial state and reducer**

Create `fixthis-mcp/src/main/console/domain/consoleAppState.js`:

```js
// @requires domain/workspaceState.js
function normalizeSessions(sessions = []) {
  const sessionsById = {};
  const sessionOrder = [];
  for (const session of sessions || []) {
    if (!session?.sessionId) continue;
    sessionsById[session.sessionId] = cloneConsoleValue(session);
    sessionOrder.push(session.sessionId);
  }
  return { sessionsById, sessionOrder };
}

function createInitialConsoleAppState(options = {}) {
  const normalized = normalizeSessions(options.sessions || []);
  return Object.freeze({
    activeSessionId: options.activeSessionId || null,
    sessionsById: Object.freeze(normalized.sessionsById),
    sessionOrder: Object.freeze(normalized.sessionOrder),
    workspace: options.workspace || emptyWorkspace(),
    tool: Object.freeze({ mode: 'select' }),
    connection: Object.freeze(options.connection || { current: null }),
    polling: Object.freeze(options.polling || { pendingMutationCount: 0 }),
    pendingBoundary: null,
    promptAction: Object.freeze({ inFlight: false }),
    effectsGeneration: Number(options.effectsGeneration || 1),
    status: null,
  });
}

function replaceConsoleState(state, patch) {
  return Object.freeze({ ...state, ...patch });
}

function nextGeneration(state) {
  return state.effectsGeneration + 1;
}
```

Create `fixthis-mcp/src/main/console/domain/consoleReducer.js`:

```js
// @requires domain/workspaceState.js, domain/consoleAppState.js, domain/consoleInvariants.js
function reduceConsoleAppState(state = createInitialConsoleAppState(), event = {}) {
  let result;
  switch (event.type) {
    case 'SESSION_ROW_CLICKED':
      result = reduceSessionRowClicked(state, event);
      break;
    case 'DRAFT_STARTED_FROM_PREVIEW':
      result = {
        state: replaceConsoleState(state, {
          activeSessionId: event.sessionId,
          workspace: draftWorkspaceFromPreview(event.sessionId, event.preview, event),
          tool: Object.freeze({ mode: 'annotate', hoveredTarget: null, drag: null }),
          pendingBoundary: null,
        }),
        effects: [],
      };
      break;
    case 'PREVIEW_CAPTURE_SUCCEEDED':
      result = reducePreviewCaptureSucceeded(state, event);
      break;
    default:
      result = { state, effects: [] };
  }
  assertConsoleInvariants(result.state);
  return result;
}

function reduceSessionRowClicked(state, event) {
  const sessionId = event.sessionId || null;
  if (!sessionId || sessionId === state.activeSessionId) return { state, effects: [] };
  if (isDraftWorkspace(state.workspace) && state.workspace.context.sessionId !== sessionId) {
    return {
      state: replaceConsoleState(state, {
        pendingBoundary: Object.freeze({
          kind: 'session-switch',
          fromSessionId: state.workspace.context.sessionId,
          targetSessionId: sessionId,
          draftSummary: Object.freeze({
            itemCount: state.workspace.items.length,
            missingCommentCount: state.workspace.items.filter((item) => !String(item.comment || '').trim()).length,
            previewId: state.workspace.context.previewId,
          }),
        }),
      }),
      effects: [],
    };
  }
  const generation = nextGeneration(state);
  return {
    state: replaceConsoleState(state, { effectsGeneration: generation }),
    effects: [Object.freeze({ kind: 'openSession', requestId: 'open-session-' + generation, sessionId, generation })],
  };
}

function reducePreviewCaptureSucceeded(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  if (event.sessionId !== state.activeSessionId) return { state, effects: [] };
  if (isDraftWorkspace(state.workspace)) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      workspace: livePreviewWorkspace(event.sessionId, event.preview),
    }),
    effects: [],
  };
}
```

- [ ] **Step 5: Implement invariants**

Create `fixthis-mcp/src/main/console/domain/consoleInvariants.js`:

```js
// @requires domain/workspaceState.js
function assertConsoleInvariants(state) {
  if (!state) throw new Error('Console state is required');
  const workspace = state.workspace;
  if (!workspace?.kind) throw new Error('Console state requires workspace.kind');
  if (workspace.kind === WorkspaceKind.DRAFT) {
    if (!workspace.context?.sessionId) throw new Error('Draft workspace requires context.sessionId');
    if (state.activeSessionId !== workspace.context.sessionId) {
      throw new Error('Draft workspace session must match activeSessionId');
    }
    if (!workspace.context.previewId) throw new Error('Draft workspace requires context.previewId');
    if (!workspace.context.screenId) throw new Error('Draft workspace requires context.screenId');
  }
  if (workspace.kind === WorkspaceKind.SAVED_FOCUS && workspace.itemId && workspace.focusedItemId) {
    throw new Error('Saved focus cannot carry draft focus');
  }
  if (state.pendingBoundary && workspace.kind !== WorkspaceKind.DRAFT) {
    throw new Error('pendingBoundary requires an active draft workspace');
  }
  return true;
}
```

- [ ] **Step 6: Run canonical tests**

Run:

```bash
node --test scripts/consoleCanonicalState-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/domain scripts/consoleCanonicalState-test.mjs scripts/console-tests.json
git commit -m "feat(console): add canonical state reducer"
```

## Task 3: Add Store and Fenced Effects

**Files:**
- Create: `fixthis-mcp/src/main/console/application/consoleStore.js`
- Create: `fixthis-mcp/src/main/console/application/consoleEffects.js`
- Create: `fixthis-mcp/src/main/console/adapters/browserPorts.js`
- Create: `scripts/consoleEffects-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write failing store/effects tests**

Create `scripts/consoleEffects-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'domain/workspaceState.js',
  'domain/consoleAppState.js',
  'domain/consoleInvariants.js',
  'domain/consoleReducer.js',
  'application/consoleStore.js',
  'application/consoleEffects.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');

const factory = new Function(`${sources}; return {
  createConsoleStore,
  runConsoleEffect,
  createInitialConsoleAppState
};`);
const m = factory();

test('store dispatch queues reducer effects with current state', () => {
  const rendered = [];
  const store = m.createConsoleStore({
    initialState: m.createInitialConsoleAppState({
      activeSessionId: 'session-a',
      sessions: [{ sessionId: 'session-a' }, { sessionId: 'session-b' }],
    }),
    render: (state) => rendered.push(state.activeSessionId),
  });
  const effects = store.dispatch({ type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' });
  assert.equal(effects.length, 1);
  assert.equal(effects[0].kind, 'openSession');
  assert.deepEqual(rendered, ['session-a']);
});

test('openSession effect dispatches success event with request context', async () => {
  const dispatched = [];
  const effect = { kind: 'openSession', requestId: 'r1', sessionId: 'session-b', generation: 2 };
  await m.runConsoleEffect(effect, {
    dispatch: (event) => dispatched.push(event),
    ports: {
      sessionApi: {
        openSession: async (sessionId) => ({ sessionId, items: [], screens: [] }),
      },
    },
  });
  assert.deepEqual(dispatched, [{
    type: 'SESSION_OPEN_SUCCEEDED',
    requestId: 'r1',
    sessionId: 'session-b',
    generation: 2,
    session: { sessionId: 'session-b', items: [], screens: [] },
  }]);
});
```

Add `scripts/consoleEffects-test.mjs` to the `canonical` group in `scripts/console-tests.json`.

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
node --test scripts/consoleEffects-test.mjs
```

Expected: FAIL because store/effect files do not exist.

- [ ] **Step 3: Implement store**

Create `fixthis-mcp/src/main/console/application/consoleStore.js`:

```js
// @requires domain/consoleReducer.js
function createConsoleStore(options = {}) {
  let current = options.initialState || createInitialConsoleAppState();
  const render = typeof options.render === 'function' ? options.render : () => {};
  const onEffects = typeof options.onEffects === 'function' ? options.onEffects : () => {};
  function getState() {
    return current;
  }
  function dispatch(event) {
    const result = reduceConsoleAppState(current, event);
    current = result.state;
    render(current);
    onEffects(result.effects, current);
    return result.effects;
  }
  return Object.freeze({ getState, dispatch });
}
```

- [ ] **Step 4: Implement effect runner**

Create `fixthis-mcp/src/main/console/application/consoleEffects.js`:

```js
// @requires (none)
async function runConsoleEffect(effect, environment) {
  const dispatch = environment.dispatch;
  const ports = environment.ports || {};
  try {
    switch (effect.kind) {
      case 'openSession': {
        const session = await ports.sessionApi.openSession(effect.sessionId);
        dispatch({
          type: 'SESSION_OPEN_SUCCEEDED',
          requestId: effect.requestId,
          sessionId: effect.sessionId,
          generation: effect.generation,
          session,
        });
        return;
      }
      case 'capturePreview': {
        const preview = await ports.previewApi.capturePreview(effect.sessionId);
        dispatch({
          type: 'PREVIEW_CAPTURE_SUCCEEDED',
          requestId: effect.requestId,
          sessionId: effect.sessionId,
          generation: effect.generation,
          preview,
        });
        return;
      }
      case 'persistRecovery':
        await ports.draftStorage.saveRecovery(effect.sessionId, effect.workspace);
        dispatch({ type: 'RECOVERY_PERSISTED', sessionId: effect.sessionId });
        return;
      default:
        dispatch({ type: 'CONSOLE_EFFECT_FAILED', effect, error: 'Unknown effect kind: ' + effect.kind });
    }
  } catch (cause) {
    dispatch({ type: 'CONSOLE_EFFECT_FAILED', effect, error: cause && cause.message ? cause.message : String(cause) });
  }
}
```

- [ ] **Step 5: Implement browser ports skeleton**

Create `fixthis-mcp/src/main/console/adapters/browserPorts.js`:

```js
// @requires (none)
function createBrowserConsolePorts(options = {}) {
  const requestJson_ = options.requestJson;
  const localStorage_ = options.localStorage || localStorage;
  return Object.freeze({
    sessionApi: Object.freeze({
      openSession: async (sessionId) => requestJson_('/api/session/open', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId }),
      }),
      listSessions: async () => requestJson_('/api/sessions'),
      currentSession: async () => requestJson_('/api/session'),
    }),
    previewApi: Object.freeze({
      capturePreview: async () => requestJson_('/api/preview'),
    }),
    draftStorage: Object.freeze({
      saveRecovery: async (sessionId, workspace) => {
        localStorage_.setItem('fixthis.recovery.' + sessionId, JSON.stringify(workspace));
      },
    }),
    clock: Object.freeze({ now: () => Date.now() }),
  });
}
```

- [ ] **Step 6: Run effects tests**

Run:

```bash
node --test scripts/consoleEffects-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/application fixthis-mcp/src/main/console/adapters/browserPorts.js scripts/consoleEffects-test.mjs scripts/console-tests.json
git commit -m "feat(console): add canonical store effects"
```

## Task 4: Add Selector View Models

**Files:**
- Create: `fixthis-mcp/src/main/console/domain/consoleSelectors.js`
- Create: `scripts/consoleSelectors-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write failing selector tests**

Create `scripts/consoleSelectors-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'domain/workspaceState.js',
  'domain/consoleAppState.js',
  'domain/consoleInvariants.js',
  'domain/consoleReducer.js',
  'domain/consoleSelectors.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');

const factory = new Function(`${sources}; return {
  createInitialConsoleAppState,
  reduceConsoleAppState,
  selectInspectorModel,
  selectCanvasModel,
  selectBoundarySheet,
  selectPromptReadiness,
  selectHistoryModel
};`);
const m = factory();

function preview() {
  return {
    previewId: 'preview-a',
    screen: { screenId: 'screen-a', fingerprint: 'fp-a', roots: [] },
    screenshotUrl: '/api/preview/preview-a/screenshot/full?sessionId=session-a',
    frozenAtEpochMillis: 1,
  };
}

test('draft workspace selects draft inspector and frozen canvas', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview() }).state;
  const inspector = m.selectInspectorModel(state);
  const canvas = m.selectCanvasModel(state);
  assert.equal(inspector.title, 'Draft Annotations');
  assert.equal(inspector.primaryAction.type, 'DRAFT_ADD_ANNOTATION_CLICKED');
  assert.equal(canvas.mode, 'frozenDraft');
  assert.match(canvas.lockLabel, /Session/);
});

test('pending boundary selects session switch sheet', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [{ sessionId: 'session-a' }, { sessionId: 'session-b' }],
  });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview() }).state;
  state = m.reduceConsoleAppState(state, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' }).state;
  const boundary = m.selectBoundarySheet(state);
  assert.equal(boundary.kind, 'session-switch');
  assert.deepEqual(boundary.actions.map((action) => action.type), [
    'BOUNDARY_SAVE_DRAFT_CLICKED',
    'BOUNDARY_KEEP_RECOVERY_CLICKED',
    'BOUNDARY_DISCARD_CLICKED',
    'BOUNDARY_CANCEL_CLICKED',
  ]);
});
```

Add `scripts/consoleSelectors-test.mjs` to the `canonical` group.

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
node --test scripts/consoleSelectors-test.mjs
```

Expected: FAIL because selectors do not exist.

- [ ] **Step 3: Implement selectors**

Create `fixthis-mcp/src/main/console/domain/consoleSelectors.js`:

```js
// @requires domain/workspaceState.js
function selectHistoryModel(state) {
  return Object.freeze({
    activeSessionId: state.activeSessionId,
    sessionIds: state.sessionOrder,
    sessionsById: state.sessionsById,
    workspaceKind: state.workspace.kind,
    draftSessionId: state.workspace.kind === WorkspaceKind.DRAFT ? state.workspace.context.sessionId : null,
    pendingBoundary: state.pendingBoundary,
  });
}

function selectInspectorModel(state) {
  const workspace = state.workspace;
  if (workspace.kind === WorkspaceKind.DRAFT) {
    return Object.freeze({
      title: 'Draft Annotations',
      count: workspace.items.length,
      rows: workspace.items,
      primaryAction: Object.freeze({ label: 'Add annotation', type: 'DRAFT_ADD_ANNOTATION_CLICKED' }),
      secondaryAction: Object.freeze({ label: 'Exit Annotate', type: 'DRAFT_EXIT_CLICKED' }),
    });
  }
  if (workspace.kind === WorkspaceKind.RECOVERY) {
    return Object.freeze({
      title: 'Recovered Draft',
      count: 0,
      rows: [],
      primaryAction: Object.freeze({ label: 'Recover', type: 'RECOVERY_RECOVER_CLICKED' }),
      secondaryAction: Object.freeze({ label: 'Discard', type: 'RECOVERY_DISCARD_CLICKED' }),
    });
  }
  return Object.freeze({
    title: 'Annotations',
    count: 0,
    rows: [],
    primaryAction: Object.freeze({ label: 'Start annotating', type: 'ANNOTATE_CLICKED' }),
    secondaryAction: null,
  });
}

function selectCanvasModel(state) {
  const workspace = state.workspace;
  if (workspace.kind === WorkspaceKind.DRAFT) {
    return Object.freeze({
      mode: 'frozenDraft',
      sessionId: workspace.context.sessionId,
      screen: workspace.screen,
      screenshotUrl: workspace.screenshotUrl,
      pins: workspace.items,
      lockLabel: 'Locked: Session ' + workspace.context.sessionId + ' · Preview ' + workspace.context.previewId + ' · Live preview paused',
    });
  }
  if (workspace.kind === WorkspaceKind.LIVE_PREVIEW) {
    return Object.freeze({ mode: 'livePreview', sessionId: workspace.sessionId, preview: workspace.preview });
  }
  if (workspace.kind === WorkspaceKind.SAVED_FOCUS) {
    return Object.freeze({ mode: 'savedFocus', sessionId: workspace.sessionId, screenId: workspace.screenId, itemId: workspace.itemId });
  }
  return Object.freeze({ mode: 'empty' });
}

function selectBoundarySheet(state) {
  if (!state.pendingBoundary) return null;
  return Object.freeze({
    kind: state.pendingBoundary.kind,
    title: 'Switch to ' + state.pendingBoundary.targetSessionId + '?',
    fromSessionId: state.pendingBoundary.fromSessionId,
    targetSessionId: state.pendingBoundary.targetSessionId,
    draftSummary: state.pendingBoundary.draftSummary,
    actions: Object.freeze([
      Object.freeze({ label: 'Save draft', type: 'BOUNDARY_SAVE_DRAFT_CLICKED' }),
      Object.freeze({ label: 'Keep in recovery', type: 'BOUNDARY_KEEP_RECOVERY_CLICKED' }),
      Object.freeze({ label: 'Discard', type: 'BOUNDARY_DISCARD_CLICKED' }),
      Object.freeze({ label: 'Cancel', type: 'BOUNDARY_CANCEL_CLICKED' }),
    ]),
  });
}

function selectPromptReadiness(state) {
  const workspace = state.workspace;
  if (workspace.kind !== WorkspaceKind.DRAFT || workspace.items.length === 0) {
    return Object.freeze({ state: 'empty', label: 'No annotations ready', disabled: true });
  }
  const missing = workspace.items.filter((item) => !String(item.comment || '').trim()).length;
  if (missing) return Object.freeze({ state: 'blocked', label: workspace.items.length + ' drafts · ' + missing + ' missing comment', disabled: true });
  return Object.freeze({ state: 'ready', label: workspace.items.length + ' drafts ready', disabled: false });
}
```

- [ ] **Step 4: Run selector tests**

Run:

```bash
node --test scripts/consoleSelectors-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/domain/consoleSelectors.js scripts/consoleSelectors-test.mjs scripts/console-tests.json
git commit -m "feat(console): add canonical selectors"
```

## Task 5: Add Workflow Invariant Sequence Tests

**Files:**
- Create: `scripts/consoleCanonicalWorkflow-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write failing workflow invariant tests**

Create `scripts/consoleCanonicalWorkflow-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'domain/workspaceState.js',
  'domain/consoleAppState.js',
  'domain/consoleInvariants.js',
  'domain/consoleReducer.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');

const factory = new Function(`${sources}; return {
  createInitialConsoleAppState,
  reduceConsoleAppState,
  assertConsoleInvariants,
  WorkspaceKind
};`);
const m = factory();

function preview(n) {
  return {
    previewId: 'preview-' + n,
    screen: { screenId: 'screen-' + n, fingerprint: 'fp-' + n, roots: [] },
    screenshotUrl: '/api/preview/preview-' + n + '/screenshot/full?sessionId=session-a',
    frozenAtEpochMillis: n,
  };
}

test('active session reclick preserves draft workflow', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) }).state;
  const before = JSON.stringify(state.workspace);
  state = m.reduceConsoleAppState(state, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-a' }).state;
  assert.equal(JSON.stringify(state.workspace), before);
  assert.equal(state.workspace.kind, m.WorkspaceKind.DRAFT);
  m.assertConsoleInvariants(state);
});

test('random-style boundary sequence never switches while boundary is unresolved', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [{ sessionId: 'session-a' }, { sessionId: 'session-b' }],
  });
  const events = [
    { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) },
    { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' },
    { type: 'PREVIEW_CAPTURE_SUCCEEDED', requestId: 'late', sessionId: 'session-b', generation: 999, preview: preview(2) },
    { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' },
    { type: 'SESSION_ROW_CLICKED', sessionId: 'session-a' },
  ];
  for (const event of events) {
    state = m.reduceConsoleAppState(state, event).state;
    m.assertConsoleInvariants(state);
  }
  assert.equal(state.activeSessionId, 'session-a');
  assert.equal(state.pendingBoundary.targetSessionId, 'session-b');
  assert.equal(state.workspace.context.previewId, 'preview-1');
});
```

Add the test to the `canonical` group.

- [ ] **Step 2: Run workflow tests**

Run:

```bash
node --test scripts/consoleCanonicalWorkflow-test.mjs
```

Expected: PASS once Task 2 reducer behavior exists.

- [ ] **Step 3: Commit**

```bash
git add scripts/consoleCanonicalWorkflow-test.mjs scripts/console-tests.json
git commit -m "test(console): cover canonical workflow invariants"
```

## Task 6: Big-Bang Integrate Store Into Browser Console

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Create or modify: `fixthis-mcp/src/main/console/adapters/browserRenderer.js`
- Modify: existing contract tests under `scripts/*-test.mjs` that grep removed symbols

- [ ] **Step 1: Write failing source-contract tests for no direct legacy writes**

Add these assertions to `scripts/consoleCanonicalWorkflow-test.mjs`:

```js
test('browser console modules do not directly mutate legacy session or preview state', () => {
  const files = ['history.js', 'annotations.js', 'preview.js', 'prompt.js', 'rendering.js', 'main.js'];
  for (const file of files) {
    const content = readFileSync(resolve(root, 'fixthis-mcp/src/main/console', file), 'utf8');
    assert.doesNotMatch(content, /state\.session\s*=/, `${file} must not assign state.session`);
    assert.doesNotMatch(content, /state\.preview\s*=/, `${file} must not assign state.preview`);
    assert.doesNotMatch(content, /resetAnnotationComposerState\(/, `${file} must not orchestrate legacy reset`);
    assert.doesNotMatch(content, /invalidatePreviewContext\(/, `${file} must not orchestrate legacy preview invalidation`);
  }
});
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
node --test scripts/consoleCanonicalWorkflow-test.mjs
```

Expected: FAIL on existing direct writes.

- [ ] **Step 3: Add browser renderer coordinator**

Create `fixthis-mcp/src/main/console/adapters/browserRenderer.js`:

```js
// @requires domain/consoleSelectors.js
function createBrowserRenderer(bindings) {
  function render(state) {
    const model = {
      history: selectHistoryModel(state),
      canvas: selectCanvasModel(state),
      inspector: selectInspectorModel(state),
      prompt: selectPromptReadiness(state),
      boundary: selectBoundarySheet(state),
    };
    bindings.renderHistory?.(model.history, state);
    bindings.renderCanvas?.(model.canvas, state);
    bindings.renderInspector?.(model.inspector, state);
    bindings.renderPrompt?.(model.prompt, state);
    bindings.renderBoundary?.(model.boundary, state);
  }
  return Object.freeze({ render });
}
```

- [ ] **Step 4: Replace browser boot with canonical store**

In `main.js`, create the store once:

```js
const consolePorts = createBrowserConsolePorts({ requestJson, localStorage });
const browserRenderer = createBrowserRenderer({
  renderHistory: renderHistoryFromModel,
  renderCanvas: renderCanvasFromModel,
  renderInspector: renderInspectorFromModel,
  renderPrompt: renderPromptFromModel,
  renderBoundary: renderBoundaryFromModel,
});
const consoleStore = createConsoleStore({
  initialState: createInitialConsoleAppState(),
  render: (nextState) => browserRenderer.render(nextState),
  onEffects: (effects) => {
    effects.forEach((effect) => runConsoleEffect(effect, { ports: consolePorts, dispatch: consoleStore.dispatch }));
  },
});
```

Bind DOM events to `consoleStore.dispatch(...)` instead of legacy orchestration calls:

```js
annotateToolButton.addEventListener('click', () => consoleStore.dispatch({ type: 'ANNOTATE_CLICKED' }));
sendAgentButton.addEventListener('click', () => consoleStore.dispatch({ type: 'SAVE_TO_MCP_CLICKED' }));
copyPromptButton.addEventListener('click', () => consoleStore.dispatch({ type: 'COPY_PROMPT_CLICKED' }));
```

- [ ] **Step 5: Reduce existing modules to render/bind helpers**

For each module, move state decisions to selectors and leave DOM application only:

```js
function renderInspectorFromModel(model) {
  inspectorTitle.textContent = model.title;
  inspectorCount.textContent = String(model.count || 0);
  addItemButton.hidden = model.primaryAction?.type !== 'DRAFT_ADD_ANNOTATION_CLICKED';
  cancelAddFlowButton.hidden = model.secondaryAction?.type !== 'DRAFT_EXIT_CLICKED';
}
```

Use this explicit dispatch mapping for action buttons:

```js
function dispatchInspectorAction(action) {
  if (!action) return;
  consoleStore.dispatch({ type: action.type });
}
```

- [ ] **Step 6: Remove legacy mutable mirrors**

Delete or stop using:

```js
let addItemsFlow = null;
let pendingFeedbackItems = [];
let focusedPendingItemIndex = null;
let currentSelection = null;
function resetAnnotationComposerState(...) { ... }
function invalidatePreviewContext() { ... }
```

Equivalent data must live in `ConsoleAppState.workspace` and `ConsoleAppState.tool`.

- [ ] **Step 7: Update grep-based tests for renamed contract symbols**

Update tests that assert removed legacy function names. Replace legacy symbol expectations with canonical equivalents:

```js
assert.match(bundle, /createConsoleStore/);
assert.match(bundle, /reduceConsoleAppState/);
assert.match(bundle, /selectInspectorModel/);
assert.match(bundle, /selectBoundarySheet/);
```

Do not keep contract guards for removed functions such as `resetAnnotationComposerState` or `startAddItemsFlow`.

- [ ] **Step 8: Run canonical tests**

Run:

```bash
node scripts/run-console-tests.mjs canonical
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add fixthis-mcp/src/main/console scripts
git commit -m "refactor(console): route browser state through canonical store"
```

## Task 7: Add Draft Lock and Boundary Sheet Responsive UI

**Files:**
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `fixthis-mcp/src/main/resources/console/index.html` if the template has fixed placeholders
- Modify: console CSS source file if CSS is embedded in Kotlin/assets
- Modify: `scripts/console-responsive-stress.mjs`

- [ ] **Step 1: Add failing responsive checks**

In `scripts/console-responsive-stress.mjs`, add checks for these viewport widths:

```js
const requiredViewports = [
  { width: 1440, height: 900, name: 'desktop' },
  { width: 900, height: 900, name: 'tablet' },
  { width: 390, height: 844, name: 'mobile' },
];
```

For each viewport after entering a draft state in the harness, assert:

```js
await expectVisibleText(page, 'Locked:');
await expectVisibleText(page, 'Live preview paused');
await expectNoHorizontalOverflow(page);
```

Add these helper functions:

```js
async function expectVisibleText(page, text) {
  const visible = await page.locator(`text=${text}`).first().isVisible();
  if (!visible) throw new Error(`Expected visible text: ${text}`);
}

async function expectNoHorizontalOverflow(page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
  if (overflow) throw new Error('Unexpected horizontal overflow');
}
```

- [ ] **Step 2: Run responsive stress and verify it fails**

Run:

```bash
node scripts/console-responsive-stress.mjs
```

Expected: FAIL because draft lock UI is not implemented.

- [ ] **Step 3: Implement lock bar and boundary sheet rendering**

Add renderer functions:

```js
function renderDraftLockBar(canvasModel) {
  const root = document.getElementById('draftLockBar');
  if (!root) return;
  root.hidden = canvasModel.mode !== 'frozenDraft';
  root.textContent = canvasModel.mode === 'frozenDraft' ? canvasModel.lockLabel : '';
}

function renderBoundaryFromModel(boundary) {
  const root = document.getElementById('sessionBoundarySheet');
  if (!root) return;
  root.hidden = !boundary;
  if (!boundary) return;
  root.querySelector('[data-boundary-title]').textContent = boundary.title;
  root.querySelector('[data-boundary-summary]').textContent =
    `${boundary.draftSummary.itemCount} draft annotations · ${boundary.draftSummary.missingCommentCount} missing comments`;
  root.querySelectorAll('[data-boundary-action]').forEach((button, index) => {
    const action = boundary.actions[index];
    button.hidden = !action;
    if (action) {
      button.textContent = action.label;
      button.onclick = () => consoleStore.dispatch({ type: action.type });
    }
  });
}
```

Add responsive CSS:

```css
.draft-lock-bar {
  min-height: 34px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  border: 1px solid #6f7b3c;
  border-radius: 8px;
  background: #1b2013;
  color: #e3edab;
  font-size: 12px;
  font-weight: 800;
  line-height: 1.3;
}

.session-boundary-sheet[hidden] {
  display: none;
}

.session-boundary-sheet {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  place-items: center;
  background: rgba(3, 5, 8, 0.62);
}

.session-boundary-dialog {
  width: min(430px, calc(100vw - 32px));
  max-height: calc(100vh - 48px);
  overflow: auto;
  border: 1px solid #3f4856;
  border-radius: 12px;
  background: #151922;
  padding: 18px;
}

.session-boundary-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

@media (max-width: 640px) {
  .draft-lock-bar {
    width: 100%;
    min-height: 42px;
    white-space: normal;
  }
  .session-boundary-sheet {
    align-items: end;
    place-items: end center;
  }
  .session-boundary-dialog {
    width: 100vw;
    max-height: 88vh;
    border-radius: 14px 14px 0 0;
  }
  .session-boundary-actions {
    grid-template-columns: 1fr;
  }
}
```

- [ ] **Step 4: Run responsive checks**

Run:

```bash
node scripts/console-responsive-stress.mjs
```

Expected: PASS with no horizontal overflow.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console fixthis-mcp/src/main/resources scripts/console-responsive-stress.mjs
git commit -m "feat(console): show draft lock and boundary sheet"
```

## Task 8: Update Docs, Bundle, and Full Console Tests

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/main/resources/console/app.js.map`
- Modify: `fixthis-mcp/src/main/resources/console/console-build-meta.json` only if reproducible build requires it

- [ ] **Step 1: Update feedback console contract**

In `docs/reference/feedback-console-contract.md`, replace the "Console state machines" section with this text:

```md
## Console state model

The browser console uses one canonical `ConsoleAppState`. DOM events and network responses dispatch commands/events into a reducer. The reducer returns the next state plus effect descriptions; browser adapters execute those effects and dispatch fenced results back into the store.

Renderers consume selector view models only. They do not mutate session, preview, draft, tool, polling, or prompt state. Draft work is represented by `workspace.kind = "draft"` and is locked to an immutable session/preview/screen context until saved, moved to recovery, or discarded.

Cross-session navigation with unsaved draft work creates `pendingBoundary`; the boundary sheet is the only UI path that can save, recover, discard, or cancel that transition. The same boundary state renders as a centered modal on desktop and a bottom sheet/full-width modal on mobile.
```

- [ ] **Step 2: Run all console tests**

Run:

```bash
node scripts/run-console-tests.mjs availability pending beforeunload undo activity preview draft session canonical harness
node --test scripts/build-console-assets-test.mjs
```

Expected: all tests pass.

- [ ] **Step 3: Regenerate console bundle reproducibly**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: `--check` exits 0.

- [ ] **Step 4: Run Gradle console asset contract tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*Console*' --tests '*BuildInfoTest'
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add docs/reference/feedback-console-contract.md fixthis-mcp/src/main/resources/console scripts fixthis-mcp/src/main/console
git commit -m "test(console): verify canonical state migration"
```

## Task 9: Final Verification

**Files:**
- No planned source edits.

- [ ] **Step 1: Check for forbidden legacy mutation paths**

Run:

```bash
rg -n "state\\.session\\s*=|state\\.preview\\s*=|addItemsFlow|pendingFeedbackItems|resetAnnotationComposerState|invalidatePreviewContext" fixthis-mcp/src/main/console scripts
```

Expected: no matches in production console code. Test files may mention removed names only when asserting absence.

- [ ] **Step 2: Run full fast console suite**

Run:

```bash
npm run console:test:all
```

Expected: PASS.

- [ ] **Step 3: Run build check**

Run:

```bash
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 4: Commit any final cleanup**

If Step 1-3 required small cleanup edits:

```bash
git add fixthis-mcp/src/main/console scripts docs/reference/feedback-console-contract.md fixthis-mcp/src/main/resources/console
git commit -m "chore(console): finish canonical state cleanup"
```

If no cleanup edits were needed, do not create an empty commit.

## Self-Review Checklist

- Spec coverage:
  - Canonical `ConsoleAppState`: Tasks 2, 6.
  - Clean Architecture / SOLID boundaries: Tasks 2, 3, 4, 6.
  - Fenced async effects: Task 3 and Task 6.
  - Draft lock UX: Task 7.
  - Session boundary sheet: Tasks 4, 6, 7.
  - Desktop/mobile responsive behavior: Task 7.
  - Tests and bundle regeneration: Tasks 1, 5, 8, 9.
- Placeholder scan: no task uses undefined "later" work; each task has exact files, commands, and expected outcomes.
- Type consistency: `WorkspaceKind.DRAFT`, `pendingBoundary`, `effectsGeneration`, and command names are consistent across reducer, effects, selectors, and tests.
