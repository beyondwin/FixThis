# Exception-Hardened Studio Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a workflow-state-machine layer that preserves the current FixThis Studio UI while making connection, session, draft, handoff, claim, and resolve exceptions deterministic.

**Architecture:** Add a pure `StudioWorkflow` decision model above the existing console state, then migrate risky mutation paths through it in stages. Keep rendering surfaces unchanged; the new layer only decides whether actions are allowed, blocked, confirmed, or ignored.

**Tech Stack:** Browser JavaScript concatenated by `scripts/build-console-assets.mjs`, Node.js `node:test` console contract tests, Kotlin MCP/session service tests, Gradle test tasks.

---

## Scope Check

The approved spec covers one subsystem: FixThis Studio workflow policy. It touches browser workflow code and MCP claim/resolve mutation safety, but both serve the same product contract: durable queue mutations must pass through one exception policy. This plan keeps the scope in one implementation plan and lands it in small, testable tasks.

## File Structure

- Create `fixthis-mcp/src/main/console/studioWorkflow.js`: pure decision model. No DOM, fetch, timers, or globals.
- Create `scripts/studioWorkflow-test.mjs`: pure decision tests.
- Modify `scripts/console-tests.json`: add `scripts/studioWorkflow-test.mjs` to existing `reliability` group.
- Modify `fixthis-mcp/src/main/console/domain/consoleReducer.js`: use the workflow model for canonical reducer paths.
- Modify `scripts/consoleCanonicalWorkflow-test.mjs`: prove reducer paths use the workflow policy.
- Create `fixthis-mcp/src/main/console/studioWorkflowAdapter.js`: browser adapter that derives workflow snapshots from current Studio globals and surfaces block/confirm decisions through existing UI affordances.
- Modify `fixthis-mcp/src/main/console/annotations.js`: guard annotate and pending save entry points through the adapter.
- Modify `fixthis-mcp/src/main/console/prompt.js`: guard Copy Prompt and Save to MCP through the adapter.
- Create `scripts/studioWorkflowIntegration-test.mjs`: source-contract tests that keep browser mutation paths wired to the workflow adapter.
- Modify `scripts/console-tests.json`: add `scripts/studioWorkflowIntegration-test.mjs` to existing `reliability` group.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/AnnotationWorkflow.kt`: reject claim/resolve against closed sessions before mutating.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceClaimTest.kt`: service-level closed-session claim/resolve tests.
- Modify `docs/reference/feedback-console-contract.md` and `docs/guides/troubleshooting.md`: document automatic recovery versus confirmation-required mutations.

---

### Task 1: Pure Studio Workflow Decision Model

**Files:**
- Create: `fixthis-mcp/src/main/console/studioWorkflow.js`
- Create: `scripts/studioWorkflow-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write the failing pure workflow tests**

Create `scripts/studioWorkflow-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/studioWorkflow.js'), 'utf8');
const factory = new Function(`${src}; return {
  StudioWorkflowAction,
  StudioWorkflowDecisionType,
  decideStudioWorkflow,
};`);
const m = factory();

function decide(action, patch = {}) {
  return m.decideStudioWorkflow(action, {
    connection: 'ready',
    workspace: 'live-preview',
    operation: 'idle',
    risks: [],
    activeSessionStatus: 'active',
    targetItemExists: true,
    ...patch,
  });
}

test('allows annotate when connection is ready and workspace is live preview', () => {
  const decision = decide(m.StudioWorkflowAction.ANNOTATE_CLICKED);
  assert.equal(decision.type, m.StudioWorkflowDecisionType.ALLOW);
  assert.equal(decision.effect, 'capture-preview');
});

