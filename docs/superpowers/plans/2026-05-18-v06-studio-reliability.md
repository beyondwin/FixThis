# v0.6 Studio Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## Change History

- 2026-05-18 — Audit vs source (`fixthis-mcp/src/main/console/*`, `scripts/console-*`):
  - Task 1 Step 1 — fix backwards regex (`event.workspaceId !== state.workspace.context.workspaceId` is the actual source order) and drop the `deleteRecovery` assertion (that effect lives in `persistDraftWorkspace()`, not in `reduceDraftSaveSucceeded`).
  - Task 3 Step 2 / Step 4 — drop the `data.sessions?.find()` pattern. The actual polling adapter does `await sessResp.json()` against the by-id session endpoint and returns a single session; the existing fence is already correct (`pollingBrowserAdapter.js:31-44`).
  - Task 1 Step 3 — register the new group between `session` and `harness`; clarify that `harness` is reserved for the Playwright/scenarios runner.
  - Task 4 Step 5 — narrow the `git add` to the routes file plus any explicitly touched store/service files; do not stage the whole `fixthis-mcp` tree.
  - Task 5 — removed. The existing console harness uses `apply(fixture, options)` mutators against a Playwright fake bridge (see `scripts/console-harness.mjs` SCENARIOS table); the proposed `{ name, description, steps: [...] }` shape does not fit. Tasks 1–4 already cover the v0.6 reliability claim via reducer/use-case/route tests, and existing `preview`/`session` groups cover user-visible blocked/stale paths.

**Goal:** Make FixThis Studio preserve, save, recover, and display handoffs correctly through repeated session, draft, retry, stale-preview, and blocked-device flows.

**Architecture:** Strengthen existing console contract tests and add one reliability test group. Keep behavior changes inside existing draft/session/preview boundaries, and prefer reducer/use-case checks over incidental DOM shape assertions.

**Tech Stack:** Browser console JavaScript, Node.js `node:test`, existing console fixture harness, Kotlin route/session tests in `:fixthis-mcp`.

---

## Scope Check

This plan implements Track B from `docs/superpowers/specs/2026-05-18-v06-umbrella-roadmap-design.md`.

It does not change source matching, edit-surface intelligence, release readiness rules, Android runtime inspection, or public install channels. Track A and Track C have separate plans.

## File Structure

### Create

- `scripts/studioReliabilityContract-test.mjs`
  - Low-cost source/runtime contract tests for the Studio reliability claim.
- `scripts/draftRecoveryMatrix-test.mjs`
  - Pure JavaScript tests for browser draft recovery ownership.

### Modify

- `scripts/console-tests.json`
  - Add `reliability` group between `session` and `harness`.
- `package.json`
  - Add `console:reliability:test`.
- `fixthis-mcp/src/main/console/draftUseCases.js`
  - Clarify recovery outcomes where current behavior is implicit. As of audit
    on 2026-05-18, `persistDraftWorkspace()` already calls
    `ports.storage?.deleteWorkspace?.(...)` before emitting `SAVE_SUCCEEDED`;
    the pin-only `resolveLifecycleBoundary` path is what may still need fixing.
- `fixthis-mcp/src/main/console/domain/consoleReducer.js`
  - Fence stale save/capture/session effects through generation and active
    session checks. As of audit, both `reducePreviewCaptureSucceeded` and
    `reduceDraftSaveSucceeded` already hold the required generation/session/
    workspace fences; only re-touch if a contract test below fails.
- `fixthis-mcp/src/main/console/pollingBrowserAdapter.js`
  - Ensure session polling cannot revive deleted or closed displayed sessions.
    Already in place via the by-id fetch + `clearDisplayedSessionState()` path;
    only re-touch if Task 3 contract test fails.
- `scripts/sessionMismatchIgnore-test.mjs`
  - Add assertions for session polling and saved-item mutation paths.
- `scripts/draftUseCases-test.mjs`
  - Add Save to MCP completion and pin-only residual coverage.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
  - Add duplicate-save/idempotency regression coverage where server routes own persisted item behavior.

## Task 1: Add A Studio Reliability Test Group

**Files:**
- Create: `scripts/studioReliabilityContract-test.mjs`
- Modify: `scripts/console-tests.json`
- Modify: `package.json`

