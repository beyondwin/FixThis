# v0.7 Studio Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FixThis Studio reliable under repeated real use by finishing push-first preview/session sync, tightening draft/session mutation ownership, and proving the behavior in a real browser.

**Architecture:** Keep the existing browser-console architecture and add small policy/application units where state ownership is currently spread across event, polling, preview, and draft paths. SSE and polling must converge on shared apply contracts; durable mutation responses must be fenced by action identity before they touch visible state.

**Tech Stack:** Kotlin/JVM MCP server, browser JavaScript bundled by `scripts/build-console-assets.mjs`, Node `node:test` console harnesses, Playwright fake-bridge browser proof.

---

## Scope Check

The approved spec is an umbrella roadmap with three independent tracks. This
plan keeps the umbrella shape but makes each task small enough to land alone:

- Tasks 1-3 cover Track A, Push-First Sync Core.
- Tasks 4-5 cover Track B, Draft / Session Safety.
- Tasks 6-7 cover Track C, Browser Proof Evidence and release evidence.
- Task 8 is final verification and documentation alignment.

Do not broaden this into visual redesign, new source matching behavior,
release runtime support, or XML/View/WebView exact targeting.

## File Structure

- Create `fixthis-mcp/src/main/console/previewApplication.js`
  - Pure preview-apply decision helper. No DOM, fetch, timers, storage, or
    global `state` reads.
- Create `scripts/previewApplication-test.mjs`
  - Unit tests for preview decision outcomes.
- Modify `fixthis-mcp/src/main/console/preview.js`
  - Use the preview decision helper inside `applyLivePreview`.
- Modify `fixthis-mcp/src/main/console/events.js`
  - Keep SSE handlers thin and route server payloads through shared apply
    helpers.
- Modify `fixthis-mcp/src/main/console/pollingBrowserAdapter.js`
  - Route session polling results through the same session apply helper used by
    SSE.
- Create `fixthis-mcp/src/main/console/sessionApplication.js`
  - Pure session-apply decision helper for active/mismatched/closed/null
    server sessions.
- Create `scripts/sessionApplication-test.mjs`
  - Unit tests for session apply decisions.
- Modify `fixthis-mcp/src/main/console/domain/consoleReducer.js`
  - Add durable response identity fences for draft save success/failure and
    prompt copy responses.
- Modify `scripts/consoleCanonicalState-test.mjs`
  - Reducer tests for stale response identity.
- Modify `fixthis-mcp/src/main/console/draftUseCases.js`
  - Expand recovery ownership classification for mismatched, deleted, closed,
    pin-only, and written draft states.
- Modify `scripts/draftRecoveryMatrix-test.mjs`
  - Recovery matrix tests for ownership modes and Save to MCP residual policy.
- Modify `scripts/console-fixture/fakeBridgeServer.mjs`
  - Add fixture support for closed/deleted session conflicts, delayed batch
    saves, preview unavailability, and event-stream reconnect proof.
- Modify `scripts/console-browser-reliability.mjs`
  - Add browser proof scenarios required by v0.7.
- Create `scripts/check-v07-release-evidence.mjs`
  - Release-claim guard for v0.7 Studio Reliability evidence.
- Modify `package.json`
  - Add `release:v07:evidence:test`.
- Modify `docs/contributing/release-readiness.md`
  - Document v0.7 evidence requirements.
- (Already updated 2026-05-18) `docs/superpowers/specs/2026-05-18-v07-studio-reliability-umbrella-design.md`
  - Open Decisions: documents the dedicated `release:v07:evidence:test`
    choice; Audit Notes section pins the source baseline this plan refactors.

## Task 1: Add Pure Preview Apply Decision

**Files:**
- Create: `fixthis-mcp/src/main/console/previewApplication.js`
- Create: `scripts/previewApplication-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write the failing preview decision tests**

Create `scripts/previewApplication-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/previewApplication.js'), 'utf8');
const factory = new Function(`${source}; return { livePreviewApplyDecision };`);
const m = factory();

test('drops missing preview and active draft state', () => {
  assert.deepEqual(m.livePreviewApplyDecision({ preview: null }), {
    accepted: false,
    kind: 'ignore',
    reason: 'missing_preview',
  });
  assert.deepEqual(m.livePreviewApplyDecision({
    preview: { previewId: 'p1' },
    draftActive: true,
  }), {
    accepted: false,
    kind: 'ignore',
    reason: 'draft_active',
  });
});

test('drops mismatched session before any visible preview mutation', () => {
  assert.deepEqual(m.livePreviewApplyDecision({
    preview: { previewId: 'old' },
    ownerSessionId: 'session-old',
    activeSessionId: 'session-new',
  }), {
    accepted: false,
    kind: 'ignore',
    reason: 'stale_session',
  });
});

test('preview unavailable is readiness-only and preserves current preview', () => {
  assert.deepEqual(m.livePreviewApplyDecision({
    preview: { previewId: 'unavailable', previewAvailable: false },
    ownerSessionId: 'session-1',
    activeSessionId: 'session-1',
    currentPreview: { previewId: 'current' },
  }), {
    accepted: true,
    kind: 'readiness_only',
    reason: 'preview_unavailable',
  });
});

test('system UI obstruction marks existing preview stale', () => {
  assert.deepEqual(m.livePreviewApplyDecision({
    preview: {
      previewId: 'blocked',
      screen: { systemUiVisible: true, systemUiKind: 'keyguard' },
    },
    currentPreview: { previewId: 'current' },
  }), {
    accepted: true,
    kind: 'mark_stale',
    reason: 'system_ui',
    obstructedBy: 'keyguard',
  });
});

test('available preview replaces current preview', () => {
  assert.deepEqual(m.livePreviewApplyDecision({
    preview: { previewId: 'ready', previewAvailable: true },
    ownerSessionId: 'session-1',
    activeSessionId: 'session-1',
  }), {
    accepted: true,
    kind: 'replace',
    reason: 'ready',
  });
});
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
node --test scripts/previewApplication-test.mjs
```

Expected: FAIL with `ENOENT` for `previewApplication.js` or
`livePreviewApplyDecision is not defined`.

- [ ] **Step 3: Add the pure preview application helper**

Create `fixthis-mcp/src/main/console/previewApplication.js`:

```js
// @requires (none)
// previewApplication.js - pure live-preview apply policy.

