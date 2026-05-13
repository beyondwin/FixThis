# FixThis UI/UX Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the FixThis first-run feedback workflow easier to understand, make handoff readiness explicit, keep session history reachable on narrow screens, and polish the sample app plus debug status pill so the product feels coherent end to end.

**Architecture:** Keep the browser console as the primary product surface and continue using vanilla JS/CSS with the existing generated bundle flow. Add small, explicit view-state helpers for workflow progress, prompt readiness, and preview frame state rather than introducing a framework. Keep Android changes limited to the validation sample UI and the debug-only sidekick status pill.

**Tech Stack:** Vanilla JS, vanilla CSS, Node.js, Playwright, Kotlin, Jetpack Compose Material3, Robolectric, Android Compose UI tests

---

## Scope

This plan covers one cohesive UI/UX polish pass across three user-facing surfaces:

- Browser feedback console: first-run workflow, prompt readiness, inspector hierarchy, compact history, and preview state.
- Sample Compose app: bottom navigation and repeated-card action polish.
- Android debug overlay: product-facing status text.

The work is intentionally not a visual redesign. Existing dark Studio shell, local-only privacy model, MCP field contracts, and persisted JSON names stay unchanged.

## File Structure

Console markup and styling:

- Modify `fixthis-mcp/src/main/resources/console/index.html` for workflow progress, prompt readiness, history toggle, and drawer scrim markup.
- Modify `fixthis-mcp/src/main/resources/console/styles.css` for workflow progress, prompt readiness, compact history drawer, preview frame badge, and inspector evidence hierarchy.

Console source modules:

- Modify `fixthis-mcp/src/main/console/state.js` to cache new DOM nodes and expose workflow/readiness helpers.
- Modify `fixthis-mcp/src/main/console/annotations.js` to compute prompt readiness from pending/saved annotation state.
- Modify `fixthis-mcp/src/main/console/rendering.js` to render inspector sections, preview frame status, and workflow progress.
- Modify `fixthis-mcp/src/main/console/history.js` to open/close compact history and close it after selecting a session.
- Regenerate `fixthis-mcp/src/main/resources/console/app.js` with `node scripts/build-console-assets.mjs`.

Console verification:

- Modify `scripts/console-responsive-stress.mjs` to assert the new compact history drawer and readiness/status surfaces do not overflow.
- Modify `scripts/console-browser-smoke.mjs` to assert first-run workflow and prompt readiness states during the happy path.

Sample app:

- Modify `gradle/libs.versions.toml` to add Compose material icons.
- Modify `sample/build.gradle.kts` to use the icons dependency.
- Modify `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt` for real navigation icons.
- Modify `sample/src/main/java/io/beyondwin/fixthis/sample/components/FeedbackCard.kt` to replace the text-only save glyph with an icon and stable content description.
- Modify `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt` for navigation and content-description assertions.

Sidekick overlay:

- Modify `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayout.kt`.
- Modify `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayoutTest.kt`.

Docs:

- Modify `docs/guides/feedback-console-tour.md`.
- Modify `docs/reference/feedback-console-contract.md`.

## Conventions

- Do not hand-edit `fixthis-mcp/src/main/resources/console/app.js`; regenerate it:

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  ```

- Run browser layout checks after every console UI task:

  ```bash
  npm run console:responsive:stress
  ```

- Browser smoke requires Playwright and the fake local console harness:

  ```bash
  npm run console:smoke
  ```

- Android connected tests require a connected unlocked emulator or device:

  ```bash
  ./gradlew :app:connectedDebugAndroidTest
  ```

- Commit after each task. Generated screenshots under `output/playwright/` and local artifacts under `.fixthis/` stay uncommitted.

---

## Task 1: Add Console Workflow Progress

**Files:**

- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `scripts/console-browser-smoke.mjs`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`

**Goal:** Show the user where they are in the core workflow: Connect, Preview, Annotate, Handoff.

- [ ] **Step 1: Write the smoke assertion first**

  In `scripts/console-browser-smoke.mjs`, add this helper after `waitForReady(page)`:

  ```js
  async function assertWorkflowProgress(page, expectedActiveStep) {
    const state = await page.evaluate(() => {
      const root = document.getElementById('workflowProgress');
      return {
        visible: Boolean(root) && !root.hidden,
        active: root?.querySelector('[data-state="active"]')?.getAttribute('data-workflow-step') || null,
        labels: Array.from(root?.querySelectorAll('[data-workflow-step]') || []).map(node =>
          node.textContent.replace(/\s+/g, ' ').trim()
        ),
      };
    });
    assert.equal(state.visible, true, 'workflow progress should be visible');
    assert.equal(state.active, expectedActiveStep, 'workflow active step');
    assert.deepEqual(
      state.labels.map(label => label.split(' ')[0]),
      ['Connect', 'Preview', 'Annotate', 'Handoff'],
      'workflow labels'
    );
  }
  ```

  Then call it:

  ```js
  await assertWorkflowProgress(page, 'preview');
  await page.click('#annotateToolButton');
  await assertWorkflowProgress(page, 'annotate');
  ```

