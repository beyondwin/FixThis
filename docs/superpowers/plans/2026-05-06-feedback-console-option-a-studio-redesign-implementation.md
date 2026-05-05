# Feedback Console Option A Studio Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the PointPatch feedback console UI around the supplied Option A Studio design while preserving the shipped live-preview, batched pending items, evidence snapshot, Copy, and Send contracts.

**Architecture:** Keep the MCP/session/storage APIs unchanged and replace the browser console shell in `FeedbackConsoleAssets.kt` with an Option A Studio-inspired layout. Split the client-side rendering into stable regions so live preview polling updates only the preview surface, while session, pending, draft, and sent panels update only on their own state changes.

**Tech Stack:** Kotlin/JVM, embedded HTML/CSS/vanilla JavaScript in `pointpatch-mcp`, existing `FeedbackConsoleServer`, existing MCP session service/store APIs, Kotlin tests, Playwright/manual browser smoke.

---

## Source Inputs

- Visual prototype: `/Users/kws/Downloads/PointPatch Console _standalone_.html`
- Detailed Option A spec: `/Users/kws/Downloads/PointPatch Studio Spec _standalone_.html`
- Detailed implementation guide: `docs/superpowers/specs/2026-05-06-feedback-console-option-a-studio-redesign-implementation-details.md`
- Current console asset: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Current console tests: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`
- Current live-preview contract: `docs/superpowers/specs/2026-05-05-feedback-console-live-preview-batched-items-detailed-design.md`
- Current UX status: `docs/design-feedback-console-ux.md`

## Confirmed Prototype Event Contract

`/Users/kws/Downloads/PointPatch Console _standalone_.html` is an executable prototype, not just a static reference. Treat its Option A implementation as the behavioral source for the Studio interaction feel, then map that behavior onto PointPatch's stricter feedback-console contracts.

Observed Option A behavior:

- `StudioOption` owns `snapshots`, `activeSnapId`, `annotations`, `draftTitle`, `selectedId`, `draggingRect`, and `tool`.
- Top bar actions are working: `New session` clears the working set, while `Save snapshot` deep-copies the current annotations into an immutable history card.
- History cards open saved snapshots and delete without also opening the deleted card.
- Canvas tools are working: `Select` inspects existing annotations; `Annotate` lets a widget click or drag create a new annotation, then automatically returns to `Select`.
- Widget targeting uses closest `[data-w]` metadata and percent-based bounds in the prototype.
- Drag creation uses a movement threshold before creating a region annotation.
- Annotation list rows select the matching overlay and open the detail inspector.
- The detail inspector edits prototype-only fields: label, severity, comment, and status; delete removes only the selected annotation.
- Prototype keyboard shortcuts include view/annotate switching, escape, save, new session, delete, and severity shortcuts.

PointPatch mapping constraints:

- Do not copy prototype annotation editing wholesale. PointPatch pending items remain append-only except `Focus` and `Delete`.
- Do not introduce a Select/Navigate toggle. Idle preview interaction remains navigation; feedback selection only exists after `Add` freezes the preview.
- Map prototype `Annotate` behavior to PointPatch's frozen `Add` flow: click selects a Compose node, drag selects an area, comment queues a pending item, and the UI returns to a stable pending list.
- Keep PointPatch natural screenshot coordinates for bridge requests and evidence overlays. Do not migrate persisted selection bounds to prototype percent coordinates.
- Keep Option A's history, canvas, inspector, spacing, tokens, hover, focus, and modal-free interaction feel.

## Option A To PointPatch Mapping

Adopt from Option A:

- Dark Studio visual system: `--bg-0 #0d0e10`, `--bg-1 #131418`, `--bg-2 #1a1c21`, `--bg-3 #21242b`, `--line #2a2d35`, `--txt-0 #e8e9eb`, `--txt-1 #b6b8be`, `--txt-2 #7d8089`, `--accent #b8d36a`, `--danger #f26d6d`.
- 56px top bar plus 3-column body: left history/sidebar, center canvas, right inspector.
- Canvas toolbar, live/frozen badges, phone-like preview frame, numbered overlays, hover/focus states, 120ms transitions, reduced-motion guard.
- History-card style for sessions and sent handoff history.
- Inspector pattern: list/detail feel, but mapped to PointPatch's current add-flow and saved evidence groups.

Preserve from PointPatch:

- Idle preview click is navigation. There is no Select/Navigate toggle.
- Top-level session actions remain short: `Refresh`, `Add`, `Save`, `Copy`, `Send`, `New`, `Close`.
- `Copy` and `Send` stay session-level actions and do not live inside the comment composer.
- Live preview is transient and never appended to `FeedbackSession.screens`.
- `Add` freezes the current preview and stops preview polling.
- Pending items support only `Focus` and `Delete`; no pending edit, severity, status, label editing, pin move, or pin resize.
- `Save` promotes one frozen preview to one evidence snapshot and links all pending items to the same `screenId`.
- Saved evidence groups show persisted screenshot, numbered overlay, and saved comments.
- MCP process owns session state and `.pointpatch/feedback-sessions/` persistence.

## File Structure

