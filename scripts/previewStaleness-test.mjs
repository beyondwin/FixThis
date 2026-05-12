import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/previewStaleness.js'),
  'utf8',
);
const factory = new Function(`${source}; return { evaluateStale, MAX_PREVIEW_AGE_MS };`);
const { evaluateStale, MAX_PREVIEW_AGE_MS } = factory();

test('MAX_PREVIEW_AGE_MS is 30000', () => {
  assert.equal(MAX_PREVIEW_AGE_MS, 30000);
});

test('marks preview stale if frozenAt > 30s ago when connected', () => {
  const state = {
    preview: { frozenAtEpochMillis: 1000 },
    bridgeStatus: { connection: 'connected' },
  };
  assert.equal(evaluateStale(state, 32000), true);
});

test('marks preview stale when frozen age is older than MAX_PREVIEW_AGE_MS', () => {
  const now = 60000;
  const state = {
    preview: { frozenAtEpochMillis: now - MAX_PREVIEW_AGE_MS - 1 },
    bridgeStatus: { connection: 'connected' },
  };
  assert.equal(evaluateStale(state, now), true);
});

test('marks preview stale on disconnect even if frozen recently', () => {
  const state = {
    preview: { frozenAtEpochMillis: 31000 },
    bridgeStatus: { connection: 'disconnected' },
  };
  assert.equal(evaluateStale(state, 32000), true);
});

test('does not mark stale when connected and frozen recently', () => {
  const state = {
    preview: { frozenAtEpochMillis: 31000 },
    bridgeStatus: { connection: 'connected' },
  };
  assert.equal(evaluateStale(state, 32000), false);
});

test('does not mark stale when no preview frozen (preview null)', () => {
  const state = {
    preview: null,
    bridgeStatus: { connection: 'connected' },
  };
  assert.equal(evaluateStale(state, 32000), false);
});

test('does not mark stale when preview missing frozenAtEpochMillis', () => {
  const state = {
    preview: { activity: 'MainActivity' },
    bridgeStatus: { connection: 'disconnected' },
  };
  assert.equal(evaluateStale(state, 32000), false);
});

test('boundary: exactly 30000ms old is NOT stale, 30001ms IS stale', () => {
  const baseState = (frozenAt) => ({
    preview: { frozenAtEpochMillis: frozenAt },
    bridgeStatus: { connection: 'connected' },
  });
  // Exactly 30000ms old: age === MAX, strict > so not stale.
  assert.equal(evaluateStale(baseState(1000), 31000), false);
  // 30001ms old: strictly greater, stale.
  assert.equal(evaluateStale(baseState(1000), 31001), true);
});

test('missing bridgeStatus treated as not connected', () => {
  const state = {
    preview: { frozenAtEpochMillis: 31000 },
  };
  assert.equal(evaluateStale(state, 32000), true);
});