- [ ] **Step 2: Run smoke to verify it fails**

  ```bash
  npm run console:smoke
  ```

  Expected: FAIL with an assertion mentioning `workflow progress should be visible`.

- [ ] **Step 3: Add workflow markup**

  In `fixthis-mcp/src/main/resources/console/index.html`, insert this block immediately after the global status paragraph:

  ```html
      <nav id="workflowProgress" class="workflow-progress" aria-label="FixThis feedback workflow">
        <ol>
          <li data-workflow-step="connect" data-state="active">
            <span class="workflow-index">1</span>
            <span class="workflow-copy">
              <strong>Connect</strong>
              <em>Start the debug app</em>
            </span>
          </li>
          <li data-workflow-step="preview" data-state="upcoming">
            <span class="workflow-index">2</span>
            <span class="workflow-copy">
              <strong>Preview</strong>
              <em>Navigate the screen</em>
            </span>
          </li>
          <li data-workflow-step="annotate" data-state="upcoming">
            <span class="workflow-index">3</span>
            <span class="workflow-copy">
              <strong>Annotate</strong>
              <em>Pin UI feedback</em>
            </span>
          </li>
          <li data-workflow-step="handoff" data-state="upcoming">
            <span class="workflow-index">4</span>
            <span class="workflow-copy">
              <strong>Handoff</strong>
              <em>Copy or save</em>
            </span>
          </li>
        </ol>
      </nav>
  ```

  Update `.studio-shell` rows and `.studio-body` row in `styles.css`:

  ```css
  .studio-shell {
    grid-row: 2;
    display: grid;
    grid-template-rows: 56px auto auto auto 1fr;
    height: 100%;
    overflow: hidden;
  }

  .studio-body {
    grid-row: 5;
    display: grid;
    grid-template-columns: 280px minmax(480px, 1fr) 340px;
    min-height: 0;
    overflow: hidden;
  }
  ```

- [ ] **Step 4: Add workflow CSS**

  Add this CSS near `.global-status`:

  ```css
  .workflow-progress {
    grid-row: 4;
    min-width: 0;
    padding: 8px 16px;
    border-bottom: 1px solid var(--line);
    background: var(--bg-1);
  }

  .workflow-progress ol {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .workflow-progress li {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
    padding: 6px 8px;
    border: 1px solid var(--line);
    border-radius: 8px;
    color: var(--txt-2);
    background: var(--bg-0);
  }

  .workflow-progress li[data-state="done"] {
    color: var(--txt-1);
    border-color: rgba(111, 207, 151, .30);
  }

  .workflow-progress li[data-state="active"] {
    color: var(--txt-0);
    border-color: rgba(184, 211, 106, .45);
    background: rgba(184, 211, 106, .08);
  }

  .workflow-index {
    width: 22px;
    height: 22px;
    display: inline-grid;
    place-items: center;
    flex: 0 0 auto;
    border-radius: 999px;
    background: var(--bg-3);
    color: currentColor;
    font-size: 11px;
    font-weight: 800;
  }

  .workflow-progress li[data-state="active"] .workflow-index {
    background: var(--accent);
    color: var(--bg-0);
  }

  .workflow-copy {
    min-width: 0;
    display: grid;
    gap: 1px;
  }

  .workflow-copy strong,
  .workflow-copy em {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .workflow-copy strong {
    font-size: 12px;
    line-height: 1.15;
  }

  .workflow-copy em {
    color: var(--txt-2);
    font-size: 10px;
    font-style: normal;
    line-height: 1.15;
  }
  ```

  Add this inside the existing `@media (max-width: 900px)` block:

  ```css
  .workflow-progress {
    padding: 8px;
  }

  .workflow-progress ol {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workflow-copy em {
    display: none;
  }
  ```

- [ ] **Step 5: Add workflow state helpers**

  In `state.js`, add:

  ```js
  const workflowProgress = document.getElementById('workflowProgress');
  ```

  In `rendering.js`, before `renderSessionRegions()`, add:

  ```js
  function workflowActiveStep() {
    if (!state.connection?.hasEverConnected && userConnectionState(state.connection?.current) !== 'ready') return 'connect';
    if (addItemsFlow || toolMode === 'annotate') return 'annotate';
    if (currentPromptAnnotations().length > 0) return 'handoff';
    return 'preview';
  }

  function workflowStepOrder(step) {
    return ['connect', 'preview', 'annotate', 'handoff'].indexOf(step);
  }

  function renderWorkflowProgress() {
    if (!workflowProgress) return;
    const active = workflowActiveStep();
    const activeOrder = workflowStepOrder(active);
    workflowProgress.querySelectorAll('[data-workflow-step]').forEach(node => {
      const step = node.getAttribute('data-workflow-step');
      const order = workflowStepOrder(step);
      const stateName = order < activeOrder ? 'done' : (step === active ? 'active' : 'upcoming');
      node.dataset.state = stateName;
    });
  }
  ```

  Call `renderWorkflowProgress()` inside `render()` after `renderConnection(state.connection.current)`:

  ```js
  renderWorkflowProgress();
  ```