- Modify `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
  - Owns the redesigned browser shell, CSS tokens, DOM layout, JavaScript state, render functions, events, and API calls.
  - Keep endpoint paths and request/response payloads stable.
- Modify `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`
  - Add/adjust HTML contract tests for Option A structure, stable preview rendering, mode-aware inspector, and retained PointPatch contracts.
- Modify `docs/design-feedback-console-ux.md`
  - Update the status doc after implementation to describe the Option A Studio console, not the current light debug layout.
- No server API, store, persistence, bridge, CLI, or Compose-core file changes are expected for this redesign.

## Task 1: Lock The Redesign Contract In Tests

**Files:**
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add failing HTML contract tests for the Studio shell**

Add this test near the existing console HTML tests:

```kotlin
@Test
fun consoleHtmlUsesOptionAStudioShell() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("class=\"studio-shell\""))
    assertTrue(html.contains("class=\"studio-topbar\""))
    assertTrue(html.contains("class=\"studio-history\""))
    assertTrue(html.contains("class=\"studio-canvas\""))
    assertTrue(html.contains("class=\"studio-inspector\""))
    assertTrue(html.contains("id=\"previewModeBadge\""))
    assertTrue(html.contains("id=\"canvasToolbar\""))
    assertTrue(html.contains("id=\"inspectorTitle\""))
    assertTrue(html.contains("id=\"inspectorBody\""))
    assertTrue(html.contains("id=\"inspectorFooter\""))
    assertTrue(html.contains("--bg-0: #0d0e10"))
    assertTrue(html.contains("--accent: #b8d36a"))
    assertFalse(html.contains("class=\"queue-pane\""))
}
```

- [x] **Step 2: Add failing tests for preserved PointPatch actions**

Add this test:

```kotlin
@Test
fun consoleHtmlKeepsPointPatchTopLevelActionsInStudioTopbar() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("id=\"refreshButton\""))
    assertTrue(html.contains("id=\"addFlowButton\""))
    assertTrue(html.contains("id=\"saveButton\""))
    assertTrue(html.contains("id=\"copyMarkdownButton\""))
    assertTrue(html.contains("id=\"sendDraftButton\""))
    assertTrue(html.contains("id=\"newSessionButton\""))
    assertTrue(html.contains("id=\"closeSessionButton\""))
    assertTrue(html.contains(">Refresh<"))
    assertTrue(html.contains(">Add<"))
    assertTrue(html.contains(">Save<"))
    assertTrue(html.contains(">Copy<"))
    assertTrue(html.contains(">Send<"))
    assertTrue(html.contains(">New<"))
    assertTrue(html.contains(">Close<"))
    assertFalse(html.contains("id=\"modeSelect\""))
    assertFalse(html.contains("id=\"modeNavigate\""))
}
```

- [x] **Step 3: Add failing tests for render isolation**

Add this test:

```kotlin
@Test
fun consoleHtmlRefreshPreviewOnlyRendersPreviewRegion() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("function renderPreviewRegion"))
    assertTrue(html.contains("function renderSessionRegions"))
    assertTrue(html.contains("function renderInspectorRegion"))
    assertTrue(html.contains("function renderPreviewOnly"))
    assertTrue(Regex("async function refreshPreview\\(\\)[\\s\\S]*renderPreviewOnly\\(\\);").containsMatchIn(html))
    assertFalse(Regex("async function refreshPreview\\(\\)[\\s\\S]*render\\(\\);").containsMatchIn(html))
}
```

- [x] **Step 4: Run the failing targeted test**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: the three new tests fail because the current console still uses the light layout, `queue-pane`, and full `render()` on preview refresh.

Actual RED run: 2 failures. `consoleHtmlKeepsPointPatchTopLevelActionsInStudioTopbar` already passes because the current console has the preserved top-level PointPatch button IDs and labels, while `consoleHtmlUsesOptionAStudioShell` and `consoleHtmlRefreshPreviewOnlyRendersPreviewRegion` remain red.

- [x] **Step 5: Commit the failing tests**

```bash
git add pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "test: lock studio console redesign contract"
```

## Task 2: Replace The Static Shell With Option A Studio Layout

**Files:**
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Test: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Replace the top-level CSS tokens and grid**

In the `<style>` block, replace the current light `:root`, `header`, `main`, and `section` shell rules with this Studio shell foundation:

```css
:root {
  color-scheme: dark;
  --bg-0: #0d0e10;
  --bg-1: #131418;
  --bg-2: #1a1c21;
  --bg-3: #21242b;
  --line: #2a2d35;
  --line-soft: rgba(42, 45, 53, .72);
  --txt-0: #e8e9eb;
  --txt-1: #b6b8be;
  --txt-2: #7d8089;
  --accent: #b8d36a;
  --danger: #f26d6d;
  --warning: #e6b45a;
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  background: var(--bg-0);
  color: var(--txt-0);
}
* { box-sizing: border-box; }
body { margin: 0; height: 100vh; overflow: hidden; background: var(--bg-0); }
.studio-shell {
  display: grid;
  grid-template-rows: 56px 1fr;
  height: 100vh;
  overflow: hidden;
}
.studio-topbar {
  display: grid;
  grid-template-columns: 220px minmax(360px, 1fr) auto;
  align-items: center;
  gap: 16px;
  padding: 0 16px;
  background: var(--bg-1);
  border-bottom: 1px solid var(--line);
}
.studio-body {
  display: grid;
  grid-template-columns: 280px minmax(480px, 1fr) 340px;
  min-height: 0;
  overflow: hidden;
}
.studio-history,
.studio-canvas,
.studio-inspector {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg-0);
}
.studio-history,
.studio-inspector {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--line);
}
.studio-inspector { border-right: 0; border-left: 1px solid var(--line); }
@media (max-width: 1099px) {
  .studio-body { grid-template-columns: 220px minmax(420px, 1fr) 300px; }
}
@media (max-width: 899px) {
  .studio-shell::before {
    content: "Resize to >= 900px wide";
    position: fixed;
    inset: 0;
    display: grid;
    place-items: center;
    z-index: 999;
    background: var(--bg-0);
    color: var(--txt-1);
    font-size: 14px;
  }
}
```

- [x] **Step 2: Replace the `<body>` shell markup**

Keep all existing IDs used by JavaScript, but move them into the Studio layout:

```html
<body>
  <div class="studio-shell">
    <header class="studio-topbar">
      <div class="studio-brand">
        <div class="studio-mark" aria-hidden="true">◐</div>
        <div>
          <h1>PointPatch</h1>
          <div class="brand-caption">Studio</div>
        </div>
      </div>
      <div class="studio-context">
        <span id="sessionMeta" class="session-meta">Loading session...</span>
        <select id="devicePicker"></select>
        <select id="previewIntervalSelect" aria-label="Preview interval">
          <option value="manual">Manual</option>
          <option value="1000">1s</option>
          <option value="2000" selected>2s</option>
          <option value="5000">5s</option>
        </select>
        <button id="refreshDevicesButton">Devices</button>
        <button id="disconnectDeviceButton">Disconnect</button>
        <span id="deviceStatus" class="status-pill">No device selected</span>
      </div>
      <div class="studio-actions">
        <button id="refreshButton">Refresh</button>
        <button id="addFlowButton" class="primary">Add</button>
        <button id="saveButton" disabled>Save</button>
        <button id="copyMarkdownButton">Copy</button>
        <button id="sendDraftButton">Send</button>
        <button id="newSessionButton">New</button>
        <button id="closeSessionButton">Close</button>
      </div>
    </header>
    <main class="studio-body">
      <aside class="studio-history">
        <div class="panel-head">
          <div class="panel-title">Sessions</div>
          <div id="sessionCount" class="panel-count">0</div>
        </div>
        <div id="sessions" class="history-list"></div>
        <details class="sent-history-drawer">
          <summary>Sent History</summary>
          <div id="sentHistory" class="history-list"></div>
        </details>
      </aside>
      <section class="studio-canvas">
        <div id="canvasToolbar" class="canvas-toolbar">
          <div class="canvas-tool-status">
            <span id="previewModeBadge" class="mode-badge" data-mode="idle">Live</span>
            <span id="snapshotTitle">Live Preview</span>
          </div>
          <div id="navigationControls" class="navigation-controls">
            <button id="backButton" aria-label="Back">←</button>
            <button id="swipeUpButton" aria-label="Swipe up">↑</button>
            <button id="swipeDownButton" aria-label="Swipe down">↓</button>
            <button id="swipeLeftButton" aria-label="Swipe left">←</button>
            <button id="swipeRightButton" aria-label="Swipe right">→</button>
            <label class="capture-toggle"><input id="captureAfterNavigation" type="checkbox"> Capture</label>
          </div>
        </div>
        <div id="snapshot" class="snapshot-stage">
          <div id="selectionOverlay" class="selection-overlay" aria-hidden="true"></div>
          <div class="empty-stage">Refresh the live preview to begin.</div>
        </div>
      </section>
      <aside class="studio-inspector">
        <div class="panel-head">
          <div id="inspectorTitle" class="panel-title">Draft</div>
          <div id="inspectorCount" class="panel-count">0</div>
        </div>
        <div id="inspectorBody" class="inspector-body">
          <div id="selectionSummary" class="selection-summary">No selection.</div>
          <textarea id="comment" placeholder="Describe the UI change needed"></textarea>
          <div id="pendingItems" class="list"></div>
          <div id="draftItems" class="list"></div>
        </div>
        <div id="inspectorFooter" class="inspector-footer">
          <button id="clearSelectionButton">Clear Selection</button>
          <button id="cancelAddFlowButton" disabled>Cancel</button>
          <button id="addItemButton" class="primary" disabled>Add to Pending</button>
          <button id="clearDraftButton">Clear Draft</button>
        </div>
        <p id="error" class="error" role="status" aria-live="polite"></p>
      </aside>
    </main>
  </div>
