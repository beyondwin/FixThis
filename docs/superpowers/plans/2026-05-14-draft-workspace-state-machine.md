# Draft Workspace State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the feedback console's scattered pending-annotation globals with a Clean Architecture draft workspace state machine that prevents annotation work from crossing session, preview, screen, or device contexts.

**Architecture:** Add a pure domain reducer first, then application use cases over narrow ports, then browser adapters for localStorage/fetch/clipboard/preview, then wire existing console presentation modules through compatibility shims. The command queue serializes use cases and fences stale workspace revisions; it does not contain annotation business rules. Existing MCP session JSON fields remain compatible.

**Tech Stack:** Kotlin/JVM 21 remains unchanged server-side; vanilla browser JavaScript in `fixthis-mcp/src/main/console`; Node `node:test` for pure and source-structure tests; existing `scripts/build-console-assets.mjs` bundle generation; existing Gradle/JUnit console route tests.

---

## File Structure

Create these new console modules:

- `fixthis-mcp/src/main/console/draftWorkspace.js` - pure domain model, actions, reducer, selectors, invariant helpers.
- `fixthis-mcp/src/main/console/draftWorkspaceHistory.js` - pure undo/redo history helpers over `draftItemId`.
- `fixthis-mcp/src/main/console/draftPorts.js` - documented port validation helpers and test fake factories.
- `fixthis-mcp/src/main/console/draftStorageAdapter.js` - schema-v2 workspace localStorage adapter plus schema-v1 pending migration.
- `fixthis-mcp/src/main/console/draftApiAdapter.js` - explicit-session HTTP request builders and browser API adapter.
- `fixthis-mcp/src/main/console/draftUseCases.js` - application workflows: freeze, edit, save, handoff, recovery, boundary decisions.
- `fixthis-mcp/src/main/console/draftCommandQueue.js` - one-at-a-time command runner with workspace/revision fencing.

Create these Node tests:

- `scripts/draftWorkspace-test.mjs`
- `scripts/draftWorkspaceHistory-test.mjs`
- `scripts/draftStorageAdapter-test.mjs`
- `scripts/draftApiAdapter-test.mjs`
- `scripts/draftUseCases-test.mjs`
- `scripts/draftCommandQueue-test.mjs`
- `scripts/draftWorkflowInvariant-test.mjs`
- `scripts/draftPresentationContract-test.mjs`

Modify these existing files:

- `scripts/build-console-assets.mjs` - add new console modules in dependency order.
- `package.json` - add `console:draft:test` script for the new focused tests.
- `fixthis-mcp/src/main/console/state.js` - add `draftWorkspace`, queue holder, and temporary compatibility sync helpers.
- `fixthis-mcp/src/main/console/annotations.js` - route freeze/add/update/delete/save through use cases and stop direct array writes.
- `fixthis-mcp/src/main/console/main.js` - route recovery and before-boundary decisions through workspace use cases.
- `fixthis-mcp/src/main/console/history.js` - keep boundary calls, but bind them to workspace boundary use cases.
- `fixthis-mcp/src/main/console/prompt.js` - route copy and Save to MCP through explicit-session workspace use cases.
- `fixthis-mcp/src/main/console/rendering.js` - render from selectors/view models instead of direct mutation state.
- `fixthis-mcp/src/main/console/pendingPersistence.js`, `undoRedo.js` - keep as compatibility shims during Tasks 5-7; Task 7 verifies pending arrays are no longer mutated directly.
- `fixthis-mcp/src/main/resources/console/app.js` - regenerate, never hand-edit.

## Conventions

- Use TDD for every task.
- Do not hand-edit `fixthis-mcp/src/main/resources/console/app.js`; regenerate with `node scripts/build-console-assets.mjs`.
- Keep legacy schema-v1 `fixthis.pending.<sessionId>` readable until migration tests pass.
- Console-originated annotation mutations must have explicit `sessionId`; missing context is a local error.
- Commit after each task.

---

### Task 1: Add Pure Draft Workspace Domain

**Files:**
- Create: `fixthis-mcp/src/main/console/draftWorkspace.js`
- Create: `scripts/draftWorkspace-test.mjs`
- Modify: `scripts/build-console-assets.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write the failing domain test**

Create `scripts/draftWorkspace-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftWorkspace.js'), 'utf8');
const factory = new Function(`${src}; return {
  DraftLifecycle,
  createEmptyDraftWorkspace,
  createDraftContext,
  createFrozenDraftWorkspace,
  reduceDraftWorkspace,
  draftWorkspaceItems,
  requireDraftContext
};`);
const m = factory();

const context = {
  sessionId: 'session-a',
  previewId: 'preview-a',
  screenId: 'screen-a',
  screenFingerprint: 'fp-a',
  deviceSerial: 'device-a',
  frozenAtEpochMillis: 1000,
  activityName: 'MainActivity',
};

test('empty workspace has no mutable browser dependencies', () => {
  const workspace = m.createEmptyDraftWorkspace();
  assert.equal(workspace.lifecycle, m.DraftLifecycle.EMPTY);
  assert.deepEqual(workspace.items, []);
  assert.equal(workspace.revision, 0);
  assert.equal(workspace.context, null);
});

test('freeze creates immutable context-bound workspace', () => {
  const workspace = m.createFrozenDraftWorkspace({
    workspaceId: 'ws-a',
    context,
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/api/preview/preview-a/screenshot/full?sessionId=session-a',
  });
  assert.equal(workspace.workspaceId, 'ws-a');
  assert.equal(workspace.lifecycle, m.DraftLifecycle.EDITING);
  assert.equal(workspace.revision, 1);
  assert.deepEqual(workspace.context, context);
  assert.equal(m.requireDraftContext(workspace).sessionId, 'session-a');
});

test('add update delete item preserves stable draftItemId and increments revision', () => {
  let workspace = m.createFrozenDraftWorkspace({
    workspaceId: 'ws-a',
    context,
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/screenshot.png',
  });
  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'ADD_ITEM',
    workspaceId: 'ws-a',
    draftItem: {
      draftItemId: 'draft-1',
      targetType: 'area',
      bounds: { left: 1, top: 2, right: 30, bottom: 40 },
      label: 'Custom area',
      comment: '',
      severity: 'med',
      status: 'open',
    },
  });
  assert.equal(workspace.revision, 2);
  assert.equal(workspace.items[0].draftItemId, 'draft-1');

  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'UPDATE_ITEM',
    workspaceId: 'ws-a',
    draftItemId: 'draft-1',
    patch: { comment: 'fix this button' },
  });
  assert.equal(workspace.revision, 3);
  assert.equal(workspace.items[0].comment, 'fix this button');

  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'DELETE_ITEM',
    workspaceId: 'ws-a',
    draftItemId: 'draft-1',
  });
  assert.equal(workspace.revision, 4);
  assert.equal(workspace.items.length, 0);
});

test('stale workspace id and revision actions are ignored', () => {
  let workspace = m.createFrozenDraftWorkspace({
    workspaceId: 'ws-a',
    context,
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/screenshot.png',
  });
  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'ADD_ITEM',
    workspaceId: 'ws-b',
    draftItem: { draftItemId: 'draft-1' },
  });
  assert.equal(workspace.items.length, 0);

  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'SAVE_SUCCEEDED',
    workspaceId: 'ws-a',
    expectedRevision: 999,
  });
  assert.equal(workspace.lifecycle, m.DraftLifecycle.EDITING);
});

test('save conflict keeps items and moves lifecycle to conflict', () => {
  let workspace = m.createFrozenDraftWorkspace({
    workspaceId: 'ws-a',
    context,
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/screenshot.png',
  });
  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'ADD_ITEM',
    workspaceId: 'ws-a',
    draftItem: { draftItemId: 'draft-1', comment: 'copy' },
  });
  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'SAVE_CONFLICT',
    workspaceId: 'ws-a',
    expectedRevision: workspace.revision,
    conflict: { error: 'screen_fingerprint_mismatch' },
  });
  assert.equal(workspace.lifecycle, m.DraftLifecycle.CONFLICT);
  assert.equal(workspace.items.length, 1);
  assert.equal(workspace.lastError.error, 'screen_fingerprint_mismatch');
});
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
node --test scripts/draftWorkspace-test.mjs
```

Expected: FAIL with `ENOENT` or `draftWorkspace.js` not found.

- [ ] **Step 3: Implement the pure domain module**

Create `fixthis-mcp/src/main/console/draftWorkspace.js`:

```js
// draftWorkspace.js - pure DraftWorkspace domain policy.
// No DOM, fetch, localStorage, timers, or global console state in this file.

const DraftLifecycle = Object.freeze({
  EMPTY: 'empty',
  CAPTURING: 'capturing',
  EDITING: 'editing',
  SAVING: 'saving',
  RECOVERING: 'recovering',
  CONFLICT: 'conflict',
  CLOSED: 'closed',
});

function createEmptyDraftWorkspace() {
  return {
    workspaceId: null,
    revision: 0,
    lifecycle: DraftLifecycle.EMPTY,
    context: null,
    screen: null,
    screenshotUrl: null,
    items: [],
    history: { undoStack: [], redoStack: [] },
    focusedItemId: null,
    currentSelection: null,
    activityDriftWarning: null,
    lastError: null,
  };
}

function cloneDraftValue(value) {
  if (value == null) return value;
  return JSON.parse(JSON.stringify(value));
}

