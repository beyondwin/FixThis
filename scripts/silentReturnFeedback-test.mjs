import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const connection = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/connection.js'), 'utf8');
const annotations = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('captureScreen tells the user when there is no active session', () => {
  assert.match(connection, /if \(!state\.session\)[\s\S]{0,200}showStatus\(/);
});

test('startDraftAnnotationFlow tells the user when no preview is available', () => {
  assert.match(annotations, /Open the app first to capture a screenshot/);
});

test('startDraftAnnotationFlow surfaces capture-unavailable readiness instead of freezing it', () => {
  const start = body(annotations, 'async function startDraftAnnotationFlow()');
  assert.match(start, /applyPreviewReadinessToConnectionCard\(preview\);/);
  assert.match(start, /if \(preview\?\.previewAvailable === false\) \{[\s\S]*?renderPreviewOnly\(\);[\s\S]*?return;[\s\S]*?\}/);
  assert.ok(
    start.indexOf('if (preview?.previewAvailable === false)') < start.indexOf('setConsolePreview(preview);'),
    'preview-unavailable must be gated before state.preview is replaced',
  );
});
