# Console JS Reliability Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce recurring browser feedback console JavaScript errors by adding shared test loading, runtime boundary validation, centralized event factories, focused pure-function extraction, and optional `checkJs`.

**Architecture:** Keep the current plain browser JavaScript and `// @requires` bundle model. Add reliability guardrails around it: tests load modules through the production dependency graph, untrusted inputs are normalized at boundaries, reducers receive events from named factories, and large UI files expose smaller pure helpers where that directly improves coverage.

**Tech Stack:** Plain browser JavaScript under `fixthis-mcp/src/main/console`, Node.js 20 `node:test`, `scripts/build-console-assets.mjs`, esbuild, npm scripts, optional TypeScript `checkJs` as a later local verification lane.

---

## File Structure

### New Test Infrastructure

- Create `scripts/console-test-loader.mjs`: shared helper for Node tests that loads console source modules in `// @requires` topological order.
- Create `scripts/console-test-loader-test.mjs`: unit tests for dependency expansion, symbol lookup, and error behavior.

### New Console Domain Guardrails

- Create `fixthis-mcp/src/main/console/domain/consoleBoundary.js`: dependency-free boundary validation and normalization helpers.
- Create `fixthis-mcp/src/main/console/domain/consoleEvents.js`: canonical event factory object for reducer/FSM commands.
- Modify `scripts/build-console-assets-test.mjs`: ensure recursive `// @requires` header validation covers nested modules.

### Boundary and Event Tests

- Create `scripts/consoleBoundary-test.mjs`: malformed payload and normalization tests.
- Create `scripts/consoleEventsFactory-test.mjs`: event factory output and reducer event-name coverage.
- Modify `scripts/console-tests.json`: include the new tests in the `canonical` group after they pass locally.

### First Migration Targets

- Modify `scripts/draftWorkspace-test.mjs`: load `draftWorkspace.js` through the shared loader.
- Modify `scripts/undoRedo-test.mjs`: load `undoRedo.js` through the shared loader.
- Modify `fixthis-mcp/src/main/console/domain/consoleReducer.js`: accept factory-created events without behavior changes.
- Modify one low-risk browser boundary call site after validator tests exist. Recommended first path: `fixthis-mcp/src/main/console/draftStorageAdapter.js`.
- Create `fixthis-mcp/src/main/console/domain/targetReliabilityViewModel.js`: pure target reliability badge model extracted from rendering.
- Modify `fixthis-mcp/src/main/console/rendering.js`: call the extracted target reliability model.
- Modify `scripts/targetReliabilityPresentation-test.mjs`: test the extracted model through the shared loader.

### Optional Typecheck

- Create `tsconfig.console-check.json`: JavaScript typecheck configuration for selected stable files.
- Modify `package.json`: add `console:typecheck`.
- Modify `package-lock.json`: add `typescript` and `@types/node` only when implementing the optional typecheck task.

## Task 1: Shared Console Test Loader

**Files:**
- Create: `scripts/console-test-loader.mjs`
- Create: `scripts/console-test-loader-test.mjs`
- Modify: `scripts/build-console-assets-test.mjs`

- [ ] **Step 1: Write failing loader tests**

Create `scripts/console-test-loader-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  consoleModuleClosure,
  consoleModuleSourceOrder,
  loadConsoleSymbols,
} from './console-test-loader.mjs';

test('consoleModuleSourceOrder expands dependencies before requested modules', () => {
  const order = consoleModuleSourceOrder(['domain/consoleReducer.js']);
  const reducerIndex = order.indexOf('domain/consoleReducer.js');
  assert.ok(reducerIndex >= 0, 'requested reducer module is present');
  assert.ok(
    order.indexOf('domain/workspaceState.js') >= 0 &&
      order.indexOf('domain/workspaceState.js') < reducerIndex,
    'workspaceState dependency precedes reducer',
  );
  assert.ok(
    order.indexOf('domain/consoleAppState.js') >= 0 &&
      order.indexOf('domain/consoleAppState.js') < reducerIndex,
    'consoleAppState dependency precedes reducer',
  );
});

test('loadConsoleSymbols returns requested symbols from evaluated modules', () => {
  const m = loadConsoleSymbols({
    modules: ['undoRedo.js'],
    symbols: ['createHistory', 'recordAdd', 'undo', 'redo'],
  });
  const history = m.recordAdd(m.createHistory(), { itemId: 'item-1' });
  assert.equal(history.undoStack.length, 1);
  assert.equal(typeof m.undo, 'function');
  assert.equal(typeof m.redo, 'function');
});

test('loadConsoleSymbols fails with a clear error for unknown symbols', () => {
  assert.throws(
    () => loadConsoleSymbols({ modules: ['undoRedo.js'], symbols: ['doesNotExist'] }),
    /Console symbol not found: doesNotExist/,
  );
});

test('consoleModuleClosure can be parsed by Function', () => {
  const source = consoleModuleClosure(['draftWorkspace.js']);
  assert.doesNotThrow(() => new Function(`${source}; return true;`));
});
```

