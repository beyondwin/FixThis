import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pendingPersistence.js'), 'utf8');
const mainSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
const historySource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
const renderingSource = [
  'fixthis-mcp/src/main/console/presentation/annotationListView.js',
  'fixthis-mcp/src/main/console/presentation/annotationDetailView.js',
].map(file => readFileSync(resolve(root, file), 'utf8')).join('\n');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
const promptSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/prompt.js'), 'utf8');
const previewSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');

function buildLocalStorageStub() {
  const data = new Map();
  return {
    _data: data,
    getItem(k) { return data.has(k) ? data.get(k) : null; },
    setItem(k, v) { data.set(k, String(v)); },
    removeItem(k) { data.delete(k); },
  };
}

function loadModule(localStorage) {
  const factory = new Function('localStorage', `${source}; return { persistPendingState, restorePendingState, persistPendingItems, restorePendingItems, clearPendingMirror };`);
  return factory(localStorage);
}

function extractFunctionBody(sourceText, signature) {
  const start = sourceText.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const bodyStart = sourceText.indexOf('{', sourceText.indexOf(')', start));
  assert.notEqual(bodyStart, -1, `${signature} body not found`);
  let depth = 0;
  for (let i = bodyStart; i < sourceText.length; i += 1) {
    const ch = sourceText[i];
    if (ch === '{') depth += 1;
    if (ch === '}') {
      depth -= 1;
      if (depth === 0) {
        return sourceText.slice(bodyStart + 1, i);
      }
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('persistPendingState writes a schema v1 envelope with frozen preview context', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  m.persistPendingState('s1', {
    sessionId: 's1',
    previewId: 'p1',
    screen: { screenId: 'screen-1', fingerprint: 'fp' },
    screenshotUrl: '/api/previews/p1/screenshot',
    frozenAtEpochMillis: 1778572260000,
    items: [{ annotationId: 'local-1', comment: 'recovered' }],
  });
  const stored = JSON.parse(ls.getItem('fixthis.pending.s1'));
  assert.equal(stored.schemaVersion, 1);
  assert.equal(stored.sessionId, 's1');
  assert.equal(stored.previewId, 'p1');
  assert.equal(stored.screen.screenId, 'screen-1');
  assert.equal(stored.screenshotUrl, '/api/previews/p1/screenshot');
  assert.equal(stored.frozenAtEpochMillis, 1778572260000);
  assert.equal(stored.items.length, 1);
  assert.equal(stored.items[0].annotationId, 'local-1');
});

test('restorePendingState reads back an envelope with context intact', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  m.persistPendingState('s1', {
    previewId: 'p1',
    screen: { screenId: 'screen-1', fingerprint: 'fp' },
    screenshotUrl: '/api/previews/p1/screenshot',
    frozenAtEpochMillis: 1778572260000,
    items: [{ annotationId: 'local-1', comment: 'recovered' }],
  });
  const restored = m.restorePendingState('s1');
  assert.equal(restored.schemaVersion, 1);
  assert.equal(restored.previewId, 'p1');
  assert.equal(restored.items.length, 1);
  assert.equal(restored.screen.screenId, 'screen-1');
});

test('restorePendingState returns schema v0 envelope for legacy item arrays', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  ls.setItem('fixthis.pending.s1', JSON.stringify([{ itemId: 'i1', comment: 'legacy' }]));
  const restored = m.restorePendingState('s1');
  assert.equal(restored.schemaVersion, 0);
  assert.equal(restored.sessionId, 's1');
  assert.equal(restored.previewId, null);
  assert.equal(restored.screen, null);
  assert.equal(restored.screenshotUrl, null);
  assert.equal(restored.frozenAtEpochMillis, null);
  assert.equal(restored.items.length, 1);
  assert.equal(restored.items[0].comment, 'legacy');
});

test('persistPendingItems remains a compatible wrapper around schema v1 persistence', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  m.persistPendingItems('s1', [{ itemId: 'i1', comment: 'a' }]);
  const stored = JSON.parse(ls.getItem('fixthis.pending.s1'));
  assert.equal(stored.schemaVersion, 1);
  assert.equal(stored.sessionId, 's1');
  assert.equal(stored.previewId, null);
  assert.equal(stored.items.length, 1);
  assert.equal(stored.items[0].itemId, 'i1');
});