</body>
```

- [x] **Step 3: Add Option A component CSS**

Add focused styles for brand, buttons, panels, canvas, inspector, rows, and evidence cards. Keep border radii at 8px or below for cards/buttons:

```css
.studio-brand { display: flex; align-items: center; gap: 10px; min-width: 0; }
.studio-mark {
  width: 30px; height: 30px; border-radius: 8px;
  display: grid; place-items: center;
  background: var(--accent); color: var(--bg-0);
  font-weight: 800; font-size: 18px;
}
h1 { margin: 0; font-size: 15px; line-height: 1.1; font-weight: 700; letter-spacing: 0; }
.brand-caption, .panel-title {
  color: var(--txt-2);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .14em;
  text-transform: uppercase;
}
.studio-context, .studio-actions, .navigation-controls, .inspector-footer {
  display: flex; align-items: center; gap: 8px; min-width: 0;
}
button, select {
  min-height: 32px;
  border: 1px solid var(--line);
  border-radius: 7px;
  background: var(--bg-2);
  color: var(--txt-1);
  padding: 0 10px;
  font: inherit;
  font-size: 12px;
}
button { cursor: pointer; transition: background 120ms ease, color 120ms ease, border-color 120ms ease, transform 120ms ease; }
button:hover:not(:disabled) { background: var(--bg-3); color: var(--txt-0); }
button.primary { background: var(--accent); border-color: var(--accent); color: var(--bg-0); font-weight: 700; }
button.primary:hover:not(:disabled) { transform: translateY(-1px); }
button:disabled { opacity: .4; cursor: default; }
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  min-height: 48px;
  padding: 0 14px;
  border-bottom: 1px solid var(--line);
}
.panel-count, .status-pill {
  border-radius: 999px;
  background: var(--bg-3);
  color: var(--txt-1);
  padding: 4px 8px;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.history-list, .inspector-body { overflow: auto; padding: 8px; display: grid; gap: 6px; }
.row {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px;
  background: var(--bg-1);
}
.row.active, .session-row.active {
  background: var(--bg-2);
  box-shadow: inset 2px 0 0 var(--accent);
}
.row strong { display: block; color: var(--txt-0); font-size: 12px; margin-bottom: 4px; }
.row span { display: block; color: var(--txt-2); font-size: 11px; overflow-wrap: anywhere; }
.studio-canvas { display: grid; grid-template-rows: 48px 1fr; }
.canvas-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 14px;
  border-bottom: 1px solid var(--line);
  background: var(--bg-0);
}
.mode-badge {
  display: inline-flex; align-items: center;
  min-height: 24px;
  border-radius: 999px;
  padding: 0 8px;
  background: var(--bg-3);
  color: var(--txt-1);
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
}
.mode-badge[data-mode="live"] { color: var(--accent); }
.mode-badge[data-mode="frozen"] { background: var(--accent); color: var(--bg-0); }
.snapshot-stage {
  display: grid;
  place-items: center;
  min-height: 0;
  overflow: hidden;
  padding: 24px;
  background: radial-gradient(circle at 50% 50%, var(--bg-1) 0%, var(--bg-0) 72%);
}
.snapshot-frame {
  position: relative;
  display: inline-block;
  max-width: min(100%, 420px);
  max-height: 100%;
  padding: 8px;
  border-radius: 36px;
  background: linear-gradient(180deg, #2a2a2e 0%, #1a1a1d 100%);
  box-shadow: 0 0 0 2px #3a3a40, 0 30px 60px -20px rgba(0,0,0,.6);
}
.snapshot-frame[data-mode="frozen"] {
  box-shadow: 0 0 0 2px var(--accent), 0 0 0 6px rgba(184,211,106,.10), 0 30px 60px -20px rgba(0,0,0,.6);
}
.snapshot-frame img {
  display: block;
  max-width: 100%;
  max-height: calc(100vh - 160px);
  width: auto;
  height: auto;
  border: 0;
  border-radius: 28px;
  cursor: pointer;
}
@media (prefers-reduced-motion: reduce) {
  * { animation-duration: .01ms !important; transition-duration: .01ms !important; }
}
```

- [x] **Step 4: Run the targeted shell tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlUsesOptionAStudioShell --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlKeepsPointPatchTopLevelActionsInStudioTopbar
```

Expected: the two tests added in Task 1 pass.

- [x] **Step 5: Commit the shell replacement**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt
git commit -m "feat: add studio console shell"
```

## Task 3: Split Rendering So Live Preview Does Not Flicker Draft

**Files:**
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Test: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add stable region functions**

Replace the single full `render()` responsibility with region-specific functions. Keep `render()` as the full coordinator for session-changing operations:

```javascript
function renderSessionRegions() {
  const session = state.session;
  const allItems = session?.items || [];
  sessionMeta.textContent = session ? formatSessionHeader(session, allItems.length) : 'No active session';
  renderSessionsList();
  renderSentHistory();
}

function renderInspectorRegion() {
  if (addItemsFlow) {
    renderComposerInspector();
  } else {
    renderDraftInspector();
  }
  updateComposerState();
}

function renderPreviewOnly() {
  renderPreviewRegion();
  renderSelectionOverlay();
  updateComposerState();
}

function render() {
  renderSessionRegions();
  renderPreviewRegion();
  renderInspectorRegion();
  updateComposerState();
}
```

- [x] **Step 2: Update `refreshPreview()` to avoid full `render()`**

Change only the final render call in `refreshPreview()`:

```javascript
async function refreshPreview() {
  error.textContent = '';
  if (addItemsFlow) return;
  const requestGeneration = ++previewRequestGeneration;
  const preview = await requestLivePreview();
  if (addItemsFlow || requestGeneration !== previewRequestGeneration) return;
  state.preview = preview;
  renderPreviewOnly();
}
```

- [x] **Step 3: Make the preview image node stable**

Replace `snapshot.innerHTML = ...` in `renderPreviewRegion()` with a stable image update path:

```javascript
function ensurePreviewFrame() {
  let frame = document.getElementById('snapshotFrame');
  if (frame) return frame;
  snapshot.innerHTML =
    '<div id="snapshotFrame" class="snapshot-frame">' +
      '<img id="snapshotImage" alt="PointPatch preview">' +
      '<div id="selectionOverlay" class="selection-overlay" aria-hidden="true"></div>' +
    '</div>';
  attachSnapshotHandlers();
  return document.getElementById('snapshotFrame');
}

function renderPreviewRegion() {
  const screen = latestScreen();
  const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
  const mode = addItemsFlow ? 'frozen' : (state.preview ? 'live' : 'idle');
  previewModeBadge.dataset.mode = mode;
  previewModeBadge.textContent = mode === 'frozen' ? 'Frozen' : mode === 'live' ? 'Live' : 'Idle';
  snapshotTitle.textContent = addItemsFlow ? 'Frozen Feedback Snapshot' : 'Live Preview';
  if (!hasScreenshot) {
    snapshot.innerHTML = '<div class="empty-stage">' + (screen ? 'No screenshot artifact for this preview.' : 'Refresh the live preview to begin.') + '</div>';
    updateComposerState();
    return;
  }
  const frame = ensurePreviewFrame();
  frame.dataset.mode = mode;
  const image = document.getElementById('snapshotImage');
  const src = addItemsFlow?.screenshotUrl || previewScreenshotUrl(state.preview.previewId);
  if (image.getAttribute('src') !== src) {
    image.setAttribute('src', src);
  }
  renderSelectionOverlay();
}
```

- [x] **Step 4: Update action paths to render only affected regions**

Use narrower renders in these functions:

```javascript
function queuePendingFeedbackItem() {
  const feedbackComment = comment.value.trim();
  if (!addItemsFlow) throw new Error('Click Add before selecting feedback.');
  if (!currentSelection) throw new Error('Select a component or area first.');
  if (!feedbackComment) throw new Error('Enter a comment before adding it to the pending list.');
  pendingFeedbackItems.push({
    targetType: currentSelection.targetType,
    nodeUid: currentSelection.nodeUid,
    bounds: currentSelection.bounds,
    comment: feedbackComment
  });
  currentSelection = null;
  focusedPendingItemIndex = null;
  comment.value = '';
  renderPreviewOnly();
  renderInspectorRegion();
}

function deletePendingFeedbackItem(index) {
  pendingFeedbackItems.splice(index, 1);
  focusedPendingItemIndex = null;
  renderPreviewOnly();
  renderInspectorRegion();
}

function focusPendingFeedbackItem(index) {
  focusedPendingItemIndex = index;
  currentSelection = null;
  renderPreviewOnly();
  renderInspectorRegion();
}
```

- [x] **Step 5: Run the render isolation test**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlRefreshPreviewOnlyRendersPreviewRegion
```

Expected: pass. The test proves preview polling no longer calls the full `render()`.

- [x] **Step 6: Commit render isolation**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "fix: isolate live preview rendering"
```

## Task 4: Implement Studio Canvas Behavior

**Files:**
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add canvas behavior test**

Add this test:

```kotlin
@Test
fun consoleHtmlRendersStudioCanvasModesAndNavigation() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("previewModeBadge.dataset.mode = mode"))
    assertTrue(html.contains("previewModeBadge.textContent = mode === 'frozen' ? 'Frozen'"))
    assertTrue(html.contains("frame.dataset.mode = mode"))
    assertTrue(html.contains("navigationControls.hidden = Boolean(addItemsFlow)"))
    assertTrue(html.contains("aria-label=\"Back\""))
    assertTrue(html.contains("aria-label=\"Swipe up\""))
    assertTrue(html.contains("renderNumberedFeedbackOverlay"))
    assertTrue(html.contains("'#' + (index + 1)"))
}
```

- [x] **Step 2: Preserve idle navigation and frozen selection**

Keep this click handler logic intact after moving DOM:

```javascript
image.addEventListener('click', event => {
  try {
    if (suppressNextClick) {
      suppressNextClick = false;
      return;
    }
    if (!addItemsFlow) {
      const point = naturalPointFromEvent(event, image);
      navigate('tap', { x: point.x, y: point.y }).catch(showError);
      return;
    }
    if (!dragStart) {
      selectNodeAtPoint(event, image);
    }
  } catch (cause) {
    showError(cause);
  }
});
```

- [x] **Step 3: Keep area selection thresholds in natural screenshot pixels**

Keep the current `pointerup` contract, including the `8x8` minimum:

```javascript
if ((bounds.right - bounds.left) >= 8 && (bounds.bottom - bounds.top) >= 8) {
  suppressNextClick = true;
  finishAreaSelection(bounds);
} else {
  renderSelectionOverlay();
  selectNodeAtPoint(event, image);
}
```

- [x] **Step 4: Update overlay styling to Studio tokens**

Replace green/orange overlay colors with Studio colors while retaining focused state:

```css
.selection-overlay {
  position: absolute;
  inset: 8px;
  pointer-events: none;
}
.selection-box {
  position: absolute;
  border: 1.5px solid var(--accent);
  background: rgba(184, 211, 106, .12);
  border-radius: 6px;
}
.selection-box.drag-preview {
  border-style: dashed;
  background: rgba(184, 211, 106, .08);
}
.selection-box.focused {
  border-color: var(--warning);
  background: rgba(230, 180, 90, .16);
}
.selection-label {
  position: absolute;
  transform: translateY(-100%);
  min-width: 24px;
  min-height: 24px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: var(--accent);
  color: var(--bg-0);
  font-size: 11px;
  font-weight: 800;
}
.selection-label.focused { background: var(--warning); }
```

- [x] **Step 5: Run the canvas behavior test**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlRendersStudioCanvasModesAndNavigation
```

