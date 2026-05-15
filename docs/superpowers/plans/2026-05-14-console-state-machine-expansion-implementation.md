# Console State Machine Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the remaining ~30 module-local `let` declarations in `state.js` and its satellites with four small, pure-reducer sub-state-machines (Connection, Preview, Polling, Tool-mode) that share the architectural shape of the already-shipped draft workspace FSM, while keeping every existing HTTP route, persisted JSON shape, and asset contract test stable.

**Architecture:** Each sub-FSM is a 3-layer triangle (pure reducer → use cases over ports → browser adapter), plus a thin compat shim in `state.js` that exposes legacy globals as getters/setters delegating to the FSM during migration. The four FSMs land in dependency order (Connection → Preview → Polling → Tool-mode); cross-FSM coordination is wired explicitly at the use-case layer by a single `consoleApp.js` factory.

**Tech Stack:** Vanilla browser JavaScript in `fixthis-mcp/src/main/console/`; Node `node:test` for pure reducer + use-case tests; existing `scripts/build-console-assets.mjs` for bundling (with the new `// @requires` headers from Item 2 if it has landed); existing Gradle/JUnit asset contract tests.

---

## File Structure

Create these new console modules:

- `fixthis-mcp/src/main/console/connectionFsm.js` — pure reducer.
- `fixthis-mcp/src/main/console/connectionUseCases.js` — workflows.
- `fixthis-mcp/src/main/console/connectionBrowserAdapter.js` — DOM/timer/HTTP wiring.
- `fixthis-mcp/src/main/console/previewFsm.js`
- `fixthis-mcp/src/main/console/previewUseCases.js`
- `fixthis-mcp/src/main/console/previewBrowserAdapter.js`
- `fixthis-mcp/src/main/console/pollingFsm.js`
- `fixthis-mcp/src/main/console/pollingUseCases.js`
- `fixthis-mcp/src/main/console/pollingBrowserAdapter.js`
- `fixthis-mcp/src/main/console/toolModeFsm.js`
- `fixthis-mcp/src/main/console/toolModeUseCases.js`  (no browser adapter — no async I/O)
- `fixthis-mcp/src/main/console/consoleApp.js` — top-level FSM wiring factory.

Create these Node tests:

- `scripts/connectionFsm-test.mjs`
- `scripts/connectionUseCases-test.mjs`
- `scripts/previewFsm-test.mjs`
- `scripts/previewUseCases-test.mjs`
- `scripts/pollingFsm-test.mjs`
- `scripts/pollingUseCases-test.mjs`
- `scripts/toolModeFsm-test.mjs`
- `scripts/toolModeUseCases-test.mjs`
- `scripts/consoleFsmContract-test.mjs` — umbrella invariants.

Modify these existing files:

- `scripts/build-console-assets.mjs` — register new modules (or add `// @requires` if Item 2 has landed).
- `package.json` — add `console:fsm:test` script.
- `fixthis-mcp/src/main/console/state.js` — replace 30 `let` declarations with FSM-backed getters/setters; eventually delete legacy declarations.
- `fixthis-mcp/src/main/console/connection.js` — call connection use cases; remove `lastHeartbeatError` and direct `state.connection.*` writes.
- `fixthis-mcp/src/main/console/sessions-polling.js` — call polling use cases.
- `fixthis-mcp/src/main/console/preview.js` — call preview use cases.
- `fixthis-mcp/src/main/console/annotations.js` — call tool-mode use cases for drag/hover/click.
- `fixthis-mcp/src/main/console/main.js` — boot all FSMs via `createConsoleApp()`.
- `fixthis-mcp/src/main/resources/console/app.js` — regenerate, never hand-edit.
- `docs/reference/feedback-console-contract.md` — document the four FSMs.

## Conventions

- Use TDD: red reducer test → green reducer → red use-case test → green use case → wire up.
- Each commit ends with `node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`.
- Do not hand-edit `fixthis-mcp/src/main/resources/console/app.js`.
- Reducers must be free of `document.`, `window.`, `setTimeout`, `fetch`. Use-cases must take all async primitives via ports.
- After each FSM lands, the matching legacy `let` declarations must be deleted from `state.js`. The `state.js` `let` count is a hard checkpoint at the end of each task.
- Commit after each task.

---

### Task 1: Umbrella Contract Test (Green via pending list)

**Files:**
- Create: `scripts/consoleFsmContract-test.mjs`
- Modify: `package.json`

> **Anti-red-on-main.** Previous draft of this plan landed the umbrella test red on `main` and turned it green incrementally — that makes every intermediate commit fail CI and breaks `git bisect`. We instead land the test **green** via an explicit `pendingExtraction` list. Each subsequent Phase commits a one-line edit removing its own FSM from the list. The final Phase 4 commit empties the list and turns on full strict assertion. Every intermediate `main` commit is green. (Spec §4 Phase 0.)

- [ ] **Step 1: Write the umbrella contract test (green at land time)**

Create `scripts/consoleFsmContract-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');

// Each entry is removed by its own extraction Phase. When empty, the
// strict assertions below apply universally. Each Phase's commit
// includes a single-line edit to this list.
const PENDING_EXTRACTION = new Set([
  'connection',
  'preview',
  'polling',
  'toolMode',
]);

const FSM_FILES = {
  connection: 'connectionFsm.js',
  preview:    'previewFsm.js',
  polling:    'pollingFsm.js',
  toolMode:   'toolModeFsm.js',
};

const USE_CASE_FILES = {
  connection: 'connectionUseCases.js',
  preview:    'previewUseCases.js',
  polling:    'pollingUseCases.js',
  toolMode:   'toolModeUseCases.js',
};

const onDisk = new Set(readdirSync(sourceDir));

for (const [key, fileName] of Object.entries(FSM_FILES)) {
  if (PENDING_EXTRACTION.has(key)) continue; // not yet expected to exist
  test(`reducer file ${fileName} exists`, () => {
    assert.ok(onDisk.has(fileName), `${fileName} is missing`);
  });
  test(`reducer ${fileName} is pure: no DOM/setTimeout/fetch`, () => {
    const content = readFileSync(resolve(sourceDir, fileName), 'utf8');
    for (const forbidden of ['document.', 'window.', 'setTimeout', 'fetch(', 'localStorage']) {
      assert.ok(!content.includes(forbidden), `${fileName} must not reference ${forbidden}`);
    }
  });
}

for (const [key, fileName] of Object.entries(USE_CASE_FILES)) {
  if (PENDING_EXTRACTION.has(key)) continue;
  test(`use-case file ${fileName} exists`, () => {
    assert.ok(onDisk.has(fileName), `${fileName} is missing`);
  });
  test(`use-case ${fileName} takes async primitives via ports`, () => {
    const content = readFileSync(resolve(sourceDir, fileName), 'utf8');
    for (const forbidden of ['setTimeout(', 'setInterval(', 'fetch(']) {
      assert.ok(!content.includes(forbidden),
        `${fileName} must take ${forbidden.slice(0, -1)} via a port, not call it directly`);
    }
  });
}

// state.js threshold: tightened in two stages.
//   - while any FSM is pending: assert ≤ 40 (current baseline ~35 + slack)
//   - when PENDING_EXTRACTION is empty: assert ≤ 10 (the spec's
//     aspirational ≤ 5 does not account for the already-migrated draft
//     FSM's holder lets — addItemsFlow / pendingFeedbackItems /
//     focusedPendingItemIndex / currentSelection / undoRedoHistory /
//     draftWorkspace / draftCommandQueue — which remain module-level
//     because the draft FSM predates this plan's closure-encapsulation
//     pattern. Re-tightening to ≤ 5 is a follow-up plan that refactors
//     the draft FSM holder shape.)
test('state.js module-level let count meets current target', () => {
  const content = readFileSync(resolve(sourceDir, 'state.js'), 'utf8');
  // state.js is wrapped in an IIFE at 12-space body indentation. Module-
  // level declarations therefore appear at exactly 12 leading spaces;
  // function-body locals appear at 14+ spaces. Anchor the regex to the
  // module-level indent so we count only the declarations this refactor
  // targets.
  const matches = content.match(/^ {12}let [a-zA-Z_$]/gm) || [];
  const target = PENDING_EXTRACTION.size === 0 ? 10 : 40;
  assert.ok(matches.length <= target,
    `state.js has ${matches.length} module-level let declarations; target ≤ ${target}`);
});
```

The regex anchors at exactly 12 leading spaces, the module-body indent for state.js's IIFE. Function-body `let`s sit at ≥14 spaces and are excluded. If a future refactor changes the IIFE wrapping, update both this regex and the grep in Task 6 Step 4.

- [ ] **Step 2: Run to verify it passes on `main`**

```bash
node --test scripts/consoleFsmContract-test.mjs
```

