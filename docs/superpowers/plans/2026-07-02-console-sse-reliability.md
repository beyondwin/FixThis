# Console SSE Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the browser console's healthy state-sync path demonstrably SSE-first while keeping disconnected fallback polling intact.

**Architecture:** Add browser-local SSE diagnostics in `sse.js`, wire them from `events.js`, then use tests and docs to prove the boundary between healthy EventSource updates and recovery pull refreshes. Keep `refreshSessions()` for manual refresh and explicit session navigation, and route healthy mutation aftermath through existing SSE-aware fallback helpers.

**Tech Stack:** Plain browser JavaScript bundled by `scripts/build-console-assets.mjs`, Node.js `node:test`, Playwright browser reliability harness, Markdown docs.

## Global Constraints

- Do not remove fallback preview or session polling.
- Do not replace SSE with WebSocket.
- Do not change MCP tool signatures, persisted feedback-session JSON, compact handoff output, bridge protocol, or source-matching contracts.
- Do not make diagnostics part of `.fixthis/` persisted data.
- Do not broadly migrate console rendering to selectors or remove all legacy `state.*` projection in this pass.
- Do not change server-side session storage semantics.
- If a direct `refreshSessions()` call is ambiguous, keep it and document why rather than risking a stale visible session transition.

---

## File Structure

- `fixthis-mcp/src/main/console/sse.js` owns EventSource connection state, fallback gates, and the new diagnostics API.
- `fixthis-mcp/src/main/console/events.js` owns EventSource lifecycle events and records diagnostics at `onopen`, `onerror`, and `replay-overflow`.
- `fixthis-mcp/src/main/console/main.js` exposes diagnostics through `window.FixThisConsoleDebug` for browser reliability tests.
- `scripts/sseDiagnostics-test.mjs` is a new pure Node test for the `sse.js` diagnostics API.
- `scripts/consoleEvents-test.mjs` keeps source-level EventSource wiring contracts.
- `scripts/studioReliabilityContract-test.mjs` keeps broad console reliability source contracts, including fallback-gated refresh behavior.
- `scripts/console-fixture/fakeBridgeServer.mjs` gains one explicit helper for emitting `replay-overflow` in browser tests.
- `scripts/console-browser-reliability.mjs` proves diagnostics and zero healthy-path pull behavior in a real browser.
- `docs/reference/feedback-console-contract.md` and `docs/architecture/console-state-sync-design.md` describe the same SSE primary / fallback retained contract that tests enforce.

---

### Task 1: Add Browser-Local SSE Diagnostics API

**Files:**
- Create: `scripts/sseDiagnostics-test.mjs`
- Modify: `fixthis-mcp/src/main/console/sse.js`
- Modify: `fixthis-mcp/src/main/console/main.js`

**Interfaces:**
- Consumes: existing `setConsoleEventsConnected(connected)`, `isConsoleEventsConnected()`, `wasConsoleEventsRecentlyConnected(maxAgeMs)`, `shouldUsePreviewFallbackPolling()`, and `shouldUseSessionFallbackPolling()`.
- Produces:
  - `setConsoleEventsConnected(connected: boolean, options?: { reason?: string, nowMs?: number }): boolean`
  - `recordConsoleEventsOverflow(options?: { nowMs?: number }): object`
  - `consoleEventsDiagnostics(): object`
  - `window.FixThisConsoleDebug.consoleEventsDiagnostics(): object`

- [ ] **Step 1: Write the failing diagnostics test**

