# Console Draft and Session Lifecycle Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make session registration/deletion, annotation add/delete/edit, draft save/recovery/removal, and handoff flows use one explicit lifecycle protocol so stale state and duplicate command paths cannot silently corrupt console state.

**Architecture:** Keep the current browser console bundle system and harden it in phases: add failing guardrail tests, remove duplicate canonical-vs-legacy side effects, route all pending draft edits through the `DraftWorkspace` reducer, fence save responses by revision, centralize active draft plus pending recovery boundaries, split local/server draft clearing, and return typed server validation errors. This is an incremental hardening pass, not the full canonical single-source rewrite.

**Tech Stack:** Plain browser JavaScript in `fixthis-mcp/src/main/console`, Node `node:test` scripts under `scripts`, Kotlin/JVM console HTTP routes under `:fixthis-mcp`, Gradle tests, generated console resources in `fixthis-mcp/src/main/resources/console`.

---

## File Structure

### Console Domain and Use Cases

- Modify `fixthis-mcp/src/main/console/draftWorkspace.js`: keep reducer as the only place that changes draft items and revisions.
- Modify `fixthis-mcp/src/main/console/draftUseCases.js`: add lifecycle boundary resolution for active workspace plus pending recovery; add recovery helper functions.
- Modify `fixthis-mcp/src/main/console/draftCommandQueue.js`: simplify stale response fencing so any workspace id or revision mismatch blocks response application.
- Modify `fixthis-mcp/src/main/console/draftPorts.js`: extend fake/browser port contract with a recovery boundary prompt.
- Modify `fixthis-mcp/src/main/console/draftApiAdapter.js`: keep stale node-to-area normalization; add typed error decoding and client-side bounds validation.
- Modify `fixthis-mcp/src/main/console/draftStorageAdapter.js`: keep schema v2 storage as the only active recovery namespace.

### Console Presentation and Runtime Wiring

- Modify `fixthis-mcp/src/main/console/annotations.js`: replace direct pending item mutation with reducer updates; split local/server draft clearing calls.
- Modify `fixthis-mcp/src/main/console/history.js`: route new/open/close/delete session through the shared lifecycle boundary; remove duplicate canonical dispatch side effects.
- Modify `fixthis-mcp/src/main/console/main.js`: move boundary business logic into `draftUseCases.js`; keep boot wiring and browser prompt adapters only.
- Modify `fixthis-mcp/src/main/console/preview.js`: remove duplicate annotate canonical dispatch while legacy flow owns preview capture.
- Modify `fixthis-mcp/src/main/console/prompt.js`: make save/copy/handoff use revision-fenced draft persistence and explicit session ids.
- Modify `fixthis-mcp/src/main/console/presentation/previewRegionView.js`: expose separate local and server draft clear controls or one context-sensitive control with explicit copy.
- Modify `fixthis-mcp/src/main/console/adapters/browserPorts.js`: remove obsolete endpoints and obsolete recovery namespaces.
- Regenerate `fixthis-mcp/src/main/resources/console/app.js` and `.map` through the asset build only.

### Kotlin Server

- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHttp.kt`: support typed JSON error bodies while preserving simple error responses.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`: map known session exceptions to stable error codes.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`: map draft validation failures to typed 400/409 JSON.
- Keep `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt` strict. Do not make the server accept missing node ids as node targets.

### Tests

- Modify `scripts/consoleCanonicalRuntimeContract-test.mjs`: static guardrails against duplicate command paths, dead endpoints, obsolete storage namespaces, and direct draft mutations.
- Modify `scripts/draftWorkspace-test.mjs`: reducer revision and immutable context coverage.
- Modify `scripts/draftUseCases-test.mjs`: lifecycle boundary and pending recovery behavior.
- Modify `scripts/draftCommandQueue-test.mjs`: stale response fencing.
- Modify `scripts/draftApiAdapter-test.mjs`: typed errors and client-side selection validation.
- Modify `scripts/pendingBoundaryGuard-test.mjs`: all lifecycle callers use the shared resolver.
- Modify `scripts/sessionScopedRequests-test.mjs`: explicit session id checks.
- Modify `scripts/console-browser-smoke.mjs`: browser scenarios for save-while-editing and delete-session-with-recovery.
- Modify or create Kotlin tests in `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`.
- Modify or create Kotlin tests in `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt`.

## Conventions

- Use TDD for every behavior change.
- Commit after each task, unless the task is interrupted before tests pass.
- Do not hand-edit `fixthis-mcp/src/main/resources/console/app.js` or `.map`.
- Do not commit `.fixthis/`.
- Do not weaken server validation to hide bad client state.
- Do not keep a production user event path where both canonical and legacy flows perform network/storage side effects.

## Task 1: Add Lifecycle Guardrail Tests

**Files:**
- Modify: `scripts/consoleCanonicalRuntimeContract-test.mjs`
- Modify: `scripts/draftWorkspace-test.mjs`
- Modify: `scripts/draftCommandQueue-test.mjs`
- Modify: `scripts/pendingBoundaryGuard-test.mjs`
- Modify: `scripts/draftApiAdapter-test.mjs`

- [ ] **Step 1: Add static tests for duplicate command paths and dead ports**

Append to `scripts/consoleCanonicalRuntimeContract-test.mjs`:

```js
function extractFunctionBody(sourceText, signature) {
  const start = sourceText.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const bodyStart = sourceText.indexOf('{', sourceText.indexOf(')', start));
  let depth = 0;
  for (let i = bodyStart; i < sourceText.length; i += 1) {
    if (sourceText[i] === '{') depth += 1;
    if (sourceText[i] === '}') {
      depth -= 1;
      if (depth === 0) return sourceText.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('legacy session navigation does not also dispatch canonical session effects', () => {
  const history = source('fixthis-mcp/src/main/console/history.js');
  const openBody = extractFunctionBody(history, 'async function openSession(sessionId)');
  assert.doesNotMatch(openBody, /store\.dispatch\(ConsoleEvents\.sessionRowClicked/);
  assert.match(openBody, /resolvePendingBeforeBoundary\('open-session',\s*sessionId\)/);
  assert.match(openBody, /requestJson\('\/api\/session\/open'/);
});

test('legacy annotate flow does not dispatch duplicate canonical preview capture', () => {
  const history = source('fixthis-mcp/src/main/console/history.js');
  const enterBody = extractFunctionBody(history, 'async function enterAnnotateMode()');
  assert.doesNotMatch(enterBody, /requestCanonicalPreviewCapture\(\)/);
  assert.match(enterBody, /await startDraftAnnotationFlow\(\)/);
});

test('browser console ports do not reference dead draft endpoints or obsolete recovery namespaces', () => {
  const ports = source('fixthis-mcp/src/main/console/adapters/browserPorts.js');
  assert.doesNotMatch(ports, /\/api\/feedback\/items/);
  assert.doesNotMatch(ports, /fixthis\.recovery\./);
  assert.doesNotMatch(ports, /fixthis\.draftWorkspace\./);
});
```

- [ ] **Step 2: Add static tests that pending detail edits cannot mutate item objects directly**

Append to `scripts/consoleCanonicalRuntimeContract-test.mjs`:

```js
test('pending annotation detail edits route through draft workspace update use case', () => {
  const detail = source('fixthis-mcp/src/main/console/presentation/annotationDetailView.js');
  const pendingBody = extractFunctionBody(detail, 'function renderAnnotationDetail(item, index)');
  assert.doesNotMatch(pendingBody, /\bitem\.(label|comment|severity|status)\s*=/);
  assert.match(pendingBody, /updatePendingDraftItem\(/);
});

test('focused pending comment flush does not mutate draft item directly', () => {
  const annotations = source('fixthis-mcp/src/main/console/annotations.js');
  const flushBody = extractFunctionBody(annotations, 'function flushFocusedPendingComment()');
  assert.doesNotMatch(flushBody, /\bitem\.comment\s*=/);
  assert.match(flushBody, /updatePendingDraftItem\(/);
});
```

- [ ] **Step 3: Add reducer test for draft update revision policy**

Append to `scripts/draftWorkspace-test.mjs`:

```js
test('every draft item update advances revision and keeps immutable context', () => {
  let workspace = m.createFrozenDraftWorkspace({
    workspaceId: 'ws-update',
    context,
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/screenshot.png',
  });
  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'ADD_ITEM',
    workspaceId: 'ws-update',
    draftItem: {
      draftItemId: 'draft-1',
      targetType: 'area',
      bounds: { left: 1, top: 1, right: 20, bottom: 20 },
      comment: '',
    },
  });
  const beforeContext = JSON.stringify(workspace.context);
  const beforeRevision = workspace.revision;

  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'UPDATE_ITEM',
    workspaceId: 'ws-update',
    draftItemId: 'draft-1',
    patch: { comment: 'updated text' },
  });

  assert.equal(workspace.revision, beforeRevision + 1);
  assert.equal(JSON.stringify(workspace.context), beforeContext);
  assert.equal(workspace.items[0].comment, 'updated text');
});
```

- [ ] **Step 4: Add stale save response test**

Append to `scripts/draftCommandQueue-test.mjs`:

```js
test('save response is stale when workspace revision changed while request was in flight', async () => {
  let workspace = { workspaceId: 'ws-a', revision: 2, lifecycle: 'editing', items: [{ draftItemId: 'draft-1', comment: 'newer local edit' }] };
  const events = [];
  const queue = m.createDraftCommandQueue({
    getWorkspace: () => workspace,
    setWorkspace: (next) => { workspace = next; events.push(['set', next.lifecycle]); },
    onStaleResponse: () => events.push(['stale']),
  });

  const result = await queue.enqueue(
    { kind: 'save', workspaceId: 'ws-a', expectedRevision: 1 },
    async () => ({ workspace: { lifecycle: 'empty', items: [] }, session: { sessionId: 'session-a' } }),
  );

  assert.equal(result.applied, false);
  assert.equal(result.reason, 'stale_before');
  assert.deepEqual(events, [['stale']]);
  assert.equal(workspace.items[0].comment, 'newer local edit');
});
```

- [ ] **Step 5: Add pending recovery boundary regression test**

Append to `scripts/pendingBoundaryGuard-test.mjs`:

```js
test('pending recovery only path does not silently continue past lifecycle boundary', () => {
  const mainSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
  const resolveBody = body(mainSource, 'async function resolvePendingBeforeBoundary(action, sessionId = null)');
  assert.doesNotMatch(
    resolveBody,
    /if \(pendingRecoveryItems\(pendingRecovery\)\.length && !hasActivePending\) \{[\s\S]*?return 'continue';[\s\S]*?\}/,
  );
  assert.match(resolveBody, /resolveLifecycleBoundary\(/);
});
```

- [ ] **Step 6: Add draft API typed error test**

Append to `scripts/draftApiAdapter-test.mjs`:

```js
test('browser API adapter preserves typed validation errors', async () => {
  const adapter = m.createDraftApiAdapter({
    fetchImpl: async () => ({
      ok: false,
      status: 400,
      json: async () => ({
        error: 'selected_node_missing',
        message: 'Selected node does not exist on preview: compose:0:merged:73',
        action: 'recapture_or_convert_to_area',
      }),
      text: async () => '{"error":"selected_node_missing"}',
    }),
  });

  await assert.rejects(
    () => adapter.saveDraftWorkspace(m.buildDraftWorkspaceSaveRequest(workspace)),
    (error) => {
      assert.equal(error.code, 'selected_node_missing');
      assert.match(error.message, /Selected node/);
      assert.equal(error.action, 'recapture_or_convert_to_area');
      return true;
    },
  );
});
```

- [ ] **Step 7: Run focused tests and verify they fail**

Run:

```bash
node --test \
  scripts/consoleCanonicalRuntimeContract-test.mjs \
  scripts/draftWorkspace-test.mjs \
  scripts/draftCommandQueue-test.mjs \
  scripts/pendingBoundaryGuard-test.mjs \
  scripts/draftApiAdapter-test.mjs
```

Expected: FAIL on duplicate dispatch, direct item mutation, pending recovery silent continue, and typed error decoding.

- [ ] **Step 8: Commit failing tests only if your workflow allows red commits**

If keeping red commits is not acceptable, leave the tests unstaged until Task 2 and Task 3 make the first subset pass. If red commits are acceptable:

```bash
git add scripts/consoleCanonicalRuntimeContract-test.mjs \
  scripts/draftWorkspace-test.mjs \
  scripts/draftCommandQueue-test.mjs \
  scripts/pendingBoundaryGuard-test.mjs \
  scripts/draftApiAdapter-test.mjs
git commit -m "test(console): cover draft lifecycle regressions"
```

## Task 2: Remove Duplicate Canonical/Legacy Side Effects

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/main/console/adapters/browserPorts.js`
- Modify: `scripts/consoleCanonicalRuntimeContract-test.mjs`

- [ ] **Step 1: Stop history row clicks from dispatching canonical session effects**

In `fixthis-mcp/src/main/console/history.js`, remove this line from `openSession(sessionId)`:

```js
store.dispatch(ConsoleEvents.sessionRowClicked(sessionId));
```

Keep the legacy path intact for this hardening pass:

```js
async function openSession(sessionId) {
  if (!sessionId) return;
  error.textContent = '';
  if (sessionId === state.session?.sessionId) {
    renderSessionsList();
    return;
  }
  if (sessionNavigationInFlight) {
    pendingSessionNavigationId = sessionId;
    renderSessionsList();
    return;
  }
  sessionNavigationInFlight = true;
  pendingSessionNavigationId = null;
  renderSessionsList();
  try {
    if (await resolvePendingBeforeBoundary('open-session', sessionId) !== 'continue') return;
    bumpSessionMutationGeneration();
    stopLivePreviewPolling();
    resetComposer(true, false);
    clearPreview();
    setConsoleSession(await withMutationLock(() => requestJson('/api/session/open', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId }),
    })));
    renderCurrentSessionList();
    renderInspectorRegion();
    await refresh();
    if (!latestPersistedScreen() && shouldAutoFetchPreview()) await refreshPreview();
    startLivePreviewPolling();
  } finally {
    sessionNavigationInFlight = false;
    const queuedSessionId = pendingSessionNavigationId;
    pendingSessionNavigationId = null;
    renderCurrentSessionList();
    if (queuedSessionId && queuedSessionId !== state.session?.sessionId) await openSession(queuedSessionId);
  }
}
```

- [ ] **Step 2: Stop annotate mode from dispatching duplicate canonical preview capture**

In `fixthis-mcp/src/main/console/history.js`, remove this line from `enterAnnotateMode()`:

```js
requestCanonicalPreviewCapture();
```

The legacy freeze path already captures or reuses a preview:

```js
if (!draftFlow()) {
  await startDraftAnnotationFlow();
} else {
  renderPreviewOnly();
  renderInspectorRegion();
}
```

In `fixthis-mcp/src/main/console/preview.js`, make the helper inert until the canonical migration owns the full runtime:

```js
function requestCanonicalPreviewCapture() {
  // Canonical preview effects are disabled while legacy draft freeze owns runtime capture.
}
```

- [ ] **Step 3: Remove dead browser port endpoints and obsolete recovery namespaces**

In `fixthis-mcp/src/main/console/adapters/browserPorts.js`, replace `saveDraft` and storage helpers with endpoint/storage names that match production or explicitly throw when not wired:

```js
sessionApi: Object.freeze({
  openSession: async (sessionId) => requestJson_('/api/session/open', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId }),
  }),
  listSessions: async () => requestJson_('/api/sessions'),
  currentSession: async () => requestJson_('/api/session'),
  saveDraft: async () => {
    throw new Error('Canonical saveDraft effect is disabled until DraftWorkspace save request construction is canonical.');
  },
}),
draftStorage: Object.freeze({
  saveRecovery: async (sessionId, workspace) => {
    const adapter = createDraftStorageAdapter(localStorage_);
    adapter.saveWorkspace({
      ...workspace,
      schemaVersion: 2,
      sessionId: sessionId || workspace?.context?.sessionId,
      workspaceId: workspace?.workspaceId || workspace?.context?.workspaceId,
    });
  },
  deleteRecovery: async (sessionId, workspaceId) => {
    const adapter = createDraftStorageAdapter(localStorage_);
    adapter.deleteWorkspace(sessionId, workspaceId);
  },
}),
```

Add `draftStorageAdapter.js` to the module dependency list for `browserPorts.js` if needed by the bundle `// @requires` header:

```js
// @requires draftStorageAdapter.js
```

- [ ] **Step 4: Run the duplicate-side-effect guardrail test**

Run:

```bash
node --test scripts/consoleCanonicalRuntimeContract-test.mjs
```

Expected: PASS for the new duplicate dispatch and dead port assertions. Existing broader canonical tests must still pass.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/console/preview.js \
  fixthis-mcp/src/main/console/adapters/browserPorts.js \
  scripts/consoleCanonicalRuntimeContract-test.mjs
git commit -m "fix(console): remove duplicate lifecycle side effects"
```

## Task 3: Route Pending Draft Edits Through the Workspace Reducer

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftUseCases.js`
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `scripts/draftUseCases-test.mjs`
- Modify: `scripts/consoleCanonicalRuntimeContract-test.mjs`

- [ ] **Step 1: Add update use case support for non-history typing updates**

In `fixthis-mcp/src/main/console/draftUseCases.js`, change `updateDraftItem` to accept options:

```js
function updateDraftItem(workspace, draftItemId, patch, options = {}) {
  const before = (workspace.items || []).find((item) => item.draftItemId === draftItemId);
  const next = reduceDraftWorkspace(workspace, { type: 'UPDATE_ITEM', workspaceId: workspace.workspaceId, draftItemId, patch });
  const after = (next.items || []).find((item) => item.draftItemId === draftItemId);
  if (!before || !after || options.recordHistory === false) return next;
  return { ...next, history: recordDraftUpdate(next.history, before, after) };
}
```

- [ ] **Step 2: Add a behavioral use case test**

Append to `scripts/draftUseCases-test.mjs`:

```js
test('updateDraftItem can update typing state without polluting undo history', () => {
  let workspace = {
    workspaceId: 'ws-a',
    revision: 1,
    lifecycle: 'editing',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    screen: {},
    screenshotUrl: '/shot.png',
    items: [{ draftItemId: 'draft-1', comment: '', label: 'Old label' }],
    history: { undoStack: [], redoStack: [] },
  };

  workspace = m.updateDraftItem(workspace, 'draft-1', { comment: 'typing' }, { recordHistory: false });

  assert.equal(workspace.revision, 2);
  assert.equal(workspace.items[0].comment, 'typing');
  assert.equal(workspace.history.undoStack.length, 0);
});
```

Add `updateDraftItem` to the `factory` return object if it is not already exported in that test file.

- [ ] **Step 3: Add a presentation helper for pending item updates**

In `fixthis-mcp/src/main/console/annotations.js`, add this helper near `flushFocusedPendingComment()`:

```js
function updatePendingDraftItem(draftItemId, patch, options = {}) {
  if (!draftWorkspace?.workspaceId || !draftItemId) return draftWorkspace;
  const nextWorkspace = updateDraftItem(draftWorkspace, draftItemId, patch, {
    recordHistory: options.recordHistory === true,
  });
  replaceDraftWorkspace(nextWorkspace);
  return nextWorkspace;
}
```

Change `flushFocusedPendingComment()` to:

```js
function flushFocusedPendingComment() {
  if (draftFocusIndex() == null) return;
  const item = draftItemList()[draftFocusIndex()];
  if (!item) return;
  const commentInput = pendingItems.querySelector('#annotationCommentInput');
  const nextComment = commentInput ? commentInput.value : comment.value;
  updatePendingDraftItem(item.draftItemId, { comment: nextComment }, { recordHistory: false });
}
```

- [ ] **Step 4: Replace direct pending detail mutations**

In `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`, replace each direct assignment in `renderAnnotationDetail(item, index)`:

```js
item.label = event.target.value;
item.comment = event.target.value;
item.severity = button.dataset.setSeverity;
item.status = button.dataset.setStatus;
```

with reducer updates:

```js
labelInput.addEventListener('input', event => {
  updatePendingDraftItem(item.draftItemId, { label: event.target.value }, { recordHistory: false });
  updateComposerState();
  renderPreviewOnly();
});

commentInput.addEventListener('input', event => {
  updatePendingDraftItem(item.draftItemId, { comment: event.target.value }, { recordHistory: false });
  updateComposerState();
});

pendingItems.querySelectorAll('[data-set-severity]').forEach(button => {
  button.addEventListener('click', () => {
    updatePendingDraftItem(item.draftItemId, { severity: button.dataset.setSeverity }, { recordHistory: true });
    renderInspectorRegion();
  });
});

pendingItems.querySelectorAll('[data-set-status]').forEach(button => {
  button.addEventListener('click', () => {
    updatePendingDraftItem(item.draftItemId, { status: button.dataset.setStatus }, { recordHistory: true });
    renderPreviewOnly();
    renderInspectorRegion();
    renderCurrentSessionList();
  });
});
```

- [ ] **Step 5: Run tests**

Run:

```bash
node --test \
  scripts/draftWorkspace-test.mjs \
  scripts/draftUseCases-test.mjs \
  scripts/consoleCanonicalRuntimeContract-test.mjs \
  scripts/pendingItemRecovery-test.mjs
```

Expected: PASS. The direct mutation static tests added in Task 1 must pass.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/draftUseCases.js \
  fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/console/presentation/annotationDetailView.js \
  scripts/draftUseCases-test.mjs \
  scripts/consoleCanonicalRuntimeContract-test.mjs
git commit -m "fix(console): route pending draft edits through reducer"
```

## Task 4: Harden Draft Save Stale Response Fencing

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftCommandQueue.js`
- Modify: `scripts/draftCommandQueue-test.mjs`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`

- [ ] **Step 1: Strengthen queue stale-after check**

In `fixthis-mcp/src/main/console/draftCommandQueue.js`, replace:

```js
if (!matchesMeta(current, meta) && result?.workspace?.workspaceId !== current?.workspaceId) {
  onStaleResponse(meta, result);
  return { applied: false, reason: 'stale_after' };
}
```

with:

```js
if (!matchesMeta(current, meta)) {
  onStaleResponse(meta, result);
  return { applied: false, reason: 'stale_after', result };
}
```

This ensures a changed revision blocks clearing the current workspace even if the stale response contains a workspace object.

- [ ] **Step 2: Add an in-flight save simulation test**

Append to `scripts/draftCommandQueue-test.mjs`:

```js
test('in-flight save cannot clear workspace after reducer edit increments revision', async () => {
  let workspace = {
    workspaceId: 'ws-a',
    revision: 2,
    lifecycle: 'editing',
    items: [{ draftItemId: 'draft-1', comment: 'old' }],
  };
  const events = [];
  let releaseSave;
  const queue = m.createDraftCommandQueue({
    getWorkspace: () => workspace,
    setWorkspace: (next) => { workspace = next; },
    onStaleResponse: () => events.push('stale'),
  });

  const save = queue.enqueue({ kind: 'save', workspaceId: 'ws-a', expectedRevision: 2 }, async () => {
    await new Promise((resolve) => { releaseSave = resolve; });
    return { workspace: { lifecycle: 'empty', items: [] }, session: { sessionId: 'session-a' } };
  });

  await Promise.resolve();
  workspace = {
    ...workspace,
    revision: 3,
    items: [{ draftItemId: 'draft-1', comment: 'newer local edit' }],
  };
  releaseSave();
  const result = await save;

  assert.equal(result.applied, false);
  assert.equal(result.reason, 'stale_after');
  assert.deepEqual(events, ['stale']);
  assert.equal(workspace.items[0].comment, 'newer local edit');
});
```

- [ ] **Step 3: Make stale save visible but non-destructive**

In `fixthis-mcp/src/main/console/state.js`, `ensureDraftCommandQueue()` already passes `onStaleResponse`. Update it to surface a warning without clearing workspace:

```js
onStaleResponse: () => {
  refreshSessions().catch(showError);
  showWarning('Saved an older draft version. Your latest local edits remain open.');
},
```

If `showWarning` is not in scope there, add a small callback wrapper near queue creation:

```js
function handleDraftStaleResponse() {
  refreshSessions().catch(showError);
  showWarning('Saved an older draft version. Your latest local edits remain open.');
}
```

and use:

```js
onStaleResponse: handleDraftStaleResponse,
```

- [ ] **Step 4: Run tests**

Run:

```bash
node --test scripts/draftCommandQueue-test.mjs scripts/sessionScopedRequests-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/draftCommandQueue.js \
  fixthis-mcp/src/main/console/state.js \
  scripts/draftCommandQueue-test.mjs
git commit -m "fix(console): fence stale draft save responses by revision"
```

## Task 5: Add Shared Lifecycle Boundary Use Case

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftPorts.js`
- Modify: `fixthis-mcp/src/main/console/draftUseCases.js`
- Modify: `scripts/draftUseCases-test.mjs`

- [ ] **Step 1: Extend draft ports with recovery prompt**

In `fixthis-mcp/src/main/console/draftPorts.js`, update the comment and fake port:

```js
//   boundaryPrompt: {
//     choose(workspace, boundaryAction): Promise<'save'|'keep'|'discard'|'cancel'>,
//     chooseRecovery(recovery, boundaryAction): Promise<'resume'|'recapture'|'clear'|'cancel'>
//   },
```

and:

```js
boundaryPrompt: {
  choose: async () => 'cancel',
  chooseRecovery: async () => 'cancel',
},
```

- [ ] **Step 2: Add recovery helper functions**

In `fixthis-mcp/src/main/console/draftUseCases.js`, add:

```js
function draftRecoverySessionId(recovery) {
  return recovery?.sessionId || recovery?.context?.sessionId || null;
}

function draftRecoveryWorkspaceId(recovery) {
  return recovery?.workspaceId || recovery?.context?.workspaceId || null;
}

function draftRecoveryItems(recovery) {
  return Array.isArray(recovery?.items) ? recovery.items : [];
}

function hasDraftRecoveryItems(recovery) {
  return draftRecoveryItems(recovery).length > 0;
}
```

- [ ] **Step 3: Implement lifecycle boundary resolver**

Add to `draftUseCases.js`:

```js
async function resolveLifecycleBoundary({ action, targetSessionId = null, activeWorkspace, pendingRecovery, ports }) {
  requireDraftPort(ports, 'boundaryPrompt');
  const hasActivePending = Boolean(activeWorkspace?.workspaceId && draftWorkspaceItems(activeWorkspace).length);
  const hasRecovery = hasDraftRecoveryItems(pendingRecovery);

  if (!hasActivePending && !hasRecovery) {
    return { outcome: 'continue', workspace: activeWorkspace, pendingRecovery };
  }

  if (hasRecovery && !hasActivePending) {
    const choice = await ports.boundaryPrompt.chooseRecovery(pendingRecovery, { kind: action, sessionId: targetSessionId });
    if (choice === 'clear') {
      const sessionId = draftRecoverySessionId(pendingRecovery);
      const workspaceId = draftRecoveryWorkspaceId(pendingRecovery);
      if (sessionId && workspaceId) ports.storage?.deleteWorkspace?.(sessionId, workspaceId);
      if (sessionId) ports.storage?.clearLegacyPending?.(sessionId);
      return { outcome: 'continue', choice, workspace: activeWorkspace, pendingRecovery: null };
    }
    if (choice === 'resume' || choice === 'recapture') {
      return { outcome: 'cancel', choice, workspace: activeWorkspace, pendingRecovery };
    }
    return { outcome: 'cancel', choice: 'cancel', workspace: activeWorkspace, pendingRecovery };
  }

  const activeCommented = draftWorkspaceItems(activeWorkspace).filter((item) => String(item?.comment || '').trim());
  if (hasActivePending && activeCommented.length === 0 && action !== 'delete-session') {
    ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(activeWorkspace));
    return {
      outcome: 'continue',
      choice: 'keep',
      workspace: reduceDraftWorkspace(activeWorkspace, { type: 'SESSION_BOUNDARY_CLOSED', workspaceId: activeWorkspace.workspaceId }),
      pendingRecovery,
    };
  }

  if (targetSessionId && activeWorkspace?.context?.sessionId && targetSessionId !== activeWorkspace.context.sessionId) {
    ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(activeWorkspace));
    return {
      outcome: 'continue',
      choice: 'keep',
      workspace: reduceDraftWorkspace(activeWorkspace, { type: 'SESSION_BOUNDARY_CLOSED', workspaceId: activeWorkspace.workspaceId }),
      pendingRecovery,
    };
  }

  const result = await resolveDraftBoundary(activeWorkspace, { kind: action, sessionId: targetSessionId }, ports);
  if (result.conflict) return { outcome: 'cancel', choice: 'conflict', workspace: result.workspace, pendingRecovery, conflict: result.conflict };
  if (result.choice === 'cancel') return { outcome: 'cancel', choice: 'cancel', workspace: result.workspace, pendingRecovery };
  return { outcome: 'continue', choice: result.choice, workspace: result.workspace, pendingRecovery, session: result.session };
}
```

- [ ] **Step 4: Add lifecycle use case tests**

Append to `scripts/draftUseCases-test.mjs`:

```js
test('lifecycle boundary blocks pending recovery until user clears or resumes it', async () => {
  const calls = [];
  const ports = m.createFakeDraftPorts({
    boundaryPrompt: { chooseRecovery: async () => 'cancel' },
    storage: { deleteWorkspace: (...args) => calls.push(['deleteWorkspace', ...args]) },
  });
  const result = await m.resolveLifecycleBoundary({
    action: 'new-session',
    activeWorkspace: m.createEmptyDraftWorkspace(),
    pendingRecovery: {
      schemaVersion: 2,
      sessionId: 'session-a',
      workspaceId: 'ws-recovery',
      context: { sessionId: 'session-a', previewId: 'preview-a' },
      items: [{ draftItemId: 'draft-1', comment: 'recover me' }],
    },
    ports,
  });

  assert.equal(result.outcome, 'cancel');
  assert.deepEqual(calls, []);
});

test('lifecycle boundary clears pending recovery only after explicit clear choice', async () => {
  const calls = [];
  const ports = m.createFakeDraftPorts({
    boundaryPrompt: { chooseRecovery: async () => 'clear' },
    storage: {
      deleteWorkspace: (...args) => calls.push(['deleteWorkspace', ...args]),
      clearLegacyPending: (...args) => calls.push(['clearLegacyPending', ...args]),
    },
  });
  const result = await m.resolveLifecycleBoundary({
    action: 'delete-session',
    activeWorkspace: m.createEmptyDraftWorkspace(),
    pendingRecovery: {
      schemaVersion: 2,
      sessionId: 'session-a',
      workspaceId: 'ws-recovery',
      context: { sessionId: 'session-a', previewId: 'preview-a' },
      items: [{ draftItemId: 'draft-1', comment: 'discard me' }],
    },
    ports,
  });

  assert.equal(result.outcome, 'continue');
  assert.equal(result.pendingRecovery, null);
  assert.deepEqual(calls, [
    ['deleteWorkspace', 'session-a', 'ws-recovery'],
    ['clearLegacyPending', 'session-a'],
  ]);
});
```

Add `resolveLifecycleBoundary` to the factory return object in `scripts/draftUseCases-test.mjs`.

- [ ] **Step 5: Run tests**

Run:

```bash
node --test scripts/draftUseCases-test.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/draftPorts.js \
  fixthis-mcp/src/main/console/draftUseCases.js \
  scripts/draftUseCases-test.mjs
git commit -m "feat(console): add shared draft lifecycle boundary resolver"
```

## Task 6: Wire Lifecycle Boundary Resolver Into Runtime Flows

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/draftStorageAdapter.js`
- Modify: `scripts/pendingBoundaryGuard-test.mjs`
- Modify: `scripts/pendingItemRecovery-test.mjs`

- [ ] **Step 1: Add legacy pending cleanup to storage adapter**

In `draftStorageAdapter.js`, add:

```js
function clearLegacyPending(sessionId) {
  if (!sessionId) return;
  localStorageLike.removeItem(LegacyPendingKeyPrefix + sessionId);
}
```

Return it:

```js
return { saveWorkspace, loadWorkspacesForSession, deleteWorkspace, deleteWorkspacesForSession, migrateLegacyPending, clearLegacyPending };
```

- [ ] **Step 2: Add browser recovery prompt adapter**

In `main.js`, add:

```js
function promptPendingRecoveryBoundaryChoice(action, recovery) {
  const summary = draftRecoverySummary(recovery);
  if (typeof window !== 'undefined' && typeof window.fixThisPromptPendingRecoveryBoundary === 'function') {
    return Promise.resolve(window.fixThisPromptPendingRecoveryBoundary({ action, recovery, summary }));
  }
  renderPendingRecoveryBanner();
  if (typeof window === 'undefined' || typeof window.confirm !== 'function') return Promise.resolve('cancel');
  const clear = window.confirm(
    'Local draft annotations exist for this session.\n\n' +
    'OK = Clear local draft and continue\n' +
    'Cancel = Keep editing'
  );
  return Promise.resolve(clear ? 'clear' : 'cancel');
}
```

Update `createBrowserDraftPorts()` in `state.js`:

```js
boundaryPrompt: {
  choose: (workspace, boundaryAction) =>
    promptPendingBoundaryChoice(boundaryAction?.kind || boundaryAction, draftWorkspaceItems(workspace).length),
  chooseRecovery: (recovery, boundaryAction) =>
    promptPendingRecoveryBoundaryChoice(boundaryAction?.kind || boundaryAction, recovery),
},
```

- [ ] **Step 3: Replace `resolvePendingBeforeBoundary` internals**

In `main.js`, replace the body of `resolvePendingBeforeBoundary(action, sessionId = null)` with:

```js
async function resolvePendingBeforeBoundary(action, sessionId = null) {
  const targetRecovery = sessionId && (!pendingRecovery || draftRecoverySessionId(pendingRecovery) !== sessionId)
    ? loadDraftRecoveryForSession(sessionId)
    : pendingRecovery;
  const result = await ensureDraftCommandQueue().enqueue({
    kind: 'lifecycle-boundary',
    workspaceId: draftWorkspace?.workspaceId || null,
    expectedRevision: draftWorkspace?.workspaceId ? draftWorkspace.revision : null,
  }, async (workspace) => resolveLifecycleBoundary({
    action,
    targetSessionId: sessionId,
    activeWorkspace: workspace,
    pendingRecovery: targetRecovery,
    ports: createBrowserDraftPorts(),
  }));

  if (result?.result?.conflict) {
    showError('Resolve the draft save conflict before changing sessions.');
    return 'cancel';
  }
  if (result?.result?.workspace) replaceDraftWorkspace(result.result.workspace);
  pendingRecovery = result?.result?.pendingRecovery ?? pendingRecovery;
  renderPendingRecoveryBanner();
  return result?.result?.outcome === 'continue' ? 'continue' : 'cancel';
}
```

If `ensureDraftCommandQueue()` rejects `expectedRevision: null`, omit `expectedRevision` when no active workspace exists:

```js
const meta = { kind: 'lifecycle-boundary' };
if (draftWorkspace?.workspaceId) {
  meta.workspaceId = draftWorkspace.workspaceId;
  meta.expectedRevision = draftWorkspace.revision;
}
```

- [ ] **Step 4: Update recovery tests to stricter expected behavior**

In `scripts/pendingItemRecovery-test.mjs`, replace tests that assert pending recovery only renders a banner and returns `continue` with tests that assert the resolver is called:

```js
test('session refresh surfaces pending recovery through lifecycle resolver instead of passive continue', () => {
  const resolveBody = extractFunctionBody(mainSource, 'async function resolvePendingBeforeBoundary(action, sessionId = null)');
  assert.match(resolveBody, /resolveLifecycleBoundary\(/);
  assert.doesNotMatch(resolveBody, /pendingRecoveryItems\(pendingRecovery\)\.length && !hasActivePending[\s\S]*?return 'continue'/);
});
```

- [ ] **Step 5: Run tests**

Run:

```bash
node --test scripts/pendingBoundaryGuard-test.mjs scripts/pendingItemRecovery-test.mjs scripts/draftUseCases-test.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/main.js \
  fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/console/state.js \
  fixthis-mcp/src/main/console/draftStorageAdapter.js \
  scripts/pendingBoundaryGuard-test.mjs \
  scripts/pendingItemRecovery-test.mjs
git commit -m "fix(console): enforce lifecycle boundary for pending recovery"
```

## Task 7: Split Local Draft Clearing From Server Draft Deletion

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/presentation/previewRegionView.js`
- Modify: `scripts/pendingBoundaryGuard-test.mjs`
- Modify: `scripts/sessionScopedRequests-test.mjs`

- [ ] **Step 1: Replace `clearDraft` with explicit functions**

In `history.js`, replace `clearDraft()` with:

```js
async function clearLocalDraft() {
  error.textContent = '';
  if (!draftWorkspace?.workspaceId && !pendingRecoveryItems(pendingRecovery).length) return;
  if (!window.confirm('Clear local unsaved draft annotations from this browser?')) return;
  const sessionId = draftWorkspace?.context?.sessionId || pendingRecovery?.context?.sessionId || pendingRecovery?.sessionId || state.session?.sessionId;
  if (draftWorkspace?.workspaceId) createBrowserDraftPorts().storage.deleteWorkspace(sessionId, draftWorkspace.workspaceId);
  if (pendingRecovery?.workspaceId) createBrowserDraftPorts().storage.deleteWorkspace(sessionId, pendingRecovery.workspaceId);
  if (sessionId) {
    createBrowserDraftPorts().storage.clearLegacyPending(sessionId);
    clearPendingMirror(sessionId);
    activePendingMirrorSessions.delete(sessionId);
  }
  pendingRecovery = null;
  resetComposer();
  render();
  startLivePreviewPolling();
}

async function clearServerDraftItems() {
  error.textContent = '';
  const sessionId = state.session?.sessionId;
  if (!sessionId) return;
  if (await resolvePendingBeforeBoundary('clear-server-drafts', sessionId) !== 'continue') return;
  if (!window.confirm('Delete saved draft feedback items from this session?')) return;
  await withMutationLock(() => requestJson('/api/items/draft?sessionId=' + encodeURIComponent(sessionId), { method: 'DELETE' }));
  clearSelection();
  await refresh();
}
```

- [ ] **Step 2: Wire button behavior by context**

In `main.js`, change:

```js
clearDraftButton.addEventListener('click', () => clearDraft().catch(showError));
```

to:

```js
clearDraftButton.addEventListener('click', () => {
  const hasLocalDraft = Boolean(draftWorkspace?.workspaceId && draftWorkspaceItems(draftWorkspace).length) ||
    Boolean(pendingRecoveryItems(pendingRecovery).length);
  const action = hasLocalDraft ? clearLocalDraft : clearServerDraftItems;
  action().catch(showError);
});
```

In `previewRegionView.js`, update the button visibility so local drafts can be cleared even without saved items:

```js
const hasLocalDraft = Boolean(draftFlow() && draftItemList().length);
clearDraftButton.hidden = !hasLocalDraft && savedItems.length === 0;
clearDraftButton.textContent = hasLocalDraft ? 'Clear local draft' : 'Clear saved drafts';
```

- [ ] **Step 3: Add tests**

Append to `scripts/sessionScopedRequests-test.mjs`:

```js
test('server draft clearing uses explicit session query', () => {
  const historySource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
  const clearBody = body(historySource, 'async function clearServerDraftItems()');
  assert.match(clearBody, /\/api\/items\/draft\?sessionId=/);
  assert.doesNotMatch(clearBody, /requestJson\('\/api\/items\/draft',/);
});
```

Append to `scripts/pendingBoundaryGuard-test.mjs`:

```js
test('local draft clearing does not call server draft deletion endpoint', () => {
  const historySource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
  const localBody = body(historySource, 'async function clearLocalDraft()');
  assert.doesNotMatch(localBody, /\/api\/items\/draft/);
  assert.match(localBody, /deleteWorkspace/);
  assert.match(localBody, /clearLegacyPending/);
});
```

- [ ] **Step 4: Run tests**

Run:

```bash
node --test scripts/sessionScopedRequests-test.mjs scripts/pendingBoundaryGuard-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/console/main.js \
  fixthis-mcp/src/main/console/presentation/previewRegionView.js \
  scripts/sessionScopedRequests-test.mjs \
  scripts/pendingBoundaryGuard-test.mjs
git commit -m "fix(console): split local and server draft clearing"
```

## Task 8: Decode Typed Draft API Errors in Browser

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftApiAdapter.js`
- Modify: `scripts/draftApiAdapter-test.mjs`

- [ ] **Step 1: Add client-side bounds validation**

In `draftApiAdapter.js`, add:

```js
function hasValidDraftBounds(bounds) {
  if (!bounds) return false;
  const values = [bounds.left, bounds.top, bounds.right, bounds.bottom].map(Number);
  return values.every(Number.isFinite) && bounds.right > bounds.left && bounds.bottom > bounds.top;
}

function validateDraftItemForSave(item) {
  if (!hasValidDraftBounds(item?.bounds)) {
    const error = new Error('Selection bounds are invalid. Re-capture the screen and try again.');
    error.code = 'invalid_selection_bounds';
    error.action = 'recapture';
    throw error;
  }
}
```

Call it before normalization:

```js
items: (workspace.items || [])
  .filter((item) => options.allowBlankComments || options.allowFallbackComments || String(item.comment || '').trim())
  .map((item) => {
    validateDraftItemForSave(item);
    return normalizeDraftItemForScreen(item, workspace.screen);
  })
  .map((item) => draftItemToAnnotationDraftDto(item, options)),
```

- [ ] **Step 2: Add typed error helper**

In `draftApiAdapter.js`, add:

```js
async function draftApiErrorFromResponse(response) {
  const payload = await response.json?.().catch(() => null);
  const fallbackText = payload ? '' : await response.text?.().catch(() => '');
  const message = payload?.message || payload?.error || fallbackText || 'HTTP ' + response.status;
  const error = new Error(message);
  error.code = payload?.error || 'http_' + response.status;
  error.action = payload?.action || null;
  error.status = response.status;
  error.payload = payload;
  return error;
}
```

Change `requestJson`:

```js
if (!response.ok) {
  if (response.status === 409) {
    const conflict = await response.json().catch(() => ({}));
    return { conflict };
  }
  throw await draftApiErrorFromResponse(response);
}
```

- [ ] **Step 3: Add invalid bounds test**

Append to `scripts/draftApiAdapter-test.mjs`:

```js
test('save request rejects invalid bounds before HTTP', () => {
  assert.throws(() => m.buildDraftWorkspaceSaveRequest({
    ...workspace,
    items: [{
      draftItemId: 'draft-bad',
      targetType: 'area',
      bounds: { left: 10, top: 10, right: 10, bottom: 20 },
      comment: 'bad bounds',
    }],
  }), (error) => {
    assert.equal(error.code, 'invalid_selection_bounds');
    return true;
  });
});
```

- [ ] **Step 4: Run tests**

Run:

```bash
node --test scripts/draftApiAdapter-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/draftApiAdapter.js scripts/draftApiAdapter-test.mjs
git commit -m "fix(console): decode typed draft API errors"
```

## Task 9: Return Typed Server Validation Errors

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHttp.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt`

- [ ] **Step 1: Add typed error body**

In `ConsoleHttp.kt`, replace `ConsoleErrorBody` with:

```kotlin
@Serializable
internal data class ConsoleErrorBody(
    val error: String,
    val message: String = error,
    val action: String? = null,
)
```

Keep the existing call shape and add an overload:

```kotlin
internal fun HttpExchange.sendErrorJson(status: Int, message: String) {
    sendErrorJson(status, error = message, message = message)
}

internal fun HttpExchange.sendErrorJson(status: Int, error: String, message: String, action: String? = null) {
    sendText(
        status,
        fixThisJson.encodeToString(ConsoleErrorBody.serializer(), ConsoleErrorBody(error, message, action)),
        "application/json; charset=utf-8",
    )
}
```

Extend `FeedbackConsoleHttpException`:

```kotlin
internal class FeedbackConsoleHttpException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null,
    val errorCode: String? = null,
    val action: String? = null,
) : RuntimeException(message, cause)
```

- [ ] **Step 2: Update server dispatcher error write**

In `FeedbackConsoleServer.kt`, change:

```kotlin
} catch (error: FeedbackConsoleHttpException) {
    exchange.sendErrorJson(error.statusCode, error.message)
}
```

to:

```kotlin
} catch (error: FeedbackConsoleHttpException) {
    exchange.sendErrorJson(
        error.statusCode,
        error = error.errorCode ?: error.message,
        message = error.message,
        action = error.action,
    )
}
```

Update `toConsoleHttpException()` to return stable codes:

```kotlin
private fun FeedbackSessionException.toConsoleHttpException(): FeedbackConsoleHttpException {
    val text = message ?: "Feedback session request failed"
    val code = when {
        text.startsWith("Unknown feedback session:") -> "unknown_feedback_session"
        text.startsWith("PREVIEW_NOT_FOUND:") -> "preview_not_found"
        text.startsWith("SCREEN_NOT_FOUND:") -> "screen_not_found"
        text.startsWith("NO_DRAFT_FEEDBACK:") -> "no_draft_feedback"
        text.startsWith("NO_ACTIVE_SESSION:") -> "no_active_session"
        text.startsWith("ITEM_NOT_EDITABLE:") -> "item_not_editable"
        text.startsWith("PREVIEW_SAVE_IN_PROGRESS:") -> "preview_save_in_progress"
        else -> "feedback_session_error"
    }
    val statusCode = when (code) {
        "unknown_feedback_session", "preview_not_found" -> 404
        "screen_not_found" -> 400
        "no_draft_feedback", "no_active_session", "item_not_editable", "preview_save_in_progress" -> 409
        else -> 500
    }
    return FeedbackConsoleHttpException(statusCode, text, errorCode = code)
}
```

- [ ] **Step 3: Map draft validation failures**

In `FeedbackItemRoutes.kt`, add:

```kotlin
private fun PreviewFeedbackRequestValidationException.toConsoleHttpException(): FeedbackConsoleHttpException {
    val text = message ?: "Invalid feedback item request"
    return when {
        text.startsWith("Selected node does not exist on preview:") ->
            FeedbackConsoleHttpException(
                400,
                text,
                this,
                errorCode = "selected_node_missing",
                action = "recapture_or_convert_to_area",
            )
        text.startsWith("Selection bounds") ->
            FeedbackConsoleHttpException(
                400,
                text,
                this,
                errorCode = "invalid_selection_bounds",
                action = "recapture",
            )
        else ->
            FeedbackConsoleHttpException(400, text, this, errorCode = "invalid_feedback_item_request")
    }
}
```

Use it in `/api/items/batch`:

```kotlin
} catch (error: PreviewFeedbackRequestValidationException) {
    throw error.toConsoleHttpException()
}
```

- [ ] **Step 4: Add route tests**

In `ConsoleFeedbackItemRoutesTest.kt`, add a test that posts a batch with a missing node uid on the fallback screen and asserts:

```kotlin
assertEquals(400, connection.responseCode)
val body = connection.errorStream.bufferedReader().readText()
assertTrue(body.contains("\"error\":\"selected_node_missing\""))
assertTrue(body.contains("\"action\":\"recapture_or_convert_to_area\""))
```

Add an existing or new fingerprint mismatch assertion:

```kotlin
assertEquals(409, connection.responseCode)
assertTrue(body.contains("\"error\":\"screen_fingerprint_mismatch\""))
```

- [ ] **Step 5: Run Kotlin tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests 'io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest' \
  --tests 'io.beyondwin.fixthis.mcp.session.FeedbackDraftServiceTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHttp.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftServiceTest.kt
git commit -m "fix(console): return typed draft validation errors"
```

## Task 10: Add Browser Smoke Coverage for Real Lifecycle Sequences

**Files:**
- Modify: `scripts/console-browser-smoke.mjs`
- Modify: `scripts/console-fixture/scenarios-test.mjs` if scenarios are fixture-driven
- Modify: `scripts/console-tests.json` if the smoke script is grouped there

- [ ] **Step 1: Add save-while-editing smoke scenario**

Add a browser smoke scenario with this behavior:

```js
test('save response cannot clear newer local draft edit', async () => {
  const page = await openConsoleSmokePage();
  await seedSession(page, { sessionId: 'session-1' });
  await startDraftWithOneAnnotation(page, {
    draftItemId: 'draft-1',
    comment: 'first edit',
  });

  await page.route('**/api/items/batch', async (route) => {
    await page.evaluate(() => { window.__releaseDraftSave = null; });
    await new Promise((resolve) => { globalThis.releaseDraftSave = resolve; });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ sessionId: 'session-1', items: [{ itemId: 'server-1' }], screens: [] }),
    });
  });

  await page.click('[data-save-draft]');
  await page.fill('#annotationCommentInput', 'newer local edit');
  globalThis.releaseDraftSave();

  await page.waitForSelector('text=newer local edit');
  await assertLocalDraftRecoveryContains(page, 'newer local edit');
});
```

Use the existing smoke helper names if they differ. The assertions must prove the newer local edit remains visible after the server response.

- [ ] **Step 2: Add delete-session-with-recovery scenario**

Add:

```js
test('deleting session with local recovery requires explicit recovery choice', async () => {
  const page = await openConsoleSmokePage();
  await seedStoredRecovery(page, {
    sessionId: 'session-1',
    workspaceId: 'workspace-recovery',
    comment: 'recover before delete',
  });
  await seedSessionList(page, [
    { sessionId: 'session-1', status: 'active' },
    { sessionId: 'session-2', status: 'active' },
  ]);

  await page.click('[data-delete-session-id="session-1"]');
  await page.waitForSelector('[data-testid="pending-recovery-banner"]');
  await assertNoRequest(page, '/api/session/close');
});
```

If the smoke harness has no `assertNoRequest`, collect requests in an array:

```js
const requests = [];
page.on('request', request => requests.push(request.url()));
assert.equal(requests.some(url => url.includes('/api/session/close')), false);
```

- [ ] **Step 3: Run smoke script**

Run:

```bash
node scripts/console-browser-smoke.mjs
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add scripts/console-browser-smoke.mjs scripts/console-tests.json scripts/console-fixture/scenarios-test.mjs
git commit -m "test(console): cover draft lifecycle browser flows"
```

Only include `scripts/console-tests.json` and fixture files if they changed.

## Task 11: Rebuild Console Bundle and Run Verification

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/main/resources/console/app.js.map`

- [ ] **Step 1: Run focused JS tests**

Run:

```bash
node --test \
  scripts/consoleCanonicalRuntimeContract-test.mjs \
  scripts/draftWorkspace-test.mjs \
  scripts/draftUseCases-test.mjs \
  scripts/draftCommandQueue-test.mjs \
  scripts/draftApiAdapter-test.mjs \
  scripts/pendingBoundaryGuard-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/sessionScopedRequests-test.mjs
```

Expected: PASS.

- [ ] **Step 2: Run console draft suite**

Run:

```bash
npm run console:draft:test
```

Expected: PASS.

- [ ] **Step 3: Rebuild assets**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
```

Expected: `fixthis-mcp/src/main/resources/console/app.js` and `.map` update if source changed.

- [ ] **Step 4: Check generated bundle**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: PASS.

- [ ] **Step 5: Run focused Kotlin tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests 'io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest' \
  --tests 'io.beyondwin.fixthis.mcp.session.FeedbackDraftServiceTest'
```

Expected: PASS.

- [ ] **Step 6: Run whitespace and artifact checks**

Run:

```bash
git diff --check
git status --short
```

Expected:

- `git diff --check` has no output.
- `git status --short` does not include `.fixthis/`.

- [ ] **Step 7: Commit bundle and final verification**

```bash
git add fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map
git commit -m "build(console): rebuild lifecycle hardening assets"
```

If the generated bundle is unchanged, do not create an empty commit.

## Task 12: Final Review

**Files:**
- Review all changed files from Tasks 1-11.

- [ ] **Step 1: Inspect diff**

Run:

```bash
git diff HEAD~10..HEAD --stat
git diff HEAD~10..HEAD -- fixthis-mcp/src/main/console
```

Expected: changes are scoped to console lifecycle hardening, tests, typed server errors, and generated assets.

- [ ] **Step 2: Check forbidden patterns**

Run:

```bash
rg -n "item\\.(label|comment|severity|status)\\s*=|/api/feedback/items|fixthis\\.recovery\\.|fixthis\\.draftWorkspace\\.|return 'continue';" \
  fixthis-mcp/src/main/console scripts
```

Expected:

- No direct pending item mutation in production code.
- No `/api/feedback/items`.
- No `fixthis.recovery.*` or `fixthis.draftWorkspace.*`.
- Any remaining `return 'continue';` in boundary code is covered by tests and not the pending-recovery-only bypass.

- [ ] **Step 3: Run final verification group**

Run:

```bash
node --test \
  scripts/consoleCanonicalRuntimeContract-test.mjs \
  scripts/draftWorkspace-test.mjs \
  scripts/draftUseCases-test.mjs \
  scripts/draftCommandQueue-test.mjs \
  scripts/draftApiAdapter-test.mjs \
  scripts/pendingBoundaryGuard-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/sessionScopedRequests-test.mjs
npm run console:draft:test
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
./gradlew :fixthis-mcp:test \
  --tests 'io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest' \
  --tests 'io.beyondwin.fixthis.mcp.session.FeedbackDraftServiceTest'
git diff --check
```

Expected: all commands pass.

- [ ] **Step 4: Commit any final test or doc adjustments**

```bash
git add docs/superpowers/specs/2026-05-15-console-draft-session-lifecycle-hardening-detailed-spec.md \
  docs/superpowers/plans/2026-05-15-console-draft-session-lifecycle-hardening-implementation.md
git commit -m "docs(console): specify draft lifecycle hardening"
```

Only include this docs commit if the documentation was not already committed.

## Rollback Strategy

If a phase introduces regressions that cannot be resolved quickly:

- Revert only the most recent task commit.
- Keep the failing regression test if it accurately describes desired behavior.
- Do not revert unrelated user or generated changes.
- Do not weaken server validation to make a client test pass.

## Completion Criteria

- The `+` new session flow cannot proceed past active draft or pending recovery without an explicit boundary outcome.
- Pending annotation edits increment workspace revision and persist recovery through reducer-owned state.
- A save response for an older revision cannot clear newer local edits.
- Deleting a session with local recovery requires explicit recovery handling.
- Local draft clearing and server draft deletion are separate commands and effects.
- Browser draft save errors expose stable `error` codes for recovery decisions.
- Static tests fail on duplicate canonical/legacy side effects, dead endpoints, obsolete storage namespaces, and direct pending item mutation.
- Focused JS tests, console draft tests, bundle checks, focused Kotlin tests, and `git diff --check` pass.
