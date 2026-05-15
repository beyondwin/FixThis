# Quiet Draft Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Treat commentless annotation pins as quiet local draft metadata, while only written draft feedback drives resume UI, boundary protection, and handoff.

**Architecture:** Keep the existing browser-local draft workspace storage format. Add small draft classification helpers, then update the recovery banner, session boundary handling, history summaries, and handoff path to consume commented and pin-only counts separately. Preserve pin-only residual items locally when written items are handed off.

**Tech Stack:** Vanilla console JavaScript bundled by `scripts/build-console-assets.mjs`, Node test runner, existing console contract tests.

---

## File Map

- Modify `fixthis-mcp/src/main/console/annotations.js`
  - Owns annotation predicates and draft item serialization.
  - Add shared classification helpers: `draftItemsFromValue`, `commentedDraftItems`, `pinOnlyDraftItems`, `draftRecoverySummary`, and `hasCommentedDraftItems`.
  - Update `persistPendingFeedbackItems({ onlyWrittenComments: true })` so written items can be persisted while pin-only residual items stay in local draft storage.

- Modify `fixthis-mcp/src/main/console/main.js`
  - Owns pending recovery state, recovery banner rendering, and boundary preflight.
  - Change passive recovered drafts so pin-only recovery never blocks or warns.
  - Change banner copy/actions from "Recover" to "Resume draft" semantics for commented drafts.

- Modify `fixthis-mcp/src/main/console/history.js`
  - Owns session list counts and labels.
  - Split recovered draft counts into commented and pin-only groups for display.
  - Keep navigation non-blocking for passive recovered drafts.

- Modify `fixthis-mcp/src/main/console/prompt.js`
  - Owns Copy Prompt / Save to MCP collection.
  - Persist only written draft items and allow mixed drafts without failing because pin-only items exist.

- Modify `fixthis-mcp/src/main/resources/console/styles.css`
  - Add a compact session-row pending summary style for split draft counts.

- Modify generated console assets:
  - `fixthis-mcp/src/main/resources/console/app.js`
  - `fixthis-mcp/src/main/resources/console/app.js.map`
  - `fixthis-mcp/src/main/resources/console/console-build-meta.json`

- Modify `scripts/pendingItemRecovery-test.mjs`
  - Add focused contract tests for pin-only, mixed, and handoff behavior.

## Task 1: Failing Recovery Classification Contract Tests

**Files:**
- Modify: `scripts/pendingItemRecovery-test.mjs`

- [ ] **Step 1: Add tests for draft classification helper presence**

Add this test after `pending recovery refreshes history summaries after restoring draft items`:

```js
test('draft recovery helpers classify commented and pin-only draft items', () => {
  assert.match(annotationsSource, /function draftItemsFromValue\(value\)/);
  assert.match(annotationsSource, /function commentedDraftItems\(value\)/);
  assert.match(annotationsSource, /function pinOnlyDraftItems\(value\)/);
  assert.match(annotationsSource, /function draftRecoverySummary\(value\)/);
  assert.match(
    annotationsSource,
    /commentedDraftItems\(items\)[\s\S]*?pinOnlyDraftItems\(items\)/,
  );
});
```

- [ ] **Step 2: Add tests that passive pin-only recovery is non-blocking**

Replace the existing test named `session refresh reloads pending recovery and session switches require a recovery choice` with:

```js
test('session refresh reloads pending recovery without blocking passive drafts', () => {
  const refreshBody = extractFunctionBody(historySource, 'async function refresh()');
  assert.match(refreshBody, /loadPendingRecoveryForCurrentSession\(\);[\s\S]*?render\(\);/);

  const openBody = extractFunctionBody(historySource, 'async function openSession(sessionId)');
  const newBody = extractFunctionBody(historySource, 'async function newSession()');
  const annotateBody = extractFunctionBody(historySource, 'async function enterAnnotateMode()');

  assert.match(openBody, /resolvePendingBeforeBoundary\('open-session',\s*sessionId\)/);
  assert.match(newBody, /resolvePendingBeforeBoundary\('new-session'\)/);
  assert.match(annotateBody, /requirePendingRecoveryChoiceBeforeSessionChange\(\)/);

  const requireBody = extractFunctionBody(mainSource, 'function requirePendingRecoveryChoiceBeforeSessionChange()');
  assert.doesNotMatch(requireBody, /showError\('Recover, recapture, or discard unsaved annotations before changing sessions\.'\)/);
  assert.match(requireBody, /renderPendingRecoveryBanner\(\);[\s\S]*?return true;/);

  const resolveBody = extractFunctionBody(mainSource, 'async function resolvePendingBeforeBoundary(action, sessionId = null)');
  assert.doesNotMatch(resolveBody, /Recover, recapture, or discard unsaved annotations before changing sessions\./);
  assert.doesNotMatch(resolveBody, /return 'cancel';/);
});
```

- [ ] **Step 3: Add tests that the banner is based on commented items**

Add this test after the passive recovery test:

```js
test('pending recovery banner appears only for commented draft items and uses resume copy', () => {
  const bannerBody = extractFunctionBody(mainSource, 'function renderPendingRecoveryBanner()');
  assert.match(bannerBody, /const summary = draftRecoverySummary\(pendingRecovery\);/);
  assert.match(bannerBody, /summary\.commented === 0/);
  assert.match(bannerBody, /Resume draft/);
  assert.match(bannerBody, /pins without comments/);
  assert.doesNotMatch(bannerBody, /Recover restores the frozen preview and pins from this session\./);
  assert.doesNotMatch(bannerBody, /data-recover-pending/);
});
```

- [ ] **Step 4: Add tests that handoff uses written items only**

At the top of `scripts/pendingItemRecovery-test.mjs`, add this source read beside the existing `mainSource`, `historySource`, and `annotationsSource` declarations:

```js
const promptSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/prompt.js'), 'utf8');
```

Then add this test near the prompt-related tests:

```js
test('handoff persists written draft items without failing on pin-only items', () => {
  const persistCollectBody = extractFunctionBody(promptSource, 'async function persistAndCollectItemIds()');
  assert.match(persistCollectBody, /const writtenDraftItems = commentedDraftItems\(draftItemList\(\)\);/);
  assert.match(persistCollectBody, /if \(writtenDraftItems\.length === 0\)/);
  assert.match(persistCollectBody, /await persistPendingFeedbackItems\(\{[\s\S]*?onlyWrittenComments: true[\s\S]*?keepResidualDraftActive: options\.keepResidualDraftActive !== false[\s\S]*?\}\);/);
  assert.doesNotMatch(persistCollectBody, /draftItemList\(\)\.some\(item => !hasWrittenAnnotationComment\(item\)\)/);

  const persistPendingBody = extractFunctionBody(annotationsSource, 'async function persistPendingFeedbackItems(options = {})');
  assert.match(persistPendingBody, /const residualPinOnlyItems = onlyWrittenComments \? pinOnlyDraftItems\(items\) : \[\];/);
  assert.match(persistPendingBody, /saveResidualPinOnlyDraft\(residualPinOnlyItems,\s*\{ keepActive: keepResidualDraftActive \}\);/);
  assert.match(annotationsSource, /workspaceId: ports\.ids\.nextWorkspaceId\(\)/);

  const copyBody = extractFunctionBody(promptSource, 'async function copyPrompt()');
  const sendBody = extractFunctionBody(promptSource, 'async function sendAgentPrompt()');
  assert.match(copyBody, /persistAndCollectItemIds\(\)/);
  assert.match(sendBody, /persistAndCollectItemIds\(\{ keepResidualDraftActive: false \}\)/);
});
```

- [ ] **Step 5: Run the focused test and confirm RED**

Run:

```bash
npm run console:pending:test
```

Expected: FAIL because the helper functions, resume copy, passive boundary behavior, and mixed handoff logic do not exist yet.