Create `scripts/sseDiagnostics-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/sse.js'), 'utf8');
const factory = new Function(`${src}; return {
  setConsoleEventsConnected,
  isConsoleEventsConnected,
  wasConsoleEventsRecentlyConnected,
  shouldUsePreviewFallbackPolling,
  shouldUseSessionFallbackPolling,
  recordConsoleEventsOverflow,
  consoleEventsDiagnostics,
};`);

function fresh() {
  return factory();
}

test('initial diagnostics are zeroed and fallback gates are open', () => {
  const m = fresh();
  assert.equal(m.isConsoleEventsConnected(), false);
  assert.equal(m.shouldUsePreviewFallbackPolling(), true);
  assert.equal(m.shouldUseSessionFallbackPolling(), true);
  assert.deepEqual(m.consoleEventsDiagnostics(), {
    connected: false,
    connectCount: 0,
    disconnectCount: 0,
    reconnectCount: 0,
    replayOverflowCount: 0,
    lastConnectedAt: null,
    lastDisconnectedAt: null,
    lastFallbackReason: null,
  });
});

test('connect disconnect reconnect and overflow update diagnostics', () => {
  const m = fresh();

  assert.equal(m.setConsoleEventsConnected(true, { nowMs: 1000 }), true);
  assert.equal(m.isConsoleEventsConnected(), true);
  assert.equal(m.shouldUsePreviewFallbackPolling(), false);
  assert.equal(m.shouldUseSessionFallbackPolling(), false);
  assert.deepEqual(m.consoleEventsDiagnostics(), {
    connected: true,
    connectCount: 1,
    disconnectCount: 0,
    reconnectCount: 0,
    replayOverflowCount: 0,
    lastConnectedAt: 1000,
    lastDisconnectedAt: null,
    lastFallbackReason: null,
  });

  assert.equal(m.setConsoleEventsConnected(false, { reason: 'eventsource_error', nowMs: 1500 }), false);
  assert.equal(m.isConsoleEventsConnected(), false);
  assert.equal(m.shouldUsePreviewFallbackPolling(), true);
  assert.equal(m.shouldUseSessionFallbackPolling(), true);

  assert.equal(m.setConsoleEventsConnected(true, { nowMs: 2500 }), true);
  m.recordConsoleEventsOverflow({ nowMs: 2600 });

  assert.deepEqual(m.consoleEventsDiagnostics(), {
    connected: true,
    connectCount: 2,
    disconnectCount: 1,
    reconnectCount: 1,
    replayOverflowCount: 1,
    lastConnectedAt: 2500,
    lastDisconnectedAt: 1500,
    lastFallbackReason: 'replay_overflow',
  });
});

test('diagnostics snapshots are copies', () => {
  const m = fresh();
  m.setConsoleEventsConnected(true, { nowMs: 100 });
  const snapshot = m.consoleEventsDiagnostics();
  snapshot.connectCount = 99;
  assert.equal(m.consoleEventsDiagnostics().connectCount, 1);
});
```

- [ ] **Step 2: Run the new test and verify it fails**

Run:

```bash
node --test scripts/sseDiagnostics-test.mjs
```

Expected: FAIL with `Console symbol not found` or `recordConsoleEventsOverflow is not defined`.

- [ ] **Step 3: Implement diagnostics in `sse.js`**

Replace the current top of `fixthis-mcp/src/main/console/sse.js` with:

```js
// @requires (none)
            // sse.js — late-SSE-message session-equality gate and connection
            // state that gates both preview AND session fallback polling
            // (shouldUsePreviewFallbackPolling / shouldUseSessionFallbackPolling).
            let consoleEventsConnected = false;
            let consoleEventsLastConnectedAt = 0;
            const consoleEventsDiagnosticState = {
              connectCount: 0,
              disconnectCount: 0,
              reconnectCount: 0,
              replayOverflowCount: 0,
              lastConnectedAt: null,
              lastDisconnectedAt: null,
              lastFallbackReason: null,
            };

            function nowMs(options) {
              return typeof options?.nowMs === 'number' ? options.nowMs : Date.now();
            }

            function setConsoleEventsConnected(connected, options = {}) {
              const nextConnected = connected === true;
              if (nextConnected) {
                const wasConnectedBefore = consoleEventsDiagnosticState.connectCount > 0;
                consoleEventsDiagnosticState.connectCount += 1;
                if (wasConnectedBefore && !consoleEventsConnected) {
                  consoleEventsDiagnosticState.reconnectCount += 1;
                }
                consoleEventsLastConnectedAt = nowMs(options);
                consoleEventsDiagnosticState.lastConnectedAt = consoleEventsLastConnectedAt;
              } else if (consoleEventsConnected) {
                consoleEventsDiagnosticState.disconnectCount += 1;
                consoleEventsDiagnosticState.lastDisconnectedAt = nowMs(options);
                consoleEventsDiagnosticState.lastFallbackReason = options.reason || 'eventsource_disconnected';
              } else if (options.reason) {
                consoleEventsDiagnosticState.lastFallbackReason = options.reason;
              }
              consoleEventsConnected = nextConnected;
              return consoleEventsConnected;
            }

            function recordConsoleEventsOverflow(options = {}) {
              consoleEventsDiagnosticState.replayOverflowCount += 1;
              consoleEventsDiagnosticState.lastFallbackReason = 'replay_overflow';
              if (typeof options.nowMs === 'number') {
                consoleEventsDiagnosticState.lastDisconnectedAt = options.nowMs;
              }
              return consoleEventsDiagnostics();
            }

            function consoleEventsDiagnostics() {
              return {
                connected: consoleEventsConnected,
                connectCount: consoleEventsDiagnosticState.connectCount,
                disconnectCount: consoleEventsDiagnosticState.disconnectCount,
                reconnectCount: consoleEventsDiagnosticState.reconnectCount,
                replayOverflowCount: consoleEventsDiagnosticState.replayOverflowCount,
                lastConnectedAt: consoleEventsDiagnosticState.lastConnectedAt,
                lastDisconnectedAt: consoleEventsDiagnosticState.lastDisconnectedAt,
                lastFallbackReason: consoleEventsDiagnosticState.lastFallbackReason,
              };
            }
```

