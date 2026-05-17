import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const main = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
const preview = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');

test('checkServerStaleness errors are logged, not swallowed', () => {
  assert.doesNotMatch(main, /checkServerStaleness\(\)\.catch\(\(\) => \{ \/\* silent \*\/ \}\)/);
  assert.match(main, /checkServerStaleness\(\)\.catch\([\s\S]{0,120}console\.warn/);
});

test('refreshConnection errors are logged, not swallowed', () => {
  assert.doesNotMatch(preview, /refreshConnection\(\{ preservePreviewStale: true \}\)\.catch\(\(\) => \{\}\)/);
  assert.match(preview, /refreshConnection\(\{ preservePreviewStale: true \}\)\.catch\([\s\S]{0,160}console\.warn/);
});