- [ ] **Step 6: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  npm run console:smoke
  npm run console:responsive:stress
  ```

  Expected: all commands pass.

- [ ] **Step 7: Commit**

  ```bash
  git add fixthis-mcp/src/main/resources/console/index.html \
          fixthis-mcp/src/main/resources/console/styles.css \
          fixthis-mcp/src/main/console/state.js \
          fixthis-mcp/src/main/console/rendering.js \
          fixthis-mcp/src/main/resources/console/app.js \
          scripts/console-browser-smoke.mjs
  git commit -m "feat(console): add feedback workflow progress"
  ```

---

## Task 2: Add Prompt Readiness Summary

**Files:**

- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `scripts/console-browser-smoke.mjs`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`

**Goal:** Explain why `Copy Prompt` / `Save to MCP` are enabled or disabled and show how many annotations will be included.

- [ ] **Step 1: Add smoke assertions**

  In `scripts/console-browser-smoke.mjs`, add:

  ```js
  async function assertPromptReadiness(page, expectedText) {
    const text = await page.locator('#promptReadiness').innerText();
    assert.equal(text.replace(/\s+/g, ' ').trim(), expectedText);
  }
  ```

  During the happy path, assert these states:

  ```js
  await assertPromptReadiness(page, 'No annotations ready');
  ```

  After adding one annotation with a comment, assert:

  ```js
  await assertPromptReadiness(page, '1 ready');
  ```

- [ ] **Step 2: Run smoke to verify it fails**

  ```bash
  npm run console:smoke
  ```

  Expected: FAIL because `#promptReadiness` is absent.

- [ ] **Step 3: Add readiness markup**

  In `index.html`, insert this as the first child of `.studio-actions`, before `copyPromptButton`:

  ```html
  <span id="promptReadiness" class="prompt-readiness" aria-live="polite">No annotations ready</span>
  ```

- [ ] **Step 4: Add readiness CSS**

  Add near `.studio-actions` styles:

  ```css
  .prompt-readiness {
    display: inline-flex;
    align-items: center;
    min-height: 30px;
    max-width: 180px;
    padding: 0 10px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: var(--bg-2);
    color: var(--txt-2);
    font-size: 11px;
    font-weight: 700;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .prompt-readiness[data-state="ready"] {
    color: var(--accent);
    border-color: rgba(184, 211, 106, .35);
    background: rgba(184, 211, 106, .08);
  }

  .prompt-readiness[data-state="blocked"] {
    color: #fde68a;
    border-color: rgba(230, 180, 90, .35);
    background: rgba(230, 180, 90, .10);
  }
  ```

  In `@media (max-width: 900px)`, add:

  ```css
  .prompt-readiness {
    flex: 1 1 150px;
    max-width: none;
  }
  ```

- [ ] **Step 5: Compute readiness state**

  In `state.js`, add:

  ```js
  const promptReadiness = document.getElementById('promptReadiness');
  ```

  In `annotations.js`, add these helpers near `currentPromptAnnotations()`:

  ```js
  function promptReadinessState() {
    const annotations = toolbarAnnotations().filter(item => item.delivery !== 'sent');
    const ready = annotations.filter(hasWrittenAnnotationComment);
    const missing = annotations.length - ready.length;
    if (ready.length > 0) {
      return {
        state: missing > 0 ? 'blocked' : 'ready',
        label: countLabel(ready.length, 'ready', 'ready') + (missing > 0 ? ' · ' + countLabel(missing, 'missing comment', 'missing comments') : ''),
        title: ready.length + ' annotation(s) will be included. ' + missing + ' annotation(s) need a comment.',
      };
    }
    if (annotations.length > 0) {
      return {
        state: 'blocked',
        label: countLabel(annotations.length, 'missing comment', 'missing comments'),
        title: 'Add a comment before copying or saving feedback.',
      };
    }
    return {
      state: 'empty',
      label: 'No annotations ready',
      title: 'Annotate a UI target and add a comment to enable handoff.',
    };
  }

  function renderPromptReadiness() {
    if (!promptReadiness) return;
    const readiness = promptReadinessState();
    promptReadiness.dataset.state = readiness.state;
    promptReadiness.textContent = readiness.label;
    promptReadiness.title = readiness.title;
    copyPromptButton.title = readiness.title;
    sendAgentButton.title = readiness.title;
  }
  ```

  Call `renderPromptReadiness()` at the end of `updateComposerState()`.

- [ ] **Step 6: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  npm run console:smoke
  npm run console:responsive:stress
  ```

  Expected: all commands pass.

- [ ] **Step 7: Commit**

  ```bash
  git add fixthis-mcp/src/main/resources/console/index.html \
          fixthis-mcp/src/main/resources/console/styles.css \
          fixthis-mcp/src/main/console/state.js \
          fixthis-mcp/src/main/console/annotations.js \
          fixthis-mcp/src/main/resources/console/app.js \
          scripts/console-browser-smoke.mjs
  git commit -m "feat(console): explain handoff readiness"
  ```

---

## Task 3: Reorganize Inspector Detail Into Target, Request, Evidence

**Files:**

- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `scripts/console-responsive-stress.mjs`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`

