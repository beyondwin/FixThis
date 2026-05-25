import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));

function loadDevReload({ buildHash, devReloadEnabled }) {
  const src = readFileSync(resolve(repoRoot, 'fixthis-mcp/src/main/console/devReload.js'), 'utf8');
  const reloadCalls = [];
  const windowStub = { FixThisConsoleConfig: { devReloadEnabled, buildHash } };
  const locationStub = { reload: () => reloadCalls.push(1) };
  const consoleStub = { warn: () => {}, log: () => {}, error: () => {} };
  // Strip the // @requires header.
  const body = src.replace(/^\s*\/\/\s*@requires[^\n]*\n/, '');
  const fn = new Function(
    'window',
    'location',
    'console',
    `${body}; return { handleConsoleAssetsChanged };`,
  );
  return {
    api: fn(windowStub, locationStub, consoleStub),
    reloadCalls,
  };
}

test('does nothing when devReloadEnabled is false', () => {
  const { api, reloadCalls } = loadDevReload({ buildHash: 'a', devReloadEnabled: false });
  api.handleConsoleAssetsChanged({ buildHash: 'b' });
  assert.equal(reloadCalls.length, 0);
});

test('reloads when hash differs and devReloadEnabled is true', () => {
  const { api, reloadCalls } = loadDevReload({ buildHash: 'a', devReloadEnabled: true });
  api.handleConsoleAssetsChanged({ buildHash: 'b' });
  assert.equal(reloadCalls.length, 1);
});

test('does not reload when hash equals current', () => {
  const { api, reloadCalls } = loadDevReload({ buildHash: 'same', devReloadEnabled: true });
  api.handleConsoleAssetsChanged({ buildHash: 'same' });
  assert.equal(reloadCalls.length, 0);
});

test('ignores payload without buildHash', () => {
  const { api, reloadCalls } = loadDevReload({ buildHash: 'a', devReloadEnabled: true });
  api.handleConsoleAssetsChanged({});
  api.handleConsoleAssetsChanged(null);
  api.handleConsoleAssetsChanged(undefined);
  assert.equal(reloadCalls.length, 0);
});
