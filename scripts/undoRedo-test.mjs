import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const undoRedoSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/undoRedo.js'), 'utf8');
const factory = new Function(`${undoRedoSrc}; return { createHistory, recordAdd, recordDelete, recordUpdate, undo, redo, UNDO_MAX_DEPTH };`);
const m = factory();

test('undo a delete reinserts at original sequenceNumber position', () => {
  const state = { draftFeedbackItems: [{ itemId: 'i1', sequenceNumber: 1, comment: 'a' }] };
  const h = m.createHistory();
  m.recordDelete(h, state.draftFeedbackItems[0]);
  state.draftFeedbackItems = [];
  m.undo(h, state);
  assert.equal(state.draftFeedbackItems.length, 1);
  assert.equal(state.draftFeedbackItems[0].itemId, 'i1');
});

test('redo a delete reapplies the deletion', () => {
  const state = { draftFeedbackItems: [{ itemId: 'i1', sequenceNumber: 1 }] };
  const h = m.createHistory();
  m.recordDelete(h, state.draftFeedbackItems[0]);
  state.draftFeedbackItems = [];
  m.undo(h, state);
  m.redo(h, state);
  assert.equal(state.draftFeedbackItems.length, 0);
});

test('redo a local annotationId delete removes the same local item', () => {
  const state = {
    draftFeedbackItems: [
      { annotationId: 'local-1', comment: 'first' },
      { annotationId: 'local-2', comment: 'second' },
    ],
  };
  const h = m.createHistory();
  m.recordDelete(h, state.draftFeedbackItems[1], 1);
  state.draftFeedbackItems.splice(1, 1);
  m.undo(h, state);
  assert.deepEqual(state.draftFeedbackItems.map((item) => item.annotationId), ['local-1', 'local-2']);
  m.redo(h, state);
  assert.deepEqual(state.draftFeedbackItems.map((item) => item.annotationId), ['local-1']);
});

test('history capped at MAX_DEPTH=50', () => {
  const h = m.createHistory();
  for (let i = 0; i < 100; i++) m.recordAdd(h, { itemId: `i${i}` });
  assert.equal(h.undoStack.length, 50);
});

test('undo on empty stack returns false (no throw)', () => {
  const state = { draftFeedbackItems: [] };
  const h = m.createHistory();
  assert.equal(m.undo(h, state).applied, false);
});

test('update undo restores previous comment', () => {
  const state = { draftFeedbackItems: [{ itemId: 'i1', comment: 'old' }] };
  const h = m.createHistory();
  const before = { ...state.draftFeedbackItems[0] };
  state.draftFeedbackItems[0].comment = 'new';
  const after = { ...state.draftFeedbackItems[0] };
  m.recordUpdate(h, before, after);
  m.undo(h, state);
  assert.equal(state.draftFeedbackItems[0].comment, 'old');
});

test('recordAdd then undo removes the added item', () => {
  const state = { draftFeedbackItems: [{ itemId: 'i1' }] };
  const h = m.createHistory();
  m.recordAdd(h, state.draftFeedbackItems[0]);
  m.undo(h, state);
  assert.equal(state.draftFeedbackItems.length, 0);
});

test('redo stack cleared on new record', () => {
  const state = { draftFeedbackItems: [{ itemId: 'i1' }] };
  const h = m.createHistory();
  m.recordDelete(h, state.draftFeedbackItems[0]);
  state.draftFeedbackItems = [];
  m.undo(h, state);  // moves op to redoStack
  assert.equal(h.redoStack.length, 1);
  m.recordAdd(h, { itemId: 'i2' });  // new record clears redo
  assert.equal(h.redoStack.length, 0);
});