Expected: pass.

- [x] **Step 6: Commit canvas behavior**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat: add studio canvas modes"
```

## Task 5: Rebuild The Inspector As Mode-Aware Studio Workspace

**Files:**
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add inspector mode test**

Add this test:

```kotlin
@Test
fun consoleHtmlUsesModeAwareStudioInspector() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("function renderComposerInspector"))
    assertTrue(html.contains("function renderDraftInspector"))
    assertTrue(html.contains("inspectorTitle.textContent = 'Composer'"))
    assertTrue(html.contains("inspectorTitle.textContent = 'Draft'"))
    assertTrue(html.contains("Add to Pending"))
    assertTrue(html.contains("Focus</button>"))
    assertTrue(html.contains("Delete</button>"))
    assertFalse(html.contains("Severity"))
    assertFalse(html.contains("Status"))
    assertFalse(html.contains("Label field"))
}
```

- [x] **Step 2: Add missing DOM references**

Add references next to the existing element lookups:

```javascript
const inspectorTitle = document.getElementById('inspectorTitle');
const inspectorCount = document.getElementById('inspectorCount');
const inspectorBody = document.getElementById('inspectorBody');
const inspectorFooter = document.getElementById('inspectorFooter');
```

- [x] **Step 3: Implement composer inspector**

Move selection summary, comment, and pending items into a focused add-flow workspace:

```javascript
function renderComposerInspector() {
  inspectorTitle.textContent = 'Composer';
  inspectorCount.textContent = String(pendingFeedbackItems.length);
  selectionSummary.hidden = false;
  comment.hidden = false;
  pendingItems.hidden = false;
  draftItems.hidden = true;
  clearSelectionButton.hidden = false;
  cancelAddFlowButton.hidden = false;
  addItemButton.hidden = false;
  clearDraftButton.hidden = true;
  renderPendingItems();
}
```

- [x] **Step 4: Implement draft inspector**

Make saved evidence groups the idle hero surface:

```javascript
function renderDraftInspector() {
  const groups = savedEvidenceGroups();
  inspectorTitle.textContent = 'Draft';
  inspectorCount.textContent = String(groups.reduce((sum, group) => sum + group.items.length, 0));
  selectionSummary.hidden = true;
  comment.hidden = true;
  pendingItems.hidden = true;
  draftItems.hidden = false;
  clearSelectionButton.hidden = true;
  cancelAddFlowButton.hidden = true;
  addItemButton.hidden = true;
  clearDraftButton.hidden = groups.length === 0;
  renderSavedEvidenceGroups();
}
```

- [x] **Step 5: Make saved evidence groups expanded cards**

Replace `details` with always-visible cards:

```javascript
function renderSavedEvidenceGroups() {
  draftItems.innerHTML = savedEvidenceGroups().map(group => {
    const screen = findScreen(group.screenId);
    return '<article class="evidence-card">' +
      '<div class="evidence-card-head">' +
        '<strong>' + escapeHtml(screen?.displayName || 'Saved evidence') + '</strong>' +
        '<span>' + group.items.length + ' item' + (group.items.length === 1 ? '' : 's') + ' · screenshot attached</span>' +
      '</div>' +
      '<div class="saved-evidence-preview" data-screen-id="' + escapeHtml(group.screenId) + '"></div>' +
      group.items.map((item, index) =>
        '<div class="row evidence-item-row">' +
          '<strong>' + escapeHtml(formatSavedEvidenceItemLabel(item, index)) + '</strong>' +
          '<span>' + escapeHtml(targetLabel(item)) + ' · ' + escapeHtml(sourceHintLabel(item)) + '</span>' +
        '</div>'
      ).join('') +
    '</article>';
  }).join('') || '<div class="empty-state"><div class="empty-title">No draft feedback items.</div><div class="empty-body">Use Add to freeze a preview, add pending items, then Save.</div></div>';
  hydrateSavedEvidencePreviews();
}
```

Then move the existing `.saved-evidence-preview` hydration code into:

```javascript
function hydrateSavedEvidencePreviews() {
  draftItems.querySelectorAll('.saved-evidence-preview').forEach(container => {
    const screenId = container.dataset.screenId;
    const group = savedEvidenceGroups().find(candidate => candidate.screenId === screenId);
    const screen = findScreen(screenId);
    if (!screen?.screenshot?.desktopFullPath || !group) {
      container.textContent = 'Evidence: screenshot attached';
      return;
    }
    container.innerHTML =
      '<div class="saved-evidence-frame">' +
        '<img alt="Saved evidence screenshot" src="/api/screens/' + encodeURIComponent(screenId) + '/screenshot/full">' +
        '<div class="selection-overlay" aria-hidden="true"></div>' +
      '</div>';
    const image = container.querySelector('img');
    const overlay = container.querySelector('.selection-overlay');
    image.addEventListener('load', () => renderSavedEvidenceOverlay(overlay, image, group.items), { once: true });
  });
}
```

- [x] **Step 6: Add evidence card CSS**

```css
.evidence-card {
  display: grid;
  gap: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px;
  background: var(--bg-1);
}
.evidence-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.evidence-card-head strong { font-size: 13px; color: var(--txt-0); }
.evidence-card-head span { font-size: 11px; color: var(--txt-2); }
.saved-evidence-frame {
  position: relative;
  overflow: hidden;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: var(--bg-2);
}
.saved-evidence-frame img {
  display: block;
  width: 100%;
  height: auto;
  border: 0;
  border-radius: 0;
}
.empty-state {
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  min-height: 220px;
  color: var(--txt-2);
  text-align: center;
}
.empty-title { color: var(--txt-0); font-size: 13px; font-weight: 700; }
.empty-body { max-width: 240px; font-size: 12px; line-height: 1.5; }
```

- [x] **Step 7: Run the inspector test**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlUsesModeAwareStudioInspector
```

