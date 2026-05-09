# Console UI/UX Fixes — Design Spec

**Date:** 2026-05-09
**Owner:** Console / FixThis MCP
**Status:** Approved for implementation
**Companion plan:** [`docs/superpowers/plans/2026-05-09-console-uiux-fixes.md`](../plans/2026-05-09-console-uiux-fixes.md)

---

## 1. Problem Statement

The FixThis feedback console has shipped a number of small but compounding UX regressions. They cluster into four families:

1. **Silent data loss.** Comment text typed into the inspector is dropped when the user switches focus (H4) or when an asynchronous render races their typing (H5). The console never tells them — the text simply disappears.
2. **Dishonest disabled states.** "Copy Prompt" and "Send Agent" look disabled (dimmed via a CSS class) but are still focusable and clickable; clicking pops a blocking `window.alert()` (H2, H3). Keyboard users are misled twice — once visually and once with a modal that interrupts their flow.
3. **Silent successes.** Clipboard copies and agent sends complete with no feedback (M3, M4); users paste blind to confirm a copy worked, or wonder whether their click registered before annotations vanished.
4. **Noise and small accessibility gaps.** Heartbeat errors clobber inline status every 2s on flaky networks (H7); duplicate `#selectionOverlay` IDs violate the DOM contract (M12); empty-stage copy refers to a "Refresh" button that no longer exists (M10); segmented severity/status buttons lack `aria-pressed` (M7); tool buttons have no `:focus-visible` ring (L15); and the `#error` element styles success and error identically (L6).

None of these alone are a release blocker, but together they erode trust in the console as a precision authoring tool. This spec batches the fixes so they ship together and can be verified in one browser sweep.

## 2. Goals & Non-Goals

### Goals

- Eliminate silent comment-data loss in both the pending and saved annotation editors.
- Make every disabled-looking control actually disabled (and not focusable as an action).
- Replace the blocking `window.alert()` with the existing inline `#error` element.
- Provide visible success feedback for both prompt actions.
- Stop heartbeat errors from overwriting actionable inline status text.
- Remove the duplicate `#selectionOverlay` and clarify empty-stage copy.
- Add `aria-pressed` to segmented buttons and `:focus-visible` rings to canvas tool buttons.

### Non-Goals

- Re-architecting state management or moving to a framework.
- Adding a JavaScript test framework. JS verification stays manual.
- Reworking the connection card, history list, or inspector layout.
- Adding new server endpoints. All Kotlin code is untouched.

## 3. Constraints

- **No framework.** Vanilla JS (ES2020) and vanilla CSS only.
- **Bundler step required.** Source files live in `fixthis-mcp/src/main/console/*.js`; the production bundle lives at `fixthis-mcp/src/main/resources/console/app.js`. After every JS source edit, run `node scripts/build-console-assets.mjs` and verify with `node scripts/build-console-assets.mjs --check`.
- **Hot reload via `--console-assets-dir`.** The dev server points at the resources directory, so refreshing the browser is sufficient between iterations once the bundle is rebuilt.
- **No JS tests.** The Kotlin/JUnit suite under `fixthis-mcp/src/test/kotlin/` covers server logic only; UI verification is manual.
- **Single-page console.** All HTML is in `fixthis-mcp/src/main/resources/console/index.html` and CSS in the sibling `styles.css`.

## 4. Issues — Current Behavior, Desired Behavior, Approach

For each issue we describe (a) what happens today, (b) what should happen instead, and (c) the implementation approach. The implementation plan carries the exact code; this spec keeps prose concise and shows code only where the change is non-obvious.

### H2 — `window.alert()` blocks on prompt action failure

- **File:** `fixthis-mcp/src/main/console/prompt.js`, `ensurePromptAnnotationsAvailable`.
- **Current:** When no annotations are ready to send, the function sets `error.textContent = msg` *and* fires `window.alert(msg)`. The alert is modal — it freezes the page and demands a click before the user can read the inline message they just received.
- **Desired:** Inline `#error` (which already has `role="status"` and `aria-live="polite"`) carries the message; no modal.
- **Approach:** Delete the `window.alert(message);` line. Keep the `error.textContent = message;` and the `throw` so callers still abort their async chains.

