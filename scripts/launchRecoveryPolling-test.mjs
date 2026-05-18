import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const connectionSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/connection.js'),
  'utf8',
);

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  let i = source.indexOf('(', start);
  let parenDepth = 0;
  for (; i < source.length; i += 1) {
    if (source[i] === '(') parenDepth += 1;
    if (source[i] === ')') {
      parenDepth -= 1;
      if (parenDepth === 0) {
        i += 1;
        break;
      }
    }
  }
  const bodyStart = source.indexOf('{', i);
  let depth = 0;
  for (let j = bodyStart; j < source.length; j += 1) {
    if (source[j] === '{') depth += 1;
    if (source[j] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, j);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('launchApp uses bounded recovery polling instead of one delayed refresh', () => {
  const launchApp = body(connectionSource, 'async function launchApp()');

  assert.match(launchApp, /scheduleLaunchConnectionRefresh\(\);/);
  assert.doesNotMatch(
    launchApp,
    /setTimeout\(\(\) => refreshConnection\(\)\.catch\(showError\), 800\)/,
    'a single 800ms refresh can leave the console stuck in STARTING when the app opens slowly',
  );
});

test('launch recovery polling continues only for launch-transient states', () => {
  const { LaunchConnectionRefreshDelaysMs, shouldContinueLaunchConnectionRefresh } = loadConsoleSymbols({
    modules: ['connection.js'],
    symbols: ['LaunchConnectionRefreshDelaysMs', 'shouldContinueLaunchConnectionRefresh'],
  });

  assert.ok(Array.isArray(LaunchConnectionRefreshDelaysMs));
  assert.ok(LaunchConnectionRefreshDelaysMs.length > 1, 'launch recovery needs more than one retry');
  assert.equal(shouldContinueLaunchConnectionRefresh({ state: 'STARTING' }), true);
  assert.equal(shouldContinueLaunchConnectionRefresh({ state: 'OPEN_APP' }), true);
  assert.equal(shouldContinueLaunchConnectionRefresh({ state: 'RECONNECT' }), true);
  assert.equal(shouldContinueLaunchConnectionRefresh({ state: 'READY' }), false);
  assert.equal(shouldContinueLaunchConnectionRefresh({ state: 'CHECK_PHONE' }), false);
  assert.equal(shouldContinueLaunchConnectionRefresh(null), false);
});