- [ ] **Step 6: Commit the failing tests**

```bash
git add scripts/pendingItemRecovery-test.mjs
git commit -m "test(console): cover quiet draft recovery"
```

## Task 2: Add Draft Classification Helpers

**Files:**
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/prompt.js`

- [ ] **Step 1: Add helpers beside `hasWrittenAnnotationComment`**

In `fixthis-mcp/src/main/console/annotations.js`, replace:

```js
function hasWrittenAnnotationComment(item) {
  return Boolean(String(item?.comment || '').trim());
}
```

with:

```js
function hasWrittenAnnotationComment(item) {
  return Boolean(String(item?.comment || '').trim());
}

function draftItemsFromValue(value) {
  if (Array.isArray(value)) return value;
  return Array.isArray(value?.items) ? value.items : [];
}

function commentedDraftItems(value) {
  return draftItemsFromValue(value).filter(hasWrittenAnnotationComment);
}

function pinOnlyDraftItems(value) {
  return draftItemsFromValue(value).filter(item => !hasWrittenAnnotationComment(item));
}

function draftRecoverySummary(value) {
  const items = draftItemsFromValue(value);
  const commented = commentedDraftItems(items);
  const pinOnly = pinOnlyDraftItems(items);
  return {
    total: items.length,
    commented: commented.length,
    pinOnly: pinOnly.length,
  };
}

function hasCommentedDraftItems(value) {
  return draftRecoverySummary(value).commented > 0;
}
```

- [ ] **Step 2: Make module dependencies explicit**

Update the file headers so the topo-sort contract reflects the new helper usage.

In `fixthis-mcp/src/main/console/history.js`, change:

```js
// @requires state.js, draftWorkspace.js
```

to:

```js
// @requires state.js, draftWorkspace.js, annotations.js
```

In `fixthis-mcp/src/main/console/prompt.js`, change:

```js
// @requires state.js, draftWorkspace.js, draftUseCases.js
```

to:

```js
// @requires state.js, draftWorkspace.js, draftUseCases.js, annotations.js
```

- [ ] **Step 3: Run the focused test and confirm remaining RED**

Run:

```bash
npm run console:pending:test
```

Expected: FAIL only on behavior/copy tests. The helper presence assertions should now pass.

- [ ] **Step 4: Commit helper changes**

```bash
git add fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/console/history.js fixthis-mcp/src/main/console/prompt.js
git commit -m "feat(console): classify draft recovery items"
```

## Task 3: Make Passive Recovery Non-Blocking and Rename the Banner

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`

- [ ] **Step 1: Change pending recovery state checks to care about commented items**

In `fixthis-mcp/src/main/console/main.js`, replace:

```js
function hasPendingRecoveryItems() {
  return pendingRecoveryItems(pendingRecovery).length > 0;
}
```

with:

```js
function hasPendingRecoveryItems() {
  return pendingRecoveryItems(pendingRecovery).length > 0;
}

function hasCommentedPendingRecoveryItems() {
  return hasCommentedDraftItems(pendingRecovery);
}
```

- [ ] **Step 2: Make `requirePendingRecoveryChoiceBeforeSessionChange` non-blocking**

Replace the full function with:

```js
function requirePendingRecoveryChoiceBeforeSessionChange() {
  if (hasCommentedPendingRecoveryItems()) renderPendingRecoveryBanner();
  return true;
}
```

- [ ] **Step 3: Make passive recovered drafts non-blocking in `resolvePendingBeforeBoundary`**

Inside `resolvePendingBeforeBoundary`, replace:

```js
if (hasPendingRecoveryItems() && !hasActivePending) {
  renderPendingRecoveryBanner();
  showError('Recover, recapture, or discard unsaved annotations before changing sessions.');
  return 'cancel';
}
```

with:

```js
if (hasPendingRecoveryItems() && !hasActivePending) {
  renderPendingRecoveryBanner();
  return 'continue';
}
```

Then add this branch immediately after `const pendingSessionId = draftWorkspace?.context?.sessionId || null;`:

```js
const activeItems = draftWorkspaceItems(draftWorkspace);
const activeCommentedItems = commentedDraftItems(activeItems);
if (hasActivePending && activeCommentedItems.length === 0) {
  createBrowserDraftPorts().storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
  if (pendingSessionId) activePendingMirrorSessions.add(pendingSessionId);
  replaceDraftWorkspace(createEmptyDraftWorkspace());
  return 'continue';
}
```

This preserves pin-only active drafts locally and continues through new-session, close-session, or open-session boundaries without a prompt.

- [ ] **Step 4: Update the recovery banner visibility and copy**

In `renderPendingRecoveryBanner`, replace the summary/detail block with:

```js
const summary = draftRecoverySummary(pendingRecovery);
if (!pendingRecovery || !summary.total || summary.commented === 0 || draftFlow() || draftItemList().length) {
  banner.hidden = true;
  return;
}
const canResume = hasRecoverablePreviewContext(pendingRecovery);
const commentLabel = countLabel(summary.commented, 'draft comment', 'draft comments');
const pinLabel = summary.pinOnly > 0
  ? ' · ' + countLabel(summary.pinOnly, 'pin without comment', 'pins without comments')
  : '';
const detail = canResume
  ? 'Resume the local draft for this session.'
  : 'Recapture the current app screen to continue this local draft.';
```

Then replace the banner HTML action block with:

```js
banner.innerHTML =
  '<div class="pending-recovery-copy" data-pending-recovery-copy>' +
    '<strong>' + escapeHtml(commentLabel + pinLabel) + '</strong>' +
    '<div>' + escapeHtml(detail) + '</div>' +
  '</div>' +
  '<div class="annotation-actions pending-recovery-actions">' +
    (canResume ? '<button type="button" class="annotation-done" data-resume-pending>Resume draft</button>' : '') +
    '<button type="button" class="annotation-done" data-recapture-pending>Recapture</button>' +
    '<button type="button" class="annotation-danger" data-clear-pending>Clear local draft</button>' +
  '</div>';
```

Replace the old recover button binding:

```js
banner.querySelector('[data-recover-pending]')?.addEventListener('click', () => {
```

with:

```js
banner.querySelector('[data-resume-pending]')?.addEventListener('click', () => {
```

Replace the discard binding selector:

```js
banner.querySelector('[data-discard-pending]')?.addEventListener('click', () => {
```

with:

```js
banner.querySelector('[data-clear-pending]')?.addEventListener('click', () => {
```

- [ ] **Step 5: Run the focused test and confirm boundary/banner tests pass**

Run:

```bash
npm run console:pending:test
```

Expected: FAIL only on mixed handoff/residual pin-only persistence if Task 4 is not done yet.

- [ ] **Step 6: Commit recovery UX behavior**

```bash
git add fixthis-mcp/src/main/console/main.js
git commit -m "fix(console): make draft recovery non-blocking"
```

## Task 4: Split History Counts for Commented Drafts and Pin-Only Drafts

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `scripts/pendingItemRecovery-test.mjs`

- [ ] **Step 1: Add a history contract test for split counts**

Add this test after `history counts inactive pending recovery without migrating storage`:

```js
test('history separates commented draft and pin-only recovery counts', () => {
  const summaryBody = extractFunctionBody(historySource, 'function pendingHistorySummaryForSession(session)');
  assert.match(summaryBody, /const summary = draftRecoverySummary\(items\);/);
  assert.match(summaryBody, /summary\.commented/);
  assert.match(summaryBody, /summary\.pinOnly/);
  assert.match(summaryBody, /pins without comments/);
});
```

- [ ] **Step 2: Add a history summary helper**

In `fixthis-mcp/src/main/console/history.js`, add this after `historyRecoveryItemsForSession`:

```js
function pendingHistorySummaryForSession(session) {
  const items = pendingHistoryItemsForSession(session);
  const summary = draftRecoverySummary(items);
  if (!summary.total) return '';
  const parts = [];
  if (summary.commented) parts.push(countLabel(summary.commented, 'draft comment', 'draft comments'));
  if (summary.pinOnly) parts.push(countLabel(summary.pinOnly, 'pin without comment', 'pins without comments'));
  return parts.join(' · ');
}
```

- [ ] **Step 3: Show split summary in session rows**

In `renderSessionsListFromPayload`, find the session row markup that currently displays open/done summary text. Add the pending summary as a subtle line only when non-empty:

```js
const pendingSummary = pendingHistorySummaryForSession(session);
```

Then include this in the row body near the existing summary:

```js
+ (pendingSummary ? '<div class="session-pending-summary">' + escapeHtml(pendingSummary) + '</div>' : '') +
```

Use the existing row body string construction around `formatSessionSummary(session)`; do not add a new card or nested panel.

- [ ] **Step 4: Add compact styling for the split summary**

Add this to `fixthis-mcp/src/main/resources/console/styles.css` near the session row text styles:

```css
.session-pending-summary {
  margin-top: 3px;
  color: var(--txt-1);
  font-size: 11px;
  line-height: 1.25;
  overflow-wrap: anywhere;
}
```

- [ ] **Step 5: Run the focused test and confirm history assertions pass**

Run:

```bash
npm run console:pending:test
```

Expected: history split-count assertions pass. If the CSS file changed, generated asset check will still fail until Task 6 rebuilds.

- [ ] **Step 6: Commit history count changes**

```bash
git add fixthis-mcp/src/main/console/history.js fixthis-mcp/src/main/resources/console/styles.css scripts/pendingItemRecovery-test.mjs
git commit -m "feat(console): split draft history counts"
```

## Task 5: Persist Written Items Without Losing Pin-Only Residual Drafts

**Files:**
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `scripts/pendingItemRecovery-test.mjs`

- [ ] **Step 1: Add a residual persistence helper**

In `fixthis-mcp/src/main/console/annotations.js`, add this before `persistPendingFeedbackItems`:

```js
function saveResidualPinOnlyDraft(items, options = {}) {
  if (!draftWorkspace?.workspaceId || !items.length) return;
  const ports = createBrowserDraftPorts();
  const residualWorkspace = {
    ...draftWorkspace,
    workspaceId: ports.ids.nextWorkspaceId(),
    revision: 1,
    items,
    history: { undoStack: [], redoStack: [] },
  };
  ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(residualWorkspace));
  activePendingMirrorSessions.add(residualWorkspace.context?.sessionId);
  if (options.keepActive) {
    replaceDraftWorkspace(residualWorkspace);
    persistCurrentPendingState();
  }
  return residualWorkspace;
}
```

The residual workspace uses a fresh workspace ID so `Save to MCP` can reset the current composer without deleting the stored pin-only residual.

- [ ] **Step 2: Preserve pin-only residuals during written-only persistence**

In `persistPendingFeedbackItems`, add this after the existing option reads:

```js
const keepResidualDraftActive = options.keepResidualDraftActive !== false;
```

Then add this after `const items = draftWorkspaceItems(draftWorkspace);`:

```js
const residualPinOnlyItems = onlyWrittenComments ? pinOnlyDraftItems(items) : [];
```

Then replace the validation:

```js
if (!allowFallbackComments && !onlyWrittenComments && !allowBlankComments && items.some(item => !String(item.comment || '').trim())) throw new Error('Add a comment to every annotation before saving.');
if (onlyWrittenComments && !items.some(hasWrittenAnnotationComment)) throw new Error('Add a comment to at least one annotation before sending.');
```

with:

```js
if (!allowFallbackComments && !onlyWrittenComments && !allowBlankComments && items.some(item => !String(item.comment || '').trim())) throw new Error('Add a comment to every annotation before saving.');
if (onlyWrittenComments && !commentedDraftItems(items).length) throw new Error('Add a comment to at least one annotation before sending.');
```

