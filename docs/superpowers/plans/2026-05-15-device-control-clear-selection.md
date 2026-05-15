# Device Control Clear Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the top-bar `Clear selection` text button with a small `x` icon attached to the selected device chip while preserving device selection, refresh, and disconnect behavior.

**Architecture:** Keep the existing vanilla HTML/CSS/JS console architecture. Move `disconnectDeviceButton` into `#deviceControl`, constrain the invisible native `select` overlay to the main chip area, and let the `x` button own its own pointer/keyboard events. Update contract tests and docs to reflect the visible icon label without changing MCP endpoints or persisted data.

**Tech Stack:** Kotlin/JUnit console asset contract tests, static console HTML/CSS, vanilla JavaScript event listeners, Markdown docs.

---

## File Structure

- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt`
  - Tighten the existing top-bar contract around `disconnectDeviceButton`: it remains present and accessible, but no longer renders visible `Clear selection` text.
- Modify `fixthis-mcp/src/main/resources/console/index.html`
  - Move `disconnectDeviceButton` inside `#deviceControl` as a trailing icon segment.
- Modify `fixthis-mcp/src/main/resources/console/styles.css`
  - Replace `.clear-device-button` text-button styling with `.device-clear-button` chip-segment styling.
  - Restrict the invisible `#devicePicker` overlay to the chip body so it does not cover the clear icon.
  - Remove compact-breakpoint text-button sizing rules.
- Modify `docs/reference/feedback-console-contract.md`
  - Update the canonical visible label for the top-bar device clear action from `Clear selection` to `x` while keeping the semantic action name.

## Task 1: Update the Console Contract Test First

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt`

- [ ] **Step 1: Replace the existing text-label assertion with icon/button assertions**

In `consoleHtmlIncludesSelectionHandoffWorkspace`, replace:

```kotlin
assertTrue(html.contains("Clear selection"))
assertTrue(html.contains("aria-label=\"Clear FixThis device selection\""))
assertTrue(html.contains("title=\"Clear FixThis device selection\""))
```

with:

```kotlin
assertTrue(html.contains("id=\"disconnectDeviceButton\""))
assertTrue(html.contains("class=\"device-clear-button\""))
assertTrue(html.contains("aria-label=\"Clear FixThis device selection\""))
assertTrue(html.contains("title=\"Clear FixThis device selection\""))
assertTrue(html.contains("aria-hidden=\"true\">&times;</span>"))
assertFalse(html.contains(">Clear selection</button>"))
assertFalse(html.contains("class=\"clear-device-button\""))
```

- [ ] **Step 2: Run the focused contract test and confirm it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.github.beyondwin.fixthis.mcp.console.ConsoleAssetContractTest.consoleHtmlIncludesSelectionHandoffWorkspace'
```

Expected: FAIL because the current HTML still renders `class="clear-device-button"` and visible `Clear selection` text, and does not include `class="device-clear-button"` or `&times;`.

- [ ] **Step 3: Commit the failing test**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt
git commit -m "test: specify device clear icon control"
```

## Task 2: Move Clear Selection Into the Device Chip

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`

- [ ] **Step 1: Move the disconnect button into `#deviceControl`**

In `fixthis-mcp/src/main/resources/console/index.html`, replace the current device-control block:

```html
<div id="deviceControl" class="device-control" data-connection-state="none">
  <span class="device-state-dot" aria-hidden="true"></span>
  <span class="device-copy">
    <span id="deviceName" class="device-name">No device</span>
    <span id="deviceConnectionState" class="device-connection-state">No device</span>
  </span>
  <span class="device-chevron" aria-hidden="true">▾</span>
  <select id="devicePicker" aria-label="Android device"></select>
</div>
```

with:

```html
<div id="deviceControl" class="device-control" data-connection-state="none">
  <span class="device-state-dot" aria-hidden="true"></span>
  <span class="device-copy">
    <span id="deviceName" class="device-name">No device</span>
    <span id="deviceConnectionState" class="device-connection-state">No device</span>
  </span>
  <span class="device-chevron" aria-hidden="true">▾</span>
  <select id="devicePicker" aria-label="Android device"></select>
  <button id="disconnectDeviceButton" class="device-clear-button" type="button" title="Clear FixThis device selection" aria-label="Clear FixThis device selection">
    <span aria-hidden="true">&times;</span>
  </button>
</div>
```

Then delete the old standalone button immediately after `refreshDevicesButton`:

```html
<button id="disconnectDeviceButton" class="clear-device-button" type="button" title="Clear FixThis device selection" aria-label="Clear FixThis device selection">Clear selection</button>
```

- [ ] **Step 2: Update base device-control CSS**

In `fixthis-mcp/src/main/resources/console/styles.css`, replace the `.device-control` and `.device-control select` rules with:

```css
.device-control {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 210px;
  max-width: 294px;
  min-height: 32px;
  padding: 0 0 0 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg-2);
  color: var(--txt-0);
  overflow: hidden;
}

.device-control select {
  position: absolute;
  inset: 0 32px 0 0;
  width: auto;
  height: 100%;
  opacity: 0;
  cursor: pointer;
  z-index: 1;
}
```

Keep the existing `.device-control:focus-within`, state-color, `.device-copy`, `.device-name`, `.device-connection-state`, `.device-chevron`, and `.icon-button` rules.

- [ ] **Step 3: Replace text-button CSS with chip-clear CSS**

Delete the base rule:

```css
.clear-device-button {
  white-space: nowrap;
}
```

Add this rule in the same area:

