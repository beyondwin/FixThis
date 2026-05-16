import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const files = [
  'fixthis-mcp/src/main/console/main.js',
  'fixthis-mcp/src/main/console/devices.js',
  'fixthis-mcp/src/main/console/history.js',
  'fixthis-mcp/src/main/console/annotations.js',
];

test('production first-run console paths do not call native confirm', () => {
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    assert.doesNotMatch(source, /window\.confirm|\.confirm\(/, `${file} still uses native confirm`);
  }
});
