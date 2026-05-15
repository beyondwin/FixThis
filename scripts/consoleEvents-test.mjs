import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const source = readFileSync('fixthis-mcp/src/main/console/events.js', 'utf8');
const mainSource = readFileSync('fixthis-mcp/src/main/console/main.js', 'utf8');

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