**Goal:** Make the Inspector read as a structured authoring surface instead of one mixed list.

- [ ] **Step 1: Add responsive stress assertions**

  In `scripts/console-responsive-stress.mjs`, inside `injectStressState(page)`, add this block after the existing `draftItems.innerHTML` assignment:

  ```js
  document.getElementById('draftItems').insertAdjacentHTML('beforeend',
    '<details class="evidence-details" open>' +
      '<summary>Evidence</summary>' +
      '<div class="evidence-grid">' +
        '<span>Target</span><strong>Button "Checkout"</strong>' +
        '<span>Bounds</span><strong>70,110 - 330,190</strong>' +
        '<span>Source</span><strong>' + longPath + '</strong>' +
      '</div>' +
    '</details>'
  );
  ```

  In `assertNoHorizontalOverflow`, add `.evidence-details` and `.evidence-grid` to the selector list:

  ```js
  '.evidence-details',
  '.evidence-grid',
  ```

- [ ] **Step 2: Run stress to verify current styles fail or capture baseline**

  ```bash
  npm run console:responsive:stress
  ```

  Expected before CSS: PASS is possible because the injected block may inherit generic wrapping. Continue so the new production markup is covered by the selector list.

- [ ] **Step 3: Add rendering helpers**

  In `rendering.js`, add these helpers before `renderAnnotationDetail(item, index)`:

  ```js
  function itemBounds(item) {
    return item?.bounds || item?.target?.boundsInWindow || item?.target?.bounds || null;
  }

  function sourceCandidateLine(candidate) {
    if (!candidate) return '-';
    const location = candidate.line == null ? candidate.file : candidate.file + ':' + candidate.line;
    const reason = candidate.reason || candidate.matchReason || '';
    return location + (reason ? ' · ' + reason : '');
  }

  function evidenceDetailsHtml(item) {
    const bounds = itemBounds(item);
    const source = (item?.sourceCandidates || [])[0] || null;
    const rows = [
      ['Target', targetLabel(item)],
      ['Bounds', bounds ? formatBounds(bounds) : '-'],
      ['Source', sourceCandidateLine(source)],
    ];
    return '<details class="evidence-details">' +
      '<summary>Evidence</summary>' +
      '<div class="evidence-grid">' +
        rows.map(row =>
          '<span>' + escapeHtml(row[0]) + '</span><strong>' + escapeHtml(row[1]) + '</strong>'
        ).join('') +
      '</div>' +
    '</details>';
  }
  ```

- [ ] **Step 4: Update pending annotation detail markup**

  In `renderAnnotationDetail(item, index)`, wrap the existing label/severity/comment/status controls into explicit sections:

  ```js
  pendingItems.innerHTML =
    '<div class="annotation-detail">' +
      '<button type="button" class="annotation-back" data-back-annotations>← All annotations</button>' +
      '<section class="inspector-card">' +
        '<div class="inspector-card-title">Target</div>' +
        '<div class="selection-summary">' + escapeHtml(annotationTitle(item, index)) + ' · ' + escapeHtml(formatBounds(item.bounds)) + '</div>' +
      '</section>' +
      '<section class="inspector-card">' +
        '<div class="inspector-card-title">Request</div>' +
        '<div class="annotation-field">' +
          '<label for="annotationLabelInput">Label</label>' +
          '<input id="annotationLabelInput" class="annotation-input" value="' + escapeHtml(annotationTitle(item, index)) + '">' +
        '</div>' +
        '<div class="annotation-field">' +
          '<label>Severity</label>' +
          '<div class="annotation-segmented" role="group" aria-label="Severity">' +
            ['low', 'med', 'high'].map(value =>
              '<button type="button" class="' + (severity === value ? 'active' : '') + '"' +
                ' aria-pressed="' + (severity === value ? 'true' : 'false') + '"' +
                ' data-set-severity="' + value + '">' + value.toUpperCase() + '</button>'
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
            ['open', 'in_progress', 'resolved'].map(value =>
              '<button type="button" class="' + (status === value ? 'active' : '') + '"' +
                ' aria-pressed="' + (status === value ? 'true' : 'false') + '"' +
                ' data-set-status="' + value + '">' + statusValueLabel(value) + '</button>'
            ).join('') +
          '</div>' +
        '</div>' +
      '</section>' +
      '<section class="inspector-card inspector-card-subtle">' + evidenceDetailsHtml(item) + '</section>' +
      '<div class="annotation-actions">' +
        '<button type="button" class="annotation-danger" data-delete-current>Delete</button>' +
        '<button type="button" class="annotation-done" data-back-annotations>Done</button>' +
      '</div>' +
    '</div>';
  ```

  Preserve all event listeners already below the markup assignment.