Keep the existing functions below that block:

```js
            function isConsoleEventsConnected() {
              return consoleEventsConnected;
            }

            function wasConsoleEventsRecentlyConnected(maxAgeMs = 1000) {
              return consoleEventsLastConnectedAt > 0 && (Date.now() - consoleEventsLastConnectedAt) <= maxAgeMs;
            }

            function shouldUsePreviewFallbackPolling() {
              return !consoleEventsConnected;
            }

            function shouldUseSessionFallbackPolling() {
              return !consoleEventsConnected;
            }
```

- [ ] **Step 4: Expose diagnostics in `main.js` debug API**

In `fixthis-mcp/src/main/console/main.js`, update `window.FixThisConsoleDebug`:

```js
            window.FixThisConsoleDebug = Object.freeze({
              consoleEventsDiagnostics: () => consoleEventsDiagnostics(),
              getDraftWorkspace: () => currentDraftWorkspace(),
              getState: () => state,
              isConsoleEventsConnected: () => isConsoleEventsConnected(),
              isSessionsPollingArmed: () => isSessionsPollingArmed(),
            });
```

- [ ] **Step 5: Run tests and verify they pass**

Run:

```bash
node --test scripts/sseDiagnostics-test.mjs
node --test scripts/studioReliabilityContract-test.mjs scripts/consoleEvents-test.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add fixthis-mcp/src/main/console/sse.js fixthis-mcp/src/main/console/main.js scripts/sseDiagnostics-test.mjs
git commit -m "feat(console): add sse diagnostics"
```

---

### Task 2: Wire Diagnostics At EventSource Boundaries

**Files:**
- Modify: `fixthis-mcp/src/main/console/events.js`
- Modify: `scripts/consoleEvents-test.mjs`
- Modify: `scripts/studioReliabilityContract-test.mjs`

**Interfaces:**
- Consumes: `setConsoleEventsConnected(connected, options)`, `recordConsoleEventsOverflow(options)`, `refreshSessionsWhenEventsDisconnected()`.
- Produces: EventSource lifecycle updates with stable diagnostic reason values: `eventsource_error` and `replay_overflow`.

- [ ] **Step 1: Add failing source contract tests for diagnostics wiring**

Append to `scripts/consoleEvents-test.mjs`:

```js
test('events subscriber records diagnostics for open error and replay overflow', () => {
  const start = body(source, 'function startConsoleEvents()');
  assert.match(start, /setConsoleEventsConnected\(true\)/);
  assert.match(start, /setConsoleEventsConnected\(false,\s*\{\s*reason:\s*['"]eventsource_error['"]/);
  assert.match(start, /recordConsoleEventsOverflow\(\)/);
  assert.match(start, /source\.addEventListener\(['"]replay-overflow['"],\s*\(\)\s*=>\s*\{/);
});

test('mismatched session update uses disconnected fallback refresh instead of healthy pull', () => {
  const start = body(source, 'function startConsoleEvents()');
  const sessionUpdated = start.slice(start.indexOf("on('session-updated'"));
  assert.match(sessionUpdated, /refreshSessionsWhenEventsDisconnected\(\)\.catch\(showError\)/);
  assert.doesNotMatch(sessionUpdated, /refreshSessions\(\)\.catch\(showError\)/);
});
```

