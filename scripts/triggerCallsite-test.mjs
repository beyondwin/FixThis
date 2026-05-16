import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const readConsole = (name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8');

test('Esc shortcut resolves through Trigger.ESCAPE_KEY', () => {
  const source = readConsole('shortcuts.js');
  assert.match(source, /@requires[^\n]*boundaryTriggers\.js/);
  assert.match(source, /deriveEditorState\(/);
  assert.match(source, /resolveTrigger\(Trigger\.ESCAPE_KEY/);
});

test('session switch preflights through Trigger.SESSION_SWITCH before lifecycle boundary', () => {
  const source = readConsole('history.js');
  assert.match(source, /resolveTrigger\(Trigger\.SESSION_SWITCH/);
  assert.match(source, /resolvePendingBeforeBoundary\('open-session',\s*sessionId\)/);
});

test('beforeunload derives editor state and delegates policy through shouldGuardUnload', () => {
  const mainSource = readConsole('main.js');
  const guardSource = readConsole('beforeunloadGuard.js');

  assert.match(mainSource, /deriveEditorState\(/);
  assert.match(mainSource, /shouldGuardUnload\(draftItemList\(\)\.length,\s*editorState\)/);
  assert.match(guardSource, /boundaryPolicy\(Trigger\.BROWSER_REFRESH/);
});