### H3 — Prompt buttons are visually disabled but still clickable

- **File:** `fixthis-mcp/src/main/console/annotations.js`, `updateComposerState`.
- **Current:** The function sets `dataset.unavailable` and toggles `is-disabled` on `copyPromptButton` and `sendAgentButton`, but only sets `.disabled = promptActionInFlight`. So when `hasPromptAnnotations` is false, the buttons look dim but remain focusable and clickable, falling into H2's alert path.
- **Desired:** Buttons are truly `.disabled = true` whenever they should appear unavailable; CSS `button:disabled` already provides the dim visual at `opacity: .4`.
- **Approach:** Set `copyPromptButton.disabled = !hasPromptAnnotations || promptActionInFlight` (and the same for `sendAgentButton`). Drop the `dataset.unavailable` writes and the `classList.toggle('is-disabled', …)` calls for these two buttons. The `is-disabled` CSS class itself is left in `styles.css` to avoid touching unrelated callers in this changeset; it simply won't be applied to these two buttons anymore.

### H4 — Comment text lost when switching annotations

- **File:** `fixthis-mcp/src/main/console/annotations.js`, `createAnnotationFromSelection` and `focusPendingFeedbackItem`.
- **Current:** Both functions reassign `focusedPendingItemIndex` and overwrite `comment.value` without first flushing the textarea's current value back to the previously focused pending item. If the user starts typing a comment for annotation #1 then drags to create annotation #2 (or clicks #3 in the list), the in-progress text is gone.
- **Desired:** Whatever the user has typed into the inspector textarea is committed to the previously focused pending item before the focus moves.
- **Approach:** Introduce a small helper near the other annotation helpers:

  ```js
  function flushFocusedPendingComment() {
    if (focusedPendingItemIndex == null) return;
    const item = pendingFeedbackItems[focusedPendingItemIndex];
    if (!item) return;
    item.comment = comment.value;
  }
  ```

  Call `flushFocusedPendingComment()` as the first statement of `createAnnotationFromSelection` (before mutating `pendingFeedbackItems` or `focusedPendingItemIndex`) and as the first statement of `focusPendingFeedbackItem`. Note: the existing `comment` `input` handler already flushes via `updateSelectedAnnotationComment`, but it requires the textarea to dispatch `input` *before* the click; rapid mouse movement between the canvas and the inspector list defeats that ordering. The explicit pre-flush is the durable fix.

### H5 — Saved annotation edits lost on re-render

- **File:** `fixthis-mcp/src/main/console/rendering.js`, `renderSavedAnnotationDetail`.
- **Current:** Persistence on the saved-annotation `commentInput` only fires on the `change` event (blur or Enter). The polling cycle (`render()` → `renderInspectorRegion()` → `renderSavedEvidenceGroups()` → `renderSavedAnnotationDetail()`) tears the textarea down and rebuilds it from `item.comment` (the last persisted value). Any unflushed in-flight edits are dropped without warning.
- **Desired:** Edits persist when focus leaves the textarea for *any* reason, including DOM teardown.
- **Approach:** Add a `blur` listener alongside the existing `change` listener:

  ```js
  commentInput.addEventListener('blur', () => {
    persistSavedEvidenceItem(item, editSessionId).catch(showError);
  });
  ```

  When the polling re-render removes the focused element, the browser dispatches `blur`, giving us one last chance to flush before teardown. The `change` listener is left in place — it remains useful for keyboard users who Enter or Tab out without explicitly defocusing. (Note: the `input` handler already mutates `item.comment` locally on every keystroke, so the blur handler reads the freshest value.)

### H7 — Heartbeat errors clobber inline status messages

