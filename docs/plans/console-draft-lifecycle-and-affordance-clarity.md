# Console Draft Lifecycle & Affordance Clarity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the user-visible gap left by prior draft/dedup hardening — adopt strict "comment-required to exist" annotation policy, route every context transition through one boundary protocol, replace the overlapping inspector footer with a state-dependent action bar, surface destructive consequences before clicks, and coordinate eight independent status surfaces under one registry.

**Architecture:** Browser-side JavaScript primarily (`fixthis-mcp/src/main/console/`), one small Kotlin validation on `:fixthis-mcp` server, no MCP/bridge protocol changes. Editor state is *derived* (not stored) from existing `DraftLifecycle` + selection + saved-item context. Status surfaces are routed through a new `StatusSurfaceRegistry` singleton instead of toggling `.hidden` directly. Z-index for surfaces is tokenized.

**Tech Stack:** Vanilla browser JS bundled via `scripts/build-console-assets.mjs` (no ESM, no TS), Kotlin/JVM server (`:fixthis-mcp`), existing console JS test harness under `fixthis-mcp/src/main/console/__tests__/`, Gradle for Kotlin tests, Node 20 for console-side checks.

**Companion specs (authoritative):**
- `docs/superpowers/specs/2026-05-16-console-draft-lifecycle-and-affordance-clarity-design.md`
- `docs/superpowers/specs/2026-05-16-console-draft-lifecycle-and-affordance-clarity-implementation-details.md`

**Plan structure:** 5 phases, 20 tasks. Each task is one focused PR-sized change with TDD.

---

## File Structure (new + modified)

### New files (browser JS)

| Path | Responsibility |
|---|---|
| `fixthis-mcp/src/main/console/editorState.js` | Pure derivation: `(workspace, selection, savedItem) → 'none' \| 'pendingTarget' \| 'draft' \| 'saved'` |
| `fixthis-mcp/src/main/console/boundaryTriggers.js` | Frozen `Trigger` enum (16 values) |
| `fixthis-mcp/src/main/console/boundaryPolicy.js` | Pure `boundaryPolicy(trigger, state, payload) → verdict` (15×3 matrix) |
| `fixthis-mcp/src/main/console/inspectorFooter.js` | State-dependent renderer for `#inspectorFooter` |
| `fixthis-mcp/src/main/console/editorBackButton.js` | `← All annotations` handler + render |
| `fixthis-mcp/src/main/console/boundaryDialogVariants.js` | `BoundaryDialogVariants` map + renderer |
| `fixthis-mcp/src/main/console/statusSurfaceRegistry.js` | Singleton coordinator for the 8 surfaces |
| `fixthis-mcp/src/main/console/toastCopy.js` | Trigger → toast text map |

### New files (tests)

| Path | Covers |
|---|---|
| `fixthis-mcp/src/main/console/__tests__/editorState.test.html` | derivation cases |
| `fixthis-mcp/src/main/console/__tests__/boundaryPolicy.test.html` | 45-cell matrix |
| `fixthis-mcp/src/main/console/__tests__/inspectorFooter.test.html` | state→footer rendering |
| `fixthis-mcp/src/main/console/__tests__/editorBackButton.test.html` | back nav per state |
| `fixthis-mcp/src/main/console/__tests__/boundaryDialogVariants.test.html` | label correctness |
| `fixthis-mcp/src/main/console/__tests__/statusSurfaceRegistry.test.html` | coordination invariants |
| `fixthis-mcp/src/main/console/__tests__/recoveryEmptyFilter.test.html` | empty-entry drop on load |
| `fixthis-mcp/src/main/console/__tests__/disconnectChoreography.test.html` | disconnect/reconnect single-surface |

### New files (server, CSS, scripts)

| Path | Responsibility |
|---|---|
| `scripts/check-surface-zindex.sh` | CSS lint: surface selectors must use `var(--z-surface-*)` |

### Modified files (browser JS)

| Path | Why |
|---|---|
| `fixthis-mcp/src/main/console/draftWorkspace.js` | Add `freeWorkspace` reducer effect |
| `fixthis-mcp/src/main/console/draftUseCases.js` | Gate workspace allocation to first non-empty char, add `ensureWorkspaceForSelection`, `maybeFreeWorkspaceOnEmpty` |
| `fixthis-mcp/src/main/console/draftStorageAdapter.js` | Add `dropEmptyEntries` on every load |
| `fixthis-mcp/src/main/console/pendingPersistence.js` | Same filter |
| `fixthis-mcp/src/main/console/historyPendingDedupe.js` | Trust upstream filter, log if empty leaks |
| `fixthis-mcp/src/main/console/annotations.js` | Wire `oninput` to `ensureWorkspaceForSelection`; remove direct workspace creation in `enterAnnotateMode` |
| `fixthis-mcp/src/main/console/shortcuts.js` | Route `Esc` through `resolveTrigger` |
| `fixthis-mcp/src/main/console/history.js` | Route session-row click through resolver; add trash icon |
| `fixthis-mcp/src/main/console/main.js` | `beforeunload` listener; replace hardcoded recovery prompt with variant |
| `fixthis-mcp/src/main/console/preview.js` | Route surface toggles through registry |
| `fixthis-mcp/src/main/console/staleness.js` | Same |
| `fixthis-mcp/src/main/console/connection.js` | Same |
| `fixthis-mcp/src/main/console/state.js` | Replace direct `error.hidden` writes with registry |
| `fixthis-mcp/src/main/console/consoleApp.js` | Subscribe `renderInspectorFooter` to store |

### Modified files (HTML, CSS, server)

| Path | Why |
|---|---|
| `fixthis-mcp/src/main/resources/console/index.html` | Replace 4-button footer with `<div id="inspectorFooter" hidden></div>`; add `<div id="editorBack">`; add `<div id="toastContainer">`; replace device `×` button; add per-session trash icon template |
| `fixthis-mcp/src/main/resources/console/styles.css` | Add `--z-surface-*` tokens, update surface selectors to use them, add `.toast-container`, `.session-row-delete`, `.editor-back-button` styles |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt` | Add `validateComments` to both save handlers; map to typed `empty-comment` error |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt` | Defense-in-depth: reject `comment.isBlank()` items |

### Modified files (Kotlin tests)

| Path | Covers |
|---|---|
| `fixthis-mcp/src/test/kotlin/.../console/FeedbackItemRoutesTest.kt` | 422 `empty-comment` cases |
| `fixthis-mcp/src/test/kotlin/.../session/FeedbackSessionStoreDelegateTest.kt` | Server-side rejection |

### Bundle / docs touched

- `scripts/build-console-assets.mjs` — no API change, but rerun after each browser-JS task.
- `CONTRIBUTING.md` — append `check-surface-zindex.sh` to local PR checks list (single line edit).
- `docs/guides/manual-test-checklist.md` (create if absent) — 5-item manual checklist from impl details §9.5.

---

## Phase 1 — Foundation: Policy A enforcement

### Task 1: `editorState.js` — pure state derivation

**Files:**
- Create: `fixthis-mcp/src/main/console/editorState.js`
- Create: `fixthis-mcp/src/main/console/__tests__/editorState.test.html`

- [ ] **Step 1: Write the failing test**

Create `fixthis-mcp/src/main/console/__tests__/editorState.test.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>editorState tests</title></head>
<body>
<script src="../editorState.js"></script>
<script>
(function () {
  const cases = [
    { name: 'no selection, no workspace, no saved → none',
      input: { workspace: null, selection: null, savedItem: null }, expect: 'none' },
    { name: 'selection only, no workspace → pendingTarget',
      input: { workspace: null, selection: { id: 'el-1' }, savedItem: null }, expect: 'pendingTarget' },
    { name: 'workspace with empty items + selection → pendingTarget',
      input: { workspace: { lifecycle: 'empty', items: [] }, selection: { id: 'el-1' }, savedItem: null }, expect: 'pendingTarget' },
    { name: 'workspace with non-empty items → draft',
      input: { workspace: { lifecycle: 'editing', items: [{ comment: 'x' }] }, selection: { id: 'el-1' }, savedItem: null }, expect: 'draft' },
    { name: 'saved item, no workspace → saved',
      input: { workspace: null, selection: null, savedItem: { itemId: 'a' } }, expect: 'saved' },
    { name: 'workspace with items but no selection → draft (was being authored)',
      input: { workspace: { lifecycle: 'editing', items: [{ comment: 'x' }] }, selection: null, savedItem: null }, expect: 'draft' },
  ];
  const failures = [];
  for (const c of cases) {
    const got = deriveEditorState(c.input.workspace, c.input.selection, c.input.savedItem);
    if (got !== c.expect) failures.push(`${c.name}: expected ${c.expect}, got ${got}`);
  }
  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/editorState.test.html`

(If that runner does not exist, run via `node -e` with jsdom or open the file in headless Chrome. The runner script is consistent across this codebase — see existing tests in the same directory for the invocation.)

Expected: FAIL — `deriveEditorState is not defined`.

- [ ] **Step 3: Write minimal implementation**

Create `fixthis-mcp/src/main/console/editorState.js`:

```js
// @requires (none)
// editorState.js — pure derivation of editor state from workspace/selection/savedItem.
// No DOM, fetch, localStorage, or timers.

function deriveEditorState(workspace, selection, savedItem) {
  const hasWorkspace = workspace != null && (workspace.workspaceId != null || workspace.lifecycle !== 'empty');
  const hasWorkspaceItems = hasWorkspace && Array.isArray(workspace.items) && workspace.items.length > 0;
  if (savedItem && !hasWorkspace) return 'saved';
  if (hasWorkspaceItems) return 'draft';
  if (selection) return 'pendingTarget';
  return 'none';
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2.
Expected: PASS — `document.body.dataset.testResult === 'pass'`.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/editorState.js \
        fixthis-mcp/src/main/console/__tests__/editorState.test.html
git commit -m "feat(console): add editorState.deriveEditorState pure derivation"
```

---

