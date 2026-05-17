import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const view = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/presentation/previewRegionView.js'), 'utf8');

test('renderBoundaryFromModel clears all button handlers before rebinding', () => {
  assert.match(view, /button\.onclick = null;[\s\S]{0,200}if \(action\)/);
});

test('renderBoundaryFromModel hides buttons whose slot has no action', () => {
  assert.match(view, /button\.hidden = !action/);
});
