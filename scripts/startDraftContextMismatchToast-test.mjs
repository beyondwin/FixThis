import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const annotations = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');

test('startDraftAnnotationFlow toasts when preview context drifts mid-fetch', () => {
  const block = annotations.match(/preview = await previewUseCases\.request\(\);[\s\S]{0,400}/);
  assert.ok(block, 'expected to find the previewUseCases.request() block');
  assert.match(
    block[0],
    /if \(!previewContextStillCurrent\(previewContext\)\) \{[\s\S]{0,200}showStatus\(/,
    'expected showStatus inside the context-mismatch guard after previewUseCases.request()'
  );
});