function createDraftContext({ sessionId, previewId, screenId, screenFingerprint, deviceSerial, frozenAtEpochMillis, activityName }) {
  return {
    sessionId: sessionId || null,
    previewId: previewId || null,
    screenId: screenId || null,
    screenFingerprint: screenFingerprint ?? null,
    deviceSerial: deviceSerial || null,
    frozenAtEpochMillis: frozenAtEpochMillis || null,
    activityName: activityName || null,
  };
}

function createFrozenDraftWorkspace({ workspaceId, context, screen, screenshotUrl, history = null }) {
  if (!workspaceId) throw new Error('workspaceId is required');
  if (!context?.sessionId || !context?.previewId) throw new Error('Draft context requires sessionId and previewId');
  return {
    ...createEmptyDraftWorkspace(),
    workspaceId,
    revision: 1,
    lifecycle: DraftLifecycle.EDITING,
    context: cloneDraftValue(context),
    screen: cloneDraftValue(screen),
    screenshotUrl: screenshotUrl || null,
    history: history || { undoStack: [], redoStack: [] },
  };
}

function draftWorkspaceItems(workspace) {
  return Array.isArray(workspace?.items) ? workspace.items : [];
}

function requireDraftContext(workspace) {
  const context = workspace?.context;
  if (!context?.sessionId || !context?.previewId) {
    throw new Error('Annotation context is missing. Re-capture the screen and try again.');
  }
  return context;
}

function shouldIgnoreDraftAction(workspace, action) {
  if (!workspace) return true;
  if (action.workspaceId && workspace.workspaceId && action.workspaceId !== workspace.workspaceId) return true;
  if (Number.isInteger(action.expectedRevision) && workspace.revision !== action.expectedRevision) return true;
  return false;
}

function bumpDraftRevision(workspace, patch) {
  return {
    ...workspace,
    ...patch,
    revision: workspace.revision + 1,
  };
}

function reduceDraftWorkspace(workspace = createEmptyDraftWorkspace(), action = {}) {
  if (shouldIgnoreDraftAction(workspace, action)) return workspace;
  switch (action.type) {
    case 'FREEZE_STARTED':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.CAPTURING,
        lastError: null,
      });
    case 'FREEZE_SUCCEEDED':
      return createFrozenDraftWorkspace(action);
    case 'ADD_ITEM':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.EDITING,
        items: draftWorkspaceItems(workspace).concat(cloneDraftValue(action.draftItem)),
        focusedItemId: action.draftItem?.draftItemId || workspace.focusedItemId,
        currentSelection: null,
        lastError: null,
      });
    case 'UPDATE_ITEM':
      return bumpDraftRevision(workspace, {
        items: draftWorkspaceItems(workspace).map((item) =>
          item.draftItemId === action.draftItemId ? { ...item, ...cloneDraftValue(action.patch) } : item
        ),
        lastError: null,
      });
    case 'DELETE_ITEM':
      return bumpDraftRevision(workspace, {
        items: draftWorkspaceItems(workspace).filter((item) => item.draftItemId !== action.draftItemId),
        focusedItemId: workspace.focusedItemId === action.draftItemId ? null : workspace.focusedItemId,
        currentSelection: null,
        lastError: null,
      });
    case 'FOCUS_ITEM':
      return bumpDraftRevision(workspace, { focusedItemId: action.draftItemId || null });
    case 'CLEAR_FOCUS':
      return bumpDraftRevision(workspace, { focusedItemId: null, currentSelection: null });
    case 'RESTORE_RECOVERY':
      return bumpDraftRevision({
        ...createFrozenDraftWorkspace(action.workspace),
        revision: action.workspace?.revision || 1,
        items: cloneDraftValue(action.workspace?.items || []),
        history: cloneDraftValue(action.workspace?.history || { undoStack: [], redoStack: [] }),
        lifecycle: DraftLifecycle.EDITING,
      }, {});
    case 'SAVE_STARTED':
      return bumpDraftRevision(workspace, { lifecycle: DraftLifecycle.SAVING, lastError: null });
    case 'SAVE_SUCCEEDED':
      return createEmptyDraftWorkspace();
    case 'SAVE_CONFLICT':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.CONFLICT,
        lastError: cloneDraftValue(action.conflict || { error: 'conflict' }),
      });
    case 'SAVE_FAILED':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.EDITING,
        lastError: cloneDraftValue(action.error || { error: 'save_failed' }),
      });
    case 'DISCARD':
    case 'SESSION_BOUNDARY_CLOSED':
      return createEmptyDraftWorkspace();
    default:
      return workspace;
  }
}
```

- [ ] **Step 4: Add the module to the bundle list and package test script**

Modify `scripts/build-console-assets.mjs` so `sources` includes `draftWorkspace.js` immediately after `pendingPersistence.js`:

```js
  'pendingPersistence.js',
  'draftWorkspace.js',
  'beforeunloadGuard.js',
```

Modify `package.json` scripts:

```json
"console:draft:test": "node --test scripts/draftWorkspace-test.mjs"
```

- [ ] **Step 5: Run the focused test**

Run:

```bash
node --test scripts/draftWorkspace-test.mjs
node scripts/build-console-assets.mjs --check
```

Expected: domain test PASS; build check FAIL until `app.js` is regenerated.

- [ ] **Step 6: Regenerate console bundle and rerun**

Run:

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node --test scripts/draftWorkspace-test.mjs
```

Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add package.json scripts/build-console-assets.mjs scripts/draftWorkspace-test.mjs fixthis-mcp/src/main/console/draftWorkspace.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): add draft workspace domain"
```

---

### Task 2: Add Pure History and Narrow Port Contracts

**Files:**
- Create: `fixthis-mcp/src/main/console/draftWorkspaceHistory.js`
- Create: `fixthis-mcp/src/main/console/draftPorts.js`
- Create: `scripts/draftWorkspaceHistory-test.mjs`
- Modify: `scripts/build-console-assets.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write the failing history test**

Create `scripts/draftWorkspaceHistory-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspaceSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftWorkspace.js'), 'utf8');
const historySrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftWorkspaceHistory.js'), 'utf8');
const factory = new Function(`${workspaceSrc}; ${historySrc}; return {
  createEmptyDraftHistory,
  recordDraftAdd,
  recordDraftDelete,
  undoDraftHistory,
  redoDraftHistory
};`);
const m = factory();

test('history records draftItemId operations without array-index identity', () => {
  let items = [{ draftItemId: 'draft-1', comment: 'a' }];
  let history = m.createEmptyDraftHistory();
  history = m.recordDraftDelete(history, items[0], 0);
  items = [];

  const undo = m.undoDraftHistory(history, items);
  assert.equal(undo.applied, true);
  assert.equal(undo.items[0].draftItemId, 'draft-1');

  const redo = m.redoDraftHistory(undo.history, undo.items);
  assert.equal(redo.applied, true);
  assert.equal(redo.items.length, 0);
});

test('history depth is capped at 50', () => {
  let history = m.createEmptyDraftHistory();
  for (let i = 0; i < 55; i += 1) {
    history = m.recordDraftAdd(history, { draftItemId: `draft-${i}` });
  }
  assert.equal(history.undoStack.length, 50);
  assert.equal(history.undoStack[0].after.draftItemId, 'draft-5');
});
```

- [ ] **Step 2: Run the failing test**

```bash
node --test scripts/draftWorkspaceHistory-test.mjs
```

Expected: FAIL because `draftWorkspaceHistory.js` does not exist.

- [ ] **Step 3: Implement pure history helpers**

Create `fixthis-mcp/src/main/console/draftWorkspaceHistory.js`:

```js
// draftWorkspaceHistory.js - pure undo/redo helpers for DraftWorkspace items.

const DraftHistoryMaxDepth = 50;

function createEmptyDraftHistory() {
  return { undoStack: [], redoStack: [] };
}

function cloneDraftHistoryValue(value) {
  if (value == null) return value;
  return JSON.parse(JSON.stringify(value));
}

function pushDraftHistoryOp(stack, op) {
  const next = stack.concat(cloneDraftHistoryValue(op));
  while (next.length > DraftHistoryMaxDepth) next.shift();
  return next;
}

function recordDraftAdd(history, item) {
  return {
    undoStack: pushDraftHistoryOp(history?.undoStack || [], { kind: 'add', after: item }),
    redoStack: [],
  };
}

function recordDraftDelete(history, item, index) {
  return {
    undoStack: pushDraftHistoryOp(history?.undoStack || [], { kind: 'delete', before: item, index }),
    redoStack: [],
  };
}

function recordDraftUpdate(history, before, after) {
  return {
    undoStack: pushDraftHistoryOp(history?.undoStack || [], { kind: 'update', before, after }),
    redoStack: [],
  };
}

function sameDraftHistoryItem(left, right) {
  return Boolean(left?.draftItemId && right?.draftItemId && left.draftItemId === right.draftItemId);
}

function applyDraftInverse(op, items) {
  const next = items.map(cloneDraftHistoryValue);
  if (op.kind === 'add') {
    return next.filter((item) => !sameDraftHistoryItem(item, op.after));
  }
  if (op.kind === 'delete') {
    const restored = cloneDraftHistoryValue(op.before);
    const index = Number.isInteger(op.index) ? Math.max(0, Math.min(op.index, next.length)) : next.length;
    next.splice(index, 0, restored);
    return next;
  }
  if (op.kind === 'update') {
    return next.map((item) => sameDraftHistoryItem(item, op.before) ? cloneDraftHistoryValue(op.before) : item);
  }
  return next;
}

function applyDraftForward(op, items) {
  const next = items.map(cloneDraftHistoryValue);
  if (op.kind === 'add') return next.concat(cloneDraftHistoryValue(op.after));
  if (op.kind === 'delete') return next.filter((item) => !sameDraftHistoryItem(item, op.before));
  if (op.kind === 'update') {
    return next.map((item) => sameDraftHistoryItem(item, op.after) ? cloneDraftHistoryValue(op.after) : item);
  }
  return next;
}

function undoDraftHistory(history, items) {
  const undoStack = (history?.undoStack || []).slice();
  const op = undoStack.pop();
  if (!op) return { applied: false, reason: 'empty', history: history || createEmptyDraftHistory(), items };
  return {
    applied: true,
    history: {
      undoStack,
      redoStack: pushDraftHistoryOp(history?.redoStack || [], op),
    },
    items: applyDraftInverse(op, items || []),
  };
}

function redoDraftHistory(history, items) {
  const redoStack = (history?.redoStack || []).slice();
  const op = redoStack.pop();
  if (!op) return { applied: false, reason: 'empty', history: history || createEmptyDraftHistory(), items };
  return {
    applied: true,
    history: {
      undoStack: pushDraftHistoryOp(history?.undoStack || [], op),
      redoStack,
    },
    items: applyDraftForward(op, items || []),
  };
}
```