Expected: pass.

- [x] **Step 8: Commit inspector workspace**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat: add mode-aware studio inspector"
```

## Task 6: Add Studio Session History And Sent Drawer

**Files:**
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Add session history test**

Add this test:

```kotlin
@Test
fun consoleHtmlRendersStudioSessionHistoryWithoutInternalIds() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("function renderSessionsList"))
    assertTrue(html.contains("sessionCount.textContent"))
    assertTrue(html.contains("class=\"row session-row"))
    assertTrue(html.contains("class=\"sent-history-drawer\""))
    assertTrue(html.contains("formatSessionSummary(session)"))
    assertFalse(html.contains("shortId(session.sessionId)"))
    assertFalse(html.contains("shortId(screen.screenId)"))
    assertFalse(html.contains("shortId(batch.batchId)"))
}
```

- [ ] **Step 2: Split session and sent rendering**

Move code out of `render()` into these functions:

```javascript
function renderSessionsListFromPayload(sessionSummaries) {
  const activeId = state.session?.sessionId;
  sessionCount.textContent = String(sessionSummaries.length);
  sessions.innerHTML = sessionSummaries.map((session, index) =>
    '<button class="row session-row ' + (session.sessionId === activeId ? 'active' : '') + '" data-session-id="' + escapeHtml(session.sessionId) + '">' +
      '<strong>' + escapeHtml(formatSessionLabel(session, index)) + '</strong>' +
      '<span>' + escapeHtml(formatSessionSummary(session)) + '</span>' +
    '</button>'
  ).join('') || '<div class="row"><span>No saved sessions.</span></div>';
  document.querySelectorAll('.session-row').forEach(row => {
    row.addEventListener('click', () => openSession(row.dataset.sessionId).catch(showError));
  });
}

