# Responsive Error and Agent Feedback UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the FixThis feedback console resilient at narrow widths and make error, warning, and AI-agent feedback states readable, explicit, and regression-tested.

**Architecture:** Keep the console as a vanilla JS/CSS single-page app. Use the existing generated bundle path (`fixthis-mcp/src/main/console/*.js` -> `fixthis-mcp/src/main/resources/console/app.js`) and extend the Playwright smoke/stress harnesses so layout regressions fail automatically. Backend changes are limited to the persisted annotation lock policy for terminal agent states; Android changes are limited to adaptive sample rows and the debug overlay pill.

**Tech Stack:** Vanilla JS, vanilla CSS, Node.js, Playwright, Kotlin/JUnit, Robolectric, Jetpack Compose Material3

---

## Companion Spec

Full product/design spec:

- `docs/superpowers/specs/2026-05-13-responsive-error-agent-feedback-ux-detailed-spec.md`

Audit screenshots used as visual references:

- `output/playwright/fixthis-desktop-1280-error-agent.png`
- `output/playwright/fixthis-compact-1024-error-agent.png`
- `output/playwright/fixthis-breakpoint-900-error-agent.png`
- `output/playwright/fixthis-mobile-390-error-agent.png`

## File Structure

Console source of truth:

- Modify `fixthis-mcp/src/main/resources/console/index.html` for the global status surface placement.
- Modify `fixthis-mcp/src/main/resources/console/styles.css` for responsive shell, status variants, warning cards, agent banners, and long-token wrapping.
- Modify `fixthis-mcp/src/main/console/state.js` for status helpers.
- Modify `fixthis-mcp/src/main/console/main.js` for `showError`.
- Modify `fixthis-mcp/src/main/console/prompt.js` for Copy Prompt partial-failure warning.
- Modify `fixthis-mcp/src/main/console/annotations.js` for lifecycle phase and labels.
- Modify `fixthis-mcp/src/main/console/rendering.js` for agent banners and activity drift copy.
- Modify generated `fixthis-mcp/src/main/resources/console/app.js` only by running `node scripts/build-console-assets.mjs`.

Console verification:

- Modify `scripts/console-browser-smoke.mjs` for better ready diagnostics and agent-state assertions.
- Create `scripts/console-responsive-stress.mjs` for static responsive/error/agent overflow stress checks.
- Modify `package.json` to add `console:responsive:stress`.

MCP session policy:

- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` so `WONT_FIX` is edit-locked.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` to cover `NEEDS_CLARIFICATION` editable and `WONT_FIX` locked behavior.

Android sample and overlay:

- Modify `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt`.
- Modify `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/FeedbackCard.kt`.
- Modify `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/InfoRow.kt`.
- Modify `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ProjectScreen.kt`.
- Modify `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ReviewScreen.kt`.
- Modify `sample/src/androidTest/java/io/github/beyondwin/fixthis/sample/SampleAppSmokeTest.kt`.
- Modify `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayout.kt`.
- Modify `fixthis-compose-sidekick/src/test/kotlin/io/github/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayoutTest.kt`.

## Conventions

- Do not hand-edit `fixthis-mcp/src/main/resources/console/app.js`; regenerate it after JS edits:

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  ```

- Keep commits small and in the task order below.
- Browser tests write screenshots to `output/playwright/`; do not commit those generated PNG files.
- The plan assumes current branch `main`. If executing in a separate workspace, create it with `codex/` branch prefix unless the user asks otherwise.

---

## Task 1: Stabilize Console Smoke Diagnostics

**Files:**

- Modify: `scripts/console-browser-smoke.mjs`

- [ ] **Step 1: Run the current smoke and capture the failure**

  ```bash
  npm run console:smoke
  ```

  Expected before the fix if the audited failure is still present:

  ```text
  BROWSER_SMOKE_FAILED: page.waitForFunction: Timeout 30000ms exceeded.
  ```

  If it already passes, still complete the diagnostic hardening below so future failures identify the stuck state.

- [ ] **Step 2: Replace `waitForReady` with a diagnostic version**

  In `scripts/console-browser-smoke.mjs`, replace the existing `waitForReady(page)` function with this exact implementation:

  ```js
  async function readyDiagnostics(page) {
    return await page.evaluate(() => {
      const card = document.getElementById('connectionCard');
      const image = document.getElementById('snapshotImage');
      return {
        connectionState: card?.dataset.connectionState || null,
        connectionText: card?.textContent?.replace(/\s+/g, ' ').trim() || null,
        imagePresent: Boolean(image),
        imageComplete: Boolean(image?.complete),
        naturalWidth: image?.naturalWidth || 0,
        naturalHeight: image?.naturalHeight || 0,
        deviceName: document.getElementById('deviceName')?.textContent || null,
        error: document.getElementById('error')?.textContent || null,
      };
    });
  }

  async function waitForReady(page) {
    try {
      await page.waitForFunction(() => {
        const card = document.getElementById('connectionCard');
        const image = document.getElementById('snapshotImage');
        return card?.dataset.connectionState === 'ready' &&
          image &&
          image.complete &&
          image.naturalWidth > 0 &&
          image.naturalHeight > 0;
      }, { timeout: 15_000 });
    } catch (error) {
      const diagnostics = await readyDiagnostics(page);
      throw new Error('Console did not reach ready preview state: ' + JSON.stringify(diagnostics));
    }
  }
  ```

- [ ] **Step 3: Add a real mark-handed-off fake endpoint**

  In `handleApi`, immediately before the `/api/session/open` branch, add this endpoint so Copy Prompt can exercise the success path instead of relying on the current silent 404:

  ```js
  if (request.method === 'POST' && url.pathname.match(/^\/api\/sessions\/[^/]+\/items\/mark-handed-off$/)) {
    const body = await readJsonBody(request);
    const sessionId = decodeURIComponent(url.pathname.split('/')[3]);
    const session = fake.sessions.get(sessionId) || currentSession();
    const ids = new Set(Array.isArray(body.itemIds) ? body.itemIds : []);
    session.items = session.items.map(item => ids.has(item.itemId)
      ? {
          ...item,
          delivery: 'sent',
          lastHandedOffAtEpochMillis: now + 31_000,
          updatedAtEpochMillis: now + 31_000,
        }
      : item);
    session.updatedAtEpochMillis = now + 31_000;
    return jsonResponse(response, session);
  }
  ```

- [ ] **Step 4: Run smoke again**

  ```bash
  npm run console:smoke
  ```

  Expected after the fix:

  ```text
  Console browser smoke passed.
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add scripts/console-browser-smoke.mjs
  git commit -m "test(console): improve smoke ready diagnostics"
  ```

---

## Task 2: Add Responsive Stress Test and Fix Console Shell Overflow

**Files:**

- Create: `scripts/console-responsive-stress.mjs`
- Modify: `package.json`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`

