# Console UX Silent-Path Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the remaining user-action paths in the FixThis console that silently no-op or fall into the void, surfacing a toast/status in each case.

**Architecture:** Vanilla JS console — every user-initiated action that bails early must either disable its trigger or call `showStatus(...)` so the user gets feedback. Tests are static regex assertions against console source files following the existing pattern under `scripts/*-test.mjs`.

**Tech Stack:** Vanilla JS (esbuild bundled), Node test runner via `console-test-loader.mjs`, regex-based source assertions.

---

## Background

A second-pass UX audit (2026-05-17) verified three concrete dead-ends:

1. **`startDraftAnnotationFlow` context-mismatch leak** — `annotations.js:364` returns silently when the user changes context (device/session) while a preview is being fetched. The follow-up check at line 373 only toasts the `!state.preview` branch; the pure context-mismatch path stays silent.
2. **`enterAnnotateMode` pending-recovery block** — `history.js:187` (`if (!requirePendingRecoveryChoiceBeforeSessionChange()) return;`) silently swallows the 'A' key / Start-annotating click / canvas trigger when pending recovery is unresolved.
3. **Cmd+Z / Cmd+Shift+Z dispatch into a no-op reducer** — `main.js:51-60` dispatches `UNDO_CLICKED` / `REDO_CLICKED`, but `domain/consoleReducer.js:71-74` returns `{ state, effects: [] }` and `application/consoleEffects.js` has no handler. The keyboard shortcuts currently do absolutely nothing. The in-toast Undo button at `main.js:134` invokes `undo()` directly but also gives no feedback when the stack is empty (`result.applied === false && reason !== 'context_mismatch'`).

Three tasks address these. Each follows the TDD shape used by the prior console-UX plan (`2026-05-17-console-ux-dead-end-fixes.md`): write a static regex test asserting the new feedback exists, watch it fail, edit the source, watch it pass, commit.

## File Structure

**Modified production files:**
- `fixthis-mcp/src/main/console/annotations.js` — add context-mismatch toast in `startDraftAnnotationFlow`.
- `fixthis-mcp/src/main/console/history.js` — add pending-recovery toast in `enterAnnotateMode`.
- `fixthis-mcp/src/main/console/main.js` — replace `store.dispatch({ type: 'UNDO_CLICKED' })` / `REDO_CLICKED` with direct `undo()` / `redo()` invocations + empty-stack toast, and add empty-stack toast to the in-toast Undo button.

**New test files (under `scripts/`):**
- `scripts/startDraftContextMismatchToast-test.mjs`
- `scripts/enterAnnotateModeBlockToast-test.mjs`
- `scripts/undoRedoKeyboardWiring-test.mjs`

**Registry update:**
- `scripts/console-tests.json` — register new tests under appropriate groups.

---

## Task 1: Toast when preview-fetch context drifts

**Files:**
- Modify: `fixthis-mcp/src/main/console/annotations.js:364`
- Create: `scripts/startDraftContextMismatchToast-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write the failing test**

Create `scripts/startDraftContextMismatchToast-test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const annotations = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');