function renderSentHistory() {
  const session = state.session;
  const allItems = session?.items || [];
  const sentItems = allItems.filter(item => item.delivery === 'sent');
  const handoffBatches = session ? session.handoffBatches || [] : [];
  const batchIds = new Set(handoffBatches.map(batch => batch.batchId));
  const batchedItemIds = new Set(handoffBatches.flatMap(batch => batch.itemIds || []));
  const batchRows = handoffBatches.map(batch => {
    const items = batchItems(batch);
    return '<div class="row">' +
      '<strong>' + escapeHtml(formatBatchLabel(batch)) + '</strong>' +
      '<span>' + escapeHtml(formatBatchDetails(batch, items)) + '</span>' +
    '</div>';
  });
  const unbatchedRows = sentItems
    .filter(item => !item.handoffBatchId || !batchIds.has(item.handoffBatchId) || !batchedItemIds.has(item.itemId))
    .map(item => {
      const label = item.handoffBatchId ? 'Missing batch metadata' : 'Unbatched sent item';
      return '<div class="row">' +
        '<strong>' + escapeHtml(label) + '</strong>' +
        '<span>' + escapeHtml(firstLine(item.comment || '(No comment)')) + ' · Not sent</span>' +
      '</div>';
    });
  sentHistory.innerHTML = batchRows.concat(unbatchedRows).join('') || '<div class="row"><span>No sent handoff history.</span></div>';
}
```

Then update `refreshSessions()`:

```javascript
async function refreshSessions() {
  const response = await requestJson('/api/sessions');
  renderSessionsListFromPayload(response.sessions || []);
}
```

- [ ] **Step 3: Add drawer CSS**

```css
.sent-history-drawer {
  border-top: 1px solid var(--line);
  padding: 8px;
}
.sent-history-drawer summary {
  cursor: pointer;
  color: var(--txt-2);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .14em;
  text-transform: uppercase;
  padding: 8px 4px;
}
.session-row {
  display: block;
  width: 100%;
  min-height: 0;
  text-align: left;
}
```

- [ ] **Step 4: Run the session history test**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlRendersStudioSessionHistoryWithoutInternalIds
```