- [ ] **Step 1: Create the failing reliability contract test**

Create `scripts/studioReliabilityContract-test.mjs`:

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const reducer = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/domain/consoleReducer.js'), 'utf8');
const draftUseCases = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftUseCases.js'), 'utf8');
const polling = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pollingBrowserAdapter.js'), 'utf8');
const annotations = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const bodyStart = source.indexOf('{', start);
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

test('preview capture success is fenced by generation and active session', () => {
  const capture = body(reducer, 'function reducePreviewCaptureSucceeded(state, event)');
  assert.match(capture, /event\.generation !== state\.effectsGeneration/);
  assert.match(capture, /event\.sessionId !== state\.activeSessionId/);
  assert.match(capture, /isDraftWorkspace\(state\.workspace\)/);
});

test('draft save success is fenced by generation and original workspace id', () => {
  const save = body(reducer, 'function reduceDraftSaveSucceeded(state, event)');
  assert.match(save, /event\.generation !== state\.effectsGeneration/);
  assert.match(save, /!isDraftWorkspace\(state\.workspace\)/);
  assert.match(save, /event\.workspaceId !== state\.workspace\.context\.workspaceId/);
});

test('Save to MCP completion clears browser recovery for the saved workspace', () => {
  const persist = body(draftUseCases, 'async function persistDraftWorkspace(workspace, ports, options = {})');
  assert.match(persist, /ports\.storage\?\.deleteWorkspace\?\(started\.context\.sessionId,\s*started\.workspaceId\)/);
  assert.match(persist, /SAVE_SUCCEEDED/);
});

test('session polling clears displayed session only after checking the current displayed id', () => {
  assert.match(polling, /const activeDisplayedSessionId = displayedSessionId\(\);/);
  assert.match(polling, /clearDisplayedSessionState\(\);/);
  assert.match(polling, /fresh && fresh\.sessionId === activeDisplayedSessionId && !isClosedSession\(fresh\)/);
});