- [ ] **Step 1: Create the failing responsive stress script**

  Create `scripts/console-responsive-stress.mjs` with this complete content:

  ```js
  #!/usr/bin/env node
  import assert from 'node:assert/strict';
  import { mkdirSync, readFileSync } from 'node:fs';
  import http from 'node:http';
  import { dirname, resolve } from 'node:path';
  import { fileURLToPath, pathToFileURL } from 'node:url';

  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const outputDir = resolve(root, 'output/playwright');
  mkdirSync(outputDir, { recursive: true });

  const indexHtml = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/index.html'), 'utf8');
  const stylesCss = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');
  const pageHtml = indexHtml
    .replace('<!-- FIXTHIS_STYLES -->', `<style>${stylesCss}</style>`)
    .replace('<!-- FIXTHIS_SCRIPT -->', '<script>window.FixThisConsoleConfig = { consoleToken: "stress" };</script>');

  const viewports = [
    { name: 'mobile-390', width: 390, height: 844 },
    { name: 'breakpoint-900', width: 900, height: 900 },
    { name: 'compact-1024', width: 1024, height: 768 },
    { name: 'desktop-1280', width: 1280, height: 800 },
  ];

  function textResponse(response, value, status = 200, contentType = 'text/html; charset=utf-8') {
    response.writeHead(status, {
      'content-type': contentType,
      'cache-control': 'no-store',
    });
    response.end(value);
  }

  function createServer() {
    return http.createServer((request, response) => {
      const url = new URL(request.url || '/', 'http://127.0.0.1');
      if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
        return textResponse(response, pageHtml);
      }
      return textResponse(response, 'Not found', 404, 'text/plain; charset=utf-8');
    });
  }

  async function loadPlaywright() {
    try {
      return await import('playwright');
    } catch (directImportError) {
      for (const pathEntry of (process.env.PATH || '').split(':')) {
        if (!pathEntry.endsWith('/node_modules/.bin')) continue;
        const candidate = resolve(pathEntry, '..', 'playwright/index.mjs');
        try {
          return await import(pathToFileURL(candidate).href);
        } catch (_) {
          // Keep scanning package-manager injected node_modules paths.
        }
      }
      throw directImportError;
    }
  }

  async function injectStressState(page) {
    await page.evaluate(() => {
      const longPath = '/Users/kws/source/android/FixThis/sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt:63:VeryLongComposableNameWithoutNaturalBreakpoints'.repeat(2);
      const stalenessBanner = document.getElementById('stalenessBanner');
      stalenessBanner.hidden = false;
      stalenessBanner.dataset.severity = 'critical';
      stalenessBanner.querySelector('[data-headline]').textContent = 'Build output is stale';
      stalenessBanner.querySelector('[data-detail]').textContent = longPath;

      const connectionCard = document.getElementById('connectionCard');
      connectionCard.dataset.connectionState = 'reconnect';
      document.getElementById('connectionHeadline').textContent = 'Connection paused';
      document.getElementById('connectionMessage').textContent = 'Could not connect to FixThis bridge while preserving the current annotation workspace.';
      document.getElementById('connectionDetails').open = true;
      document.getElementById('connectionDetailsBody').textContent =
        'Bridge timed out while reading current screen. Raw detail: ' + longPath;

      document.getElementById('deviceName').textContent = 'Pixel Fold Extremely Long Device Name';
      document.getElementById('deviceConnectionState').textContent = 'Unavailable because bridge timed out';
      document.getElementById('error').textContent =
        'Copied, but MCP handoff status was not updated. Copy again after the connection recovers to update item lifecycle metadata. ' + longPath;

      document.getElementById('pendingItems').innerHTML =
        '<div class="activity-drift-warning" role="status" aria-live="polite" data-activity-drift>' +
          '<div class="activity-drift-warning-body">' +
            '<div class="activity-drift-warning-title">Activity changed during freeze</div>' +
            '<div class="activity-drift-warning-detail">Frozen: io.github.beyondwin.fixthis.sample.MainActivity · Now: io.github.beyondwin.fixthis.sample.DeepLinkReviewActivityWithLongName</div>' +
          '</div>' +
          '<button type="button" class="activity-drift-warning-button" data-activity-drift-restart>Start new freeze</button>' +
        '</div>';

      document.getElementById('draftItems').innerHTML =
        '<div class="ann-list">' +
          '<button type="button" class="ann-row saved-item-row" data-phase="in_progress">' +
            '<span class="ann-row-num">1</span><span class="ann-row-body"><span class="ann-row-title">Agent working</span><span class="ann-row-comment">Agent note should wrap and remain readable.</span></span><span class="ann-row-status st-in-progress">In Progress</span>' +
          '</button>' +
          '<button type="button" class="ann-row saved-item-row" data-phase="needs_clarification">' +
            '<span class="ann-row-num">2</span><span class="ann-row-body"><span class="ann-row-title">Question for user</span><span class="ann-row-comment">Needs clarification should not look resolved.</span></span><span class="ann-row-status st-needs-clarification">Needs Clarification</span>' +
          '</button>' +
          '<button type="button" class="ann-row saved-item-row" data-phase="wont_fix">' +
            '<span class="ann-row-num">3</span><span class="ann-row-body"><span class="ann-row-title">Won\\'t fix</span><span class="ann-row-comment">Terminal but not successful.</span></span><span class="ann-row-status st-wont-fix">Won\\'t Fix</span>' +
          '</button>' +
        '</div>' +
        '<div class="annotation-detail" data-phase="resolved">' +
          '<div class="annotation-banner annotation-banner-done">' +
            '<div>Agent completed</div>' +
            '<pre class="annotation-summary">' + longPath + '</pre>' +
          '</div>' +
        '</div>';
    });
  }

  async function assertNoHorizontalOverflow(page, viewportName) {
    const failures = await page.evaluate(() => {
      const selectors = [
        'html',
        'body',
        '.studio-shell',
        '.studio-topbar',
        '.studio-context',
        '.studio-actions',
        '.studio-body',
        '#error',
        '#connectionDetailsBody',
        '#stalenessBanner',
        '.annotation-summary',
        '.activity-drift-warning',
      ];
      return selectors.flatMap(selector => Array.from(document.querySelectorAll(selector)).map(element => {
        const overflow = element.scrollWidth - element.clientWidth;
        return {
          selector,
          overflow,
          clientWidth: element.clientWidth,
          scrollWidth: element.scrollWidth,
        };
      })).filter(result => result.overflow > 1);
    });
    assert.deepEqual(failures, [], `${viewportName} has horizontal overflow`);
  }

  async function run(baseUrl) {
    const { chromium } = await loadPlaywright();
    const browser = await chromium.launch({ headless: true });
    try {
      for (const viewport of viewports) {
        const page = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height } });
        await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
        await injectStressState(page);
        await page.screenshot({
          path: resolve(outputDir, `fixthis-responsive-stress-${viewport.name}.png`),
          fullPage: true,
        });
        await assertNoHorizontalOverflow(page, viewport.name);
        await page.close();
      }
    } finally {
      await browser.close();
    }
  }

  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  try {
    await run(`http://127.0.0.1:${port}/`);
    console.log('Console responsive stress passed.');
  } catch (error) {
    console.error(`CONSOLE_RESPONSIVE_STRESS_FAILED: ${error?.message || error}`);
    process.exitCode = 1;
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
  ```

- [ ] **Step 2: Add the npm script**

  In `package.json`, add the script entry after `console:smoke`:

  ```json
  "console:responsive:stress": "node scripts/console-responsive-stress.mjs",
  ```

- [ ] **Step 3: Run the stress script to verify it fails before CSS fixes**

  ```bash
  npm run console:responsive:stress
  ```

  Expected before the CSS fix:

  ```text
  CONSOLE_RESPONSIVE_STRESS_FAILED: mobile-390 has horizontal overflow
  ```

- [ ] **Step 4: Patch responsive shell CSS**

  In `fixthis-mcp/src/main/resources/console/styles.css`, apply these exact CSS changes:

  ```css
  body,
  .studio-shell,
  .studio-topbar,
  .studio-body {
    max-width: 100vw;
  }

  .studio-context,
  .studio-actions,
  .connection-actions,
  .staleness-banner,
  .annotation-summary,
  .activity-drift-warning {
    min-width: 0;
  }
  ```

  Replace the current `@media (max-width: 1099px)` `.studio-context` and `.studio-actions` rules with:

  ```css
  .studio-context {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-start;
    gap: 6px;
  }
  .studio-actions {
    flex-wrap: wrap;
    gap: 6px;
  }
  ```

  Replace the current `@media (max-width: 899px)` `.studio-context` rule with:

  ```css
  .studio-context {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
    gap: 6px;
    overflow: visible;
  }
  ```

  Add this block inside the same `@media (max-width: 899px)` rule:

  ```css
  .studio-actions {
    display: flex;
    justify-content: flex-start;
    flex-wrap: wrap;
    width: 100%;
  }
  .studio-actions button {
    flex: 0 1 auto;
    max-width: 100%;
  }
  ```

- [ ] **Step 5: Run the stress script again**

  ```bash
  npm run console:responsive:stress
  ```

  Expected:

  ```text
  Console responsive stress passed.
  ```

- [ ] **Step 6: Commit**

  ```bash
  git add package.json scripts/console-responsive-stress.mjs fixthis-mcp/src/main/resources/console/styles.css
  git commit -m "fix(console): prevent responsive shell overflow"
  ```

---

## Task 3: Promote `#error` to a Global Status Surface

**Files:**

- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `fixthis-mcp/src/main/resources/console/app.js`

- [ ] **Step 1: Move the status node out of the inspector**

  In `index.html`, insert this immediately after the closing `</section>` for `#connectionCard`:

  ```html
  <p id="error" class="global-status" role="status" aria-live="polite" hidden></p>
  ```

  Remove the old inspector-local node:

  ```html
  <p id="error" class="error" role="status" aria-live="polite"></p>
  ```

- [ ] **Step 2: Replace `.error` CSS with global status variants**

  Replace the current `.error` and `.error.status-success` rules with:

  ```css
  .global-status {
    grid-row: 3;
    min-width: 0;
    margin: 0;
    padding: 10px 16px;
    border-bottom: 1px solid var(--line);
    font-size: 13px;
    line-height: 1.45;
    overflow-wrap: anywhere;
    white-space: normal;
  }
  .global-status[hidden] { display: none; }
  .global-status.status-error {
    background: rgba(242, 109, 109, .12);
    color: #fecaca;
    border-bottom-color: rgba(242, 109, 109, .35);
  }
  .global-status.status-warning {
    background: rgba(230, 180, 90, .14);
    color: #fde68a;
    border-bottom-color: rgba(230, 180, 90, .35);
  }
  .global-status.status-success {
    background: rgba(184, 211, 106, .12);
    color: var(--accent);
    border-bottom-color: rgba(184, 211, 106, .35);
  }
  .global-status.status-info {
    background: rgba(91, 180, 232, .12);
    color: #bfdbfe;
    border-bottom-color: rgba(91, 180, 232, .35);
  }
  ```

  Update `.studio-shell` rows from:

  ```css
  grid-template-rows: 56px auto 1fr;
  ```

  to:

  ```css
  grid-template-rows: 56px auto auto 1fr;
  ```

  Update `.studio-body` from `grid-row: 3;` to:

  ```css
  grid-row: 4;
  ```

  In the `@media (max-width: 899px)` `.studio-shell` rule, change rows from:

  ```css
  grid-template-rows: auto auto 1fr;
  ```

  to:

  ```css
  grid-template-rows: auto auto auto 1fr;
  ```

- [ ] **Step 3: Replace status helper logic in `state.js`**

  Replace the existing `successClearTimeout`, `showSuccess`, and `clearSuccessStatus` block with:

  ```js
  let statusClearTimeout = null;

  function clearStatusTimer() {
    if (statusClearTimeout) {
      clearTimeout(statusClearTimeout);
      statusClearTimeout = null;
    }
  }

  function showStatus(message, { variant = 'info', durationMs = 0, assertive = false } = {}) {
    clearStatusTimer();
    error.textContent = message;
    error.hidden = !message;
    error.className = 'global-status status-' + variant;
    error.setAttribute('role', assertive ? 'alert' : 'status');
    error.setAttribute('aria-live', assertive ? 'assertive' : 'polite');
    if (durationMs > 0) {
      statusClearTimeout = setTimeout(() => {
        error.textContent = '';
        error.hidden = true;
        error.className = 'global-status';
        error.setAttribute('role', 'status');
        error.setAttribute('aria-live', 'polite');
        statusClearTimeout = null;
      }, durationMs);
    }
  }

  function showSuccess(message, durationMs = 2000) {
    showStatus(message, { variant: 'success', durationMs });
  }

  function showWarning(message, durationMs = 0) {
    showStatus(message, { variant: 'warning', durationMs });
  }

  function clearSuccessStatus() {
    clearStatusTimer();
    error.textContent = '';
    error.hidden = true;
    error.className = 'global-status';
    error.setAttribute('role', 'status');
    error.setAttribute('aria-live', 'polite');
  }
  ```

- [ ] **Step 4: Update `showError`**

  Replace `showError` in `main.js` with:

  ```js
  function showError(cause) {
    showStatus(
      friendlyErrorMessage(cause && cause.message ? cause.message : cause),
      { variant: 'error', assertive: true },
    );
  }
  ```

- [ ] **Step 5: Surface Copy Prompt partial failure**

  In `prompt.js`, replace the silent `markItemsHandedOff` catch block:

  ```js
  } catch (markError) {
      // Clipboard write succeeded — silently ignore mark errors.
  }
  ```

  with:

  ```js
  } catch (markError) {
      showWarning('Copied, but MCP handoff status was not updated. Copy again after the connection recovers to update item state.');
  }
  ```

  At the beginning of `copyPrompt()` and `sendAgentPrompt()`, replace `error.textContent = '';` with:

  ```js
  clearSuccessStatus();
  ```

- [ ] **Step 6: Rebuild assets and run checks**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  npm run console:responsive:stress
  npm run console:smoke
  ```

  Expected:

  ```text
  Console responsive stress passed.
  Console browser smoke passed.
  ```

- [ ] **Step 7: Commit**

  ```bash
  git add fixthis-mcp/src/main/resources/console/index.html fixthis-mcp/src/main/resources/console/styles.css fixthis-mcp/src/main/console/state.js fixthis-mcp/src/main/console/main.js fixthis-mcp/src/main/console/prompt.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "feat(console): promote errors to global status surface"
  ```

---

## Task 4: Render Agent Terminal States and Lock `wont_fix`

**Files:**

- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`
- Modify: `scripts/console-browser-smoke.mjs`

- [ ] **Step 1: Add failing backend lock tests**

  In `FeedbackSessionStoreTest.kt`, after `updateDraftItemRejectsSentResolved`, add:

  ```kotlin
  @Test
  fun updateDraftItemAllowsSentNeedsClarification() {
      val clock = MutableClock(1000L)
      val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
      val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
      val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
      val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
      store.addItem(
          session.sessionId,
          AnnotationDto(
              itemId = "pending",
              screenId = screen.screenId,
              createdAtEpochMillis = 0L,
              updatedAtEpochMillis = 0L,
              target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
              comment = "original",
          ),
      )
      store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
      store.updateItemStatus(
          sessionId = session.sessionId,
          itemId = "item-1",
          status = AnnotationStatusDto.NEEDS_CLARIFICATION,
          agentSummary = "Which screen should this apply to?",
      )

      val updated = store.updateDraftItem(
          sessionId = session.sessionId,
          itemId = "item-1",
          label = null,
          severity = null,
          comment = "clarified by user",
          status = null,
      )

      val item = updated.items.single { it.itemId == "item-1" }
      assertEquals("clarified by user", item.comment)
      assertEquals(AnnotationStatusDto.NEEDS_CLARIFICATION, item.status)
  }

  @Test
  fun updateDraftItemRejectsSentWontFix() {
      val clock = MutableClock(1000L)
      val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
      val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
      val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
      val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
      store.addItem(
          session.sessionId,
          AnnotationDto(
              itemId = "pending",
              screenId = screen.screenId,
              createdAtEpochMillis = 0L,
              updatedAtEpochMillis = 0L,
              target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
              comment = "original",
          ),
      )
      store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
      store.updateItemStatus(
          sessionId = session.sessionId,
          itemId = "item-1",
          status = AnnotationStatusDto.WONT_FIX,
          agentSummary = "Outside product scope",
      )

      val ex = assertFailsWith<FeedbackSessionException> {
          store.updateDraftItem(
              sessionId = session.sessionId,
              itemId = "item-1",
              label = null,
              severity = null,
              comment = "should be blocked",
              status = null,
          )
      }
      assertTrue(ex.message!!.startsWith("ITEM_NOT_EDITABLE"))
  }
  ```

- [ ] **Step 2: Run the focused backend test and verify one failure**

  ```bash
  ./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest.updateDraftItemRejectsSentWontFix'
  ```

  Expected before the store fix: failure because `WONT_FIX` is editable.

- [ ] **Step 3: Lock `WONT_FIX` in store edit policy**

  In `FeedbackSessionStore.kt`, replace `isLockedForEdit` with:

  ```kotlin
  private fun isLockedForEdit(item: AnnotationDto): Boolean = item.delivery == FeedbackDelivery.SENT &&
      item.status in setOf(
          AnnotationStatusDto.IN_PROGRESS,
          AnnotationStatusDto.RESOLVED,
          AnnotationStatusDto.WONT_FIX,
      )
  ```

- [ ] **Step 4: Update lifecycle helpers**

  In `annotations.js`, replace `lifecyclePhase`, `statusLabel`, and `statusValueLabel` with:

  ```js
  function lifecyclePhase(item) {
    const status = String(item?.status || 'open').replace('-', '_');
    if (status === 'resolved') return 'resolved';
    if (status === 'wont_fix') return 'wont_fix';
    if (status === 'needs_clarification') return 'needs_clarification';
    if (status === 'in_progress') return 'in_progress';
    if (item?.delivery === 'sent') {
      return item?.staleAfterHandoff ? 'sent_modified' : 'sent';
    }
    return 'draft';
  }

  function statusLabel(item) {
    switch (lifecyclePhase(item)) {
      case 'resolved': return 'Resolved';
      case 'wont_fix': return "Won't Fix";
      case 'needs_clarification': return 'Needs Clarification';
      case 'in_progress': return 'In Progress';
      case 'sent_modified': return 'Sent · Modified';
      case 'sent': return 'Sent';
      default: return 'Draft';
    }
  }

  function statusValueLabel(value) {
    if (value === 'in-progress' || value === 'in_progress') return 'In Progress';
    if (value === 'resolved') return 'Resolved';
    if (value === 'needs-clarification' || value === 'needs_clarification') return 'Needs Clarification';
    if (value === 'wont-fix' || value === 'wont_fix') return "Won't Fix";
    return 'Open';
  }
  ```

- [ ] **Step 5: Update saved detail banner rendering**

  In `rendering.js`, inside `renderSavedAnnotationDetail`, change:

  ```js
  const editable = phase === 'draft' || phase === 'sent' || phase === 'sent_modified';
  const deletable = phase !== 'in_progress' && phase !== 'resolved';
  ```

  to:

  ```js
  const editable = phase === 'draft' || phase === 'sent' || phase === 'sent_modified' || phase === 'needs_clarification';
  const deletable = phase !== 'in_progress' && phase !== 'resolved' && phase !== 'wont_fix';
  ```

  Add these banner branches before the `resolved` branch:

  ```js
  if (phase === 'needs_clarification') {
    const summary = item.agentSummary ? escapeHtml(item.agentSummary) : 'Agent needs more detail before continuing.';
    return '<div class="annotation-banner annotation-banner-question">' +
      '<div>Needs clarification</div>' +
      '<pre class="annotation-summary">' + summary + '</pre>' +
      '<div class="annotation-banner-subtle">Edit the comment, then Re-save or Copy Prompt again.</div>' +
      '</div>';
  }
  if (phase === 'wont_fix') {
    const summary = item.agentSummary ? escapeHtml(item.agentSummary) : '(no reason provided)';
    return '<div class="annotation-banner annotation-banner-wont-fix">' +
      '<div>Won\\'t fix</div>' +
      '<pre class="annotation-summary">' + summary + '</pre>' +
      '</div>';
  }
  ```

  Replace the `in_progress` branch with:

  ```js
  if (phase === 'in_progress') {
    const note = item.agentSummary ? '<pre class="annotation-summary">' + escapeHtml(item.agentSummary) + '</pre>' : '';
    return '<div class="annotation-banner annotation-banner-locked">' +
      '<div>Agent working on this — edits locked.</div>' +
      note +
      '</div>';
  }
  ```

- [ ] **Step 6: Add status and banner CSS**

  In `styles.css`, after the existing `.ann-row-status.st-resolved` block, add:

  ```css
  .ann-row-status.st-needs-clarification {
    background: rgba(230, 180, 90, .22);
    color: #fde68a;
    border: 1px solid rgba(230, 180, 90, .45);
  }
  .ann-row-status.st-needs-clarification::before { content: '? '; }
  .ann-row-status.st-wont-fix {
    background: rgba(156, 163, 175, .18);
    color: #d1d5db;
    border: 1px solid rgba(156, 163, 175, .32);
  }
  .ann-row-status.st-wont-fix::before { content: '× '; }
  .ann-row[data-phase="needs_clarification"] { border-left: 4px solid #e6b45a; }
  .ann-row[data-phase="wont_fix"] { border-left: 4px solid #9ca3af; opacity: 0.85; }
  ```

  Replace `.annotation-summary` with:

  ```css
  .annotation-summary {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    word-break: break-word;
    max-width: 100%;
    max-height: 240px;
    overflow-x: hidden;
    overflow-y: auto;
    margin-top: 8px;
    padding: 8px;
    background: rgba(0, 0, 0, .25);
    border-radius: 4px;
  }
  ```

  Add:

  ```css
  .annotation-banner-question {
    background: rgba(230, 180, 90, .16);
    color: #fde68a;
    border: 1px solid rgba(230, 180, 90, .35);
  }
  .annotation-banner-wont-fix {
    background: rgba(156, 163, 175, .14);
    color: #d1d5db;
    border: 1px solid rgba(156, 163, 175, .28);
  }
  .annotation-banner-subtle {
    margin-top: 8px;
    color: var(--txt-1);
    font-size: 12px;
  }
  ```

- [ ] **Step 7: Add browser smoke agent-state assertions**

  In `scripts/console-browser-smoke.mjs`, add these items to `session-2` after the existing two items:

  ```js
  makePersistedItem('item-question', 'Agent needs more detail', 'screen-session-2', { left: 180, top: 160, right: 320, bottom: 250 }, {
    delivery: 'sent',
    status: 'needs_clarification',
    agentSummary: 'Which checkout variant should this apply to?',
  }),
  makePersistedItem('item-wont-fix', 'Outside scope', 'screen-session-2', { left: 200, top: 300, right: 360, bottom: 380 }, {
    delivery: 'sent',
    status: 'wont_fix',
    agentSummary: 'This is intentionally not fixed in the sample screen.',
  }),
  ```

  Change `makePersistedItem` signature to:

  ```js
  function makePersistedItem(itemId, comment, screenId, bounds = { left: 40, top: 40, right: 160, bottom: 120 }, overrides = {}) {
  ```

  and add `...overrides` as the last property in the returned object.

  After adding two more persisted items, update the saved-row count assertion from:

  ```js
  await page.waitForFunction(() => document.querySelectorAll('.saved-item-row').length === 2);
  ```

  to:

  ```js
  await page.waitForFunction(() => document.querySelectorAll('.saved-item-row').length === 4);
  ```

  Update the overlay-label assertion from:

  ```js
  .join(',') === '1,2'
  ```

  to:

  ```js
  .join(',') === '1,2,3,4'
  ```

  Add these label assertions after the saved-row count assertion:

  ```js
  assert.match(await page.locator('#draftItems').textContent(), /Needs Clarification/);
  assert.match(await page.locator('#draftItems').textContent(), /Won't Fix/);
  ```

- [ ] **Step 8: Rebuild and run focused verification**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  ./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest.updateDraftItemAllowsSentNeedsClarification' --tests '*FeedbackSessionStoreTest.updateDraftItemRejectsSentWontFix'
  npm run console:smoke
  npm run console:responsive:stress
  ```

  Expected:

  ```text
  Console browser smoke passed.
  Console responsive stress passed.
  ```

- [ ] **Step 9: Commit**

  ```bash
  git add fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/console/rendering.js fixthis-mcp/src/main/resources/console/styles.css fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt scripts/console-browser-smoke.mjs
  git commit -m "feat(console): render agent terminal feedback states"
  ```

---

## Task 5: Style Activity Drift, Connection Details, and Staleness Long Text

**Files:**

- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `fixthis-mcp/src/main/console/connection.js`
- Modify: `fixthis-mcp/src/main/resources/console/app.js`

- [ ] **Step 1: Update activity drift button copy**

  In `rendering.js`, replace:

  ```js
  '<button type="button" class="activity-drift-warning-button" data-activity-drift-restart>분리 (새 freeze 시작)</button>'
  ```

  with:

  ```js
  '<button type="button" class="activity-drift-warning-button" data-activity-drift-restart>Start new freeze</button>'
  ```

- [ ] **Step 2: Add warning/detail wrapping CSS**

  In `styles.css`, add this block near the annotation banner styles:

  ```css
  .activity-drift-warning {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 10px;
    margin: 0 0 10px;
    padding: 10px 12px;
    border: 1px solid rgba(230, 180, 90, .38);
    border-radius: 8px;
    background: rgba(230, 180, 90, .12);
    color: #fde68a;
    overflow-wrap: anywhere;
  }
  .activity-drift-warning-body {
    min-width: 0;
    display: grid;
    gap: 4px;
  }
  .activity-drift-warning-title {
    color: #fff7d6;
    font-weight: 800;
    font-size: 13px;
  }
  .activity-drift-warning-detail {
    color: #f8e8b0;
    font-size: 12px;
    line-height: 1.4;
  }
  .activity-drift-warning-button {
    flex: 0 0 auto;
    border-color: rgba(230, 180, 90, .45);
    color: #fff7d6;
  }
  .connection-details {
    min-width: 0;
    max-width: 100%;
  }
  .connection-details pre {
    max-width: min(360px, 100%);
    overflow-wrap: anywhere;
    word-break: break-word;
  }
  .staleness-banner {
    flex-wrap: wrap;
  }
  .staleness-banner [data-detail] {
    min-width: 0;
    overflow-wrap: anywhere;
    word-break: break-word;
  }
  ```

  Add this inside the existing `@media (max-width: 899px)` block:

  ```css
  .activity-drift-warning {
    flex-direction: column;
  }
  .activity-drift-warning-button {
    width: 100%;
  }
  .connection-actions {
    flex-wrap: wrap;
    gap: 8px;
  }
  .connection-details {
    flex: 1 1 100%;
  }
  .connection-details pre {
    max-width: 100%;
  }
  .staleness-banner {
    align-items: flex-start;
    gap: 6px 10px;
  }
  .staleness-banner [data-detail] {
    flex: 1 1 100%;
  }
  ```

- [ ] **Step 3: Update the connection layout comment**

  In `connection.js`, replace the current comment above `connectionDetailsBody.textContent = baseDetails` with:

  ```js
  // Surface a sub-line indicating sessions polling is paused. The details panel
  // preserves line breaks and wraps long tokens through .connection-details pre.
  ```

- [ ] **Step 4: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  npm run console:responsive:stress
  npm run console:smoke
  ```

  Expected:

  ```text
  Console responsive stress passed.
  Console browser smoke passed.
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add fixthis-mcp/src/main/resources/console/styles.css fixthis-mcp/src/main/console/rendering.js fixthis-mcp/src/main/console/connection.js fixthis-mcp/src/main/resources/console/app.js
  git commit -m "fix(console): wrap drift and diagnostic detail surfaces"
  ```

---

## Task 6: Harden Sample Compose Rows

**Files:**

- Modify: `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt`
- Modify: `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/FeedbackCard.kt`
- Modify: `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/InfoRow.kt`
- Modify: `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ProjectScreen.kt`
- Modify: `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ReviewScreen.kt`
- Modify: `sample/src/androidTest/java/io/github/beyondwin/fixthis/sample/SampleAppSmokeTest.kt`

- [ ] **Step 1: Update `StudioHeader` to let text and status share width safely**

  Add imports:

  ```kotlin
  import androidx.compose.foundation.layout.widthIn
  import androidx.compose.ui.text.style.TextOverflow
  ```

  Change the title column modifier from:

  ```kotlin
  modifier = Modifier.weight(1f),
  ```

  to:

  ```kotlin
  modifier = Modifier
      .weight(1f)
      .padding(end = 8.dp),
  ```

  Change the title and subtitle text to:

  ```kotlin
  Text(
      title,
      style = MaterialTheme.typography.headlineSmall,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
  )
  Text(
      subtitle,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
  )
  ```

  Add `widthIn(max = 132.dp)` to the status `Text` modifier before `.background(...)`.

- [ ] **Step 2: Use `FlowRow` for chips and action rows**

  In `FeedbackCard.kt`, add:

  ```kotlin
  import androidx.compose.foundation.layout.ExperimentalLayoutApi
  import androidx.compose.foundation.layout.FlowRow
  ```

  Annotate `FeedbackCard`:

  ```kotlin
  @OptIn(ExperimentalLayoutApi::class)
  @Composable
  fun FeedbackCard(
  ```

  Replace the chip `Row` with:

  ```kotlin
  FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
      StateChip(item.state)
      StatusChip(
          label = item.screenName,
          background = MaterialTheme.colorScheme.surfaceVariant,
          contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      )
  }
  ```

  Replace the action `Row` with:

  ```kotlin
  FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
      OutlinedButton(onClick = {}) { Text("Assign") }
      IconButton(
          modifier = Modifier.semantics {
              contentDescription = "Save ${item.id}"
          },
          onClick = {},
      ) {
          Text("S", style = MaterialTheme.typography.labelMedium)
      }
      Button(
          enabled = !showDisabledAction,
          onClick = {},
      ) {
          Text("Reviewed")
      }
  }
  ```

- [ ] **Step 3: Let `InfoRow` trailing content wrap below text**

  In `InfoRow.kt`, add:

  ```kotlin
  import androidx.compose.foundation.layout.ExperimentalLayoutApi
  import androidx.compose.foundation.layout.FlowRow
  ```

  Annotate `InfoRow`:

  ```kotlin
  @OptIn(ExperimentalLayoutApi::class)
  @Composable
  fun InfoRow(
  ```

  Replace the outer `Row` with:

  ```kotlin
  FlowRow(
      modifier = Modifier.padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
  ) {
      Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
          Text(title, style = MaterialTheme.typography.titleSmall)
          Text(detail, style = MaterialTheme.typography.bodyMedium)
          Text(
              meta,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
          )
      }
      trailing?.invoke()
  }
  ```

- [ ] **Step 4: Convert Project action/chip rows to `FlowRow`**

  In `ProjectScreen.kt`, import `ExperimentalLayoutApi` and `FlowRow`, annotate `ProjectScreen`, and replace the chip/action `Row` calls with `FlowRow` using:

  ```kotlin
  horizontalArrangement = Arrangement.spacedBy(8.dp),
  verticalArrangement = Arrangement.spacedBy(8.dp),
  ```

  Preserve the existing children and click handlers.

- [ ] **Step 5: Give Review labels weight**

  In `ReviewScreen.kt`, change the checkbox label to:

  ```kotlin
  Text(
      "Include screenshot context",
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
  )
  ```

  Change the switch row text to:

  ```kotlin
  Text(
      text = if (sendToAgent) "Send to agent queue" else "Keep as draft",
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyMedium,
  )
  ```

- [ ] **Step 6: Extend the Android smoke test**

  In `SampleAppSmokeTest.kt`, add this test:

  ```kotlin
  @Test
  fun diagnosticsLongRowKeepsDisabledActionVisible() {
      rule.onNodeWithText("Diagnostics").performClick()
      rule.waitForIdle()

      rule.onNodeWithText(
          "Very long diagnostic row label that should wrap without breaking Smart Select behavior",
      ).assertExists()
      rule.onNodeWithText("Disabled action").assertExists()
  }
  ```

- [ ] **Step 7: Verify sample build and Android tests**

  ```bash
  ./gradlew :app:assembleDebug
  ./gradlew :app:connectedDebugAndroidTest
  ```

  Expected:

  ```text
  BUILD SUCCESSFUL
  ```

  If no emulator/device is connected, record that `:app:connectedDebugAndroidTest` was blocked by device availability and run `./gradlew :app:assembleDebug`.

- [ ] **Step 8: Commit**

  ```bash
  git add sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt sample/src/main/java/io/github/beyondwin/fixthis/sample/components/FeedbackCard.kt sample/src/main/java/io/github/beyondwin/fixthis/sample/components/InfoRow.kt sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ProjectScreen.kt sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ReviewScreen.kt sample/src/androidTest/java/io/github/beyondwin/fixthis/sample/SampleAppSmokeTest.kt
  git commit -m "fix(sample): make demo rows adapt to narrow screens"
  ```

---

## Task 7: Constrain the Sidekick Overlay Pill

**Files:**

- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayout.kt`
- Modify: `fixthis-compose-sidekick/src/test/kotlin/io/github/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayoutTest.kt`

- [ ] **Step 1: Add failing overlay max-width test**

  In `FixThisConnectionStatusHostLayoutTest.kt`, add:

  ```kotlin
  @Test
  fun statusTextIsSingleLineAndEllipsized() {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      val host = FixThisConnectionStatusHostLayout(activity)
      val textView = host.statusTextView()

      assertEquals(1, textView.maxLines)
      assertEquals(TextUtils.TruncateAt.END, textView.ellipsize)
  }
  ```

  Add import:

  ```kotlin
  import android.text.TextUtils
  ```

- [ ] **Step 2: Run the focused test and verify it fails**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests '*FixThisConnectionStatusHostLayoutTest.statusTextIsSingleLineAndEllipsized'
  ```

  Expected before implementation: assertion failure because `maxLines` and `ellipsize` are not configured.

- [ ] **Step 3: Constrain the status text**

  In `FixThisConnectionStatusHostLayout.kt`, add imports:

  ```kotlin
  import android.text.TextUtils
  ```

  After `statusView.textSize = 14f`, add:

  ```kotlin
  statusView.maxLines = 1
  statusView.ellipsize = TextUtils.TruncateAt.END
  statusView.maxWidth = resources.displayMetrics.widthPixels
      .times(MaxPillWidthFraction)
      .toInt()
      .coerceAtLeast(context.dp(MinPillTextWidthDp))
  ```

  In the companion object, add:

  ```kotlin
  private const val MaxPillWidthFraction = 0.55f
  private const val MinPillTextWidthDp = 96
  ```

- [ ] **Step 4: Verify sidekick tests**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests '*FixThisConnectionStatusHostLayoutTest'
  ```

  Expected:

  ```text
  BUILD SUCCESSFUL
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayout.kt fixthis-compose-sidekick/src/test/kotlin/io/github/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayoutTest.kt
  git commit -m "fix(sidekick): constrain connection status pill width"
  ```

---

## Task 8: Final Verification and Release Notes

**Files:**

- Modify: `docs/superpowers/specs/2026-05-13-responsive-error-agent-feedback-ux-detailed-spec.md`

- [ ] **Step 1: Run full console verification**

  ```bash
  node scripts/build-console-assets.mjs --check
  npm run console:smoke
  npm run console:responsive:stress
  npm run console:availability:test
  npm run console:pending:test
  npm run console:beforeunload:test
  npm run console:undo:test
  ```

  Expected:

  ```text
  Console browser smoke passed.
  Console responsive stress passed.
  ```

  Node test scripts should exit `0`.

- [ ] **Step 2: Run focused JVM verification**

  ```bash
  ./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest'
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests '*FixThisConnectionStatusHostLayoutTest'
  ./gradlew :app:assembleDebug
  ```

  Expected:

  ```text
  BUILD SUCCESSFUL
  ```

- [ ] **Step 3: Run connected Android verification when a device is available**

  ```bash
  adb devices
  ./gradlew :app:connectedDebugAndroidTest
  ```

  Expected if a device/emulator is connected:

  ```text
  BUILD SUCCESSFUL
  ```

  If `adb devices` has no device rows, record `connectedDebugAndroidTest` as not run due to missing Android device/emulator.

- [ ] **Step 4: Update the spec status**

  In `docs/superpowers/specs/2026-05-13-responsive-error-agent-feedback-ux-detailed-spec.md`, change:

  ```markdown
  Status: Draft
  ```

  to:

  ```markdown
  Status: Implemented
  ```

  Add this line under the status/date block:

  ```markdown
  Implementation plan: `docs/superpowers/plans/2026-05-13-responsive-error-agent-feedback-ux-implementation.md`
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add docs/superpowers/specs/2026-05-13-responsive-error-agent-feedback-ux-detailed-spec.md
  git commit -m "docs: mark responsive agent feedback UX implemented"
  ```

## Self-Review

Spec coverage:

- RUX-1 is covered by Task 2.
- RUX-2 and RUX-6 are covered by Task 3.
- RUX-3 and RUX-4 are covered by Task 4.
- RUX-5 and RUX-7 are covered by Task 5.
- RUX-8 is covered by Tasks 1, 2, and 8.
- RUX-9 is covered by Task 6.
- RUX-10 is covered by Task 7.

Placeholder scan:

- No task uses placeholder filenames.
- Every code-changing task names concrete files and includes concrete snippets or commands.
- Every verification step has an expected success or failure signal.

Type consistency:

- Console phase names use underscore forms internally: `needs_clarification`, `wont_fix`, `in_progress`.
- CSS class names use `statusClass(item)` conversion to dashed forms: `st-needs-clarification`, `st-wont-fix`, `st-in-progress`.
- Kotlin status names use `AnnotationStatusDto.NEEDS_CLARIFICATION` and `AnnotationStatusDto.WONT_FIX`.