- **File:** `fixthis-mcp/src/main/console/connection.js`, `startHeartbeatPolling`.
- **Current:** Both invocation sites of `sendBridgeHeartbeat()` (`startHeartbeatPolling` lines 168 and 170) end with `.catch(showError)`. On a flaky link, `#error` is rewritten with the same string every 2s, drowning out anything else the user is reading. `sendBridgeHeartbeat()` itself does not catch, so the dedup logic must live at the call sites.
- **Desired:** Heartbeat failures only surface in the inline error bar when the message *changes*; repeats are silently dropped. Connection card state continues to update on every poll.
- **Approach:** Replace the two `.catch(showError)` calls with a single helper:

  ```js
  let lastHeartbeatError = null;

  function handleHeartbeatError(cause) {
    const message = cause && cause.message ? cause.message : String(cause);
    if (message === lastHeartbeatError) return;
    lastHeartbeatError = message;
    showError(cause);
  }
  ```

  Reset `lastHeartbeatError = null` inside `sendBridgeHeartbeat` after a successful `refreshConnection()` so the next failure (if it changes) surfaces again. Both `startHeartbeatPolling` `.catch` calls switch to `handleHeartbeatError`. `applyConnectionStatus` continues to render connection card state as before — only the `#error` bar is silenced for repeats.

### M3 — "Send Agent" gives no success feedback

- **File:** `fixthis-mcp/src/main/console/prompt.js`, `sendAgentPrompt`.
- **Current:** On success, the pending list empties and the inspector re-renders. There is no banner, no toast, no inline confirmation; users have to infer success from absence.
- **Desired:** A short-lived inline success message ("Sent to agent ✓") in the existing `#error` slot, styled distinctly from errors, auto-clearing after 3 seconds.
- **Approach:** After the successful POST and re-render, call `showSuccess('Sent to agent ✓', 3000)` (helper added in L6).

### M4 — "Copy Prompt" gives no success feedback

- **File:** `fixthis-mcp/src/main/console/prompt.js`, `copyPrompt`.
- **Current:** Clipboard write completes silently. Users must paste somewhere to confirm.
- **Desired:** The button itself confirms by briefly relabelling to "Copied ✓" for 1.5 seconds, then reverting.
- **Approach:** Capture the original label text node before the await, change it on success, restore via `setTimeout`. Implementation lives entirely inside `copyPrompt`; do not change the HTML structure (the button has `<span>Copy Prompt</span>` as its second child).

### M7 — Segmented severity/status buttons missing `aria-pressed`

- **File:** `fixthis-mcp/src/main/console/rendering.js`, `renderAnnotationDetail` and `renderSavedAnnotationDetail`.
- **Current:** Each segmented group already has `role="group"` and an `aria-label`. The active button gets a CSS class but no ARIA state, so screen readers cannot announce which severity/status is selected.
- **Desired:** Each segmented button advertises its state via `aria-pressed="true"` or `aria-pressed="false"`.
- **Approach:** In both render functions, add `aria-pressed="${value === currentValue ? 'true' : 'false'}"` to the inline button HTML for both severity and status groups. No event-handler changes required.

### M10 — Empty preview message references nonexistent Refresh button

- **Files:** `fixthis-mcp/src/main/resources/console/index.html` (line 105 stub) and `fixthis-mcp/src/main/console/rendering.js`, `renderPreviewRegion`.
- **Current:** The static markup says "Refresh the live preview to begin." There is no Refresh button near the canvas; users hunt for one. `renderPreviewRegion` repeats the same string when no screen is present.
- **Desired:** A three-state message:
  - No session: "Connect a device to get started."
  - Session exists, no screenshot yet: "Waiting for first capture from device…"
  - Screen exists but `desktopFullPath` missing (existing branch): "No screenshot artifact for this preview." (unchanged.)
- **Approach:** Update the static HTML to "Connect a device to get started." (the safe default before any session). In `renderPreviewRegion`, when `!hasScreenshot`, choose:
  - if `screen` exists → "No screenshot artifact for this preview." (current behavior, preserved);
  - else if `state.session` exists → "Waiting for first capture from device…";
  - else → "Connect a device to get started."

### M12 — Duplicate `#selectionOverlay` IDs

- **Files:** `fixthis-mcp/src/main/resources/console/index.html` (line 104) and `fixthis-mcp/src/main/console/rendering.js`, `ensurePreviewFrame` (~line 423).
- **Current:** The static markup includes `<div id="selectionOverlay">` inside `#snapshot`, and `ensurePreviewFrame()` injects a second `<div id="selectionOverlay">` inside the dynamically created `#snapshotFrame`. Two elements share the ID; `document.getElementById('selectionOverlay')` returns whichever the browser registered first (the static one), but layout-coupled positioning relies on the dynamic one.
- **Desired:** Exactly one `#selectionOverlay` exists, owned by `ensurePreviewFrame`.
- **Approach:** Delete the line `<div id="selectionOverlay" class="selection-overlay"></div>` from `index.html`. `renderPreviewRegion` already replaces `snapshot.innerHTML` whenever there's no screenshot, so no orphan markup remains.

