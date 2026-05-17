import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const devices = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/devices.js'), 'utf8');

test('selectDevice surfaces a toast when a disabled option is chosen', () => {
  assert.match(devices, /option\.disabled[\s\S]{0,200}showStatus\(/);
});
