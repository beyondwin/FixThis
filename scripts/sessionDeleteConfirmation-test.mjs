import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
const row = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/historySessionRow.js'), 'utf8');

test('history row delete handler does not pre-render the boundary dialog', () => {
  assert.doesNotMatch(row, /renderBoundaryDialog\(['"]sessionDelete['"]/);
});

test('deleteHistorySession confirms via promptBoundaryDialogChoice before mutating', () => {
  assert.match(history, /promptBoundaryDialogChoice\(['"]sessionDelete['"]/);
});

test('deleteHistorySession aborts when the confirmation returns cancel', () => {
  assert.match(history, /if \(choice === ['"]cancel['"]\) return/);
});
