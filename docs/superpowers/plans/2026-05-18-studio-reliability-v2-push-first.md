# Studio Reliability v2 Push-First Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FixThis Studio push-first for live preview/session reliability, with explicit recovery ownership, durable mutation fences, and browser-level proof.

**Architecture:** Preserve the current Clean Architecture/SOLID boundaries: pure preview/recovery policy stays in focused JS use cases, browser adapters own DOM/network wiring, and `fixthis-mcp` session services own durable truth. Route SSE and fallback preview delivery through one visible state application path, then narrow polling to fallback-only behavior. Add tests before implementation for reducer contracts, recovery ownership, server mutation rejection, and browser proof.

**Tech Stack:** Browser console JavaScript, Node.js `node:test`, Playwright harness, Kotlin/JVM, kotlinx.serialization, JUnit, existing `:fixthis-mcp` route/session services.

---

## Scope Check

This plan implements `docs/superpowers/specs/2026-05-18-studio-reliability-v2-push-first-design.md`.

The spec covers four Studio reliability risks, but they are not independent products. They share the same state pipeline and can be implemented as one plan with six reviewable tasks:

1. Add a pure preview apply contract.
2. Route SSE and fallback preview through that contract.
3. Narrow live preview polling to fallback behavior.
4. Harden draft recovery ownership.
5. Reject closed-session durable mutations at the server boundary.
6. Add browser proof for SSE sync, reconnect, late response isolation, and save idempotency.

No task changes handoff intelligence, visual design, release-build behavior, XML/View targeting, or persisted MCP JSON field names.

## File Structure

### Create

- `scripts/console-browser-reliability.mjs`
  - Playwright proof suite for two-tab SSE sync, EventSource reconnect recovery, repeated Save to MCP idempotency, and late preview isolation. Stale preview Save confirmation remains covered by `scripts/studioWorkflow-test.mjs` and `scripts/studioWorkflowIntegration-test.mjs`.

### Modify

- `fixthis-mcp/src/main/console/previewFsm.js`
  - Add a reducer action for externally delivered ready previews.
- `fixthis-mcp/src/main/console/previewUseCases.js`
  - Expose a small `applyReady(preview, options)` use-case method so SSE and fallback code share the same preview state contract.
- `fixthis-mcp/src/main/console/preview.js`
  - Add `applyLivePreview(preview, options)` and route `refreshPreview()` through it.
- `fixthis-mcp/src/main/console/events.js`
  - Route `preview-ready` through `applyLivePreview()` and track EventSource open/error state.
- `fixthis-mcp/src/main/console/sse.js`
  - Add tiny SSE connection state helpers used by preview fallback polling.
- `fixthis-mcp/src/main/console/draftUseCases.js`
  - Add draft recovery ownership classification for open, closed, deleted/missing, commented, and pin-only recovery.
- `fixthis-mcp/src/main/console/main.js`
  - Apply recovery ownership when loading browser-local recovery for the active session.
- `fixthis-mcp/src/main/console/pendingRecoveryUi.js`
  - Render closed/deleted recovery as recapture/discard instead of automatic resume.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`
  - Reject event-backed durable mutations when the target session is closed.
- `scripts/previewUseCases-test.mjs`
  - Tests for pure external preview application.
- `scripts/consoleEvents-test.mjs`
  - Source contract for `preview-ready` using `applyLivePreview`.
- `scripts/sessionMismatchIgnore-test.mjs`
  - Runtime/source tests for shared preview stale-session fencing.
- `scripts/draftRecoveryMatrix-test.mjs`
  - Recovery ownership matrix.
- `scripts/studioReliabilityContract-test.mjs`
  - Contract checks for fallback-only preview polling and shared preview application.
- `scripts/console-fixture/fakeBridgeServer.mjs`
  - Add controllable SSE subscribers and idempotent fake batch save support for browser proof.
- `package.json`
  - Add `console:browser:reliability` for Playwright proof.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
  - Add closed-session mutation route tests.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHandoffRoutesTest.kt`
  - Add closed-session handoff route test.

## Task 1: Add A Pure External Preview Apply Contract

**Files:**
- Modify: `scripts/previewUseCases-test.mjs`
- Modify: `fixthis-mcp/src/main/console/previewFsm.js`
- Modify: `fixthis-mcp/src/main/console/previewUseCases.js`

- [ ] **Step 1: Write failing preview use-case tests**

Append these tests to `scripts/previewUseCases-test.mjs`:

```js
test('applyReady() stores externally delivered preview without starting a fetch', () => {
  const seen = [];
  const uc = makeUseCases({
    api: { capture: async () => { throw new Error('capture must not run'); } },
    observer: (next) => { seen.push(next); },
  });

  const next = uc.applyReady({ previewId: 'sse-preview', sessionId: 'session-1' });

  assert.equal(next.lifecycle, m.PreviewLifecycle.READY);
  assert.equal(next.current.previewId, 'sse-preview');
  assert.equal(uc.getState().current.sessionId, 'session-1');
  assert.equal(seen.at(-1).current.previewId, 'sse-preview');
});

test('applyReady() ignores stale context generation', () => {
  const uc = makeUseCases();
  uc.contextChanged();

  const before = uc.getState();
  const next = uc.applyReady(
    { previewId: 'stale-preview', sessionId: 'session-1' },
    { contextGeneration: before.contextGeneration - 1 },
  );

  assert.equal(next, before);
  assert.equal(uc.getState().current, null);
});

test('applyReady() clears stale/error state on accepted external preview', async () => {
  const uc = makeUseCases({ api: { capture: async () => ({ previewId: 'poll-preview' }) } });
  await uc.request();
  uc.setStale(true);

  const next = uc.applyReady({ previewId: 'sse-preview', sessionId: 'session-1' });

  assert.equal(next.lifecycle, m.PreviewLifecycle.READY);
  assert.equal(next.current.previewId, 'sse-preview');
  assert.equal(next.error, null);
});
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
node --test scripts/previewUseCases-test.mjs
```

Expected: FAIL with `TypeError: uc.applyReady is not a function`.

- [ ] **Step 3: Add the reducer action**

In `fixthis-mcp/src/main/console/previewFsm.js`, add this action to the header comment action list:

```js
//   APPLY_READY          — accept externally delivered preview payloads
//                          such as SSE preview-ready events; supplied
//                          contextGeneration fences stale delivery.
```

Then add this `switch` case immediately before `REQUEST_FAILED`:

```js
    case 'APPLY_READY': {
      if (
        Number.isInteger(action.contextGeneration) &&
        action.contextGeneration !== state.contextGeneration
      ) {
        return state;
      }
      return Object.freeze({
        ...state,
        lifecycle: PreviewLifecycle.READY,
        current: action.preview ?? null,
        inFlight: null,
        error: null,
      });
    }
```

- [ ] **Step 4: Add the use-case method**

In `fixthis-mcp/src/main/console/previewUseCases.js`, add this function after `request()`:

```js
  function applyReady(preview, options = {}) {
    const contextGeneration = Number.isInteger(options.contextGeneration)
      ? options.contextGeneration
      : current.contextGeneration;
    return dispatch({
      type: 'APPLY_READY',
      preview,
      contextGeneration,
    });
  }
```

Then add `applyReady,` to the returned object:

```js
    applyReady,
```

- [ ] **Step 5: Run the test again**

Run:

```bash
node --test scripts/previewUseCases-test.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add scripts/previewUseCases-test.mjs fixthis-mcp/src/main/console/previewFsm.js fixthis-mcp/src/main/console/previewUseCases.js
git commit -m "feat(console): add external preview apply contract"
```

## Task 2: Route SSE And Fallback Preview Through One State Path

