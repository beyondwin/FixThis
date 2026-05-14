# Compact Recovery Stale Banner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current full-width canvas stale warning with a compact recovered-draft banner that does not crowd the preview or collide with other console messages.

**Architecture:** Keep the existing stale-frame state and `Use latest frame` action, but change its presentation from a large warning strip to a compact canvas status banner. Global build/protocol staleness remains in the top sticky banner, pending recovery remains in the inspector, and interaction-blocked overlay remains highest priority inside the canvas.

**Tech Stack:** Vanilla console JavaScript, static `styles.css`, Playwright responsive stress harness, Node test runner.

---

### Task 1: Failing Visual Contract Test

**Files:**
- Modify: `scripts/console-responsive-stress.mjs`

- [ ] **Step 1: Add a compact stale notice fixture**

In `injectStressState`, append `#canvasStaleNotice` visible with recovered-draft copy:

```js
const staleNotice = document.getElementById('canvasStaleNotice');
staleNotice.hidden = false;
staleNotice.querySelector('[data-stale-title]').textContent = 'Recovered draft';
staleNotice.querySelector('[data-stale-detail]').textContent = 'Live preview is paused for this frozen frame.';
staleNotice.querySelector('[data-use-latest]').textContent = 'Use latest frame';
```

- [ ] **Step 2: Add an assertion that the stale notice is compact and non-overlapping**

Add `assertCanvasStaleNoticeIsCompact(page, viewportName)` that verifies:

```js
const noticeBox = notice.getBoundingClientRect();
const frameBox = frame.getBoundingClientRect();
const toolbarBox = document.querySelector('.canvas-toolbar').getBoundingClientRect();
return {
  tooTall: noticeBox.height > 76,
  outsideFrame: noticeBox.left < frameBox.left - 1 || noticeBox.right > frameBox.right + 1,
  overlapsToolbar: noticeBox.top < toolbarBox.bottom - 1,
  textOverflow: copy.scrollWidth - copy.clientWidth,
  actionsOverflow: action.scrollWidth - action.clientWidth,
};
```

- [ ] **Step 3: Run the test and confirm RED**

Run: `npm run console:responsive:stress`

Expected: FAIL because the current `.canvas-stale` is full-width over the canvas and has no `[data-stale-title]` / `[data-stale-detail]` structure.

### Task 2: Compact Banner Implementation

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/console/rendering.js`

- [ ] **Step 1: Update stale notice markup**

Change `#canvasStaleNotice` to include structured title/detail/action slots:

```html
<div id="canvasStaleNotice" class="canvas-stale" role="status" aria-live="polite" hidden>
  <div class="canvas-stale__copy">
    <strong data-stale-title>Recovered draft</strong>
    <span data-stale-detail>Live preview is paused for this frozen frame.</span>
  </div>
  <button type="button" data-use-latest>Use latest frame</button>
</div>
```

- [ ] **Step 2: Render recovered-draft copy and respect blocked-overlay priority**

Update `renderStaleFrameNotice` so it hides the stale notice when `state.connection.interactionBlockedReason` is present, then fills the title/detail slots before showing the banner.

- [ ] **Step 3: Replace the full-width warning CSS**

Style `.canvas-stale` as a compact banner anchored inside the canvas, with wrapping text, stable button sizing, and responsive stacking below narrow widths.

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run:

```bash
npm run console:responsive:stress
npm run console:pending:test
node scripts/build-console-assets.mjs --check
```

Expected: responsive stress and pending recovery tests pass; build check fails only if generated `app.js` is stale.

### Task 3: Rebuild Generated Console Assets

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/main/resources/console/app.js.map`
- Modify: `fixthis-mcp/src/main/resources/console/console-build-meta.json`

- [ ] **Step 1: Rebuild assets**

Run: `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs`

- [ ] **Step 2: Verify generated assets are current**

Run:

```bash
node scripts/build-console-assets.mjs --check
npm run console:responsive:stress
npm run console:pending:test
```

Expected: all pass.