function livePreviewApplyDecision({
  preview = null,
  ownerSessionId = null,
  activeSessionId = null,
  draftActive = false,
  currentPreview = null,
} = {}) {
  if (!preview) {
    return Object.freeze({ accepted: false, kind: 'ignore', reason: 'missing_preview' });
  }
  if (draftActive) {
    return Object.freeze({ accepted: false, kind: 'ignore', reason: 'draft_active' });
  }
  if (ownerSessionId && ownerSessionId !== activeSessionId) {
    return Object.freeze({ accepted: false, kind: 'ignore', reason: 'stale_session' });
  }
  if (preview.previewAvailable === false) {
    return Object.freeze({ accepted: true, kind: 'readiness_only', reason: 'preview_unavailable' });
  }
  if (preview.screen?.systemUiVisible && currentPreview) {
    return Object.freeze({
      accepted: true,
      kind: 'mark_stale',
      reason: 'system_ui',
      obstructedBy: preview.screen.systemUiKind || 'system_ui',
    });
  }
  return Object.freeze({ accepted: true, kind: 'replace', reason: 'ready' });
}
```

- [ ] **Step 4: Register the test in the preview console group**

In `scripts/console-tests.json`, add the new test to the `preview` array:

```json
"scripts/previewApplication-test.mjs"
```

Keep the JSON valid and keep the existing preview tests in place.

- [ ] **Step 5: Run focused verification**

Run:

```bash
node --test scripts/previewApplication-test.mjs
npm run console:preview:test
```

Expected: both commands PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add fixthis-mcp/src/main/console/previewApplication.js scripts/previewApplication-test.mjs scripts/console-tests.json
git commit -m "feat(console): add preview application policy"
```

## Task 2: Route SSE And Polling Preview Updates Through One Decision Path

