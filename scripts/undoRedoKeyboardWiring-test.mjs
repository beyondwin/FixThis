import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const main = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');

test('Cmd+Z keyboard handler applies DraftWorkspace undo, not a no-op dispatch or direct array mutation', () => {
  assert.doesNotMatch(main, /matchesUndo\(e, active\)[\s\S]{0,80}store\.dispatch\(\{ type: 'UNDO_CLICKED' \}\)/);
  assert.match(main, /matchesUndo\(e, active\)[\s\S]{0,240}applyDraftHistoryUndo\(\)/);
  assert.doesNotMatch(main, /matchesUndo\(e, active\)[\s\S]{0,260}undo\(undoRedoHistory,\s*\{ items: draftItemList\(\) \}/);
});

test('Cmd+Shift+Z keyboard handler applies DraftWorkspace redo, not a no-op dispatch or direct array mutation', () => {
  assert.doesNotMatch(main, /matchesRedo\(e, active\)[\s\S]{0,80}store\.dispatch\(\{ type: 'REDO_CLICKED' \}\)/);
  assert.match(main, /matchesRedo\(e, active\)[\s\S]{0,240}applyDraftHistoryRedo\(\)/);
  assert.doesNotMatch(main, /matchesRedo\(e, active\)[\s\S]{0,260}redo\(undoRedoHistory,\s*\{ items: draftItemList\(\) \}/);
});

test('Keyboard undo shows a toast when the stack is empty', () => {
  const block = main.match(/matchesUndo\(e, active\)[\s\S]{0,500}/);
  assert.ok(block, 'expected to find the Cmd+Z handler block');
  assert.match(block[0], /Nothing to undo/);
});

test('Keyboard redo shows a toast when the stack is empty', () => {
  const block = main.match(/matchesRedo\(e, active\)[\s\S]{0,500}/);
  assert.ok(block, 'expected to find the Cmd+Shift+Z handler block');
  assert.match(block[0], /Nothing to redo/);
});

test('In-toast Undo button shows a toast when the undo stack is empty', () => {
  const block = main.match(/fixthis-undo-toast[\s\S]{0,1500}/);
  assert.ok(block, 'expected to find the in-toast Undo block');
  assert.match(
    block[0],
    /result\.applied[\s\S]{0,400}Nothing to undo|Nothing to undo[\s\S]{0,400}toast\.remove/,
    'expected a Nothing-to-undo toast in the in-toast Undo path'
  );
});