- [ ] **Step 2: Run the failing loader tests**

Run:

```bash
node --test scripts/console-test-loader-test.mjs
```

Expected: FAIL with `Cannot find module './console-test-loader.mjs'`.

- [ ] **Step 3: Implement the shared loader**

Create `scripts/console-test-loader.mjs`:

```js
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseRequires, topoSort } from './build-console-assets.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');

function readConsoleModule(name) {
  return readFileSync(resolve(sourceDir, name), 'utf8');
}

function dependencyGraphFor(requestedModules) {
  const graph = new Map();
  const visiting = [...requestedModules];
  while (visiting.length > 0) {
    const name = visiting.pop();
    if (graph.has(name)) continue;
    let content;
    try {
      content = readConsoleModule(name);
    } catch (error) {
      throw new Error(`Console module not found: ${name}`);
    }
    const deps = parseRequires(content);
    graph.set(name, { content, deps });
    for (const dep of deps) visiting.push(dep);
  }
  return graph;
}

export function consoleModuleSourceOrder(requestedModules) {
  return topoSort(dependencyGraphFor(requestedModules));
}

export function consoleModuleClosure(requestedModules) {
  const graph = dependencyGraphFor(requestedModules);
  return topoSort(graph)
    .map((name) => `//#region ${name}\n${graph.get(name).content.trimEnd()}\n//#endregion ${name}\n`)
    .join('\n');
}

export function loadConsoleSymbols({ modules, symbols, args = [], values = [] }) {
  const source = consoleModuleClosure(modules);
  const returnObject = symbols.map((symbol) => `${JSON.stringify(symbol)}: ${symbol}`).join(',\n');
  const factory = new Function(
    ...args,
    `${source}
const __result = {
${returnObject}
};
for (const [name, value] of Object.entries(__result)) {
  if (typeof value === 'undefined') throw new Error('Console symbol not found: ' + name);
}
return __result;`,
  );
  return factory(...values);
}
```

- [ ] **Step 4: Run loader tests and verify pass**

Run:

```bash
node --test scripts/console-test-loader-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Tighten nested module header test**

In `scripts/build-console-assets-test.mjs`, replace the top-level `readdirSync`
header test with recursive discovery from `consoleSourceFiles`:

```js
test('every console module except entry point carries a // @requires header', async () => {
  const { consoleSourceFiles } = await import('../scripts/build-console-assets.mjs');
  const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');
  const files = consoleSourceFiles(sourceDir).filter((name) => name !== 'main.js');
  assert.ok(files.length >= 40, `expected >=40 non-entry modules, found ${files.length}`);
  const missing = [];
  for (const name of files) {
    const text = readFileSync(resolve(sourceDir, name), 'utf8');
    if (!/^\\s*\\/\\/\\s*@requires\\s+/m.test(text)) missing.push(name);
  }
  assert.deepEqual(missing, [], `Missing // @requires header in: ${missing.join(', ')}`);
});
```

- [ ] **Step 6: Run build script tests**

Run:

```bash
node --test scripts/build-console-assets-test.mjs scripts/console-test-loader-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scripts/console-test-loader.mjs scripts/console-test-loader-test.mjs scripts/build-console-assets-test.mjs
git commit -m "test(console): add shared source loader"
```

## Task 2: Convert Two Low-Risk Tests to the Loader

**Files:**
- Modify: `scripts/draftWorkspace-test.mjs`
- Modify: `scripts/undoRedo-test.mjs`

- [ ] **Step 1: Update `draftWorkspace-test.mjs` imports and module setup**

Replace the manual `readFileSync` / `new Function` setup with:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['draftWorkspace.js'],
  symbols: [
    'DraftLifecycle',
    'createEmptyDraftWorkspace',
    'createDraftContext',
    'createFrozenDraftWorkspace',
    'reduceDraftWorkspace',
    'draftWorkspaceItems',
    'requireDraftContext',
  ],
});
```