**Files:**
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/main/console/events.js`
- Modify: `scripts/sessionMismatchIgnore-test.mjs`
- Modify: `scripts/consoleEvents-test.mjs`
- Modify: `scripts/studioReliabilityContract-test.mjs`

- [ ] **Step 1: Add failing source contract assertions**

In `scripts/sessionMismatchIgnore-test.mjs`, update the
`applyLivePreview surfaces preview-unavailable readiness without replacing preview state`
test to require the shared decision helper:

```js
test('applyLivePreview delegates preview state ownership to preview application policy', () => {
  const previewApply = extractFunction(previewSource, 'function applyLivePreview');

  assert.match(previewApply, /livePreviewApplyDecision\(\{/);
  assert.match(previewApply, /decision\.kind === 'readiness_only'/);
  assert.match(previewApply, /decision\.kind === 'mark_stale'/);
  assert.match(previewApply, /decision\.kind === 'replace'/);
  assert.ok(
    previewApply.indexOf("decision.kind === 'readiness_only'") < previewApply.indexOf('setConsolePreview({'),
    'readiness-only preview must be handled before replacing state.preview',
  );
  assert.ok(
    previewApply.indexOf("decision.kind === 'readiness_only'") < previewApply.indexOf('previewUseCases.applyReady('),
    'preview FSM must not accept unavailable payloads',
  );
});
```

Keep the existing stale-session gate test, but change it to assert the helper
and the diagnostic gates both remain present:

```js
test('SSE and preview polling share applyLivePreview stale-session gate', () => {
  const previewApply = extractFunction(previewSource, 'function applyLivePreview');

  assert.match(previewApply, /livePreviewApplyDecision\(\{/);
  assert.match(previewApply, /decision\.reason === 'stale_session'/);
  assert.match(previewApply, /dropStaleSse\(\{ sessionId: ownerSessionId \}, activeSessionId\)/);
  assert.match(previewApply, /dropStalePreviewPoll\(\{ sessionId: ownerSessionId \}, activeSessionId\)/);
});
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```bash
node --test scripts/sessionMismatchIgnore-test.mjs scripts/consoleEvents-test.mjs scripts/studioReliabilityContract-test.mjs
```

Expected: FAIL because `applyLivePreview` has not yet called
`livePreviewApplyDecision`.

- [ ] **Step 3: Update `preview.js` to use the decision helper**

In `fixthis-mcp/src/main/console/preview.js`, replace the body of
`applyLivePreview(preview, options = {})` with this structure:

```js
function applyLivePreview(preview, options = {}) {
  const activeSessionId = state.session?.sessionId || null;
  const ownerSessionId = options.sessionId || preview?.sessionId || null;
  const decision = livePreviewApplyDecision({
    preview,
    ownerSessionId,
    activeSessionId,
    draftActive: Boolean(draftFlow()),
    currentPreview: state.preview,
  });
  if (!decision.accepted) {
    if (decision.reason === 'stale_session') {
      if (options.source === 'sse') {
        dropStaleSse({ sessionId: ownerSessionId }, activeSessionId);
      } else {
        dropStalePreviewPoll({ sessionId: ownerSessionId }, activeSessionId);
      }
    }
    return false;
  }
  applyPreviewReadinessToConnectionCard(preview);
  if (decision.kind === 'readiness_only') {
    renderPreviewOnly();
    return true;
  }
  if (decision.kind === 'mark_stale') {
    state.preview.stale = true;
    state.preview.obstructedBySystemUi = decision.obstructedBy;
    markPreviewStale(true);
    renderPreviewOnly();
    return true;
  }
  previewUseCases.applyReady(preview);
  setConsolePreview({
    ...preview,
    activity: state.connection?.availability?.activity ?? null,
    frozenAtEpochMillis: Date.now(),
    stale: false,
  });
  if (userConnectionState(state.connection.current) === 'ready') markPreviewStale(false);
  renderPreviewOnly();
  return true;
}
```

Ensure the file header includes the new dependency:

```js
// @requires state.js, api.js, draftWorkspace.js, previewApplication.js, previewPoll.js, sse.js
```

- [ ] **Step 4: Keep `events.js` preview handler thin**

Confirm the `preview-ready` handler remains this shape:

```js
on('preview-ready', (data) => {
  if (!data.preview || draftFlow()) return;
  applyLivePreview(data.preview, {
    source: 'sse',
    sessionId: data.sessionId,
  });
});
```

Do not add `setConsolePreview`, `previewUseCases.applyReady`, or
`applyPreviewReadinessToConnectionCard` directly to `events.js`.

- [ ] **Step 5: Run focused preview/session tests**

Run:

```bash
node --test scripts/previewApplication-test.mjs scripts/sessionMismatchIgnore-test.mjs scripts/consoleEvents-test.mjs scripts/studioReliabilityContract-test.mjs
npm run console:preview:test
npm run console:reliability:test
```

Expected: all commands PASS.

- [ ] **Step 6: Rebuild checked-in console assets**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: bundle generation succeeds and `--check` PASS.

- [ ] **Step 7: Commit Task 2**

```bash
git add fixthis-mcp/src/main/console/preview.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json scripts/sessionMismatchIgnore-test.mjs scripts/consoleEvents-test.mjs scripts/studioReliabilityContract-test.mjs
git commit -m "refactor(console): route preview updates through shared policy"
```

## Task 3: Add Shared Session Apply Decisions For SSE And Polling

**Files:**
- Create: `fixthis-mcp/src/main/console/sessionApplication.js`
- Create: `scripts/sessionApplication-test.mjs`
- Modify: `fixthis-mcp/src/main/console/events.js`
- Modify: `fixthis-mcp/src/main/console/pollingBrowserAdapter.js`
- Modify: `scripts/console-tests.json`
- Modify: `scripts/consoleEvents-test.mjs`
- Modify: `scripts/studioReliabilityContract-test.mjs`

- [ ] **Step 1: Write failing session application tests**

Create `scripts/sessionApplication-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/sessionApplication.js'), 'utf8');
const factory = new Function(`${source}; return { serverSessionApplyDecision };`);
const m = factory();

test('null session clears only when a session is currently displayed', () => {
  assert.deepEqual(m.serverSessionApplyDecision({
    session: null,
    eventSessionId: null,
    displayedSessionId: 'session-1',
  }), { accepted: true, kind: 'clear', reason: 'server_session_missing' });
  assert.deepEqual(m.serverSessionApplyDecision({
    session: null,
    eventSessionId: null,
    displayedSessionId: null,
  }), { accepted: false, kind: 'ignore', reason: 'no_displayed_session' });
});

test('mismatched session asks caller to refresh summaries without replacing detail', () => {
  assert.deepEqual(m.serverSessionApplyDecision({
    session: { sessionId: 'session-old', status: 'active' },
    eventSessionId: 'session-old',
    displayedSessionId: 'session-new',
  }), { accepted: false, kind: 'refresh_summaries', reason: 'stale_session' });
});

test('closed session clears the displayed session', () => {
  assert.deepEqual(m.serverSessionApplyDecision({
    session: { sessionId: 'session-1', status: 'closed' },
    eventSessionId: 'session-1',
    displayedSessionId: 'session-1',
  }), { accepted: true, kind: 'clear', reason: 'closed_session' });
});

test('matching active session applies normally', () => {
  assert.deepEqual(m.serverSessionApplyDecision({
    session: { sessionId: 'session-1', status: 'active' },
    eventSessionId: 'session-1',
    displayedSessionId: 'session-1',
  }), { accepted: true, kind: 'apply', reason: 'active_session' });
});
```

- [ ] **Step 2: Run the new test and verify failure**

Run:

```bash
node --test scripts/sessionApplication-test.mjs
```

Expected: FAIL with `ENOENT` for `sessionApplication.js` or
`serverSessionApplyDecision is not defined`.

- [ ] **Step 3: Add the pure session application helper**

Create `fixthis-mcp/src/main/console/sessionApplication.js`:

```js
// @requires (none)
// sessionApplication.js - pure server-session apply policy.

function sessionStatusValue(session) {
  return String(session?.status || '').toLowerCase();
}

function serverSessionApplyDecision({
  session = null,
  eventSessionId = null,
  displayedSessionId = null,
} = {}) {
  if (!session) {
    if (displayedSessionId) {
      return Object.freeze({ accepted: true, kind: 'clear', reason: 'server_session_missing' });
    }
    return Object.freeze({ accepted: false, kind: 'ignore', reason: 'no_displayed_session' });
  }
  const incomingSessionId = eventSessionId || session.sessionId || null;
  if (displayedSessionId && incomingSessionId && incomingSessionId !== displayedSessionId) {
    return Object.freeze({ accepted: false, kind: 'refresh_summaries', reason: 'stale_session' });
  }
  if (session.deleted === true || sessionStatusValue(session) === 'deleted' || sessionStatusValue(session) === 'missing') {
    return Object.freeze({ accepted: true, kind: 'clear', reason: 'deleted_session' });
  }
  if (sessionStatusValue(session) === 'closed') {
    return Object.freeze({ accepted: true, kind: 'clear', reason: 'closed_session' });
  }
  return Object.freeze({ accepted: true, kind: 'apply', reason: 'active_session' });
}
```

- [ ] **Step 4: Route `events.js` session handlers through the helper**

In `events.js`, keep `applySessionFromServer(session)` but make the event
handler decision-driven:

```js
function applyServerSessionDecision(session, eventSessionId = session?.sessionId || null) {
  const decision = serverSessionApplyDecision({
    session,
    eventSessionId,
    displayedSessionId: displayedSessionId(),
  });
  if (decision.kind === 'refresh_summaries') {
    refreshSessions().catch(showError);
    return false;
  }
  if (decision.kind === 'clear') {
    clearDisplayedSessionState();
    render();
    return false;
  }
  if (decision.kind === 'apply') {
    applySessionFromServer(session);
    loadPendingRecoveryForCurrentSession();
    render();
    return true;
  }
  return false;
}
```

Then use it in `snapshot` and `session-updated`:

```js
on('snapshot', (data) => {
  if ('session' in data) applyServerSessionDecision(data.session || null, data.session?.sessionId || null);
  if (data.sessions?.sessions) renderSessionsListFromPayload(data.sessions.sessions);
  if (data.devices) renderDeviceList(data.devices);
  if (data.connection) applyConnectionStatus(data.connection);
  render();
});

on('session-updated', (data) => {
  if (!data.session) return;
  applyServerSessionDecision(data.session, data.sessionId);
});
```

Add the dependency:

```js
// @requires state.js, connection.js, devices.js, preview.js, history.js, rendering.js, sessionApplication.js, sessions-polling.js, sse.js
```

- [ ] **Step 5: Route polling session responses through the helper**

In `pollingBrowserAdapter.js`, replace the direct `fresh` branch with:

```js
const decision = serverSessionApplyDecision({
  session: fresh,
  eventSessionId: fresh?.sessionId || null,
  displayedSessionId: activeDisplayedSessionId,
});
if (decision.kind === 'apply') {
  mergeSessionIntoState(fresh);
  renderInspectorRegion();
} else if (decision.kind === 'clear') {
  clearDisplayedSessionState();
  if (fresh && decision.reason !== 'closed_session' && decision.reason !== 'deleted_session') {
    mergeSessionIntoState(fresh);
  }
  render();
} else if (decision.kind === 'refresh_summaries') {
  // Stale session response (user switched displayed session mid-poll).
  // Keep the active detail pane untouched; sessions list refresh happens
  // through the next sessions ETag tick, so no action is required here.
}
```

The explicit no-op branch is intentional: the existing else branch in the
previous code would `clearDisplayedSessionState()` and merge the stale `fresh`
session, switching the user's view to whatever the slow poll returned. The
v0.7 contract requires that late session responses for a no-longer-displayed
session do not mutate the active detail pane.

Add the dependency:

```js
// @requires pollingFsm.js, pollingUseCases.js, state.js, history.js, rendering.js, sessionApplication.js
```

- [ ] **Step 6: Register the test in the session console group**

In `scripts/console-tests.json`, add:

```json
"scripts/sessionApplication-test.mjs"
```

to the `session` array.

- [ ] **Step 6.5: Update obsolete source-shape assertions for the new decision path**

The pre-Task-3 source-shape assertions encode literal strings (e.g.
`data.sessionId === state.session?.sessionId`, `isClosedSession(data.session)`,
`fresh && fresh.sessionId === activeDisplayedSessionId && !isClosedSession(fresh)`)
that move into the new decision helpers. Update them so the contract tests stay
green and still pin the behavior:

In `scripts/consoleEvents-test.mjs`, replace the three legacy tests with:

```js
test('session-updated event delegates ownership to serverSessionApplyDecision', () => {
  const start = body(source, 'function startConsoleEvents()');
  const handler = start.slice(start.indexOf("on('session-updated'"));
  assert.match(handler, /applyServerSessionDecision\(data\.session, data\.sessionId\)/);
  assert.doesNotMatch(handler, /setConsoleSession\(/);
});

test('session-updated closed/deleted event delegates clear to the decision helper', () => {
  const start = body(source, 'function startConsoleEvents()');
  assert.match(start, /function applyServerSessionDecision\(/);
  assert.match(start, /decision\.kind === 'clear'/);
  assert.match(start, /clearDisplayedSessionState\(\)/);
});

test('snapshot null session clears active draft and preview state via decision helper', () => {
  const start = body(source, 'function startConsoleEvents()');
  const handler = start.slice(start.indexOf("on('snapshot'"));
  assert.match(handler, /applyServerSessionDecision\(data\.session \|\| null, data\.session\?\.sessionId \|\| null\)/);
});
```

In `scripts/studioReliabilityContract-test.mjs`, replace the polling assertion
block with one that pins the new decision-driven shape:

```js
test('session polling routes responses through serverSessionApplyDecision', () => {
  assert.match(polling, /const activeDisplayedSessionId = displayedSessionId\(\);/);
  assert.match(polling, /serverSessionApplyDecision\(\{/);
  assert.match(polling, /decision\.kind === 'apply'/);
  assert.match(polling, /decision\.kind === 'clear'/);
  assert.match(polling, /decision\.kind === 'refresh_summaries'/);
  assert.match(polling, /clearDisplayedSessionState\(\);/);
});
```

- [ ] **Step 7: Run focused session verification**

Run:

```bash
node --test scripts/sessionApplication-test.mjs scripts/consoleEvents-test.mjs
npm run console:session:test
npm run console:reliability:test
```

Expected: all commands PASS.

- [ ] **Step 8: Rebuild checked-in console assets**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: bundle generation succeeds and `--check` PASS.

- [ ] **Step 9: Commit Task 3**

```bash
git add fixthis-mcp/src/main/console/sessionApplication.js fixthis-mcp/src/main/console/events.js fixthis-mcp/src/main/console/pollingBrowserAdapter.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json scripts/sessionApplication-test.mjs scripts/console-tests.json scripts/consoleEvents-test.mjs scripts/studioReliabilityContract-test.mjs
git commit -m "refactor(console): share session apply policy"
```

## Task 4: Fence Durable Mutation Responses By Action Identity

**Files:**
- Modify: `fixthis-mcp/src/main/console/domain/consoleReducer.js`
- Modify: `scripts/consoleCanonicalState-test.mjs`
- Modify: `scripts/sessionMismatchIgnore-test.mjs`
- Modify: `scripts/studioReliabilityContract-test.mjs`

- [ ] **Step 1: Add failing reducer tests for stale session/workspace responses**

Append these tests to `scripts/consoleCanonicalState-test.mjs`:

```js
test('draft save success ignores mismatched source session even when generation matches', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [session('session-a')] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview('a') }).state;
  state = m.reduceConsoleAppState(state, { type: 'SAVE_TO_MCP_CLICKED' }).state;

  const result = m.reduceConsoleAppState(state, {
    type: 'DRAFT_SAVE_SUCCEEDED',
    requestId: 'save-draft-1',
    sessionId: 'session-b',
    workspaceId: state.workspace.context.workspaceId,
    generation: state.effectsGeneration,
    session: session('session-b'),
  });

  assert.equal(result.state.activeSessionId, 'session-a');
  assert.equal(result.state.workspace.kind, m.WorkspaceKind.DRAFT);
  assert.equal(result.state.promptAction.inFlight, true);
});

test('draft save failure ignores mismatched source session even when generation matches', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [session('session-a')] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview('a') }).state;
  state = m.reduceConsoleAppState(state, { type: 'SAVE_TO_MCP_CLICKED' }).state;

  const result = m.reduceConsoleAppState(state, {
    type: 'DRAFT_SAVE_FAILED',
    requestId: 'save-draft-1',
    sessionId: 'session-b',
    workspaceId: state.workspace.context.workspaceId,
    generation: state.effectsGeneration,
    error: 'wrong session failed',
  });

  assert.equal(result.state.promptAction.inFlight, true);
  assert.equal(result.state.status, null);
});
```

- [ ] **Step 2: Run the reducer tests and verify failure**

Run:

```bash
node --test scripts/consoleCanonicalState-test.mjs
```

Expected: FAIL because `reduceDraftSaveSucceeded` and
`reduceDraftSaveFailed` do not yet check `event.sessionId` against the draft
workspace context.

- [ ] **Step 3: Add a reducer helper for draft response ownership**

In `consoleReducer.js`, add this helper near the draft save reducers:

```js
function draftResponseMatchesWorkspace(state, event) {
  if (!isDraftWorkspace(state.workspace)) return false;
  if (event.workspaceId && event.workspaceId !== state.workspace.context.workspaceId) return false;
  if (event.sessionId && event.sessionId !== state.workspace.context.sessionId) return false;
  return true;
}
```

- [ ] **Step 4: Use the helper in save success/failure reducers**

Change `reduceDraftSaveSucceeded`:

```js
function reduceDraftSaveSucceeded(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  if (!draftResponseMatchesWorkspace(state, event)) return { state, effects: [] };
  const nextSessionId = state.pendingBoundary?.targetSessionId || event.targetSessionId || event.sessionId || state.activeSessionId;
  let next = event.session ? upsertSession(state, event.session) : state;
  next = replaceConsoleState(next, {
    activeSessionId: nextSessionId,
    workspace: livePreviewWorkspace(nextSessionId, null),
    pendingBoundary: null,
    promptAction: Object.freeze({ inFlight: false }),
    tool: Object.freeze({ mode: 'select' }),
  });
  return { state: next, effects: [] };
}
```

Change `reduceDraftSaveFailed`:

```js
function reduceDraftSaveFailed(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  if (!draftResponseMatchesWorkspace(state, event)) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      promptAction: Object.freeze({ inFlight: false }),
      status: Object.freeze({ message: event.error || 'Could not save draft.', variant: 'error', assertive: true }),
    }),
    effects: [],
  };
}
```

- [ ] **Step 4.5: Fence prompt-copy responses by active session**

The File Structure section promises identity fences for prompt copy responses,
not only draft save. Update `reducePromptCopySucceeded` and
`reducePromptCopyFailed` to drop responses for a session/generation that is no
longer active:

```js
function reducePromptCopySucceeded(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  if (event.sessionId && event.sessionId !== state.activeSessionId) return { state, effects: [] };
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
  if (event.sessionId && event.sessionId !== state.activeSessionId) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      promptAction: Object.freeze({ inFlight: false }),
      status: Object.freeze({ message: event.error || 'Could not copy prompt.', variant: 'error', assertive: true }),
    }),
    effects: [],
  };
}
```

Append a matching reducer test to `scripts/consoleCanonicalState-test.mjs`:

```js
test('prompt copy success ignores mismatched source session even when generation matches', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [session('session-a')] });
  state = m.reduceConsoleAppState(state, { type: 'COPY_PROMPT_CLICKED' }).state;

  const result = m.reduceConsoleAppState(state, {
    type: 'PROMPT_COPY_SUCCEEDED',
    requestId: 'copy-prompt-1',
    sessionId: 'session-b',
    generation: state.effectsGeneration,
  });

  assert.equal(result.state.promptAction.inFlight, true);
  assert.equal(result.state.status, null);
});
```

- [ ] **Step 4.6: Update obsolete source-shape assertions that pinned inline checks**

Two existing contract tests assert the literal inline workspace fence that
Task 4 moves into `draftResponseMatchesWorkspace`. Update them to pin the new
helper-based shape instead of the literal pre-helper expression:

In `scripts/sessionMismatchIgnore-test.mjs` replace:

```js
assert.match(save, /event\.workspaceId !== state\.workspace\.context\.workspaceId/);
```

with:

```js
assert.match(save, /draftResponseMatchesWorkspace\(state, event\)/);
```

In `scripts/studioReliabilityContract-test.mjs` replace:

```js
assert.match(save, /!isDraftWorkspace\(state\.workspace\)/);
assert.match(save, /event\.workspaceId !== state\.workspace\.context\.workspaceId/);
```

with:

```js
assert.match(save, /draftResponseMatchesWorkspace\(state, event\)/);
```

and add a body test for the helper itself so the workspace/session checks
remain pinned:

```js
test('draftResponseMatchesWorkspace pins workspace and session identity', () => {
  const helper = body(reducer, 'function draftResponseMatchesWorkspace(state, event)');
  assert.match(helper, /isDraftWorkspace\(state\.workspace\)/);
  assert.match(helper, /event\.workspaceId !== state\.workspace\.context\.workspaceId/);
  assert.match(helper, /event\.sessionId !== state\.workspace\.context\.sessionId/);
});
```

- [ ] **Step 5: Run focused reducer tests**

Run:

```bash
node --test scripts/consoleCanonicalState-test.mjs
node scripts/run-console-tests.mjs canonical
```

Expected: both commands PASS.

- [ ] **Step 6: Rebuild checked-in console assets**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: bundle generation succeeds and `--check` PASS.

- [ ] **Step 7: Commit Task 4**

```bash
git add fixthis-mcp/src/main/console/domain/consoleReducer.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json scripts/consoleCanonicalState-test.mjs scripts/sessionMismatchIgnore-test.mjs scripts/studioReliabilityContract-test.mjs
git commit -m "fix(console): fence durable mutation responses by session"
```

## Task 5: Expand Draft Recovery Ownership And Residual Policy Tests

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftUseCases.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/pendingRecoveryUi.js`
- Modify: `scripts/draftRecoveryMatrix-test.mjs`
- Modify: `scripts/boundaryDialogVariants-test.mjs`

- [ ] **Step 1: Add failing recovery ownership tests**

Append to `scripts/draftRecoveryMatrix-test.mjs`:

```js
test('draftRecoveryOwnership classifies mismatched recovery as recapture-only', () => {
  const runtime = loadRuntime();
  const recovery = runtime.draftWorkspaceRecoveryEnvelope(workspaceWithItems([
    { draftItemId: 'draft-1', comment: 'Recover me', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]));

  const ownership = runtime.draftRecoveryOwnership(recovery, { status: 'active', sessionId: 'session-1' }, { activeSessionId: 'session-2' });

  assert.equal(ownership.mode, 'mismatched');
  assert.equal(ownership.canResume, false);
  assert.equal(ownership.canRecapture, true);
  assert.equal(ownership.shouldAutoRestore, false);
});

test('Save to MCP completion discards pin-only residuals in the action scope', async () => {
  const runtime = loadRuntime();
  const activeWorkspace = workspaceWithItems([
    { draftItemId: 'draft-written', comment: 'Save this', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
    { draftItemId: 'draft-pin-only', comment: '', targetType: 'area', bounds: { left: 20, top: 20, right: 40, bottom: 40 } },
  ]);
  const p = ports('save');

  const result = await runtime.resolveLifecycleBoundary({
    action: 'save-to-mcp',
    targetSessionId: 'session-1',
    activeWorkspace,
    pendingRecovery: null,
    ports: p,
  });

  assert.equal(result.outcome, 'continue');
  assert.equal(result.savedSession.sessionId, 'session-1');
  assert.deepEqual(p.calls.map(call => call[0]), ['deleteWorkspace']);
});
```

- [ ] **Step 2: Run draft tests and verify failure**

Run:

```bash
node --test scripts/draftRecoveryMatrix-test.mjs
```

Expected: FAIL because `draftRecoveryOwnership` does not yet accept the
`activeSessionId` option.

- [ ] **Step 3: Extend recovery ownership classification**

Update `draftRecoveryOwnership` in `draftUseCases.js`:

```js
function draftRecoveryOwnership(recovery, session = null, options = {}) {
  const total = recoveryItems(recovery).length;
  const commented = hasCommentedRecovery(recovery);
  const status = String(session?.status || '').toLowerCase();
  const recoverySession = recoverySessionId(recovery);
  const activeSessionId = options?.activeSessionId || null;
  if (!total) {
    return Object.freeze({
      mode: 'none',
      total,
      commented,
      canResume: false,
      canRecapture: false,
      shouldAutoRestore: false,
    });
  }
  if (session?.deleted === true || status === 'deleted' || status === 'missing') {
    return Object.freeze({
      mode: 'deleted',
      total,
      commented,
      canResume: false,
      canRecapture: false,
      shouldAutoRestore: false,
    });
  }
  if (status === 'closed') {
    return Object.freeze({
      mode: 'closed',
      total,
      commented,
      canResume: false,
      canRecapture: true,
      shouldAutoRestore: false,
    });
  }
  if (activeSessionId && recoverySession && recoverySession !== activeSessionId) {
    return Object.freeze({
      mode: 'mismatched',
      total,
      commented,
      canResume: false,
      canRecapture: true,
      shouldAutoRestore: false,
    });
  }
  return Object.freeze({
    mode: commented ? 'commented' : 'pin-only',
    total,
    commented,
    canResume: true,
    canRecapture: true,
    shouldAutoRestore: !commented,
  });
}
```

- [ ] **Step 4: Keep residual policy explicit in boundary save**

Confirm `persistDraftWorkspaceForBoundary` filters written items:

```js
const writtenItems = items.filter(draftItemHasWrittenComment);
```

Confirm Save to MCP completion clears the browser workspace through
`persistDraftWorkspace`:

```js
ports.storage?.deleteWorkspace?.(started.context.sessionId, started.workspaceId);
```

If either line is missing, add it exactly.

- [ ] **Step 4.5: Wire active-session context into recovery ownership callers**

The new `mismatched` classification only triggers when callers pass
`activeSessionId`. Update the two production callers so cross-session recovery
banners surface the recapture-only state instead of silently auto-restoring
into the wrong session.

In `fixthis-mcp/src/main/console/main.js`, change:

```js
const ownership = draftRecoveryOwnership(restored, state.session);
```

to:

```js
const ownership = draftRecoveryOwnership(restored, state.session, {
  activeSessionId: state.session?.sessionId || null,
});
```

In `fixthis-mcp/src/main/console/pendingRecoveryUi.js`, change both call sites
that look like:

```js
const ownership = pendingRecovery?.recoveryOwnership || draftRecoveryOwnership(pendingRecovery, state.session);
```

to:

```js
const ownership = pendingRecovery?.recoveryOwnership || draftRecoveryOwnership(pendingRecovery, state.session, {
  activeSessionId: state.session?.sessionId || null,
});
```

Note: in `main.js` the recovery is loaded for the current session, so the
`mismatched` branch will not normally fire from that call site; the parameter
is passed for forward compatibility so future cross-session recovery loaders
get the correct ownership without further changes.

- [ ] **Step 5: Run draft and boundary tests**

Run:

```bash
node --test scripts/draftRecoveryMatrix-test.mjs scripts/draftUseCases-test.mjs scripts/boundaryDialogVariants-test.mjs
npm run console:draft:test
```

Expected: all commands PASS.

- [ ] **Step 6: Rebuild checked-in console assets**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: bundle generation succeeds and `--check` PASS.

- [ ] **Step 7: Commit Task 5**

```bash
git add fixthis-mcp/src/main/console/draftUseCases.js fixthis-mcp/src/main/console/main.js fixthis-mcp/src/main/console/pendingRecoveryUi.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json scripts/draftRecoveryMatrix-test.mjs scripts/boundaryDialogVariants-test.mjs
git commit -m "fix(console): classify draft recovery ownership"
```

## Task 6: Expand Browser Reliability Proof And Fixture Support

**Files:**
- Modify: `scripts/console-fixture/fakeBridgeServer.mjs`
- Modify: `scripts/console-browser-reliability.mjs`
- Modify: `scripts/console-fixture/fakeBridgeServer-test.mjs`

- [ ] **Step 1: Add failing fixture tests for closed conflict and preview unavailable**

Append to `scripts/console-fixture/fakeBridgeServer-test.mjs`:

```js
test('fake bridge can reject the next batch with a session_closed conflict', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    fixture.rejectNextBatchForSessionClosed();
    const response = await fetch(fixture.url + '/api/items/batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'If-Match': '*' },
      body: JSON.stringify({
        sessionId: 'session-1',
        previewId: 'preview-1',
        workspaceId: 'workspace-1',
        screen: { screenId: 'screen-1' },
        items: [{ draftItemId: 'draft-1', targetType: 'area', bounds: { left: 1, top: 1, right: 2, bottom: 2 }, comment: 'x' }],
      }),
    });
    const body = await response.json();
    assert.equal(response.status, 409);
    assert.equal(body.error, 'session_closed');
  } finally {
    await fixture.close();
  }
});

test('fake bridge can emit capture unavailable preview payload', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    const preview = fixture.emitPreviewReady('session-1', {
      previewId: 'unavailable',
      previewAvailable: false,
      readiness: { state: 'CAPTURE_UNAVAILABLE', nextAction: 'Retry capture' },
    });
    assert.equal(preview.previewAvailable, false);
  } finally {
    await fixture.close();
  }
});
```

- [ ] **Step 2: Run fixture tests and verify failure**

Run:

```bash
node --test scripts/console-fixture/fakeBridgeServer-test.mjs
```

Expected: FAIL because `rejectNextBatchForSessionClosed` does not exist.

- [ ] **Step 3: Add fixture support**

In `scripts/console-fixture/fakeBridgeServer.mjs`, add state near
`nextFingerprintMismatch`:

```js
let nextSessionClosedConflict = false;
```

Inside the `/api/items/batch` handler, before fingerprint mismatch handling:

```js
if (nextSessionClosedConflict) {
  nextSessionClosedConflict = false;
  json(res, {
    error: 'session_closed',
    message: 'Session is closed and cannot be mutated.',
    action: 'recapture_or_open_active_session',
  }, 409);
  return;
}
```

Expose the fixture method:

```js
rejectNextBatchForSessionClosed: () => {
  nextSessionClosedConflict = true;
},
```

- [ ] **Step 4: Add browser proof for closed conflict and preview unavailable**

In `scripts/console-browser-reliability.mjs`, add:

```js
async function testClosedSessionConflictSurfaces() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    await createDraftAnnotation(page, 'Closed sessions must not mutate');
    fixture.rejectNextBatchForSessionClosed();

    await page.click('#sendAgentButton');
    await page.waitForFunction(
      () => {
        const text = document.body.innerText || '';
        return text.includes('closed') || text.includes('recapture') || text.includes('Could not save draft');
      },
      null,
      { timeout: 8000 },
    );
    assert.equal(fixture.currentSession().items.length, 0);
  });
}