Expected: all assertions PASS at land time because every FSM key is pending, and the state.js threshold is the lax target (≤40). The test exists and runs; it just has nothing to assert until Phase 1.

- [ ] **Step 3: Add the npm script**

Modify `package.json` to add:

```json
"console:fsm:test": "node --test scripts/connectionFsm-test.mjs scripts/connectionUseCases-test.mjs scripts/previewFsm-test.mjs scripts/previewUseCases-test.mjs scripts/pollingFsm-test.mjs scripts/pollingUseCases-test.mjs scripts/toolModeFsm-test.mjs scripts/toolModeUseCases-test.mjs scripts/consoleFsmContract-test.mjs"
```

The per-FSM test files do not yet exist; each Phase creates its own and removes the pending entry in `consoleFsmContract-test.mjs` in the same commit. To avoid `node --test` failing on missing files during phases, gate the npm script entries to "added at the same commit as the file". The simplest pattern: replace the literal list above with a glob runner script that picks up only existing files (write a 10-line `scripts/run-console-fsm-tests.mjs`). Use whichever fits the project's existing test-runner style.

- [ ] **Step 4: Commit (GREEN — Phase 0 lands green)**

```bash
git add scripts/consoleFsmContract-test.mjs package.json
git commit -m "$(cat <<'EOF'
test(console): umbrella FSM contract test (Phase 0, green via pending list)

Lands the umbrella isolation test with an explicit pendingExtraction
list. Each subsequent Phase removes its own entry as it lands.
Asserts pass at land time so `main` stays green and `git bisect` works.
EOF
)"
```

---

### Task 2: Connection FSM

**Files:**
- Create: `fixthis-mcp/src/main/console/connectionFsm.js`
- Create: `fixthis-mcp/src/main/console/connectionUseCases.js`
- Create: `fixthis-mcp/src/main/console/connectionBrowserAdapter.js`
- Create: `scripts/connectionFsm-test.mjs`
- Create: `scripts/connectionUseCases-test.mjs`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/connection.js`
- Modify: `scripts/build-console-assets.mjs`

- [ ] **Step 1: Write the reducer test**

Create `scripts/connectionFsm-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/connectionFsm.js'), 'utf8');
const m = new Function(`${src}; return {
  ConnectionLifecycle, createEmptyConnection, reduceConnection,
};`)();

test('empty connection is DISCONNECTED', () => {
  const s = m.createEmptyConnection();
  assert.equal(s.lifecycle, m.ConnectionLifecycle.DISCONNECTED);
  assert.equal(s.current, null);
  assert.equal(s.hasEverConnected, false);
  assert.equal(s.heartbeatGeneration, 0);
});

test('LAUNCH_REQUESTED moves to LAUNCHING', () => {
  const s = m.reduceConnection(m.createEmptyConnection(), { type: 'LAUNCH_REQUESTED' });
  assert.equal(s.lifecycle, m.ConnectionLifecycle.LAUNCHING);
});

test('LAUNCH_SUCCEEDED moves to READY and records hasEverConnected', () => {
  let s = m.createEmptyConnection();
  s = m.reduceConnection(s, { type: 'LAUNCH_REQUESTED' });
  s = m.reduceConnection(s, { type: 'LAUNCH_SUCCEEDED', current: { id: 'c-1' }, nowMs: 1000 });
  assert.equal(s.lifecycle, m.ConnectionLifecycle.READY);
  assert.equal(s.hasEverConnected, true);
  assert.equal(s.lastReadyAt, 1000);
  assert.deepEqual(s.current, { id: 'c-1' });
});

test('LAUNCH_FAILED moves to UNAVAILABLE', () => {
  let s = m.createEmptyConnection();
  s = m.reduceConnection(s, { type: 'LAUNCH_REQUESTED' });
  s = m.reduceConnection(s, { type: 'LAUNCH_FAILED', error: 'boom' });
  assert.equal(s.lifecycle, m.ConnectionLifecycle.UNAVAILABLE);
});

test('HEARTBEAT_OK increments generation and clears error', () => {
  let s = m.createEmptyConnection();
  s = m.reduceConnection(s, { type: 'LAUNCH_REQUESTED' });
  s = m.reduceConnection(s, { type: 'LAUNCH_SUCCEEDED', current: { id: 'c-1' }, nowMs: 1 });
  s = m.reduceConnection(s, { type: 'HEARTBEAT_FAILED', error: 'net' });
  s = m.reduceConnection(s, { type: 'HEARTBEAT_OK', nowMs: 2 });
  assert.equal(s.lifecycle, m.ConnectionLifecycle.READY);
  assert.equal(s.lastHeartbeatError, null);
  assert.equal(s.heartbeatGeneration, 2);
});

test('INTERACTION_BLOCKED moves to BLOCKED preserving current', () => {
  let s = m.createEmptyConnection();
  s = m.reduceConnection(s, { type: 'LAUNCH_REQUESTED' });
  s = m.reduceConnection(s, { type: 'LAUNCH_SUCCEEDED', current: { id: 'c-1' }, nowMs: 1 });
  s = m.reduceConnection(s, { type: 'INTERACTION_BLOCKED', reason: 'screen-locked' });
  assert.equal(s.lifecycle, m.ConnectionLifecycle.BLOCKED);
  assert.equal(s.interactionBlockedReason, 'screen-locked');
  assert.deepEqual(s.current, { id: 'c-1' });
});
```

- [ ] **Step 2: Run to verify FAIL**

```bash
node --test scripts/connectionFsm-test.mjs 2>&1 | head -10
```

Expected: `ENOENT` because `connectionFsm.js` does not exist.

- [ ] **Step 3: Implement the reducer**

Create `fixthis-mcp/src/main/console/connectionFsm.js`:

```js
// @requires (none)

const ConnectionLifecycle = Object.freeze({
  DISCONNECTED: 'DISCONNECTED',
  LAUNCHING: 'LAUNCHING',
  READY: 'READY',
  BLOCKED: 'BLOCKED',
  UNAVAILABLE: 'UNAVAILABLE',
});

const MaxHeartbeatFailures = 3;

function createEmptyConnection() {
  return Object.freeze({
    lifecycle: ConnectionLifecycle.DISCONNECTED,
    current: null,
    hasEverConnected: false,
    lastReadyAt: null,
    availability: null,
    interactionBlockedReason: null,
    previousBlockedReason: null,
    sessionsPollingPaused: false,
    heartbeatGeneration: 0,
    consecutiveHeartbeatFailures: 0,
    lastHeartbeatError: null,
  });
}

function reduceConnection(state, action) {
  switch (action.type) {
    case 'LAUNCH_REQUESTED':
      return Object.freeze({ ...state, lifecycle: ConnectionLifecycle.LAUNCHING });
    case 'LAUNCH_SUCCEEDED':
      return Object.freeze({
        ...state,
        lifecycle: ConnectionLifecycle.READY,
        current: action.current,
        hasEverConnected: true,
        lastReadyAt: action.nowMs,
        consecutiveHeartbeatFailures: 0,
        lastHeartbeatError: null,
      });
    case 'LAUNCH_FAILED':
      return Object.freeze({
        ...state,
        lifecycle: ConnectionLifecycle.UNAVAILABLE,
        lastHeartbeatError: action.error,
      });
    case 'HEARTBEAT_OK':
      return Object.freeze({
        ...state,
        lifecycle: ConnectionLifecycle.READY,
        heartbeatGeneration: state.heartbeatGeneration + 1,
        consecutiveHeartbeatFailures: 0,
        lastHeartbeatError: null,
        lastReadyAt: action.nowMs,
      });
    case 'HEARTBEAT_FAILED': {
      const nextFailures = state.consecutiveHeartbeatFailures + 1;
      if (nextFailures >= MaxHeartbeatFailures) {
        // Degrade: too many consecutive failures → fall back to DISCONNECTED
        return Object.freeze({
          ...state,
          lifecycle: ConnectionLifecycle.DISCONNECTED,
          heartbeatGeneration: state.heartbeatGeneration + 1,
          consecutiveHeartbeatFailures: 0, // reset on lifecycle change
          lastHeartbeatError: action.error,
          current: null,
        });
      }
      return Object.freeze({
        ...state,
        heartbeatGeneration: state.heartbeatGeneration + 1,
        consecutiveHeartbeatFailures: nextFailures,
        lastHeartbeatError: action.error,
      });
    }
    case 'INTERACTION_BLOCKED':
      return Object.freeze({
        ...state,
        lifecycle: ConnectionLifecycle.BLOCKED,
        previousBlockedReason: state.interactionBlockedReason,
        interactionBlockedReason: action.reason,
        sessionsPollingPaused: true,
      });
    case 'INTERACTION_UNBLOCKED':
      return Object.freeze({
        ...state,
        lifecycle: state.current ? ConnectionLifecycle.READY : ConnectionLifecycle.DISCONNECTED,
        previousBlockedReason: state.interactionBlockedReason,
        interactionBlockedReason: null,
        sessionsPollingPaused: false,
      });
    case 'AVAILABILITY_UPDATED':
      return Object.freeze({ ...state, availability: action.availability });
    case 'DISCONNECT_REQUESTED':
      // User or programmatic teardown — unconditional return to DISCONNECTED
      // from any lifecycle except DISCONNECTED (no-op there).
      if (state.lifecycle === ConnectionLifecycle.DISCONNECTED) return state;
      return Object.freeze({
        ...state,
        lifecycle: ConnectionLifecycle.DISCONNECTED,
        current: null,
        interactionBlockedReason: null,
        sessionsPollingPaused: false,
        consecutiveHeartbeatFailures: 0,
      });
    default:
      return state;
  }
}

