import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/events.js'), 'utf8');
const mainSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');

function body(sourceText, signature) {
  const start = sourceText.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const bodyStart = sourceText.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < sourceText.length; i += 1) {
    if (sourceText[i] === '{') depth += 1;
    if (sourceText[i] === '}') {
      depth -= 1;
      if (depth === 0) return sourceText.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('events subscriber uses EventSource and handles console event names', () => {
  assert.match(source, /new EventSource\(['"]\/api\/events['"]\)/);
  for (const eventName of [
    'snapshot',
    'session-updated',
    'sessions-updated',
    'preview-ready',
    'devices-updated',
    'connection-updated',
  ]) {
    assert.match(source, new RegExp(eventName));
  }
});

test('events subscriber is bundled before console startup and started after refresh', () => {
  assert.match(mainSource.split('\n')[0], /events\.js/);
  assert.match(mainSource, /startConsoleEvents\(\)/);
});

test('session-updated event is fenced to active session before replacing detail state', () => {
  const start = body(source, 'function startConsoleEvents()');
  assert.match(start, /data\.sessionId/);
  assert.match(start, /state\.session\?\.sessionId/);
  assert.match(start, /data\.sessionId === state\.session\?\.sessionId/);
  assert.doesNotMatch(start, /on\('session-updated'[\s\S]*?setConsoleSession\(data\.session\);[\s\S]*?loadPendingRecoveryForCurrentSession\(\);/);
});

test('session-updated closed event clears active draft and preview state', () => {
  const start = body(source, 'function startConsoleEvents()');
  assert.match(start, /isClosedSession\(data\.session\)/);
  assert.match(start, /displayedSessionId\(\) === data\.sessionId/);
  assert.match(start, /clearDisplayedSessionState\(\);[\s\S]*?render\(\);[\s\S]*?return;/);
});

test('snapshot null session clears active draft and preview state', () => {
  const start = body(source, 'function startConsoleEvents()');
  assert.match(start, /const previousDisplayedSessionId = displayedSessionId\(\);/);
  assert.match(start, /if \(!data\.session && previousDisplayedSessionId\) clearDisplayedSessionState\(\);/);
});

test('preview-ready event is ignored when its session does not match active session', () => {
  const start = body(source, 'function startConsoleEvents()');
  assert.match(start, /on\('preview-ready'/);
  assert.match(start, /data\.sessionId !== state\.session\?\.sessionId/);
  assert.match(start, /return;/);
});