### L6 — `#error` shares red styling for both errors and successes

- **Files:** `fixthis-mcp/src/main/resources/console/styles.css` (`.error`), `fixthis-mcp/src/main/console/state.js`.
- **Current:** Any text we drop into `#error` shows in `var(--danger)` red. Success messages are not yet possible without misleading users.
- **Desired:** A `status-success` modifier class on `#error` that flips colour to `var(--accent)` (or a new `--success`). A reusable `showSuccess(msg, durationMs)` helper that sets the class, schedules a clear, and ensures the next error reverts the class.
- **Approach:** Add CSS:

  ```css
  .error.status-success { color: var(--accent); }
  ```

  In `state.js`, declare `let successClearTimeout = null;` and add:

  ```js
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
  ```

  Update `showError` in `main.js` so it removes the `status-success` class and cancels any pending success timeout before writing its red text. This guarantees a real error after a success doesn't bleed colour.

### L15 — Tool buttons missing `:focus-visible` ring

- **File:** `fixthis-mcp/src/main/resources/console/styles.css`.
- **Current:** Tool buttons (`.tool-button`, `.zoom-button`, `.annotation-back`, `.annotation-segmented button`) have no visible focus ring beyond the user-agent default that is suppressed by their resets.
- **Desired:** A 2px `var(--accent)` outline with 2px offset for keyboard focus, matching the `.annotation-danger:focus-visible` precedent already in the file.
- **Approach:** Append a single CSS block:

  ```css
  .tool-button:focus-visible,
  .zoom-button:focus-visible,
  .annotation-back:focus-visible,
  .annotation-segmented button:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  ```

## 5. Cross-Cutting Concerns

### 5.1 Bundler discipline

After every change to a `fixthis-mcp/src/main/console/*.js` source file, the implementer must run:

```
node scripts/build-console-assets.mjs && node scripts/build-console-assets.mjs --check
```

The first invocation regenerates `app.js`; the second exits non-zero if the bundle drifts from sources, gating the commit.

### 5.2 Interaction between `showSuccess` and `showError`

Order of operations matters. The user can click "Copy Prompt" (success path) and then immediately do something that errors. `showError` must clear the success class and any pending timeout; otherwise the next error renders in green. The plan threads this requirement through Task 3 (`showSuccess`) and Task 3's update to `showError`.

### 5.3 H4 flush vs. existing `input` handler

`updateSelectedAnnotationComment` (annotations.js line 454) already syncs `comment.value` into the focused item on every keystroke. In theory H4 should not happen. In practice, browser event ordering between mouse-down on the canvas and the textarea's `input` event is not guaranteed, and the `change` event (which would flush on blur) is never wired for the pending textarea. The explicit `flushFocusedPendingComment()` call inside the focus-changing functions is the deterministic fix. Keep the existing `input` handler — it is still useful for keeping `pendingItems` rendering in sync.

## 6. Testing Approach

### 6.1 Server tests

No server-side code changes. Run the existing Kotlin suite as a regression gate:

```
./gradlew :fixthis-mcp:test
```

Expected: green. If anything fails it indicates an unintended ripple from console changes (none expected in this changeset).

### 6.2 Manual browser verification

Single console session per task with the dev server running and `--console-assets-dir` pointed at `fixthis-mcp/src/main/resources/console/`. Browser at `http://127.0.0.1:60006/`.

Per-issue verification scripts:

- **H2 / H3:** Open a session that has zero pending annotations with comments. Tab to "Copy Prompt" — the keyboard cursor must skip it (it is `disabled`). Confirm the button is dim. Click the button area — nothing happens; no `window.alert` modal appears.
- **H4:** Click "Annotate", click a component, type "first" into the textarea, then drag-select another area on the canvas. The new annotation gets focus; in the inspector list, click annotation #1. The textarea must show "first".
- **H5:** Open a saved annotation, type characters into the comment textarea, and wait for the polling tick (≤2 s). The textarea must retain the typed text *and* a follow-up GET must reveal the value persisted (visible in the saved annotation row's preview line).
- **H7:** Disable Wi-Fi or kill the bridge. Heartbeat fails. The first failure shows "Connection paused. Your work is saved." in `#error`. Subsequent failures (every 2s) must NOT re-render the message; manually setting `error.textContent = 'test'` in DevTools must persist for at least one full heartbeat cycle.
- **M3:** With at least one commented annotation, click "Send Agent". After the network round-trip the inspector clears AND `#error` shows green "Sent to agent ✓"; after ~3 s it fades.
- **M4:** Click "Copy Prompt" with a commented annotation. The button label flips to "Copied ✓"; after ~1.5 s it reverts to "Copy Prompt". Pasting into a text editor confirms clipboard contents.
- **M7:** With a screen reader (VoiceOver, NVDA) focused on a severity button, navigation between High/Med/Low must announce the pressed state.
- **M10:** Load the console with no session. Empty stage reads "Connect a device to get started." Connect a device but before any capture: "Waiting for first capture from device…" (force this by stopping the bridge after device detection).
- **M12:** In DevTools, run `document.querySelectorAll('#selectionOverlay').length`. It must equal `1` (or `0` before any preview loads).
- **L6:** Trigger M3's success path, then immediately trigger an error (e.g., disable network and click "Send Agent"). Success class must clear; the error must render in red.
- **L15:** Tab through the canvas toolbar. Each `.tool-button`, `.zoom-button`, `.annotation-back`, and segmented option must show a 2 px lime focus ring.

### 6.3 Bundle verification

Each task's commit gate is `node scripts/build-console-assets.mjs --check` returning exit code 0. A non-zero exit means the source files and `app.js` are out of sync — the bundler must be re-run.

## 7. Acceptance Criteria

| Issue | Acceptance |
|---|---|
| H2 | No `window.alert(` calls remain in `prompt.js`. Failed prompt actions show inline only. |
| H3 | "Copy Prompt" and "Send Agent" report `disabled === true` in DevTools whenever the inspector lacks commented annotations or while a prompt action is in flight. |
| H4 | Switching focus between pending annotations (via canvas drag, list click, or new selection) preserves the textarea contents into the previously focused item. |
| H5 | Polling-driven re-render during typing does not lose unsaved changes; subsequent reload shows the typed text persisted. |
| H7 | Repeated heartbeat failures with identical messages do not re-write `#error`. A new distinct error message is still surfaced. |
| M3 | Successful "Send Agent" displays "Sent to agent ✓" in green for ~3 s, then clears. |
| M4 | "Copy Prompt" button shows "Copied ✓" for ~1.5 s on success. |
| M7 | Severity and status segmented buttons each carry `aria-pressed="true"` on the active option, `"false"` on the others. |
| M10 | Empty stage copy is correct for all three states (no session, session/no screenshot, screen/no artifact). |
| M12 | `document.querySelectorAll('#selectionOverlay').length` is at most `1` at all times. |
| L6 | `showSuccess` exists and is reusable; `showError` clears any prior success state. |
| L15 | All four button selectors render the 2 px accent focus ring under keyboard navigation. |

## 8. Risks & Mitigations

- **Bundle drift.** Forgetting to re-run the bundler ships sources without effect. Mitigation: every plan task ends with the bundler + check command before `git commit`.
- **`is-disabled` orphan.** Removing the class from H3's two buttons leaves the CSS rule in place; other future callers of `is-disabled` (if any) keep working. Audit reveals only these two buttons set the class today, so the rule could be removed — but doing so is out of scope for this change to keep the diff small.
- **Success ↔ error colour bleed.** Mitigated by L6's explicit `classList.remove('status-success')` in `showError`.
- **Timing of `flushFocusedPendingComment`.** Calling it inside `createAnnotationFromSelection` before mutating arrays guarantees we read the right index. Calling it inside `focusPendingFeedbackItem` likewise. We do not call it from `focusSavedEvidenceItem` because that path leaves the pending textarea untouched (`comment.value = ''` is fine — the user wasn't editing a pending item if they're now focusing a saved one; the H4 fix is scoped to pending-to-pending transitions, which is the user-reported defect).
