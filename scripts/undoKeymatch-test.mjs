import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/undoKeymatch.js'), 'utf8');
const { matchesUndo, matchesRedo } = new Function(`${src}; return { matchesUndo, matchesRedo };`)();

const div = { tagName: 'DIV', isContentEditable: false };
const input = { tagName: 'INPUT', isContentEditable: false };
const textarea = { tagName: 'TEXTAREA', isContentEditable: false };
const editable = { tagName: 'DIV', isContentEditable: true };

test('Cmd+Z matches undo when not in editable field', () => {
  assert.equal(matchesUndo({ metaKey: true, key: 'z', shiftKey: false }, div), true);
});

test('Ctrl+Z also matches undo', () => {
  assert.equal(matchesUndo({ ctrlKey: true, key: 'z', shiftKey: false }, div), true);
});

test('Cmd+Shift+Z does NOT match plain undo', () => {
  assert.equal(matchesUndo({ metaKey: true, key: 'z', shiftKey: true }, div), false);
});

test('Cmd+Shift+Z matches redo', () => {
  assert.equal(matchesRedo({ metaKey: true, key: 'z', shiftKey: true }, div), true);
});

test('plain Z (no modifier) matches neither', () => {
  assert.equal(matchesUndo({ key: 'z' }, div), false);
  assert.equal(matchesRedo({ key: 'z' }, div), false);
});

test('inside <input> the shortcut does NOT match (let typing through)', () => {
  assert.equal(matchesUndo({ metaKey: true, key: 'z' }, input), false);
  assert.equal(matchesRedo({ metaKey: true, key: 'z', shiftKey: true }, input), false);
});

test('inside <textarea> the shortcut does NOT match', () => {
  assert.equal(matchesUndo({ metaKey: true, key: 'z' }, textarea), false);
});

test('inside contenteditable the shortcut does NOT match', () => {
  assert.equal(matchesUndo({ metaKey: true, key: 'z' }, editable), false);
});

test('null event does not throw', () => {
  assert.equal(matchesUndo(null, div), false);
});