- [ ] **Step 4: Add port contract helpers**

Create `fixthis-mcp/src/main/console/draftPorts.js`:

```js
// draftPorts.js - narrow port helpers for DraftWorkspace use cases.
// Port shape:
// {
//   ids: { nextWorkspaceId(): string, nextDraftItemId(): string },
//   clock: { now(): number },
//   preview: { capture(): Promise<FeedbackPreviewSnapshot>, screenshotUrl(previewId, sessionId): string },
//   feedbackApi: { saveDraftWorkspace(request): Promise<object>, saveToMcp(sessionId, itemIds): Promise<object>, handoffPreview(sessionId, itemIds): Promise<string>, markHandedOff(sessionId, itemIds): Promise<object> },
//   storage: { saveWorkspace(envelope): void, deleteWorkspace(sessionId, workspaceId): void, loadWorkspacesForSession(sessionId): object[], migrateLegacyPending(sessionId): object[] },
//   clipboard: { writeText(text): Promise<void> },
//   boundaryPrompt: { choose(workspace, boundaryAction): Promise<'save'|'keep'|'discard'|'cancel'> },
//   refresh: { sessions(): Promise<void> }
// }

function requireDraftPort(ports, name) {
  const value = ports?.[name];
  if (!value) throw new Error('Missing draft port: ' + name);
  return value;
}

function createFakeDraftPorts(overrides = {}) {
  let id = 0;
  return {
    ids: {
      nextWorkspaceId: () => 'workspace-' + (++id),
      nextDraftItemId: () => 'draft-' + (++id),
    },
    clock: { now: () => 1000 },
    preview: {
      capture: async () => { throw new Error('preview.capture fake not configured'); },
      screenshotUrl: (previewId, sessionId) => '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full?sessionId=' + encodeURIComponent(sessionId),
    },
    feedbackApi: {
      saveDraftWorkspace: async () => { throw new Error('feedbackApi.saveDraftWorkspace fake not configured'); },
      saveToMcp: async () => { throw new Error('feedbackApi.saveToMcp fake not configured'); },
      handoffPreview: async () => { throw new Error('feedbackApi.handoffPreview fake not configured'); },
      markHandedOff: async () => { throw new Error('feedbackApi.markHandedOff fake not configured'); },
    },
    storage: {
      saveWorkspace: () => {},
      deleteWorkspace: () => {},
      loadWorkspacesForSession: () => [],
      migrateLegacyPending: () => [],
    },
    clipboard: { writeText: async () => {} },
    boundaryPrompt: { choose: async () => 'cancel' },
    refresh: { sessions: async () => {} },
    ...overrides,
  };
}
```

- [ ] **Step 5: Register files in the bundle**

Modify `scripts/build-console-assets.mjs`:

```js
  'pendingPersistence.js',
  'draftWorkspace.js',
  'draftWorkspaceHistory.js',
  'draftPorts.js',
  'beforeunloadGuard.js',
```

Modify `package.json`:

```json
"console:draft:test": "node --test scripts/draftWorkspace-test.mjs scripts/draftWorkspaceHistory-test.mjs"
```

- [ ] **Step 6: Run focused tests and bundle check**

```bash
node --test scripts/draftWorkspace-test.mjs scripts/draftWorkspaceHistory-test.mjs
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add package.json scripts/build-console-assets.mjs scripts/draftWorkspaceHistory-test.mjs fixthis-mcp/src/main/console/draftWorkspaceHistory.js fixthis-mcp/src/main/console/draftPorts.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): add draft workspace ports and history"
```

---

### Task 3: Add Storage and API Adapters

**Files:**
- Create: `fixthis-mcp/src/main/console/draftStorageAdapter.js`
- Create: `fixthis-mcp/src/main/console/draftApiAdapter.js`
- Create: `scripts/draftStorageAdapter-test.mjs`
- Create: `scripts/draftApiAdapter-test.mjs`
- Modify: `scripts/build-console-assets.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write failing storage adapter tests**

Create `scripts/draftStorageAdapter-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const storageSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftStorageAdapter.js'), 'utf8');
const factory = new Function(`${storageSrc}; return {
  draftWorkspaceKey,
  draftWorkspaceIndexKey,
  createDraftStorageAdapter
};`);
const m = factory();

function fakeLocalStorage(seed = {}) {
  const values = new Map(Object.entries(seed));
  return {
    getItem: (key) => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key),
    key: (index) => Array.from(values.keys())[index] || null,
    get length() { return values.size; },
    dump: () => Object.fromEntries(values.entries()),
  };
}

test('workspace storage is keyed by captured session and workspace', () => {
  const localStorage = fakeLocalStorage();
  const adapter = m.createDraftStorageAdapter(localStorage);
  adapter.saveWorkspace({
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'ws-a',
    revision: 2,
    context: { sessionId: 'session-a' },
    items: [{ draftItemId: 'draft-1' }],
  });
  assert.ok(localStorage.getItem(m.draftWorkspaceKey('session-a', 'ws-a')));
  assert.deepEqual(adapter.loadWorkspacesForSession('session-a').map((w) => w.workspaceId), ['ws-a']);
  assert.deepEqual(adapter.loadWorkspacesForSession('session-b'), []);
});

test('schema v1 pending envelope migrates into schema v2 workspace recovery', () => {
  const legacy = {
    schemaVersion: 1,
    sessionId: 'session-a',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    previewId: 'preview-a',
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/shot.png',
    frozenAtEpochMillis: 1000,
    items: [{ annotationId: 'local-1', comment: 'legacy' }],
  };
  const localStorage = fakeLocalStorage({ 'fixthis.pending.session-a': JSON.stringify(legacy) });
  const adapter = m.createDraftStorageAdapter(localStorage, { nextWorkspaceId: () => 'ws-migrated' });
  const migrated = adapter.migrateLegacyPending('session-a');
  assert.equal(migrated.length, 1);
  assert.equal(migrated[0].schemaVersion, 2);
  assert.equal(migrated[0].workspaceId, 'ws-migrated');
  assert.equal(migrated[0].context.sessionId, 'session-a');
  assert.equal(migrated[0].items[0].draftItemId, 'local-1');
});
```

- [ ] **Step 2: Write failing API adapter tests**

Create `scripts/draftApiAdapter-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspaceSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftWorkspace.js'), 'utf8');
const apiSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftApiAdapter.js'), 'utf8');
const factory = new Function(`${workspaceSrc}; ${apiSrc}; return {
  buildDraftWorkspaceSaveRequest,
  createDraftApiAdapter
};`);
const m = factory();

const workspace = {
  workspaceId: 'ws-a',
  revision: 2,
  context: {
    sessionId: 'session-a',
    previewId: 'preview-a',
    screenFingerprint: 'fp-a',
  },
  screen: { screenId: 'screen-a' },
  items: [{
    draftItemId: 'draft-1',
    targetType: 'area',
    bounds: { left: 1, top: 2, right: 30, bottom: 40 },
    label: 'Custom area',
    severity: 'med',
    status: 'open',
    comment: 'fix',
  }],
};

test('save request includes explicit session context', () => {
  const request = m.buildDraftWorkspaceSaveRequest(workspace);
  assert.equal(request.sessionId, 'session-a');
  assert.equal(request.previewId, 'preview-a');
  assert.equal(request.frozenFingerprint, 'fp-a');
  assert.equal(request.items[0].comment, 'fix');
});

test('save request rejects missing sessionId and previewId', () => {
  assert.throws(() => m.buildDraftWorkspaceSaveRequest({ ...workspace, context: { previewId: 'p' } }), /sessionId/);
  assert.throws(() => m.buildDraftWorkspaceSaveRequest({ ...workspace, context: { sessionId: 's' } }), /previewId/);
});

