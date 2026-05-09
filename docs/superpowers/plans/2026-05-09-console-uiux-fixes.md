# Console UI/UX Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 10 high/medium/low UI/UX defects in the FixThis web console covering data loss bugs, silent failures, accessibility gaps, and DOM inconsistencies.

**Architecture:** Vanilla JS single-page console. Source files in `fixthis-mcp/src/main/console/*.js`, bundled via `node scripts/build-console-assets.mjs` into `fixthis-mcp/src/main/resources/console/app.js`. CSS in `styles.css`. No test framework for JS — verification is manual browser testing after each task.

**Tech Stack:** Vanilla JS (ES2020), vanilla CSS, Kotlin/JUnit (server tests only), Node.js bundler script

---

## Companion design

See `docs/superpowers/specs/2026-05-09-console-uiux-fixes-design.md` for the full problem statement, current/desired behavior per issue, constraints, and acceptance criteria.

## Conventions

- Edit only the files listed under each task's **Files** section. Source-of-truth JS lives in `fixthis-mcp/src/main/console/`; never hand-edit `fixthis-mcp/src/main/resources/console/app.js` (it is generated).
- After every JS source edit, run:
  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```
  The first command regenerates `app.js`; the second is the gate. The `--check` invocation exits `0` silently when sources and `app.js` agree (treat that as the "In sync" pass signal). On drift it exits non-zero with `Generated console app.js is out of date.` printed to stderr — that means you forgot to rebuild. Each task's bundler step below restates this expectation as "silent success (exit 0)".
- Browser verification assumes the dev server is running and the console is reachable at `http://127.0.0.1:60006/`. Refresh the tab between tasks.
- Commit messages use the existing repo style (lowercase scope, imperative mood, body optional).

---

## Task 1 — Remove blocking `window.alert()` from prompt actions (H2)

**Files:**