```css
.device-clear-button {
  position: relative;
  z-index: 2;
  align-self: stretch;
  width: 32px;
  min-width: 32px;
  min-height: 30px;
  padding: 0;
  display: inline-grid;
  place-items: center;
  border: 0;
  border-left: 1px solid var(--line);
  border-radius: 0;
  background: transparent;
  color: var(--txt-2);
  font-size: 16px;
  line-height: 1;
}

.device-clear-button:hover,
.device-clear-button:focus-visible {
  background: rgba(242, 109, 109, .12);
  color: #f26d6d;
}
```

- [ ] **Step 4: Update compact-breakpoint CSS**

At `@media (max-width: 1099px)`, replace:

```css
.device-control {
  min-width: 150px;
  max-width: 190px;
}
```

with:

```css
.device-control {
  min-width: 182px;
  max-width: 226px;
}
```

Delete the whole `@media (max-width: 1099px)` block for `.clear-device-button`:

```css
.clear-device-button {
  width: 64px;
  min-width: 64px;
  max-width: 64px;
  overflow: hidden;
  text-overflow: ellipsis;
}
```

At `@media (max-width: 900px)`, keep:

```css
.device-control {
  min-width: 0;
  max-width: none;
}
```

Delete the whole `@media (max-width: 900px)` block for `.clear-device-button`:

```css
.clear-device-button {
  justify-self: start;
  width: max-content;
  min-width: 0;
  max-width: none;
}
```

- [ ] **Step 5: Run the focused contract test and confirm it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.github.beyondwin.fixthis.mcp.console.ConsoleAssetContractTest.consoleHtmlIncludesSelectionHandoffWorkspace'
```

Expected: PASS.

- [ ] **Step 6: Commit the HTML/CSS implementation**

```bash
git add fixthis-mcp/src/main/resources/console/index.html fixthis-mcp/src/main/resources/console/styles.css
git commit -m "fix: attach device clear action to chip"
```

## Task 3: Update Console Contract Documentation

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`

- [ ] **Step 1: Update the canonical label table**

Replace this row:

```markdown
| Clear FixThis device selection | `disconnectDeviceButton` | `Clear selection` |
```

with:

```markdown
| Clear FixThis device selection | `disconnectDeviceButton` | `x` icon |
```

- [ ] **Step 2: Clarify device semantics language**

Replace:

```markdown
- `Clear selection` clears only FixThis's active device selection and owned bridge resources.
- `Clear selection` must not run `adb disconnect`, detach USB, or affect Wi-Fi ADB outside FixThis-owned resources.
```

with:

```markdown
- The device-chip `x` clears only FixThis's active device selection and owned bridge resources.
- The device-chip `x` must not run `adb disconnect`, detach USB, or affect Wi-Fi ADB outside FixThis-owned resources.
```

- [ ] **Step 3: Run the docs/asset consistency checks**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.github.beyondwin.fixthis.mcp.console.ConsoleAssetContractTest.consoleHtmlIncludesSelectionHandoffWorkspace'
node scripts/check-doc-consistency.mjs
```

Expected: both commands PASS. If `node scripts/check-doc-consistency.mjs` reports unrelated pre-existing doc drift, capture the output and do not broaden this change without checking the diff.

- [ ] **Step 4: Commit docs**

```bash
git add docs/reference/feedback-console-contract.md
git commit -m "docs: describe device clear icon"
```

## Task 4: Browser Verification and Final Test Sweep

**Files:**
- Verify: `fixthis-mcp/src/main/resources/console/index.html`
- Verify: `fixthis-mcp/src/main/resources/console/styles.css`
- Verify: `fixthis-mcp/src/main/console/main.js`
- Verify: `fixthis-mcp/src/main/console/devices.js`

- [ ] **Step 1: Run the console asset test file**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.github.beyondwin.fixthis.mcp.console.ConsoleAssetContractTest'
```

Expected: PASS.

- [ ] **Step 2: Build console assets if the project requires generated asset refresh**

Run:

```bash
node scripts/build-console-assets.mjs
```

Expected: exits 0. If it modifies `fixthis-mcp/src/main/resources/console/app.js`, `app.js.map`, or `console-build-meta.json`, inspect the diff and commit those generated files only if this repo's current asset workflow expects them to change for static HTML/CSS edits.

- [ ] **Step 3: Run the browser smoke test**

Run:

```bash
node scripts/console-browser-smoke.mjs
```

Expected: PASS or explicit documented SKIP when no browser/device fixture is available. If it starts a local console URL, verify the top bar visually: the selected device chip has an attached `x`, refresh remains a standalone icon, and visible `Clear selection` text is gone from the top bar.

- [ ] **Step 4: Verify keyboard/pointer behavior manually**

Use the local console page from the smoke/dev script and confirm:

1. Clicking the main device chip opens the native device picker.
2. Tabbing reaches the `x` button.
3. Pressing Enter or Space on the `x` calls the existing disconnect flow.
4. Clicking `x` does not open the device picker.
5. Clicking refresh still calls the refresh-devices flow.
6. The inspector footer still contains the separate canvas `Clear Selection` action.

- [ ] **Step 5: Commit any generated or verification-only updates**

If Task 4 Step 2 produced intentional generated asset changes, commit them:

```bash
git add fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "chore: refresh console assets"
```

If no generated files changed, skip this commit.

## Self-Review

- Spec coverage: device chip owns selection/status, `x` clears selection, refresh remains separate, accessible name/title remain, no MCP or persisted JSON changes.
- Placeholder scan: no TBD/TODO/fill-in instructions; every code change has exact snippets and commands.
- Type/name consistency: `disconnectDeviceButton`, `devicePicker`, `device-control`, and `device-clear-button` are used consistently across tasks.
- Scope check: one feedback-console UI change; no Android sidekick, bridge, or MCP data-model work included.
