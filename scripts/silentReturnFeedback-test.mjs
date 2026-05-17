import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const connection = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/connection.js'), 'utf8');
const annotations = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');

test('captureScreen tells the user when there is no active session', () => {
  assert.match(connection, /if \(!state\.session\)[\s\S]{0,200}showStatus\(/);
});

test('startDraftAnnotationFlow tells the user when no preview is available', () => {
  assert.match(annotations, /Open the app first to capture a screenshot/);
});