Update the existing `SSE connection state is explicit and controls preview fallback polling` test in `scripts/studioReliabilityContract-test.mjs` so its `setConsoleEventsConnected` assertion accepts the optional parameter:

```js
  assert.match(sse, /function setConsoleEventsConnected\(connected,\s*options = \{\}\)/);
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs scripts/consoleEvents-test.mjs
```

Expected: FAIL because `events.js` does not yet pass `eventsource_error`, does not call `recordConsoleEventsOverflow()`, and still calls `refreshSessions()` for mismatched active-session events.

- [ ] **Step 3: Update `events.js`**

Keep `source.onopen` in this shape so reconnect diagnostics are recorded by `setConsoleEventsConnected(true)` and fallback polling is stopped:

```js
              source.onopen = () => {
                setConsoleEventsConnected(true);
                stopLivePreviewPolling();
                stopSessionsPolling();
```

Change the mismatched session branch:

```js
                if (activeSessionId() && !matchesActiveSession(data)) {
                  refreshSessionsWhenEventsDisconnected().catch(showError);
                  return;
                }
```

Change `replay-overflow` handling from a single expression to a block:

```js
              source.addEventListener('replay-overflow', () => {
                recordConsoleEventsOverflow();
                refresh().catch(showError);
              });
```

Change `source.onerror`:

```js
              source.onerror = () => {
                setConsoleEventsConnected(false, { reason: 'eventsource_error' });
                if (state.connection && !state.connection.sessionsPollingPaused) setSessionsPollingPaused(true);
                startLivePreviewPolling();
                startSessionsPolling();
                const node = getServerBuildChipNode();
                if (node) updateServerBuildChipState(node, { state: 'reconnecting' });
              };
```

- [ ] **Step 4: Run tests and verify they pass**

Run:

```bash
node --test scripts/sseDiagnostics-test.mjs scripts/studioReliabilityContract-test.mjs scripts/consoleEvents-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add fixthis-mcp/src/main/console/events.js scripts/consoleEvents-test.mjs scripts/studioReliabilityContract-test.mjs
git commit -m "feat(console): record sse lifecycle diagnostics"
```

---

### Task 3: Codify The Healthy-Path Pull Boundary

**Files:**
- Modify: `scripts/studioReliabilityContract-test.mjs`

**Interfaces:**
- Consumes: existing `refreshSessions()`, `refreshSessionsWhenEventsDisconnected()`, `isConsoleEventsConnected()`, and `wasConsoleEventsRecentlyConnected(maxAgeMs)`.
- Produces: a source contract that direct `refreshSessions()` remains limited to explicit navigation, manual refresh, session close/delete, and session creation paths.

- [ ] **Step 1: Add the boundary contract test**

Append to `scripts/studioReliabilityContract-test.mjs`:

```js
test('direct refreshSessions calls stay limited to explicit session lifecycle paths', () => {
  const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
  const directCalls = [...history.matchAll(/refreshSessions\(\)/g)]
    .map((match) => history.slice(Math.max(0, match.index - 220), match.index + 220));
  const allowedMarkers = [
    'async function refreshSessions()',
    'async function refreshSessionsWhenEventsDisconnected()',
    'async function refresh()',
    'async function ensureSessionForAnnotating()',
    'async function openSession(sessionId)',
    'async function newSession()',
    'async function closeSession()',
    'async function deleteHistorySession(sessionId',
  ];
  for (const snippet of directCalls) {
    assert.ok(
      allowedMarkers.some((marker) => snippet.includes(marker)),
      `unexpected direct refreshSessions call outside allowed lifecycle paths:\n${snippet}`,
    );
  }
});
```

- [ ] **Step 2: Run the test**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: PASS. The current allowed lifecycle calls remain direct, and Task 2 removed the remaining direct `refreshSessions()` call from `events.js`.

- [ ] **Step 3: Inspect the allowed direct calls**

Run:

```bash
rg -n "refreshSessions\\(\\)" fixthis-mcp/src/main/console/history.js fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/console/prompt.js fixthis-mcp/src/main/console/events.js
```

Expected output contains direct `refreshSessions()` only in `history.js` lifecycle helpers and no direct calls in `annotations.js`, `prompt.js`, or `events.js`:

```text
fixthis-mcp/src/main/console/history.js:...:await refreshSessions();
fixthis-mcp/src/main/console/history.js:...:return refreshSessions();
```

- [ ] **Step 4: Run the boundary tests**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs scripts/consoleEvents-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add scripts/studioReliabilityContract-test.mjs
git commit -m "test(console): codify sse refresh boundary"
```

---

### Task 4: Extend Browser Reliability Evidence

**Files:**
- Modify: `scripts/console-fixture/fakeBridgeServer.mjs`
- Modify: `scripts/console-browser-reliability.mjs`

**Interfaces:**
- Consumes: `window.FixThisConsoleDebug.consoleEventsDiagnostics()`, fake bridge EventSource support, and existing `recordReliabilityObservation(observation)`.
- Produces:
  - `fixture.emitReplayOverflow(): void`
  - browser reliability proof that reconnect and replay-overflow diagnostics are observable.

- [ ] **Step 1: Add failing browser reliability assertions**

In `scripts/console-browser-reliability.mjs`, update `testEventSourceReconnectRecovery()` after `fixture.closeEventClients(); await waitUntil(...)`:

```js
    await page.waitForFunction(
      () => window.FixThisConsoleDebug.consoleEventsDiagnostics().reconnectCount >= 1,
      null,
      { timeout: 8000 },
    );
    const reconnectDiagnostics = await page.evaluate(() => window.FixThisConsoleDebug.consoleEventsDiagnostics());
    assert.equal(reconnectDiagnostics.connected, true);
    assert.equal(reconnectDiagnostics.lastFallbackReason, 'eventsource_error');
```

Append a new test before `run()`:

```js
async function testReplayOverflowDiagnostics() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    await waitUntil(() => fixture.eventClientCount() >= 1);
    const pullsBefore = countSessionPulls(fixture);
    fixture.emitReplayOverflow();
    await page.waitForFunction(
      () => window.FixThisConsoleDebug.consoleEventsDiagnostics().replayOverflowCount >= 1,
      null,
      { timeout: 8000 },
    );
    await page.waitForFunction(
      () => window.FixThisConsoleDebug.getState().session?.sessionId === 'session-1',
      null,
      { timeout: 8000 },
    );
    const diagnostics = await page.evaluate(() => window.FixThisConsoleDebug.consoleEventsDiagnostics());
    assert.equal(diagnostics.lastFallbackReason, 'replay_overflow');
    assert.ok(
      countSessionPulls(fixture) > pullsBefore,
      'replay overflow should trigger the documented recovery refresh',
    );
    recordReliabilityObservation({
      name: 'replay-overflow-diagnostics',
      eventSourceConnected: diagnostics.connected,
      requestSummary: summarizeRequests(fixture.getRequestLog()),
      fallbackReasons: ['replay_overflow'],
    });
  });
}
```

Add the test to `run()`:

```js
  await testReplayOverflowDiagnostics();
```

- [ ] **Step 2: Run the browser reliability proof and verify it fails**

Run:

```bash
npm run console:browser:reliability
```

Expected: FAIL because `fixture.emitReplayOverflow` is not defined.

- [ ] **Step 3: Add fake bridge replay-overflow helper**

In `scripts/console-fixture/fakeBridgeServer.mjs`, add this helper to the returned `fixture` object near `emitPreviewReady`:

```js
    emitReplayOverflow: () => {
      emitEvent('replay-overflow', { reason: 'test overflow' });
    },
```

- [ ] **Step 4: Run browser reliability proof**

Run:

```bash
npm run console:browser:reliability
```

Expected: PASS and output:

```text
Console reliability report: pass
PASS console browser reliability proof
```

- [ ] **Step 5: Commit Task 4**

```bash
git add scripts/console-fixture/fakeBridgeServer.mjs scripts/console-browser-reliability.mjs
git commit -m "test(console): prove sse diagnostics in browser"
```

---

### Task 5: Align Docs And Run Final Verification

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/architecture/console-state-sync-design.md`

**Interfaces:**
- Consumes: diagnostics fields from Task 1 and browser proof from Task 4.
- Produces: docs that describe healthy SSE, disconnected fallback, and replay-overflow recovery consistently.

- [ ] **Step 1: Update feedback console contract text**