Leave the existing test bodies unchanged.

- [ ] **Step 2: Update `undoRedo-test.mjs` imports and module setup**

Replace the manual setup with:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['undoRedo.js'],
  symbols: [
    'createHistory',
    'recordAdd',
    'recordDelete',
    'recordUpdate',
    'undo',
    'redo',
    'UNDO_MAX_DEPTH',
  ],
});
```

If the existing file destructures returned values, replace direct names with
`m.createHistory`, `m.recordAdd`, and the other `m.*` symbols.

- [ ] **Step 3: Run converted tests**

Run:

```bash
node --test scripts/draftWorkspace-test.mjs scripts/undoRedo-test.mjs
```

Expected: PASS.

- [ ] **Step 4: Run related grouped tests**

Run:

```bash
npm run console:draft:test
npm run console:undo:test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/draftWorkspace-test.mjs scripts/undoRedo-test.mjs
git commit -m "test(console): use shared loader in draft and undo tests"
```

## Task 3: Boundary Validation Module

**Files:**
- Create: `fixthis-mcp/src/main/console/domain/consoleBoundary.js`
- Create: `scripts/consoleBoundary-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write boundary validation tests**

Create `scripts/consoleBoundary-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['domain/consoleBoundary.js'],
  symbols: [
    'okBoundary',
    'errorBoundary',
    'normalizeSessionPayload',
    'normalizePreviewPayload',
    'normalizeDraftItemPayload',
    'normalizeStoredJson',
  ],
});

test('normalizeSessionPayload requires a session id', () => {
  assert.deepEqual(
    m.normalizeSessionPayload({ status: 'open' }),
    m.errorBoundary('missing_session_id', 'Session payload is missing sessionId.'),
  );
});

test('normalizeSessionPayload preserves supported fields', () => {
  const result = m.normalizeSessionPayload({
    sessionId: 'session-a',
    status: 'open',
    items: [{ itemId: 'item-a' }],
    screens: [{ screenId: 'screen-a' }],
  });
  assert.equal(result.ok, true);
  assert.equal(result.value.sessionId, 'session-a');
  assert.deepEqual(result.value.items, [{ itemId: 'item-a' }]);
  assert.deepEqual(result.value.screens, [{ screenId: 'screen-a' }]);
});

test('normalizePreviewPayload requires preview and screen ids', () => {
  assert.equal(m.normalizePreviewPayload({ previewId: 'preview-a' }).ok, false);
  assert.equal(
    m.normalizePreviewPayload({ previewId: 'preview-a', screen: { screenId: 'screen-a' } }).ok,
    true,
  );
});

test('normalizeDraftItemPayload creates stable ids and comment strings', () => {
  const result = m.normalizeDraftItemPayload({
    itemId: 'item-a',
    annotationId: 'pin-a',
    comment: 123,
    selection: { type: 'node' },
    targetEvidence: { label: 'Save' },
  });
  assert.equal(result.ok, true);
  assert.equal(result.value.itemId, 'item-a');
  assert.equal(result.value.annotationId, 'pin-a');
  assert.equal(result.value.comment, '123');
});

test('normalizeStoredJson handles malformed storage text', () => {
  assert.deepEqual(
    m.normalizeStoredJson('{bad json'),
    m.errorBoundary('invalid_storage_payload', 'Storage payload is not valid JSON.'),
  );
});
```

- [ ] **Step 2: Run the failing boundary tests**

Run:

```bash
node --test scripts/consoleBoundary-test.mjs
```

Expected: FAIL because `domain/consoleBoundary.js` does not exist.

- [ ] **Step 3: Implement `consoleBoundary.js`**

Create `fixthis-mcp/src/main/console/domain/consoleBoundary.js`:

```js
// @requires (none)
function okBoundary(value) {
  return Object.freeze({ ok: true, value });
}

function errorBoundary(code, message) {
  return Object.freeze({ ok: false, error: Object.freeze({ code, message }) });
}

function isObjectLike(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function cloneBoundaryValue(value) {
  if (value === null || typeof value !== 'object') return value;
  return JSON.parse(JSON.stringify(value));
}

function normalizeSessionPayload(payload) {
  if (!isObjectLike(payload)) {
    return errorBoundary('invalid_session_payload', 'Session payload must be an object.');
  }
  const sessionId = String(payload.sessionId || '').trim();
  if (!sessionId) {
    return errorBoundary('missing_session_id', 'Session payload is missing sessionId.');
  }
  return okBoundary(Object.freeze({
    ...cloneBoundaryValue(payload),
    sessionId,
    items: Array.isArray(payload.items) ? cloneBoundaryValue(payload.items) : [],
    screens: Array.isArray(payload.screens) ? cloneBoundaryValue(payload.screens) : [],
  }));
}

function normalizePreviewPayload(payload) {
  if (!isObjectLike(payload)) {
    return errorBoundary('invalid_preview_payload', 'Preview payload must be an object.');
  }
  const previewId = String(payload.previewId || '').trim();
  const screen = isObjectLike(payload.screen) ? payload.screen : null;
  const screenId = String(screen?.screenId || payload.screenId || '').trim();
  if (!previewId) {
    return errorBoundary('missing_preview_id', 'Preview payload is missing previewId.');
  }
  if (!screenId) {
    return errorBoundary('missing_screen_id', 'Preview payload is missing screenId.');
  }
  return okBoundary(Object.freeze({
    ...cloneBoundaryValue(payload),
    previewId,
    screen: Object.freeze({ ...cloneBoundaryValue(screen || {}), screenId }),
  }));
}

function normalizeDraftItemPayload(payload) {
  if (!isObjectLike(payload)) {
    return errorBoundary('invalid_draft_item', 'Draft item payload must be an object.');
  }
  const itemId = String(payload.itemId || payload.annotationId || '').trim();
  if (!itemId) {
    return errorBoundary('missing_item_id', 'Draft item payload is missing itemId.');
  }
  return okBoundary(Object.freeze({
    ...cloneBoundaryValue(payload),
    itemId,
    annotationId: String(payload.annotationId || itemId),
    comment: String(payload.comment || ''),
    selection: cloneBoundaryValue(payload.selection || null),
    targetEvidence: cloneBoundaryValue(payload.targetEvidence || null),
  }));
}

function normalizeStoredJson(text) {
  if (!text) return okBoundary(null);
  try {
    return okBoundary(JSON.parse(text));
  } catch (_) {
    return errorBoundary('invalid_storage_payload', 'Storage payload is not valid JSON.');
  }
}
```

- [ ] **Step 4: Run boundary tests**

Run:

```bash
node --test scripts/consoleBoundary-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Add the test to the canonical group**

In `scripts/console-tests.json`, add `scripts/consoleBoundary-test.mjs` to the
`canonical` group:

```json
"canonical": [
  "scripts/consoleCanonicalState-test.mjs",
  "scripts/consoleEffects-test.mjs",
  "scripts/consoleSelectors-test.mjs",
  "scripts/consoleCanonicalWorkflow-test.mjs",
  "scripts/consoleCanonicalRuntimeContract-test.mjs",
  "scripts/consoleBoundary-test.mjs"
]
```

- [ ] **Step 6: Run canonical tests and asset check**

Run:

```bash
npm run console:fsm:test
npm run console:test:all
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: all commands PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/domain/consoleBoundary.js \
  scripts/consoleBoundary-test.mjs \
  scripts/console-tests.json \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): add boundary payload normalization"
```

## Task 4: Event Factory Module

**Files:**
- Create: `fixthis-mcp/src/main/console/domain/consoleEvents.js`
- Create: `scripts/consoleEventsFactory-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write event factory tests**

Create `scripts/consoleEventsFactory-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['domain/consoleEvents.js'],
  symbols: ['ConsoleEvents'],
});

test('sessionRowClicked creates a frozen canonical event', () => {
  const event = m.ConsoleEvents.sessionRowClicked('session-a');
  assert.deepEqual(event, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-a' });
  assert.equal(Object.isFrozen(event), true);
});

test('draftCommentChanged normalizes comment text', () => {
  assert.deepEqual(
    m.ConsoleEvents.draftCommentChanged('item-a', 123),
    { type: 'DRAFT_COMMENT_CHANGED', itemId: 'item-a', comment: '123' },
  );
});

test('previewCaptureSucceeded requires session id and preview', () => {
  assert.throws(
    () => m.ConsoleEvents.previewCaptureSucceeded('', { previewId: 'preview-a' }, 1),
    /sessionId is required/,
  );
  assert.deepEqual(
    m.ConsoleEvents.previewCaptureSucceeded('session-a', { previewId: 'preview-a' }, 2),
    {
      type: 'PREVIEW_CAPTURE_SUCCEEDED',
      sessionId: 'session-a',
      preview: { previewId: 'preview-a' },
      generation: 2,
    },
  );
});
```

