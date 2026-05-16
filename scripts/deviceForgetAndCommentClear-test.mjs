import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/index.html'), 'utf8');
const main = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
const devices = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/devices.js'), 'utf8');
const variants = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/boundaryDialogVariants.js'), 'utf8');

test('device affordance is labeled Forget device, not a bare x clear button', () => {
  assert.match(html, /id="forgetDeviceButton"/);
  assert.match(html, /Forget device/);
  assert.match(html, /class="sr-only"/);
  assert.doesNotMatch(html, /id="disconnectDeviceButton"/);
  assert.doesNotMatch(html, /device-clear-button/);
  assert.doesNotMatch(html, /&times;/);
});

test('comment clear affordance is absent', () => {
  assert.doesNotMatch(html, /Clear comment|id="clearComment"|data-action="clearComment"/);
});

test('forget device click uses the existing disconnect routine behind explicit copy', () => {
  assert.match(main, /forgetDeviceButton/);
  assert.match(main, /forgetDevice\(\)\.catch\(showError\)/);
  assert.match(devices, /async function forgetDevice\(\)/);
  assert.match(devices, /promptBoundaryDialogChoice\('forgetDevice'/);
  assert.match(devices, /disconnectDevice\(\)/);
  assert.match(variants, /forgetDevice:/);
});