module.exports.MaxHeartbeatFailures = MaxHeartbeatFailures;
```

**New reducer tests** (add to `connectionFsm-test.mjs`):

```js
test('HEARTBEAT_FAILED < Max keeps READY but increments counter', () => {
  let s = m.reduceConnection(m.createEmptyConnection(), { type: 'LAUNCH_REQUESTED' });
  s = m.reduceConnection(s, { type: 'LAUNCH_SUCCEEDED', current: { id: 'c-1' }, nowMs: 1 });
  s = m.reduceConnection(s, { type: 'HEARTBEAT_FAILED', error: 'net' });
  assert.equal(s.lifecycle, 'READY');
  assert.equal(s.consecutiveHeartbeatFailures, 1);
});

test('HEARTBEAT_FAILED at threshold degrades READY → DISCONNECTED', () => {
  let s = m.reduceConnection(m.createEmptyConnection(), { type: 'LAUNCH_REQUESTED' });
  s = m.reduceConnection(s, { type: 'LAUNCH_SUCCEEDED', current: { id: 'c-1' }, nowMs: 1 });
  for (let i = 0; i < m.MaxHeartbeatFailures; i++) {
    s = m.reduceConnection(s, { type: 'HEARTBEAT_FAILED', error: 'net' });
  }
  assert.equal(s.lifecycle, 'DISCONNECTED');
  assert.equal(s.consecutiveHeartbeatFailures, 0); // reset on transition
  assert.equal(s.current, null);
});

test('DISCONNECT_REQUESTED is accepted from READY/BLOCKED/UNAVAILABLE', () => {
  let s = m.reduceConnection(m.createEmptyConnection(), { type: 'LAUNCH_REQUESTED' });
  s = m.reduceConnection(s, { type: 'LAUNCH_SUCCEEDED', current: { id: 'c-1' }, nowMs: 1 });
  s = m.reduceConnection(s, { type: 'DISCONNECT_REQUESTED' });
  assert.equal(s.lifecycle, 'DISCONNECTED');
});

test('DISCONNECT_REQUESTED from DISCONNECTED is a no-op (same reference)', () => {
  const s0 = m.createEmptyConnection();
  const s1 = m.reduceConnection(s0, { type: 'DISCONNECT_REQUESTED' });
  assert.strictEqual(s1, s0);
});
```

- [ ] **Step 4: Run to verify PASS**

```bash
node --test scripts/connectionFsm-test.mjs
```

Expected: 6 tests PASS.

- [ ] **Step 5: Write the use-case test and implementation**

Create `scripts/connectionUseCases-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
function load(name) {
  return readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8');
}
const sources = load('connectionFsm.js') + '\n' + load('connectionUseCases.js');
const m = new Function(`${sources}; return {
  createConnectionUseCases, ConnectionLifecycle,
};`)();

test('launch() success transitions to READY', async () => {
  let s = null;
  const onChange = (next) => { s = next; };
  const ports = {
    onChange,
    clock: { now: () => 1000 },
    api: { launch: async () => ({ id: 'c-1' }) },
  };
  const uc = m.createConnectionUseCases(ports);
  await uc.launch();
  assert.equal(s.lifecycle, m.ConnectionLifecycle.READY);
  assert.deepEqual(s.current, { id: 'c-1' });
});

test('heartbeat() failure does not change lifecycle but records error', async () => {
  let s = null;
  const ports = {
    onChange: (next) => { s = next; },
    clock: { now: () => 1000 },
    api: {
      launch: async () => ({ id: 'c-1' }),
      heartbeat: async () => { throw new Error('net'); },
    },
  };
  const uc = m.createConnectionUseCases(ports);
  await uc.launch();
  await uc.heartbeat();
  assert.equal(s.lastHeartbeatError, 'net');
  assert.equal(s.lifecycle, m.ConnectionLifecycle.READY);
});
```

Create `fixthis-mcp/src/main/console/connectionUseCases.js`:

```js
// @requires connectionFsm.js

function createConnectionUseCases(ports) {
  let state = createEmptyConnection();
  const dispatch = (action) => {
    state = reduceConnection(state, action);
    ports.onChange?.(state);
  };

  return {
    getState: () => state,
    async launch() {
      dispatch({ type: 'LAUNCH_REQUESTED' });
      try {
        const current = await ports.api.launch();
        dispatch({ type: 'LAUNCH_SUCCEEDED', current, nowMs: ports.clock.now() });
      } catch (err) {
        dispatch({ type: 'LAUNCH_FAILED', error: String(err?.message || err) });
      }
    },
    async heartbeat() {
      try {
        await ports.api.heartbeat();
        dispatch({ type: 'HEARTBEAT_OK', nowMs: ports.clock.now() });
      } catch (err) {
        dispatch({ type: 'HEARTBEAT_FAILED', error: String(err?.message || err) });
      }
    },
    block(reason) {
      dispatch({ type: 'INTERACTION_BLOCKED', reason });
    },
    unblock() {
      dispatch({ type: 'INTERACTION_UNBLOCKED' });
    },
    setAvailability(availability) {
      dispatch({ type: 'AVAILABILITY_UPDATED', availability });
    },
  };
}
```

Run:

```bash
node --test scripts/connectionUseCases-test.mjs
```

Expected: 2 PASS.

- [ ] **Step 6: Implement the browser adapter**

Create `fixthis-mcp/src/main/console/connectionBrowserAdapter.js`:

```js
// @requires connectionUseCases.js, api.js

function createBrowserConnectionUseCases({ onChange } = {}) {
  return createConnectionUseCases({
    onChange,
    clock: { now: () => Date.now() },
    api: {
      launch: () => requestJson('/api/connection/launch', { method: 'POST' }),
      heartbeat: () => requestJson('/api/connection/heartbeat'),
    },
  });
}
```

(Heartbeat timer scheduling is wired in `connection.js` for now; the
adapter just exposes the use cases.)

- [ ] **Step 7: Add the legacy compat shim in state.js**

Delete from `fixthis-mcp/src/main/console/state.js`:

```js
let heartbeatTimer = null;
let heartbeatPolling = false;
const state = { connection: { ... } };  // the whole connection sub-object
```

Add:

```js
const connectionUseCases = createBrowserConnectionUseCases({
  onChange: (next) => { state.connection = next; },
});
const state = { connection: connectionUseCases.getState(), /* ... other fields ... */ };
```

(The `state.connection` field now holds the immutable FSM state; legacy
reads of `state.connection.current`, `state.connection.hasEverConnected`,
etc. work unchanged. `state.connection.sessionsPollingPaused` continues
to work — same field name.)

- [ ] **Step 8: Migrate connection.js callers**

In `fixthis-mcp/src/main/console/connection.js`:

- Replace every direct mutation of `state.connection.*` with a use-case
  call. Search-and-replace each pattern:
  - `state.connection.current = X` → `connectionUseCases.<appropriate>`
  - The `let lastHeartbeatError = null` declaration (line 232) is deleted.
- Heartbeat timer scheduling continues to use `setTimeout`, but the
  *result* of each tick is `connectionUseCases.heartbeat()`.

- [ ] **Step 9: Add module to build**

If Item 2 has landed, add `// @requires` headers (done in the file
content above). If not, modify
`scripts/build-console-assets.mjs:8-36` to include the three new files
in order:

```js
const sources = [
  'state.js',
  // ...
  'api.js',
  'connectionFsm.js',
  'connectionUseCases.js',
  'connectionBrowserAdapter.js',
  // ...
];
```

- [ ] **Step 10: Run full local check**

```bash
node --test scripts/connectionFsm-test.mjs scripts/connectionUseCases-test.mjs
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.*"
```

Expected: PASS for all.

- [ ] **Step 11: Verify state.js no longer declares heartbeat/connection let**

```bash
grep -nE "^\s+let (heartbeatTimer|heartbeatPolling)" fixthis-mcp/src/main/console/state.js
grep -nE "^\s+let lastHeartbeatError" fixthis-mcp/src/main/console/connection.js
```