- [ ] **Step 2: Run the failing event factory tests**

Run:

```bash
node --test scripts/consoleEventsFactory-test.mjs
```

Expected: FAIL because `domain/consoleEvents.js` does not exist.

- [ ] **Step 3: Implement `consoleEvents.js`**

Create `fixthis-mcp/src/main/console/domain/consoleEvents.js`:

```js
// @requires (none)
function requireEventString(name, value) {
  const normalized = String(value || '').trim();
  if (!normalized) throw new Error(`${name} is required`);
  return normalized;
}

function freezeEvent(event) {
  return Object.freeze(event);
}

const ConsoleEvents = Object.freeze({
  sessionRowClicked(sessionId) {
    return freezeEvent({
      type: 'SESSION_ROW_CLICKED',
      sessionId: requireEventString('sessionId', sessionId),
    });
  },

  annotateClicked() {
    return freezeEvent({ type: 'ANNOTATE_CLICKED' });
  },

  draftTargetSelected(payload) {
    return freezeEvent({
      type: 'DRAFT_TARGET_SELECTED',
      ...payload,
    });
  },

  draftCommentChanged(itemId, comment) {
    return freezeEvent({
      type: 'DRAFT_COMMENT_CHANGED',
      itemId: itemId || null,
      comment: String(comment || ''),
    });
  },

  previewCaptureSucceeded(sessionId, preview, generation) {
    return freezeEvent({
      type: 'PREVIEW_CAPTURE_SUCCEEDED',
      sessionId: requireEventString('sessionId', sessionId),
      preview,
      generation,
    });
  },
});
```

- [ ] **Step 4: Run event factory tests**

Run:

```bash
node --test scripts/consoleEventsFactory-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Add event factory test to canonical group**

In `scripts/console-tests.json`, append `scripts/consoleEventsFactory-test.mjs`
to the `canonical` group.

- [ ] **Step 6: Run canonical tests and bundle check**

Run:

```bash
npm run console:test:all
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/domain/consoleEvents.js \
  scripts/consoleEventsFactory-test.mjs \
  scripts/console-tests.json \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): centralize canonical event creation"
```

## Task 5: Use Boundary Validation in One Storage Path

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftStorageAdapter.js`
- Modify: `scripts/draftStorageAdapter-test.mjs`

- [ ] **Step 1: Add failing storage malformed JSON test**

In `scripts/draftStorageAdapter-test.mjs`, load `domain/consoleBoundary.js`
before `draftStorageAdapter.js` if the file still uses manual source loading.
Then add:

```js
test('loadWorkspacesForSession drops malformed indexed workspace payloads', () => {
  const localStorage = fakeLocalStorage({
    'fixthis.workspace.index.session-a': JSON.stringify(['ws-bad']),
    'fixthis.workspace.session-a.ws-bad': '{bad json',
  });
  const adapter = m.createDraftStorageAdapter(localStorage);

  assert.deepEqual(adapter.loadWorkspacesForSession('session-a'), []);
  assert.equal(localStorage.getItem('fixthis.workspace.session-a.ws-bad'), null);
});
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
node --test scripts/draftStorageAdapter-test.mjs
```

Expected: FAIL if malformed JSON currently throws or is not cleared.

- [ ] **Step 3: Add boundary dependency and use `normalizeStoredJson`**

Change the header in `fixthis-mcp/src/main/console/draftStorageAdapter.js` to
include the boundary module:

```js
// @requires domain/consoleBoundary.js
```

If it already has dependencies, append `domain/consoleBoundary.js` to the
existing comma-separated list.

Use this pattern in the restore path:

```js
const parsed = normalizeStoredJson(localStorageLike.getItem(draftWorkspaceKey(sessionId, workspaceId)));
if (!parsed.ok) {
  localStorageLike.removeItem(draftWorkspaceKey(sessionId, workspaceId));
  return null;
}
if (!parsed.value) return null;
```

Then keep the existing domain-specific workspace validation after parsing.

- [ ] **Step 4: Run storage tests**

Run:

```bash
node --test scripts/draftStorageAdapter-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Run draft group and bundle check**

Run:

```bash
npm run console:draft:test
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/draftStorageAdapter.js \
  scripts/draftStorageAdapter-test.mjs \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "fix(console): normalize stored draft payloads"
