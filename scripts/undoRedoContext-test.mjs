import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const undoRedoSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/undoRedo.js'), 'utf8');
const factory = new Function(`${undoRedoSrc}; return { createHistory, recordAdd, recordDelete, undo, redo };`);
const m = factory();

const contextA = { sessionId: 'a', previewId: 'p1', screenId: 's1', screenFingerprint: 'fp1', deviceSerial: 'd1' };
const contextB = { sessionId: 'b', previewId: 'p2', screenId: 's2', screenFingerprint: 'fp2', deviceSerial: 'd1' };

test('undo rejects operations from a different context', () => {
  const state = { pendingFeedbackItems: [{ annotationId: 'local-1', comment: 'a' }] };
  const h = m.createHistory(contextA);
  m.recordDelete(h, state.pendingFeedbackItems[0], 0, contextA);
  state.pendingFeedbackItems = [];
  const result = m.undo(h, state, contextB);
  assert.deepEqual(result, { applied: false, reason: 'context_mismatch' });
  assert.equal(state.pendingFeedbackItems.length, 0);
  assert.equal(h.undoStack.length, 0);
});

test('undo applies operations from the same context', () => {
  const state = { pendingFeedbackItems: [{ annotationId: 'local-1', comment: 'a' }] };
  const h = m.createHistory(contextA);
  m.recordDelete(h, state.pendingFeedbackItems[0], 0, contextA);
  state.pendingFeedbackItems = [];
  const result = m.undo(h, state, contextA);
  assert.deepEqual(result, { applied: true });
  assert.equal(state.pendingFeedbackItems[0].annotationId, 'local-1');
});