- [ ] **Step 5: Add inspector hierarchy CSS**

  Add near `.annotation-detail`:

  ```css
  .inspector-card {
    display: grid;
    gap: 10px;
    min-width: 0;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--bg-1);
  }

  .inspector-card-subtle {
    background: transparent;
  }

  .inspector-card-title {
    color: var(--txt-2);
    font-size: 10px;
    font-weight: 800;
    letter-spacing: .12em;
    text-transform: uppercase;
  }

  .evidence-details {
    min-width: 0;
    color: var(--txt-1);
    font-size: 12px;
  }

  .evidence-details summary {
    cursor: pointer;
    color: var(--txt-0);
    font-weight: 800;
  }

  .evidence-grid {
    display: grid;
    grid-template-columns: minmax(64px, max-content) minmax(0, 1fr);
    gap: 6px 10px;
    min-width: 0;
    margin-top: 10px;
  }

  .evidence-grid span {
    color: var(--txt-2);
    font-size: 11px;
  }

  .evidence-grid strong {
    min-width: 0;
    color: var(--txt-0);
    font-size: 11px;
    font-weight: 700;
    overflow-wrap: anywhere;
  }
  ```

- [ ] **Step 6: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  npm run console:responsive:stress
  npm run console:smoke
  ```

  Expected: all commands pass.

- [ ] **Step 7: Commit**

  ```bash
  git add fixthis-mcp/src/main/resources/console/styles.css \
          fixthis-mcp/src/main/console/rendering.js \
          fixthis-mcp/src/main/resources/console/app.js \
          scripts/console-responsive-stress.mjs
  git commit -m "feat(console): clarify annotation inspector hierarchy"
  ```

---

## Task 4: Keep History Reachable On Narrow Screens

**Files:**

- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `scripts/console-responsive-stress.mjs`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`

**Goal:** Replace the narrow-screen `display:none` history behavior with an accessible drawer.

- [ ] **Step 1: Add stress assertion**

  In `scripts/console-responsive-stress.mjs`, add this helper:

  ```js
  async function assertCompactHistoryDrawer(page, viewportName) {
    if ((await page.viewportSize()).width > 900) return;
    const result = await page.evaluate(() => {
      const button = document.getElementById('historyToggleButton');
      const history = document.querySelector('.studio-history');
      button?.click();
      return {
        buttonVisible: Boolean(button) && getComputedStyle(button).display !== 'none',
        drawerOpen: history?.classList.contains('is-open') || false,
        ariaExpanded: button?.getAttribute('aria-expanded') || null,
      };
    });
    assert.equal(result.buttonVisible, true, `${viewportName} history button visible`);
    assert.equal(result.drawerOpen, true, `${viewportName} history drawer opens`);
    assert.equal(result.ariaExpanded, 'true', `${viewportName} history button aria-expanded`);
  }
  ```

  Call it in the viewport loop after `injectStressState(page)`:

  ```js
  await assertCompactHistoryDrawer(page, viewport.name);
  ```

- [ ] **Step 2: Run stress to verify it fails**

  ```bash
  npm run console:responsive:stress
  ```

  Expected: FAIL because `#historyToggleButton` is absent.

- [ ] **Step 3: Add drawer controls**

  In `index.html`, add the button inside `.studio-context` after `deviceStatus`:

  ```html
  <button id="historyToggleButton" class="history-toggle-button" type="button" aria-controls="sessions" aria-expanded="false">History</button>
  ```

  Add the scrim immediately before `<main class="studio-body">`:

  ```html
  <button id="historyScrim" class="history-scrim" type="button" aria-label="Close history" hidden></button>
  ```

- [ ] **Step 4: Add drawer CSS**

  Add base styles:

  ```css
  .history-toggle-button {
    display: none;
    white-space: nowrap;
  }

  .history-scrim {
    display: none;
  }
  ```

  Replace the current narrow history rule inside `@media (max-width: 900px)`:

  ```css
  .history-toggle-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }

  .studio-history {
    position: fixed;
    z-index: 30;
    top: 0;
    left: 0;
    bottom: 0;
    display: flex;
    width: min(320px, calc(100vw - 32px));
    max-width: 100vw;
    border-right: 1px solid var(--line);
    background: var(--bg-0);
    transform: translateX(-100%);
    transition: transform 160ms ease;
    box-shadow: 18px 0 48px -24px rgba(0, 0, 0, .8);
  }

  .studio-history.is-open {
    transform: translateX(0);
  }

  .history-scrim {
    position: fixed;
    z-index: 25;
    inset: 0;
    display: block;
    border: 0;
    border-radius: 0;
    padding: 0;
    background: rgba(0, 0, 0, .42);
  }

  .history-scrim[hidden] {
    display: none;
  }
  ```