In `docs/reference/feedback-console-contract.md`, replace the paragraph beginning with `SSE-cleanup gate outcome` with:

```md
- **SSE reliability boundary:** `/api/events` is the primary session, summary,
  device, connection, and preview sync path. Healthy EventSource sessions must
  not arm session or preview fallback polling and must not issue steady-state
  `/api/session`, `/api/sessions`, or `/api/preview` pull fetches after local
  mutations. The disconnected fallback remains live: EventSource errors record
  `eventsource_error`, clear the connected flag, and re-arm
  `startSessionsPolling()` plus `startLivePreviewPolling()`. Replay overflow is
  an explicit recovery path: the browser records `replay_overflow` and performs
  a full `refresh()` from authoritative HTTP endpoints.
- Browser-local SSE diagnostics are exposed only for local tests and debugging
  through `window.FixThisConsoleDebug.consoleEventsDiagnostics()`. They are not
  persisted in `.fixthis/`, MCP output, compact prompts, or feedback-session
  JSON.
```

- [ ] **Step 2: Update console state sync design**

In `docs/architecture/console-state-sync-design.md`, update Phase 3 and Phase 4 to:

```md
**Phase 3 — retire happy-path pull calls (current hardening line)**
- Keep `refreshSessions()` for manual refresh, initial load, explicit session
  navigation, and replay-overflow recovery.
- Route healthy mutation aftermath through response data,
  `sessions-updated` events, or `refreshSessionsWhenEventsDisconnected()`.
- Acceptance: under healthy EventSource, Save to MCP, preview push, and
  session-summary updates perform zero steady-state `/api/session`,
  `/api/sessions`, and `/api/preview` pull fetches.

**Phase 4 — observability (current hardening line)**
- Track browser-local connect, disconnect, reconnect, replay-overflow, and last
  fallback reason diagnostics.
- Expose diagnostics through the debug object for tests and local reports only.
- Acceptance: browser reliability proof observes reconnect diagnostics after an
  EventSource drop and replay-overflow diagnostics before the documented
  recovery refresh.
```

- [ ] **Step 3: Run docs and focused reliability verification**

Run:

```bash
node --test scripts/sseDiagnostics-test.mjs scripts/studioReliabilityContract-test.mjs scripts/consoleEvents-test.mjs
npm run console:browser:reliability
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: all commands pass.

- [ ] **Step 4: Run graph update**

Run:

```bash
graphify update .
```

Expected: command completes. `graphify-out/` may become dirty or remain dirty as an ignored local artifact; do not commit it.

- [ ] **Step 5: Inspect final tracked diff**

Run:

```bash
git status --short
git diff --stat
```

Expected tracked changes are limited to:

```text
docs/architecture/console-state-sync-design.md
docs/reference/feedback-console-contract.md
fixthis-mcp/src/main/console/events.js
fixthis-mcp/src/main/console/main.js
fixthis-mcp/src/main/console/sse.js
scripts/console-browser-reliability.mjs
scripts/console-fixture/fakeBridgeServer.mjs
scripts/consoleEvents-test.mjs
scripts/sseDiagnostics-test.mjs
scripts/studioReliabilityContract-test.mjs
```

- [ ] **Step 6: Commit Task 5**

```bash
git add docs/architecture/console-state-sync-design.md docs/reference/feedback-console-contract.md
git commit -m "docs: clarify console sse reliability boundary"
```

Do not add `.fixthis/`, `graphify-out/`, `build/`, or browser screenshots.

---

## Final Verification Matrix

Run after all tasks:

```bash
node --test scripts/sseDiagnostics-test.mjs scripts/studioReliabilityContract-test.mjs scripts/consoleEvents-test.mjs
npm run console:browser:reliability
node scripts/check-doc-consistency.mjs
git diff --check
graphify update .
git status --short
```

Expected:

- All test and docs commands pass.
- `graphify update .` completes.
- No tracked files outside the expected task scope are dirty.
- Ignored local artifacts such as `graphify-out/` are not staged.

## Review Notes

- Treat any proposed fallback polling deletion as out of scope.
- Keep diagnostics local to the browser process.
- Preserve `refreshSessions()` in explicit session lifecycle paths unless a failing test proves a specific call is redundant and safe to gate.
- Browser reliability failures should be inspected through the generated report under `build/reports/` before changing production code.