test('browser API adapter posts explicit session payloads', async () => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return { ok: true, json: async () => ({ sessionId: 'session-a' }) };
  };
  const adapter = m.createDraftApiAdapter({ fetchImpl, consoleToken: 'token' });
  await adapter.saveDraftWorkspace(m.buildDraftWorkspaceSaveRequest(workspace));
  assert.equal(calls[0].url, '/api/items/batch');
  assert.equal(JSON.parse(calls[0].init.body).sessionId, 'session-a');
  assert.equal(calls[0].init.headers.get('X-FixThis-Console-Token'), 'token');
});
```

- [ ] **Step 3: Run failing adapter tests**

```bash
node --test scripts/draftStorageAdapter-test.mjs scripts/draftApiAdapter-test.mjs
```

Expected: FAIL because adapter files do not exist.

- [ ] **Step 4: Implement storage adapter**

Create `fixthis-mcp/src/main/console/draftStorageAdapter.js`:

```js
// draftStorageAdapter.js - browser storage adapter for DraftWorkspace recovery.

const DraftWorkspaceKeyPrefix = 'fixthis.workspace.';
const LegacyPendingKeyPrefix = 'fixthis.pending.';

function draftWorkspaceKey(sessionId, workspaceId) {
  return DraftWorkspaceKeyPrefix + sessionId + '.' + workspaceId;
}

function draftWorkspaceIndexKey(sessionId) {
  return DraftWorkspaceKeyPrefix + 'index.' + sessionId;
}

function parseDraftStorageJson(raw) {
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (_) { return null; }
}

function normalizeLegacyDraftItem(item, index) {
  return {
    ...item,
    draftItemId: item?.draftItemId || item?.annotationId || ('legacy-' + (index + 1)),
  };
}

function createDraftStorageAdapter(localStorageLike, ids = {}) {
  const nextWorkspaceId = ids.nextWorkspaceId || (() => 'workspace-' + Date.now());

  function readIndex(sessionId) {
    const parsed = parseDraftStorageJson(localStorageLike.getItem(draftWorkspaceIndexKey(sessionId)));
    return Array.isArray(parsed) ? parsed : [];
  }

  function writeIndex(sessionId, workspaceIds) {
    localStorageLike.setItem(draftWorkspaceIndexKey(sessionId), JSON.stringify(Array.from(new Set(workspaceIds))));
  }

  function saveWorkspace(envelope) {
    const sessionId = envelope?.sessionId || envelope?.context?.sessionId;
    const workspaceId = envelope?.workspaceId;
    if (!sessionId || !workspaceId) throw new Error('Workspace storage requires sessionId and workspaceId');
    const normalized = { ...envelope, schemaVersion: 2, sessionId, workspaceId };
    localStorageLike.setItem(draftWorkspaceKey(sessionId, workspaceId), JSON.stringify(normalized));
    writeIndex(sessionId, readIndex(sessionId).concat(workspaceId));
  }

  function loadWorkspacesForSession(sessionId) {
    return readIndex(sessionId)
      .map((workspaceId) => parseDraftStorageJson(localStorageLike.getItem(draftWorkspaceKey(sessionId, workspaceId))))
      .filter((value) => value?.schemaVersion === 2 && value?.context?.sessionId === sessionId);
  }

  function deleteWorkspace(sessionId, workspaceId) {
    localStorageLike.removeItem(draftWorkspaceKey(sessionId, workspaceId));
    writeIndex(sessionId, readIndex(sessionId).filter((id) => id !== workspaceId));
  }

  function migrateLegacyPending(sessionId) {
    const raw = localStorageLike.getItem(LegacyPendingKeyPrefix + sessionId);
    const legacy = parseDraftStorageJson(raw);
    if (!legacy || !Array.isArray(legacy.items) || !legacy.items.length) return [];
    if (legacy.schemaVersion === 0 || (!legacy.context && !legacy.previewId)) {
      return [{
        schemaVersion: 0,
        sessionId,
        requiresRecapture: true,
        items: legacy.items.map(normalizeLegacyDraftItem),
      }];
    }
    const workspaceId = nextWorkspaceId();
    const envelope = {
      schemaVersion: 2,
      sessionId,
      workspaceId,
      revision: 1,
      lifecycle: 'editing',
      context: legacy.context || {
        sessionId,
        previewId: legacy.previewId,
        screenId: legacy.screen?.screenId || null,
        screenFingerprint: legacy.screen?.fingerprint ?? null,
        deviceSerial: null,
        frozenAtEpochMillis: legacy.frozenAtEpochMillis || null,
        activityName: legacy.activity || legacy.screen?.activityName || null,
      },
      screen: legacy.screen || null,
      screenshotUrl: legacy.screenshotUrl || null,
      items: legacy.items.map(normalizeLegacyDraftItem),
      history: { undoStack: [], redoStack: [] },
      updatedAtEpochMillis: Date.now(),
    };
    saveWorkspace(envelope);
    return [envelope];
  }

  return { saveWorkspace, loadWorkspacesForSession, deleteWorkspace, migrateLegacyPending };
}
```

- [ ] **Step 5: Implement API adapter**

Create `fixthis-mcp/src/main/console/draftApiAdapter.js`:

```js
// draftApiAdapter.js - explicit-session HTTP adapter for DraftWorkspace use cases.

function draftItemToAnnotationDraftDto(item) {
  return {
    targetType: item.targetType,
    bounds: item.bounds,
    nodeUid: item.nodeUid,
    label: String(item.label || '').trim() || null,
    severity: item.severity || 'med',
    status: String(item.status || 'open').replace('-', '_'),
    comment: String(item.comment || ''),
  };
}

function buildDraftWorkspaceSaveRequest(workspace, options = {}) {
  const context = requireDraftContext(workspace);
  if (!context.sessionId) throw new Error('Draft save requires sessionId');
  if (!context.previewId) throw new Error('Draft save requires previewId');
  return {
    sessionId: context.sessionId,
    previewId: context.previewId,
    screen: workspace.screen || null,
    items: (workspace.items || [])
      .filter((item) => options.allowBlankComments || String(item.comment || '').trim())
      .map(draftItemToAnnotationDraftDto),
    frozenFingerprint: context.screenFingerprint,
    forceMismatchOverride: Boolean(options.forceMismatchOverride),
  };
}

function createDraftApiHeaders(consoleToken) {
  const headers = new Headers({ 'Content-Type': 'application/json' });
  if (consoleToken) headers.set('X-FixThis-Console-Token', consoleToken);
  return headers;
}

function createDraftApiAdapter({ fetchImpl = fetch, consoleToken = null } = {}) {
  async function requestJson(path, body) {
    const response = await fetchImpl(path, {
      method: 'POST',
      headers: createDraftApiHeaders(consoleToken),
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      if (response.status === 409) {
        const conflict = await response.json().catch(() => ({}));
        return { conflict };
      }
      throw new Error(await response.text?.() || 'HTTP ' + response.status);
    }
    return await response.json();
  }

  return {
    saveDraftWorkspace: (request) => requestJson('/api/items/batch', request),
    saveToMcp: (sessionId, itemIds) => requestJson('/api/agent-handoffs', { sessionId, itemIds }),
    handoffPreview: async (sessionId, itemIds) => {
      const response = await fetchImpl('/api/sessions/' + encodeURIComponent(sessionId) + '/handoff-preview', {
        method: 'POST',
        headers: createDraftApiHeaders(consoleToken),
        body: JSON.stringify({ itemIds }),
      });
      if (!response.ok) throw new Error(await response.text?.() || 'HTTP ' + response.status);
      return await response.text();
    },
    markHandedOff: (sessionId, itemIds) =>
      requestJson('/api/sessions/' + encodeURIComponent(sessionId) + '/items/mark-handed-off', { itemIds }),
  };
}
```

- [ ] **Step 6: Register adapter files**

Modify `scripts/build-console-assets.mjs`:

```js
  'draftPorts.js',
  'draftStorageAdapter.js',
  'beforeunloadGuard.js',
  ...
  'api.js',
  'draftApiAdapter.js',
  'connection.js',
```

Modify `package.json`:

```json
"console:draft:test": "node --test scripts/draftWorkspace-test.mjs scripts/draftWorkspaceHistory-test.mjs scripts/draftStorageAdapter-test.mjs scripts/draftApiAdapter-test.mjs"
```

- [ ] **Step 7: Run focused tests and regenerate bundle**

```bash
node --test scripts/draftStorageAdapter-test.mjs scripts/draftApiAdapter-test.mjs
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
npm run console:draft:test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add package.json scripts/build-console-assets.mjs scripts/draftStorageAdapter-test.mjs scripts/draftApiAdapter-test.mjs fixthis-mcp/src/main/console/draftStorageAdapter.js fixthis-mcp/src/main/console/draftApiAdapter.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): add draft workspace adapters"
```

---

### Task 4: Add Use Cases and Command Queue

**Files:**
- Create: `fixthis-mcp/src/main/console/draftUseCases.js`
- Create: `fixthis-mcp/src/main/console/draftCommandQueue.js`
- Create: `scripts/draftUseCases-test.mjs`
- Create: `scripts/draftCommandQueue-test.mjs`
- Modify: `scripts/build-console-assets.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write failing use case tests**