- [ ] **Step 5: Add drawer behavior**

  In `state.js`, add:

  ```js
  const historyToggleButton = document.getElementById('historyToggleButton');
  const historyScrim = document.getElementById('historyScrim');
  ```

  In `history.js`, add:

  ```js
  function setHistoryDrawerOpen(open) {
    const historyPanel = document.querySelector('.studio-history');
    if (!historyPanel || !historyToggleButton || !historyScrim) return;
    historyPanel.classList.toggle('is-open', open);
    historyToggleButton.setAttribute('aria-expanded', String(open));
    historyScrim.hidden = !open;
  }

  function toggleHistoryDrawer() {
    const historyPanel = document.querySelector('.studio-history');
    setHistoryDrawerOpen(!historyPanel?.classList.contains('is-open'));
  }
  ```

  Add listeners after existing history bindings:

  ```js
  historyToggleButton?.addEventListener('click', toggleHistoryDrawer);
  historyScrim?.addEventListener('click', () => setHistoryDrawerOpen(false));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') setHistoryDrawerOpen(false);
  });
  ```

  In the existing session-row click handler inside `renderSessionsList()`, after opening the selected session, call:

  ```js
  setHistoryDrawerOpen(false);
  ```

- [ ] **Step 6: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  npm run console:responsive:stress
  npm run console:smoke
  ```

  Expected: all commands pass.

- [ ] **Step 7: Commit**

  ```bash
  git add fixthis-mcp/src/main/resources/console/index.html \
          fixthis-mcp/src/main/resources/console/styles.css \
          fixthis-mcp/src/main/console/state.js \
          fixthis-mcp/src/main/console/history.js \
          fixthis-mcp/src/main/resources/console/app.js \
          scripts/console-responsive-stress.mjs
  git commit -m "feat(console): keep history accessible on compact screens"
  ```

---

## Task 5: Add Preview Frame State Badge

**Files:**

- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `scripts/console-responsive-stress.mjs`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`

**Goal:** Make it obvious whether the user is looking at a live, frozen, stale, or saved frame.

- [ ] **Step 1: Add stress assertion**

  In `scripts/console-responsive-stress.mjs`, after preview injection, include:

  ```js
  document.getElementById('snapshotFrame').insertAdjacentHTML(
    'afterbegin',
    '<div id="previewFrameStatus" class="preview-frame-status" data-frame-state="frozen">Frozen for annotation</div>'
  );
  ```

  Add `#previewFrameStatus` to `assertNoHorizontalOverflow` selectors.

- [ ] **Step 2: Add badge CSS**

  Add near `.snapshot-frame`:

  ```css
  .preview-frame-status {
    position: absolute;
    z-index: 2;
    top: 10px;
    left: 50%;
    transform: translateX(-50%);
    max-width: calc(100% - 24px);
    padding: 5px 10px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: rgba(13, 14, 16, .82);
    color: var(--txt-0);
    font-size: 11px;
    font-weight: 800;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    pointer-events: none;
  }

  .preview-frame-status[data-frame-state="live"] {
    color: #86efac;
    border-color: rgba(34, 197, 94, .35);
  }

  .preview-frame-status[data-frame-state="frozen"],
  .preview-frame-status[data-frame-state="saved"] {
    color: var(--accent);
    border-color: rgba(184, 211, 106, .40);
  }

  .preview-frame-status[data-frame-state="stale"] {
    color: #fde68a;
    border-color: rgba(230, 180, 90, .45);
  }
  ```

- [ ] **Step 3: Render badge in preview frame**

  In `ensurePreviewFrame()`, add the badge before the image:

  ```js
  '<div id="previewFrameStatus" class="preview-frame-status" data-frame-state="idle">Waiting for preview</div>' +
  ```

  In `renderPreviewRegion()`, after `frame.dataset.mode = mode`, add:

  ```js
  const frameStatus = document.getElementById('previewFrameStatus');
  if (frameStatus) {
    const frameState = state.preview?.stale ? 'stale' : mode;
    const label = frameState === 'live'
      ? 'Live preview'
      : frameState === 'frozen'
        ? (addItemsFlow ? 'Frozen for annotation' : 'Saved evidence')
        : frameState === 'stale'
          ? 'Stale preview'
          : 'Waiting for preview';
    frameStatus.dataset.frameState = frameState;
    frameStatus.textContent = label;
  }
  ```

- [ ] **Step 4: Rebuild and verify**

  ```bash
  node scripts/build-console-assets.mjs
  node scripts/build-console-assets.mjs --check
  npm run console:responsive:stress
  npm run console:smoke
  ```

  Expected: all commands pass.

- [ ] **Step 5: Commit**

  ```bash
  git add fixthis-mcp/src/main/resources/console/styles.css \
          fixthis-mcp/src/main/console/rendering.js \
          fixthis-mcp/src/main/resources/console/app.js \
          scripts/console-responsive-stress.mjs
  git commit -m "feat(console): label preview frame state"
  ```

---

## Task 6: Polish Sample App Navigation And Card Actions

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `sample/build.gradle.kts`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/FeedbackCard.kt`
- Modify: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt`

**Goal:** Replace one-letter navigation glyphs with familiar icons and keep stable semantics for FixThis targeting.