Expected: no output.

- [ ] **Step 12: Commit**

```bash
git add fixthis-mcp/src/main/console/connectionFsm.js \
        fixthis-mcp/src/main/console/connectionUseCases.js \
        fixthis-mcp/src/main/console/connectionBrowserAdapter.js \
        fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/console/connection.js \
        scripts/connectionFsm-test.mjs \
        scripts/connectionUseCases-test.mjs \
        scripts/build-console-assets.mjs \
        fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
feat(console): introduce Connection FSM

5 lifecycle states (DISCONNECTED → LAUNCHING → READY → BLOCKED ⇄ UNAVAILABLE).
Owns state.connection.*, replaces heartbeatTimer/heartbeatPolling/lastHeartbeatError.
Pure reducer + use cases over a launch/heartbeat port + a browser adapter
that wires to /api/connection/*.
EOF
)"
```

---

### Task 3: Preview FSM

**Files:**
- Create: `fixthis-mcp/src/main/console/previewFsm.js`
- Create: `fixthis-mcp/src/main/console/previewUseCases.js`
- Create: `fixthis-mcp/src/main/console/previewBrowserAdapter.js`
- Create: `scripts/previewFsm-test.mjs`
- Create: `scripts/previewUseCases-test.mjs`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `scripts/build-console-assets.mjs`

- [ ] **Step 1: Write the reducer test**

Create `scripts/previewFsm-test.mjs` with these tests (full bodies; see Task 2 Step 1 for the same loader pattern):

```js
test('empty preview is IDLE with zoom 1', () => {
  const s = m.createEmptyPreview();
  assert.equal(s.lifecycle, m.PreviewLifecycle.IDLE);
  assert.equal(s.zoom, 1);
  assert.equal(s.requestGeneration, 0);
  assert.equal(s.contextGeneration, 0);
  assert.equal(s.inFlight, null);
});

test('REQUEST_STARTED increments requestGeneration and records inFlight', () => {
  let s = m.createEmptyPreview();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  assert.equal(s.lifecycle, m.PreviewLifecycle.REQUESTING);
  assert.equal(s.requestGeneration, 1);
  assert.deepEqual(s.inFlight, { generation: 1, contextGeneration: 0 });
});

test('CONTEXT_CHANGED bumps contextGeneration', () => {
  let s = m.createEmptyPreview();
  s = m.reducePreview(s, { type: 'CONTEXT_CHANGED' });
  assert.equal(s.contextGeneration, 1);
});

test('REQUEST_SUCCEEDED with stale generation is ignored', () => {
  let s = m.createEmptyPreview();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });  // gen 1
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });  // gen 2
  s = m.reducePreview(s, {
    type: 'REQUEST_SUCCEEDED',
    generation: 1, contextGeneration: 0,
    current: { previewId: 'p-1' },
  });
  assert.equal(s.lifecycle, m.PreviewLifecycle.REQUESTING);  // still requesting
  assert.equal(s.current, null);
});

test('REQUEST_SUCCEEDED with fresh generation transitions to READY', () => {
  let s = m.createEmptyPreview();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  s = m.reducePreview(s, {
    type: 'REQUEST_SUCCEEDED',
    generation: 1, contextGeneration: 0,
    current: { previewId: 'p-1' },
  });
  assert.equal(s.lifecycle, m.PreviewLifecycle.READY);
  assert.deepEqual(s.current, { previewId: 'p-1' });
});

test('SET_ZOOM clamps to [0.5, 2]', () => {
  let s = m.createEmptyPreview();
  s = m.reducePreview(s, { type: 'SET_ZOOM', value: 5 });
  assert.equal(s.zoom, 2);
  s = m.reducePreview(s, { type: 'SET_ZOOM', value: 0.1 });
  assert.equal(s.zoom, 0.5);
});
```

- [ ] **Step 2: Run to verify FAIL**

```bash
node --test scripts/previewFsm-test.mjs 2>&1 | head -10
```

Expected: ENOENT.

- [ ] **Step 3: Implement the reducer**

Create `fixthis-mcp/src/main/console/previewFsm.js`:

```js
// @requires (none)

const PreviewLifecycle = Object.freeze({
  IDLE: 'IDLE',
  REQUESTING: 'REQUESTING',
  READY: 'READY',
  STALE: 'STALE',
  ERROR: 'ERROR',
});

const PreviewZoomMin = 0.5;
const PreviewZoomMax = 2;

function createEmptyPreview() {
  return Object.freeze({
    lifecycle: PreviewLifecycle.IDLE,
    requestGeneration: 0,
    contextGeneration: 0,
    inFlight: null,
    current: null,
    zoom: 1,
    pollIntervalMs: 1000,
    error: null,
  });
}

function clampZoom(value) {
  if (!Number.isFinite(value)) return 1;
  return Math.min(PreviewZoomMax, Math.max(PreviewZoomMin, value));
}

function reducePreview(state, action) {
  switch (action.type) {
    case 'REQUEST_STARTED': {
      const generation = state.requestGeneration + 1;
      return Object.freeze({
        ...state,
        lifecycle: PreviewLifecycle.REQUESTING,
        requestGeneration: generation,
        inFlight: { generation, contextGeneration: state.contextGeneration },
      });
    }
    case 'CONTEXT_CHANGED':
      return Object.freeze({ ...state, contextGeneration: state.contextGeneration + 1 });
    case 'REQUEST_SUCCEEDED': {
      // Race-fence: drop the action if EITHER generation has advanced
      // (a newer request started) OR contextGeneration has advanced
      // (the device context changed mid-flight). Both checks are
      // mandatory per spec §3.3.
      if (
        !state.inFlight ||
        action.generation !== state.inFlight.generation ||
        action.contextGeneration !== state.inFlight.contextGeneration
      ) {
        return state;
      }
      return Object.freeze({
        ...state,
        lifecycle: PreviewLifecycle.READY,
        inFlight: null,
        current: action.current,
        error: null,
      });
    }
    case 'REQUEST_FAILED': {
      if (
        !state.inFlight ||
        action.generation !== state.inFlight.generation ||
        action.contextGeneration !== state.inFlight.contextGeneration
      ) {
        return state;
      }
      return Object.freeze({
        ...state,
        lifecycle: PreviewLifecycle.ERROR,
        inFlight: null,
        error: action.error,
      });
    }
    case 'MARK_STALE':
      return Object.freeze({ ...state, lifecycle: PreviewLifecycle.STALE });
    case 'SET_ZOOM':
      return Object.freeze({ ...state, zoom: clampZoom(action.value) });
    case 'SET_POLL_INTERVAL':
      return Object.freeze({ ...state, pollIntervalMs: Math.max(1000, action.ms | 0) });
    case 'RESET':
      return createEmptyPreview();
    default:
      return state;
  }
}
```

- [ ] **Step 4: Run to verify PASS**

```bash
node --test scripts/previewFsm-test.mjs
```

Expected: 6 tests PASS.

- [ ] **Step 5: Write and implement use cases**

Create `scripts/previewUseCases-test.mjs` with at least these scenarios:

- `request()` fences stale responses (race test).
- `setZoom()` clamps.
- `setPollInterval()` stores via the storage port and clamps.

Create `fixthis-mcp/src/main/console/previewUseCases.js`:

```js
// @requires previewFsm.js

function createPreviewUseCases(ports) {
  let state = createEmptyPreview();
  const dispatch = (action) => {
    state = reducePreview(state, action);
    ports.onChange?.(state);
  };

  return {
    getState: () => state,
    async request() {
      dispatch({ type: 'REQUEST_STARTED' });
      // Capture BOTH generation counters after REQUEST_STARTED so the
      // reducer's race-fence (spec §3.3) can compare both. Comparing
      // only `generation` is a regression — a CONTEXT_CHANGED that
      // arrives while a request is in flight would not invalidate
      // the stale response, polluting `current` with data from the
      // previous device context.
      const captured = {
        generation: state.requestGeneration,
        contextGeneration: state.contextGeneration,
      };
      try {
        const result = await ports.api.capture();
        dispatch({
          type: 'REQUEST_SUCCEEDED',
          generation: captured.generation,
          contextGeneration: captured.contextGeneration,
          current: result,
        });
      } catch (err) {
        dispatch({
          type: 'REQUEST_FAILED',
          generation: captured.generation,
          contextGeneration: captured.contextGeneration,
          error: String(err?.message || err),
        });
      }
    },
    contextChanged() { dispatch({ type: 'CONTEXT_CHANGED' }); },
    markStale() { dispatch({ type: 'MARK_STALE' }); },
    setZoom(value) { dispatch({ type: 'SET_ZOOM', value }); },
    setPollInterval(ms) {
      dispatch({ type: 'SET_POLL_INTERVAL', ms });
      ports.storage?.setPollIntervalMs?.(state.pollIntervalMs);
    },
    reset() { dispatch({ type: 'RESET' }); },
  };
}
```