async function testCaptureUnavailableDoesNotReplacePreview() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    await page.waitForFunction(
      () => Boolean(window.FixThisConsoleDebug.getState().preview?.previewId),
      null,
      { timeout: 8000 },
    );
    const before = await page.evaluate(() => window.FixThisConsoleDebug.getState().preview.previewId);
    fixture.emitPreviewReady('session-1', {
      previewId: 'unavailable',
      previewAvailable: false,
      readiness: {
        state: 'CAPTURE_UNAVAILABLE',
        cause: 'Screenshot unavailable.',
        verify: 'Retry capture.',
        nextAction: 'Retry capture',
      },
    });

    await page.waitForFunction(
      () => document.getElementById('connectionReadiness')?.dataset.state === 'CAPTURE_UNAVAILABLE',
      null,
      { timeout: 8000 },
    );
    const after = await page.evaluate(() => window.FixThisConsoleDebug.getState().preview.previewId);
    assert.equal(after, before);
  });
}
```

Call both from `run()`:

```js
await testClosedSessionConflictSurfaces();
await testCaptureUnavailableDoesNotReplacePreview();
```

- [ ] **Step 5: Run browser reliability proof**

Run:

```bash
node --test scripts/console-fixture/fakeBridgeServer-test.mjs
npm run console:browser:reliability
```

Expected: both commands PASS.

- [ ] **Step 6: Commit Task 6**

```bash
git add scripts/console-fixture/fakeBridgeServer.mjs scripts/console-fixture/fakeBridgeServer-test.mjs scripts/console-browser-reliability.mjs
git commit -m "test(console): expand browser reliability proof"
```

## Task 7: Add v0.7 Release Evidence Guard

**Files:**
- Create: `scripts/check-v07-release-evidence.mjs`
- Create: `scripts/v07-release-claims-test.mjs`
- Modify: `package.json`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/superpowers/specs/2026-05-18-v07-studio-reliability-umbrella-design.md`