**Files:**
- Modify: `scripts/consoleEvents-test.mjs`
- Modify: `scripts/sessionMismatchIgnore-test.mjs`
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/main/console/events.js`

- [ ] **Step 1: Add failing source contract tests**

Append this test to `scripts/consoleEvents-test.mjs`:

```js
test('preview-ready event routes through shared live preview application', () => {
  const start = body(source, 'function startConsoleEvents()');
  const previewReady = start.slice(start.indexOf("on('preview-ready'"));

  assert.match(previewReady, /applyLivePreview\(data\.preview,\s*\{/);
  assert.match(previewReady, /source:\s*['"]sse['"]/);
  assert.match(previewReady, /sessionId:\s*data\.sessionId/);
  assert.doesNotMatch(previewReady, /setConsolePreview\(\{/);
});
```

Append this test to `scripts/sessionMismatchIgnore-test.mjs`:

```js
test('SSE and preview polling share applyLivePreview stale-session gate', () => {
  const previewApply = extractFunction(previewSource, 'function applyLivePreview');
  assert.match(previewApply, /options\.source === 'sse'/);
  assert.match(previewApply, /dropStaleSse\(\{ sessionId: ownerSessionId \}, activeSessionId\)/);
  assert.match(previewApply, /dropStalePreviewPoll\(\{ sessionId: ownerSessionId \}, activeSessionId\)/);
  assert.match(previewApply, /previewUseCases\.applyReady\(/);
  assert.match(previewApply, /setConsolePreview\(\{/);
});
```

- [ ] **Step 2: Run the failing tests**

Run:

```bash
node --test scripts/consoleEvents-test.mjs scripts/sessionMismatchIgnore-test.mjs
```

Expected: FAIL because `events.js` still calls `setConsolePreview` directly and `applyLivePreview` does not exist.

- [ ] **Step 3: Add `applyLivePreview()`**

In `fixthis-mcp/src/main/console/preview.js`, insert this function immediately before `refreshPreview()`:

```js
            function applyLivePreview(preview, options = {}) {
              if (!preview || draftFlow()) return false;
              const activeSessionId = state.session?.sessionId || null;
              const ownerSessionId = options.sessionId || preview.sessionId || null;
              if (ownerSessionId) {
                const dropped = options.source === 'sse'
                  ? dropStaleSse({ sessionId: ownerSessionId }, activeSessionId)
                  : dropStalePreviewPoll({ sessionId: ownerSessionId }, activeSessionId);
                if (dropped) return false;
              }
              previewUseCases.applyReady(preview, {
                contextGeneration: previewUseCases.getState().contextGeneration,
              });
              applyPreviewReadinessToConnectionCard(preview);
              if (preview.previewAvailable === false) {
                renderPreviewOnly();
                return true;
              }
              if (preview.screen?.systemUiVisible && state.preview) {
                state.preview.stale = true;
                state.preview.obstructedBySystemUi = preview.screen.systemUiKind || 'system_ui';
                markPreviewStale(true);
                renderPreviewOnly();
                return true;
              }
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

- [ ] **Step 4: Replace `refreshPreview()` tail**

In `fixthis-mcp/src/main/console/preview.js`, replace the body of `refreshPreview()` with:

```js
            async function refreshPreview() {
              error.textContent = '';
              if (!state.session || draftFlow()) return;
              const previewContext = capturePreviewContext();
              try {
                const preview = await previewUseCases.request();
                if (draftFlow() || !previewContextStillCurrent(previewContext)) return;
                applyLivePreview(preview, {
                  source: 'poll',
                  sessionId: preview?.sessionId || state.session?.sessionId || null,
                });
              } catch (cause) {
                markPreviewStale(true);
                refreshConnection({ preservePreviewStale: true }).catch((err) => console.warn('[fixthis] background connection refresh failed:', err));
                throw cause;
              }
            }
```

- [ ] **Step 5: Replace `preview-ready` handling**

In `fixthis-mcp/src/main/console/events.js`, replace the `preview-ready` handler with:

```js
              on('preview-ready', (data) => {
                if (!data.preview || draftFlow()) return;
                applyLivePreview(data.preview, {
                  source: 'sse',
                  sessionId: data.sessionId,
                });
              });
```

- [ ] **Step 6: Run the tests**

Run:

```bash
node --test scripts/previewUseCases-test.mjs scripts/consoleEvents-test.mjs scripts/sessionMismatchIgnore-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Rebuild console assets**

Run:

```bash
node scripts/build-console-assets.mjs
node --test scripts/build-console-assets-test.mjs
```

Expected: PASS and `fixthis-mcp/src/main/resources/console/app.js` plus source map update.

- [ ] **Step 8: Commit**

Run:

```bash
git add scripts/consoleEvents-test.mjs scripts/sessionMismatchIgnore-test.mjs fixthis-mcp/src/main/console/preview.js fixthis-mcp/src/main/console/events.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "refactor(console): route live preview updates through shared apply path"
```

## Task 3: Narrow Live Preview Polling To Fallback Behavior

**Files:**
- Modify: `scripts/studioReliabilityContract-test.mjs`
- Modify: `fixthis-mcp/src/main/console/sse.js`
- Modify: `fixthis-mcp/src/main/console/events.js`
- Modify: `fixthis-mcp/src/main/console/preview.js`

- [ ] **Step 1: Add failing fallback-only polling tests**

Append these tests to `scripts/studioReliabilityContract-test.mjs`:

```js
test('SSE connection state is explicit and controls preview fallback polling', () => {
  const sse = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/sse.js'), 'utf8');
  const preview = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');
  const events = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/events.js'), 'utf8');

  assert.match(sse, /function setConsoleEventsConnected\(connected\)/);
  assert.match(sse, /function shouldUsePreviewFallbackPolling\(\)/);
  assert.match(preview, /if \(!shouldUsePreviewFallbackPolling\(\)\) return;/);
  assert.match(events, /source\.onopen = \(\) => \{/);
  assert.match(events, /setConsoleEventsConnected\(true\)/);
  assert.match(events, /setConsoleEventsConnected\(false\)/);
  assert.match(events, /startLivePreviewPolling\(\)/);
});
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: FAIL because SSE connection state helpers do not exist and polling is not fallback-only.

- [ ] **Step 3: Add SSE connection helpers**

Replace `fixthis-mcp/src/main/console/sse.js` with:

```js
// @requires (none)
            // sse.js — late-SSE-message session-equality gate and connection
            // state for preview fallback polling.
            let consoleEventsConnected = false;

            function setConsoleEventsConnected(connected) {
              consoleEventsConnected = connected === true;
              return consoleEventsConnected;
            }

            function isConsoleEventsConnected() {
              return consoleEventsConnected;
            }

            function shouldUsePreviewFallbackPolling() {
              return !consoleEventsConnected;
            }

            // Returns true (and emits a diagnostic warn) when msg.sessionId no
            // longer matches the active session; callers MUST early-return
            // without mutating state or notifying. Broadcasts without a
            // sessionId pass through.
            function dropStaleSse(msg, activeSessionId) {
              if (msg?.sessionId && msg.sessionId !== activeSessionId) {
                console.warn('[sse] dropping stale response for session', msg.sessionId);
                return true;
              }
              return false;
            }
```

- [ ] **Step 4: Gate preview polling**

In `fixthis-mcp/src/main/console/preview.js`, replace `startLivePreviewPolling()` with:

```js
            function startLivePreviewPolling() {
              stopLivePreviewPolling();
              if (!shouldUsePreviewFallbackPolling()) return;
              const intervalMs = configuredPreviewIntervalMs();
              if (!intervalMs) return;
              livePreviewTimer = setInterval(() => {
                if (shouldPollPreview()) refreshPreview().catch(showError);
              }, intervalMs);
            }
```

- [ ] **Step 5: Track EventSource open/error**

In `fixthis-mcp/src/main/console/events.js`, add this after the `on` helper:

```js
              source.onopen = () => {
                setConsoleEventsConnected(true);
                stopLivePreviewPolling();
              };
```

Then replace the current `source.onerror` assignment with:

```js
              source.onerror = () => {
                setConsoleEventsConnected(false);
                if (state.connection && !state.connection.sessionsPollingPaused) setSessionsPollingPaused(true);
                startLivePreviewPolling();
              };
```

- [ ] **Step 6: Run reliability tests**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs scripts/consoleEvents-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Run Studio workflow policy tests**

Run:

```bash
node --test scripts/studioWorkflow-test.mjs scripts/studioWorkflowIntegration-test.mjs
```

Expected: PASS and stale-preview Save to MCP still requires the workflow confirmation boundary.

- [ ] **Step 8: Rebuild console assets**

Run:

```bash
node scripts/build-console-assets.mjs
node --test scripts/build-console-assets-test.mjs
```

Expected: PASS.

- [ ] **Step 9: Commit**

Run:

```bash
git add scripts/studioReliabilityContract-test.mjs fixthis-mcp/src/main/console/sse.js fixthis-mcp/src/main/console/events.js fixthis-mcp/src/main/console/preview.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): use preview polling as SSE fallback"
```

## Task 4: Harden Draft Recovery Ownership

**Files:**
- Modify: `scripts/draftRecoveryMatrix-test.mjs`
- Modify: `fixthis-mcp/src/main/console/draftUseCases.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/pendingRecoveryUi.js`

- [ ] **Step 1: Add failing recovery ownership tests**

Append these tests to `scripts/draftRecoveryMatrix-test.mjs`:

```js
test('draftRecoveryOwnership classifies deleted session recovery as discard-only', () => {
  const runtime = loadRuntime();
  const recovery = runtime.draftWorkspaceRecoveryEnvelope(workspaceWithItems([
    { draftItemId: 'draft-1', comment: 'Recover me', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]));

  const ownership = runtime.draftRecoveryOwnership(recovery, { deleted: true });

  assert.equal(ownership.mode, 'deleted');
  assert.equal(ownership.canResume, false);
  assert.equal(ownership.canRecapture, false);
  assert.equal(ownership.shouldAutoRestore, false);
});

test('draftRecoveryOwnership classifies closed session recovery as recapture-only', () => {
  const runtime = loadRuntime();
  const recovery = runtime.draftWorkspaceRecoveryEnvelope(workspaceWithItems([
    { draftItemId: 'draft-1', comment: 'Recover me', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]));

  const ownership = runtime.draftRecoveryOwnership(recovery, { status: 'closed' });

  assert.equal(ownership.mode, 'closed');
  assert.equal(ownership.canResume, false);
  assert.equal(ownership.canRecapture, true);
  assert.equal(ownership.shouldAutoRestore, false);
});

test('draftRecoveryOwnership auto-restores pin-only open-session recovery', () => {
  const runtime = loadRuntime();
  const recovery = runtime.draftWorkspaceRecoveryEnvelope(workspaceWithItems([
    { draftItemId: 'draft-1', comment: '', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]));

  const ownership = runtime.draftRecoveryOwnership(recovery, { status: 'active' });

  assert.equal(ownership.mode, 'pin-only');
  assert.equal(ownership.canResume, true);
  assert.equal(ownership.canRecapture, true);
  assert.equal(ownership.shouldAutoRestore, true);
});
```

Also update `loadRuntime()` in the same file so the returned object includes:

```js
      draftRecoveryOwnership,
```

- [ ] **Step 2: Run the failing recovery tests**

Run:

```bash
node --test scripts/draftRecoveryMatrix-test.mjs
```

Expected: FAIL because `draftRecoveryOwnership` is not defined.

- [ ] **Step 3: Add recovery ownership policy**

In `fixthis-mcp/src/main/console/draftUseCases.js`, add this function after `hasCommentedRecovery()`:

```js
function draftRecoveryOwnership(recovery, session = null) {
  const total = recoveryItems(recovery).length;
  const commented = hasCommentedRecovery(recovery);
  const status = String(session?.status || '').toLowerCase();
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

- [ ] **Step 4: Apply ownership when loading current-session recovery**

In `fixthis-mcp/src/main/console/main.js`, replace this block in `loadPendingRecoveryForCurrentSession()`:

```js
              const restored = loadDraftRecoveryForSession(sessionId);
              const restoredSummary = draftRecoverySummary(restored);
              if (restoredSummary.total && restoredSummary.commented === 0 && hasRecoverablePreviewContext(restored)) {
                restorePendingRecoveryContext(restored);
                pendingRecovery = null;
                renderPendingRecoveryBanner();
                return;
              }
              pendingRecovery = restoredSummary.total ? restored : null;
              renderPendingRecoveryBanner();
```

with:

```js
              const restored = loadDraftRecoveryForSession(sessionId);
              const restoredSummary = draftRecoverySummary(restored);
              const ownership = draftRecoveryOwnership(restored, state.session);
              if (
                restoredSummary.total &&
                ownership.shouldAutoRestore &&
                ownership.canResume &&
                hasRecoverablePreviewContext(restored)
              ) {
                restorePendingRecoveryContext(restored);
                pendingRecovery = null;
                renderPendingRecoveryBanner();
                return;
              }
              pendingRecovery = restoredSummary.total ? { ...restored, recoveryOwnership: ownership } : null;
              renderPendingRecoveryBanner();
```

- [ ] **Step 5: Render closed/deleted recovery safely**

In `fixthis-mcp/src/main/console/pendingRecoveryUi.js`, update `renderPendingRecoveryBanner()` so the `canResume` and detail selection read:

```js
  const ownership = pendingRecovery?.recoveryOwnership || draftRecoveryOwnership(pendingRecovery, state.session);
  const canResume = ownership.canResume && hasRecoverablePreviewContext(pendingRecovery);
  const canRecapture = ownership.canRecapture;
  const detail = ownership.mode === 'deleted'
    ? 'This local draft belongs to a deleted session. Discard it to continue.'
    : ownership.mode === 'closed'
      ? 'This local draft belongs to a closed session. Recapture into an active session to continue.'
      : canResume
        ? 'Resume the local draft for this session.'
        : 'Recapture the current app screen to continue this local draft.';
```

Then update the `renderBoundaryDialog` call in the same function:

```js
  renderBoundaryDialog('pendingRecovery', { canResume, canRecapture, itemCount: summary.total });
```

In `handlePendingRecoveryBoundaryAction(action)`, update the `recapture` branch guard:

```js
  if (action === 'recapture') {
    const ownership = pendingRecovery?.recoveryOwnership || draftRecoveryOwnership(pendingRecovery, state.session);
    if (!ownership.canRecapture) return;
    hideBoundaryDialog('pendingRecovery');
    recapturePendingRecovery().catch(showError);
    return;
  }
```

- [ ] **Step 6: Run draft tests**

Run:

```bash
node --test scripts/draftRecoveryMatrix-test.mjs scripts/draftUseCases-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Rebuild console assets**

Run:

```bash
node scripts/build-console-assets.mjs
node --test scripts/build-console-assets-test.mjs
```

Expected: PASS.

- [ ] **Step 8: Commit**

Run:

```bash
git add scripts/draftRecoveryMatrix-test.mjs fixthis-mcp/src/main/console/draftUseCases.js fixthis-mcp/src/main/console/main.js fixthis-mcp/src/main/console/pendingRecoveryUi.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): classify draft recovery ownership"
```

## Task 5: Reject Closed-Session Durable Mutations Server-Side

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHandoffRoutesTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`

- [ ] **Step 1: Add closed-session batch/update/delete route tests**

Add these tests to `ConsoleFeedbackItemRoutesTest.kt` near the existing batch idempotency tests:

```kotlin
    @Test
    fun batchItemsApiRejectsClosedSession() {
        withTempProject("fixthis-console-closed-batch") { projectRoot ->
            val store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L).next,
                idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
            )
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = store,
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
            )
            val session = service.openSession(null, newSession = true)
            val preview = runBlocking { service.capturePreview(session.sessionId) }
            store.closeSession(session.sessionId)

            withConsoleServer(service) { server ->
                val body = """
                    {
                      "sessionId": "${session.sessionId}",
                      "previewId": "${preview.previewId}",
                      "workspaceId": "workspace-closed",
                      "items": [
                        {
                          "draftItemId": "draft-closed",
                          "targetType": "area",
                          "bounds": { "left": 1, "top": 2, "right": 3, "bottom": 4 },
                          "comment": "must reject"
                        }
                      ]
                    }
                """.trimIndent()

                val response = ConsoleHttpTestClient(server.url).postJson("/api/items/batch", body)

                assertEquals(400, response.statusCode)
                assertTrue(response.body.contains("SESSION_CLOSED"), response.body)
            }
        }
    }

    @Test
    fun updateAndDeleteItemRoutesRejectClosedSession() {
        val fixture = newConsoleSessionFixture(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
            idGenerator = FakeIds("session-1", "screen-1", "item-1").next,
        )
        fixture.use { context ->
            val service = context.service
            val store = context.store
            val server = context.server
            val session = service.openSession(null, newSession = true)
            store.addScreen(
                session.sessionId,
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 100L,
                    displayName = "Screen 1",
                ),
            )
            val item = store.addItem(
                session.sessionId,
                AnnotationDto(
                    itemId = "pending",
                    screenId = "screen-1",
                    createdAtEpochMillis = 0L,
                    updatedAtEpochMillis = 0L,
                    target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                    comment = "before close",
                ),
            )
            store.closeSession(session.sessionId)

            val update = ConsoleHttpTestClient(server.url).connection(
                "/api/items/${item.itemId}",
                method = "PUT",
                body = """{"sessionId":"${session.sessionId}","comment":"after close"}""",
            )
            val delete = ConsoleHttpTestClient(server.url).connection(
                "/api/items/${item.itemId}?sessionId=${session.sessionId}",
                method = "DELETE",
            )

            assertEquals(400, update.responseCode)
            assertTrue(
                update.errorStream.bufferedReader().readText().contains("SESSION_CLOSED"),
            )
            assertEquals(400, delete.responseCode)
            assertTrue(
                delete.errorStream.bufferedReader().readText().contains("SESSION_CLOSED"),
            )
        }
    }
```

- [ ] **Step 2: Add closed-session handoff route test**

Add this test to `ConsoleHandoffRoutesTest.kt`:

```kotlin
    @Test
    fun agentHandoffRejectsClosedSession() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
            idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1").next,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        store.addScreen(session.sessionId, SnapshotDto("screen-1", 100L, displayName = "Screen 1"))
        val item = store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "handoff me",
            ),
        )
        store.closeSession(session.sessionId)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                "/api/agent-handoffs",
                """{"sessionId":"${session.sessionId}","itemIds":["${item.itemId}"]}""",
            )

            assertEquals(400, response.statusCode)
            assertTrue(response.body.contains("SESSION_CLOSED"), response.body)
        } finally {
            server.stop()
        }
    }
```

- [ ] **Step 3: Run the failing route tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleFeedbackItemRoutesTest" --tests "*ConsoleHandoffRoutesTest" --no-daemon
```

Expected: FAIL for at least one closed-session mutation path that currently accepts durable mutation.

- [ ] **Step 4: Add a reusable closed-session guard**

In `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`, add this helper near the event log helpers:

```kotlin
    private fun requireOpenSessionForMutation(sessionId: String, type: String) {
        val session = getSessionLocked(sessionId)
        if (session.status == SessionStatusDto.CLOSED) {
            throw FeedbackSessionException(
                "SESSION_CLOSED: Cannot run $type on a closed feedback session.",
            )
        }
    }
```

Then update `withEventBackedMutation()` to call it before `prepare()`:

```kotlin
    private fun <T> withEventBackedMutation(
        sessionId: String,
        type: String,
        prepare: () -> Pair<JsonObject, () -> T>,
    ): T {
        val result = synchronized(lock) {
            requireOpenSessionForMutation(sessionId, type)
            val (payload, mutate) = prepare()
            // Throws EventLogException on failure, so mutate() is never reached.
            journal.append(sessionId = sessionId, type = type, payload = payload)
            mutate()
        }
        compactEventLogAfterMutation(sessionId)
        return result
    }
```

Also update `withOptionalEventBackedMutation()` the same way:

```kotlin
    private fun <T> withOptionalEventBackedMutation(
        sessionId: String,
        type: String,
        prepare: () -> Pair<JsonObject, () -> T>?,
        noop: () -> T,
    ): T {
        val result = synchronized(lock) {
            requireOpenSessionForMutation(sessionId, type)
            val (payload, mutate) = prepare() ?: return@synchronized noop()
            journal.append(sessionId = sessionId, type = type, payload = payload)
            mutate()
        }
        compactEventLogAfterMutation(sessionId)
        return result
    }
```

- [ ] **Step 5: Run route/session tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleFeedbackItemRoutesTest" --tests "*ConsoleHandoffRoutesTest" --tests "*FeedbackSessionServiceClaimTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHandoffRoutesTest.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt
git commit -m "fix(mcp): reject closed session durable mutations"
```

## Task 6: Add Browser Reliability Proof

**Files:**
- Create: `scripts/console-browser-reliability.mjs`
- Modify: `scripts/console-fixture/fakeBridgeServer.mjs`
- Modify: `package.json`

- [ ] **Step 1: Add fake bridge SSE support and idempotent batch save**

In `scripts/console-fixture/fakeBridgeServer.mjs`, add these variables near `requestLog`:

```js
  const eventClients = new Set();
  let eventId = 0;
  const savedDraftKeys = new Set();
```

Add this helper near `json()`:

```js
function sseFrame(name, data, id) {
  return `id: ${id}\nevent: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
}
```

Inside `startFakeBridge()`, add this helper before `const server = http.createServer`:

```js
  function emitEvent(name, data) {
    eventId += 1;
    const frame = sseFrame(name, data, eventId);
    for (const client of eventClients) {
      client.write(frame);
    }
  }
```

Replace the `/api/events` route with:

```js
    if (url.pathname === '/api/events' && req.method === 'GET') {
      res.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-store',
        connection: 'keep-alive',
      });
      eventClients.add(res);
      res.write(sseFrame('snapshot', {
        session,
        sessions: { sessions: Array.from(sessionsById.values()).map(sessionSummary) },
        connection: {
          state: scenarioState.forceState || 'READY',
          connection: 'connected',
          selectedDevice: { serial: 'fake-device', label: 'Fake Device', state: 'device', selected: true },
        },
      }, ++eventId));
      req.on('close', () => eventClients.delete(res));
      return;
    }
```

In `/api/items/batch`, replace the item append loop with idempotent draft-key handling:

```js
      for (const draft of items) {
        const draftKey = body.workspaceId && draft.draftItemId ? `${body.workspaceId}\u0000${draft.draftItemId}` : null;
        if (draftKey && savedDraftKeys.has(draftKey)) continue;
        if (draftKey) savedDraftKeys.add(draftKey);
        session.items.push({
          itemId: `item-${session.items.length + 1}`,
          screenId: screen.screenId,
          createdAtEpochMillis: now,
          updatedAtEpochMillis: now,
          target: {
            type: draft.targetType || 'visual_area',
            boundsInWindow: draft.bounds || { left: 10, top: 10, right: 80, bottom: 80 },
          },
          label: draft.label || 'First-run annotation',
          severity: draft.severity || 'med',
          comment: draft.comment || 'First-run annotation',
          sequenceNumber: session.items.length + 1,
          delivery: 'draft',
          status: 'open',
          clientWorkspaceId: body.workspaceId || null,
          clientDraftItemId: draft.draftItemId || null,
        });
      }
```

After mutating `session` in `/api/items/batch`, `/api/agent-handoffs`, and `/api/session/open`, call:

```js
      emitEvent('session-updated', { sessionId: session.sessionId, session });
      emitEvent('sessions-updated', { sessionId: session.sessionId, sessions: { sessions: Array.from(sessionsById.values()).map(sessionSummary) } });
```

In `/api/preview`, before `json(res, fakePreview(previewSessionId));`, store the preview and emit:

```js
      const preview = fakePreview(previewSessionId);
      emitEvent('preview-ready', { sessionId: previewSessionId, preview });
      json(res, preview);
```

Add these methods to the returned `fixture` object:

```js
    openSession,
    currentSession: () => session,
    closeEventClients: () => {
      for (const client of Array.from(eventClients)) client.end();
      eventClients.clear();
    },
    emitSessionUpdated: () => {
      emitEvent('session-updated', { sessionId: session.sessionId, session });
      emitEvent('sessions-updated', { sessionId: session.sessionId, sessions: { sessions: Array.from(sessionsById.values()).map(sessionSummary) } });
      return session;
    },
    emitPreviewReady: (sessionId = session.sessionId, overrides = {}) => {
      const preview = { ...fakePreview(sessionId), ...overrides };
      emitEvent('preview-ready', { sessionId, preview });
      return preview;
    },
    eventClientCount: () => eventClients.size,
```

- [ ] **Step 2: Create browser reliability proof script**

Create `scripts/console-browser-reliability.mjs`:

```js
#!/usr/bin/env node
import assert from 'node:assert/strict';
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';

async function loadPlaywright() {
  return import('playwright');
}

async function withBrowser(fn) {
  const playwright = await loadPlaywright();
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  const browser = await playwright.chromium.launch({ headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1024, height: 768 } });
    await fn({ fixture, context });
  } finally {
    await browser.close();
    await fixture.close();
  }
}

async function waitUntil(predicate, timeoutMs = 8000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error('timed out waiting for condition');
}

async function openConsolePage(context, url) {
  const page = await context.newPage();
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => Boolean(window.FixThisConsoleDebug?.getState), null, { timeout: 8000 });
  return page;
}

async function postJson(page, path, body) {
  return page.evaluate(async ({ path, body }) => {
    const response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'If-Match': '*' },
      body: JSON.stringify(body),
    });
    return {
      status: response.status,
      json: await response.json(),
    };
  }, { path, body });
}

async function testTwoTabSseSync() {
  await withBrowser(async ({ fixture, context }) => {
    const second = await openConsolePage(context, fixture.url);
    await openConsolePage(context, fixture.url);
    await waitUntil(() => fixture.eventClientCount() >= 2);

    const item = fixture.seedAnnotation({ itemId: 'item-sse-sync', comment: 'Sync me' });
    fixture.emitSessionUpdated();

    await second.waitForFunction(
      () => window.FixThisConsoleDebug.getState().session?.items?.some((candidate) => candidate.itemId === 'item-sse-sync'),
      null,
      { timeout: 8000 },
    );
    const visible = await second.evaluate(() => window.FixThisConsoleDebug.getState().session.items.map((candidate) => candidate.itemId));
    assert.ok(visible.includes(item.itemId), `receiver tab did not see ${item.itemId}: ${visible.join(', ')}`);
  });
}

async function testLatePreviewIsolation() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    fixture.createSession({ sessionId: 'session-a', title: 'Session A' });
    fixture.createSession({ sessionId: 'session-b', title: 'Session B' });
    await page.evaluate(async () => {
      await fetch('/api/session/open', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: 'session-b' }),
      });
    });
    await page.waitForFunction(
      () => window.FixThisConsoleDebug.getState().session?.sessionId === 'session-b',
      null,
      { timeout: 8000 },
    );
    fixture.emitPreviewReady('session-a', { previewId: 'old-preview' });
    fixture.emitPreviewReady('session-b', { previewId: 'new-preview' });

    await page.waitForFunction(
      () => window.FixThisConsoleDebug.getState().preview?.previewId === 'new-preview',
      null,
      { timeout: 8000 },
    );
    const preview = await page.evaluate(() => window.FixThisConsoleDebug.getState().preview);
    assert.equal(preview.previewId, 'new-preview');
    assert.notEqual(preview.previewId, 'old-preview');
  });
}

async function testEventSourceReconnectRecovery() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    await waitUntil(() => fixture.eventClientCount() >= 1);
    fixture.closeEventClients();
    await waitUntil(() => fixture.eventClientCount() >= 1);

    const item = fixture.seedAnnotation({ itemId: 'item-after-reconnect', comment: 'Reconnect sync' });
    fixture.emitSessionUpdated();

    await page.waitForFunction(
      () => window.FixThisConsoleDebug.getState().session?.items?.some((candidate) => candidate.itemId === 'item-after-reconnect'),
      null,
      { timeout: 8000 },
    );
    const visible = await page.evaluate(() => window.FixThisConsoleDebug.getState().session.items.map((candidate) => candidate.itemId));
    assert.ok(visible.includes(item.itemId), `reconnected tab did not see ${item.itemId}: ${visible.join(', ')}`);
  });
}

async function testRepeatedSaveToMcpIdempotency() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    const preview = await page.evaluate(async () => {
      const response = await fetch('/api/preview');
      return response.json();
    });
    const body = {
      sessionId: 'session-1',
      previewId: preview.previewId,
      workspaceId: 'workspace-idempotent',
      screen: preview.screen,
      items: [{
        draftItemId: 'draft-idempotent',
        targetType: 'area',
        bounds: { left: 10, top: 10, right: 80, bottom: 80 },
        comment: 'Save once only',
      }],
    };

    const first = await postJson(page, '/api/items/batch', body);
    const duplicate = await postJson(page, '/api/items/batch', body);

    assert.equal(first.status, 200);
    assert.equal(duplicate.status, 200);
    const saved = fixture.currentSession().items.filter((item) => item.clientDraftItemId === 'draft-idempotent');
    assert.equal(saved.length, 1);
  });
}

async function run() {
  await testTwoTabSseSync();
  await testLatePreviewIsolation();
  await testEventSourceReconnectRecovery();
  await testRepeatedSaveToMcpIdempotency();
  console.log('PASS console browser reliability proof');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
```

- [ ] **Step 3: Add npm script**

In `package.json`, add near the other console scripts:

```json
"console:browser:reliability": "node scripts/console-browser-reliability.mjs",
```

- [ ] **Step 4: Run browser proof**

Run:

```bash
npm run console:browser:reliability
```

Expected: PASS.

- [ ] **Step 5: Run full reliability group**

Run:

```bash
npm run console:reliability:test
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add scripts/console-browser-reliability.mjs scripts/console-fixture/fakeBridgeServer.mjs package.json
git commit -m "test(console): add browser reliability proof"
```

## Final Verification

- [ ] **Step 1: Run console reliability tests**

Run:

```bash
npm run console:reliability:test
```

Expected: PASS.

- [ ] **Step 2: Run preview/session/draft JS tests touched by this plan**

Run:

```bash
node --test scripts/previewUseCases-test.mjs scripts/consoleEvents-test.mjs scripts/sessionMismatchIgnore-test.mjs scripts/draftRecoveryMatrix-test.mjs scripts/draftUseCases-test.mjs scripts/studioReliabilityContract-test.mjs scripts/studioWorkflow-test.mjs scripts/studioWorkflowIntegration-test.mjs
```

Expected: PASS.

- [ ] **Step 3: Run browser proof**

Run:

```bash
npm run console:browser:reliability
```

Expected: PASS.

- [ ] **Step 4: Run server route/session tests touched by this plan**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleFeedbackItemRoutesTest" --tests "*ConsoleHandoffRoutesTest" --tests "*FeedbackSessionServiceClaimTest" --tests "*ConsoleEventsRoutesTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Run bundle freshness check**

Run:

```bash
node scripts/build-console-assets.mjs
node --test scripts/build-console-assets-test.mjs
```

Expected: PASS and no unexpected bundle drift beyond files changed by the tasks.

- [ ] **Step 6: Run git whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

## Clean Architecture And SOLID Review Gate

Before opening a PR or asking for final review, verify:

- `:fixthis-compose-core` has no changes for this plan.
- New preview logic is split between pure `previewFsm`/`previewUseCases` and browser adapter code in `preview.js`/`events.js`.
- Draft recovery ownership lives in `draftUseCases.js`; DOM rendering only reads the policy output.
- Server durable mutation rejection lives in `FeedbackSessionStoreDelegate`, not in browser-only checks.
- Browser proof uses fixture APIs and visible state, not private DOM shape unrelated to the user contract.
- No persisted MCP JSON fields are renamed.