Create `scripts/draftUseCases-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'draftWorkspace.js',
  'draftWorkspaceHistory.js',
  'draftPorts.js',
  'draftApiAdapter.js',
  'draftUseCases.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');
const factory = new Function(`${sources}; return {
  createEmptyDraftWorkspace,
  createFakeDraftPorts,
  startDraftFreeze,
  addDraftItem,
  updateDraftItem,
  deleteDraftItem,
  persistDraftWorkspace,
  resolveDraftBoundary
};`);
const m = factory();

test('startDraftFreeze captures session preview context through ports', async () => {
  const ports = m.createFakeDraftPorts({
    ids: { nextWorkspaceId: () => 'ws-a', nextDraftItemId: () => 'draft-unused' },
    clock: { now: () => 1234 },
    preview: {
      capture: async () => ({ previewId: 'preview-a', screen: { screenId: 'screen-a', fingerprint: 'fp-a' }, activity: 'MainActivity' }),
      screenshotUrl: (previewId, sessionId) => `/api/preview/${previewId}/screenshot/full?sessionId=${sessionId}`,
    },
  });
  const workspace = await m.startDraftFreeze(m.createEmptyDraftWorkspace(), {
    sessionId: 'session-a',
    selectedDeviceSerial: 'device-a',
  }, ports);
  assert.equal(workspace.workspaceId, 'ws-a');
  assert.equal(workspace.context.sessionId, 'session-a');
  assert.equal(workspace.context.previewId, 'preview-a');
  assert.equal(workspace.context.screenFingerprint, 'fp-a');
});

test('add update delete use cases use stable draft item ids and history', () => {
  const ports = m.createFakeDraftPorts({ ids: { nextDraftItemId: () => 'draft-1', nextWorkspaceId: () => 'ws-unused' } });
  let workspace = {
    workspaceId: 'ws-a',
    revision: 1,
    lifecycle: 'editing',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    screen: {},
    screenshotUrl: '/shot.png',
    items: [],
    history: { undoStack: [], redoStack: [] },
  };
  workspace = m.addDraftItem(workspace, { targetType: 'area', bounds: { left: 1, top: 1, right: 2, bottom: 2 }, label: 'Area' }, ports);
  assert.equal(workspace.items[0].draftItemId, 'draft-1');
  workspace = m.updateDraftItem(workspace, 'draft-1', { comment: 'fix' });
  assert.equal(workspace.items[0].comment, 'fix');
  workspace = m.deleteDraftItem(workspace, 'draft-1');
  assert.equal(workspace.items.length, 0);
  assert.equal(workspace.history.undoStack.at(-1).kind, 'delete');
});

test('persistDraftWorkspace returns conflict without clearing workspace', async () => {
  const workspace = {
    workspaceId: 'ws-a',
    revision: 2,
    lifecycle: 'editing',
    context: { sessionId: 'session-a', previewId: 'preview-a', screenFingerprint: 'fp-a' },
    screen: { screenId: 'screen-a' },
    items: [{ draftItemId: 'draft-1', targetType: 'area', bounds: { left: 1, top: 1, right: 2, bottom: 2 }, comment: 'fix' }],
  };
  const ports = m.createFakeDraftPorts({
    feedbackApi: {
      saveDraftWorkspace: async () => ({ conflict: { error: 'screen_fingerprint_mismatch' } }),
    },
  });
  const result = await m.persistDraftWorkspace(workspace, ports);
  assert.equal(result.workspace.lifecycle, 'conflict');
  assert.equal(result.workspace.items.length, 1);
});

test('resolveDraftBoundary keep saves recoverable workspace without discard', async () => {
  const saved = [];
  const workspace = { workspaceId: 'ws-a', context: { sessionId: 'session-a' }, items: [{ draftItemId: 'draft-1' }] };
  const ports = m.createFakeDraftPorts({
    boundaryPrompt: { choose: async () => 'keep' },
    storage: { saveWorkspace: (value) => saved.push(value) },
  });
  const result = await m.resolveDraftBoundary(workspace, { kind: 'open-session' }, ports);
  assert.equal(result.choice, 'keep');
  assert.equal(saved[0].workspaceId, 'ws-a');
});
```

- [ ] **Step 2: Write failing command queue tests**

Create `scripts/draftCommandQueue-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftCommandQueue.js'), 'utf8');
const factory = new Function(`${src}; return { createDraftCommandQueue };`);
const m = factory();

test('queue serializes commands and fences stale workspace responses', async () => {
  let workspace = { workspaceId: 'ws-a', revision: 1 };
  const events = [];
  const queue = m.createDraftCommandQueue({
    getWorkspace: () => workspace,
    setWorkspace: (next) => { workspace = next; },
    onStaleResponse: () => events.push('stale'),
  });

  const first = queue.enqueue({ kind: 'save', workspaceId: 'ws-a', expectedRevision: 1 }, async () => {
    events.push('first-start');
    workspace = { workspaceId: 'ws-b', revision: 1 };
    return { workspace: { workspaceId: 'ws-a', revision: 2 } };
  });
  const second = queue.enqueue({ kind: 'edit', workspaceId: 'ws-b', expectedRevision: 1 }, async () => {
    events.push('second-start');
    return { workspace: { workspaceId: 'ws-b', revision: 2 } };
  });

  await Promise.all([first, second]);
  assert.deepEqual(events, ['first-start', 'stale', 'second-start']);
  assert.equal(workspace.workspaceId, 'ws-b');
  assert.equal(workspace.revision, 2);
});
```

- [ ] **Step 3: Run failing tests**

```bash
node --test scripts/draftUseCases-test.mjs scripts/draftCommandQueue-test.mjs
```

Expected: FAIL because use case and queue files do not exist.

- [ ] **Step 4: Implement use cases**

Create `fixthis-mcp/src/main/console/draftUseCases.js`:

```js
// draftUseCases.js - DraftWorkspace application workflows over narrow ports.

async function startDraftFreeze(workspace, input, ports) {
  requireDraftPort(ports, 'preview');
  requireDraftPort(ports, 'ids');
  requireDraftPort(ports, 'clock');
  const preview = await ports.preview.capture();
  const sessionId = input?.sessionId;
  if (!sessionId) throw new Error('Cannot annotate without an active feedback session.');
  const context = createDraftContext({
    sessionId,
    previewId: preview.previewId,
    screenId: preview.screen?.screenId || null,
    screenFingerprint: preview.screen?.fingerprint ?? null,
    deviceSerial: input?.selectedDeviceSerial || null,
    frozenAtEpochMillis: ports.clock.now(),
    activityName: preview.activity || input?.activityName || null,
  });
  return createFrozenDraftWorkspace({
    workspaceId: ports.ids.nextWorkspaceId(),
    context,
    screen: preview.screen,
    screenshotUrl: ports.preview.screenshotUrl(preview.previewId, sessionId),
  });
}

function draftSelectionToItem(selection, ports) {
  return {
    draftItemId: ports.ids.nextDraftItemId(),
    targetType: selection.targetType,
    nodeUid: selection.nodeUid,
    bounds: selection.bounds,
    label: selection.label || null,
    severity: 'med',
    status: 'open',
    comment: '',
  };
}

function addDraftItem(workspace, selection, ports) {
  const draftItem = draftSelectionToItem(selection, ports);
  const next = reduceDraftWorkspace(workspace, { type: 'ADD_ITEM', workspaceId: workspace.workspaceId, draftItem });
  return { ...next, history: recordDraftAdd(next.history, draftItem) };
}

function updateDraftItem(workspace, draftItemId, patch) {
  const before = (workspace.items || []).find((item) => item.draftItemId === draftItemId);
  const next = reduceDraftWorkspace(workspace, { type: 'UPDATE_ITEM', workspaceId: workspace.workspaceId, draftItemId, patch });
  const after = (next.items || []).find((item) => item.draftItemId === draftItemId);
  return before && after ? { ...next, history: recordDraftUpdate(next.history, before, after) } : next;
}

function deleteDraftItem(workspace, draftItemId) {
  const index = (workspace.items || []).findIndex((item) => item.draftItemId === draftItemId);
  const before = index >= 0 ? workspace.items[index] : null;
  const next = reduceDraftWorkspace(workspace, { type: 'DELETE_ITEM', workspaceId: workspace.workspaceId, draftItemId });
  return before ? { ...next, history: recordDraftDelete(next.history, before, index) } : next;
}

async function persistDraftWorkspace(workspace, ports, options = {}) {
  const started = reduceDraftWorkspace(workspace, { type: 'SAVE_STARTED', workspaceId: workspace.workspaceId });
  const request = buildDraftWorkspaceSaveRequest(started, options);
  const response = await ports.feedbackApi.saveDraftWorkspace(request);
  if (response?.conflict?.error) {
    return {
      workspace: reduceDraftWorkspace(started, {
        type: 'SAVE_CONFLICT',
        workspaceId: started.workspaceId,
        expectedRevision: started.revision,
        conflict: response.conflict,
      }),
      session: null,
      conflict: response.conflict,
    };
  }
  ports.storage?.deleteWorkspace?.(started.context.sessionId, started.workspaceId);
  return {
    workspace: reduceDraftWorkspace(started, {
      type: 'SAVE_SUCCEEDED',
      workspaceId: started.workspaceId,
      expectedRevision: started.revision,
    }),
    session: response,
    itemIds: (response?.items || []).map((item) => item.itemId),
  };
}

function draftWorkspaceRecoveryEnvelope(workspace) {
  return {
    schemaVersion: 2,
    sessionId: workspace.context?.sessionId,
    workspaceId: workspace.workspaceId,
    revision: workspace.revision,
    lifecycle: workspace.lifecycle,
    context: workspace.context,
    screen: workspace.screen,
    screenshotUrl: workspace.screenshotUrl,
    items: workspace.items || [],
    history: workspace.history || { undoStack: [], redoStack: [] },
    updatedAtEpochMillis: Date.now(),
  };
}

async function resolveDraftBoundary(workspace, boundaryAction, ports) {
  if (!workspace?.workspaceId || !(workspace.items || []).length) return { choice: 'continue', workspace };
  const choice = await ports.boundaryPrompt.choose(workspace, boundaryAction);
  if (choice === 'keep') {
    ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(workspace));
    return { choice, workspace };
  }
  if (choice === 'discard') {
    ports.storage.deleteWorkspace(workspace.context?.sessionId, workspace.workspaceId);
    return { choice, workspace: reduceDraftWorkspace(workspace, { type: 'DISCARD', workspaceId: workspace.workspaceId }) };
  }
  if (choice === 'save') {
    const result = await persistDraftWorkspace(workspace, ports, { allowBlankComments: true });
    return { choice, ...result };
  }
  return { choice: 'cancel', workspace };
}
```