- [ ] **Step 1: Write failing release claim tests**

Create `scripts/v07-release-claims-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

test('package exposes v0.7 evidence script', () => {
  const pkg = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'));
  assert.equal(pkg.scripts['release:v07:evidence:test'], 'node scripts/check-v07-release-evidence.mjs && node --test scripts/v07-release-claims-test.mjs');
});

test('release readiness documents v0.7 Studio Reliability evidence commands', () => {
  const doc = readFileSync(resolve(root, 'docs/contributing/release-readiness.md'), 'utf8');
  for (const command of [
    'npm run console:reliability:test',
    'npm run console:session:test',
    'npm run console:draft:test',
    'npm run console:browser:reliability',
  ]) {
    assert.match(doc, new RegExp(command.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
});
```

- [ ] **Step 2: Run the claim tests and verify failure**

Run:

```bash
node --test scripts/v07-release-claims-test.mjs
```

Expected: FAIL because the package script and release-readiness section do not
exist yet.

- [ ] **Step 3: Add the evidence checker**

Create `scripts/check-v07-release-evidence.mjs`:

```js
#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const releaseReadiness = readFileSync(resolve(root, 'docs/contributing/release-readiness.md'), 'utf8');

const required = [
  'npm run console:reliability:test',
  'npm run console:session:test',
  'npm run console:draft:test',
  'npm run console:browser:reliability',
];

const missing = required.filter((command) => !releaseReadiness.includes(command));
if (missing.length) {
  console.error('Missing v0.7 Studio Reliability evidence command(s):');
  for (const command of missing) console.error(`- ${command}`);
  process.exit(1);
}

console.log('PASS v0.7 Studio Reliability evidence docs');
```