Expected: pass.

- [ ] **Step 5: Commit session history**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat: style studio session history"
```

## Task 7: Add Keyboard And Accessibility Polish Within Existing Scope

**Files:**
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Add keyboard/accessibility test**

Add this test:

```kotlin
@Test
fun consoleHtmlAddsStudioKeyboardAndAccessibilityGuards() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("function isTextInputFocused"))
    assertTrue(html.contains("function handleGlobalShortcut"))
    assertTrue(html.contains("event.key === 'Escape'"))
    assertTrue(html.contains("event.key.toLowerCase() === 'a'"))
    assertTrue(html.contains("event.key.toLowerCase() === 's'"))
    assertTrue(html.contains("event.key.toLowerCase() === 'n'"))
    assertTrue(html.contains("document.addEventListener('keydown', handleGlobalShortcut)"))
    assertTrue(html.contains("role=\"status\" aria-live=\"polite\""))
    assertTrue(html.contains("aria-label=\"PointPatch preview\""))
}
```

- [ ] **Step 2: Add guarded global shortcuts**

Add these functions before event listener registration:

```javascript
function isTextInputFocused() {
  const tag = document.activeElement?.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || document.activeElement?.isContentEditable;
}

function handleGlobalShortcut(event) {
  if (isTextInputFocused()) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    if (addItemsFlow) {
      cancelAddItemsFlow();
    } else {
      clearSelection();
    }
    return;
  }
  if (event.key.toLowerCase() === 'a' && !event.metaKey && !event.ctrlKey && !event.altKey) {
    event.preventDefault();
    startAddItemsFlow().catch(showError);
    return;
  }
  if (event.key.toLowerCase() === 's' && (event.metaKey || event.ctrlKey)) {
    event.preventDefault();
    savePendingFeedbackItems().catch(showError);
    return;
  }
  if (event.key.toLowerCase() === 'n' && (event.metaKey || event.ctrlKey)) {
    event.preventDefault();
    newSession().catch(showError);
  }
}
```

Register it:

```javascript
document.addEventListener('keydown', handleGlobalShortcut);
```

- [ ] **Step 3: Add accessible preview and pending labels**

In `renderPendingItems()`, include ARIA labels:

```javascript
'<button type="button" aria-label="Focus pending item ' + (index + 1) + '" data-focus-pending="' + index + '">Focus</button>' +
'<button type="button" aria-label="Delete pending item ' + (index + 1) + '" data-delete-pending="' + index + '">Delete</button>'
```

In `ensurePreviewFrame()`, use:

```javascript
'<img id="snapshotImage" alt="PointPatch preview" aria-label="PointPatch preview">' +
```

- [ ] **Step 4: Run keyboard/accessibility test**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlAddsStudioKeyboardAndAccessibilityGuards
```

Expected: pass.

- [ ] **Step 5: Commit accessibility polish**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat: add studio console shortcuts"
```

## Task 8: Verify Full Console Contracts

**Files:**
- Modify only if tests expose a real regression.

- [ ] **Step 1: Run all console server tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: pass. If an older string-contract test fails because it expects the old light layout, update only that assertion to the Studio equivalent while keeping the behavioral contract.

- [ ] **Step 2: Run session/service tests that protect persistence and handoff**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest
```

Expected: pass. No persisted JSON schema, preview save, or handoff behavior should change.

- [ ] **Step 3: Run the required broader tests**

Run:

```bash
./gradlew :pointpatch-mcp:test :pointpatch-cli:test :pointpatch-compose-core:test
```

Expected: pass.

- [ ] **Step 4: Run install distributions**

Run:

```bash
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
```

Expected: pass.

- [ ] **Step 5: Run diff whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 6: Commit verification-only test updates if any**

If Step 1 required test assertion updates, commit them:

```bash
git add pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "test: update console studio assertions"
```

If no files changed, do not create a commit for this task.

## Task 9: Manual Browser And Connected-Device Smoke

**Files:**
- Modify only if smoke exposes a real UI bug.

- [ ] **Step 1: Rebuild runnable distributions**

Run:

```bash
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
```

Expected: pass.

- [ ] **Step 2: Check connected devices**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk PATH=/Users/kws/Library/Android/sdk/platform-tools:$PATH /Users/kws/Library/Android/sdk/platform-tools/adb devices -l
```

Expected when available: a `device` row for `SM_G986N` or another usable Android device. If the output has no device, `offline`, or `unauthorized`, record the raw output and skip only connected-device smoke.

- [ ] **Step 3: Launch the console**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk PATH=/Users/kws/Library/Android/sdk/platform-tools:$PATH ./pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.github.pointpatch.sample
```