test('restorePendingItems reads back only items from any supported payload', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  m.persistPendingState('s1', {
    previewId: 'p1',
    items: [{ itemId: 'i1', comment: 'recovered' }],
  });
  const items = m.restorePendingItems('s1');
  assert.equal(items.length, 1);
  assert.equal(items[0].comment, 'recovered');
});

test('restorePendingItems returns [] when no data exists', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  assert.deepEqual(m.restorePendingItems('s1'), []);
});

test('restorePendingItems returns [] when payload is corrupt', () => {
  const ls = buildLocalStorageStub();
  ls.setItem('fixthis.pending.s1', '{not json');
  const m = loadModule(ls);
  assert.deepEqual(m.restorePendingItems('s1'), []);
});

test('clearPendingMirror removes the entry', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  m.persistPendingItems('s1', [{ itemId: 'i1' }]);
  m.clearPendingMirror('s1');
  assert.equal(ls.getItem('fixthis.pending.s1'), null);
});

test('null/undefined sessionId is a no-op (no throw)', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  m.persistPendingItems(null, [{ itemId: 'i1' }]);
  m.persistPendingItems(undefined, [{ itemId: 'i2' }]);
  assert.equal(ls._data.size, 0);
  assert.deepEqual(m.restorePendingItems(null), []);
});

test('recapture forces a fresh preview before remapping recovered pending items', () => {
  const recaptureBody = extractFunctionBody(mainSource, 'async function recapturePendingRecovery()');
  assert.match(recaptureBody, /clearPreview\(\);[\s\S]*?await startDraftAnnotationFlow\(\);/);
  assert.match(recaptureBody, /const recoveredItems = items\.map/);
  assert.match(recaptureBody, /replaceDraftWorkspace\(\{[\s\S]*?items:\s*recoveredItems/);
  assert.match(recaptureBody, /persistCurrentPendingState\(\);/);
});

test('pending recovery refreshes history summaries after restoring draft items', () => {
  const bannerBody = extractFunctionBody(mainSource, 'function renderPendingRecoveryBanner()');
  assert.match(
    bannerBody,
    /restorePendingRecoveryContext\(pendingRecovery\);[\s\S]*?renderPendingRecoveryBanner\(\);[\s\S]*?render\(\);/,
  );

  const recaptureBody = extractFunctionBody(mainSource, 'async function recapturePendingRecovery()');
  assert.match(
    recaptureBody,
    /replaceDraftWorkspace\(\{[\s\S]*?items:\s*recoveredItems[\s\S]*?\}\);[\s\S]*?render\(\);/,
  );
});

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
  assert.match(resolveBody, /if \(pendingRecoveryItems\(pendingRecovery\)\.length && !hasActivePending\) \{[\s\S]*?renderPendingRecoveryBanner\(\);[\s\S]*?return 'continue';[\s\S]*?\}/);
});

test('pending recovery banner appears for recoverable drafts and uses resume copy', () => {
  const bannerBody = extractFunctionBody(mainSource, 'function renderPendingRecoveryBanner()');
  assert.match(bannerBody, /const summary = draftRecoverySummary\(pendingRecovery\);/);
  assert.doesNotMatch(bannerBody, /summary\.commented === 0/);
  assert.match(bannerBody, /Resume draft/);
  assert.match(bannerBody, /pins without comments/);
  assert.doesNotMatch(bannerBody, /Recover restores the frozen preview and pins from this session\./);
  assert.doesNotMatch(bannerBody, /data-recover-pending/);
});