- [ ] **Step 5: Implement queue**

Create `fixthis-mcp/src/main/console/draftCommandQueue.js`:

```js
// draftCommandQueue.js - serialize DraftWorkspace application commands.

function createDraftCommandQueue({ getWorkspace, setWorkspace, onStaleResponse = () => {}, onError = () => {} }) {
  let tail = Promise.resolve();
  let pendingCount = 0;

  function matchesMeta(workspace, meta) {
    if (meta.workspaceId && workspace?.workspaceId !== meta.workspaceId) return false;
    if (Number.isInteger(meta.expectedRevision) && workspace?.revision !== meta.expectedRevision) return false;
    return true;
  }

  function enqueue(meta, run) {
    const execute = async () => {
      pendingCount += 1;
      try {
        const before = getWorkspace();
        if (!matchesMeta(before, meta)) {
          onStaleResponse(meta);
          return { applied: false, reason: 'stale_before' };
        }
        const result = await run(before);
        const current = getWorkspace();
        if (!matchesMeta(current, meta) && result?.workspace?.workspaceId !== current?.workspaceId) {
          onStaleResponse(meta, result);
          return { applied: false, reason: 'stale_after' };
        }
        if (result?.workspace) setWorkspace(result.workspace);
        return { applied: true, result };
      } catch (error) {
        onError(error, meta);
        throw error;
      } finally {
        pendingCount -= 1;
      }
    };
    const promise = tail.then(execute, execute);
    tail = promise.catch(() => {});
    return promise;
  }

  return {
    enqueue,
    isIdle: () => pendingCount === 0,
    pendingCount: () => pendingCount,
  };
}
```

- [ ] **Step 6: Register files and script**

Modify `scripts/build-console-assets.mjs` after `draftApiAdapter.js`:

```js
  'draftApiAdapter.js',
  'draftUseCases.js',
  'draftCommandQueue.js',
```

Modify `package.json`:

```json
"console:draft:test": "node --test scripts/draftWorkspace-test.mjs scripts/draftWorkspaceHistory-test.mjs scripts/draftStorageAdapter-test.mjs scripts/draftApiAdapter-test.mjs scripts/draftUseCases-test.mjs scripts/draftCommandQueue-test.mjs"
```

- [ ] **Step 7: Run focused tests and regenerate bundle**

```bash
node --test scripts/draftUseCases-test.mjs scripts/draftCommandQueue-test.mjs
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
npm run console:draft:test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add package.json scripts/build-console-assets.mjs scripts/draftUseCases-test.mjs scripts/draftCommandQueue-test.mjs fixthis-mcp/src/main/console/draftUseCases.js fixthis-mcp/src/main/console/draftCommandQueue.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): add draft workspace use cases"
```

---

### Task 5: Wire Draft Workspace Into Annotation Editing

**Files:**
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Create: `scripts/draftPresentationContract-test.mjs`
- Modify: `package.json`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`

- [ ] **Step 1: Write failing presentation contract tests**

Create `scripts/draftPresentationContract-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const stateSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/state.js'), 'utf8');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
const renderingSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/rendering.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const paramsEnd = source.indexOf(')', start);
  const bodyStart = source.indexOf('{', paramsEnd);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('state owns a draft workspace and command queue', () => {
  assert.match(stateSource, /let draftWorkspace = createEmptyDraftWorkspace\(\);/);
  assert.match(stateSource, /let draftCommandQueue = null;/);
  assert.match(stateSource, /function setDraftWorkspace\(nextWorkspace\)/);
});