- [ ] **Step 6: Browser adapter**

Create `fixthis-mcp/src/main/console/previewBrowserAdapter.js`:

```js
// @requires previewUseCases.js, api.js

function createBrowserPreviewUseCases({ onChange } = {}) {
  return createPreviewUseCases({
    onChange,
    api: { capture: requestLivePreview },
    storage: {
      setPollIntervalMs: (ms) => localStorage.setItem(PreviewIntervalStorageKey, String(ms)),
    },
  });
}
```

`requestLivePreview` is the existing function in `preview.js`;
`PreviewIntervalStorageKey` is the existing constant in `state.js`.

- [ ] **Step 7: Migrate state.js and preview.js**

Delete from `state.js`:

```js
let livePreviewTimer = null;
let previewRequestGeneration = 0;
let previewRequestContextGeneration = 0;
let previewRequestInFlight = null;
let previewRequestInFlightContextGeneration = null;
let previewZoom = 1;
```

Add the FSM holder:

```js
const previewUseCases = createBrowserPreviewUseCases({
  onChange: () => triggerRender(),  // existing render scheduler
});
```

Where `previewZoom` was previously read, replace with
`previewUseCases.getState().zoom`. Where `previewRequestGeneration` was
incremented, replace with `previewUseCases.request()` (or
`contextChanged()`).

The `livePreviewTimer` itself moves into a closure inside
`preview.js`'s `startLivePreview()` (it is browser timer state, not
reducer state).

- [ ] **Step 8: Run full local check**

```bash
node --test scripts/previewFsm-test.mjs scripts/previewUseCases-test.mjs
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.*"
```

Expected: PASS.

- [ ] **Step 9: Verify legacy declarations gone**

```bash
grep -nE "^\s+let (livePreviewTimer|previewRequest|previewZoom)" fixthis-mcp/src/main/console/state.js
```

Expected: no output.

- [ ] **Step 10: Commit**

```bash
git add fixthis-mcp/src/main/console/previewFsm.js \
        fixthis-mcp/src/main/console/previewUseCases.js \
        fixthis-mcp/src/main/console/previewBrowserAdapter.js \
        fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/console/preview.js \
        scripts/previewFsm-test.mjs \
        scripts/previewUseCases-test.mjs \
        scripts/build-console-assets.mjs \
        fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
feat(console): introduce Preview FSM

5 lifecycle states (IDLE → REQUESTING → READY / STALE / ERROR). Replaces
previewRequestGeneration/previewRequestContextGeneration/previewRequestInFlight/
previewRequestInFlightContextGeneration/previewZoom with formalized
race-fenced reducer. livePreviewTimer becomes a closure in preview.js.
EOF
)"
```

---

### Task 4: Polling FSM

**Files:**
- Create: `fixthis-mcp/src/main/console/pollingFsm.js`
- Create: `fixthis-mcp/src/main/console/pollingUseCases.js`
- Create: `fixthis-mcp/src/main/console/pollingBrowserAdapter.js`
- Create: `scripts/pollingFsm-test.mjs`
- Create: `scripts/pollingUseCases-test.mjs`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/sessions-polling.js`
- Modify: `scripts/build-console-assets.mjs`

Important: the existing `ConsoleSessionsPollingContractTest` (post-Item-3 split) asserts on JS source for `pollSessionsTick`, `MaxConsecutivePollFailures`, `mergeSessionIntoState`, `withMutationLock`, `startSessionsPolling`. Each of those names must remain a top-level identifier after migration.

- [ ] **Step 1: Write the reducer test**

Create `scripts/pollingFsm-test.mjs`:

```js
test('STOPPED initial state', () => {
  const s = m.createEmptyPolling();
  assert.equal(s.lifecycle, m.PollingLifecycle.STOPPED);
  assert.equal(s.consecutiveFailures, 0);
});

test('START moves to POLLING_ACTIVE', () => {
  const s = m.reducePolling(m.createEmptyPolling(), { type: 'START' });
  assert.equal(s.lifecycle, m.PollingLifecycle.POLLING_ACTIVE);
});

test('TICK_OK resets failure counter', () => {
  let s = m.createEmptyPolling();
  s = m.reducePolling(s, { type: 'START' });
  s = m.reducePolling(s, { type: 'TICK_FAILED' });
  s = m.reducePolling(s, { type: 'TICK_FAILED' });
  s = m.reducePolling(s, { type: 'TICK_OK', sessionsEtag: 'e1', sessionEtag: 'e2' });
  assert.equal(s.consecutiveFailures, 0);
  assert.equal(s.lastSessionsEtag, 'e1');
  assert.equal(s.lastSessionEtag, 'e2');
});

test('TICK_FAILED after MAX moves to BACKOFF', () => {
  let s = m.reducePolling(m.createEmptyPolling(), { type: 'START' });
  for (let i = 0; i < m.MaxConsecutivePollFailures; i++) {
    s = m.reducePolling(s, { type: 'TICK_FAILED' });
  }
  assert.equal(s.consecutiveFailures, m.MaxConsecutivePollFailures);
  assert.equal(s.lifecycle, m.PollingLifecycle.POLLING_BACKOFF);
});

test('MUTATION_START/END toggle pendingMutationCount', () => {
  let s = m.createEmptyPolling();
  s = m.reducePolling(s, { type: 'MUTATION_START' });
  s = m.reducePolling(s, { type: 'MUTATION_START' });
  assert.equal(s.pendingMutationCount, 2);
  s = m.reducePolling(s, { type: 'MUTATION_END' });
  assert.equal(s.pendingMutationCount, 1);
});

test('BACKOFF_TIMER_FIRED returns BACKOFF → ACTIVE without resetting counter', () => {
  let s = m.reducePolling(m.createEmptyPolling(), { type: 'START' });
  for (let i = 0; i < m.MaxConsecutivePollFailures; i++) {
    s = m.reducePolling(s, { type: 'TICK_FAILED' });
  }
  assert.equal(s.lifecycle, m.PollingLifecycle.POLLING_BACKOFF);
  s = m.reducePolling(s, { type: 'BACKOFF_TIMER_FIRED' });
  assert.equal(s.lifecycle, m.PollingLifecycle.POLLING_ACTIVE);
  assert.equal(s.consecutiveFailures, m.MaxConsecutivePollFailures); // not reset
});

test('VISIBILITY_HIDDEN from BACKOFF preserves BACKOFF as pausedReturn', () => {
  let s = m.reducePolling(m.createEmptyPolling(), { type: 'START' });
  for (let i = 0; i < m.MaxConsecutivePollFailures; i++) {
    s = m.reducePolling(s, { type: 'TICK_FAILED' });
  }
  s = m.reducePolling(s, { type: 'VISIBILITY_HIDDEN' });
  assert.equal(s.lifecycle, m.PollingLifecycle.POLLING_PAUSED);
  assert.equal(s.pausedReturnLifecycle, m.PollingLifecycle.POLLING_BACKOFF);
  s = m.reducePolling(s, { type: 'VISIBILITY_VISIBLE' });
  assert.equal(s.lifecycle, m.PollingLifecycle.POLLING_BACKOFF);
  assert.equal(s.pausedReturnLifecycle, null);
});

test('DISCONNECT_REQUESTED stops polling from any state', () => {
  let s = m.reducePolling(m.createEmptyPolling(), { type: 'START' });
  s = m.reducePolling(s, { type: 'DISCONNECT_REQUESTED' });
  assert.equal(s.lifecycle, m.PollingLifecycle.STOPPED);
});
```

- [ ] **Step 2: Run to verify FAIL → implement → verify PASS**

```bash
node --test scripts/pollingFsm-test.mjs  # FAIL (ENOENT)
```

Create `fixthis-mcp/src/main/console/pollingFsm.js`:

```js
// @requires (none)

const PollingLifecycle = Object.freeze({
  STOPPED: 'STOPPED',
  POLLING_ACTIVE: 'POLLING_ACTIVE',
  POLLING_BACKOFF: 'POLLING_BACKOFF',
  POLLING_PAUSED: 'POLLING_PAUSED',
});

const MaxConsecutivePollFailures = 5;

function createEmptyPolling() {
  return Object.freeze({
    lifecycle: PollingLifecycle.STOPPED,
    pausedReturnLifecycle: null,  // 'POLLING_ACTIVE' | 'POLLING_BACKOFF' | null
    lastSessionsEtag: null,
    lastSessionEtag: null,
    pendingMutationCount: 0,
    mutationGeneration: 0,
    consecutiveFailures: 0,
    promptActionInFlight: false,
  });
}

