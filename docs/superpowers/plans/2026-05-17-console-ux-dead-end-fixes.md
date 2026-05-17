# Console UX Dead-End Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate six confirmed UX dead-end paths in the FixThis console where user clicks produce no visible response, including the zombie session-delete dialog where buttons silently no-op when a session has zero items.

**Architecture:** Each task is a localized fix in `fixthis-mcp/src/main/console/`. Tests use Node's built-in test runner and the `console-test-loader.mjs` symbol loader pattern. After each task, run `node scripts/build-console-assets.mjs` (or set `FIXTHIS_BUNDLE_REPRODUCIBLE=1`) to rebundle JS, run the affected test files plus `npm run console:test:all`, then commit. Coverage targets the user-visible click → response contract, not internal implementation.

**Tech Stack:** Vanilla JS bundled by esbuild, CSS, Node test runner.

---

## Task 1: Repair zombie session-delete dialog (findings #1 and #7)

The trash button in the history drawer renders `renderBoundaryDialog('sessionDelete', …)` *without binding click handlers* and simultaneously fires `deleteHistorySession(sessionId)`. For sessions with pending drafts this accidentally works because `resolvePendingBeforeBoundary` re-renders the same dialog through `promptBoundaryDialogChoice` (which does wire clicks). For sessions with zero items, `resolvePendingBeforeBoundary` returns `'continue'` immediately, the backend session is silently deleted, and the dialog sits on screen with non-responsive buttons until the user clicks outside.

The fix: remove the broken pre-render in `historySessionRow.js`; have `deleteHistorySession` always confirm via `promptBoundaryDialogChoice`. For sessions with pending drafts, continue routing through `resolvePendingBeforeBoundary` so the existing save/discard branch still works.

**Files:**
- Modify: `fixthis-mcp/src/main/console/historySessionRow.js:43-53`
- Modify: `fixthis-mcp/src/main/console/history.js:468-496` (deleteHistorySession)
- Modify: `scripts/sessionTrashIcon-test.mjs` (update obsolete assertion)
- Create: `scripts/sessionDeleteConfirmation-test.mjs` (new behavior test)

- [ ] **Step 1: Write a failing test for the new confirmation contract**

Create `scripts/sessionDeleteConfirmation-test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
const row = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/historySessionRow.js'), 'utf8');

test('history row delete handler does not pre-render the boundary dialog', () => {
  assert.doesNotMatch(row, /renderBoundaryDialog\(['"]sessionDelete['"]/);
});

test('deleteHistorySession confirms via promptBoundaryDialogChoice before mutating', () => {
  assert.match(history, /promptBoundaryDialogChoice\(['"]sessionDelete['"]/);
});

test('deleteHistorySession aborts when the confirmation returns cancel', () => {
  assert.match(history, /if \(choice === ['"]cancel['"]\) return/);
});
```

- [ ] **Step 2: Run the new test to verify it fails**

Run: `node scripts/sessionDeleteConfirmation-test.mjs`
Expected: FAIL on all three asserts because the current code still calls `renderBoundaryDialog('sessionDelete'` and `deleteHistorySession` never calls `promptBoundaryDialogChoice`.

- [ ] **Step 3: Remove the broken pre-render from `historySessionRow.js`**

Replace the click handler block (around lines 43-53) so it only stops propagation and forwards to `deleteHistorySession`. The new contents:

```javascript
  root.querySelectorAll('[data-delete-session-id]').forEach(button => {
    button.addEventListener('click', event => {
      event.stopPropagation();
      deleteHistorySession(button.dataset.deleteSessionId, {
        sessionLabel: button.dataset.deleteSessionLabel,
        annotationCount: Number(button.dataset.deleteSessionAnnotations || 0),
        screenCount: Number(button.dataset.deleteSessionScreens || 0),
      }).catch(showError);
    });
  });
```

- [ ] **Step 4: Update `deleteHistorySession` to always confirm**