test('annotation creation uses addDraftItem use case instead of direct push', () => {
  const createBody = body(annotationsSource, 'function createAnnotationFromSelection(selection)');
  assert.match(createBody, /addDraftItem\(/);
  assert.doesNotMatch(createBody, /pendingFeedbackItems\.push\(/);
});

test('pending delete uses deleteDraftItem use case instead of direct splice', () => {
  const deleteBody = body(annotationsSource, 'function deletePendingFeedbackItem(index)');
  assert.match(deleteBody, /deleteDraftItem\(/);
  assert.doesNotMatch(deleteBody, /pendingFeedbackItems\.splice\(/);
});

test('pending overlay renders from draft workspace selector', () => {
  assert.match(renderingSource, /draftWorkspaceItems\(draftWorkspace\)/);
});
```

- [ ] **Step 2: Run failing contract tests**

```bash
node --test scripts/draftPresentationContract-test.mjs
```

Expected: FAIL because presentation is still mutating old globals directly.

- [ ] **Step 3: Add workspace state and compatibility sync**

Modify `fixthis-mcp/src/main/console/state.js` after `undoRedoHistory`:

```js
let draftWorkspace = createEmptyDraftWorkspace();
let draftCommandQueue = null;

function currentDraftWorkspace() {
  return draftWorkspace;
}

function setDraftWorkspace(nextWorkspace) {
  draftWorkspace = nextWorkspace || createEmptyDraftWorkspace();
  syncDraftWorkspaceCompatibility();
  persistCurrentDraftWorkspaceIfNeeded();
}

function syncDraftWorkspaceCompatibility() {
  if (draftWorkspace.lifecycle === DraftLifecycle.EMPTY) {
    addItemsFlow = null;
    pendingFeedbackItems = [];
    focusedPendingItemIndex = null;
    currentSelection = null;
    undoRedoHistory = createHistory();
    return;
  }
  addItemsFlow = {
    context: draftWorkspace.context,
    previewId: draftWorkspace.context?.previewId || null,
    screen: draftWorkspace.screen,
    screenshotUrl: draftWorkspace.screenshotUrl,
    frozenAtEpochMillis: draftWorkspace.context?.frozenAtEpochMillis || null,
    activity: draftWorkspace.context?.activityName || null,
    activityDriftWarning: draftWorkspace.activityDriftWarning || null,
  };
  pendingFeedbackItems = draftWorkspace.items;
  focusedPendingItemIndex = draftWorkspace.focusedItemId
    ? draftWorkspace.items.findIndex((item) => item.draftItemId === draftWorkspace.focusedItemId)
    : null;
  if (focusedPendingItemIndex < 0) focusedPendingItemIndex = null;
  currentSelection = draftWorkspace.currentSelection;
  undoRedoHistory = draftWorkspace.history || createHistory(draftWorkspace.context);
}

function createBrowserDraftPorts() {
  return createFakeDraftPorts({
    ids: {
      nextWorkspaceId: () => 'workspace-' + Date.now() + '-' + Math.random().toString(36).slice(2),
      nextDraftItemId: () => 'draft-' + annotationSequence++,
    },
    clock: { now: () => Date.now() },
    preview: {
      capture: requestLivePreview,
      screenshotUrl: previewScreenshotUrl,
    },
    feedbackApi: createDraftApiAdapter({
      fetchImpl: fetch.bind(window),
      consoleToken: window.FixThisConsoleConfig?.consoleToken || null,
    }),
    storage: createDraftStorageAdapter(localStorage, {
      nextWorkspaceId: () => 'workspace-' + Date.now() + '-' + Math.random().toString(36).slice(2),
    }),
    clipboard: { writeText: copyTextToClipboard },
    boundaryPrompt: { choose: promptPendingBoundaryChoice },
    refresh: { sessions: refreshSessions },
  });
}

function ensureDraftCommandQueue() {
  if (draftCommandQueue) return draftCommandQueue;
  draftCommandQueue = createDraftCommandQueue({
    getWorkspace: currentDraftWorkspace,
    setWorkspace: setDraftWorkspace,
    onStaleResponse: () => refreshSessions().catch(showError),
    onError: showError,
  });
  return draftCommandQueue;
}
```

- [ ] **Step 4: Route annotation creation and deletion through use cases**

Modify `createAnnotationFromSelection(selection)` in `annotations.js` so the mutation section becomes:

```js
const ports = createBrowserDraftPorts();
const nextWorkspace = addDraftItem(draftWorkspace, selection, ports);
setDraftWorkspace(nextWorkspace);
const createdItem = nextWorkspace.items[nextWorkspace.items.length - 1];
focusedSavedItemId = null;
focusedSavedSessionId = null;
toolMode = 'annotate';
comment.value = '';
renderPreviewOnly();
renderInspectorRegion();
renderCurrentSessionList();
return createdItem;
```

Remove the direct `pendingFeedbackItems.push(annotation)` and `recordAdd(...)` lines from that function.

Modify `deletePendingFeedbackItem(index)`:

```js
const removed = draftWorkspace.items[index];
if (!removed) return;
setDraftWorkspace(deleteDraftItem(draftWorkspace, removed.draftItemId));
showUndoToast(removed.draftItemId);
focusedSavedItemId = null;
focusedSavedSessionId = null;
comment.value = '';
renderPreviewOnly();
renderInspectorRegion();
renderCurrentSessionList();
```

Remove the direct `pendingFeedbackItems.splice(index, 1)` and `recordDelete(...)` lines.

- [ ] **Step 5: Route freeze through use case while preserving existing preview behavior**

Modify `startAddItemsFlow()` after the live preview acquisition block:

```js
const ports = createBrowserDraftPorts();
const nextWorkspace = await startDraftFreeze(draftWorkspace, {
  sessionId: state.session?.sessionId || null,
  selectedDeviceSerial: state.selectedDeviceSerial || null,
  activityName: state.connection?.availability?.activity ?? null,
}, {
  ...ports,
  preview: {
    ...ports.preview,
    capture: async () => state.preview,
  },
});
setDraftWorkspace(nextWorkspace);
toolMode = 'annotate';
focusedSavedItemId = null;
focusedSavedSessionId = null;
render();
```

Remove the manual `addItemsFlow = { ... }` assignment in that function.

- [ ] **Step 6: Update rendering to read selector**

Modify `renderNumberedFeedbackOverlay` in `rendering.js`:

```js
function renderNumberedFeedbackOverlay(overlay, image) {
  draftWorkspaceItems(draftWorkspace).forEach((item, index) => {
    const displayNumber = index + 1;
    renderOverlayBox(overlay, image, item.bounds, String(displayNumber), false, index === focusedPendingItemIndex, index, '', severityColor(annotationSeverity(item)));
  });
}
```

- [ ] **Step 7: Run tests**

```bash
node --test scripts/draftPresentationContract-test.mjs
npm run console:draft:test
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add package.json scripts/draftPresentationContract-test.mjs fixthis-mcp/src/main/console/state.js fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/console/rendering.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): route pending annotations through draft workspace"
```

---

### Task 6: Move Recovery, Save, Copy, and Session Boundaries to Workspace Use Cases

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `scripts/pendingBoundaryGuard-test.mjs`
- Modify: `scripts/sessionScopedRequests-test.mjs`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`

- [ ] **Step 1: Update failing source contract tests**

Modify `scripts/sessionScopedRequests-test.mjs`:

```js
test('batch save uses draft workspace use case with explicit context', () => {
  const persist = body(annotationsSource, 'async function persistPendingFeedbackItems(options = {})');
  assert.match(persist, /ensureDraftCommandQueue\(\)\.enqueue/);
  assert.match(persist, /persistDraftWorkspace\(/);
  assert.match(persist, /draftWorkspace\.workspaceId/);
  assert.match(persist, /draftWorkspace\.revision/);
});
```

Modify `scripts/pendingBoundaryGuard-test.mjs`:

```js
test('shared pending boundary resolver delegates to draft boundary use case', () => {
  assert.match(mainSource, /async function resolvePendingBeforeBoundary\(action,\s*sessionId = null\)/);
  assert.match(mainSource, /resolveDraftBoundary\(/);
  assert.match(mainSource, /ensureDraftCommandQueue\(\)\.enqueue/);
});
```

- [ ] **Step 2: Run failing contract tests**

```bash
node --test scripts/sessionScopedRequests-test.mjs scripts/pendingBoundaryGuard-test.mjs
```

Expected: FAIL because save and boundary still use the old ad hoc paths.

- [ ] **Step 3: Persist current workspace mirror via storage adapter**

Add to `state.js` near compatibility helpers:

```js
function persistCurrentDraftWorkspaceIfNeeded() {
  if (!draftWorkspace?.workspaceId || !(draftWorkspace.items || []).length) return;
  const storage = createBrowserDraftPorts().storage;
  storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
}
```

Keep `persistCurrentPendingState()` temporarily, but change it to delegate:

```js
function persistCurrentPendingState() {
  persistCurrentDraftWorkspaceIfNeeded();
}
```

- [ ] **Step 4: Route `persistPendingFeedbackItems` through queue and use case**

Replace the network section of `persistPendingFeedbackItems(options = {})` in `annotations.js` with:

```js
if (!draftWorkspace?.workspaceId || !draftWorkspaceItems(draftWorkspace).length) {
  throw new Error('Add at least one pending feedback item.');
}
const queue = ensureDraftCommandQueue();
const meta = {
  kind: 'persist-draft-workspace',
  workspaceId: draftWorkspace.workspaceId,
  expectedRevision: draftWorkspace.revision,
};
const result = await queue.enqueue(meta, async (workspace) => {
  return await persistDraftWorkspace(workspace, createBrowserDraftPorts(), {
    allowBlankComments,
    onlyWrittenComments,
    allowFallbackComments,
    forceMismatchOverride,
  });
});
if (result?.result?.conflict?.error === 'screen_fingerprint_mismatch') {
  const conflict = result.result.conflict;
  const choice = await promptScreenFingerprintMismatch(conflict.frozenFingerprint, conflict.currentFingerprint);
  if (choice === 'force') {
    return await persistPendingFeedbackItems({ ...options, forceMismatchOverride: true });
  }
  if (choice === 'recapture') {
    setDraftWorkspace(createEmptyDraftWorkspace());
    state.preview = null;
    startLivePreviewPolling();
    return null;
  }
  return null;
}
if (result?.result?.session) {
  state.session = result.result.session;
  state.preview = null;
  return state.session;
}
await refreshSessions();
return state.session;
```

- [ ] **Step 5: Route copy and Save to MCP through explicit session workspace state**

In `prompt.js`, after `const itemIds = await persistAndCollectItemIds();`, capture the session id before any await that might refresh state:

```js
const sessionId = draftWorkspace?.context?.sessionId || state.session?.sessionId;
if (!sessionId) throw new Error('Feedback session context is missing. Re-capture and try again.');
```

Use `sessionId` in `fetchHandoffPreview`, `markItemsHandedOff`, and `/api/agent-handoffs` body.

- [ ] **Step 6: Route boundary resolver through use case**

Replace `resolvePendingBeforeBoundary(action, sessionId = null)` body in `main.js` with:

```js
const hasActivePending = Boolean(draftWorkspace?.workspaceId && draftWorkspaceItems(draftWorkspace).length);
if (!hasActivePending && !hasPendingRecoveryItems()) return 'continue';
if (hasPendingRecoveryItems() && !hasActivePending) {
  renderPendingRecoveryBanner();
  showError('Recover, recapture, or discard unsaved annotations before changing sessions.');
  return 'cancel';
}
const pendingSessionId = draftWorkspace?.context?.sessionId || null;
if (sessionId && pendingSessionId && sessionId !== pendingSessionId) {
  createBrowserDraftPorts().storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
  setDraftWorkspace(createEmptyDraftWorkspace());
  return 'continue';
}
const result = await ensureDraftCommandQueue().enqueue({
  kind: 'session-boundary',
  workspaceId: draftWorkspace.workspaceId,
  expectedRevision: draftWorkspace.revision,
}, async (workspace) => {
  return await resolveDraftBoundary(workspace, { kind: action, sessionId }, createBrowserDraftPorts());
});
return result?.result?.choice === 'cancel' ? 'cancel' : 'continue';
```

- [ ] **Step 7: Run tests**

```bash
node --test scripts/sessionScopedRequests-test.mjs scripts/pendingBoundaryGuard-test.mjs scripts/draftPresentationContract-test.mjs
npm run console:draft:test
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add scripts/sessionScopedRequests-test.mjs scripts/pendingBoundaryGuard-test.mjs fixthis-mcp/src/main/console/state.js fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/console/main.js fixthis-mcp/src/main/console/prompt.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): persist draft workspace through use cases"
```

---

### Task 7: Add Randomized Invariant Tests and Remove Direct Mutation Shims

**Files:**
- Create: `scripts/draftWorkflowInvariant-test.mjs`
- Modify: `scripts/draftPresentationContract-test.mjs`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`

- [ ] **Step 1: Write the randomized invariant test**

Create `scripts/draftWorkflowInvariant-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'draftWorkspace.js',
  'draftWorkspaceHistory.js',
  'draftPorts.js',
  'draftApiAdapter.js',
  'draftUseCases.js',
  'draftCommandQueue.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');
const factory = new Function(`${sources}; return {
  createEmptyDraftWorkspace,
  createFakeDraftPorts,
  startDraftFreeze,
  addDraftItem,
  updateDraftItem,
  deleteDraftItem,
  persistDraftWorkspace,
  draftWorkspaceItems
};`);
const m = factory();

function rng(seed) {
  let value = seed;
  return () => {
    value = (value * 48271) % 0x7fffffff;
    return value / 0x7fffffff;
  };
}

test('random draft workflow never crosses captured session context', async () => {
  const random = rng(42);
  const savedRequests = [];
  let currentSessionId = 'session-a';
  let nextId = 0;
  const ports = m.createFakeDraftPorts({
    ids: {
      nextWorkspaceId: () => 'ws-' + (++nextId),
      nextDraftItemId: () => 'draft-' + (++nextId),
    },
    preview: {
      capture: async () => ({ previewId: 'preview-' + nextId, screen: { screenId: 'screen-' + nextId, fingerprint: 'fp-' + nextId } }),
      screenshotUrl: (previewId, sessionId) => `/api/preview/${previewId}/screenshot/full?sessionId=${sessionId}`,
    },
    feedbackApi: {
      saveDraftWorkspace: async (request) => {
        savedRequests.push(request);
        return { sessionId: request.sessionId, items: request.items.map((_, index) => ({ itemId: `item-${index + 1}` })) };
      },
    },
  });
  let workspace = m.createEmptyDraftWorkspace();
  for (let i = 0; i < 100; i += 1) {
    const op = Math.floor(random() * 6);
    if (op === 0 || workspace.lifecycle === 'empty') {
      workspace = await m.startDraftFreeze(workspace, { sessionId: currentSessionId }, ports);
    } else if (op === 1) {
      workspace = m.addDraftItem(workspace, { targetType: 'area', bounds: { left: 1, top: 1, right: 10, bottom: 10 }, label: 'Area' }, ports);
    } else if (op === 2 && m.draftWorkspaceItems(workspace)[0]) {
      workspace = m.updateDraftItem(workspace, m.draftWorkspaceItems(workspace)[0].draftItemId, { comment: 'comment-' + i });
    } else if (op === 3 && m.draftWorkspaceItems(workspace)[0]) {
      workspace = m.deleteDraftItem(workspace, m.draftWorkspaceItems(workspace)[0].draftItemId);
    } else if (op === 4) {
      currentSessionId = currentSessionId === 'session-a' ? 'session-b' : 'session-a';
    } else if (op === 5 && m.draftWorkspaceItems(workspace).some((item) => String(item.comment || '').trim())) {
      const beforeSession = workspace.context.sessionId;
      const result = await m.persistDraftWorkspace(workspace, ports);
      assert.equal(savedRequests.at(-1).sessionId, beforeSession);
      workspace = result.workspace;
    }
    if (workspace.context?.sessionId) {
      assert.match(workspace.context.sessionId, /^session-/);
    }
    for (const request of savedRequests) {
      assert.ok(request.sessionId);
      assert.equal(request.sessionId, request.sessionId.trim());
    }
  }
});
```

- [ ] **Step 2: Strengthen presentation contract against direct mutation**

Append to `scripts/draftPresentationContract-test.mjs`:

```js
test('annotation presentation no longer mutates pending array directly', () => {
  assert.doesNotMatch(annotationsSource, /pendingFeedbackItems\.push\(/);
  assert.doesNotMatch(annotationsSource, /pendingFeedbackItems\.splice\(/);
  assert.doesNotMatch(annotationsSource, /pendingFeedbackItems\s*=\s*items/);
});
```

- [ ] **Step 3: Run failing tests**

```bash
node --test scripts/draftWorkflowInvariant-test.mjs scripts/draftPresentationContract-test.mjs
```

Expected: FAIL if any direct mutation remains.

- [ ] **Step 4: Remove remaining direct writes from presentation modules**

In `annotations.js`, replace any remaining `pendingFeedbackItems = []` after reset with:

```js
setDraftWorkspace(createEmptyDraftWorkspace());
```

Replace any remaining direct pending item assignments in recovery paths with reducer/use case restoration:

```js
setDraftWorkspace(recoverDraftWorkspaceFromEnvelope(recovery, createBrowserDraftPorts()));
```

Add `recoverDraftWorkspaceFromEnvelope` to `draftUseCases.js`:

```js
function recoverDraftWorkspaceFromEnvelope(envelope) {
  if (envelope?.schemaVersion !== 2) throw new Error('Draft recovery requires schema v2 workspace envelope.');
  return {
    ...createFrozenDraftWorkspace({
      workspaceId: envelope.workspaceId,
      context: envelope.context,
      screen: envelope.screen,
      screenshotUrl: envelope.screenshotUrl,
      history: envelope.history || { undoStack: [], redoStack: [] },
    }),
    revision: envelope.revision || 1,
    items: envelope.items || [],
  };
}
```

- [ ] **Step 5: Run all draft tests and existing console JS tests**

```bash
npm run console:draft:test
node --test scripts/draftWorkflowInvariant-test.mjs scripts/draftPresentationContract-test.mjs
node --test scripts/sessionScopedRequests-test.mjs scripts/pendingBoundaryGuard-test.mjs scripts/undoRedoContext-test.mjs scripts/savedOverlayScope-test.mjs scripts/pendingItemRecovery-test.mjs
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add package.json scripts/draftWorkflowInvariant-test.mjs scripts/draftPresentationContract-test.mjs fixthis-mcp/src/main/console/state.js fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/console/draftUseCases.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "test(console): cover draft workspace invariants"
```

---

### Task 8: End-to-End Verification and Documentation Update

**Files:**
- Modify: `scripts/console-browser-smoke.mjs`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/reference/output-schema.md`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`

- [ ] **Step 1: Add smoke assertion for stale save response**

Modify `fixthis-mcp/src/main/console/main.js` near console initialization to expose a read-only debug object for browser smoke tests:

```js
window.FixThisConsoleDebug = {
  getDraftWorkspace: () => draftWorkspace,
  getState: () => state,
};
```

Modify `scripts/console-browser-smoke.mjs` by adding a scenario named `draftWorkspaceSessionSwitchRace` with this assertion block after the scenario switches sessions while a draft command is in flight:

```js
await page.evaluate(() => {
  const debug = window.FixThisConsoleDebug;
  const draftWorkspace = debug.getDraftWorkspace();
  const state = debug.getState();
  window.__fixthisDraftRaceResult = {
    activeWorkspaceSession: draftWorkspace?.context?.sessionId || null,
    activeWorkspaceItems: draftWorkspace?.items?.length || 0,
    visibleSession: state?.session?.sessionId || null,
  };
});
const race = await page.evaluate(() => window.__fixthisDraftRaceResult);
assert.equal(race.activeWorkspaceSession, race.visibleSession);
assert.ok(race.activeWorkspaceItems >= 0);
```

- [ ] **Step 2: Update feedback console contract**

In `docs/reference/feedback-console-contract.md`, update the persistence section to say:

```markdown
- Browser-only pending work is stored as a schema-v2 DraftWorkspace envelope
  under `localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`, with a
  per-session index at `localStorage["fixthis.workspace.index.<sessionId>"]`.
  The envelope carries `workspaceId`, `revision`, `lifecycle`, immutable
  `context`, frozen `screen`, `screenshotUrl`, `items`, and `history`.
- Legacy schema-v1 `localStorage["fixthis.pending.<sessionId>"]` envelopes are
  migrated into schema-v2 recovery workspaces after explicit user choice.
```

- [ ] **Step 3: Update output schema**

In `docs/reference/output-schema.md`, replace the paragraph that describes `localStorage["fixthis.pending.<sessionId>"]` with:

```markdown
Connection loss does not change feedback delivery fields. Browser-only pending
items are mirrored separately as DraftWorkspace schema-v2 envelopes under
`localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`, with
`localStorage["fixthis.workspace.index.<sessionId>"]` storing the recoverable
workspace ids for that session. Each envelope carries `workspaceId`,
`revision`, `lifecycle`, immutable frozen `context` (`sessionId`, `previewId`,
`screenId`, `screenFingerprint`, `deviceSerial`, `frozenAtEpochMillis`, and
`activityName`), frozen `screen`, `screenshotUrl`, browser-local `items`, and
undo/redo `history`. The persisted MCP `FeedbackSession` JSON remains
unchanged; workspaces are only a browser recovery mirror until Copy Prompt or
Save to MCP persists items into `.fixthis/feedback-sessions/`.
```

- [ ] **Step 4: Run final verification**

```bash
npm run console:draft:test
node --test scripts/draftWorkflowInvariant-test.mjs scripts/draftPresentationContract-test.mjs
node --test scripts/sessionScopedRequests-test.mjs scripts/pendingBoundaryGuard-test.mjs scripts/undoRedoContext-test.mjs scripts/savedOverlayScope-test.mjs scripts/pendingItemRecovery-test.mjs scripts/console-availability-test.mjs scripts/beforeunloadGuard-test.mjs scripts/undoRedo-test.mjs scripts/undoKeymatch-test.mjs scripts/activityDrift-test.mjs scripts/previewStaleness-test.mjs
node scripts/build-console-assets.mjs --check
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*"
```

Expected: PASS for every command.

- [ ] **Step 5: Commit**

```bash
git add scripts/console-browser-smoke.mjs fixthis-mcp/src/main/console/main.js docs/reference/feedback-console-contract.md docs/reference/output-schema.md fixthis-mcp/src/main/resources/console/app.js
git commit -m "docs(console): document draft workspace recovery"
```

## Self-Review Checklist

- Spec coverage:
  - Clean Architecture boundaries: Tasks 1-4 create domain/use case/port/adapter/queue layers.
  - Explicit session mutations: Task 3 API adapter and Task 6 save/copy/handoff wiring.
  - Session boundary behavior: Task 6.
  - Recovery schema v2 and legacy migration: Task 3 and Task 6.
  - Rendering/presentation separation: Task 5 and Task 7.
  - Randomized invariant coverage: Task 7.
  - Documentation: Task 8.
- Placeholder scan: no task says to add unspecified tests or generic error handling; each test and code step includes concrete snippets.
- Type consistency:
  - `workspaceId`, `revision`, `context`, `draftItemId`, and `items` are used consistently across reducer, history, storage, API, use cases, queue, and tests.
  - `sessionId` and `previewId` are always read from `workspace.context` for annotation-owned server requests.