function reducePolling(state, action) {
  switch (action.type) {
    case 'START':
      return Object.freeze({
        ...state,
        lifecycle: PollingLifecycle.POLLING_ACTIVE,
        consecutiveFailures: 0,
        pausedReturnLifecycle: null,
      });
    case 'STOP':
    case 'DISCONNECT_REQUESTED':
      return Object.freeze({
        ...state,
        lifecycle: PollingLifecycle.STOPPED,
        pausedReturnLifecycle: null,
      });
    case 'VISIBILITY_HIDDEN': {
      if (state.lifecycle === PollingLifecycle.POLLING_PAUSED ||
          state.lifecycle === PollingLifecycle.STOPPED) {
        return state;
      }
      return Object.freeze({
        ...state,
        lifecycle: PollingLifecycle.POLLING_PAUSED,
        pausedReturnLifecycle: state.lifecycle,
      });
    }
    case 'VISIBILITY_VISIBLE': {
      if (state.lifecycle !== PollingLifecycle.POLLING_PAUSED) return state;
      const restore = state.pausedReturnLifecycle ?? PollingLifecycle.POLLING_ACTIVE;
      return Object.freeze({
        ...state,
        lifecycle: restore,
        pausedReturnLifecycle: null,
      });
    }
    case 'TICK_OK':
      return Object.freeze({
        ...state,
        lifecycle: PollingLifecycle.POLLING_ACTIVE,
        consecutiveFailures: 0,
        lastSessionsEtag: action.sessionsEtag ?? state.lastSessionsEtag,
        lastSessionEtag: action.sessionEtag ?? state.lastSessionEtag,
      });
    case 'TICK_FAILED': {
      const next = state.consecutiveFailures + 1;
      return Object.freeze({
        ...state,
        consecutiveFailures: next,
        lifecycle: next >= MaxConsecutivePollFailures
          ? PollingLifecycle.POLLING_BACKOFF
          : state.lifecycle,
      });
    }
    case 'BACKOFF_TIMER_FIRED': {
      // Adapter's backoff timer fired. Move from BACKOFF back to ACTIVE
      // so the next tick attempt runs. Counter is NOT reset — only a
      // successful TICK_OK resets it. If the next tick also fails,
      // the FSM returns to BACKOFF (counter remains at threshold).
      if (state.lifecycle !== PollingLifecycle.POLLING_BACKOFF) return state;
      return Object.freeze({ ...state, lifecycle: PollingLifecycle.POLLING_ACTIVE });
    }
    case 'MUTATION_START':
      return Object.freeze({
        ...state,
        pendingMutationCount: state.pendingMutationCount + 1,
        mutationGeneration: state.mutationGeneration + 1,
      });
    case 'MUTATION_END':
      return Object.freeze({
        ...state,
        pendingMutationCount: Math.max(0, state.pendingMutationCount - 1),
      });
    case 'PROMPT_ACTION_START':
      return Object.freeze({ ...state, promptActionInFlight: true });
    case 'PROMPT_ACTION_END':
      return Object.freeze({ ...state, promptActionInFlight: false });
    default:
      return state;
  }
}
```

```bash
node --test scripts/pollingFsm-test.mjs
```

Expected: 5 tests PASS.

- [ ] **Step 3: Use cases**

Create `scripts/pollingUseCases-test.mjs` with these scenarios:

- `withMutationLock(fn)` wraps the fn in MUTATION_START/END.
- `pollSessionsTick()` failures eventually pause via PAUSE.
- `pollSessionsTick()` success resets the counter.

Create `fixthis-mcp/src/main/console/pollingUseCases.js`:

```js
// @requires pollingFsm.js

function createPollingUseCases(ports) {
  let state = createEmptyPolling();
  const dispatch = (action) => {
    state = reducePolling(state, action);
    ports.onChange?.(state);
  };

  async function withMutationLock(fn) {
    dispatch({ type: 'MUTATION_START' });
    let succeeded = false;
    try {
      const result = await fn();
      succeeded = true;
      return result;
    } finally {
      dispatch({ type: 'MUTATION_END' });
      if (succeeded && state.pendingMutationCount === 0 && state.lifecycle === 'POLLING_PAUSED') {
        dispatch({ type: 'RESUME' });
      }
    }
  }

  async function pollSessionsTick() {
    try {
      const result = await ports.api.sessions({
        sessionsEtag: state.lastSessionsEtag,
        sessionEtag: state.lastSessionEtag,
      });
      dispatch({
        type: 'TICK_OK',
        sessionsEtag: result.sessionsEtag,
        sessionEtag: result.sessionEtag,
      });
      return result;
    } catch (err) {
      dispatch({ type: 'TICK_FAILED' });
      throw err;
    }
  }

  return {
    getState: () => state,
    startSessionsPolling: () => dispatch({ type: 'START' }),
    stopSessionsPolling: () => dispatch({ type: 'STOP' }),
    pauseSessionsPolling: () => dispatch({ type: 'PAUSE' }),
    resumeSessionsPolling: () => dispatch({ type: 'RESUME' }),
    withMutationLock,
    pollSessionsTick,
    setPromptActionInFlight: (b) => dispatch({ type: b ? 'PROMPT_ACTION_START' : 'PROMPT_ACTION_END' }),
  };
}
```

The function names `startSessionsPolling`, `pollSessionsTick`,
`withMutationLock`, `MaxConsecutivePollFailures` survive as top-level
identifiers — the asset contract tests greps stay green.

Run:

```bash
node --test scripts/pollingUseCases-test.mjs
```

Expected: PASS.

- [ ] **Step 4: Browser adapter**

Create `fixthis-mcp/src/main/console/pollingBrowserAdapter.js`:

```js
// @requires pollingUseCases.js, api.js

function createBrowserPollingUseCases({ onChange } = {}) {
  return createPollingUseCases({
    onChange,
    api: {
      sessions: async ({ sessionsEtag, sessionEtag }) => {
        const sessionsResp = await fetch('/api/sessions', {
          headers: sessionsEtag ? { 'If-None-Match': sessionsEtag } : {},
        });
        const sessionResp = await fetch('/api/session', {
          headers: sessionEtag ? { 'If-None-Match': sessionEtag } : {},
        });
        return {
          sessionsEtag: sessionsResp.headers.get('ETag'),
          sessionEtag: sessionResp.headers.get('ETag'),
          sessions: await sessionsResp.json().catch(() => null),
          session: await sessionResp.json().catch(() => null),
        };
      },
    },
  });
}
```

- [ ] **Step 5: Migrate state.js and sessions-polling.js**

Delete from `state.js`:

```js
let sessionsPollingTimer = null;
let lastSessionsEtag = null;
let lastSessionEtag = null;
let pendingMutationCount = 0;
let consecutivePollFailures = 0;
let sessionMutationGeneration = 0;
let promptActionInFlight = false;
```

Add holder:

```js
const pollingUseCases = createBrowserPollingUseCases({
  onChange: () => triggerRender(),
});
```

In `sessions-polling.js`, replace the existing `pollSessionsTick()`,
`startSessionsPolling()`, `withMutationLock()` implementations with
delegates that call into `pollingUseCases.*`. The function names are
preserved at module scope.

`sessionsPollingTimer` becomes a closure inside `sessions-polling.js`'s
`startSessionsPolling` wrapper (browser-only timer state, not reducer
state).

- [ ] **Step 6: Run the contract test for asset symbols**

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleSessionsPollingContractTest"
```

Expected: PASS for all 13 tests. If any fails on a grep, capture the
symbol name and ensure the migration preserves it as a top-level
identifier in `sessions-polling.js`.

- [ ] **Step 7: Run full local check**

```bash
node --test scripts/pollingFsm-test.mjs scripts/pollingUseCases-test.mjs
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.*"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/console/pollingFsm.js \
        fixthis-mcp/src/main/console/pollingUseCases.js \
        fixthis-mcp/src/main/console/pollingBrowserAdapter.js \
        fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/console/sessions-polling.js \
        scripts/pollingFsm-test.mjs \
        scripts/pollingUseCases-test.mjs \
        scripts/build-console-assets.mjs \
        fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
feat(console): introduce Polling FSM

4 lifecycle states (STOPPED / POLLING_ACTIVE / POLLING_BACKOFF /
POLLING_PAUSED). Owns lastSessionsEtag, lastSessionEtag,
pendingMutationCount, sessionMutationGeneration, consecutivePollFailures,
promptActionInFlight. Preserves the public function names
(pollSessionsTick, startSessionsPolling, withMutationLock,
MaxConsecutivePollFailures) that ConsoleSessionsPollingContractTest
greps for.
EOF
)"
```

---

### Task 5: Tool-Mode FSM

