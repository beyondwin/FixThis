import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspaceSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftWorkspace.js'), 'utf8');
const historySrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftWorkspaceHistory.js'), 'utf8');
const factory = new Function(`${workspaceSrc}; ${historySrc}; return {
  createEmptyDraftHistory,
  recordDraftAdd,
  recordDraftDelete,
  undoDraftHistory,
  redoDraftHistory
};`);
const m = factory();

test('history records draftItemId operations without array-index identity', () => {
  let items = [{ draftItemId: 'draft-1', comment: 'a' }];
  let history = m.createEmptyDraftHistory();
  history = m.recordDraftDelete(history, items[0], 0);
  items = [];

  const undo = m.undoDraftHistory(history, items);
  assert.equal(undo.applied, true);
  assert.equal(undo.items[0].draftItemId, 'draft-1');

  const redo = m.redoDraftHistory(undo.history, undo.items);
  assert.equal(redo.applied, true);
  assert.equal(redo.items.length, 0);
});

test('history depth is capped at 50', () => {
  let history = m.createEmptyDraftHistory();
  for (let i = 0; i < 55; i += 1) {
    history = m.recordDraftAdd(history, { draftItemId: `draft-${i}` });
  }
  assert.equal(history.undoStack.length, 50);
  assert.equal(history.undoStack[0].after.draftItemId, 'draft-5');
});