Modify `fixthis-mcp/src/main/console/history.js`. Change the signature to accept an options object, and add an upfront confirmation step. Replace the current first ~5 lines of the function body with:

```javascript
            async function deleteHistorySession(sessionId, options = {}) {
              error.textContent = '';
              if (!sessionId) return;
              if (sessionNavigationInFlight) return;
              const choice = await promptBoundaryDialogChoice('sessionDelete', {
                currentSessionName: options.sessionLabel || 'this session',
                annotationCount: options.annotationCount ?? 0,
                screenCount: options.screenCount ?? 0,
              });
              if (choice === 'cancel') return;
              const hasDraftForThisSession = draftWorkspace?.context?.sessionId === sessionId
                && draftItemList().length > 0;
              if (hasDraftForThisSession) {
                if (await resolvePendingBeforeBoundary('delete-session', sessionId) !== 'continue') return;
              }
```

Keep the rest of the function (the `isDisplayedSession`/`bumpSessionMutationGeneration`/`/api/session/close` block) unchanged.

- [ ] **Step 5: Update the existing `sessionTrashIcon-test.mjs` to match the new contract**

The test at line ~18 asserts `renderBoundaryDialog('sessionDelete'` is present in `historySessionRow.js`. Change it to assert the dialog is opened from `history.js` instead:

```javascript
test('session delete click forwards to deleteHistorySession', () => {
  assert.match(historySessionRow, /deleteHistorySession\(button\.dataset\.deleteSessionId/);
  assert.match(historySessionRow, /event\.stopPropagation\(\);/);
});
```

- [ ] **Step 6: Run the contract tests**

Run: `node scripts/sessionDeleteConfirmation-test.mjs && node scripts/sessionTrashIcon-test.mjs`
Expected: both PASS.

- [ ] **Step 7: Rebundle and run the full console test suite**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check && npm run console:test:all`
Expected: bundle is current, 306+ tests pass (count grows by 3 from the new test file).

- [ ] **Step 8: Add `scripts/sessionDeleteConfirmation-test.mjs` to `scripts/console-tests.json`**

Append the new test path under the `"canonical"` group so `npm run console:test:all` discovers it.

- [ ] **Step 9: Commit**

```bash
git add fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/console/historySessionRow.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  scripts/sessionDeleteConfirmation-test.mjs \
  scripts/sessionTrashIcon-test.mjs \
  scripts/console-tests.json
git commit -m "fix(console): wire session-delete confirmation dialog correctly"
```

---

## Task 2: Add user feedback for silent returns in capture / annotate flows (finding #4)

Several async entry points return silently when state preconditions fail:

- `connection.js` `captureScreen()` returns silently when `!state.session`.
- `history.js` `enterAnnotateMode()` returns silently when `!requirePendingRecoveryChoiceBeforeSessionChange()` (always true today, so effectively dead — kept for now since Task 4 reworks that gate).
- `annotations.js` `startDraftAnnotationFlow()` returns silently when `previewContextStillCurrent` flips or `state.preview` is null.

Surface a brief status toast so the user knows their click was received and why nothing visible happened.

**Files:**
- Modify: `fixthis-mcp/src/main/console/connection.js` (captureScreen)
- Modify: `fixthis-mcp/src/main/console/annotations.js` (startDraftAnnotationFlow)
- Create: `scripts/silentReturnFeedback-test.mjs`

- [ ] **Step 1: Write the failing test**

Create `scripts/silentReturnFeedback-test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const connection = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/connection.js'), 'utf8');
const annotations = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');