test('batch save goes through command queue and expected revision', () => {
  const persist = body(annotations, 'async function persistPendingFeedbackItems(options = {})');
  assert.match(persist, /ensureDraftCommandQueue\(\)\.enqueue/);
  assert.match(persist, /expectedRevision:\s*draftWorkspace\.revision/);
});
```

- [ ] **Step 2: Run the new test and verify current baseline**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: PASS if the current contracts already hold. If this fails, keep the failure and fix it in the task that owns the missing contract.

- [ ] **Step 3: Register the reliability group**

In `scripts/console-tests.json`, add:

```json
"reliability": [
  "scripts/studioReliabilityContract-test.mjs",
  "scripts/sessionMismatchIgnore-test.mjs",
  "scripts/draftUseCases-test.mjs",
  "scripts/draftRecoveryMatrix-test.mjs"
]
```

Place it between the existing `session` and `harness` groups. The `harness`
group is reserved for the Playwright/`scenarios-test.mjs` runner; the new
`reliability` group is plain `node --test` reducer/use-case coverage and
should not depend on Playwright.

- [ ] **Step 4: Add the npm script**

In `package.json`, add:

```json
"console:reliability:test": "node scripts/run-console-tests.mjs reliability"
```

Place it after `console:session:test`.

- [ ] **Step 5: Run the group and verify it fails because the matrix test is missing**

Run:

```bash
npm run console:reliability:test
```

Expected: FAIL with a missing `scripts/draftRecoveryMatrix-test.mjs` file.

- [ ] **Step 6: Commit the reliability group skeleton**

Run:

```bash
git add scripts/studioReliabilityContract-test.mjs scripts/console-tests.json package.json
git commit -m "test: add Studio reliability console test group"
```

## Task 2: Add The Draft Recovery Matrix

**Files:**
- Create: `scripts/draftRecoveryMatrix-test.mjs`
- Modify: `fixthis-mcp/src/main/console/draftUseCases.js`

- [ ] **Step 1: Write matrix tests**

Create `scripts/draftRecoveryMatrix-test.mjs`:

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = [
  'fixthis-mcp/src/main/console/draftWorkspace.js',
  'fixthis-mcp/src/main/console/draftWorkspaceHistory.js',
  'fixthis-mcp/src/main/console/boundaryPolicy.js',
  'fixthis-mcp/src/main/console/draftPorts.js',
  'fixthis-mcp/src/main/console/draftUseCases.js',
].map(file => readFileSync(resolve(root, file), 'utf8')).join('\n');

function loadRuntime() {
  return new Function(`
    ${source}
    return {
      createDraftContext,
      createFrozenDraftWorkspace,
      draftWorkspaceRecoveryEnvelope,
      recoverDraftWorkspaceFromEnvelope,
      resolveLifecycleBoundary,
      hasCommentedRecovery,
      recoveryItems,
    };
  `)();
}

function workspaceWithItems(items) {
  const runtime = loadRuntime();
  return runtime.createFrozenDraftWorkspace({
    workspaceId: 'workspace-1',
    context: runtime.createDraftContext({
      sessionId: 'session-1',
      previewId: 'preview-1',
      screenId: 'screen-1',
      frozenAtEpochMillis: 1,
    }),
    screen: null,
    screenshotUrl: '/screenshot.png',
  }).constructor === Object
    ? {
        ...runtime.createFrozenDraftWorkspace({
          workspaceId: 'workspace-1',
          context: runtime.createDraftContext({
            sessionId: 'session-1',
            previewId: 'preview-1',
            screenId: 'screen-1',
            frozenAtEpochMillis: 1,
          }),
          screen: null,
          screenshotUrl: '/screenshot.png',
        }),
        items,
      }
    : assert.fail('workspace factory returned a non-object');
}

function ports(choice = 'discard') {
  const calls = [];
  return {
    calls,
    storage: {
      saveWorkspace: (...args) => calls.push(['saveWorkspace', ...args]),
      deleteWorkspace: (...args) => calls.push(['deleteWorkspace', ...args]),
    },
    boundaryPrompt: {
      choose: async () => choice,
    },
    recoveryPrompt: {
      choose: async () => choice,
    },
    feedbackApi: {
      saveDraftWorkspace: async () => ({ sessionId: 'session-1', items: [] }),
    },
  };
}

test('pin-only active draft is saved to browser recovery on session switch without prompting', async () => {
  const runtime = loadRuntime();
  const activeWorkspace = workspaceWithItems([
    { draftItemId: 'draft-1', comment: '', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]);
  const p = ports();

  const result = await runtime.resolveLifecycleBoundary({
    action: 'session-switch',
    targetSessionId: 'session-2',
    activeWorkspace,
    pendingRecovery: null,
    ports: p,
  });

  assert.equal(result.outcome, 'continue');
  assert.deepEqual(p.calls.map(call => call[0]), ['saveWorkspace']);
});

test('commented active draft opens boundary and preserves on cancel', async () => {
  const runtime = loadRuntime();
  const activeWorkspace = workspaceWithItems([
    { draftItemId: 'draft-1', comment: 'Make this smaller', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]);
  const p = ports('cancel');

  const result = await runtime.resolveLifecycleBoundary({
    action: 'session-switch',
    targetSessionId: 'session-2',
    activeWorkspace,
    pendingRecovery: null,
    ports: p,
  });

  assert.equal(result.outcome, 'cancel');
  assert.equal(result.nextWorkspace.workspaceId, 'workspace-1');
});

test('Save to MCP completion clears saved browser recovery', async () => {
  const runtime = loadRuntime();
  const activeWorkspace = workspaceWithItems([
    { draftItemId: 'draft-1', comment: 'Make this smaller', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]);
  const p = ports('save');

  const result = await runtime.resolveLifecycleBoundary({
    action: 'session-switch',
    targetSessionId: 'session-2',
    activeWorkspace,
    pendingRecovery: null,
    ports: p,
  });

  assert.equal(result.outcome, 'continue');
  assert.equal(result.savedSession.sessionId, 'session-1');
  assert.deepEqual(p.calls.map(call => call[0]), ['deleteWorkspace']);
});

test('pending recovery resume cancels navigation and restores workspace', async () => {
  const runtime = loadRuntime();
  const pendingRecovery = runtime.draftWorkspaceRecoveryEnvelope(workspaceWithItems([
    { draftItemId: 'draft-1', comment: 'Recover me', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]));
  const p = ports('resume');

  const result = await runtime.resolveLifecycleBoundary({
    action: 'session-switch',
    targetSessionId: 'session-2',
    activeWorkspace: null,
    pendingRecovery,
    ports: p,
  });

  assert.equal(result.outcome, 'cancel');
  assert.equal(result.nextWorkspace.workspaceId, 'workspace-1');
});
```

