import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const renderingSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/rendering.js'), 'utf8');
const previewSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const paramsEnd = source.indexOf(')', start);
  const bodyStart = source.indexOf('{', paramsEnd);
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

test('saved overlays require focused saved item', () => {
  const render = body(renderingSource, 'function renderSelectionOverlay()');
  assert.match(render, /if \(!addItemsFlow && focusedSavedItemId\)/);
  assert.doesNotMatch(render, /visibleScreenNodeUids\(visibleScreen\)/);
  assert.doesNotMatch(render, /visibleUids\.has\(nodeUid\)/);
});

test('latestScreen uses focused saved screenshot before persisted fallback', () => {
  const latest = body(previewSource, 'function latestScreen()');
  assert.match(latest, /if \(focusedSavedItemId\)/);
  assert.match(latest, /focusedItem\.screenId/);
  assert.match(latest, /return state\.preview\?\.screen \|\| latestPersistedScreen\(\);/);
});