After the successful session branch:

```js
if (result?.result?.session) {
  setConsoleSession(result.result.session);
  setConsolePreview(null);
  return state.session;
}
```

change it to:

```js
if (result?.result?.session) {
  setConsoleSession(result.result.session);
  setConsolePreview(null);
  if (onlyWrittenComments) saveResidualPinOnlyDraft(residualPinOnlyItems, { keepActive: keepResidualDraftActive });
  return state.session;
}
```

- [ ] **Step 3: Update Copy Prompt / Save to MCP collection**

In `fixthis-mcp/src/main/console/prompt.js`, change the function signature:

```js
async function persistAndCollectItemIds() {
```

to:

```js
async function persistAndCollectItemIds(options = {}) {
```

Then replace this block:

```js
if (draftFlow()) {
    flushFocusedPendingComment();
    if (draftItemList().some(item => !hasWrittenAnnotationComment(item))) {
        throw new Error('Add a comment to every annotation before saving.');
    }
    await persistPendingFeedbackItems();
}
```

with:

```js
if (draftFlow()) {
    flushFocusedPendingComment();
    const writtenDraftItems = commentedDraftItems(draftItemList());
    if (writtenDraftItems.length === 0) {
        throw new Error('Add a comment to at least one annotation before sending.');
    }
    await persistPendingFeedbackItems({
        onlyWrittenComments: true,
        keepResidualDraftActive: options.keepResidualDraftActive !== false,
    });
}
```

In `sendAgentPrompt`, replace:

```js
const itemIds = await persistAndCollectItemIds();
```

with:

```js
const itemIds = await persistAndCollectItemIds({ keepResidualDraftActive: false });
```

Leave `copyPrompt` calling `persistAndCollectItemIds()` so Copy Prompt keeps pin-only residuals visible in the composer.

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run:

```bash
npm run console:pending:test
```

Expected: PASS.

- [ ] **Step 5: Commit handoff behavior**

```bash
git add fixthis-mcp/src/main/console/annotations.js fixthis-mcp/src/main/console/prompt.js scripts/pendingItemRecovery-test.mjs
git commit -m "fix(console): hand off written draft items only"
```

## Task 6: Rebuild Assets and Verify Console Contracts

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/main/resources/console/app.js.map`
- Modify: `fixthis-mcp/src/main/resources/console/console-build-meta.json`

- [ ] **Step 1: Run focused console suites**

Run:

```bash
npm run console:pending:test
npm run console:session:test
npm run console:draft:test
```

Expected: PASS for all three suites.

- [ ] **Step 2: Check generated assets before rebuilding**

Run:

```bash
node scripts/build-console-assets.mjs --check
```

Expected: FAIL if source changes have not been bundled yet.

- [ ] **Step 3: Rebuild generated console assets**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
```

Expected: generated `app.js`, `app.js.map`, and `console-build-meta.json` update.

- [ ] **Step 4: Verify generated assets are current**

Run:

```bash
node scripts/build-console-assets.mjs --check
```

Expected: PASS.

- [ ] **Step 5: Run final focused verification**

Run:

```bash
npm run console:test:fast
```

Expected: PASS.

- [ ] **Step 6: Commit generated assets**

```bash
git add fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "chore(console): rebuild quiet draft recovery assets"
```

## Self-Review Notes

- Spec coverage:
  - Pin-only drafts quiet: Task 3.
  - Commented drafts resumable with split counts: Tasks 3 and 4.
  - No passive recovery blocking: Task 3.
  - Mixed handoff persists only written feedback while preserving pin-only residuals: Task 5.
  - Compatibility with existing storage schemas: no storage schema changes in any task.

- Placeholder scan:
  - The plan contains exact paths, function names, code snippets, commands, expected failures, expected passes, and commit messages.

- Type consistency:
  - Classification helpers all accept either an item array or an envelope with `items`.
  - `draftRecoverySummary(value)` returns numeric `total`, `commented`, and `pinOnly` properties used consistently across tasks.
