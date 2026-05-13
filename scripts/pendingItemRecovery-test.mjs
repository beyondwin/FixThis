import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pendingPersistence.js'), 'utf8');
const mainSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
const historySource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
const renderingSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/rendering.js'), 'utf8');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
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
  const bodyStart = sourceText.indexOf('{', start);
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
  assert.match(recaptureBody, /invalidatePreviewContext\(\);[\s\S]*?await startAddItemsFlow\(\);/);
  assert.match(recaptureBody, /pendingFeedbackItems\s*=\s*items;/);
  assert.match(recaptureBody, /persistCurrentPendingState\(\);/);
});

test('session refresh reloads pending recovery and session switches require a recovery choice', () => {
  const refreshBody = extractFunctionBody(historySource, 'async function refresh()');
  assert.match(refreshBody, /loadPendingRecoveryForCurrentSession\(\);[\s\S]*?render\(\);/);
  const openBody = extractFunctionBody(historySource, 'async function openSession(sessionId)');
  const newBody = extractFunctionBody(historySource, 'async function newSession()');
  const annotateBody = extractFunctionBody(historySource, 'async function enterAnnotateMode()');
  assert.match(openBody, /resolvePendingBeforeBoundary\('open-session',\s*sessionId\)/);
  assert.match(newBody, /resolvePendingBeforeBoundary\('new-session'\)/);
  assert.match(annotateBody, /requirePendingRecoveryChoiceBeforeSessionChange\(\)/);
});

test('switching sessions keeps the previous session pending recovery mirror', () => {
  const resetBody = extractFunctionBody(annotationsSource, 'function resetAnnotationComposerState');
  const openBody = extractFunctionBody(historySource, 'async function openSession(sessionId)');
  assert.match(annotationsSource, /function resetAnnotationComposerState\(clearFlow\s*=\s*true,\s*clearMirror\s*=\s*true\)/);
  assert.match(resetBody, /if\s*\(clearMirror\)\s*\{[\s\S]*?clearPendingMirror\(state\.session\?\.sessionId\);/);
  assert.match(openBody, /resetAnnotationComposerState\(true,\s*false\);/);
  assert.doesNotMatch(openBody, /resetAnnotationComposerState\(\);/);
});

test('returning to a session with an active pending mirror restores without showing recovery again', () => {
  const persistBody = extractFunctionBody(annotationsSource, 'function persistCurrentPendingState()');
  const loadBody = extractFunctionBody(mainSource, 'function loadPendingRecoveryForCurrentSession()');
  const resetBody = extractFunctionBody(annotationsSource, 'function resetAnnotationComposerState');
  assert.match(mainSource, /const activePendingMirrorSessions = new Set\(\);/);
  assert.match(persistBody, /activePendingMirrorSessions\.add\(sessionId\);/);
  assert.match(persistBody, /activePendingMirrorSessions\.delete\(sessionId\);/);
  assert.match(resetBody, /activePendingMirrorSessions\.delete\(state\.session\?\.sessionId\);/);
  assert.match(loadBody, /activePendingMirrorSessions\.has\(sessionId\)[\s\S]*?hasRecoverablePreviewContext\(restored\)/);
  assert.match(loadBody, /restorePendingRecoveryContext\(restored\);[\s\S]*?pendingRecovery\s*=\s*null;[\s\S]*?return;/);
});

test('pending annotation detail edits write through to recovery envelope', () => {
  const detailBody = extractFunctionBody(renderingSource, 'function renderAnnotationDetail(item, index)');
  assert.match(detailBody, /item\.label\s*=\s*event\.target\.value;[\s\S]*?persistCurrentPendingState\(\);/);
  assert.match(detailBody, /item\.comment\s*=\s*event\.target\.value;[\s\S]*?persistCurrentPendingState\(\);/);
  assert.match(detailBody, /item\.severity\s*=\s*button\.dataset\.setSeverity;[\s\S]*?persistCurrentPendingState\(\);/);
  assert.match(detailBody, /item\.status\s*=\s*button\.dataset\.setStatus;[\s\S]*?persistCurrentPendingState\(\);/);
});

test('annotation detail comment focus places the caret after existing text', () => {
  const helperBody = extractFunctionBody(renderingSource, 'function focusCommentInputAtEnd(commentInput)');
  assert.match(helperBody, /commentInput\.focus\(\);/);
  assert.match(helperBody, /const end = commentInput\.value\.length;/);
  assert.match(helperBody, /commentInput\.setSelectionRange\(end,\s*end\);/);

  const pendingDetailBody = extractFunctionBody(renderingSource, 'function renderAnnotationDetail(item, index)');
  assert.match(pendingDetailBody, /focusCommentInputAtEnd\(commentInput\);/);
  assert.doesNotMatch(pendingDetailBody, /commentInput\.focus\(\);/);

  const savedDetailBody = extractFunctionBody(renderingSource, 'function renderSavedAnnotationDetail(item, index)');
  assert.match(savedDetailBody, /if \(editable\) focusCommentInputAtEnd\(commentInput\);/);
  assert.doesNotMatch(savedDetailBody, /if \(editable\) commentInput\.focus\(\);/);
});

test('pending detail comments are not overwritten by the hidden composer before persistence', () => {
  const flushBody = extractFunctionBody(annotationsSource, 'function flushFocusedPendingComment()');
  assert.match(flushBody, /pendingItems\.querySelector\('#annotationCommentInput'\)/);
  assert.match(flushBody, /item\.comment\s*=\s*commentInput\s*\?\s*commentInput\.value\s*:\s*comment\.value;/);
});

test('new pending annotations record undo history before persistence', () => {
  const createBody = extractFunctionBody(annotationsSource, 'function createAnnotationFromSelection(selection)');
  assert.match(createBody, /pendingFeedbackItems\.push\(annotation\);[\s\S]*?recordAdd\(undoRedoHistory,\s*annotation,\s*addItemsFlow\.context\);[\s\S]*?persistCurrentPendingState\(\);/);
});

test('using latest stale frame preserves pending annotations while recapturing', () => {
  assert.match(previewSource, /const pendingItems = pendingFeedbackItems\.slice\(\);[\s\S]*?invalidatePreviewContext\(\);[\s\S]*?await startAddItemsFlow\(\);[\s\S]*?pendingFeedbackItems = pendingItems;[\s\S]*?persistCurrentPendingState\(\);/);
  assert.doesNotMatch(previewSource, /data-use-latest[\s\S]*?pendingFeedbackItems\.length\s*=\s*0;/);
  assert.doesNotMatch(previewSource, /data-use-latest[\s\S]*?clearPendingMirror\(state\.session\?\.sessionId\);/);
});