- [ ] **Step 2: Run matrix tests and verify baseline**

Run:

```bash
node --test scripts/draftRecoveryMatrix-test.mjs
```

Expected: PASS if current use-case behavior already matches the reliability contract. If any row fails, fix `draftUseCases.js` in Step 3.

- [ ] **Step 3: Fix `draftUseCases.js` only if a matrix row fails**

If the pin-only row fails, update the pin-only branch inside `resolveLifecycleBoundary()` to save browser recovery and clear active workspace:

```js
if (!activeCommented && action !== 'delete-session' && action !== 'clear-local-draft' && action !== 'clear-server-drafts') {
  ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(nextWorkspace));
  nextWorkspace = createEmptyDraftWorkspace();
}
```

If the Save to MCP row fails, ensure `persistDraftWorkspace()` deletes the saved workspace recovery after a successful save:

```js
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
```

- [ ] **Step 4: Run the reliability group**

Run:

```bash
npm run console:reliability:test
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add scripts/draftRecoveryMatrix-test.mjs fixthis-mcp/src/main/console/draftUseCases.js
git commit -m "test: cover Studio draft recovery matrix"
```

## Task 3: Strengthen Cross-Session Stale Response Gates

**Files:**
- Modify: `scripts/sessionMismatchIgnore-test.mjs`
- Modify: `fixthis-mcp/src/main/console/domain/consoleReducer.js`
- Modify: `fixthis-mcp/src/main/console/pollingBrowserAdapter.js`

- [ ] **Step 1: Add reducer stale-effect tests**

Append this test to `scripts/sessionMismatchIgnore-test.mjs`:

```js
test('console reducer drops stale save and capture completions by generation and session', () => {
  const reducerSource = readFileSync(
    resolve(root, 'fixthis-mcp/src/main/console/domain/consoleReducer.js'),
    'utf8',
  );
  const capture = extractFunction(reducerSource, 'function reducePreviewCaptureSucceeded');
  const save = extractFunction(reducerSource, 'function reduceDraftSaveSucceeded');

  assert.match(capture, /event\.generation !== state\.effectsGeneration/);
  assert.match(capture, /event\.sessionId !== state\.activeSessionId/);
  assert.match(save, /event\.generation !== state\.effectsGeneration/);
  assert.match(save, /event\.workspaceId !== state\.workspace\.context\.workspaceId/);
});
```

- [ ] **Step 2: Add polling stale-session test**

Append this test to `scripts/sessionMismatchIgnore-test.mjs`:

The polling adapter fetches a single session by id (`/api/sessions/:id`) and
treats the parsed body as the fresh session, then either merges it or clears
displayed state when the freshly fetched session is missing or closed. The
test should pin that contract, not invent a `sessions?.find()` shape that does
not exist in the source:

```js
test('session polling cannot keep a deleted displayed session alive', () => {
  const pollingSource = readFileSync(
    resolve(root, 'fixthis-mcp/src/main/console/pollingBrowserAdapter.js'),
    'utf8',
  );
  assert.match(pollingSource, /const activeDisplayedSessionId = displayedSessionId\(\);/);
  assert.match(pollingSource, /const fresh = await sessResp\.json\(\);/);
  assert.match(pollingSource, /fresh && fresh\.sessionId === activeDisplayedSessionId && !isClosedSession\(fresh\)/);
  assert.match(pollingSource, /clearDisplayedSessionState\(\);/);
});
```

- [ ] **Step 3: Run the tests and verify failure or pass**

Run:

```bash
node --test scripts/sessionMismatchIgnore-test.mjs
```

Expected: PASS if existing code already has the fences. If it fails, make the code changes in Step 4.

- [ ] **Step 4: Fix reducer and polling fences if needed**