```

## Task 6: Use Event Factories in One Canonical Dispatch Path

**Files:**
- Modify: `fixthis-mcp/src/main/console/domain/consoleReducer.js`
- Modify: `fixthis-mcp/src/main/console/application/consoleEffects.js`
- Modify: one browser dispatch call site, recommended `fixthis-mcp/src/main/console/history.js`
- Modify: `scripts/consoleCanonicalState-test.mjs`

- [ ] **Step 1: Add a reducer test using factory-created events**

In `scripts/consoleCanonicalState-test.mjs`, load `domain/consoleEvents.js` with
the existing source list or the shared loader. Add:

```js
test('factory-created session row event opens session through reducer effect', () => {
  const state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [
      { sessionId: 'session-a', status: 'open' },
      { sessionId: 'session-b', status: 'open' },
    ],
  });

  const result = m.reduceConsoleAppState(
    state,
    m.ConsoleEvents.sessionRowClicked('session-b'),
  );

  assert.equal(result.effects.length, 1);
  assert.equal(result.effects[0].kind, 'openSession');
  assert.equal(result.effects[0].sessionId, 'session-b');
});
```

- [ ] **Step 2: Run the focused canonical state test**

Run:

```bash
node --test scripts/consoleCanonicalState-test.mjs
```

Expected: PASS after `ConsoleEvents` is loaded. If it fails because the test
setup cannot load the new module, update the setup rather than changing reducer
behavior.

- [ ] **Step 3: Wire one browser call site through the factory**

In `fixthis-mcp/src/main/console/history.js`, add
`domain/consoleEvents.js` to the `// @requires` header.

Replace direct session row dispatch:

```js
consoleStore.dispatch({ type: 'SESSION_ROW_CLICKED', sessionId });
```

with:

```js
consoleStore.dispatch(ConsoleEvents.sessionRowClicked(sessionId));
```

Keep legacy behavior unchanged if the file still has transitional code after
dispatch. This task only centralizes event construction.

- [ ] **Step 4: Run history/session related tests**

Run:

```bash
node --test scripts/consoleCanonicalState-test.mjs scripts/sessionScopedRequests-test.mjs
npm run console:test:all
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/history.js \
  scripts/consoleCanonicalState-test.mjs \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "refactor(console): dispatch session row events through factory"
```

## Task 7: Extract One Pure Target Reliability View Model

**Files:**
- Create: `fixthis-mcp/src/main/console/domain/targetReliabilityViewModel.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `scripts/targetReliabilityPresentation-test.mjs`

- [ ] **Step 1: Add a focused view model test**

In `scripts/targetReliabilityPresentation-test.mjs`, import the shared loader
and add a runtime test for the new pure module:

```js
import { loadConsoleSymbols } from './console-test-loader.mjs';

const reliabilityModel = loadConsoleSymbols({
  modules: ['domain/targetReliabilityViewModel.js'],
  symbols: ['targetReliabilityBadgeModel'],
});

test('target reliability badge model maps confidence to stable label', () => {
  assert.deepEqual(
    reliabilityModel.targetReliabilityBadgeModel({ confidence: 'high', score: 0.93 }),
    { confidence: 'high', label: 'High', tone: 'good', scoreLabel: '93%' },
  );
  assert.deepEqual(
    reliabilityModel.targetReliabilityBadgeModel({ confidence: 'unknown' }),
    { confidence: 'unknown', label: 'Unknown', tone: 'muted', scoreLabel: '' },
  );
});
```

- [ ] **Step 2: Run the focused test**

Run:

```bash
node --test scripts/targetReliabilityPresentation-test.mjs
```

Expected: FAIL because `domain/targetReliabilityViewModel.js` does not exist.

- [ ] **Step 3: Implement the pure view model module**

Create `fixthis-mcp/src/main/console/domain/targetReliabilityViewModel.js`:

```js
// @requires (none)
function targetReliabilityBadgeModel(targetReliability) {
  const confidence = String(targetReliability?.confidence || 'unknown').toLowerCase();
  const score = Number(targetReliability?.score);
  const scoreLabel = Number.isFinite(score) ? `${Math.round(score * 100)}%` : '';
  if (confidence === 'high') return { confidence, label: 'High', tone: 'good', scoreLabel };
  if (confidence === 'medium') return { confidence, label: 'Medium', tone: 'warn', scoreLabel };
  if (confidence === 'low') return { confidence, label: 'Low', tone: 'bad', scoreLabel };
  return { confidence: 'unknown', label: 'Unknown', tone: 'muted', scoreLabel: '' };
}
```

- [ ] **Step 4: Wire rendering to the pure helper**

In `fixthis-mcp/src/main/console/rendering.js`, add
`domain/targetReliabilityViewModel.js` to the `// @requires` header.

