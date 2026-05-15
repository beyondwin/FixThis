import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['domain/consoleBoundary.js'],
  symbols: [
    'okBoundary',
    'errorBoundary',
    'normalizeSessionPayload',
    'normalizePreviewPayload',
    'normalizeDraftItemPayload',
    'normalizeStoredJson',
  ],
});

test('normalizeSessionPayload requires a session id', () => {
  assert.deepEqual(
    m.normalizeSessionPayload({ status: 'open' }),
    m.errorBoundary('missing_session_id', 'Session payload is missing sessionId.'),
  );
});

test('normalizeSessionPayload preserves supported fields', () => {
  const result = m.normalizeSessionPayload({
    sessionId: 'session-a',
    status: 'open',
    items: [{ itemId: 'item-a' }],
    screens: [{ screenId: 'screen-a' }],
  });
  assert.equal(result.ok, true);
  assert.equal(result.value.sessionId, 'session-a');
  assert.deepEqual(result.value.items, [{ itemId: 'item-a' }]);
  assert.deepEqual(result.value.screens, [{ screenId: 'screen-a' }]);
});

test('normalizePreviewPayload requires preview and screen ids', () => {
  assert.equal(m.normalizePreviewPayload({ previewId: 'preview-a' }).ok, false);
  assert.equal(
    m.normalizePreviewPayload({ previewId: 'preview-a', screen: { screenId: 'screen-a' } }).ok,
    true,
  );
});

test('normalizeDraftItemPayload creates stable ids and comment strings', () => {
  const result = m.normalizeDraftItemPayload({
    itemId: 'item-a',
    annotationId: 'pin-a',
    comment: 123,
    selection: { type: 'node' },
    targetEvidence: { label: 'Save' },
  });
  assert.equal(result.ok, true);
  assert.equal(result.value.itemId, 'item-a');
  assert.equal(result.value.annotationId, 'pin-a');
  assert.equal(result.value.comment, '123');
});

test('normalizeStoredJson handles malformed storage text', () => {
  assert.deepEqual(
    m.normalizeStoredJson('{bad json'),
    m.errorBoundary('invalid_storage_payload', 'Storage payload is not valid JSON.'),
  );
});