Expected: CLI prints a local console URL and `sidekick: connected` when the device is usable.

- [ ] **Step 4: Browser smoke the idle Studio UI**

Open the printed URL and verify:

- Studio dark top bar and 3-column body render.
- `Refresh | Add | Save | Copy | Send | New | Close` are visible with short labels.
- Idle preview badge reads `Live` after a preview exists.
- Idle preview click still sends one-step tap navigation.
- Right inspector shows Draft/evidence state, not the composer.
- Live preview refresh does not flicker Draft/evidence cards.

- [ ] **Step 5: Browser smoke the Add flow**

Verify:

- Clicking `Add` freezes the current preview and hides navigation controls.
- Badge changes to `Frozen`.
- Selecting a component or dragging an area updates `Current Selection`.
- Comment plus `Add to Pending` creates numbered overlay `#1` and pending row `#1`.
- Adding a second pending item creates matching `#2` overlay and row.
- `Focus` highlights the matching overlay.
- `Delete` removes only that pending row and renumbers remaining overlays and rows.
- No edit/severity/status UI appears for pending items.

- [ ] **Step 6: Browser smoke Save, Copy, Send**

Verify:

- `Save` persists one evidence snapshot for all current pending items.
- Right inspector returns to Draft and shows an expanded evidence card with screenshot, numbered overlay, and comments.
- Starting `Add` again creates a new frozen work item and later a new evidence snapshot, even on the same screen.
- `Copy` copies compact Markdown.
- `Send` creates a handoff batch and moves draft items out of Draft.

- [ ] **Step 7: Commit smoke bug fixes if any**

If smoke required code fixes, run the targeted test from the affected task, then commit:

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "fix: polish studio console smoke issues"
```

If no files changed, do not create a commit for this task.

## Task 10: Documentation Update

**Files:**
- Modify: `docs/design-feedback-console-ux.md`
- Modify if needed: `docs/troubleshooting.md`

- [ ] **Step 1: Update the UX status document**

Replace the current workflow summary in `docs/design-feedback-console-ux.md` with:

```markdown
# Feedback Console UX Status

**Status:** Current Studio UI, based on the Option A redesign.
**Current version:** V1.2
**Related module:** `pointpatch-mcp`

The shipped feedback console is a local-first, MCP-owned Studio UI for Android preview feedback. The browser UI uses a dark three-panel workspace: Sessions on the left, live/frozen preview canvas in the center, and a mode-aware Inspector on the right.

## Current Workflow

1. Select a connected ADB device.
2. Use the live preview normally; preview clicks navigate the app.
3. Click `Add` to freeze the latest preview for feedback.
4. Select a Compose target or drag a visual area and write a comment.
5. Click `Add to Pending`; numbered pending rows and preview overlays stay in sync.
6. Repeat on the same frozen preview as needed.
7. Click `Save` once to persist one evidence snapshot and all pending items.
8. Review saved evidence groups in the Inspector Draft view.
9. Click `Copy` for compact Markdown or `Send` to create a local handoff batch for MCP tools.

## Current Constraints

- There is no Select/Navigate toggle. Idle preview clicks navigate; `Add` enters frozen feedback mode.
- Pending items support `Focus` and `Delete` only.
- Live preview frames are transient and are not added to `FeedbackSession.screens`.
- `Save` promotes exactly one frozen preview into one evidence snapshot for all pending items in that frozen work set.
- `Copy` and `Send` are session-level actions.
```

- [ ] **Step 2: Check troubleshooting docs**

Run:

```bash
rg -n "Select/Navigate|modeSelect|modeNavigate|Refresh Preview|Copy Agent Context|Send Draft|light layout|queue-pane|Draft" docs README.md
```

Expected: any references to removed old UI labels are reviewed. Update only text that now conflicts with the Studio UI.

- [ ] **Step 3: Run docs verification**

Run:

```bash
rg -n "modeSelect|modeNavigate|Select/Navigate|Copy Agent Context|Send Draft to Agent|queue-pane" docs README.md
git diff --check
```

Expected: no stale UI references unless they are historical notes clearly marked as old/superseded; `git diff --check` has no output.

- [ ] **Step 4: Commit docs**

```bash
git add docs/design-feedback-console-ux.md docs/troubleshooting.md
git commit -m "docs: describe studio feedback console"
```

If `docs/troubleshooting.md` did not change, omit it from `git add`.

## Final Verification

Run all final checks before declaring completion:

```bash
./gradlew :pointpatch-mcp:test :pointpatch-cli:test :pointpatch-compose-core:test
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
git diff --check
```

Expected: all Gradle commands pass and `git diff --check` produces no output.

If a connected Android device is available, also run the connected smoke in Task 9. If no device is available or it is offline/unauthorized, record the raw `adb devices -l` output and skip only the connected-device smoke.

## Self-Review Notes

- Spec coverage: Option A layout, history, canvas, inspector, click/drag annotation creation, save/restore semantics, responsive/accessibility, tokens, and QA expectations are mapped to tasks.
- PointPatch contract coverage: live preview, frozen preview, pending Focus/Delete, Save one snapshot/N items, Copy/Send, MCP persistence, no Select/Navigate toggle, and no pending edit are preserved.
- Type consistency: the plan uses existing JavaScript names where possible: `addItemsFlow`, `pendingFeedbackItems`, `currentSelection`, `state.preview`, `state.session`, `renderSelectionOverlay`, `renderNumberedFeedbackOverlay`, `savePendingFeedbackItems`.
- Scope boundary: no server API, persistence schema, external AI API, network mocking, arbitrary typing, cloud sync, or multi-user feature is introduced.