test('captureScreen tells the user when there is no active session', () => {
  // The function returns early when state.session is null — that branch must
  // emit a status toast before returning so the click is acknowledged.
  assert.match(connection, /if \(!state\.session\)[\s\S]{0,200}showStatus\(/);
});

test('startDraftAnnotationFlow tells the user when no preview is available', () => {
  // The early-return branch in startDraftAnnotationFlow when state.preview is
  // missing must surface a toast.
  assert.match(annotations, /Open the app first to capture a screenshot/);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/silentReturnFeedback-test.mjs`
Expected: FAIL.

- [ ] **Step 3: Add a toast to `captureScreen`**

In `fixthis-mcp/src/main/console/connection.js`, locate the early return `if (!state.session) return;` inside `captureScreen()` (around line 318) and replace with:

```javascript
              if (!state.session) {
                showStatus('Connect to a session before capturing a screen.', { variant: 'warn', durationMs: 3000 });
                return;
              }
```

- [ ] **Step 4: Add a toast in `startDraftAnnotationFlow`**

In `fixthis-mcp/src/main/console/annotations.js`, replace the silent `return;` at line ~374 (where `!state.preview` causes the function to abort) with:

```javascript
                if (!previewContextStillCurrent(previewContext) || !state.preview) {
                  showStatus('Open the app first to capture a screenshot, then try again.', { variant: 'warn', durationMs: 3500 });
                  return;
                }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `node scripts/silentReturnFeedback-test.mjs`
Expected: PASS.

- [ ] **Step 6: Rebundle and run the full suite**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && npm run console:test:all`
Expected: all green.

- [ ] **Step 7: Add `scripts/silentReturnFeedback-test.mjs` to `scripts/console-tests.json`** (canonical group)

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/console/connection.js \
  fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  scripts/silentReturnFeedback-test.mjs \
  scripts/console-tests.json
git commit -m "fix(console): surface status toasts when capture/annotate preconditions fail"
```

---

## Task 3: Acknowledge disabled / no-op device dropdown changes (finding #5)

`selectDevice()` in `devices.js` silently bails when the chosen `<option>` is disabled or empty. Users see the picker bounce back with no explanation. Add a one-line toast for the disabled case.

**Files:**
- Modify: `fixthis-mcp/src/main/console/devices.js:135-150`
- Create: `scripts/deviceSelectFeedback-test.mjs`

- [ ] **Step 1: Write the failing test**

Create `scripts/deviceSelectFeedback-test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const devices = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/devices.js'), 'utf8');

test('selectDevice surfaces a toast when a disabled option is chosen', () => {
  // The bail branch must call showStatus before the early return.
  assert.match(devices, /option\.disabled[\s\S]{0,200}showStatus\(/);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/deviceSelectFeedback-test.mjs`
Expected: FAIL.

- [ ] **Step 3: Inspect the selectDevice early return and split disabled vs empty cases**

In `fixthis-mcp/src/main/console/devices.js`, find the line `if (!option || !option.value || option.disabled) return;` (around line 141). Replace with:

```javascript
              if (!option || !option.value) return;
              if (option.disabled) {
                showStatus('That device is not selectable right now. Reconnect or pick another one.', { variant: 'warn', durationMs: 3000 });
                return;
              }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node scripts/deviceSelectFeedback-test.mjs`
Expected: PASS.

- [ ] **Step 5: Rebundle and run the suite**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && npm run console:test:all`

- [ ] **Step 6: Add the new test file to `scripts/console-tests.json`** (availability group)

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/devices.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  scripts/deviceSelectFeedback-test.mjs \
  scripts/console-tests.json
git commit -m "fix(console): toast when the device picker change is ignored"
```

---

## Task 4: ~~Replace dead-code `requirePendingRecoveryChoiceBeforeSessionChange`~~ — DROPPED

**Reason for drop:** `scripts/pendingItemRecovery-test.mjs:236-238` asserts the current body (`renderPendingRecoveryBanner(); return true;`) and explicitly forbids the older blocking `showError(...)` behavior. The "always true" gate is an intentional design choice — the banner notifies the user but does not block session changes. This is *not* dead code. Skip this task.

*Original draft below preserved for reference.*

## ~~Task 4 (DROPPED): Replace dead-code `requirePendingRecoveryChoiceBeforeSessionChange` with an explicit check (finding #3)~~

`main.js:154` defines `requirePendingRecoveryChoiceBeforeSessionChange()` which calls `renderPendingRecoveryBanner()` and unconditionally returns `true`. Multiple call sites in `history.js` and `annotations.js` use it as if it were a guard ("if false, abort"). The function name says it gates session changes when there is unresolved recovery; the body never actually blocks anything. Restore the gate so unresolved recovery actually prevents silent session switches.

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js:154-157`
- Modify (verify call sites still behave): `fixthis-mcp/src/main/console/history.js:171-188`
- Create: `scripts/recoveryGate-test.mjs`

- [ ] **Step 1: Write the failing test**

Create `scripts/recoveryGate-test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const main = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');

test('requirePendingRecoveryChoiceBeforeSessionChange returns false when commented recovery exists', () => {
  // The gate must return false (i.e. block) when the user has unresolved
  // commented draft recovery; the previous body always returned true.
  assert.match(main, /function requirePendingRecoveryChoiceBeforeSessionChange[\s\S]{0,500}return !hasCommentedDraftItems\(pendingRecovery\)/);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/recoveryGate-test.mjs`
Expected: FAIL — current body returns true unconditionally.

- [ ] **Step 3: Fix the gate body**

In `fixthis-mcp/src/main/console/main.js`, replace the function body:

```javascript
            function requirePendingRecoveryChoiceBeforeSessionChange() {
              if (hasCommentedDraftItems(pendingRecovery)) {
                renderPendingRecoveryBanner();
                return !hasCommentedDraftItems(pendingRecovery);
              }
              return true;
            }
```

(The inner re-check covers the case where `renderPendingRecoveryBanner` clears recovery as a side effect; if recovery remains, the gate blocks.)

- [ ] **Step 4: Run test to verify it passes**

Run: `node scripts/recoveryGate-test.mjs`
Expected: PASS.

- [ ] **Step 5: Rebundle and run the full suite**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && npm run console:test:all`
Expected: all green (including pendingRecovery-related tests).

- [ ] **Step 6: Add test path to `scripts/console-tests.json`** (pending group)

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/main.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  scripts/recoveryGate-test.mjs \
  scripts/console-tests.json
git commit -m "fix(console): restore commented-recovery gate so session change blocks"
```

---

## Task 5: Surface background server-staleness and connection errors (finding #6)

Two background loops swallow errors today:
- `main.js:408` `checkServerStaleness().catch(() => {})`
- `preview.js:217` `refreshConnection().catch(() => {})`

Silent failure here means a stale console keeps serving cached state without warning. Route both errors to `console.warn` with a clear prefix so they surface in DevTools, and emit a one-time NotificationCenter banner the first time staleness is detected.

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js:405-410`
- Modify: `fixthis-mcp/src/main/console/preview.js:215-220`
- Create: `scripts/backgroundErrorVisibility-test.mjs`

- [ ] **Step 1: Write the failing test**

Create `scripts/backgroundErrorVisibility-test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const main = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
const preview = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');

test('checkServerStaleness errors are logged, not swallowed', () => {
  assert.doesNotMatch(main, /checkServerStaleness\(\)\.catch\(\(\) => \{\}\)/);
  assert.match(main, /checkServerStaleness\(\)\.catch\([^)]*console\.warn/);
});

test('refreshConnection errors are logged, not swallowed', () => {
  assert.doesNotMatch(preview, /refreshConnection\(\)\.catch\(\(\) => \{\}\)/);
  assert.match(preview, /refreshConnection\(\)\.catch\([^)]*console\.warn/);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/backgroundErrorVisibility-test.mjs`
Expected: FAIL on both asserts.

- [ ] **Step 3: Update `main.js` checkServerStaleness call site**

Replace the swallowed `.catch(() => {})` with:

```javascript
            checkServerStaleness().catch((err) => console.warn('[fixthis] server staleness check failed:', err));
```

- [ ] **Step 4: Update `preview.js` refreshConnection call site**

Replace the swallowed `.catch(() => {})` with:

```javascript
              refreshConnection().catch((err) => console.warn('[fixthis] background connection refresh failed:', err));
```

- [ ] **Step 5: Run test to verify it passes**

Run: `node scripts/backgroundErrorVisibility-test.mjs`
Expected: PASS.

- [ ] **Step 6: Rebundle and run the suite**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && npm run console:test:all`

- [ ] **Step 7: Add new test file to `scripts/console-tests.json`** (canonical group)

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/console/main.js \
  fixthis-mcp/src/main/console/preview.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  scripts/backgroundErrorVisibility-test.mjs \
  scripts/console-tests.json
git commit -m "fix(console): log background staleness/connection errors instead of swallowing"
```

---

## Task 6: Reconcile duplicate boundary-dialog render paths (finding #2)

`presentation/previewRegionView.js:119-144` defines `renderBoundaryFromModel(boundary)` which targets the same `#sessionBoundarySheet` element as `boundaryDialogVariants.js#renderBoundaryDialog`. It assigns handlers via `button.onclick = …` (overwriting any prior listener) and reads `boundary.actions[index]` without ensuring the array length matches the four button slots. If both paths render in close succession, whichever runs last owns the handlers — the other path's click contract is lost.

The minimal fix: have `renderBoundaryFromModel` clear all four buttons' handlers explicitly before rebinding only the slots backed by an action. Make it idempotent and document the contract.

**Files:**
- Modify: `fixthis-mcp/src/main/console/presentation/previewRegionView.js:119-144`
- Create: `scripts/boundaryRenderModelIsolation-test.mjs`

- [ ] **Step 1: Write the failing test**

Create `scripts/boundaryRenderModelIsolation-test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const view = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/presentation/previewRegionView.js'), 'utf8');

test('renderBoundaryFromModel clears all button handlers before rebinding', () => {
  // The function must null out button.onclick on every button before deciding
  // which slots get an action, so leftover handlers from a prior render do
  // not fire on a button that is now action-less.
  assert.match(view, /button\.onclick = null;[\s\S]{0,200}if \(action\)/);
});

test('renderBoundaryFromModel hides buttons whose slot has no action', () => {
  assert.match(view, /button\.hidden = !action/);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/boundaryRenderModelIsolation-test.mjs`
Expected: FAIL on the first assert (no `button.onclick = null;` reset today).

- [ ] **Step 3: Patch `renderBoundaryFromModel` to reset handlers explicitly**

In `presentation/previewRegionView.js`, change the forEach callback (around line 129) to:

```javascript
              root.querySelectorAll('[data-boundary-action]').forEach((button, index) => {
                const action = boundary.actions[index];
                button.onclick = null;
                button.hidden = !action;
                if (action) {
                  button.textContent = action.label;
                  button.onclick = () => {
                    if (typeof consoleStore !== 'undefined') consoleStore.dispatch({ type: action.type });
                  };
                }
              });
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node scripts/boundaryRenderModelIsolation-test.mjs`
Expected: PASS.

- [ ] **Step 5: Rebundle and run the suite**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && npm run console:test:all`

- [ ] **Step 6: Add the new test file to `scripts/console-tests.json`** (canonical group)

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/presentation/previewRegionView.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  scripts/boundaryRenderModelIsolation-test.mjs \
  scripts/console-tests.json
git commit -m "fix(console): clear boundary button handlers before rebinding"
```

---

## Final verification

After all six tasks land, run the canonical full sweep once more:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
npm run console:test:all
```

Expected: bundle is current, 306 + 6 ≈ 312 tests pass (count grows with each new test file).