- [ ] **Step 1: Add failing sample test assertions**

  In `SampleAppSmokeTest.fixThisShowsProductScenesAndNavigatesTabs`, after the first Home assertions, add:

  ```kotlin
  rule.onNodeWithContentDescription("Home tab").assertExists()
  rule.onNodeWithContentDescription("Queue tab").assertExists()
  rule.onNodeWithContentDescription("Project tab").assertExists()
  rule.onNodeWithContentDescription("Review tab").assertExists()
  rule.onNodeWithContentDescription("Diagnostics tab").assertExists()
  ```

  In the Queue section, add:

  ```kotlin
  rule.onNodeWithContentDescription("Save FX-1042").assertExists()
  ```

- [ ] **Step 2: Run connected test to verify it fails**

  ```bash
  ./gradlew :app:connectedDebugAndroidTest
  ```

  Expected: FAIL until icons/content descriptions are added. If no device is connected, run the compile gate in Step 6 and leave connected verification for a device-backed pass.

- [ ] **Step 3: Add icons dependency**

  In `gradle/libs.versions.toml`, add:

  ```toml
  compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
  ```

  In `sample/build.gradle.kts`, add:

  ```kotlin
  implementation(libs.compose.material.icons.extended)
  ```

- [ ] **Step 4: Update bottom navigation icons**

  In `FixThisStudioApp.kt`, replace the enum with:

  ```kotlin
  import androidx.compose.material.icons.Icons
  import androidx.compose.material.icons.outlined.BugReport
  import androidx.compose.material.icons.outlined.Dashboard
  import androidx.compose.material.icons.outlined.FactCheck
  import androidx.compose.material.icons.outlined.FolderOpen
  import androidx.compose.material.icons.outlined.RateReview
  import androidx.compose.material3.Icon
  import androidx.compose.ui.graphics.vector.ImageVector
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.semantics.contentDescription
  import androidx.compose.ui.semantics.semantics

  enum class FixThisTab(
      val label: String,
      val contentDescription: String,
      val icon: ImageVector,
  ) {
      Home("Home", "Home tab", Icons.Outlined.Dashboard),
      Queue("Queue", "Queue tab", Icons.Outlined.FactCheck),
      Project("Project", "Project tab", Icons.Outlined.FolderOpen),
      Review("Review", "Review tab", Icons.Outlined.RateReview),
      Diagnostics("Diagnostics", "Diagnostics tab", Icons.Outlined.BugReport),
  }
  ```

  Replace the `NavigationBarItem` icon:

  ```kotlin
  icon = {
      Icon(
          imageVector = tab.icon,
          contentDescription = tab.contentDescription,
      )
  },
  label = {
      Text(
          text = tab.label,
          maxLines = 1,
          modifier = Modifier.semantics {
              contentDescription = tab.contentDescription
          },
      )
  },
  ```

  Keep `onNodeWithText("Queue").performClick()` working by leaving visible labels unchanged.

- [ ] **Step 5: Update card save action**

  In `FeedbackCard.kt`, add imports:

  ```kotlin
  import androidx.compose.material.icons.Icons
  import androidx.compose.material.icons.outlined.BookmarkBorder
  import androidx.compose.material3.Icon
  ```

  Replace the `IconButton` body:

  ```kotlin
  IconButton(
      modifier = Modifier.semantics {
          contentDescription = "Save ${item.id}"
      },
      onClick = {},
  ) {
      Icon(
          imageVector = Icons.Outlined.BookmarkBorder,
          contentDescription = null,
      )
  }
  ```

- [ ] **Step 6: Verify compile and connected behavior**

  ```bash
  ./gradlew :app:assembleDebug
  ./gradlew :app:connectedDebugAndroidTest
  ```

  Expected:

  ```text
  BUILD SUCCESSFUL
  ```

  If the second command cannot run because no device is attached, record that in the task handoff and run it before merge.

- [ ] **Step 7: Commit**

  ```bash
  git add gradle/libs.versions.toml \
          sample/build.gradle.kts \
          sample/src/main/java/io/beyondwin/fixthis/sample/FixThisStudioApp.kt \
          sample/src/main/java/io/beyondwin/fixthis/sample/components/FeedbackCard.kt \
          sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt
  git commit -m "feat(sample): replace text glyphs with navigation icons"
  ```

---

## Task 7: Rename Android Debug Pill Copy To Product Language

**Files:**

- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayout.kt`
- Modify: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayoutTest.kt`

**Goal:** Keep the overlay debug-only and minimal, but avoid exposing MCP jargon directly on top of the target app UI.

- [ ] **Step 1: Update failing tests first**

  In `FixThisConnectionStatusHostLayoutTest.kt`, replace expected text:

  ```kotlin
  assertEquals("FixThis waiting", host.statusText())
  assertEquals("FixThis connected", host.statusText())
  ```

  In `connectedStateDoesNotRecreateTextViewOrAnimateWholeViews`, update all `"MCP waiting"` and `"MCP connected"` expectations the same way.

  Add this content-description assertion to `rendersWaitingOrConnectedStatusOnly`:

  ```kotlin
  assertEquals("FixThis browser waiting", host.statusTextView().contentDescription)

  state.markAuthorizedRequest()
  host.forceStatusRefreshForTest()

  assertEquals("FixThis browser connected", host.statusTextView().contentDescription)
  ```