- `fixthis-mcp/src/main/console/prompt.js`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/console/prompt.js` and locate `ensurePromptAnnotationsAvailable` (~line 9).
- [ ] Replace the function body so it sets the inline error and throws without firing `window.alert`. The full updated function is:

  ```js
  function ensurePromptAnnotationsAvailable() {
    const annotations = currentPromptAnnotations();
    if (annotations.length) return annotations;
    const message = promptUnavailableMessage();
    error.textContent = message;
    throw new Error(message);
  }
  ```

- [ ] Confirm there are no other `window.alert(` callers in any `fixthis-mcp/src/main/console/*.js` file:

  ```bash
  grep -n "window.alert" fixthis-mcp/src/main/console/*.js
  ```

  Expected: no output.

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success (exit 0).

- [ ] **Browser verification:** Open `http://127.0.0.1:60006/`, ensure the active session has zero annotations with comments. The "Send Agent" button is dimmed but still clickable at this point (Task 2 will make it truly disabled). Click the dimmed button. The `#error` paragraph below the inspector must show "Add a comment to at least one annotation before copying or sending it." (or whichever variant matches the state). **No `window.alert` modal must appear.**

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/console/prompt.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "fix(console): drop window.alert in prompt action guard (H2)"
  ```

---

## Task 2 — Make prompt buttons truly disabled when unavailable (H3)

**Files:**

- `fixthis-mcp/src/main/console/annotations.js`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/console/annotations.js` and locate `updateComposerState` (~line 138).
- [ ] Replace the function body so the two prompt buttons set `.disabled` based on availability. Drop the `dataset.unavailable` writes and the `is-disabled` toggles for these buttons. The full updated function is:

  ```js
  function updateComposerState() {
    const hasPromptAnnotations = currentPromptAnnotations().length > 0;
    const promptDisabled = !hasPromptAnnotations || promptActionInFlight;
    copyPromptButton.disabled = promptDisabled;
    sendAgentButton.disabled = promptDisabled;
    cancelAddFlowButton.disabled = !addItemsFlow;
    addItemButton.hidden = true;
    addItemButton.disabled = true;
    annotateToolButton.disabled = addItemsFlowStarting;
    selectToolButton.setAttribute('aria-pressed', String(toolMode === 'select'));
    annotateToolButton.setAttribute('aria-pressed', String(toolMode === 'annotate'));
    toolStatus.innerHTML = toolMode === 'annotate'
      ? '<span class="ts-hint"><span class="ts-dot"></span><span>Click a widget — or drag to draw a region</span></span>'
      : '<span class="ts-meta">' +
          '<span class="ts-dot-label"><span class="ts-dot"></span>' + toolbarOpenCount() + ' open</span>' +
          '<span class="ts-dot-label"><span class="ts-dot resolved"></span>' + toolbarResolvedCount() + ' resolved</span>' +
        '</span>';
    const item = focusedPendingSelectionSummary();
    selectionSummary.textContent = currentSelection
      ? currentSelection.label + ' - ' + formatBounds(currentSelection.bounds)
      : (item
        ? 'Focused #' + (focusedPendingItemIndex + 1) + ' - ' + formatBounds(item.bounds)
        : (toolMode === 'annotate' ? 'Click a component or drag a region to create an annotation.' : 'No annotation selected.'));
  }
  ```

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:**
  1. Refresh `http://127.0.0.1:60006/`. With no commented annotations, "Copy Prompt" and "Send Agent" both look dim. In DevTools console run `document.getElementById('copyPromptButton').disabled` — expect `true`. Repeat for `sendAgentButton`.
  2. Try Tab-navigating into either button — keyboard focus must skip them (browsers do not focus `disabled` buttons).
  3. Add a commented annotation and confirm both buttons become enabled (`disabled === false`) and clickable.

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "fix(console): make prompt buttons truly disabled when unavailable (H3)"
  ```

---

## Task 3 — Add reusable `showSuccess` helper and clear it on errors (L6)

**Files:**

- `fixthis-mcp/src/main/console/state.js`
- `fixthis-mcp/src/main/console/main.js`
- `fixthis-mcp/src/main/resources/console/styles.css`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/resources/console/styles.css`. Locate the `.error` rule (~line 1087). Append a sibling rule immediately after it:

  ```css
  .error.status-success { color: var(--accent); }
  ```

- [ ] Open `fixthis-mcp/src/main/console/state.js`. At the bottom of the file (after `function countLabel(...)`, line 146) append:

  ```js
  let successClearTimeout = null;

  function showSuccess(message, durationMs = 2000) {
    if (successClearTimeout) {
      clearTimeout(successClearTimeout);
      successClearTimeout = null;
    }
    error.textContent = message;
    error.classList.add('status-success');
    successClearTimeout = setTimeout(() => {
      error.textContent = '';
      error.classList.remove('status-success');
      successClearTimeout = null;
    }, durationMs);
  }

  function clearSuccessStatus() {
    if (successClearTimeout) {
      clearTimeout(successClearTimeout);
      successClearTimeout = null;
    }
    error.classList.remove('status-success');
  }
  ```

- [ ] Open `fixthis-mcp/src/main/console/main.js` and locate `showError` (~line 56). Update it so any pending success state is cleared first. The full updated function is:

  ```js
  function showError(cause) {
    clearSuccessStatus();
    error.textContent = friendlyErrorMessage(cause && cause.message ? cause.message : cause);
  }
  ```

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:** In DevTools, run `showSuccess('Test success', 4000)`. The `#error` paragraph shows "Test success" in lime; after ~4 s it clears. Then run `showSuccess('Another', 10000)` followed immediately by `showError(new Error('boom'))` — the bar must turn red and read "boom" with no green left over. Run `document.getElementById('error').classList.contains('status-success')` — expect `false`.

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/console/state.js fixthis-mcp/src/main/console/main.js fixthis-mcp/src/main/resources/console/styles.css fixthis-mcp/src/main/resources/console/app.js
  git commit -m "feat(console): add showSuccess helper with success/error class hygiene (L6)"
  ```

---

## Task 4 — Add "Copied ✓" feedback to Copy Prompt (M4)

**Files:**

- `fixthis-mcp/src/main/console/prompt.js`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/console/prompt.js` and locate `copyPrompt` (~line 289). Update it so the button label briefly flips to "Copied ✓" on success. The button's label lives in its second `<span>` child (see `index.html` line 42); we manipulate `.textContent` of that span. The full updated function is:

  ```js
  async function copyPrompt() {
    error.textContent = '';
    if (promptActionInFlight) return;
    ensurePromptAnnotationsAvailable();
    promptActionInFlight = true;
    updateComposerState();
    const labelSpan = copyPromptButton.querySelector('span:not(.button-icon)');
    const originalLabel = labelSpan ? labelSpan.textContent : null;
    let copied = false;
    try {
      if (addItemsFlow) {
        await persistPendingFeedbackItems({ onlyWrittenComments: true });
      }
      await copyTextToClipboard(currentAnnotationsPrompt());
      copied = true;
    } finally {
      promptActionInFlight = false;
      updateComposerState();
      if (copied && labelSpan) {
        labelSpan.textContent = 'Copied ✓';
        setTimeout(() => {
          if (labelSpan.textContent === 'Copied ✓') {
            labelSpan.textContent = originalLabel;
          }
        }, 1500);
      }
    }
  }
  ```

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:** Refresh the console. Add at least one annotation and write a comment so the prompt buttons enable. Click "Copy Prompt". The button label must flip to "Copied ✓"; after ~1.5 s it reverts to "Copy Prompt". Paste into a text editor to confirm the clipboard payload. Repeat the click — the second click must also produce the brief "Copied ✓" feedback.

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/console/prompt.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "feat(console): show transient Copied confirmation on prompt copy (M4)"
  ```

---

## Task 5 — Add "Sent to agent ✓" feedback to Send Agent (M3)

**Files:**

- `fixthis-mcp/src/main/console/prompt.js`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/console/prompt.js` and locate `sendAgentPrompt` (~line 261). Update it so the success path calls `showSuccess`. The full updated function is:

  ```js
  async function sendAgentPrompt() {
    error.textContent = '';
    if (promptActionInFlight) return;
    ensurePromptAnnotationsAvailable();
    promptActionInFlight = true;
    updateComposerState();
    let sent = false;
    try {
      if (addItemsFlow) {
        await persistPendingFeedbackItems({ onlyWrittenComments: true });
      }
      const prompt = currentAnnotationsPrompt();
      state.session = await requestJson('/api/agent-handoffs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: prompt })
      });
      comment.value = '';
      resetAnnotationComposerState();
      invalidatePreviewContext();
      await refreshSessions();
      render();
      startLivePreviewPolling();
      sent = true;
    } finally {
      promptActionInFlight = false;
      updateComposerState();
      if (sent) {
        showSuccess('Sent to agent ✓', 3000);
      }
    }
  }
  ```

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:** Add at least one commented annotation and click "Send Agent". After the network round-trip the inspector clears AND the `#error` paragraph shows green "Sent to agent ✓"; after ~3 s the paragraph clears and reverts to neutral. Force a server error (e.g. stop the server before clicking) and confirm a subsequent error renders red, not green.

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/console/prompt.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "feat(console): confirm successful agent handoff inline (M3)"
  ```

---

## Task 6 — Flush comment text before pending annotation focus changes (H4)

**Files:**

- `fixthis-mcp/src/main/console/annotations.js`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/console/annotations.js`. Just above `function createAnnotationFromSelection(selection)` (~line 343), insert the helper:

  ```js
  function flushFocusedPendingComment() {
    if (focusedPendingItemIndex == null) return;
    const item = pendingFeedbackItems[focusedPendingItemIndex];
    if (!item) return;
    item.comment = comment.value;
  }
  ```

- [ ] Update `createAnnotationFromSelection` (~line 343) to flush before mutating the pending list. The full updated function is:

  ```js
  function createAnnotationFromSelection(selection) {
    if (!addItemsFlow) throw new Error('Switch to Annotate before selecting feedback.');
    if (!selection) throw new Error('Select a component or area first.');
    flushFocusedPendingComment();
    const annotation = {
      annotationId: 'local-' + annotationSequence++,
      targetType: selection.targetType,
      nodeUid: selection.nodeUid,
      bounds: selection.bounds,
      label: selection.label,
      severity: 'med',
      status: 'open',
      comment: ''
    };
    pendingFeedbackItems.push(annotation);
    currentSelection = null;
    hoveredAnnotationTarget = null;
    focusedPendingItemIndex = pendingFeedbackItems.length - 1;
    focusedSavedItemId = null;
    focusedSavedSessionId = null;
    toolMode = 'annotate';
    comment.value = '';
    renderPreviewOnly();
    renderInspectorRegion();
    renderCurrentSessionList();
  }
  ```

- [ ] Update `focusPendingFeedbackItem` (~line 382) to flush before reassigning the focused index. The full updated function is:

  ```js
  function focusPendingFeedbackItem(index) {
    flushFocusedPendingComment();
    focusedPendingItemIndex = index;
    focusedSavedItemId = null;
    focusedSavedSessionId = null;
    currentSelection = null;
    toolMode = 'select';
    comment.value = pendingFeedbackItems[index]?.comment || '';
    renderPreviewOnly();
    renderInspectorRegion();
  }
  ```

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:**
  1. Refresh `http://127.0.0.1:60006/`. Click "Annotate", click any component to create annotation #1.
  2. Type "first comment" into the inspector textarea.
  3. Without clicking elsewhere first, drag-select a new region on the canvas to create annotation #2. The new annotation gets focus.
  4. In the inspector list, click annotation #1. The textarea must show "first comment".
  5. Repeat with three or more annotations to confirm round-trip preservation across multiple switches.

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "fix(console): flush pending comment before annotation focus change (H4)"
  ```

---

## Task 7 — Persist saved annotation comments on blur (H5)

**Files:**

- `fixthis-mcp/src/main/console/rendering.js`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/console/rendering.js` and locate `renderSavedAnnotationDetail` (~line 280). The function is large; only the `commentInput` listener block changes. Locate the existing block:

  ```js
  commentInput.addEventListener('input', event => {
    item.comment = event.target.value;
    updateComposerState();
  });
  commentInput.addEventListener('change', () => {
    persistSavedEvidenceItem(item, editSessionId).catch(showError);
  });
  ```

  Add a `blur` listener immediately after the `change` listener so the trio reads:

  ```js
  commentInput.addEventListener('input', event => {
    item.comment = event.target.value;
    updateComposerState();
  });
  commentInput.addEventListener('change', () => {
    persistSavedEvidenceItem(item, editSessionId).catch(showError);
  });
  commentInput.addEventListener('blur', () => {
    persistSavedEvidenceItem(item, editSessionId).catch(showError);
  });
  ```

  The rest of `renderSavedAnnotationDetail` is unchanged. (No other listener inside the function depends on this ordering.)

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:**
  1. Refresh `http://127.0.0.1:60006/` and open a session with saved annotations.
  2. Click a saved annotation to open its editor. Type new text into the comment textarea.
  3. While the cursor is still in the textarea, wait for the next polling cycle (≤2 s — observe the Network tab for repeating GETs).
  4. The polling re-render will tear down the textarea. Because `blur` fires during teardown, the persistence PUT goes out (visible in the Network tab as `PUT /api/items/{id}`).
  5. Re-open the same annotation. The textarea must show the typed text. Reload the page to confirm server-side persistence.

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/console/rendering.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "fix(console): persist saved annotation comment on blur (H5)"
  ```

---

## Task 8 — Suppress repeated heartbeat errors in inline status (H7)

**Files:**

- `fixthis-mcp/src/main/console/connection.js`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/console/connection.js`. Just above `async function sendBridgeHeartbeat()` (~line 161), insert the dedup helper plus its state:

  ```js
  let lastHeartbeatError = null;

  function handleHeartbeatError(cause) {
    const message = cause && cause.message ? cause.message : String(cause);
    if (message === lastHeartbeatError) return;
    lastHeartbeatError = message;
    showError(cause);
  }
  ```

- [ ] Update `sendBridgeHeartbeat` so it clears `lastHeartbeatError` after a successful poll. The full updated function is:

  ```js
  async function sendBridgeHeartbeat() {
    if (!state.session || !state.selectedDeviceSerial) return;
    await refreshConnection();
    lastHeartbeatError = null;
  }
  ```

- [ ] Update `startHeartbeatPolling` so both invocations route failures through the dedup helper. The full updated function is:

  ```js
  function startHeartbeatPolling() {
    stopHeartbeatPolling();
    sendBridgeHeartbeat().catch(handleHeartbeatError);
    heartbeatTimer = setInterval(() => {
      if (state.selectedDeviceSerial) sendBridgeHeartbeat().catch(handleHeartbeatError);
    }, HeartbeatIntervalMs);
  }
  ```

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:**
  1. With the console connected to a device, kill the bridge or disable Wi-Fi.
  2. Wait for the next heartbeat (~2 s). The `#error` paragraph shows a friendly message (e.g. "Connection paused. Your work is saved.").
  3. In DevTools, set `document.getElementById('error').textContent = 'sentinel'`. Wait through several heartbeat cycles (≥6 s).
  4. The text "sentinel" must persist — repeated identical heartbeat failures must not overwrite it.
  5. Restore the connection. After the next successful heartbeat, kill it again with a *different* failure mode (e.g. stop the server entirely); the new distinct error message must appear.

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/console/connection.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "fix(console): suppress repeated heartbeat errors in inline status (H7)"
  ```

---

## Task 9 — Remove duplicate `#selectionOverlay` from index.html (M12)

**Files:**

- `fixthis-mcp/src/main/resources/console/index.html`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/resources/console/index.html`. Locate the `<div id="snapshot" class="snapshot-stage">` block (~lines 103–106). Delete only the line `<div id="selectionOverlay" class="selection-overlay"></div>` so the block reads:

  ```html
  <div id="snapshot" class="snapshot-stage">
    <div class="empty-stage">Refresh the live preview to begin.</div>
  </div>
  ```

  (Task 10 will further refine the empty-stage copy.)

- [ ] No bundler step required — only HTML changed. Confirm the bundle is still in sync (in case a previous task left it stale):

  ```bash
  node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:**
  1. Hard-refresh `http://127.0.0.1:60006/` (Cmd+Shift+R).
  2. In DevTools console: `document.querySelectorAll('#selectionOverlay').length`. Before any preview loads expect `0`.
  3. Trigger a preview (click a session, ensure the screenshot renders). Re-run the query — expect exactly `1`.
  4. Switch to Annotate mode and create an annotation; confirm the selection box still draws correctly inside `#snapshotFrame`.

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/resources/console/index.html
  git commit -m "fix(console): remove duplicate #selectionOverlay markup (M12)"
  ```

---

## Task 10 — Improve empty preview stage messaging (M10)

**Files:**

- `fixthis-mcp/src/main/resources/console/index.html`
- `fixthis-mcp/src/main/console/rendering.js`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/resources/console/index.html`. Update the static empty-stage copy inside `#snapshot` so the block reads:

  ```html
  <div id="snapshot" class="snapshot-stage">
    <div class="empty-stage">Connect a device to get started.</div>
  </div>
  ```

- [ ] Open `fixthis-mcp/src/main/console/rendering.js` and locate `renderPreviewRegion` (~line 437). Update the `!hasScreenshot` branch to pick the right copy for the three states. The full updated function is:

  ```js
  function renderPreviewRegion() {
    const screen = latestScreen();
    const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
    const mode = addItemsFlow ? 'frozen' : (state.preview ? 'live' : (screen ? 'frozen' : 'idle'));
    snapshot.dataset.toolMode = toolMode;
    if (!hasScreenshot) {
      const emptyMessage = screen
        ? 'No screenshot artifact for this preview.'
        : (state.session
          ? 'Waiting for first capture from device…'
          : 'Connect a device to get started.');
      snapshot.innerHTML = '<div class="empty-stage">' + emptyMessage + '</div>';
      updateComposerState();
      return;
    }
    const frame = ensurePreviewFrame();
    frame.dataset.mode = mode;
    const image = document.getElementById('snapshotImage');
    const src = screenImageUrl(screen);
    if (image.getAttribute('src') !== src) {
      image.setAttribute('src', src);
    }
    const hintSlot = document.getElementById('annotateHintSlot');
    let hint = document.getElementById('annotateHint');
    if (toolMode === 'annotate') {
      if (!hint) {
        hint = document.createElement('div');
        hint.id = 'annotateHint';
        hint.className = 'annotate-hint';
        hintSlot.appendChild(hint);
      }
      hint.textContent = 'Annotate mode';
    } else if (hint) {
      hint.remove();
    }
    renderSelectionOverlay();
  }
  ```

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:**
  1. Hard-refresh with no active session. Empty stage reads "Connect a device to get started."
  2. Connect a device but, before the first capture, ensure the preview is empty (in DevTools run `state.preview = null; state.session.screens = []; renderPreviewRegion();`). Empty stage reads "Waiting for first capture from device…".
  3. Force a session whose screen has no `desktopFullPath` (in DevTools mutate the screen object directly: `latestScreen().screenshot = {};` then `renderPreviewRegion();`). Empty stage reads "No screenshot artifact for this preview." (preserved branch).

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/resources/console/index.html fixthis-mcp/src/main/console/rendering.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "fix(console): clarify empty preview stage copy (M10)"
  ```

---

## Task 11 — Add `aria-pressed` to severity/status segmented buttons (M7)

**Files:**

- `fixthis-mcp/src/main/console/rendering.js`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/console/rendering.js` and locate `renderAnnotationDetail` (~line 135). Replace the inner HTML build so each segmented `<button>` carries `aria-pressed`. The full updated function is:

  ```js
  function renderAnnotationDetail(item, index) {
    const severity = annotationSeverity(item);
    const status = annotationStatus(item);
    pendingItems.innerHTML =
      '<div class="annotation-detail">' +
        '<button type="button" class="annotation-back" data-back-annotations>← All annotations</button>' +
        '<div class="annotation-field">' +
          '<label for="annotationLabelInput">Label</label>' +
          '<input id="annotationLabelInput" class="annotation-input" value="' + escapeHtml(annotationTitle(item, index)) + '">' +
        '</div>' +
        '<div class="annotation-field">' +
          '<label>Severity</label>' +
          '<div class="annotation-segmented" role="group" aria-label="Severity">' +
            ['high', 'med', 'low'].map(value =>
              '<button type="button" class="' + (severity === value ? 'active' : '') + '" data-set-severity="' + value + '"' +
                ' aria-pressed="' + (severity === value ? 'true' : 'false') + '"' +
                (severity === value ? ' style="background:' + severityColor(value) + '; color: var(--bg-0);"' : '') + '>' +
                escapeHtml(value === 'med' ? 'Med' : value) +
              '</button>'
            ).join('') +
          '</div>' +
        '</div>' +
        '<div class="annotation-field">' +
          '<label for="annotationCommentInput">Comment</label>' +
          '<textarea id="annotationCommentInput" class="annotation-textarea">' + escapeHtmlValue(item.comment) + '</textarea>' +
        '</div>' +
        '<div class="annotation-field">' +
          '<label>Status</label>' +
          '<div class="annotation-segmented" role="group" aria-label="Status">' +
            ['open', 'in-progress', 'resolved'].map(value =>
              '<button type="button" class="' + (status === value ? 'active' : '') + '" data-set-status="' + value + '"' +
                ' aria-pressed="' + (status === value ? 'true' : 'false') + '">' +
                escapeHtml(statusLabel(value)) +
              '</button>'
            ).join('') +
          '</div>' +
        '</div>' +
        '<div class="annotation-actions">' +
          '<button type="button" class="annotation-danger" data-delete-current>Delete</button>' +
          '<button type="button" class="annotation-done" data-back-annotations>Done</button>' +
        '</div>' +
      '</div>';
    const labelInput = document.getElementById('annotationLabelInput');
    const commentInput = document.getElementById('annotationCommentInput');
    labelInput.addEventListener('input', event => {
      item.label = event.target.value;
      updateComposerState();
      renderPreviewOnly();
    });
    commentInput.addEventListener('input', event => {
      item.comment = event.target.value;
      updateComposerState();
    });
    pendingItems.querySelectorAll('[data-set-severity]').forEach(button => {
      button.addEventListener('click', () => {
        item.severity = button.dataset.setSeverity;
        renderInspectorRegion();
      });
    });
    pendingItems.querySelectorAll('[data-set-status]').forEach(button => {
      button.addEventListener('click', () => {
        item.status = button.dataset.setStatus;
        renderPreviewOnly();
        renderInspectorRegion();
        renderCurrentSessionList();
      });
    });
    pendingItems.querySelectorAll('[data-back-annotations]').forEach(button => {
      button.addEventListener('click', () => {
        focusedPendingItemIndex = null;
        renderPreviewOnly();
        renderInspectorRegion();
      });
    });
    pendingItems.querySelector('[data-delete-current]').addEventListener('click', () => {
      deletePendingFeedbackItem(index);
    });
    commentInput.focus();
  }
  ```

- [ ] Locate `renderSavedAnnotationDetail` (~line 280). Apply the same `aria-pressed` treatment to its severity and status buttons. The full updated function is:

  ```js
  function renderSavedAnnotationDetail(item, index) {
    const severity = annotationSeverity(item);
    const status = annotationStatus(item);
    const editSessionId = focusedSavedSessionId || state.session?.sessionId || null;
    draftItems.innerHTML =
      '<div class="annotation-detail">' +
        '<button type="button" class="annotation-back" data-back-saved-annotations>← All annotations</button>' +
        '<div class="annotation-field">' +
          '<label for="annotationLabelInput">Label</label>' +
          '<input id="annotationLabelInput" class="annotation-input" value="' + escapeHtml(targetLabel(item)) + '">' +
        '</div>' +
        '<div class="annotation-field">' +
          '<label>Severity</label>' +
          '<div class="annotation-segmented" role="group" aria-label="Severity">' +
            ['high', 'med', 'low'].map(value =>
              '<button type="button" class="' + (severity === value ? 'active' : '') + '" data-set-severity="' + value + '"' +
                ' aria-pressed="' + (severity === value ? 'true' : 'false') + '"' +
                (severity === value ? ' style="background:' + severityColor(value) + '; color: var(--bg-0);"' : '') + '>' +
                escapeHtml(value === 'med' ? 'Med' : value) +
              '</button>'
            ).join('') +
          '</div>' +
        '</div>' +
        '<div class="annotation-field">' +
          '<label for="annotationCommentInput">Comment</label>' +
          '<textarea id="annotationCommentInput" class="annotation-textarea">' + escapeHtmlValue(item.comment) + '</textarea>' +
        '</div>' +
        '<div class="annotation-field">' +
          '<label>Status</label>' +
          '<div class="annotation-segmented" role="group" aria-label="Status">' +
            ['open', 'in-progress', 'resolved'].map(value =>
              '<button type="button" class="' + (status === value ? 'active' : '') + '" data-set-status="' + value + '"' +
                ' aria-pressed="' + (status === value ? 'true' : 'false') + '">' +
                escapeHtml(statusLabel(value)) +
              '</button>'
            ).join('') +
          '</div>' +
        '</div>' +
        '<div class="annotation-actions">' +
          '<button type="button" class="annotation-danger" data-delete-current>Delete</button>' +
          '<button type="button" class="annotation-done" data-back-saved-annotations>Done</button>' +
        '</div>' +
      '</div>';
    const labelInput = draftItems.querySelector('#annotationLabelInput');
    const commentInput = draftItems.querySelector('#annotationCommentInput');
    labelInput.addEventListener('input', event => {
      item.label = event.target.value;
      updateComposerState();
      renderPreviewOnly();
    });
    labelInput.addEventListener('change', () => {
      persistSavedEvidenceItem(item, editSessionId).catch(showError);
    });
    commentInput.addEventListener('input', event => {
      item.comment = event.target.value;
      updateComposerState();
    });
    commentInput.addEventListener('change', () => {
      persistSavedEvidenceItem(item, editSessionId).catch(showError);
    });
    commentInput.addEventListener('blur', () => {
      persistSavedEvidenceItem(item, editSessionId).catch(showError);
    });
    draftItems.querySelectorAll('[data-set-severity]').forEach(button => {
      button.addEventListener('click', () => {
        item.severity = button.dataset.setSeverity;
        persistSavedEvidenceItem(item, editSessionId)
          .then(() => renderInspectorRegion())
          .catch(showError);
      });
    });
    draftItems.querySelectorAll('[data-set-status]').forEach(button => {
      button.addEventListener('click', () => {
        item.status = button.dataset.setStatus;
        persistSavedEvidenceItem(item, editSessionId)
          .then(() => {
            renderPreviewOnly();
            renderInspectorRegion();
          })
          .catch(showError);
      });
    });
    draftItems.querySelectorAll('[data-back-saved-annotations]').forEach(button => {
      button.addEventListener('click', () => {
        persistSavedEvidenceItem(item, editSessionId)
          .then(() => {
            focusedSavedItemId = null;
            focusedSavedSessionId = null;
            renderPreviewOnly();
            renderInspectorRegion();
          })
          .catch(showError);
      });
    });
    draftItems.querySelector('[data-delete-current]').addEventListener('click', () => {
      deleteSavedEvidenceItem(item.itemId, editSessionId).catch(showError);
    });
    commentInput.focus();
  }
  ```

  Note: this updated body retains the `blur` handler added in Task 7.

- [ ] Rebuild and verify the bundle:

  ```bash
  node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:**
  1. Refresh and open any annotation in the inspector (pending or saved).
  2. In DevTools, run `document.querySelectorAll('.annotation-segmented button[aria-pressed="true"]').length` — expect `2` (one severity, one status).
  3. Click a different severity/status; the `aria-pressed="true"` must move to the newly active button. Re-run the query — count remains `2`.
  4. With VoiceOver (Cmd+F5 on macOS) navigate to a severity button; the announcement must include "selected" or "pressed" depending on the screen reader.

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/console/rendering.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "fix(console): announce severity/status state via aria-pressed (M7)"
  ```

---

## Task 12 — Add keyboard focus rings to canvas tool buttons (L15)

**Files:**

- `fixthis-mcp/src/main/resources/console/styles.css`

**Steps:**

- [ ] Open `fixthis-mcp/src/main/resources/console/styles.css`. Append the following block at the very end of the file (do not interleave with existing rules):

  ```css
  .tool-button:focus-visible,
  .zoom-button:focus-visible,
  .annotation-back:focus-visible,
  .annotation-segmented button:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  ```

- [ ] No bundler step required — only CSS changed. Re-verify the JS bundle is in sync:

  ```bash
  node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success.

- [ ] **Browser verification:**
  1. Hard-refresh the page.
  2. Tab through the canvas toolbar starting from the device picker. Each `.tool-button` (Select, Annotate), `.zoom-button` (− and +), and segmented severity/status option must show a 2 px lime ring with 2 px offset when keyboard-focused.
  3. Open an annotation; Tab to the back-arrow button (`← All annotations`) and confirm the ring appears.
  4. Mouse-clicking the same buttons must NOT show the ring (`:focus-visible` excludes pointer focus by spec).

- [ ] Commit:

  ```bash
  git add fixthis-mcp/src/main/resources/console/styles.css
  git commit -m "fix(console): add focus-visible rings to canvas tool buttons (L15)"
  ```

---

## Task 13 — Run full test suite and final smoke-test sweep

**Files:** none modified.

**Steps:**

- [ ] Confirm the JS bundle is in sync after all prior tasks:

  ```bash
  node scripts/build-console-assets.mjs --check
  ```

  Expected: silent success (exit 0).

- [ ] Run the full Kotlin test suite as a regression check:

  ```bash
  ./gradlew :fixthis-mcp:test
  ```

  Expected: BUILD SUCCESSFUL with all server tests green.

- [ ] **Browser smoke-test checklist** (single session, executed top-to-bottom):
  - [ ] H2/H3: With no commented annotations, "Copy Prompt" and "Send Agent" are dim, `disabled === true`, and Tab skips them. No `window.alert` ever appears.
  - [ ] H4: Create annotation #1, type "alpha", drag-create annotation #2, click annotation #1 in the inspector list — textarea shows "alpha".
  - [ ] H5: Open a saved annotation, type into its comment, wait through one polling tick. Re-open the annotation — the typed text is present.
  - [ ] H7: With the bridge offline, multiple heartbeat cycles (≥6 s of repeated identical failures) leave a manually-set `#error` value untouched. A genuinely new error message still appears.
  - [ ] M3: With one commented annotation, click "Send Agent" — green "Sent to agent ✓" appears for ~3 s.
  - [ ] M4: With one commented annotation, click "Copy Prompt" — button label flips to "Copied ✓" for ~1.5 s; clipboard contains the prompt.
  - [ ] M7: `document.querySelectorAll('.annotation-segmented button[aria-pressed="true"]').length === 2` whenever an annotation is open.
  - [ ] M10: Empty stage messages match the three states described in Task 10.
  - [ ] M12: `document.querySelectorAll('#selectionOverlay').length` is `0` when no preview, `1` when a preview is loaded — never `2`.
  - [ ] L6: Trigger M3, then immediately trigger an error — `#error` flips to red without lingering green.
  - [ ] L15: Tab through canvas toolbar; every tool/zoom/segmented/back button shows a lime focus ring when keyboard-focused only.

- [ ] If any checklist item fails, return to the relevant task, repair, rebuild the bundle, and rerun `node scripts/build-console-assets.mjs --check`. Re-commit only the affected files.

- [ ] Commit only if there are uncommitted bookkeeping changes (e.g. an `app.js` that drifted from sources):

  ```bash
  git status
  ```

  If the working tree is clean, no final commit is needed.