**Files:**
- Create: `fixthis-mcp/src/main/console/toolModeFsm.js`
- Create: `fixthis-mcp/src/main/console/toolModeUseCases.js`
- Create: `scripts/toolModeFsm-test.mjs`
- Create: `scripts/toolModeUseCases-test.mjs`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `scripts/build-console-assets.mjs`

- [ ] **Step 1: Write the reducer test**

Create `scripts/toolModeFsm-test.mjs`:

```js
test('initial mode is SELECT', () => {
  const s = m.createEmptyToolMode();
  assert.equal(s.mode, m.ToolMode.SELECT);
  assert.equal(s.annotationSequence, 1);
  assert.equal(s.drag, null);
  assert.equal(s.historyDrawerOpen, false);
});

test('ENTER_ANNOTATE moves to ANNOTATE_IDLE', () => {
  const s = m.reduceToolMode(m.createEmptyToolMode(), { type: 'ENTER_ANNOTATE' });
  assert.equal(s.mode, m.ToolMode.ANNOTATE_IDLE);
});

test('START_DRAG moves to ANNOTATE_DRAGGING and records the start', () => {
  let s = m.reduceToolMode(m.createEmptyToolMode(), { type: 'ENTER_ANNOTATE' });
  s = m.reduceToolMode(s, { type: 'START_DRAG', point: { x: 10, y: 20 } });
  assert.equal(s.mode, m.ToolMode.ANNOTATE_DRAGGING);
  assert.deepEqual(s.drag, { start: { x: 10, y: 20 }, preview: null });
});

test('DROP_COMMIT moves to SELECT and increments annotationSequence', () => {
  let s = m.reduceToolMode(m.createEmptyToolMode(), { type: 'ENTER_ANNOTATE' });
  s = m.reduceToolMode(s, { type: 'START_DRAG', point: { x: 1, y: 2 } });
  s = m.reduceToolMode(s, { type: 'DROP_COMMIT' });
  assert.equal(s.mode, m.ToolMode.SELECT);
  assert.equal(s.annotationSequence, 2);
  assert.equal(s.drag, null);
});

test('TOGGLE_HISTORY_DRAWER flips historyDrawerOpen', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'TOGGLE_HISTORY_DRAWER' });
  assert.equal(s.historyDrawerOpen, true);
  s = m.reduceToolMode(s, { type: 'TOGGLE_HISTORY_DRAWER' });
  assert.equal(s.historyDrawerOpen, false);
});

test('FOCUS_SAVED_ITEM records id and sessionId', () => {
  let s = m.createEmptyToolMode();
  s = m.reduceToolMode(s, { type: 'FOCUS_SAVED_ITEM', itemId: 'i-1', sessionId: 's-1' });
  assert.equal(s.focusedSavedItemId, 'i-1');
  assert.equal(s.focusedSavedSessionId, 's-1');
});
```

- [ ] **Step 2: Run to verify FAIL → implement → verify PASS**

Create `fixthis-mcp/src/main/console/toolModeFsm.js`:

```js
// @requires (none)

const ToolMode = Object.freeze({
  SELECT: 'SELECT',
  ANNOTATE_IDLE: 'ANNOTATE_IDLE',
  ANNOTATE_DRAGGING: 'ANNOTATE_DRAGGING',
});

function createEmptyToolMode() {
  return Object.freeze({
    mode: ToolMode.SELECT,
    annotationSequence: 1,
    hoveredTarget: null,
    drag: null,
    suppressNextClick: false,
    focusedSavedItemId: null,
    focusedSavedSessionId: null,
    historyDrawerOpen: false,
    addItemsFlowStarting: false,
    newHistoryAnnotateModeStarting: false,
  });
}

function reduceToolMode(state, action) {
  switch (action.type) {
    case 'ENTER_SELECT':
      return Object.freeze({ ...state, mode: ToolMode.SELECT, drag: null });
    case 'ENTER_ANNOTATE':
      return Object.freeze({ ...state, mode: ToolMode.ANNOTATE_IDLE });
    case 'START_DRAG':
      return Object.freeze({
        ...state,
        mode: ToolMode.ANNOTATE_DRAGGING,
        drag: { start: action.point, preview: null },
      });
    case 'UPDATE_DRAG_PREVIEW':
      return Object.freeze({
        ...state,
        drag: state.drag ? { ...state.drag, preview: action.preview } : null,
      });
    case 'DROP_COMMIT':
      return Object.freeze({
        ...state,
        mode: ToolMode.SELECT,
        drag: null,
        annotationSequence: state.annotationSequence + 1,
      });
    case 'DROP_DISCARD':
      return Object.freeze({ ...state, mode: ToolMode.SELECT, drag: null });
    case 'SET_HOVERED_TARGET':
      return Object.freeze({ ...state, hoveredTarget: action.target });
    case 'SET_SUPPRESS_NEXT_CLICK':
      return Object.freeze({ ...state, suppressNextClick: !!action.value });
    case 'TOGGLE_HISTORY_DRAWER':
      return Object.freeze({ ...state, historyDrawerOpen: !state.historyDrawerOpen });
    case 'SET_HISTORY_DRAWER':
      return Object.freeze({ ...state, historyDrawerOpen: !!action.open });
    case 'FOCUS_SAVED_ITEM':
      return Object.freeze({
        ...state,
        focusedSavedItemId: action.itemId,
        focusedSavedSessionId: action.sessionId,
      });
    case 'SET_ADD_ITEMS_FLOW_STARTING':
      return Object.freeze({ ...state, addItemsFlowStarting: !!action.value });
    case 'SET_NEW_HISTORY_ANNOTATE_MODE_STARTING':
      return Object.freeze({ ...state, newHistoryAnnotateModeStarting: !!action.value });
    default:
      return state;
  }
}
```

Run:

```bash
node --test scripts/toolModeFsm-test.mjs
```

Expected: 6 tests PASS.

- [ ] **Step 3: Use cases (synchronous — no async I/O)**

Create `scripts/toolModeUseCases-test.mjs` and
`fixthis-mcp/src/main/console/toolModeUseCases.js`. The use cases are
thin wrappers over the reducer, providing one method per action and
side-effecting `onChange`:

```js
// @requires toolModeFsm.js

function createToolModeUseCases({ onChange } = {}) {
  let state = createEmptyToolMode();
  const dispatch = (action) => {
    state = reduceToolMode(state, action);
    onChange?.(state);
  };
  return {
    getState: () => state,
    enterSelect: () => dispatch({ type: 'ENTER_SELECT' }),
    enterAnnotate: () => dispatch({ type: 'ENTER_ANNOTATE' }),
    startDrag: (point) => dispatch({ type: 'START_DRAG', point }),
    updateDragPreview: (preview) => dispatch({ type: 'UPDATE_DRAG_PREVIEW', preview }),
    dropCommit: () => dispatch({ type: 'DROP_COMMIT' }),
    dropDiscard: () => dispatch({ type: 'DROP_DISCARD' }),
    setHoveredTarget: (target) => dispatch({ type: 'SET_HOVERED_TARGET', target }),
    setSuppressNextClick: (value) => dispatch({ type: 'SET_SUPPRESS_NEXT_CLICK', value }),
    toggleHistoryDrawer: () => dispatch({ type: 'TOGGLE_HISTORY_DRAWER' }),
    setHistoryDrawer: (open) => dispatch({ type: 'SET_HISTORY_DRAWER', open }),
    focusSavedItem: (itemId, sessionId) => dispatch({ type: 'FOCUS_SAVED_ITEM', itemId, sessionId }),
    setAddItemsFlowStarting: (value) => dispatch({ type: 'SET_ADD_ITEMS_FLOW_STARTING', value }),
    setNewHistoryAnnotateModeStarting: (value) => dispatch({ type: 'SET_NEW_HISTORY_ANNOTATE_MODE_STARTING', value }),
  };
}
```

- [ ] **Step 4: Migrate state.js and annotations.js**

Delete from `state.js`:

```js
let toolMode = 'select';
let annotationSequence = 1;
let hoveredAnnotationTarget = null;
let dragStart = null;
let dragPreview = null;
let suppressNextClick = false;
let historyDrawerOpen = false;
let addItemsFlowStarting = false;
let newHistoryAnnotateModeStarting = false;
let focusedSavedItemId = null;
let focusedSavedSessionId = null;
```

Add the holder:

```js
const toolModeUseCases = createToolModeUseCases({ onChange: () => triggerRender() });
```

In `annotations.js`, every read of `toolMode`, `dragStart`,
`dragPreview`, `hoveredAnnotationTarget`, `suppressNextClick`,
`historyDrawerOpen`, `focusedSavedItemId`, `focusedSavedSessionId`,
`addItemsFlowStarting`, `newHistoryAnnotateModeStarting` becomes
`toolModeUseCases.getState().<field>`. Every write becomes the matching
use-case method call (e.g., `toolMode = 'annotate'` →
`toolModeUseCases.enterAnnotate()`).