If `reduceDraftSaveSucceeded()` lacks a workspace check, add this guard before mutating state. Match the existing source order (`event.workspaceId !== state.workspace.context.workspaceId`); the only-fire-when-non-blank guard keeps older event payloads compatible:

```js
if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
if (event.workspaceId && event.workspaceId !== state.workspace.context.workspaceId) {
  return { state, effects: [] };
}
```

If polling lacks the deleted-session clear, update the refresh path in `pollingBrowserAdapter.js`. The adapter fetches the displayed session by id (`/api/sessions/:id`), so `fresh` is the parsed body of that response, not a search over a list:

```js
const activeDisplayedSessionId = displayedSessionId();
if (activeDisplayedSessionId) {
  const sessResp = await fetch(`/api/sessions/${activeDisplayedSessionId}`);
  if (sessResp.ok) {
    const fresh = await sessResp.json();
    if (fresh && fresh.sessionId === activeDisplayedSessionId && !isClosedSession(fresh)) {
      mergeSessionIntoState(fresh);
    } else {
      clearDisplayedSessionState();
      if (fresh && !isClosedSession(fresh)) mergeSessionIntoState(fresh);
    }
  }
}
```

- [ ] **Step 5: Run session and reliability tests**

Run:

```bash
node --test scripts/sessionMismatchIgnore-test.mjs
npm run console:reliability:test
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add scripts/sessionMismatchIgnore-test.mjs \
  fixthis-mcp/src/main/console/domain/consoleReducer.js \
  fixthis-mcp/src/main/console/pollingBrowserAdapter.js
git commit -m "test: strengthen Studio stale session fences"
```

## Task 4: Lock Server-Side Duplicate Save Idempotency

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Extend the existing route-level idempotency test**

In `ConsoleFeedbackItemRoutesTest`, replace the body of
`batchItemsApiDoesNotDuplicateSameWorkspaceDraftItemWhenPreviewFallsBackToScreen`
with this version. It preserves the current same-draft retry assertion and adds
the v0.6 requirement that a retry containing one new draft item only appends the
new item:

```kotlin
withTempProject("fixthis-console-idempotent-batch") { projectRoot ->
    val service = FeedbackSessionService(
        bridge = FakeFixThisBridge(),
        store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L).next,
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1", "item-2", "item-3").next,
        ),
        projectRoot = projectRoot.absolutePath,
        defaultPackageName = "io.github.beyondwin.fixthis.sample",
    )
    service.openSession(null, newSession = true)
    val preview = runBlocking { service.capturePreview("session-1") }
    val screenJson = fixThisJson.encodeToString(preview.screen)

    withConsoleServer(service) { server ->
        val firstBody = """
            {
              "workspaceId": "workspace-a",
              "previewId": "${preview.previewId}",
              "screen": $screenJson,
              "items": [{
                "draftItemId": "draft-a",
                "targetType": "area",
                "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                "comment": "save once"
              }]
            }
        """.trimIndent()

        val retryWithNewItemBody = """
            {
              "workspaceId": "workspace-a",
              "previewId": "${preview.previewId}",
              "screen": $screenJson,
              "items": [
                {
                  "draftItemId": "draft-a",
                  "targetType": "area",
                  "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                  "comment": "save once"
                },
                {
                  "draftItemId": "draft-b",
                  "targetType": "area",
                  "bounds": {"left":50.0,"top":60.0,"right":90.0,"bottom":120.0},
                  "comment": "save second"
                }
              ]
            }
        """.trimIndent()

        val first = ConsoleHttpTestClient(server.url).postJson("/api/items/batch", firstBody)
        val duplicateRetry = ConsoleHttpTestClient(server.url).postJson(
            "/api/items/batch",
            firstBody,
            headers = mapOf("If-Match" to "*"),
        )
        val retryWithNewItem = ConsoleHttpTestClient(server.url).postJson(
            "/api/items/batch",
            retryWithNewItemBody,
            headers = mapOf("If-Match" to "*"),
        )

        assertEquals(200, first.statusCode)
        assertEquals(200, duplicateRetry.statusCode)
        assertEquals(200, retryWithNewItem.statusCode)
        val stored = service.getSession("session-1")
        assertEquals(1, stored.screens.size)
        assertEquals(2, stored.items.size)
        assertEquals(listOf("draft-a", "draft-b"), stored.items.map { it.clientDraftItemId })
        assertEquals(listOf("save once", "save second"), stored.items.map { it.comment })
        assertTrue(stored.items.all { it.clientWorkspaceId == "workspace-a" })
    }
}
```