test('switching sessions keeps the previous session pending recovery mirror', () => {
  const resetBody = extractFunctionBody(annotationsSource, 'function resetComposer');
  const openBody = extractFunctionBody(historySource, 'async function openSession(sessionId)');
  assert.match(annotationsSource, /function resetComposer\(clearFlow\s*=\s*true,\s*clearMirror\s*=\s*true\)/);
  assert.match(resetBody, /const composerSessionId = draftWorkspace\?\.context\?\.sessionId \|\| state\.session\?\.sessionId;/);
  assert.match(resetBody, /if\s*\(clearMirror\)\s*\{[\s\S]*?clearPendingMirror\(composerSessionId\);/);
  assert.match(openBody, /resetComposer\(true,\s*false\);/);
  assert.doesNotMatch(openBody, /resetComposer\(\);/);
});

test('resetComposer deletes schema v2 workspace when clearing flow and mirror', () => {
  const resetBody = extractFunctionBody(annotationsSource, 'function resetComposer');
  assert.match(resetBody, /deleteCurrentDraftWorkspaceStorage\(\);/);
  assert.match(resetBody, /if \(clearFlow\) replaceDraftWorkspace\(createEmptyDraftWorkspace\(\)\);/);
});

test('deletePendingFeedbackItem deletes workspace storage when last draft item is removed', () => {
  const deleteBody = extractFunctionBody(annotationsSource, 'function deletePendingFeedbackItem');
  assert.match(deleteBody, /deleteCurrentDraftWorkspaceStorage\(\);/);
  assert.match(deleteBody, /nextWorkspace\.items\.length === 0/);
});

test('returning to a session with pending mirror loads draft workspace recovery', () => {
  const persistBody = extractFunctionBody(annotationsSource, 'function persistCurrentPendingState()');
  const loadBody = extractFunctionBody(mainSource, 'function loadPendingRecoveryForCurrentSession()');
  const resetBody = extractFunctionBody(annotationsSource, 'function resetComposer');
  assert.match(mainSource, /const activePendingMirrorSessions = new Set\(\);/);
  assert.match(persistBody, /persistCurrentDraftWorkspaceIfNeeded\(\);/);
  assert.match(mainSource, /function loadDraftRecoveryForSession\(sessionId\)/);
  assert.match(mainSource, /storage\.loadWorkspacesForSession\(sessionId\)/);
  assert.match(mainSource, /storage\.migrateLegacyPending\(sessionId\)/);
  assert.match(resetBody, /activePendingMirrorSessions\.delete\(composerSessionId\);/);
  assert.match(loadBody, /loadDraftRecoveryForSession\(sessionId\) \|\| restorePendingState\(sessionId\)/);
  assert.match(loadBody, /pendingRecovery = restoredSummary\.total \? restored : null;/);
});

test('returning to an active pending mirror auto-restores instead of re-showing recovery banner', () => {
  const resolveBody = extractFunctionBody(mainSource, 'async function resolvePendingBeforeBoundary(action, sessionId = null)');
  const loadBody = extractFunctionBody(mainSource, 'function loadPendingRecoveryForCurrentSession()');
  const bannerBody = extractFunctionBody(mainSource, 'function renderPendingRecoveryBanner()');
  assert.match(resolveBody, /activePendingMirrorSessions\.add\(pendingSessionId\);/);
  assert.match(bannerBody, /activePendingMirrorSessions\.add\(recoverySessionId\);/);
  assert.match(
    loadBody,
    /if \(activePendingMirrorSessions\.has\(sessionId\) && pendingRecoveryItems\(restored\)\.length && hasRecoverablePreviewContext\(restored\)\) \{/,
  );
  assert.match(
    loadBody,
    /restorePendingRecoveryContext\(restored\);[\s\S]*?pendingRecovery = null;[\s\S]*?renderPendingRecoveryBanner\(\);[\s\S]*?return;/,
  );
});

test('returning to a recoverable pin-only draft restores it into the selected session view', () => {
  const loadBody = extractFunctionBody(mainSource, 'function loadPendingRecoveryForCurrentSession()');

  assert.match(loadBody, /const restoredSummary = draftRecoverySummary\(restored\);/);
  assert.match(
    loadBody,
    /if \(restoredSummary\.total && restoredSummary\.commented === 0 && hasRecoverablePreviewContext\(restored\)\) \{[\s\S]*?restorePendingRecoveryContext\(restored\);[\s\S]*?pendingRecovery = null;[\s\S]*?renderPendingRecoveryBanner\(\);[\s\S]*?return;/,
  );
});

test('history counts inactive pending recovery without migrating storage', () => {
  const pendingHistoryBody = extractFunctionBody(historySource, 'function pendingHistoryItemsForSession(session)');
  const recoveryHistoryBody = extractFunctionBody(historySource, 'function historyRecoveryItemsForSession(session)');

  assert.match(historySource, /function historyRecoveryItemsForSession\(session\)/);
  assert.match(pendingHistoryBody, /historyRecoveryItemsForSession\(session\)/);
  assert.match(recoveryHistoryBody, /storage\.loadWorkspacesForSession\(sessionId\)/);
  assert.match(recoveryHistoryBody, /restorePendingState\(sessionId\)/);
  assert.doesNotMatch(recoveryHistoryBody, /migrateLegacyPending/);
  assert.doesNotMatch(recoveryHistoryBody, /loadDraftRecoveryForSession/);
});

test('history separates commented draft and pin-only recovery counts', () => {
  const summaryBody = extractFunctionBody(historySource, 'function pendingHistorySummaryForSession(session)');
  assert.match(summaryBody, /const summary = draftRecoverySummary\(items\);/);
  assert.match(summaryBody, /summary\.commented/);
  assert.match(summaryBody, /summary\.pinOnly/);
  assert.match(summaryBody, /pins without comments/);
});

test('prompt persistence keeps residual pin-only items for copy but discards them for Save to MCP', () => {
  const persistCollectBody = extractFunctionBody(promptSource, 'async function persistAndCollectItemIds(options = {})');
  assert.doesNotMatch(
    persistCollectBody,
    /draftItemList\(\)\.some\(item => !hasWrittenAnnotationComment\(item\)\)[\s\S]*?throw new Error\('Add a comment to every annotation before saving\.'\);/,
  );
  assert.match(
    persistCollectBody,
    /const writtenDraftItems = commentedDraftItems\(draftItemList\(\)\);[\s\S]*?if \(writtenDraftItems\.length === 0\)/,
  );
  assert.match(
    persistCollectBody,
    /persistPendingFeedbackItems\(\{[\s\S]*?onlyWrittenComments: true[\s\S]*?keepResidualDraftActive: options\.keepResidualDraftActive !== false[\s\S]*?\}\);/,
  );

  const persistPendingBody = extractFunctionBody(annotationsSource, 'async function persistPendingFeedbackItems(options = {})');
  assert.match(persistPendingBody, /const residualPinOnlyItems = onlyWrittenComments \? pinOnlyDraftItems\(items\) : \[\];/);
  assert.match(
    persistPendingBody,
    /if \(onlyWrittenComments && keepResidualDraftActive\) saveResidualPinOnlyDraft\(residualPinOnlyItems,\s*\{ keepActive: true \}\);/,
  );
  assert.match(annotationsSource, /workspaceId: ports\.ids\.nextWorkspaceId\(\)/);

  const copyBody = extractFunctionBody(promptSource, 'async function copyPrompt()');
  const sendBody = extractFunctionBody(promptSource, 'async function sendAgentPrompt()');
  assert.match(copyBody, /persistAndCollectItemIds\(\)/);
  assert.match(sendBody, /persistAndCollectItemIds\(\{ keepResidualDraftActive: false \}\)/);
});

test('pending annotation detail edits write through to recovery envelope', () => {
  const detailBody = extractFunctionBody(renderingSource, 'function renderAnnotationDetail(item, index)');
  assert.match(detailBody, /item\.label\s*=\s*event\.target\.value;[\s\S]*?persistCurrentPendingState\(\);/);
  assert.match(detailBody, /item\.comment\s*=\s*event\.target\.value;[\s\S]*?persistCurrentPendingState\(\);/);
  assert.match(detailBody, /item\.severity\s*=\s*button\.dataset\.setSeverity;[\s\S]*?persistCurrentPendingState\(\);/);
  assert.match(detailBody, /item\.status\s*=\s*button\.dataset\.setStatus;[\s\S]*?persistCurrentPendingState\(\);/);
});

test('annotation detail selection does not steal focus into the comment textarea', () => {
  const pendingDetailBody = extractFunctionBody(renderingSource, 'function renderAnnotationDetail(item, index)');
  assert.doesNotMatch(pendingDetailBody, /focusCommentInputAtEnd\(commentInput\);/);
  assert.doesNotMatch(pendingDetailBody, /commentInput\.focus\(\);/);

  const savedDetailBody = extractFunctionBody(renderingSource, 'function renderSavedAnnotationDetail(item, index)');
  assert.doesNotMatch(savedDetailBody, /focusCommentInputAtEnd\(commentInput\);/);
  assert.doesNotMatch(savedDetailBody, /if \(editable\) commentInput\.focus\(\);/);
  assert.doesNotMatch(renderingSource, /function focusCommentInputAtEnd\(commentInput\)/);
});

test('saved annotation back navigation does not wait for persistence', () => {
  const savedDetailBody = extractFunctionBody(renderingSource, 'function renderSavedAnnotationDetail(item, index)');
  const backBindingIndex = savedDetailBody.indexOf("draftItems.querySelectorAll('[data-back-saved-annotations]')");
  assert.notEqual(backBindingIndex, -1, 'saved detail back button binding not found');
  const backHandlerBody = savedDetailBody.slice(backBindingIndex);
  const goBackCallIndex = backHandlerBody.indexOf('goBack();');
  const persistCallIndex = backHandlerBody.indexOf('persistSavedEvidenceItem(item, editSessionId)');
  assert.ok(goBackCallIndex >= 0, 'back handler should leave detail view immediately');
  assert.ok(persistCallIndex >= 0, 'back handler should still persist editable changes');
  assert.ok(goBackCallIndex < persistCallIndex, 'navigation must happen before best-effort persistence');
  assert.match(backHandlerBody, /goBack\(\);\s*if \(editable\) \{\s*persistSavedEvidenceItem\(item, editSessionId\)\.catch\(showError\);/);
  assert.doesNotMatch(backHandlerBody, /persistSavedEvidenceItem\(item, editSessionId\)[\s\S]*?\.then\(goBack\)/);
  assert.match(savedDetailBody, /event\.relatedTarget\?\.hasAttribute\?\.\('data-back-saved-annotations'\)/);
});

test('pending detail comments are not overwritten by the hidden composer before persistence', () => {
  const flushBody = extractFunctionBody(annotationsSource, 'function flushFocusedPendingComment()');
  assert.match(flushBody, /pendingItems\.querySelector\('#annotationCommentInput'\)/);
  assert.match(flushBody, /item\.comment\s*=\s*commentInput\s*\?\s*commentInput\.value\s*:\s*comment\.value;/);
});

test('new pending annotations record undo history before persistence', () => {
  const createBody = extractFunctionBody(annotationsSource, 'function createAnnotationFromSelection(selection)');
  assert.match(createBody, /addDraftItem\(draftWorkspace,\s*selection,\s*ports\)/);
  assert.match(createBody, /replaceDraftWorkspace\(nextWorkspace\);/);
});

test('using latest stale frame preserves pending annotations while recapturing', () => {
  assert.match(previewSource, /const pendingItems = draftWorkspaceItems\(draftWorkspace\)\.slice\(\);[\s\S]*?clearPreview\(\);[\s\S]*?await startDraftAnnotationFlow\(\);[\s\S]*?replaceDraftWorkspace\(\{[\s\S]*?items:\s*pendingItems[\s\S]*?persistCurrentPendingState\(\);/);
  assert.doesNotMatch(previewSource, /data-use-latest[\s\S]*?draftItemList\.length\s*=\s*0;/);
  assert.doesNotMatch(previewSource, /data-use-latest[\s\S]*?clearPendingMirror\(state\.session\?\.sessionId\);/);
});

test('stale canvas notice yields to interaction blocked overlay', () => {
  const body = extractFunctionBody(previewSource, 'function renderStaleFrameNotice()');
  assert.match(body, /state\.connection\?\.interactionBlockedReason/);
  assert.match(body, /root\.hidden = true;[\s\S]*?return;/);
});
