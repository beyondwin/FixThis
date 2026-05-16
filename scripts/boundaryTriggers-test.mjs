import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['boundaryTriggers.js'],
  symbols: ['Trigger'],
});

test('Trigger exposes the full frozen context-transition enum', () => {
  const expected = [
    'SESSION_SWITCH',
    'SESSION_CREATE',
    'SESSION_DELETE',
    'SCREEN_SWITCH',
    'NEW_CAPTURE',
    'ELEMENT_CLICK',
    'ESCAPE_KEY',
    'BROWSER_REFRESH',
    'TAB_CLOSE',
    'ROUTE_CHANGE',
    'SERVER_DISCONNECT',
    'RECONNECT',
    'BRIDGE_MISMATCH',
    'ACTIVITY_DRIFT',
    'INACTIVITY',
    'EDITOR_BACK',
  ];

  assert.equal(Object.isFrozen(m.Trigger), true);
  for (const key of expected) {
    assert.equal(typeof m.Trigger[key], 'string', `Trigger.${key}`);
  }
});