Update `reliabilityBadgeHtml(item)` so it calls the helper:

```js
function reliabilityBadgeHtml(item) {
  const model = targetReliabilityBadgeModel(item?.targetReliability);
  if (model.tone === 'muted') return '';
  return '<span class="ann-row-reliability" data-confidence="' + escapeHtml(model.confidence) + '">' +
    escapeHtml(model.label) +
    (model.scoreLabel ? ' · ' + escapeHtml(model.scoreLabel) : '') +
    '</span>';
}
```

- [ ] **Step 5: Run focused and full console tests**

Run:

```bash
node --test scripts/targetReliabilityPresentation-test.mjs
npm run console:test:all
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/domain/targetReliabilityViewModel.js \
  fixthis-mcp/src/main/console/rendering.js \
  scripts/targetReliabilityPresentation-test.mjs \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "refactor(console): extract target reliability badge model"
```

## Task 8: Optional Local `checkJs` Lane

**Files:**
- Create: `tsconfig.console-check.json`
- Modify: `package.json`
- Modify: `package-lock.json`

- [ ] **Step 1: Add TypeScript dependencies**

Run:

```bash
npm install --save-dev typescript @types/node
```

Expected: `package.json` and `package-lock.json` include `typescript` and
`@types/node`.

- [ ] **Step 2: Create `tsconfig.console-check.json`**

Create:

```json
{
  "compilerOptions": {
    "allowJs": true,
    "checkJs": true,
    "noEmit": true,
    "target": "ES2020",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "lib": ["ES2020", "DOM"],
    "strict": false,
    "skipLibCheck": true
  },
  "include": [
    "fixthis-mcp/src/main/console/domain/*.js",
    "fixthis-mcp/src/main/console/*Fsm.js",
    "fixthis-mcp/src/main/console/draftWorkspace.js",
    "fixthis-mcp/src/main/console/undoRedo.js",
    "scripts/console-test-loader.mjs"
  ]
}
```

- [ ] **Step 3: Add npm script**

In `package.json`, add:

```json
"console:typecheck": "tsc -p tsconfig.console-check.json"
```

- [ ] **Step 4: Run typecheck**

Run:

```bash
npm run console:typecheck
```

Expected: PASS or a small number of concrete JS inference errors in the selected
files.

- [ ] **Step 5: Fix only selected-file typecheck errors**

For each error, prefer narrow JSDoc over behavior changes. Example:

```js
/** @param {{ type?: string, [key: string]: unknown }} event */
function reduceConsoleAppState(state = createInitialConsoleAppState(), event = {}) {
  // existing reducer body
}
```

Do not broaden the include list in this task.

- [ ] **Step 6: Run final JS verification**

Run:

```bash
npm run console:typecheck
npm run console:test:all
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add package.json package-lock.json tsconfig.console-check.json \
  fixthis-mcp/src/main/console/domain/*.js \
  fixthis-mcp/src/main/console/*Fsm.js \
  fixthis-mcp/src/main/console/draftWorkspace.js \
  fixthis-mcp/src/main/console/undoRedo.js
git commit -m "chore(console): add local javascript typecheck"
```

## Final Verification

Run:

```bash
node --test scripts/console-test-loader-test.mjs scripts/consoleBoundary-test.mjs scripts/consoleEventsFactory-test.mjs
npm run console:test:all
node scripts/build-console-assets.mjs --check
```

If Task 8 is implemented, also run:

```bash
npm run console:typecheck
```

Expected: all commands PASS.

## Execution Notes

- Keep each task as a separate commit.
- Do not commit `.fixthis/`.
- Do not hand-edit `fixthis-mcp/src/main/resources/console/app.js`; regenerate
  it with `node scripts/build-console-assets.mjs`.
- Do not rename persisted MCP JSON compatibility fields.
- If implementation discovers that a current function name differs from the plan
  examples, preserve the existing public name and apply the same validation or
  factory pattern at that boundary.