- [ ] **Step 4: Add the package script**

In `package.json` `scripts`, add:

```json
"release:v07:evidence:test": "node scripts/check-v07-release-evidence.mjs && node --test scripts/v07-release-claims-test.mjs"
```

Keep the JSON comma placement valid.

- [ ] **Step 5: Document v0.7 evidence**

In `docs/contributing/release-readiness.md`, add this section after the v0.6
manifest:

```markdown
## v0.7 Studio Reliability Claim Manifest

v0.7 may claim Studio Reliability only when the release issue includes
evidence that Studio remains coherent through push-first preview/session sync,
draft/session ownership boundaries, and browser-level repeated-use scenarios.

Required v0.7 evidence before tagging:

- `npm run console:reliability:test`
- `npm run console:session:test`
- `npm run console:draft:test`
- `npm run console:browser:reliability`
- `npm run release:v07:evidence:test`
```

- [ ] **Step 6: Confirm the spec's resolved Open Decision**

The spec's Open Decision #2 was already resolved on 2026-05-18 in
`docs/superpowers/specs/2026-05-18-v07-studio-reliability-umbrella-design.md`
to use a dedicated `release:v07:evidence:test` script. Verify the wording
still matches Task 7's package script; if a future spec edit reopens the
decision, reconcile it here before continuing.