### Task 2: Gate workspace allocation to first comment character

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftUseCases.js`
- Modify: `fixthis-mcp/src/main/console/draftWorkspace.js`
- Create: `fixthis-mcp/src/main/console/__tests__/workspaceAllocationGate.test.html`

- [ ] **Step 1: Write the failing test**

Create `fixthis-mcp/src/main/console/__tests__/workspaceAllocationGate.test.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>workspace allocation gate tests</title></head>
<body>
<script src="../draftWorkspace.js"></script>
<script src="../draftUseCases.js"></script>
<script>
(function () {
  const failures = [];

  // Case A: no workspace exists before first comment char.
  let state = { draftWorkspace: createEmptyDraftWorkspace(), selection: { context: { sessionId: 's', previewId: 'p', screenId: 'sc' }, screen: { screenId: 'sc' }, screenshotUrl: null } };
  const beforeId = state.draftWorkspace.workspaceId;
  if (beforeId != null) failures.push('A: workspaceId should be null before first char');

  // Case B: ensureWorkspaceForSelection allocates exactly once.
  const ws1 = ensureWorkspaceForSelection(state, state.selection, { nextWorkspaceId: () => 'ws-1' });
  if (!ws1 || ws1.workspaceId !== 'ws-1') failures.push('B: first call should allocate ws-1');
  const ws2 = ensureWorkspaceForSelection({ ...state, draftWorkspace: ws1 }, state.selection, { nextWorkspaceId: () => 'ws-2' });
  if (ws2.workspaceId !== 'ws-1') failures.push('B: second call should return existing ws-1');

  // Case C: maybeFreeWorkspaceOnEmpty frees when last item becomes empty.
  const wsWith1 = { ...ws1, items: [{ draftItemId: 'd-1', comment: '' }] };
  const freed = maybeFreeWorkspaceOnEmpty(wsWith1, 'd-1');
  if (freed !== null) failures.push('C: should return null (workspace freed) when last item is empty');

  // Case D: maybeFreeWorkspaceOnEmpty does NOT free when comment is non-empty.
  const wsWithFilled = { ...ws1, items: [{ draftItemId: 'd-1', comment: 'hi' }] };
  const kept = maybeFreeWorkspaceOnEmpty(wsWithFilled, 'd-1');
  if (kept === null) failures.push('D: should not free when comment has content');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/workspaceAllocationGate.test.html`
Expected: FAIL — `ensureWorkspaceForSelection is not defined` and `maybeFreeWorkspaceOnEmpty is not defined`.

- [ ] **Step 3: Write minimal implementation**

Append to `fixthis-mcp/src/main/console/draftUseCases.js`:

```js
function ensureWorkspaceForSelection(state, selection, ports) {
  if (state.draftWorkspace?.workspaceId) return state.draftWorkspace;
  if (!selection || !selection.context) throw new Error('selection with context required to allocate workspace');
  const workspaceId = ports.nextWorkspaceId();
  return createFrozenDraftWorkspace({
    workspaceId,
    context: selection.context,
    screen: selection.screen,
    screenshotUrl: selection.screenshotUrl,
  });
}

function maybeFreeWorkspaceOnEmpty(workspace, itemId) {
  const items = workspace?.items ?? [];
  if (items.length !== 1) return workspace;
  if (items[0].draftItemId !== itemId) return workspace;
  if (String(items[0].comment || '').length !== 0) return workspace;
  return null;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/draftUseCases.js \
        fixthis-mcp/src/main/console/__tests__/workspaceAllocationGate.test.html
git commit -m "feat(console): gate workspace allocation to first non-empty comment character"
```

---

### Task 3: `dropEmptyEntries` filter in storage adapters

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftStorageAdapter.js`
- Modify: `fixthis-mcp/src/main/console/pendingPersistence.js`
- Modify: `fixthis-mcp/src/main/console/historyPendingDedupe.js`
- Create: `fixthis-mcp/src/main/console/__tests__/recoveryEmptyFilter.test.html`

- [ ] **Step 1: Write the failing test**

Create `fixthis-mcp/src/main/console/__tests__/recoveryEmptyFilter.test.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>recovery empty filter</title></head>
<body>
<script src="../draftStorageAdapter.js"></script>
<script>
(function () {
  const failures = [];

  const envelope = {
    workspaceId: 'ws-1',
    items: [
      { draftItemId: 'a', comment: 'real one' },
      { draftItemId: 'b', comment: '' },
      { draftItemId: 'c', comment: '  \t  ' },   // whitespace-only -> KEEP (Policy A says length===0 only)
      { draftItemId: 'd', comment: 'another' },
    ],
  };
  const result = dropEmptyEntries(envelope);
  if (result.items.length !== 3) failures.push(`expected 3 items kept, got ${result.items.length}`);
  if (result.items.some(i => i.draftItemId === 'b')) failures.push('item b (empty) should be dropped');
  if (!result.items.some(i => i.draftItemId === 'c')) failures.push('item c (whitespace) should be kept by Policy A (length===0 only)');

  // Empty input → empty output, no throw.
  const empty = dropEmptyEntries({ items: [] });
  if (empty.items.length !== 0) failures.push('empty envelope should return empty items');

  // Null items field → empty array.
  const nullItems = dropEmptyEntries({ items: null });
  if (!Array.isArray(nullItems.items) || nullItems.items.length !== 0) failures.push('null items should become empty array');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/recoveryEmptyFilter.test.html`
Expected: FAIL — `dropEmptyEntries is not defined`.

- [ ] **Step 3: Write minimal implementation**

Append to `fixthis-mcp/src/main/console/draftStorageAdapter.js`:

```js
function dropEmptyEntries(envelope) {
  if (!envelope) return { items: [] };
  let dropped = 0;
  const items = (envelope.items || []).filter(item => {
    const comment = String(item?.comment || '');
    if (comment.length === 0) { dropped += 1; return false; }
    return true;
  });
  if (dropped > 0) {
    // eslint-disable-next-line no-console
    console.info(`[draft-recovery] skipped ${dropped} empty-comment entries`);
  }
  return { ...envelope, items };
}
```

Then wire it into the existing load entry points. In `draftStorageAdapter.js`, find `loadWorkspacesForSession` and wrap its return value:

```js
// Before:
//   return parsed;
// After:
return parsed ? dropEmptyEntries(parsed) : parsed;
```

Apply the same wrap in `pendingPersistence.js` (function `loadPendingState`) and in `historyPendingDedupe.js` (function `restorePendingState`). Each is a one-line wrap at the existing return site — no signature changes.

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/draftStorageAdapter.js \
        fixthis-mcp/src/main/console/pendingPersistence.js \
        fixthis-mcp/src/main/console/historyPendingDedupe.js \
        fixthis-mcp/src/main/console/__tests__/recoveryEmptyFilter.test.html
git commit -m "feat(console): drop empty-comment entries on all recovery load paths"
```

---

### Task 4: Server-side `empty-comment` validation

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutesTest.kt`

- [ ] **Step 1: Write the failing test**

In `FeedbackItemRoutesTest.kt`, add (next to similar 422 tests; if no such file exists, create it following the pattern of the nearest sibling test):

```kotlin
@Test
fun `batch save rejects items with blank comment`() {
    val request = SaveSnapshotRequest(
        sessionId = "s1",
        previewId = "p1",
        workspaceId = "w1",
        screen = stubScreen(),
        items = listOf(
            stubDraftItem(draftItemId = "d1", comment = ""),
        ),
        frozenFingerprint = null,
        forceMismatchOverride = false,
    )
    val response = postBatch(request)
    assertEquals(422, response.statusCode)
    assertContains(response.body, "empty-comment")
}
```

(If `stubScreen()` / `stubDraftItem()` are not in the test util, copy the smallest existing builder pattern from the nearest test file in `console/` and adapt.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests "*FeedbackItemRoutesTest.batch save rejects items with blank comment*"`
Expected: FAIL — request succeeds with 200 / 201.

- [ ] **Step 3: Write minimal implementation**

In `FeedbackItemRoutes.kt`, add the validator function and call it from both save handlers (the ones at lines 39 and 117 today, where `request.comment` flows through):

```kotlin
private fun SaveSnapshotRequest.validateComments() {
    val empties = items.count { it.comment.isBlank() }
    if (empties > 0) {
        throw PreviewFeedbackRequestValidationException(
            "Cannot save annotation with empty comment ($empties item(s))."
        )
    }
}
```

Then at the entry of each save handler (immediately after `decodeSavePreviewFeedbackItemsBody()`):

```kotlin
val request = exchange.decodeSavePreviewFeedbackItemsBody()
request.validateComments()
// ... existing logic
```

In the existing `toConsoleHttpException()` mapping at line 176, add a branch:

```kotlin
text.startsWith("Cannot save annotation with empty comment") ->
    422 to "empty-comment"
```

In `FeedbackSessionStoreDelegate.addScreenWithItems` (defense-in-depth), add at the top:

```kotlin
require(items.all { it.comment.isNotBlank() }) {
    "addScreenWithItems received items with blank comment"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same Gradle command from Step 2. Expected: PASS.

Also run the existing console test suite to confirm no regression:
`./gradlew :fixthis-mcp:test`

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutesTest.kt
git commit -m "feat(server): reject empty-comment items with typed 422 empty-comment"
```

---

## Phase 2 — Trigger matrix completion

### Task 5: `boundaryTriggers.js` enum

**Files:**
- Create: `fixthis-mcp/src/main/console/boundaryTriggers.js`
- Create: `fixthis-mcp/src/main/console/__tests__/boundaryTriggers.test.html`

- [ ] **Step 1: Write the failing test**

Create `fixthis-mcp/src/main/console/__tests__/boundaryTriggers.test.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>boundaryTriggers</title></head>
<body>
<script src="../boundaryTriggers.js"></script>
<script>
(function () {
  const failures = [];
  const expected = [
    'SESSION_SWITCH','SESSION_CREATE','SESSION_DELETE',
    'SCREEN_SWITCH','NEW_CAPTURE','ELEMENT_CLICK','ESCAPE_KEY',
    'BROWSER_REFRESH','TAB_CLOSE','ROUTE_CHANGE',
    'SERVER_DISCONNECT','RECONNECT','BRIDGE_MISMATCH',
    'ACTIVITY_DRIFT','INACTIVITY','EDITOR_BACK',
  ];
  for (const k of expected) {
    if (typeof Trigger[k] !== 'string') failures.push(`Trigger.${k} missing or not a string`);
  }
  if (!Object.isFrozen(Trigger)) failures.push('Trigger must be frozen');
  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/boundaryTriggers.test.html`
Expected: FAIL — `Trigger is not defined`.

- [ ] **Step 3: Write minimal implementation**

Create `fixthis-mcp/src/main/console/boundaryTriggers.js`:

```js
// @requires (none)
// boundaryTriggers.js — frozen enum of context-transition triggers.

const Trigger = Object.freeze({
  SESSION_SWITCH:    'sessionSwitch',
  SESSION_CREATE:    'sessionCreate',
  SESSION_DELETE:    'sessionDelete',
  SCREEN_SWITCH:     'screenSwitch',
  NEW_CAPTURE:       'newCapture',
  ELEMENT_CLICK:     'elementClick',
  ESCAPE_KEY:        'escapeKey',
  BROWSER_REFRESH:   'browserRefresh',
  TAB_CLOSE:         'tabClose',
  ROUTE_CHANGE:      'routeChange',
  SERVER_DISCONNECT: 'serverDisconnect',
  RECONNECT:         'reconnect',
  BRIDGE_MISMATCH:   'bridgeMismatch',
  ACTIVITY_DRIFT:    'activityDrift',
  INACTIVITY:        'inactivity',
  EDITOR_BACK:       'editorBack',
});
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/boundaryTriggers.js \
        fixthis-mcp/src/main/console/__tests__/boundaryTriggers.test.html
git commit -m "feat(console): add boundaryTriggers enum (16 context-transition triggers)"
```

---

### Task 6: `boundaryPolicy.js` — 15×3 verdict matrix

**Files:**
- Create: `fixthis-mcp/src/main/console/boundaryPolicy.js`
- Create: `fixthis-mcp/src/main/console/__tests__/boundaryPolicy.test.html`

- [ ] **Step 1: Write the failing test**

Create `fixthis-mcp/src/main/console/__tests__/boundaryPolicy.test.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>boundaryPolicy 45-cell matrix</title></head>
<body>
<script src="../boundaryTriggers.js"></script>
<script src="../boundaryPolicy.js"></script>
<script>
(function () {
  const failures = [];

  // The full matrix from design §2. State 'saved' is treated as 'none' for boundary purposes
  // (saved annotations auto-save; transitions don't gate them).
  const M = {
    none:        'pass',
    pendingTarget: 'discardWithToast',
    draft:       'boundaryDialog',
  };
  // Triggers grouped by their effective verdict family.
  const groups = [
    { triggers: ['SESSION_SWITCH','SESSION_CREATE','SESSION_DELETE','ROUTE_CHANGE','EDITOR_BACK'],
      none: 'pass', pendingTarget: 'discardWithToast', draft: 'boundaryDialog' },
    { triggers: ['SCREEN_SWITCH','NEW_CAPTURE'],
      none: 'pass', pendingTarget: 'discardWithToast', draft: 'preserve' },
    { triggers: ['ELEMENT_CLICK'],
      none: 'pass', pendingTarget: 'silentSwap', draft: 'preserve' },
    { triggers: ['ESCAPE_KEY'],
      none: 'pass', pendingTarget: 'discardWithToast', draft: 'discardConfirm' },
    { triggers: ['BROWSER_REFRESH','TAB_CLOSE'],
      none: 'pass', pendingTarget: 'silentLoss', draft: 'beforeunloadGuard' },
    { triggers: ['SERVER_DISCONNECT'],
      none: 'pass', pendingTarget: 'retainWithBadge', draft: 'retainWithBadge' },
    { triggers: ['RECONNECT'],
      none: 'pass', pendingTarget: 'staleRevalidate', draft: 'staleRevalidate' },
    { triggers: ['BRIDGE_MISMATCH'],
      none: 'pass', pendingTarget: 'discardWithToast', draft: 'retainAndMigrate' },
    { triggers: ['ACTIVITY_DRIFT','INACTIVITY'],
      none: 'pass', pendingTarget: 'retain', draft: 'retain' },
  ];

  for (const g of groups) {
    for (const tName of g.triggers) {
      for (const state of ['none','pendingTarget','draft']) {
        const verdict = boundaryPolicy(Trigger[tName], state, {});
        const expected = g[state];
        if (verdict !== expected) {
          failures.push(`${tName} × ${state}: expected ${expected}, got ${verdict}`);
        }
      }
    }
  }

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/boundaryPolicy.test.html`
Expected: FAIL — `boundaryPolicy is not defined`.

- [ ] **Step 3: Write minimal implementation**

Create `fixthis-mcp/src/main/console/boundaryPolicy.js`:

```js
// @requires boundaryTriggers.js
// boundaryPolicy.js — pure verdict resolver for design §2 trigger × state matrix.

function boundaryPolicy(trigger, state, _payload) {
  if (state === 'none') return 'pass';

  switch (trigger) {
    case Trigger.SESSION_SWITCH:
    case Trigger.SESSION_CREATE:
    case Trigger.SESSION_DELETE:
    case Trigger.ROUTE_CHANGE:
    case Trigger.EDITOR_BACK:
      return state === 'draft' ? 'boundaryDialog' : 'discardWithToast';

    case Trigger.SCREEN_SWITCH:
    case Trigger.NEW_CAPTURE:
      return state === 'draft' ? 'preserve' : 'discardWithToast';

    case Trigger.ELEMENT_CLICK:
      return state === 'draft' ? 'preserve' : 'silentSwap';

    case Trigger.ESCAPE_KEY:
      return state === 'draft' ? 'discardConfirm' : 'discardWithToast';

    case Trigger.BROWSER_REFRESH:
    case Trigger.TAB_CLOSE:
      return state === 'draft' ? 'beforeunloadGuard' : 'silentLoss';

    case Trigger.SERVER_DISCONNECT:
      return 'retainWithBadge';

    case Trigger.RECONNECT:
      return 'staleRevalidate';

    case Trigger.BRIDGE_MISMATCH:
      return state === 'draft' ? 'retainAndMigrate' : 'discardWithToast';

    case Trigger.ACTIVITY_DRIFT:
    case Trigger.INACTIVITY:
      return 'retain';

    default:
      return 'pass';
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/boundaryPolicy.js \
        fixthis-mcp/src/main/console/__tests__/boundaryPolicy.test.html
git commit -m "feat(console): add boundaryPolicy 15×3 verdict resolver"
```

---

### Task 7: `toastCopy.js` + `resolveTrigger` glue

**Files:**
- Create: `fixthis-mcp/src/main/console/toastCopy.js`
- Modify: `fixthis-mcp/src/main/console/draftUseCases.js` (add `resolveTrigger`)
- Create: `fixthis-mcp/src/main/console/__tests__/resolveTrigger.test.html`

- [ ] **Step 1: Write the failing test**

Create `fixthis-mcp/src/main/console/__tests__/resolveTrigger.test.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>resolveTrigger</title></head>
<body>
<script src="../boundaryTriggers.js"></script>
<script src="../boundaryPolicy.js"></script>
<script src="../toastCopy.js"></script>
<script src="../editorState.js"></script>
<script src="../draftWorkspace.js"></script>
<script src="../draftUseCases.js"></script>
<script>
(function () {
  const failures = [];
  const fired = [];

  const ports = {
    showToast: (text, opts) => fired.push({ kind: 'toast', text, opts }),
    openBoundaryDialog: (variant, ctx) => fired.push({ kind: 'dialog', variant, ctx }),
    silentDiscard: () => fired.push({ kind: 'discard' }),
    preserve: () => fired.push({ kind: 'preserve' }),
  };

  // Pending + SESSION_SWITCH → discard + toast.
  fired.length = 0;
  resolveTrigger(Trigger.SESSION_SWITCH, { state: 'pendingTarget' }, ports);
  if (!fired.some(e => e.kind === 'discard') || !fired.some(e => e.kind === 'toast' && /Switched/.test(e.text))) {
    failures.push('pending + SESSION_SWITCH should produce discard + Switched toast');
  }

  // Draft + SESSION_SWITCH → boundary dialog.
  fired.length = 0;
  resolveTrigger(Trigger.SESSION_SWITCH, { state: 'draft' }, ports);
  if (!fired.some(e => e.kind === 'dialog' && e.variant === 'sessionSwitch')) {
    failures.push('draft + SESSION_SWITCH should open sessionSwitch dialog');
  }

  // None + anything → pass.
  fired.length = 0;
  resolveTrigger(Trigger.SESSION_SWITCH, { state: 'none' }, ports);
  if (fired.length !== 0) failures.push('none state should be no-op');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/resolveTrigger.test.html`
Expected: FAIL — `resolveTrigger is not defined`.

- [ ] **Step 3: Write minimal implementation**

Create `fixthis-mcp/src/main/console/toastCopy.js`:

```js
// @requires boundaryTriggers.js
// toastCopy.js — trigger → toast text for Pending Target discard transitions.

const ToastCopy = Object.freeze({
  [Trigger.SESSION_SWITCH]:    '🔄 Switched · 1 empty annotation discarded',
  [Trigger.SESSION_CREATE]:    '🔄 New session · 1 empty annotation discarded',
  [Trigger.SESSION_DELETE]:    '🗑 Session deleted · 1 empty annotation discarded',
  [Trigger.SCREEN_SWITCH]:     '📷 Different screen · 1 empty annotation discarded',
  [Trigger.NEW_CAPTURE]:       '📷 New screen · 1 empty annotation discarded',
  [Trigger.ESCAPE_KEY]:        '🎯 Cancelled',
  [Trigger.ROUTE_CHANGE]:      '🔄 Navigated · 1 empty annotation discarded',
  [Trigger.BRIDGE_MISMATCH]:   '⚠ Bridge upgraded · 1 empty annotation discarded',
  [Trigger.EDITOR_BACK]:       '🎯 Cancelled',
});

function toastTextForTrigger(trigger) {
  return ToastCopy[trigger] || null;
}
```

Append to `fixthis-mcp/src/main/console/draftUseCases.js`:

```js
function resolveTrigger(trigger, ctx, ports) {
  const state = ctx.state;
  const verdict = boundaryPolicy(trigger, state, ctx);
  switch (verdict) {
    case 'pass':
      return;
    case 'discardWithToast': {
      ports.silentDiscard?.();
      const text = toastTextForTrigger(trigger);
      if (text) ports.showToast?.(text, { class: 'info', duration: 2000 });
      return;
    }
    case 'silentSwap':
      ports.silentDiscard?.();
      return;
    case 'silentLoss':
      // No toast — refresh/close has its own browser-native signal for Draft.
      return;
    case 'boundaryDialog':
      ports.openBoundaryDialog?.(boundaryVariantForTrigger(trigger), ctx);
      return;
    case 'discardConfirm':
      ports.openBoundaryDialog?.('editorBack', ctx);
      return;
    case 'beforeunloadGuard':
      // The beforeunload listener returns truthy to trigger native warning.
      return;
    case 'preserve':
    case 'retain':
    case 'retainWithBadge':
    case 'staleRevalidate':
    case 'retainAndMigrate':
      ports.preserve?.();
      return;
    default:
      return;
  }
}

function boundaryVariantForTrigger(trigger) {
  switch (trigger) {
    case Trigger.SESSION_SWITCH: return 'sessionSwitch';
    case Trigger.SESSION_CREATE: return 'sessionCreate';
    case Trigger.SESSION_DELETE: return 'sessionDelete';
    case Trigger.EDITOR_BACK:    return 'editorBack';
    case Trigger.ROUTE_CHANGE:   return 'routeChange';
    default:                     return 'editorBack';
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/toastCopy.js \
        fixthis-mcp/src/main/console/draftUseCases.js \
        fixthis-mcp/src/main/console/__tests__/resolveTrigger.test.html
git commit -m "feat(console): add resolveTrigger glue + toastCopy map"
```

---

### Task 8: Migrate session-switch and Esc call sites to `resolveTrigger`

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js` (session row click)
- Modify: `fixthis-mcp/src/main/console/shortcuts.js:8-25` (Esc handler)
- Modify: `fixthis-mcp/src/main/console/main.js` (beforeunload)

- [ ] **Step 1: Write the failing test (integration smoke)**

Create or extend `fixthis-mcp/src/main/console/__tests__/triggerCallsite.test.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>trigger call sites</title></head>
<body>
<div id="sessions"></div>
<script src="../boundaryTriggers.js"></script>
<script src="../boundaryPolicy.js"></script>
<script src="../toastCopy.js"></script>
<script src="../editorState.js"></script>
<script>
(function () {
  // This smoke test asserts that the consumer code imports Trigger and calls resolveTrigger.
  // The full DOM harness for history click → resolveTrigger is in Phase 3 integration.
  // Here we just lock in that the symbols are wired into the bundle entry.
  const failures = [];
  // After bundling, the global namespace should include Trigger and resolveTrigger.
  if (typeof Trigger === 'undefined') failures.push('Trigger missing from bundle');
  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/triggerCallsite.test.html`
Expected: PASS for this minimal symbol check; the *behaviour* assertion comes when we migrate Esc.

Then run the existing harness to verify no regressions before editing call sites:
`node scripts/build-console-assets.mjs --check` (validates bundle output is sane).

- [ ] **Step 3: Migrate Esc handler**

Edit `fixthis-mcp/src/main/console/shortcuts.js`:

```js
// @requires state.js, boundaryTriggers.js, draftUseCases.js, editorState.js, undoRedo.js
function isTextInputFocused(target = document.activeElement) {
  const element = target?.nodeType === Node.ELEMENT_NODE ? target : target?.parentElement || document.activeElement;
  const tag = element?.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || element?.isContentEditable;
}

function handleGlobalShortcut(event) {
  if (event.repeat) return;
  if (isTextInputFocused(event.target)) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    const editorState = deriveEditorState(currentWorkspace(), currentSelection(), currentSavedItem());
    resolveTrigger(Trigger.ESCAPE_KEY, { state: editorState }, defaultTriggerPorts());
    return;
  }
  if (event.key.toLowerCase() === 'a' && !event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey) {
    event.preventDefault();
    enterAnnotateMode().catch(showError);
    return;
  }
}
```

Where `defaultTriggerPorts()` is defined once in `main.js`:

```js
function defaultTriggerPorts() {
  return {
    showToast: (text, opts) => statusSurfaceRegistry.show('toast-' + Date.now(), {
      surfaceClass: 'toast', priority: 3, content: text, autoDismissMs: opts?.duration ?? 2000,
    }),
    openBoundaryDialog: (variant, ctx) => renderBoundaryDialog(variant, ctx),
    silentDiscard: () => dispatch({ type: 'editor/discardPending' }),
    preserve: () => { /* no-op for now; preserve is the default */ },
  };
}
```

Migrate `history.js` session-row click handler in the same fashion. Pseudocode for the change:

```js
// Find the session row click handler (search: 'session' click, or wherever
// sessions are wired). Replace direct navigation with:
sessionRow.addEventListener('click', async (e) => {
  e.preventDefault();
  const targetSessionId = sessionRow.dataset.sessionId;
  const state = deriveEditorState(currentWorkspace(), currentSelection(), currentSavedItem());
  await resolveTrigger(Trigger.SESSION_SWITCH, { state, targetSessionId }, defaultTriggerPorts());
  // The dialog or discard ports complete the navigation; if verdict was 'pass',
  // navigate directly:
  if (state === 'none') navigateToSession(targetSessionId);
});
```

Add `beforeunload` listener in `main.js`:

```js
window.addEventListener('beforeunload', (event) => {
  const state = deriveEditorState(currentWorkspace(), currentSelection(), currentSavedItem());
  const verdict = boundaryPolicy(Trigger.BROWSER_REFRESH, state, {});
  if (verdict === 'beforeunloadGuard') {
    event.preventDefault();
    event.returnValue = '';
  }
  // 'silentLoss' is no-op (Policy A).
});
```

- [ ] **Step 4: Rebundle and run all console tests**

```
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/
```

Expected: bundle OK; all tests pass.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/shortcuts.js \
        fixthis-mcp/src/main/console/history.js \
        fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/console/__tests__/triggerCallsite.test.html
git commit -m "feat(console): migrate Esc/session-switch/beforeunload to resolveTrigger"
```

---

## Phase 3 — Inspector footer + back navigation

### Task 9: HTML restructure — `#inspectorFooter` slot + `#editorBack` slot

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/index.html:179-184`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css` (footer + back styles)

- [ ] **Step 1: Write the failing test**

Create `fixthis-mcp/src/main/console/__tests__/htmlScaffold.test.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>html scaffold</title></head>
<body>
<script>
(async function () {
  const res = await fetch('../../resources/console/index.html');
  const html = await res.text();
  const failures = [];
  if (!/id="inspectorFooter"/.test(html))    failures.push('inspectorFooter slot missing');
  if (!/id="editorBack"/.test(html))         failures.push('editorBack slot missing');
  if (!/id="toastContainer"/.test(html))     failures.push('toastContainer missing');
  if (/id="clearSelectionButton"/.test(html))failures.push('clearSelectionButton must be removed');
  if (/id="cancelAddFlowButton"/.test(html)) failures.push('cancelAddFlowButton must be removed');
  if (/id="clearDraftButton"/.test(html))    failures.push('clearDraftButton must be removed');
  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/htmlScaffold.test.html`
Expected: FAIL — `inspectorFooter slot missing`, etc.

- [ ] **Step 3: Modify HTML**

In `fixthis-mcp/src/main/resources/console/index.html`, replace lines 179-184:

```html
<div id="inspectorFooter" class="inspector-footer" data-editor-state="none" hidden></div>
```

Add an `editorBack` element at the top of the inspector panel (immediately inside the `<aside class="studio-inspector">` opening, before `<div class="panel-head">`):

```html
<button id="editorBack" class="editor-back-button" type="button" hidden>
  <span aria-hidden="true">←</span>
  <span>All annotations</span>
</button>
```

Add `toastContainer` immediately after the `#error` element (line 75):

```html
<div id="toastContainer" class="toast-container" aria-live="polite" aria-atomic="false"></div>
```

Add corresponding styles to `styles.css` (append near other inspector / surface styles):

```css
.editor-back-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: transparent;
  border: none;
  color: var(--color-text-muted, #888);
  padding: 8px 12px;
  cursor: pointer;
  font: inherit;
}
.editor-back-button:hover { color: var(--color-text, #ddd); }

.toast-container {
  position: fixed;
  bottom: 20px;
  right: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 200; /* will become var(--z-surface-toast) in Phase 5 */
  pointer-events: none;
}
.toast-container .toast {
  pointer-events: auto;
  background: rgba(20, 20, 28, 0.95);
  color: #ddd;
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 13px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.3);
  animation: toastIn 160ms ease-out;
}
@keyframes toastIn { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
@media (prefers-reduced-motion: reduce) {
  .toast-container .toast { animation: none; }
}
```

- [ ] **Step 4: Re-run the scaffold test**

Run the same command from Step 2. Expected: PASS.

Also verify the existing console tests still pass (a few may reference the removed IDs):

```
node scripts/build-console-assets.mjs
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/
```

If existing tests break on removed IDs, mark them with a `TODO(plan task 11)` comment and fix in Task 11 when we wire the renderer — do NOT delete tests.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/index.html \
        fixthis-mcp/src/main/resources/console/styles.css \
        fixthis-mcp/src/main/console/__tests__/htmlScaffold.test.html
git commit -m "feat(console): scaffold inspectorFooter/editorBack/toastContainer slots, remove legacy footer IDs"
```

---

### Task 10: `inspectorFooter.js` renderer

**Files:**
- Create: `fixthis-mcp/src/main/console/inspectorFooter.js`
- Create: `fixthis-mcp/src/main/console/__tests__/inspectorFooter.test.html`

- [ ] **Step 1: Write the failing test**

Create the test:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>inspectorFooter render</title></head>
<body>
<div id="inspectorFooter" hidden></div>
<script src="../inspectorFooter.js"></script>
<script>
(function () {
  const failures = [];
  const root = document.getElementById('inspectorFooter');

  renderInspectorFooter('none', {});
  if (!root.hidden) failures.push('none: footer should be hidden');

  renderInspectorFooter('pendingTarget', {});
  if (root.hidden) failures.push('pendingTarget: footer should be visible');
  if (!root.querySelector('[data-action="cancel"]')) failures.push('pendingTarget: Cancel button missing');
  if (root.querySelector('[data-action="addAnnotation"]')) failures.push('pendingTarget: Add button must NOT appear');

  renderInspectorFooter('draft', {});
  if (!root.querySelector('[data-action="cancel"]')) failures.push('draft: Cancel missing');
  if (!root.querySelector('[data-action="addAnnotation"]')) failures.push('draft: Add missing');

  renderInspectorFooter('saved', {});
  if (root.querySelector('[data-action="cancel"]')) failures.push('saved: Cancel should not appear');
  if (root.querySelector('[data-action="addAnnotation"]')) failures.push('saved: Add should not appear');
  if (!root.querySelector('[data-action="overflowToggle"]')) failures.push('saved: overflow toggle missing');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/inspectorFooter.test.html`
Expected: FAIL — `renderInspectorFooter is not defined`.

- [ ] **Step 3: Write minimal implementation**

Create `fixthis-mcp/src/main/console/inspectorFooter.js`:

```js
// @requires (none — DOM-only)
// inspectorFooter.js — state-dependent renderer for the inspector footer.

function renderInspectorFooter(state, ctx) {
  const root = document.getElementById('inspectorFooter');
  if (!root) return;
  root.dataset.editorState = state;

  if (state === 'none') {
    root.hidden = true;
    root.replaceChildren();
    return;
  }

  root.hidden = false;
  const cancel = makeButton({
    text: 'Cancel', action: 'cancel', className: '',
  });
  const addAnnotation = makeButton({
    text: 'Add annotation', action: 'addAnnotation', className: 'primary',
  });
  const overflow = makeButton({
    text: '⋯', action: 'overflowToggle', className: 'overflow-toggle', ariaLabel: 'More actions',
  });

  switch (state) {
    case 'pendingTarget':
      root.replaceChildren(cancel);
      return;
    case 'draft':
      root.replaceChildren(cancel, addAnnotation);
      return;
    case 'saved':
      root.replaceChildren(overflow);
      return;
  }
}

function makeButton({ text, action, className, ariaLabel }) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.textContent = text;
  btn.dataset.action = action;
  if (className) btn.className = className;
  if (ariaLabel) btn.setAttribute('aria-label', ariaLabel);
  return btn;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/inspectorFooter.js \
        fixthis-mcp/src/main/console/__tests__/inspectorFooter.test.html
git commit -m "feat(console): inspectorFooter state-dependent renderer"
```

---

### Task 11: Wire footer to store + migrate broken legacy tests

**Files:**
- Modify: `fixthis-mcp/src/main/console/consoleApp.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js` (wire `oninput` to `ensureWorkspaceForSelection`)
- Modify: any test files referencing removed IDs

- [ ] **Step 1: Write the failing integration test**

Create `fixthis-mcp/src/main/console/__tests__/footerSubscriberFlow.test.html`:

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>footer subscriber flow</title></head>
<body>
<div id="inspectorFooter" hidden></div>
<textarea id="comment"></textarea>
<script src="../editorState.js"></script>
<script src="../draftWorkspace.js"></script>
<script src="../inspectorFooter.js"></script>
<script>
(async function () {
  const failures = [];
  // Simulate selection-only (Pending Target):
  renderInspectorFooter(deriveEditorState(null, { id: 'el-1' }, null), {});
  if (!document.querySelector('#inspectorFooter [data-action="cancel"]')) failures.push('Pending should render Cancel');
  if (document.querySelector('#inspectorFooter [data-action="addAnnotation"]')) failures.push('Pending should NOT render Add');

  // Simulate one keystroke (Draft):
  const ws = { workspaceId: 'w-1', lifecycle: 'editing', items: [{ comment: 'x' }] };
  renderInspectorFooter(deriveEditorState(ws, { id: 'el-1' }, null), {});
  if (!document.querySelector('#inspectorFooter [data-action="addAnnotation"]')) failures.push('Draft should render Add');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails (or passes trivially — then add the subscriber)**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/footerSubscriberFlow.test.html`
Expected: PASS (this is a render-only test). Then run all tests:
`node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/`
Expected: any legacy test referencing `clearSelectionButton`, `cancelAddFlowButton`, `addItemButton`, `clearDraftButton` should currently FAIL.

- [ ] **Step 3: Wire the renderer + migrate `oninput`**

In `consoleApp.js`, find the canonical store subscription (search for `consoleStore.subscribe` or equivalent) and add:

```js
consoleStore.subscribe((state, prevState) => {
  const next = deriveEditorState(state.draftWorkspace, state.selection, state.savedItem);
  const prev = prevState ? deriveEditorState(prevState.draftWorkspace, prevState.selection, prevState.savedItem) : null;
  if (next !== prev) renderInspectorFooter(next, { state, prev });
});
```

In `annotations.js`, find the `#comment` `oninput` (or `addEventListener('input', ...)`) site (one of the comment.value writes around line 532). Replace any pre-existing direct workspace creation with:

```js
commentEl.addEventListener('input', () => {
  const value = commentEl.value;
  const selection = currentSelection();
  if (value.length > 0) {
    const workspace = ensureWorkspaceForSelection(getState(), selection, { nextWorkspaceId });
    if (workspace !== getState().draftWorkspace) {
      dispatch({ type: 'workspace/created', workspace });
    }
    updatePendingDraftItem(currentItemId(), { comment: value }, { recordHistory: false });
  } else {
    // Comment became empty — possibly free workspace.
    const freed = maybeFreeWorkspaceOnEmpty(getState().draftWorkspace, currentItemId());
    if (freed === null) dispatch({ type: 'workspace/freed', reason: 'last-item-empty' });
  }
});
```

Migrate any legacy test that selects `#clearSelectionButton` etc. to use `[data-action="cancel"]` / `[data-action="addAnnotation"]` / `[data-action="overflowToggle"]`. Grep first:

```
grep -rn 'clearSelectionButton\|cancelAddFlowButton\|addItemButton\|clearDraftButton' fixthis-mcp/src/main/console/__tests__/
```

For each match, update the selector.

- [ ] **Step 4: Rebundle and run all tests**

```
node scripts/build-console-assets.mjs
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/
./gradlew :fixthis-mcp:test
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/consoleApp.js \
        fixthis-mcp/src/main/console/annotations.js \
        fixthis-mcp/src/main/console/__tests__/
git commit -m "feat(console): wire inspectorFooter to store + comment.input → workspace gate"
```

---

### Task 12: `editorBackButton.js` — back navigation handler

**Files:**
- Create: `fixthis-mcp/src/main/console/editorBackButton.js`
- Modify: `fixthis-mcp/src/main/console/consoleApp.js` (subscribe visibility + click)
- Create: `fixthis-mcp/src/main/console/__tests__/editorBackButton.test.html`

- [ ] **Step 1: Write the failing test**

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>editorBackButton</title></head>
<body>
<button id="editorBack" hidden><span>←</span><span>All annotations</span></button>
<script src="../boundaryTriggers.js"></script>
<script src="../boundaryPolicy.js"></script>
<script src="../editorBackButton.js"></script>
<script>
(function () {
  const failures = [];
  const events = [];
  const ports = {
    showToast: (text) => events.push({ kind: 'toast', text }),
    openBoundaryDialog: (variant) => events.push({ kind: 'dialog', variant }),
    navigateToList: () => events.push({ kind: 'nav' }),
    silentDiscard: () => events.push({ kind: 'discard' }),
  };

  // None: hidden
  renderEditorBack({ state: 'none' });
  if (!document.getElementById('editorBack').hidden) failures.push('none: should be hidden');

  // Pending: visible; click → discard + toast + nav
  renderEditorBack({ state: 'pendingTarget' });
  if (document.getElementById('editorBack').hidden) failures.push('pending: should be visible');
  events.length = 0;
  handleEditorBackClick({ state: 'pendingTarget' }, ports);
  if (!events.find(e => e.kind === 'discard')) failures.push('pending click: discard missing');
  if (!events.find(e => e.kind === 'toast'))   failures.push('pending click: toast missing');
  if (!events.find(e => e.kind === 'nav'))     failures.push('pending click: nav missing');

  // Draft: click → boundary dialog (no immediate nav)
  events.length = 0;
  handleEditorBackClick({ state: 'draft' }, ports);
  if (!events.find(e => e.kind === 'dialog' && e.variant === 'editorBack')) failures.push('draft click: dialog missing');
  if (events.find(e => e.kind === 'nav')) failures.push('draft click: nav should be deferred to dialog');

  // Saved clean: immediate nav
  events.length = 0;
  handleEditorBackClick({ state: 'saved' }, ports);
  if (!events.find(e => e.kind === 'nav')) failures.push('saved click: nav missing');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/editorBackButton.test.html`
Expected: FAIL — `renderEditorBack is not defined`.

- [ ] **Step 3: Write minimal implementation**

Create `fixthis-mcp/src/main/console/editorBackButton.js`:

```js
// @requires boundaryTriggers.js, boundaryPolicy.js
// editorBackButton.js — "← All annotations" navigation.

function renderEditorBack(ctx) {
  const el = document.getElementById('editorBack');
  if (!el) return;
  el.hidden = (ctx.state === 'none');
}

function handleEditorBackClick(ctx, ports) {
  const verdict = boundaryPolicy(Trigger.EDITOR_BACK, ctx.state, ctx);
  switch (verdict) {
    case 'pass':
      ports.navigateToList();
      return;
    case 'discardWithToast':
      ports.silentDiscard();
      ports.showToast('🎯 Cancelled', { class: 'info', duration: 1500 });
      ports.navigateToList();
      return;
    case 'boundaryDialog':
      // The dialog ports handle save/discard/cancel and call navigateToList themselves.
      ports.openBoundaryDialog('editorBack', ctx);
      return;
    default:
      ports.navigateToList();
      return;
  }
}
```

In `consoleApp.js`, append:

```js
document.getElementById('editorBack')?.addEventListener('click', (e) => {
  e.preventDefault();
  const state = deriveEditorState(currentWorkspace(), currentSelection(), currentSavedItem());
  handleEditorBackClick({ state }, defaultTriggerPorts());
});

consoleStore.subscribe((state) => {
  const editorState = deriveEditorState(state.draftWorkspace, state.selection, state.savedItem);
  renderEditorBack({ state: editorState });
});
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

Rebundle + full test sweep:

```
node scripts/build-console-assets.mjs
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/
```

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/editorBackButton.js \
        fixthis-mcp/src/main/console/consoleApp.js \
        fixthis-mcp/src/main/console/__tests__/editorBackButton.test.html
git commit -m "feat(console): editorBack back-to-list handler with per-state routing"
```

---

## Phase 4 — Destructive affordances + dialog labels

### Task 13: `boundaryDialogVariants.js` — variant map + renderer

**Files:**
- Create: `fixthis-mcp/src/main/console/boundaryDialogVariants.js`
- Create: `fixthis-mcp/src/main/console/__tests__/boundaryDialogVariants.test.html`

- [ ] **Step 1: Write the failing test**

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>boundary dialog variants</title></head>
<body>
<div id="sessionBoundarySheet" hidden>
  <h2 data-boundary-title></h2>
  <p data-boundary-summary></p>
  <div class="session-boundary-actions">
    <button data-boundary-action></button>
    <button data-boundary-action></button>
    <button data-boundary-action></button>
    <button data-boundary-action></button>
  </div>
</div>
<script src="../boundaryDialogVariants.js"></script>
<script>
(function () {
  const failures = [];
  renderBoundaryDialog('sessionSwitch', { currentSessionName: 'Login', targetSessionName: 'Profile' });
  const title = document.querySelector('[data-boundary-title]').textContent;
  if (!/Save draft before switching/.test(title)) failures.push('sessionSwitch title wrong: ' + title);
  const labels = [...document.querySelectorAll('.session-boundary-actions button')].filter(b => !b.hidden).map(b => b.textContent);
  if (labels[0] !== 'Cancel') failures.push('Cancel must be leftmost; got ' + labels[0]);
  if (!labels.includes('Save and switch')) failures.push('primary label missing');
  if (!labels.includes('Discard'))         failures.push('Discard label missing');

  renderBoundaryDialog('sessionCreate', {});
  const summary2 = document.querySelector('[data-boundary-summary]').textContent;
  if (!/unsaved draft/.test(summary2)) failures.push('sessionCreate summary wrong: ' + summary2);

  renderBoundaryDialog('sessionDelete', { currentSessionName: 'Settings', annotationCount: 5, screenCount: 3 });
  if (!/Settings/.test(document.querySelector('[data-boundary-title]').textContent)) failures.push('sessionDelete title missing name');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/boundaryDialogVariants.test.html`
Expected: FAIL — `renderBoundaryDialog is not defined`.

- [ ] **Step 3: Write minimal implementation**

Create `fixthis-mcp/src/main/console/boundaryDialogVariants.js`:

```js
// @requires (none — DOM-only)
// boundaryDialogVariants.js — labels and rendering for #sessionBoundarySheet.

const BoundaryDialogVariants = Object.freeze({
  sessionSwitch: {
    title: () => 'Save draft before switching?',
    summary: (ctx) => `1 unsaved draft in '${ctx.currentSessionName || 'current session'}'` +
                      (ctx.targetSessionName ? `, switching to '${ctx.targetSessionName}'.` : '.'),
    primary:   { label: 'Save and switch', action: 'saveAndProceed' },
    secondary: { label: 'Discard',         action: 'discardAndProceed' },
    cancel:    { label: 'Cancel',          action: 'cancel' },
  },
  sessionCreate: {
    title: () => 'Save draft before creating new session?',
    summary: () => '1 unsaved draft.',
    primary:   { label: 'Save and create', action: 'saveAndProceed' },
    secondary: { label: 'Discard',         action: 'discardAndProceed' },
    cancel:    { label: 'Cancel',          action: 'cancel' },
  },
  sessionDelete: {
    title: (ctx) => `Delete '${ctx.currentSessionName || 'current session'}'?`,
    summary: (ctx) => `Removes ${ctx.annotationCount ?? 0} annotations across ${ctx.screenCount ?? 0} screens. Cannot be undone.`,
    primary:   { label: 'Save and delete', action: 'saveAndProceed' },
    secondary: { label: 'Discard',         action: 'discardAndProceed' },
    cancel:    { label: 'Cancel',          action: 'cancel' },
  },
  editorBack: {
    title: () => 'Save draft before going back?',
    summary: () => '1 unsaved draft.',
    primary:   { label: 'Save and back',   action: 'saveAndProceed' },
    secondary: { label: 'Discard',         action: 'discardAndProceed' },
    cancel:    { label: 'Cancel',          action: 'cancel' },
  },
  routeChange: {
    title: () => 'Save draft before leaving?',
    summary: () => '1 unsaved draft.',
    primary:   { label: 'Save and leave',  action: 'saveAndProceed' },
    secondary: { label: 'Discard',         action: 'discardAndProceed' },
    cancel:    { label: 'Cancel',          action: 'cancel' },
  },
});

function renderBoundaryDialog(variantName, ctx) {
  const v = BoundaryDialogVariants[variantName];
  if (!v) throw new Error(`Unknown boundary variant: ${variantName}`);

  const root = document.getElementById('sessionBoundarySheet');
  if (!root) return;
  root.hidden = false;

  root.querySelector('[data-boundary-title]').textContent = v.title(ctx);
  root.querySelector('[data-boundary-summary]').textContent = v.summary(ctx);

  // Slot order: Cancel (leftmost), Discard, Primary, [reserved]
  const slots = [v.cancel, v.secondary, v.primary, null];
  const buttons = [...root.querySelectorAll('.session-boundary-actions [data-boundary-action]')];
  buttons.forEach((btn, idx) => {
    const slot = slots[idx];
    if (!slot) { btn.hidden = true; return; }
    btn.hidden = false;
    btn.textContent = slot.label;
    btn.dataset.boundaryAction = slot.action;
    btn.className =
      slot === v.cancel    ? '' :
      slot === v.secondary ? 'annotation-danger' :
                             'annotation-done';
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/boundaryDialogVariants.js \
        fixthis-mcp/src/main/console/__tests__/boundaryDialogVariants.test.html
git commit -m "feat(console): boundary dialog variants with consequence-explicit labels"
```

---

### Task 14: Session row trash icon (replaces session `×`)

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js` (row template + handler)
- Modify: `fixthis-mcp/src/main/resources/console/styles.css` (`.session-row-delete`)

- [ ] **Step 1: Write the failing test**

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>session trash icon</title></head>
<body>
<div id="sessions"></div>
<div id="sessionBoundarySheet" hidden>
  <h2 data-boundary-title></h2><p data-boundary-summary></p>
  <div class="session-boundary-actions">
    <button data-boundary-action></button><button data-boundary-action></button>
    <button data-boundary-action></button><button data-boundary-action></button>
  </div>
</div>
<script src="../boundaryDialogVariants.js"></script>
<script src="../history.js"></script>
<script>
(function () {
  const failures = [];
  const fakeSessions = [
    { sessionId: 'a', screensCount: 3, itemsCount: 5, updatedAtEpochMillis: Date.now() },
  ];
  renderSessionRows(fakeSessions);
  const row = document.querySelector('#sessions [data-session-id="a"]');
  if (!row) failures.push('row not rendered');
  const deleteBtn = row?.querySelector('.session-row-delete');
  if (!deleteBtn) failures.push('trash icon missing');
  if (deleteBtn?.getAttribute('aria-label') !== 'Delete session') failures.push('aria-label wrong');

  // Click → boundary dialog opens with sessionDelete variant
  deleteBtn?.click();
  const sheet = document.getElementById('sessionBoundarySheet');
  if (sheet?.hidden) failures.push('boundary sheet did not open');
  if (!/Delete/.test(sheet?.querySelector('[data-boundary-title]')?.textContent || '')) failures.push('sessionDelete title missing');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/sessionTrashIcon.test.html`
Expected: FAIL — `renderSessionRows is not defined` or trash icon missing.

- [ ] **Step 3: Modify `history.js`**

Find the existing session-row builder. Update its template (HTML string concatenation) to include the trash icon:

```js
function buildSessionRow(session) {
  const div = document.createElement('div');
  div.className = 'history-row session-row';
  div.dataset.sessionId = session.sessionId;
  div.innerHTML = `
    <div class="session-label">${formatSessionLabel(session, ordinalFor(session))}</div>
    <div class="session-summary">${formatSessionSummary(session)}</div>
    <button type="button"
            class="session-row-delete icon-button"
            data-action="deleteSession"
            aria-label="Delete session"
            title="Delete session">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M3 6h18M8 6v-2a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14"
              stroke="currentColor" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </button>
  `;
  div.querySelector('.session-row-delete').addEventListener('click', (e) => {
    e.stopPropagation();
    renderBoundaryDialog('sessionDelete', {
      currentSessionName: formatSessionLabel(session, ordinalFor(session)),
      annotationCount: session.itemsCount ?? 0,
      screenCount: session.screensCount ?? 0,
    });
  });
  return div;
}

function renderSessionRows(sessions) {
  const root = document.getElementById('sessions');
  root.replaceChildren(...sessions.map(buildSessionRow));
}
```

Append CSS to `styles.css`:

```css
.session-row { position: relative; }
.session-row-delete {
  position: absolute;
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
  background: transparent;
  border: none;
  color: var(--color-text-muted, #888);
  opacity: 0;
  transition: opacity 160ms ease-out;
  cursor: pointer;
  padding: 4px;
}
.session-row:hover .session-row-delete,
.session-row:focus-within .session-row-delete {
  opacity: 1;
}
.session-row-delete:hover { color: #e87f7f; }
.session-row-delete:focus-visible { outline: 2px solid #e87f7f; outline-offset: 2px; }
.session-row-delete svg { width: 16px; height: 16px; display: block; }
@media (prefers-reduced-motion: reduce) { .session-row-delete { transition: none; } }
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

Rebundle + full sweep:

```
node scripts/build-console-assets.mjs
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/
```

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/history.js \
        fixthis-mcp/src/main/resources/console/styles.css \
        fixthis-mcp/src/main/console/__tests__/sessionTrashIcon.test.html
git commit -m "feat(console): replace session × with trash-icon + sessionDelete boundary dialog"
```

---

### Task 15: Device picker "Forget device" + remove comment Clear

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/index.html:33-35` (rename, change icon)
- Modify: `fixthis-mcp/src/main/console/devices.js` (handler) — search for current `disconnectDeviceButton` handler
- Remove (if exists): any `clearComment` element / handler

- [ ] **Step 1: Write the failing test**

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>device forget + no comment clear</title></head>
<body>
<script>
(async function () {
  const res = await fetch('../../resources/console/index.html');
  const html = await res.text();
  const failures = [];
  if (!/id="forgetDeviceButton"/.test(html)) failures.push('forgetDeviceButton missing');
  if (/id="disconnectDeviceButton"/.test(html)) failures.push('legacy disconnectDeviceButton must be removed');
  if (/Clear comment|id="clearComment"|data-action="clearComment"/.test(html)) failures.push('comment Clear control must be removed');

  // Forget button needs a text label, not bare ×
  const forgetMatch = html.match(/<button[^>]*id="forgetDeviceButton"[\s\S]*?<\/button>/);
  if (!forgetMatch || !/Forget device|sr-only/.test(forgetMatch[0])) {
    failures.push('forgetDeviceButton must include a visible or sr-only "Forget device" label');
  }

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/deviceForgetAndCommentClear.test.html`
Expected: FAIL — `forgetDeviceButton missing`.

- [ ] **Step 3: Modify HTML + JS**

Replace `index.html:33-35`:

```html
<button id="forgetDeviceButton" class="device-forget-button icon-button" type="button" title="Forget device">
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M3 6h18M8 6v-2a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14"
          stroke="currentColor" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>
  <span class="sr-only">Forget device</span>
</button>
```

In `devices.js`, find the existing `disconnectDeviceButton` handler (search the file) and rename references to `forgetDeviceButton`. Wrap the action in a `confirm`-style dialog or reuse `renderBoundaryDialog` with a new lightweight variant in `boundaryDialogVariants.js`:

```js
// Append to BoundaryDialogVariants in boundaryDialogVariants.js
forgetDevice: {
  title: (ctx) => `Forget '${ctx.deviceName || 'this device'}'?`,
  summary: () => 'The next pick in the device list will pair fresh. Active session data is unaffected.',
  primary:   { label: 'Forget',   action: 'confirm' },
  cancel:    { label: 'Cancel',   action: 'cancel' },
},
```

In `devices.js`:

```js
document.getElementById('forgetDeviceButton')?.addEventListener('click', () => {
  renderBoundaryDialog('forgetDevice', { deviceName: currentDeviceName() });
});
```

(Wire the dialog's `confirm` action to the existing forget/clear-selection routine.)

Comment Clear removal: grep the codebase:

```
grep -rn 'clearComment\|id="clearComment"\|data-action="clearComment"' fixthis-mcp/src/main/
```

If nothing matches, no removal needed (the design's "Clear" was a hypothetical button that may not have been added). If matches exist, remove the element and handler.

Add to `styles.css`:

```css
.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

Rebundle + sweep.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/index.html \
        fixthis-mcp/src/main/resources/console/styles.css \
        fixthis-mcp/src/main/console/devices.js \
        fixthis-mcp/src/main/console/boundaryDialogVariants.js \
        fixthis-mcp/src/main/console/__tests__/deviceForgetAndCommentClear.test.html
git commit -m "feat(console): replace device × with Forget device confirm; remove ambiguous comment Clear"
```

---

### Task 16: Recovery prompt → variant-based (replace hardcoded `main.js:316-321`)

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js:295-325` (replace inline button markup with boundary dialog variant)
- Modify: `fixthis-mcp/src/main/console/boundaryDialogVariants.js` (add `pendingRecovery` variant)

- [ ] **Step 1: Write the failing test**

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>pending recovery variant</title></head>
<body>
<div id="sessionBoundarySheet" hidden>
  <h2 data-boundary-title></h2><p data-boundary-summary></p>
  <div class="session-boundary-actions">
    <button data-boundary-action></button><button data-boundary-action></button>
    <button data-boundary-action></button><button data-boundary-action></button>
  </div>
</div>
<script src="../boundaryDialogVariants.js"></script>
<script>
(function () {
  const failures = [];
  renderBoundaryDialog('pendingRecovery', { canResume: true, itemCount: 2 });
  const labels = [...document.querySelectorAll('.session-boundary-actions button')].filter(b => !b.hidden).map(b => b.textContent);
  if (!labels.includes('Resume draft')) failures.push('Resume draft label missing');
  if (!labels.includes('Discard'))      failures.push('Discard label missing');
  if (!labels.includes('Recapture'))    failures.push('Recapture label missing');
  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/pendingRecoveryVariant.test.html`
Expected: FAIL — `Unknown boundary variant: pendingRecovery`.

- [ ] **Step 3: Add `pendingRecovery` variant + replace inline markup**

Append to `BoundaryDialogVariants`:

```js
pendingRecovery: {
  title: () => 'Recover unsaved draft?',
  summary: (ctx) => `${ctx.itemCount ?? 1} annotation(s) preserved from previous session.`,
  primary:   { label: 'Resume draft', action: 'resume' },
  secondary: { label: 'Recapture',    action: 'recapture' },
  tertiary:  { label: 'Discard',      action: 'discard' },
  cancel:    { label: 'Cancel',       action: 'cancel' },
},
```

Update `renderBoundaryDialog` slot order to also handle 4-slot variants (the renderer already supports up to 4; ensure `pendingRecovery` uses tertiary correctly — adjust slot mapping):

```js
const slots = [v.cancel, v.tertiary || null, v.secondary, v.primary];
```

Replace `main.js:316-321` (the hardcoded `'<button ... data-resume-pending>Resume draft</button>'` block) with:

```js
renderBoundaryDialog('pendingRecovery', { canResume, itemCount: pendingItems.length });
// Wire dialog buttons by data-boundary-action; existing handlers stay.
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

Rebundle + sweep.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/boundaryDialogVariants.js \
        fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/console/__tests__/pendingRecoveryVariant.test.html
git commit -m "feat(console): move pending-recovery prompt onto boundaryDialogVariants"
```

---

## Phase 5 — Surface registry + disconnect UX

### Task 17: `statusSurfaceRegistry.js` — coordinator skeleton + unit tests

**Files:**
- Create: `fixthis-mcp/src/main/console/statusSurfaceRegistry.js`
- Create: `fixthis-mcp/src/main/console/__tests__/statusSurfaceRegistry.test.html`

- [ ] **Step 1: Write the failing test**

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>statusSurfaceRegistry</title></head>
<body>
<div id="surface-modal" hidden></div>
<div id="surface-overlay" hidden></div>
<div id="surface-inline" hidden></div>
<div id="surface-badge" hidden></div>
<div id="toastContainer"></div>
<script src="../statusSurfaceRegistry.js"></script>
<script>
(function () {
  const failures = [];
  const r = createStatusSurfaceRegistry();

  // Modal exclusion: opening modal suspends inline.
  r.show('a', { surfaceClass: 'inline',  priority: 3, element: document.getElementById('surface-inline'), content: 'hi' });
  if (document.getElementById('surface-inline').hidden) failures.push('inline should be visible before modal');
  r.show('m', { surfaceClass: 'modal',   priority: 1, element: document.getElementById('surface-modal'),  content: '!' });
  if (!document.getElementById('surface-inline').hidden) failures.push('inline should be suspended when modal visible');

  // Resume after modal closes.
  r.hide('m');
  if (document.getElementById('surface-inline').hidden) failures.push('inline should resume after modal hides');

  // Toast stack limit = 3.
  for (let i = 0; i < 5; i += 1) {
    r.show('t' + i, { surfaceClass: 'toast', priority: 3, content: 'toast ' + i, autoDismissMs: 99999 });
  }
  if (r.size('toast') > 3) failures.push('toast stack must not exceed 3, got ' + r.size('toast'));

  // Banner limit = 1.
  r.show('b1', { surfaceClass: 'banner', priority: 2, content: 'first' });
  r.show('b2', { surfaceClass: 'banner', priority: 2, content: 'second' });
  if (r.size('banner') !== 1) failures.push('banner stack must be 1, got ' + r.size('banner'));

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/statusSurfaceRegistry.test.html`
Expected: FAIL — `createStatusSurfaceRegistry is not defined`.

- [ ] **Step 3: Write minimal implementation**

Create `fixthis-mcp/src/main/console/statusSurfaceRegistry.js`:

```js
// @requires (none — DOM-only)
// statusSurfaceRegistry.js — central coordinator for the 8 status surfaces.

const STACK_LIMIT = Object.freeze({
  modal: 1, modalCanvas: 1, banner: 1, inline: Infinity, badge: Infinity, toast: 3, pill: 1,
});

function createStatusSurfaceRegistry() {
  const surfaces = new Map();          // id → { surfaceClass, element, priority, timer, content }
  const visibleByClass = new Map();    // class → Set<id>
  const suspendedClasses = new Set();

  function trackVisible(id, surfaceClass) {
    if (!visibleByClass.has(surfaceClass)) visibleByClass.set(surfaceClass, new Set());
    visibleByClass.get(surfaceClass).add(id);
  }
  function untrackVisible(id, surfaceClass) {
    visibleByClass.get(surfaceClass)?.delete(id);
  }
  function size(surfaceClass) { return visibleByClass.get(surfaceClass)?.size ?? 0; }

  function renderToast(id, content) {
    const container = document.getElementById('toastContainer');
    if (!container) return null;
    const el = document.createElement('div');
    el.className = 'toast';
    el.dataset.toastId = id;
    el.textContent = content;
    container.appendChild(el);
    return el;
  }

  function show(id, opts) {
    if (surfaces.has(id)) hide(id);

    const surfaceClass = opts.surfaceClass;
    const element = opts.element || (surfaceClass === 'toast' ? renderToast(id, opts.content) : null);

    // Stack overflow: evict oldest of same class.
    const limit = STACK_LIMIT[surfaceClass] ?? Infinity;
    while (size(surfaceClass) >= limit) {
      const oldest = visibleByClass.get(surfaceClass).values().next().value;
      if (!oldest) break;
      hide(oldest);
    }

    // Modal exclusion: opening a modal suspends inline + badge.
    if (surfaceClass === 'modal' || surfaceClass === 'modalCanvas') {
      suspend('inline');
      suspend('badge');
    }

    surfaces.set(id, { ...opts, element });
    if (element && !suspendedClasses.has(surfaceClass)) {
      element.hidden = false;
      if (typeof opts.content === 'string' && surfaceClass !== 'toast') element.textContent = opts.content;
    }
    trackVisible(id, surfaceClass);

    if (opts.autoDismissMs && opts.autoDismissMs > 0) {
      const timer = setTimeout(() => hide(id), opts.autoDismissMs);
      surfaces.get(id).timer = timer;
    }
  }

  function hide(id) {
    const entry = surfaces.get(id);
    if (!entry) return;
    if (entry.timer) clearTimeout(entry.timer);
    if (entry.element) {
      if (entry.surfaceClass === 'toast') entry.element.remove();
      else entry.element.hidden = true;
    }
    surfaces.delete(id);
    untrackVisible(id, entry.surfaceClass);

    // If this was the last modal, resume.
    if ((entry.surfaceClass === 'modal' || entry.surfaceClass === 'modalCanvas') &&
        size('modal') === 0 && size('modalCanvas') === 0) {
      resume('inline');
      resume('badge');
    }
  }

  function suspend(surfaceClass) {
    suspendedClasses.add(surfaceClass);
    for (const id of visibleByClass.get(surfaceClass) ?? []) {
      const entry = surfaces.get(id);
      if (entry?.element) entry.element.hidden = true;
    }
  }

  function resume(surfaceClass) {
    suspendedClasses.delete(surfaceClass);
    for (const id of visibleByClass.get(surfaceClass) ?? []) {
      const entry = surfaces.get(id);
      if (entry?.element) entry.element.hidden = false;
    }
  }

  return { show, hide, suspend, resume, size };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/statusSurfaceRegistry.js \
        fixthis-mcp/src/main/console/__tests__/statusSurfaceRegistry.test.html
git commit -m "feat(console): statusSurfaceRegistry coordinator with modal exclusion + stack limits"
```

---

### Task 18: Route the 8 existing surfaces through the registry

**Files:**
- Modify: `fixthis-mcp/src/main/console/staleness.js:45-60` (use registry)
- Modify: `fixthis-mcp/src/main/console/preview.js:210-260` (overlay + stale-notice + retry)
- Modify: `fixthis-mcp/src/main/console/connection.js:35-50` (stale badge)
- Modify: `fixthis-mcp/src/main/console/state.js:355-400` (global error → toast)
- Modify: `fixthis-mcp/src/main/console/main.js` (boundary sheet open/close)
- Modify: `fixthis-mcp/src/main/console/consoleApp.js` (instantiate singleton)

- [ ] **Step 1: Write the failing integration test**

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>surface routing smoke</title></head>
<body>
<div id="canvasBlockedOverlay" hidden></div>
<div id="canvasStaleNotice" hidden></div>
<div id="draftLockBar" hidden></div>
<div id="previewStaleBadge" hidden></div>
<div id="stalenessBanner" hidden></div>
<div id="sessionBoundarySheet" hidden></div>
<div id="toastContainer"></div>
<script src="../statusSurfaceRegistry.js"></script>
<script>
(function () {
  const failures = [];
  const r = createStatusSurfaceRegistry();
  window.__testRegistry = r;

  // Simulate disconnect path: overlay + stale notice both fire today; after this task,
  // overlay alone wins.
  r.show('canvasBlockedOverlay', {
    surfaceClass: 'modalCanvas', priority: 1,
    element: document.getElementById('canvasBlockedOverlay'),
    content: 'Device disconnected',
  });
  r.show('canvasStaleNotice', {
    surfaceClass: 'inline', priority: 3,
    element: document.getElementById('canvasStaleNotice'),
    content: 'stale',
  });
  if (!document.getElementById('canvasStaleNotice').hidden === false)
    failures.push('inline should be suspended when overlay shown');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails (or passes registration-only)**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/surfaceRoutingSmoke.test.html`

If PASS at this stage, the registry already enforces exclusion — the routing migration below ensures consumers actually call through it.

- [ ] **Step 3: Migrate each surface module**

In `consoleApp.js`, instantiate a single registry and expose to other modules via the existing port-injection mechanism:

```js
const statusSurfaceRegistry = createStatusSurfaceRegistry();
window.__statusSurfaceRegistry = statusSurfaceRegistry; // for tests
```

In each surface module, replace direct `.hidden` toggles with registry calls. Examples:

`staleness.js:45-60`:

```js
// Before (illustrative):
//   banner.hidden = false;
// After:
statusSurfaceRegistry.show('stalenessBanner', {
  surfaceClass: 'banner', priority: 2,
  element: banner, content: bannerText,
});
// Hide path:
statusSurfaceRegistry.hide('stalenessBanner');
```

`preview.js:210-260` — overlay + retry + canvas stale notice:

```js
// Show overlay:
statusSurfaceRegistry.show('canvasBlockedOverlay', {
  surfaceClass: 'modalCanvas', priority: 1,
  element: overlay, content: { headline, detail, retry: true },
});
// Hide:
statusSurfaceRegistry.hide('canvasBlockedOverlay');
```

`connection.js:35-50` — preview stale badge:

```js
if (stale && hasPreviewSurface) {
  statusSurfaceRegistry.show('previewStaleBadge', {
    surfaceClass: 'badge', priority: 3,
    element: previewStaleBadge, content: 'Connection paused — showing last preview',
  });
} else {
  statusSurfaceRegistry.hide('previewStaleBadge');
}
```

`state.js:355-400` — global error becomes a toast:

```js
function showError(message) {
  if (!message) {
    statusSurfaceRegistry.hide('global-error');
    return;
  }
  statusSurfaceRegistry.show('global-error-' + Date.now(), {
    surfaceClass: 'toast', priority: 1, content: message, autoDismissMs: 6000,
  });
}
```

`main.js` — boundary sheet open/close uses `surfaceClass: 'modal'`:

```js
function openBoundarySheet() {
  statusSurfaceRegistry.show('sessionBoundarySheet', {
    surfaceClass: 'modal', priority: 1,
    element: document.getElementById('sessionBoundarySheet'),
  });
}
function closeBoundarySheet() { statusSurfaceRegistry.hide('sessionBoundarySheet'); }
```

Every existing `.hidden = ...` write on the eight surfaces is replaced. Grep first to find them:

```
grep -nE '(stalenessBanner|canvasBlockedOverlay|canvasStaleNotice|draftLockBar|previewStaleBadge|sessionBoundarySheet|deviceStatus|#error)\.hidden' fixthis-mcp/src/main/console/
```

Migrate every match. Tests catch regressions.

- [ ] **Step 4: Rebundle + full test sweep**

```
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/
./gradlew :fixthis-mcp:test
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/staleness.js \
        fixthis-mcp/src/main/console/preview.js \
        fixthis-mcp/src/main/console/connection.js \
        fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/console/consoleApp.js \
        fixthis-mcp/src/main/console/__tests__/surfaceRoutingSmoke.test.html
git commit -m "feat(console): route 8 status surfaces through statusSurfaceRegistry"
```

---

### Task 19: Z-index tokens + `check-surface-zindex.sh` lint

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/styles.css` (add tokens + update surface selectors)
- Create: `scripts/check-surface-zindex.sh`
- Modify: `CONTRIBUTING.md` (one-line addition to local PR checks)

- [ ] **Step 1: Write the failing lint test**

Create `scripts/check-surface-zindex.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
STYLES="fixthis-mcp/src/main/resources/console/styles.css"
# Find surface-class selectors followed (within 30 lines) by a raw numeric z-index.
violations=$(awk '
  /\.(staleness|canvas-blocked|session-boundary|preview-stale|toast-container|status-pill|device-state|draft-lock|canvas-stale)/ { ctx=NR; sel=$0 }
  /^\s*z-index:\s*[0-9]/ {
    if (ctx > 0 && NR - ctx < 30) print FILENAME ":" NR ":" sel " -> " $0
  }
' "$STYLES" || true)
if [ -n "$violations" ]; then
  echo "Surface z-index must use var(--z-surface-*):"
  echo "$violations"
  exit 1
fi
echo "OK: all surface z-index values use tokens."
```

Make executable: `chmod +x scripts/check-surface-zindex.sh`

- [ ] **Step 2: Run test to verify it fails**

Run: `bash scripts/check-surface-zindex.sh`
Expected: FAIL — current `styles.css` has raw `z-index: 50;`, `z-index: 100;` etc. on surface selectors.

- [ ] **Step 3: Add tokens + update selectors**

In `styles.css`, near the existing `:root { ... }` block:

```css
:root {
  /* status surface stacking — token, not raw value */
  --z-surface-pill:     5;
  --z-surface-badge:   10;
  --z-surface-banner:  30;
  --z-surface-overlay: 50;
  --z-surface-modal:  100;
  --z-surface-toast:  200;
}
```

Find each surface-class selector and replace its raw `z-index` with the token:

```css
.staleness-banner            { z-index: var(--z-surface-banner); }
.canvas-blocked              { z-index: var(--z-surface-overlay); }
.session-boundary-sheet      { z-index: var(--z-surface-modal); }
.preview-stale-badge         { z-index: var(--z-surface-badge); }
.canvas-stale                { z-index: var(--z-surface-badge); }
.toast-container             { z-index: var(--z-surface-toast); }
.status-pill                 { z-index: var(--z-surface-pill); }
.draft-lock-bar              { z-index: var(--z-surface-badge); }
```

(Adjust only the surface selectors; layout-level z-indexes 1–4 remain raw.)

Append one line to `CONTRIBUTING.md` under "Required local checks":

```markdown
- `bash scripts/check-surface-zindex.sh` — surface z-index uses tokens.
```

- [ ] **Step 4: Run lint to verify it passes**

```
bash scripts/check-surface-zindex.sh
```

Expected: `OK: all surface z-index values use tokens.`

Rebundle + full sweep (no behaviour change expected, but verify CSS still parses):

```
node scripts/build-console-assets.mjs
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/
```

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/styles.css \
        scripts/check-surface-zindex.sh \
        CONTRIBUTING.md
git commit -m "feat(console): tokenize surface z-index; add check-surface-zindex.sh lint"
```

---

### Task 20: Disconnect / reconnect choreography rewrite + final harness

**Files:**
- Modify: `fixthis-mcp/src/main/console/connection.js` (disconnect/reconnect paths)
- Create: `fixthis-mcp/src/main/console/__tests__/disconnectChoreography.test.html`
- Create: `docs/guides/manual-test-checklist.md` (if absent) — 5-item smoke

- [ ] **Step 1: Write the failing test**

```html
<!doctype html>
<html><head><meta charset="utf-8"><title>disconnect choreography</title></head>
<body>
<div id="canvasBlockedOverlay" hidden></div>
<div id="canvasStaleNotice" hidden></div>
<div id="draftLockBar" hidden></div>
<div id="previewStaleBadge" hidden></div>
<div id="stalenessBanner" hidden></div>
<div id="toastContainer"></div>
<script src="../statusSurfaceRegistry.js"></script>
<script src="../connection.js"></script>
<script>
(function () {
  const failures = [];
  const r = createStatusSurfaceRegistry();
  window.__statusSurfaceRegistry = r;

  // Simulate disconnect with a dirty draft.
  applyDisconnect({ hasDirtyDraft: true });

  if (document.getElementById('canvasBlockedOverlay').hidden) failures.push('overlay must be visible on disconnect');
  if (!document.getElementById('canvasStaleNotice').hidden)  failures.push('canvasStaleNotice must be suspended');
  if (!document.getElementById('previewStaleBadge').hidden)  failures.push('previewStaleBadge must be suspended');
  if (document.getElementById('stalenessBanner').hidden)     failures.push('stalenessBanner must be visible when dirty draft');

  // Reconnect.
  applyReconnect({ targetStale: false });
  if (!document.getElementById('canvasBlockedOverlay').hidden) failures.push('overlay must be hidden after reconnect');

  document.body.dataset.testResult = failures.length === 0 ? 'pass' : 'fail';
  document.body.dataset.testFailures = JSON.stringify(failures);
})();
</script>
</body></html>
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/disconnectChoreography.test.html`
Expected: FAIL — `applyDisconnect is not defined` or assertion failures on coordination.

- [ ] **Step 3: Implement choreography in `connection.js`**

Add to `connection.js`:

```js
function applyDisconnect({ hasDirtyDraft }) {
  const r = window.__statusSurfaceRegistry;
  if (!r) return;
  r.show('canvasBlockedOverlay', {
    surfaceClass: 'modalCanvas', priority: 1,
    element: document.getElementById('canvasBlockedOverlay'),
    content: { headline: 'Device disconnected', detail: 'Reconnecting…', retry: true },
  });
  if (hasDirtyDraft) {
    r.show('stalenessBanner', {
      surfaceClass: 'banner', priority: 2,
      element: document.getElementById('stalenessBanner'),
      content: '⚠ 1 unsaved draft preserved locally',
    });
  } else {
    r.hide('stalenessBanner');
  }
  // Inline surfaces (canvasStaleNotice, draftLockBar) are suspended automatically
  // by the registry's modal-class exclusion rule.
}

function applyReconnect({ targetStale }) {
  const r = window.__statusSurfaceRegistry;
  if (!r) return;
  r.hide('canvasBlockedOverlay');
  if (targetStale) {
    r.show('canvasStaleNotice', {
      surfaceClass: 'inline', priority: 3,
      element: document.getElementById('canvasStaleNotice'),
      content: 'Selection may be stale; tap to revalidate.',
    });
  } else {
    r.show('previewStaleBadge', {
      surfaceClass: 'badge', priority: 3,
      element: document.getElementById('previewStaleBadge'),
      content: 'Connection restored · refreshing preview',
      autoDismissMs: 2000,
    });
  }
}
```

Wire `applyDisconnect` / `applyReconnect` into the connection FSM transitions (find the existing disconnect/reconnect handlers — search `connection.js` for `userConnectionState`).

- [ ] **Step 4: Run test + full sweep**

```
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/disconnectChoreography.test.html
node scripts/build-console-assets.mjs
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/
bash scripts/check-surface-zindex.sh
./gradlew :fixthis-mcp:test
```

Expected: all green.

Create `docs/guides/manual-test-checklist.md`:

```markdown
# Manual Test Checklist — Console Draft Lifecycle & Affordance Clarity

Run these by hand before declaring the plan done.

1. **Pending discard on session switch.** Open console, click an element on the
   canvas, **do not type a comment**, click another session. Expect: toast
   "🔄 Switched · 1 empty annotation discarded"; reload page; expect no
   recovery prompt.
2. **Draft beforeunload + recovery.** Click an element, type one character,
   press F5 / Cmd+R. Expect: native browser warning. Confirm leave; reload;
   expect Draft footer and the typed character restored.
3. **Disconnect single-surface.** Unplug device (or kill the server).
   Expect: only `canvasBlockedOverlay` + topbar pill change. No stacking of
   banner + lock bar + badge. If a Draft existed, also expect the
   `stalenessBanner` "1 unsaved draft preserved locally".
4. **Reconnect.** Plug device back in. Expect: overlay dismisses, single
   "Connection restored" badge fires briefly (2s), other surfaces resume
   normal behaviour.
5. **Back navigation per state.** From the annotation editor:
   - Pending: click "← All annotations" — toast + back to list.
   - Draft: click — boundary dialog with `Save and back / Discard / Cancel`.
   - Saved (clean): click — back to list immediately.

If any of these fail, the plan is not done.
```

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/connection.js \
        fixthis-mcp/src/main/console/__tests__/disconnectChoreography.test.html \
        docs/guides/manual-test-checklist.md
git commit -m "feat(console): disconnect/reconnect choreography via registry + manual checklist"
```

---

## Done

All 20 tasks complete. Verify:

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
node scripts/run-console-tests.mjs fixthis-mcp/src/main/console/__tests__/
bash scripts/check-surface-zindex.sh
./gradlew :fixthis-mcp:test
./gradlew check
git diff --check
```

Expected: all green. Then walk the manual checklist in
`docs/guides/manual-test-checklist.md`.