- [ ] **Step 2: Run sidekick overlay test to verify it fails**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests '*FixThisConnectionStatusHostLayoutTest*'
  ```

  Expected: FAIL because production text still says `MCP`.

- [ ] **Step 3: Update overlay text**

  In `FixThisConnectionStatusHostLayout.kt`, replace:

  ```kotlin
  statusView.text = if (connected) "MCP connected" else "MCP waiting"
  statusView.contentDescription = if (connected) {
      "FixThis MCP browser connected"
  } else {
      "FixThis MCP browser waiting"
  }
  ```

  With:

  ```kotlin
  statusView.text = if (connected) "FixThis connected" else "FixThis waiting"
  statusView.contentDescription = if (connected) {
      "FixThis browser connected"
  } else {
      "FixThis browser waiting"
  }
  ```

- [ ] **Step 4: Run overlay tests**

  ```bash
  ./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests '*FixThisConnectionStatusHostLayoutTest*'
  ```

  Expected:

  ```text
  BUILD SUCCESSFUL
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayout.kt \
          fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisConnectionStatusHostLayoutTest.kt
  git commit -m "feat(sidekick): use product language in status pill"
  ```

---

## Task 8: Update User-Facing Docs For The New UI Cues

**Files:**

- Modify: `docs/guides/feedback-console-tour.md`
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `README.md`

**Goal:** Keep docs aligned with the shipped labels and new progress/readiness cues.

- [ ] **Step 1: Update console tour wording**

  In `docs/guides/feedback-console-tour.md`, under "Open the console", add:

  ```markdown
  The workflow strip shows the four expected phases: **Connect**, **Preview**,
  **Annotate**, and **Handoff**. It is a progress cue only; it does not replace
  the device selector or Inspector actions.
  ```

  Under "Save the batch", add:

  ```markdown
  The top-bar readiness pill shows how many annotations will be included. If an
  annotation exists but has no comment, the pill calls that out and the handoff
  buttons stay disabled until at least one written annotation is ready.
  ```

- [ ] **Step 2: Update console contract**

  In `docs/reference/feedback-console-contract.md`, add rows to the control table:

  ```markdown
  | Workflow progress | `workflowProgress` | `Connect / Preview / Annotate / Handoff` |
  | Prompt readiness | `promptReadiness` | `No annotations ready`, `N ready`, or `N missing comments` |
  | Compact history | `historyToggleButton` | `History` |
  ```

  Add this invariant under the UI modes section:

  ```markdown
  - The workflow progress strip is derived UI. It must not create or mutate
    feedback items by itself.
  - The prompt readiness pill is derived from annotations that are not already
    `delivery: sent` and have non-blank comments.
  - On compact viewports, History remains reachable through `historyToggleButton`;
    hiding the only session navigation surface is a regression.
  ```

- [ ] **Step 3: Update README quick start sentence**

  In `README.md`, replace the single sentence after the quick-start command block with:

  ```markdown
  `fixthis run` installs the sample debug APK, attaches the sidekick bridge, and opens the FixThis Studio console at `http://127.0.0.1:<port>`. Follow the **Connect → Preview → Annotate → Handoff** progress strip: click **Annotate**, point at any UI element, write a comment, and hit **Copy Prompt** or **Save to MCP**.
  ```

- [ ] **Step 4: Verify docs references**

  ```bash
  rg -n "workflowProgress|promptReadiness|historyToggleButton|Connect → Preview → Annotate → Handoff" README.md docs/guides/feedback-console-tour.md docs/reference/feedback-console-contract.md
  ```

  Expected: each new id and the quick-start phrase appear.

- [ ] **Step 5: Commit**

  ```bash
  git add README.md docs/guides/feedback-console-tour.md docs/reference/feedback-console-contract.md
  git commit -m "docs: document console workflow and readiness cues"
  ```

---

## Final Verification

Run the full relevant verification set after all tasks:

```bash
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
npm run console:smoke
npm run console:responsive:stress
./gradlew :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :app:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
Console browser smoke passed.
```

Run connected tests before merge when a device/emulator is available:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Self-Review

- Spec coverage: first-run progress is Task 1; handoff readiness is Task 2; inspector hierarchy is Task 3; compact history access is Task 4; preview frame state is Task 5; sample polish is Task 6; sidekick copy is Task 7; docs sync is Task 8.
- Compatibility: no persisted MCP JSON field names change. Console additions are derived UI and do not change the Save to MCP or Copy Prompt contract.
- Risk: the largest UI risk is compact history drawer behavior because it changes a previously hidden panel into a fixed overlay. Task 4 includes browser assertions for drawer visibility, open state, and horizontal overflow.
- Verification: console JS bundle, Playwright smoke/stress, sidekick unit tests, app assemble, and optional connected tests are all listed with exact commands.
