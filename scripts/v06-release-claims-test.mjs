import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function read(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

test('release readiness defines v0.6 claim manifest and evidence commands', () => {
  const text = read('docs/contributing/release-readiness.md');
  assert.match(text, /## v0\.6 Release Claim Manifest/);
  assert.match(text, /Handoff Intelligence/);
  assert.match(text, /Studio Reliability/);
  assert.match(text, /Release Grade/);
  assert.match(text, /npm run handoff:eval:test/);
  assert.match(text, /npm run console:reliability:test/);
  assert.match(text, /npm run release:v06:evidence:test/);
  assert.match(text, /npm run checks:observation -- --json/);
});

test('v0.6 manifest requires narrowing claims when evidence is missing', () => {
  const text = read('docs/contributing/release-readiness.md');
  assert.match(text, /If evidence is missing, narrow or remove the corresponding v0\.6 release note claim\./);
});
