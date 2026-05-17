import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');

test('enterAnnotateMode toasts when pending recovery blocks the session change', () => {
  const block = history.match(/async function enterAnnotateMode\(\)[\s\S]{0,400}/);
  assert.ok(block, 'expected to find enterAnnotateMode function');
  assert.match(
    block[0],
    /if \(!requirePendingRecoveryChoiceBeforeSessionChange\(\)\) \{[\s\S]{0,200}showStatus\(/,
    'expected showStatus inside the pending-recovery guard'
  );
});