- [ ] **Step 7: Run release evidence verification**

Run:

```bash
npm run release:v07:evidence:test
node scripts/check-doc-consistency.mjs
```

Expected: both commands PASS.

- [ ] **Step 8: Commit Task 7**

```bash
git add package.json scripts/check-v07-release-evidence.mjs scripts/v07-release-claims-test.mjs docs/contributing/release-readiness.md
git commit -m "docs: add v0.7 reliability evidence gate"
```

## Task 8: Final Verification And Release-Readiness Sweep

**Files:**
- Modify only files required by verification failures.

- [ ] **Step 1: Run focused v0.7 verification**

Run:

```bash
npm run console:preview:test
npm run console:session:test
npm run console:draft:test
npm run console:reliability:test
npm run console:browser:reliability
npm run release:v07:evidence:test
```

Expected: every command PASS.

- [ ] **Step 2: Rebuild and check console assets**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: all commands PASS.

- [ ] **Step 3: Run repository hygiene checks**

Run:

```bash
node scripts/check-doc-consistency.mjs
npm run detekt:baseline:check
git diff --check
node scripts/check-whitespace.mjs diff --check
```

Expected: all commands PASS.

- [ ] **Step 4: Run full console JS gate**

Run:

```bash
npm run console:test:all
```

Expected: PASS.