test('startDraftAnnotationFlow toasts when preview context drifts mid-fetch', () => {
  // After `previewUseCases.request()` returns, a context check happens at line ~364.
  // If the context drifted, the flow returns silently; this test enforces a toast
  // so the user knows why nothing happened.
  const block = annotations.match(/preview = await previewUseCases\.request\(\);[\s\S]{0,400}/);
  assert.ok(block, 'expected to find the previewUseCases.request() block');
  assert.match(
    block[0],
    /if \(!previewContextStillCurrent\(previewContext\)\) \{[\s\S]{0,200}showStatus\(/,
    'expected showStatus inside the context-mismatch guard after previewUseCases.request()'
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test scripts/startDraftContextMismatchToast-test.mjs`
Expected: FAIL with assertion error (`if (!previewContextStillCurrent(previewContext)) return;` has no `{ ... }` body / no `showStatus`).

- [ ] **Step 3: Modify `annotations.js:362-364`**

Replace:

```javascript
                if (previewUseCases.getState().inFlight || !preview) {
                  preview = await previewUseCases.request();
                  if (!previewContextStillCurrent(previewContext)) return;
```

with:

```javascript
                if (previewUseCases.getState().inFlight || !preview) {
                  preview = await previewUseCases.request();
                  if (!previewContextStillCurrent(previewContext)) {
                    showStatus('Annotation start cancelled because the device or session changed.', { variant: 'warn', durationMs: 3500 });
                    return;
                  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test scripts/startDraftContextMismatchToast-test.mjs`
Expected: PASS.

- [ ] **Step 5: Register the test in `scripts/console-tests.json`**

In the `canonical` array (alphabetic-by-recently-added at the tail), append:

```json
    "scripts/startDraftContextMismatchToast-test.mjs"
```

so the array ends with the new entry before the closing bracket.

- [ ] **Step 6: Run the full canonical group**

Run: `npm run console:test:all`
Expected: all green (including the new test).

- [ ] **Step 7: Rebundle console assets**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: bundle ≤ 205 KiB raw / 52 KiB gzip; check passes.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/console/annotations.js scripts/startDraftContextMismatchToast-test.mjs scripts/console-tests.json fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "fix(console): toast when preview context drifts mid-fetch"
```

---

## Task 2: Toast when pending recovery blocks annotate-mode entry

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js:186-188`
- Create: `scripts/enterAnnotateModeBlockToast-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write the failing test**

Create `scripts/enterAnnotateModeBlockToast-test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');

test('enterAnnotateMode toasts when pending recovery blocks the session change', () => {
  const block = history.match(/async function enterAnnotateMode\(\)[\s\S]{0,400}/);
  assert.ok(block, 'expected to find enterAnnotateMode function');
  assert.match(
    block[0],
    /if \(!requirePendingRecoveryChoiceBeforeSessionChange\(\)\) \{[\s\S]{0,200}showStatus\(/,
    'expected showStatus inside the pending-recovery guard'
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test scripts/enterAnnotateModeBlockToast-test.mjs`
Expected: FAIL (the guard is currently `if (!...) return;` with no braces / no toast).

- [ ] **Step 3: Modify `history.js:186-188`**

Replace:

```javascript
            async function enterAnnotateMode() {
              if (!requirePendingRecoveryChoiceBeforeSessionChange()) return;
              await ensureSessionForAnnotating();
```

with:

```javascript
            async function enterAnnotateMode() {
              if (!requirePendingRecoveryChoiceBeforeSessionChange()) {
                showStatus('Resolve the recovered draft first, then try Start annotating again.', { variant: 'warn', durationMs: 3500 });
                return;
              }
              await ensureSessionForAnnotating();
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test scripts/enterAnnotateModeBlockToast-test.mjs`
Expected: PASS.

- [ ] **Step 5: Register the test in `scripts/console-tests.json`**

Append to the `session` array (since this gates session entry):

```json
    "scripts/enterAnnotateModeBlockToast-test.mjs"
```

- [ ] **Step 6: Run the full suite**

Run: `npm run console:test:all`
Expected: all green.

- [ ] **Step 7: Rebundle**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: budgets OK, check passes.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/console/history.js scripts/enterAnnotateModeBlockToast-test.mjs scripts/console-tests.json fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "fix(console): toast when pending recovery blocks annotate-mode entry"
```

---

## Task 3: Wire Cmd+Z / Cmd+Shift+Z to real undo/redo with empty-stack feedback

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js:51-60` (replace store dispatch with real invocation)
- Modify: `fixthis-mcp/src/main/console/main.js:134-142` (add empty-stack toast to in-toast Undo button)
- Create: `scripts/undoRedoKeyboardWiring-test.mjs`
- Modify: `scripts/console-tests.json`

**Context for the engineer:** `main.js:54-58` currently does:

```javascript
              if (matchesUndo(e, active)) {
                e.preventDefault();
                store.dispatch({ type: 'UNDO_CLICKED' });
              } else if (matchesRedo(e, active)) {
                e.preventDefault();
                store.dispatch({ type: 'REDO_CLICKED' });
              }
```

But `domain/consoleReducer.js:71-74` handles those actions with `{ state, effects: [] }` and `application/consoleEffects.js` has no matching effect kind. So Cmd+Z and Cmd+Shift+Z fall into the void. The existing in-toast Undo button at `main.js:134` already calls `undo(undoRedoHistory, { items: draftItemList() }, draftFlow()?.context ?? null)` directly — we mirror that pattern for keyboard shortcuts.

`undoRedo.js:120-142` defines `undo(history, state, context)` and `redo(history, state, context)`, each returning `{ applied: true }` or `{ applied: false, reason: 'empty' | 'context_mismatch' }`. The `state` argument is mutated in place via `applyInverse` / `applyForward`.

- [ ] **Step 1: Write the failing test**

Create `scripts/undoRedoKeyboardWiring-test.mjs`:

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const main = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');

test('Cmd+Z keyboard handler calls undo() directly, not a no-op dispatch', () => {
  // The old code dispatched UNDO_CLICKED which is a no-op in the reducer.
  // Ensure the keyboard handler now invokes the real undo() helper.
  assert.doesNotMatch(main, /matchesUndo\(e, active\)[\s\S]{0,80}store\.dispatch\(\{ type: 'UNDO_CLICKED' \}\)/);
  assert.match(main, /matchesUndo\(e, active\)[\s\S]{0,200}undo\(undoRedoHistory/);
});

test('Cmd+Shift+Z keyboard handler calls redo() directly, not a no-op dispatch', () => {
  assert.doesNotMatch(main, /matchesRedo\(e, active\)[\s\S]{0,80}store\.dispatch\(\{ type: 'REDO_CLICKED' \}\)/);
  assert.match(main, /matchesRedo\(e, active\)[\s\S]{0,200}redo\(undoRedoHistory/);
});

test('Keyboard undo shows a toast when the stack is empty', () => {
  // The keyboard wiring must surface a "Nothing to undo" toast when result.applied is false
  // (other than context_mismatch, which has its own showError).
  const block = main.match(/matchesUndo\(e, active\)[\s\S]{0,500}/);
  assert.ok(block, 'expected to find the Cmd+Z handler block');
  assert.match(block[0], /Nothing to undo/);
});

test('Keyboard redo shows a toast when the stack is empty', () => {
  const block = main.match(/matchesRedo\(e, active\)[\s\S]{0,500}/);
  assert.ok(block, 'expected to find the Cmd+Shift+Z handler block');
  assert.match(block[0], /Nothing to redo/);
});

test('In-toast Undo button shows a toast when the undo stack is empty', () => {
  // The 5-second deletion-undo toast (main.js:121+) currently removes itself silently
  // if result.applied is false and reason isn't context_mismatch. Ensure feedback exists.
  const block = main.match(/fixthis-undo-toast[\s\S]{0,1500}/);
  assert.ok(block, 'expected to find the in-toast Undo block');
  assert.match(
    block[0],
    /result\.applied[\s\S]{0,400}Nothing to undo|Nothing to undo[\s\S]{0,400}toast\.remove/,
    'expected a Nothing-to-undo toast in the in-toast Undo path'
  );
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test scripts/undoRedoKeyboardWiring-test.mjs`
Expected: FAIL on multiple assertions — `store.dispatch({ type: 'UNDO_CLICKED' })` still present; no `undo(undoRedoHistory` call from the kbd handler; no "Nothing to undo" string anywhere.

- [ ] **Step 3: Modify `main.js:51-60` (keyboard handler)**

Replace:

```javascript
            window.addEventListener('keydown', (e) => {
              const active = document.activeElement;
              if (matchesUndo(e, active)) {
                e.preventDefault();
                store.dispatch({ type: 'UNDO_CLICKED' });
              } else if (matchesRedo(e, active)) {
                e.preventDefault();
                store.dispatch({ type: 'REDO_CLICKED' });
              }
            });
```

with:

```javascript
            window.addEventListener('keydown', (e) => {
              const active = document.activeElement;
              if (matchesUndo(e, active)) {
                e.preventDefault();
                const result = undo(undoRedoHistory, { items: draftItemList() }, draftFlow()?.context ?? null);
                if (result.reason === 'context_mismatch') {
                  showError('Undo history was cleared because the annotation session changed.');
                } else if (!result.applied) {
                  showStatus('Nothing to undo.', { variant: 'info', durationMs: 2000 });
                } else {
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                }
              } else if (matchesRedo(e, active)) {
                e.preventDefault();
                const result = redo(undoRedoHistory, { items: draftItemList() }, draftFlow()?.context ?? null);
                if (result.reason === 'context_mismatch') {
                  showError('Redo history was cleared because the annotation session changed.');
                } else if (!result.applied) {
                  showStatus('Nothing to redo.', { variant: 'info', durationMs: 2000 });
                } else {
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                }
              }
            });
```

- [ ] **Step 4: Modify the in-toast Undo button (`main.js:133-143`)**

Locate (inside `showUndoToast`):

```javascript
              btn.addEventListener('click', () => {
                const result = undo(undoRedoHistory, { items: draftItemList() }, draftFlow()?.context ?? null);
                if (result.reason === 'context_mismatch') showError('Undo history was cleared because the annotation session changed.');
                if (result.applied) {
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                }
                toast.remove();
              });
```

Replace with:

```javascript
              btn.addEventListener('click', () => {
                const result = undo(undoRedoHistory, { items: draftItemList() }, draftFlow()?.context ?? null);
                if (result.reason === 'context_mismatch') showError('Undo history was cleared because the annotation session changed.');
                if (result.applied) {
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                } else if (result.reason !== 'context_mismatch') {
                  showStatus('Nothing to undo.', { variant: 'info', durationMs: 2000 });
                }
                toast.remove();
              });
```

- [ ] **Step 5: Run test to verify it passes**

Run: `node --test scripts/undoRedoKeyboardWiring-test.mjs`
Expected: all five tests pass.

- [ ] **Step 6: Register the test in `scripts/console-tests.json`**

Append to the `undo` array:

```json
    "scripts/undoRedoKeyboardWiring-test.mjs"
```

- [ ] **Step 7: Run the full suite**

Run: `npm run console:test:all`
Expected: all green. If any existing test asserts the old `UNDO_CLICKED` / `REDO_CLICKED` dispatch (likely `consoleCanonicalRuntimeContract-test.mjs`), inspect, and:
- If the test asserts the dispatch was reached → adjust the test to either remove that assertion (it was a placeholder) or assert the new behavior. Show the diff to the reviewer before changing.

- [ ] **Step 8: Rebundle**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check`
Expected: budgets OK.

- [ ] **Step 9: Commit**

```bash
git add fixthis-mcp/src/main/console/main.js scripts/undoRedoKeyboardWiring-test.mjs scripts/console-tests.json fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "fix(console): wire Cmd+Z / Cmd+Shift+Z to real undo/redo with empty-stack feedback"
```

If `consoleCanonicalRuntimeContract-test.mjs` was also modified, include it in the same commit and explain in the commit body why the old placeholder assertion is replaced.

---

## Final verification (after Task 3)

- [ ] **Run all console tests**

Run: `npm run console:test:all`
Expected: all green; no skipped tests.

- [ ] **Confirm bundle freshness**

Run: `node scripts/build-console-assets.mjs --check`
Expected: PASS.

- [ ] **Manual smoke (optional, requires emulator + console open)**

1. Launch the console, open the app, capture a preview.
2. Press Cmd+Z with no draft items → toast says "Nothing to undo".
3. Add an annotation, delete it via the 5-second toast → toast says "Annotation deleted". Press the Undo button after the deletion → annotation restored.
4. Click another button after the toast is gone (no longer applicable).
5. While a preview-fetch is in-flight, switch sessions → toast says "Annotation start cancelled because the device or session changed."
6. With pending recovery showing, press 'A' → toast says "Resolve the recovered draft first..."

