import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const stateSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/state.js'), 'utf8');
const apiSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/api.js'), 'utf8');

function extractFunction(source, signaturePrefix) {
  const start = source.indexOf(signaturePrefix);
  assert.notEqual(start, -1, `${signaturePrefix} not found in source`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  assert.fail(`${signaturePrefix} body did not close`);
}

function loadSurfaceReloadConsoleNotice() {
  const helperSource = extractFunction(stateSource, 'function surfaceReloadConsoleNotice(');
  // ConsoleRequestError is defined in api.js — eval that class declaration and
  // hand the helper a closure that references it. The factory returns the
  // helper so tests can call it.
  const errorClassSource = extractFunction(apiSource, 'class ConsoleRequestError');
  const factory = new Function('notificationCenter', 'window', `
    ${errorClassSource}
    ${helperSource}
    return { surfaceReloadConsoleNotice, ConsoleRequestError };
  `);
  return factory;
}

function createFakeNotificationCenter() {
  const calls = [];
  return {
    calls,
    notify(input) { calls.push(input); return input.dedupeKey || 'id'; },
    hide() {},
    clearRecoverable() {},
  };
}

test('surfaceReloadConsoleNotice fires reload_console_403 banner with reload CTA', () => {
  const factory = loadSurfaceReloadConsoleNotice();
  const center = createFakeNotificationCenter();
  let reloaded = 0;
  const fakeWindow = { location: { reload() { reloaded += 1; } } };
  const { surfaceReloadConsoleNotice, ConsoleRequestError } = factory(center, fakeWindow);

  const err = new ConsoleRequestError({
    status: 403,
    error: 'forbidden_origin',
    action: 'reload_console',
    details: { expectedHost: '127.0.0.1' },
  });
  const handled = surfaceReloadConsoleNotice(err);

  assert.equal(handled, true);
  assert.equal(center.calls.length, 1);
  const call = center.calls[0];
  assert.equal(call.severity, 'error');
  assert.equal(call.surface, 'banner');
  assert.equal(call.dedupeKey, 'reload_console_403');
  assert.equal(call.title, 'Reload required');
  assert.ok(call.primaryAction);
  assert.equal(call.primaryAction.label, 'Reload console');
  assert.equal(typeof call.primaryAction.onSelect, 'function');

  call.primaryAction.onSelect();
  assert.equal(reloaded, 1);
});

test('surfaceReloadConsoleNotice returns false for non-reload_console errors', () => {
  const factory = loadSurfaceReloadConsoleNotice();
  const center = createFakeNotificationCenter();
  const fakeWindow = { location: { reload() {} } };
  const { surfaceReloadConsoleNotice, ConsoleRequestError } = factory(center, fakeWindow);

  const err = new ConsoleRequestError({ status: 500, error: 'server_error', message: 'boom' });
  const handled = surfaceReloadConsoleNotice(err);

  assert.equal(handled, false);
  assert.equal(center.calls.length, 0);
});

test('surfaceReloadConsoleNotice returns false for non-ConsoleRequestError throwables', () => {
  const factory = loadSurfaceReloadConsoleNotice();
  const center = createFakeNotificationCenter();
  const fakeWindow = { location: { reload() {} } };
  const { surfaceReloadConsoleNotice } = factory(center, fakeWindow);

  const handled = surfaceReloadConsoleNotice(new Error('plain'));

  assert.equal(handled, false);
  assert.equal(center.calls.length, 0);
});

test('api.js requestJson catch path calls surfaceReloadConsoleNotice', () => {
  // The integration is enforced via source pattern: requestJson must invoke
  // surfaceReloadConsoleNotice on error before propagating.
  assert.match(
    apiSource,
    /surfaceReloadConsoleNotice\s*\(/,
    'api.js must call surfaceReloadConsoleNotice in the requestJson catch path',
  );
});
