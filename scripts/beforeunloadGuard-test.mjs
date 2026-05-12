import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/beforeunloadGuard.js'), 'utf8');
const factory = new Function(`${source}; return { shouldGuardUnload };`);
const { shouldGuardUnload } = factory();

test('shouldGuardUnload returns true when pending items > 0', () => {
  assert.equal(shouldGuardUnload(1), true);
  assert.equal(shouldGuardUnload(5), true);
});

test('shouldGuardUnload returns false when pending items == 0', () => {
  assert.equal(shouldGuardUnload(0), false);
});

test('shouldGuardUnload coerces non-number safely (no false positives)', () => {
  assert.equal(shouldGuardUnload(undefined), false);
  assert.equal(shouldGuardUnload(null), false);
  assert.equal(shouldGuardUnload('0'), false);
  assert.equal(shouldGuardUnload(''), false);
});

test('shouldGuardUnload coerces stringified number truthy', () => {
  assert.equal(shouldGuardUnload('3'), true);
});