- [ ] **Step 5: Run focused Gradle route/session tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleFeedbackItemRoutesTest" --tests "*ConsoleHandoffRoutesTest" --tests "*ConsoleSessionRoutesTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit any final verification fallout**

If Step 1-5 forced changes, commit them:

```bash
git add -u
git commit -m "fix: close v0.7 reliability verification gaps"
```

If no files changed, skip this commit.

- [ ] **Step 7: Record final status**

Run:

```bash
git status --short
```

Expected: no output.

## Plan Self-Review

- Spec coverage: Track A is covered by Tasks 1-3, Track B by Tasks 4-5,
  Track C by Tasks 6-7, and release/final verification by Task 8.
- Placeholder scan: this plan contains no red-flag placeholder steps.
- Type/name consistency: new helpers are `livePreviewApplyDecision`,
  `serverSessionApplyDecision`, and `draftResponseMatchesWorkspace`; tests and
  implementation snippets use those exact names.
- Scope check: plan does not add visual redesign, new source matching,
  release runtime behavior, XML/View/WebView targeting, or automatic code
  edits.
- Caller-update sweep: every helper added in this plan (preview decision,
  session decision, recovery ownership `activeSessionId`) lists the
  production callers that need wiring in its task's Files section.

## Plan Audit Log (2026-05-18)

Doc-vs-source audit performed against `HEAD` (`af252a03`). Findings folded back
into the plan:

1. **Task 3 — polling adapter dropped `refresh_summaries`.** The first draft
   handled only `apply` and `clear`, which silently discarded mid-poll
   responses for a session the user has navigated away from. Without the
   guard, the legacy `else` branch in `pollingBrowserAdapter.js` was
   load-bearing — it kept summaries fresh. The plan now adds an explicit
   no-op branch with a comment so the contract is visible, and the polling
   contract test now pins the three decision kinds.
2. **Task 3 — obsolete source-shape assertions.** Three tests in
   `consoleEvents-test.mjs` and `studioReliabilityContract-test.mjs` matched
   literal expressions that the refactor moves into the new decision
   helpers. Added Step 6.5 to update those assertions to the helper-based
   shape; both files are now listed.
3. **Task 4 — `studioReliabilityContract-test.mjs` and
   `sessionMismatchIgnore-test.mjs` matched the inline workspace fence**
   (`event.workspaceId !== state.workspace.context.workspaceId`) that moves
   into the new `draftResponseMatchesWorkspace` helper. Added Step 4.6 to
   pin the helper-based shape and the helper body's identity checks.
4. **Task 4 — prompt-copy fence was promised in File Structure but never
   implemented.** Added Step 4.5 to fence `reducePromptCopySucceeded` and
   `reducePromptCopyFailed` by `activeSessionId` and append a matching
   reducer test.
5. **Task 5 — `draftRecoveryOwnership` callers did not pass
   `activeSessionId`.** Without wiring `main.js:365`,
   `pendingRecoveryUi.js:35`, and `pendingRecoveryUi.js:71`, the new
   `mismatched` classification could never fire. Added Step 4.5 with the
   exact call-site updates.
6. **Design spec Open Decisions.** Decision #2 (dedicated
   `release:v07:evidence:test` script) is resolved by Task 7 Step 6.
   Decisions #1 (PR-time gating after observation) and #3 (fallback polling
   filename migration) remain intentionally open and are out of scope for
   v0.7.
