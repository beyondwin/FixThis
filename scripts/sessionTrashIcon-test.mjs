import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
const historySessionRow = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/historySessionRow.js'), 'utf8');
const styles = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');

test('session rows use a trash icon button instead of an x glyph', () => {
  assert.match(historySessionRow, /class="session-row-delete icon-button"/);
  assert.match(historySessionRow, /aria-label="Delete session"/);
  assert.match(historySessionRow, /<svg viewBox="0 0 24 24"/);
  assert.doesNotMatch(history, />×<\/button>/);
});

test('session delete click forwards to deleteHistorySession with confirmation context', () => {
  assert.match(historySessionRow, /deleteHistorySession\(button\.dataset\.deleteSessionId/);
  assert.match(historySessionRow, /event\.stopPropagation\(\);/);
});

test('trash icon has stable hover and focus styles', () => {
  assert.match(styles, /\.session-row-delete/);
  assert.match(styles, /\.session-row:hover \.session-row-delete/);
  assert.match(styles, /\.session-row-delete:focus-visible/);
});