test('blocks annotate when device is blocked', () => {
  const decision = decide(m.StudioWorkflowAction.ANNOTATE_CLICKED, {
    connection: 'blocked',
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.BLOCK);
  assert.equal(decision.reason, 'connection-blocked');
  assert.equal(decision.displaySurface, 'connection-card');
});

test('confirms Save to MCP from stale preview', () => {
  const decision = decide(m.StudioWorkflowAction.SAVE_TO_MCP_CLICKED, {
    workspace: 'frozen-draft',
    risks: ['stale-preview'],
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.CONFIRM);
  assert.equal(decision.boundaryKind, 'fingerprintMismatch');
  assert.deepEqual(decision.choices, ['recapture', 'force', 'cancel']);
});

test('confirms session switch with dirty draft', () => {
  const decision = decide(m.StudioWorkflowAction.SESSION_SWITCH_REQUESTED, {
    workspace: 'frozen-draft',
    risks: ['dirty-draft'],
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.CONFIRM);
  assert.equal(decision.boundaryKind, 'sessionSwitch');
});

test('blocks session switch while durable mutation is in flight', () => {
  const decision = decide(m.StudioWorkflowAction.SESSION_SWITCH_REQUESTED, {
    operation: 'saving-handoff',
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.BLOCK);
  assert.equal(decision.reason, 'operation-in-flight');
  assert.equal(decision.displaySurface, 'prompt-readiness');
});

test('ignores late async response for stale generation', () => {
  const decision = decide(m.StudioWorkflowAction.ASYNC_RESPONSE_RECEIVED, {
    currentGeneration: 7,
    eventGeneration: 6,
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.IGNORE);
  assert.equal(decision.reason, 'stale-generation');
});

test('ignores late async response for inactive session', () => {
  const decision = decide(m.StudioWorkflowAction.ASYNC_RESPONSE_RECEIVED, {
    activeSessionId: 'session-a',
    eventSessionId: 'session-b',
    currentGeneration: 7,
    eventGeneration: 7,
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.IGNORE);
  assert.equal(decision.reason, 'session-mismatch');
});

test('blocks resolve when item is missing', () => {
  const decision = decide(m.StudioWorkflowAction.RESOLVE_ITEM_CLICKED, {
    targetItemExists: false,
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.BLOCK);
  assert.equal(decision.reason, 'missing-item');
});

test('blocks durable mutations for closed session', () => {
  const decision = decide(m.StudioWorkflowAction.CLAIM_ITEM_CLICKED, {
    activeSessionStatus: 'closed',
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.BLOCK);
  assert.equal(decision.reason, 'closed-session');
});
```

- [ ] **Step 2: Run the pure workflow test to verify it fails**

Run:

```bash
node --test scripts/studioWorkflow-test.mjs
```

Expected: FAIL with `ENOENT` for `fixthis-mcp/src/main/console/studioWorkflow.js`.

- [ ] **Step 3: Add the pure workflow implementation**

Create `fixthis-mcp/src/main/console/studioWorkflow.js`:

```js
// @requires (none)
const StudioWorkflowDecisionType = Object.freeze({
  ALLOW: 'allow',
  BLOCK: 'block',
  CONFIRM: 'confirm',
  IGNORE: 'ignore',
});

const StudioWorkflowAction = Object.freeze({
  ANNOTATE_CLICKED: 'annotate-clicked',
  SAVE_DRAFT_CLICKED: 'save-draft-clicked',
  COPY_PROMPT_CLICKED: 'copy-prompt-clicked',
  SAVE_TO_MCP_CLICKED: 'save-to-mcp-clicked',
  SESSION_SWITCH_REQUESTED: 'session-switch-requested',
  ASYNC_RESPONSE_RECEIVED: 'async-response-received',
  CLAIM_ITEM_CLICKED: 'claim-item-clicked',
  RESOLVE_ITEM_CLICKED: 'resolve-item-clicked',
  CONNECTION_STATUS_RECEIVED: 'connection-status-received',
});

const durableMutationActions = new Set([
  StudioWorkflowAction.SAVE_DRAFT_CLICKED,
  StudioWorkflowAction.COPY_PROMPT_CLICKED,
  StudioWorkflowAction.SAVE_TO_MCP_CLICKED,
  StudioWorkflowAction.CLAIM_ITEM_CLICKED,
  StudioWorkflowAction.RESOLVE_ITEM_CLICKED,
]);

const hardConnectionStates = new Set(['blocked', 'unsupported', 'no-device']);
const inFlightOperations = new Set([
  'capturing',
  'saving-draft',
  'copying-prompt',
  'saving-handoff',
  'claiming',
  'resolving',
]);

function allowStudioWorkflow(effect) {
  return Object.freeze({ type: StudioWorkflowDecisionType.ALLOW, effect });
}

function blockStudioWorkflow(reason, displaySurface) {
  return Object.freeze({ type: StudioWorkflowDecisionType.BLOCK, reason, displaySurface });
}

function confirmStudioWorkflow(boundaryKind, choices, reason) {
  return Object.freeze({
    type: StudioWorkflowDecisionType.CONFIRM,
    boundaryKind,
    choices: Object.freeze([...choices]),
    reason,
  });
}

function ignoreStudioWorkflow(reason) {
  return Object.freeze({ type: StudioWorkflowDecisionType.IGNORE, reason });
}

function workflowRisks(snapshot) {
  return new Set(snapshot?.risks || []);
}

function hasWorkflowRisk(snapshot, risk) {
  return workflowRisks(snapshot).has(risk);
}

function connectionReady(snapshot) {
  return (snapshot?.connection || 'initializing') === 'ready';
}

function sessionClosed(snapshot) {
  return (snapshot?.activeSessionStatus || 'active') === 'closed';
}

function durableMutationInFlight(snapshot) {
  return inFlightOperations.has(snapshot?.operation || 'idle');
}

function decideAsyncResponse(snapshot) {
  if (
    snapshot?.currentGeneration != null &&
    snapshot?.eventGeneration != null &&
    snapshot.eventGeneration !== snapshot.currentGeneration
  ) {
    return ignoreStudioWorkflow('stale-generation');
  }
  if (
    snapshot?.activeSessionId &&
    snapshot?.eventSessionId &&
    snapshot.eventSessionId !== snapshot.activeSessionId
  ) {
    return ignoreStudioWorkflow('session-mismatch');
  }
  return allowStudioWorkflow('apply-response');
}

function decideStudioWorkflow(action, snapshot = {}) {
  if (action === StudioWorkflowAction.ASYNC_RESPONSE_RECEIVED) {
    return decideAsyncResponse(snapshot);
  }

  if (durableMutationInFlight(snapshot)) {
    return blockStudioWorkflow('operation-in-flight', 'prompt-readiness');
  }

  if (sessionClosed(snapshot) && durableMutationActions.has(action)) {
    return blockStudioWorkflow('closed-session', 'history-panel');
  }

  if (action === StudioWorkflowAction.ANNOTATE_CLICKED) {
    if (!connectionReady(snapshot)) {
      const state = snapshot.connection || 'initializing';
      return blockStudioWorkflow(
        hardConnectionStates.has(state) ? 'connection-blocked' : 'connection-not-ready',
        'connection-card',
      );
    }
    if ((snapshot.workspace || 'empty') !== 'live-preview') {
      return blockStudioWorkflow('no-live-preview', 'preview-frame');
    }
    return allowStudioWorkflow('capture-preview');
  }

  if (action === StudioWorkflowAction.SESSION_SWITCH_REQUESTED) {
    if (hasWorkflowRisk(snapshot, 'dirty-draft')) {
      return confirmStudioWorkflow('sessionSwitch', ['saveAndProceed', 'discardAndProceed', 'cancel'], 'dirty-draft');
    }
    return allowStudioWorkflow('open-session');
  }

  if (
    action === StudioWorkflowAction.SAVE_DRAFT_CLICKED ||
    action === StudioWorkflowAction.COPY_PROMPT_CLICKED ||
    action === StudioWorkflowAction.SAVE_TO_MCP_CLICKED
  ) {
    if (hasWorkflowRisk(snapshot, 'stale-preview') || hasWorkflowRisk(snapshot, 'activity-drift')) {
      return confirmStudioWorkflow('fingerprintMismatch', ['recapture', 'force', 'cancel'], 'stale-preview');
    }
    if (!connectionReady(snapshot) && (snapshot.workspace || 'empty') === 'frozen-draft') {
      return confirmStudioWorkflow('fingerprintMismatch', ['recapture', 'force', 'cancel'], 'connection-paused');
    }
    return allowStudioWorkflow(action === StudioWorkflowAction.COPY_PROMPT_CLICKED ? 'copy-prompt' : 'persist-feedback');
  }

  if (action === StudioWorkflowAction.CLAIM_ITEM_CLICKED || action === StudioWorkflowAction.RESOLVE_ITEM_CLICKED) {
    if (snapshot.targetItemExists === false) {
      return blockStudioWorkflow('missing-item', 'handoff-queue');
    }
    return allowStudioWorkflow(action === StudioWorkflowAction.CLAIM_ITEM_CLICKED ? 'claim-item' : 'resolve-item');
  }

  return allowStudioWorkflow('continue');
}
```

- [ ] **Step 4: Run the pure workflow test to verify it passes**

Run:

```bash
node --test scripts/studioWorkflow-test.mjs
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Register the pure test in the existing reliability group**

Modify the `reliability` array in `scripts/console-tests.json` to include the new test:

```json
  "reliability": [
    "scripts/studioWorkflow-test.mjs",
    "scripts/studioReliabilityContract-test.mjs",
    "scripts/sessionMismatchIgnore-test.mjs",
    "scripts/draftUseCases-test.mjs",
    "scripts/draftRecoveryMatrix-test.mjs"
  ],
```

- [ ] **Step 6: Run the reliability group**

Run:

```bash
npm run console:reliability:test
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add fixthis-mcp/src/main/console/studioWorkflow.js scripts/studioWorkflow-test.mjs scripts/console-tests.json
git commit -m "feat(console): add Studio workflow policy model"
```

---

### Task 2: Wire Workflow Decisions Into Canonical Reducer Paths

**Files:**
- Modify: `fixthis-mcp/src/main/console/domain/consoleReducer.js`
- Modify: `scripts/consoleCanonicalWorkflow-test.mjs`

- [ ] **Step 1: Add failing canonical reducer tests**

Append these tests to `scripts/consoleCanonicalWorkflow-test.mjs` and add `studioWorkflow.js` to the `sources` list before `domain/workspaceState.js`:

```js
test('workflow blocks annotate when connection is not ready', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [{ sessionId: 'session-a' }],
    connection: { current: { state: 'CHECK_PHONE' } },
    workspace: m.livePreviewWorkspace ? m.livePreviewWorkspace('session-a', preview(1)) : { kind: m.WorkspaceKind.LIVE_PREVIEW, sessionId: 'session-a', preview: preview(1) },
  });
  const result = m.reduceConsoleAppState(state, { type: 'ANNOTATE_CLICKED' });
  assert.deepEqual(result.effects, []);
  assert.equal(result.state.status.variant, 'warn');
  assert.match(result.state.status.message, /Connect the app before annotating/);
});

test('workflow blocks session switch while prompt action is in flight', () => {
  const state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [{ sessionId: 'session-a' }, { sessionId: 'session-b' }],
    promptAction: { inFlight: true },
  });
  const result = m.reduceConsoleAppState(state, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' });
  assert.equal(result.state.activeSessionId, 'session-a');
  assert.deepEqual(result.effects, []);
  assert.equal(result.state.status.variant, 'warn');
  assert.match(result.state.status.message, /Finish the current handoff action/);
});
```

Also expose `livePreviewWorkspace` from the factory return object:

```js
const factory = new Function(`${sources}; return {
  createInitialConsoleAppState,
  reduceConsoleAppState,
  assertConsoleInvariants,
  WorkspaceKind,
  livePreviewWorkspace
};`);
```

- [ ] **Step 2: Run canonical workflow tests to verify they fail**

Run:

```bash
node --test scripts/consoleCanonicalWorkflow-test.mjs
```

Expected: FAIL because `consoleReducer.js` does not call `decideStudioWorkflow`.

- [ ] **Step 3: Add workflow snapshot helpers to the reducer**

Modify the header in `fixthis-mcp/src/main/console/domain/consoleReducer.js`:

```js
// @requires studioWorkflow.js, domain/workspaceState.js, domain/consoleAppState.js, domain/consoleInvariants.js
```

Add these helpers after `reduceConsoleAppState`:

```js
function workflowConnectionFromState(state) {
  const raw = String(state.connection?.current?.state || '').toLowerCase();
  if (raw === 'ready') return 'ready';
  if (raw === 'check_phone' || raw === 'choose_device') return 'blocked';
  if (raw === 'unsupported_build') return 'unsupported';
  if (raw === 'open_app' || raw === 'reconnect' || raw === 'starting') return 'reconnecting';
  return 'initializing';
}

function workflowWorkspaceFromState(state) {
  if (state.workspace?.kind === WorkspaceKind.LIVE_PREVIEW) return 'live-preview';
  if (state.workspace?.kind === WorkspaceKind.DRAFT) return 'frozen-draft';
  if (state.workspace?.kind === WorkspaceKind.SAVED_FOCUS) return 'saved-focus';
  if (state.workspace?.kind === WorkspaceKind.RECOVERY) return 'recovery';
  return 'empty';
}

function workflowRisksFromState(state) {
  const risks = [];
  if (state.workspace?.kind === WorkspaceKind.DRAFT && state.workspace.items?.length) risks.push('dirty-draft');
  if (state.workspace?.kind === WorkspaceKind.DRAFT && state.workspace.activityDriftWarning) risks.push('activity-drift');
  if (state.workspace?.kind === WorkspaceKind.LIVE_PREVIEW && state.workspace.preview?.stale) risks.push('stale-preview');
  if (state.promptAction?.inFlight) risks.push('in-flight-mutation');
  return risks;
}

function workflowSnapshotFromState(state, patch = {}) {
  // In the canonical reducer slice we use `effectsGeneration` as the
  // generation token because reducer-driven tests stage decisions over
  // reducer state. The browser adapter uses the polling FSM's
  // `mutationGeneration` for the same logical role; the two counters track
  // different lifecycles (reducer effect dispatch vs. server mutation
  // round-trips) but both monotonically increment and both are sufficient
  // to detect stale `ASYNC_RESPONSE_RECEIVED` events within their own
  // surface. Cross-surface generation comparisons are out of scope here.
  return Object.freeze({
    connection: workflowConnectionFromState(state),
    workspace: workflowWorkspaceFromState(state),
    operation: state.promptAction?.inFlight ? 'saving-handoff' : 'idle',
    risks: workflowRisksFromState(state),
    activeSessionId: state.activeSessionId || null,
    activeSessionStatus: state.sessionsById?.[state.activeSessionId]?.status || 'active',
    currentGeneration: state.effectsGeneration,
    ...patch,
  });
}

function workflowStatus(decision) {
  if (decision.reason === 'operation-in-flight') {
    return Object.freeze({ message: 'Finish the current handoff action before switching sessions.', variant: 'warn', assertive: false });
  }
  if (decision.reason === 'connection-blocked' || decision.reason === 'connection-not-ready') {
    return Object.freeze({ message: 'Connect the app before annotating.', variant: 'warn', assertive: false });
  }
  if (decision.reason === 'closed-session') {
    return Object.freeze({ message: 'Reopen the session or create a new active session before changing feedback.', variant: 'warn', assertive: false });
  }
  return Object.freeze({ message: 'This action is blocked by the current Studio workflow state.', variant: 'warn', assertive: false });
}
```

- [ ] **Step 4: Gate annotate and session switch in the reducer**

Modify `reduceSessionRowClicked` before the draft boundary logic:

```js
  const decision = decideStudioWorkflow(
    StudioWorkflowAction.SESSION_SWITCH_REQUESTED,
    workflowSnapshotFromState(state, { eventSessionId: sessionId }),
  );
  if (decision.type === StudioWorkflowDecisionType.BLOCK) {
    return { state: replaceConsoleState(state, { status: workflowStatus(decision) }), effects: [] };
  }
```

Modify `reduceAnnotateClicked` at the beginning:

```js
  const decision = decideStudioWorkflow(
    StudioWorkflowAction.ANNOTATE_CLICKED,
    workflowSnapshotFromState(state),
  );
  if (decision.type === StudioWorkflowDecisionType.BLOCK) {
    return { state: replaceConsoleState(state, { status: workflowStatus(decision) }), effects: [] };
  }
```

- [ ] **Step 5: Run canonical workflow tests**

Run:

```bash
node --test scripts/consoleCanonicalWorkflow-test.mjs
```

Expected: PASS.

- [ ] **Step 6: Run canonical and reliability groups**

Run:

```bash
npm run console:reliability:test
node scripts/run-console-tests.mjs canonical
```

Expected: both PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add fixthis-mcp/src/main/console/domain/consoleReducer.js scripts/consoleCanonicalWorkflow-test.mjs
git commit -m "feat(console): gate canonical Studio workflow actions"
```

---

### Task 3: Add Browser Workflow Adapter And Guard Visible Actions

**Files:**
- Create: `fixthis-mcp/src/main/console/studioWorkflowAdapter.js`
- Create: `scripts/studioWorkflowIntegration-test.mjs`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write failing browser integration contract tests**

Create `scripts/studioWorkflowIntegration-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const adapterSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/studioWorkflowAdapter.js'), 'utf8');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
const promptSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/prompt.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  let i = source.indexOf('(', start);
  let parenDepth = 0;
  for (; i < source.length; i += 1) {
    if (source[i] === '(') parenDepth += 1;
    if (source[i] === ')') {
      parenDepth -= 1;
      if (parenDepth === 0) { i += 1; break; }
    }
  }
  const bodyStart = source.indexOf('{', i);
  let depth = 0;
  for (let j = bodyStart; j < source.length; j += 1) {
    if (source[j] === '{') depth += 1;
    if (source[j] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, j);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('browser adapter derives workflow snapshot and surfaces decisions', () => {
  assert.match(adapterSource, /function currentStudioWorkflowSnapshot\(/);
  assert.match(adapterSource, /function decideCurrentStudioWorkflow\(/);
  assert.match(adapterSource, /function surfaceStudioWorkflowDecision\(/);
  assert.match(adapterSource, /decideStudioWorkflow\(action,\s*currentStudioWorkflowSnapshot/);
});

test('annotate flow consults workflow before mutating UI state', () => {
  const start = body(annotationsSource, 'async function startDraftAnnotationFlow()');
  assert.match(start, /decideCurrentStudioWorkflow\(StudioWorkflowAction\.ANNOTATE_CLICKED/);
  assert.match(start, /surfaceStudioWorkflowDecision\(decision\)/);
});

test('composer disables prompt buttons using workflow decisions', () => {
  const update = body(annotationsSource, 'function updateComposerState()');
  assert.match(update, /decideCurrentStudioWorkflow\(StudioWorkflowAction\.SAVE_TO_MCP_CLICKED/);
  assert.match(update, /promptDecision\.type === StudioWorkflowDecisionType\.BLOCK/);
});

test('copy and Save to MCP consult workflow before durable mutation', () => {
  const copy = body(promptSource, 'async function copyPrompt()');
  const send = body(promptSource, 'async function sendAgentPrompt()');
  assert.match(copy, /decideCurrentStudioWorkflow\(StudioWorkflowAction\.COPY_PROMPT_CLICKED/);
  assert.match(copy, /surfaceStudioWorkflowDecision\(decision\)/);
  assert.match(send, /decideCurrentStudioWorkflow\(StudioWorkflowAction\.SAVE_TO_MCP_CLICKED/);
  assert.match(send, /surfaceStudioWorkflowDecision\(decision\)/);
});
```

- [ ] **Step 2: Run the integration test to verify it fails**

Run:

```bash
node --test scripts/studioWorkflowIntegration-test.mjs
```

Expected: FAIL with `ENOENT` for `studioWorkflowAdapter.js`.

- [ ] **Step 3: Add the browser adapter**

Create `fixthis-mcp/src/main/console/studioWorkflowAdapter.js`:

```js
// @requires studioWorkflow.js, state.js, draftWorkspace.js
function currentStudioWorkflowConnection() {
  const raw = String(state.connection?.current?.state || '').toLowerCase();
  if (raw === 'ready') return 'ready';
  if (raw === 'check_phone' || raw === 'choose_device') return 'blocked';
  if (raw === 'unsupported_build') return 'unsupported';
  if (raw === 'open_app' || raw === 'reconnect' || raw === 'starting') return 'reconnecting';
  if (!state.selectedDeviceSerial && Array.isArray(state.devices) && state.devices.length === 0) return 'no-device';
  return 'initializing';
}

function currentStudioWorkflowWorkspace() {
  if (draftFlow()) return 'frozen-draft';
  if (state.preview) return 'live-preview';
  if (toolMode?.getState?.().focusedSavedItemId) return 'saved-focus';
  return 'empty';
}

function currentStudioWorkflowOperation() {
  if (pollingUseCases?.getState?.().promptActionInFlight) return 'saving-handoff';
  if (previewUseCases?.getState?.().inFlight) return 'capturing';
  if (toolMode?.getState?.().draftFlowStarting) return 'capturing';
  return 'idle';
}

function currentStudioWorkflowRisks() {
  const risks = [];
  if (draftFlow() && draftItemList().length) risks.push('dirty-draft');
  if (draftFlow() && draftWorkspace?.activityDriftWarning) risks.push('activity-drift');
  if (state.preview?.stale) risks.push('stale-preview');
  if (pollingUseCases?.getState?.().promptActionInFlight) risks.push('in-flight-mutation');
  return risks;
}

function currentStudioWorkflowSnapshot(patch = {}) {
  // `currentGeneration` reflects the polling FSM mutation counter so that
  // ASYNC_RESPONSE_RECEIVED can detect stale responses. The variable
  // `sessionMutationGeneration` is only referenced in a comment in `state.js`;
  // the live counter lives at `pollingUseCases.getState().mutationGeneration`.
  const generation = pollingUseCases?.getState?.()?.mutationGeneration;
  return {
    connection: currentStudioWorkflowConnection(),
    workspace: currentStudioWorkflowWorkspace(),
    operation: currentStudioWorkflowOperation(),
    risks: currentStudioWorkflowRisks(),
    activeSessionId: state.session?.sessionId || null,
    activeSessionStatus: state.session?.status || 'active',
    currentGeneration: typeof generation === 'number' ? generation : null,
    ...patch,
  };
}

function decideCurrentStudioWorkflow(action, patch = {}) {
  return decideStudioWorkflow(action, currentStudioWorkflowSnapshot(patch));
}

function studioWorkflowMessage(decision) {
  if (decision.reason === 'operation-in-flight') return 'Finish the current handoff action before starting another.';
  if (decision.reason === 'connection-blocked' || decision.reason === 'connection-not-ready') return 'Connection paused - draft preserved.';
  if (decision.reason === 'closed-session') return 'Reopen the session or create a new active session before changing feedback.';
  if (decision.reason === 'missing-item') return 'This feedback item is no longer available.';
  return 'This action is blocked by the current Studio workflow state.';
}

function surfaceStudioWorkflowDecision(decision) {
  if (!decision || decision.type === StudioWorkflowDecisionType.ALLOW || decision.type === StudioWorkflowDecisionType.IGNORE) return false;
  if (decision.type === StudioWorkflowDecisionType.CONFIRM) return false;
  const message = studioWorkflowMessage(decision);
  if (typeof showStatus === 'function') {
    showStatus(message, { variant: 'warn', durationMs: 3500 });
  } else if (typeof showWarning === 'function') {
    showWarning(message);
  }
  return true;
}
```

- [ ] **Step 4: Guard annotate and prompt controls**

Modify the header of `fixthis-mcp/src/main/console/annotations.js`:

```js
// @requires state.js, studioWorkflowAdapter.js, draftWorkspace.js, draftUseCases.js, draftCommandQueue.js, editorState.js, inspectorFooter.js, editorBackButton.js, viewmodel/reliabilityPresentation.js, viewmodel/annotationPresentation.js
```

At the top of `startDraftAnnotationFlow`, after the `draftFlowStarting` guard, add:

```js
              const decision = decideCurrentStudioWorkflow(StudioWorkflowAction.ANNOTATE_CLICKED);
              if (surfaceStudioWorkflowDecision(decision)) return;
```

In `updateComposerState`, replace:

```js
              const promptDisabled = !hasPromptAnnotations || pollingUseCases.getState().promptActionInFlight;
```

with:

```js
              const promptDecision = decideCurrentStudioWorkflow(StudioWorkflowAction.SAVE_TO_MCP_CLICKED);
              const promptDisabled = !hasPromptAnnotations ||
                pollingUseCases.getState().promptActionInFlight ||
                promptDecision.type === StudioWorkflowDecisionType.BLOCK;
```

Modify the header of `fixthis-mcp/src/main/console/prompt.js`:

```js
// @requires state.js, studioWorkflowAdapter.js, draftWorkspace.js, draftUseCases.js, annotations.js
```

In `copyPrompt`, after the `promptActionInFlight` guard, add:

```js
                const decision = decideCurrentStudioWorkflow(StudioWorkflowAction.COPY_PROMPT_CLICKED);
                if (surfaceStudioWorkflowDecision(decision)) return;
```

In `sendAgentPrompt`, after the `promptActionInFlight` guard, add:

```js
                const decision = decideCurrentStudioWorkflow(StudioWorkflowAction.SAVE_TO_MCP_CLICKED);
                if (surfaceStudioWorkflowDecision(decision)) return;
```

- [ ] **Step 5: Run integration test**

Run:

```bash
node --test scripts/studioWorkflowIntegration-test.mjs
```

Expected: PASS.

- [ ] **Step 6: Register integration test in reliability group**

Modify the `reliability` array in `scripts/console-tests.json`:

```json
  "reliability": [
    "scripts/studioWorkflow-test.mjs",
    "scripts/studioWorkflowIntegration-test.mjs",
    "scripts/studioReliabilityContract-test.mjs",
    "scripts/sessionMismatchIgnore-test.mjs",
    "scripts/draftUseCases-test.mjs",
    "scripts/draftRecoveryMatrix-test.mjs"
  ],
```

- [ ] **Step 7: Run reliability and bundle checks**

Run:

```bash
npm run console:reliability:test
node scripts/build-console-assets.mjs --check
```

Expected: reliability PASS. The bundle check may fail because generated assets are stale after adding browser JS.

- [ ] **Step 8: Regenerate console assets if the bundle check reports stale assets**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: bundle check PASS.

- [ ] **Step 9: Commit**

Run:

```bash
git add fixthis-mcp/src/main/console/studioWorkflowAdapter.js \
  fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/console/prompt.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  fixthis-mcp/src/main/resources/console/console-build-meta.json \
  scripts/studioWorkflowIntegration-test.mjs \
  scripts/console-tests.json
git commit -m "feat(console): guard Studio actions through workflow policy"
```

---

### Task 4: Enforce Closed-Session Claim And Resolve Safety

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/AnnotationWorkflow.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceClaimTest.kt`

**Scope note:** This slice only adds a closed-session guard to
`claimFeedback` and `resolveFeedback`. The store-side `claimFeedback`
in `FeedbackSessionStoreDelegate.kt` already rejects already-resolved
items but does not check session status, so the guard must live in
`AnnotationWorkflow`. Other durable mutation paths on closed sessions
(`addAreaFeedback`, draft-save batches, `updateDraftItem`) are not
guarded here; the UI-side workflow policy from Tasks 1–3 prevents
those entry points from firing on a closed session, and a defense-in-
depth store guard for those paths is deferred to a follow-up slice.

- [ ] **Step 1: Add failing service tests**

Append to `FeedbackSessionServiceClaimTest.kt`:

```kotlin
    @Test
    fun serviceClaimRejectsClosedSession() = runBlocking {
        val ids = ArrayDeque(listOf("session-1", "screen-1", "item-1"))
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { ids.removeFirst() })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(packageNameOverride = null, newSession = true)
        val screen = service.captureScreen(session.sessionId)
        service.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = FixThisRect(1f, 2f, 3f, 4f),
            comment = "fix this",
        )
        store.closeSession(session.sessionId)

        val error = kotlin.test.assertFailsWith<FeedbackSessionException> {
            service.claimFeedback(session.sessionId, "item-1", agentNote = "via service")
        }

        kotlin.test.assertTrue(error.message!!.startsWith("SESSION_CLOSED"))
    }

    @Test
    fun serviceResolveRejectsClosedSession() = runBlocking {
        val ids = ArrayDeque(listOf("session-1", "screen-1", "item-1"))
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { ids.removeFirst() })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(packageNameOverride = null, newSession = true)
        val screen = service.captureScreen(session.sessionId)
        service.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = FixThisRect(1f, 2f, 3f, 4f),
            comment = "fix this",
        )
        store.closeSession(session.sessionId)

        val error = kotlin.test.assertFailsWith<FeedbackSessionException> {
            service.resolveFeedback(
                sessionId = session.sessionId,
                itemId = "item-1",
                status = AnnotationStatusDto.RESOLVED,
                summary = "done",
            )
        }

        kotlin.test.assertTrue(error.message!!.startsWith("SESSION_CLOSED"))
    }
```

- [ ] **Step 2: Run the focused Kotlin test to verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackSessionServiceClaimTest" --no-daemon
```

Expected: FAIL because closed sessions still allow claim/resolve through `AnnotationWorkflow`.

- [ ] **Step 3: Add the closed-session guard**

In `AnnotationWorkflow.kt`, add this private helper inside `AnnotationWorkflow`:

```kotlin
    private fun requireOpenSessionForAgentMutation(sessionId: String) {
        val session = store.getSession(sessionId)
        if (session.status == SessionStatusDto.CLOSED) {
            throw FeedbackSessionException(
                "SESSION_CLOSED: Reopen the session or create a new active session before changing feedback.",
            )
        }
    }
```

Call it at the start of `resolveFeedback`:

```kotlin
    fun resolveFeedback(
        sessionId: String,
        itemId: String,
        status: AnnotationStatusDto,
        summary: String?,
    ): AnnotationDto = runBlocking {
        requireOpenSessionForAgentMutation(sessionId)
        val updated = resolveAnnotation(
```

Call it at the start of `claimFeedback`:

```kotlin
    fun claimFeedback(
        sessionId: String,
        itemId: String,
        agentNote: String?,
    ): AnnotationDto = runBlocking {
        requireOpenSessionForAgentMutation(sessionId)
        val updated = claimAnnotation(
```

- [ ] **Step 4: Run the focused Kotlin test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackSessionServiceClaimTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Run broader session tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackSessionStoreTest" --tests "*FeedbackSessionServiceClaimTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/AnnotationWorkflow.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceClaimTest.kt
git commit -m "fix(mcp): reject agent queue mutations on closed sessions"
```

---

### Task 5: Document Workflow Exception Policy

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/guides/troubleshooting.md`

- [ ] **Step 1: Add failing docs contract checks**

Append these checks to `scripts/studioWorkflowIntegration-test.mjs`:

```js
const feedbackConsoleContract = readFileSync(resolve(root, 'docs/reference/feedback-console-contract.md'), 'utf8');
const troubleshooting = readFileSync(resolve(root, 'docs/guides/troubleshooting.md'), 'utf8');

test('docs describe automatic recovery and confirmation-required mutations', () => {
  assert.match(feedbackConsoleContract, /Automatic recovery/);
  assert.match(feedbackConsoleContract, /Confirmation-required mutations/);
  assert.match(feedbackConsoleContract, /Connection loss never clears a draft workspace/);
  assert.match(troubleshooting, /Connection paused - draft preserved/);
  assert.match(troubleshooting, /Reopen the session or create a new active session/);
});
```

- [ ] **Step 2: Run docs contract test to verify it fails**

Run:

```bash
node --test scripts/studioWorkflowIntegration-test.mjs
```

Expected: FAIL because the docs do not contain the new workflow policy wording.

- [ ] **Step 3: Update feedback console contract**

Add this section to `docs/reference/feedback-console-contract.md` after the connection/device state contract section:

```markdown
## Studio workflow exception policy

Studio keeps the existing preview, connection card, annotation panel, and
handoff controls. Exception handling is centralized as a workflow policy:

- **Automatic recovery:** heartbeat retry, preview refresh, session polling
  resume, app foreground recovery, and blocked-reason clearing may happen
  without a confirmation dialog. These actions do not mutate draft, handoff,
  claim, or resolve state.
- **Confirmation-required mutations:** dirty-draft session switch, stale
  preview Save to MCP, activity drift during handoff, server draft conflict,
  and stale force-save require a boundary dialog before durable state changes.
- **Blocked mutations:** unsupported builds, unavailable devices, missing
  active sessions, closed sessions, missing items, and in-flight durable
  mutations disable or no-op the requested action.
- **Ignored stale responses:** late SSE, late preview poll, late save, and
  generation-mismatched responses do not mutate a newer session or workspace.

Connection loss never clears a draft workspace, sent handoff batch, claim
state, resolve state, or persisted evidence. Primary UI copy should describe
the safe next action, such as "Connection paused - draft preserved"; raw bridge
diagnostics remain in Details.
```

- [ ] **Step 4: Update troubleshooting**

Add this subsection to `docs/guides/troubleshooting.md` near reconnect or stale preview troubleshooting:

```markdown
### Connection paused while work is in progress

If Studio says `Connection paused - draft preserved`, the bridge or foreground
app state changed while local feedback work still exists. FixThis keeps the
draft, sent handoff batches, claim state, resolve state, and persisted evidence
intact.

Safe automatic recovery includes heartbeat retry, reconnect, preview refresh,
and polling resume. Durable mutations still require a current context or an
explicit confirmation: Save to MCP from a stale preview, dirty-draft session
switch, stale force-save, claim, and resolve.

If a closed session blocks work with `Reopen the session or create a new active
session before changing feedback`, open an active session from the history list
or create a new session before adding, claiming, resolving, or handing off
feedback.
```

- [ ] **Step 5: Run docs contract test**

Run:

```bash
node --test scripts/studioWorkflowIntegration-test.mjs
```

Expected: PASS.

- [ ] **Step 6: Run reliability group**

Run:

```bash
npm run console:reliability:test
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add docs/reference/feedback-console-contract.md docs/guides/troubleshooting.md scripts/studioWorkflowIntegration-test.mjs
git commit -m "docs: document Studio workflow exception policy"
```

---

### Task 6: Final Verification And Release-Surface Check

**Files:**
- Verify only; no planned source edits.

- [ ] **Step 1: Run focused console groups**

Run:

```bash
npm run console:reliability:test
node scripts/run-console-tests.mjs canonical draft session preview
```

Expected: PASS.

- [ ] **Step 2: Run focused Kotlin tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackSessionStoreTest" --tests "*FeedbackSessionServiceClaimTest" --tests "*McpProtocolTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Check generated console assets**

Run:

```bash
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 4: Run release-readiness docs checks affected by this work**

Run:

```bash
node scripts/check-release-readiness.mjs
bash scripts/check-docs-cli-surface.sh
```

Expected: PASS.

- [ ] **Step 5: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 6: Inspect final diff**

Run:

```bash
git status --short
git diff --stat HEAD
```

Expected: only files from this plan are modified.

- [ ] **Step 7: Commit any final test or generated-asset adjustments**

If Step 6 shows uncommitted changes from the verification steps, commit them:

```bash
git add fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  fixthis-mcp/src/main/resources/console/console-build-meta.json \
  scripts/console-tests.json \
  scripts/studioWorkflow-test.mjs \
  scripts/studioWorkflowIntegration-test.mjs \
  docs/reference/feedback-console-contract.md \
  docs/guides/troubleshooting.md
git commit -m "test: verify Studio workflow exception policy"
```

Expected: commit succeeds only when there are final verification changes.

---

## Self-Review Notes

- Spec coverage: Tasks 1-3 implement centralized browser workflow decisions, Task 4 covers closed-session claim/resolve mutation safety, Task 5 documents automatic recovery versus confirmation-required mutations, Task 6 verifies affected release surfaces.
- UI preservation: No task changes layout structure or renames existing primary controls.
- Test command policy: New tests are added to existing console groups; no new npm script is introduced.
- Persisted JSON compatibility: No task renames persisted MCP session fields.
- Action enum scope: The spec lists action examples such as
  `heartbeatFailed`, `previewPollReceived`, and `handoffResponseReceived`.
  The first implementation slice intentionally unifies all late async
  results under `ASYNC_RESPONSE_RECEIVED` and uses
  `CONNECTION_STATUS_RECEIVED` for connection updates. Heartbeat and
  preview/poll specific actions are deferred until a concrete decision
  difference between them is required; expanding the enum can happen in
  a later slice without breaking the existing decision shape.
- Generation token boundary: `workflowSnapshotFromState`
  (canonical reducer) uses `state.effectsGeneration` while
  `currentStudioWorkflowSnapshot` (browser adapter) uses
  `pollingUseCases.getState().mutationGeneration`. Both serve
  `decideAsyncResponse` in their own surface; cross-surface generation
  comparisons are intentionally out of scope.
- Closed-session guard scope: Task 4 adds the guard only to
  `claimFeedback` and `resolveFeedback`. Other mutation paths on closed
  sessions are protected at the UI workflow layer (Tasks 1–3); a deeper
  store-side guard is deferred and noted in Task 4.