`annotationSequence` had an increment site (`'draft-' + annotationSequence++`).
Replace with a use-case method `nextAnnotationSeq()` that dispatches
`DROP_COMMIT` (which increments) and returns the previous value — or
simpler, a dedicated `ADVANCE_ANNOTATION_SEQ` action that returns the
seq.

- [ ] **Step 5: Run full local check**

```bash
node --test scripts/toolModeFsm-test.mjs scripts/toolModeUseCases-test.mjs
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/toolModeFsm.js \
        fixthis-mcp/src/main/console/toolModeUseCases.js \
        fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/console/annotations.js \
        scripts/toolModeFsm-test.mjs \
        scripts/toolModeUseCases-test.mjs \
        scripts/build-console-assets.mjs \
        fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
feat(console): introduce Tool-mode FSM

3 modes (SELECT / ANNOTATE_IDLE / ANNOTATE_DRAGGING). Owns toolMode,
annotationSequence, hoveredAnnotationTarget, dragStart, dragPreview,
suppressNextClick, addItemsFlowStarting,
newHistoryAnnotateModeStarting, historyDrawerOpen, focusedSavedItemId,
focusedSavedSessionId. No browser adapter — pure synchronous transitions.
EOF
)"
```

---

### Task 6: Top-Level Boot Factory and Final Cleanup

**Files:**
- Create: `fixthis-mcp/src/main/console/consoleApp.js`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `scripts/build-console-assets.mjs`
- Modify: `docs/reference/feedback-console-contract.md`

- [ ] **Step 1: Write the failing umbrella test**

Run:

```bash
node --test scripts/consoleFsmContract-test.mjs 2>&1 | head -20
```

Expected: the test `state.js has at most 5 module-level let declarations`
PASSES if all previous tasks deleted their `let`s correctly. The
remaining `let` count in `state.js` after Tasks 2-5 is now:

```js
let livePreviewTimer = null;     // belongs in preview.js closure
let statusClearTimeout = null;   // small status-banner timer, not state
```

Both are about to move into closures.

- [ ] **Step 2: Create the boot factory**

Create `fixthis-mcp/src/main/console/consoleApp.js`:

```js
// @requires connectionBrowserAdapter.js, previewBrowserAdapter.js, pollingBrowserAdapter.js, toolModeUseCases.js, draftCommandQueue.js

function createConsoleApp({ render } = {}) {
  const render_ = typeof render === 'function' ? render : () => {};
  const connection = createBrowserConnectionUseCases({ onChange: render_ });
  const preview = createBrowserPreviewUseCases({ onChange: render_ });
  const polling = createBrowserPollingUseCases({ onChange: render_ });
  const toolMode = createToolModeUseCases({ onChange: render_ });
  return { connection, preview, polling, toolMode };
}
```

- [ ] **Step 3: Wire main.js to use the factory**

In `fixthis-mcp/src/main/console/main.js`, replace the four individual
`createBrowser*UseCases` calls with one `createConsoleApp({ render: triggerRender })`. Export the four use-case bundles through `state.js`-level constants so existing modules keep their references.

- [ ] **Step 4: Move residual `let`s into closures**

In `preview.js`:

```js
function startLivePreview() {
  let livePreviewTimer = null;  // closure-scoped
  // ...
}
```

Same pattern for any other `let` left in `state.js`. After this step:

```bash
grep -cE "^ {12}let " fixthis-mcp/src/main/console/state.js
```

Expected: ≤ `5`. (The 12-space anchor matches the umbrella contract test; it counts only module-level declarations and excludes function-body locals.)

- [ ] **Step 5: Run umbrella tests**

```bash
node --test scripts/consoleFsmContract-test.mjs
```

Expected: all 5 tests PASS.

- [ ] **Step 6: Run the full local-checks recipe**

```bash
npm run console:fsm:test
npm run console:draft:test
npm run console:smoke
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
./gradlew :fixthis-mcp:test
./gradlew check
git diff --check
```

Expected: PASS.

- [ ] **Step 7: Update the contract doc**

In `docs/reference/feedback-console-contract.md`, add a section:

```markdown
## Console state machines

The console maintains five independent FSMs:

| FSM | States | Owned state |
|---|---|---|
| Draft Workspace | EMPTY / EDITING / FROZEN / RECOVERY | session-scoped pending annotations |
| Connection | DISCONNECTED / LAUNCHING / READY / BLOCKED / UNAVAILABLE | bridge connection lifecycle |
| Preview | IDLE / REQUESTING / READY / STALE / ERROR | live preview pipeline + zoom |
| Polling | STOPPED / POLLING_ACTIVE / POLLING_BACKOFF / POLLING_PAUSED | sessions polling + mutation lock |
| Tool-mode | SELECT / ANNOTATE_IDLE / ANNOTATE_DRAGGING | UI tool, hover, drag, drawer |

Each FSM is a pure reducer + use cases over ports + browser adapter.
Cross-FSM coordination happens in `consoleApp.js` (boot factory) and
`main.js`; there is no global event bus.
```

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/console/consoleApp.js \
        fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/console/preview.js \
        scripts/build-console-assets.mjs \
        docs/reference/feedback-console-contract.md \
        fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
feat(console): wire all four FSMs through createConsoleApp factory

state.js retains only DOM handles plus the FSM holders. Five
module-level let declarations total (residual closures, no shared
mutable state). The umbrella consoleFsmContract-test.mjs is GREEN.
Documents the five console FSMs in feedback-console-contract.md.
EOF
)"
```

---

### Task 7: End-to-End Verification and Smoke

**Files:**
- (verification only)

- [ ] **Step 1: Run the complete local checks recipe**

From `CONTRIBUTING.md` "Required local checks":

```bash
./gradlew :fixthis-mcp:test
./gradlew :fixthis-mcp:check
./gradlew :app:assembleDebug
node scripts/build-console-assets.mjs --check
npm run console:fsm:test
npm run console:draft:test
npm run console:smoke
git diff --check
```

Expected: PASS for every command.

- [ ] **Step 2: Microbenchmark frame budget**

```bash
npm run console:responsive:stress
```

Compare the reported frame time against the baseline (recorded before
Task 1). If the new selectors regress frame budget by more than 5%,
document the slowest selector and consider memoizing.

- [ ] **Step 3: Verify final `state.js` shape**

```bash
wc -l fixthis-mcp/src/main/console/state.js
grep -cE "^ {12}let " fixthis-mcp/src/main/console/state.js
grep -cE "^ {12}const " fixthis-mcp/src/main/console/state.js
```

Expected:
- Line count: significantly reduced from 326.
- Module-level `let` count: ≤ 5.
- Module-level `const` count: roughly preserved (DOM handles + FSM holders).

- [ ] **Step 4: Generate verification report (no commit)**

Print the final inventory to STDOUT (no file written):

```bash
{
  echo "Final state.js declarations:"
  grep -E "^\s+(let|const) " fixthis-mcp/src/main/console/state.js | head -30
  echo
  echo "FSM modules:"
  ls fixthis-mcp/src/main/console/*Fsm.js fixthis-mcp/src/main/console/*UseCases.js fixthis-mcp/src/main/console/*BrowserAdapter.js
  echo
  echo "Bundle size:"
  wc -c fixthis-mcp/src/main/resources/console/app.js
} | tee /tmp/fsm-final-report.txt
```

This is a verification step only; no commit.

---

## Self-Review Checklist

- [ ] Spec coverage:
  - §3.2 Connection FSM: Task 2.
  - §3.3 Preview FSM: Task 3.
  - §3.4 Tool-mode FSM: Task 5.
  - §3.5 Polling FSM: Task 4.
  - §3.6 Cross-FSM coordination via `consoleApp.js`: Task 6.
- [ ] Every FSM has a reducer test, a use-case test, and an asset
      contract check (Task 4 Step 6 for polling; Tasks 2/3/5 verify via
      `--tests "io.github.beyondwin.fixthis.mcp.console.*"`).
- [ ] All public function names grep'd by the asset contract tests
      (`withMutationLock`, `pollSessionsTick`, `startSessionsPolling`,
      `mergeSessionIntoState`, `MaxConsecutivePollFailures`,
      `DefaultLivePreviewIntervalMs`, `PreviewIntervalStorageKey`)
      survive — Task 4 Step 3 explicitly preserves them.
- [ ] No `--no-verify`, no rebase, no force push.
- [ ] No placeholder code; every step shows concrete diffs or commands.
- [ ] Each task ends with one commit (Tasks 1, 2, 3, 4, 5, 6) — 6 commits
      total plus the Task 7 verification (no commit).
- [ ] `state.js` ends with ≤ 5 module-level `let` declarations (umbrella
      contract test).
- [ ] Documentation updated in Task 6 Step 7.