- [ ] **Step 2: Run the focused route test and verify it fails or passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleFeedbackItemRoutesTest.batchItemsApiDoesNotDuplicateSameWorkspaceDraftItemWhenPreviewFallsBackToScreen" --no-daemon
```

Expected: PASS if current idempotency is complete. If it fails with duplicate `draft-a` items, fix the production route or session store path in Step 3.

- [ ] **Step 3: Fix production idempotency if needed**

If Step 2 fails, update the production save path so the retry key is `(clientWorkspaceId, clientDraftItemId)` and existing saved items are reused instead of appended.

The result must preserve these persisted item fields:

```kotlin
clientWorkspaceId = request.workspaceId
clientDraftItemId = requestItem.draftItemId
```

The retry response must return the existing saved item rather than creating a new item.

- [ ] **Step 4: Run route and store tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests "*ConsoleFeedbackItemRoutesTest" \
  --tests "*FeedbackSessionStoreTest" \
  --tests "*FeedbackDraftServiceTest" \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
# If Step 3 was needed, also add only the specific files you edited, e.g.
#   fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt
# Do not `git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp` — that stages the whole subtree.
git commit -m "test: lock duplicate draft save idempotency"
```

## Task 5: (removed)

The original Task 5 proposed a step-DSL scenario object
(`{ name, description, steps: [...] }`) and a `scenarios = { ... }` registry
that do not exist in the current console harness. The actual
`scripts/console-harness.mjs` exposes a `SCENARIOS = { 'kebab-case': { apply,
requiredViewports } }` table whose values are `apply(fixture, options)`
mutators against a Playwright fake bridge. Forcing a step-DSL on top would
either duplicate the runtime or require a Playwright assertion harness that is
out of scope for Track B.

Reliability under repeated Studio use is already covered by:

- Tasks 1–3 (reducer/use-case/polling contract tests) for state-pipeline
  reliability under session switch, stale preview, and recovery.
- Task 4 (`ConsoleFeedbackItemRoutesTest`) for duplicate-save idempotency.
- Existing `preview` and `session` test groups in `scripts/console-tests.json`
  for the user-visible blocked/stale paths called out in the umbrella spec.

If a future plan wants a Playwright scenario for v0.6, it should add an
`applyReliability(fixture, options)` mutator and register
`'reliability': { apply: applyReliability, requiredViewports: [...] }` in the
existing SCENARIOS table — not a parallel scenario object format.

## Task 6: Run The Track B Verification Set

**Files:**
- No file edits.

- [ ] **Step 1: Run reliability test group**

Run:

```bash
npm run console:reliability:test
```

Expected: PASS.

- [ ] **Step 2: Run related console groups**

Run:

```bash
npm run console:session:test
npm run console:draft:test
npm run console:preview:test
```

Expected: PASS.

- [ ] **Step 3: Run Kotlin route/session tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests "*ConsoleFeedbackItemRoutesTest" \
  --tests "*FeedbackSessionStoreTest" \
  --tests "*FeedbackDraftServiceTest" \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 4: (removed — see Task 5 note above)**

The original Track B verification ran `node scripts/console-harness.mjs
reliability`. That scenario no longer exists; the user-visible blocked/stale
paths are covered by the existing `preview` and `session` groups already
listed above.

- [ ] **Step 5: Run consistency checks**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: both commands exit 0.

- [ ] **Step 6: Record verification in PR notes**

Use this exact summary in the PR body:

```markdown
Track B verification:
- `npm run console:reliability:test`
- `npm run console:session:test`
- `npm run console:draft:test`
- `npm run console:preview:test`
- `./gradlew :fixthis-mcp:test --tests "*ConsoleFeedbackItemRoutesTest" --tests "*FeedbackSessionStoreTest" --tests "*FeedbackDraftServiceTest" --no-daemon`
- `node scripts/check-doc-consistency.mjs`
- `git diff --check`
```
