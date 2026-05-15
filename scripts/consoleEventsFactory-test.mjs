import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['domain/consoleEvents.js'],
  symbols: ['ConsoleEvents'],
});

test('sessionRowClicked creates a frozen canonical event', () => {
  const event = m.ConsoleEvents.sessionRowClicked('session-a');
  assert.deepEqual(event, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-a' });
  assert.equal(Object.isFrozen(event), true);
});

test('draftCommentChanged normalizes comment text', () => {
  assert.deepEqual(
    m.ConsoleEvents.draftCommentChanged('item-a', 123),
    { type: 'DRAFT_COMMENT_CHANGED', itemId: 'item-a', comment: '123' },
  );
});

test('previewCaptureSucceeded requires session id and preview', () => {
  assert.throws(
    () => m.ConsoleEvents.previewCaptureSucceeded('', { previewId: 'preview-a' }, 1),
    /sessionId is required/,
  );
  assert.deepEqual(
    m.ConsoleEvents.previewCaptureSucceeded('session-a', { previewId: 'preview-a' }, 2),
    {
      type: 'PREVIEW_CAPTURE_SUCCEEDED',
      sessionId: 'session-a',
      preview: { previewId: 'preview-a' },
      generation: 2,
    },
  );
});
