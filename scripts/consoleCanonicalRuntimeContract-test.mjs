import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function source(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

test('console:test:all includes the canonical group', () => {
  const pkg = JSON.parse(source('package.json'));
  assert.match(pkg.scripts['console:test:all'], /canonical/);
});

test('canonical group includes runtime contract test', () => {
  const groups = JSON.parse(source('scripts/console-tests.json'));
  assert.ok(groups.canonical.includes('scripts/consoleCanonicalRuntimeContract-test.mjs'));
});
