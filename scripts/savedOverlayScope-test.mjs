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

test('saved list overlays use saved screen context after detail focus clears', () => {
  const render = body(renderingSource, 'function renderSelectionOverlay()');
  assert.match(render, /focusedSavedScreenId/);
  assert.match(render, /sameScreenItems = savedEvidenceItems\(\)\.filter\(item => item\.screenId === savedScreenId\)/);
  assert.doesNotMatch(render, /if \(!runtimeDraftFlow && toolModeUseCases\.getState\(\)\.focusedSavedItemId\)/);
});

test('latestScreen keeps saved screen context before live preview fallback', () => {
  const latest = body(previewSource, 'function latestScreen()');
  assert.match(latest, /if \(focusedSavedItemId\)/);
  assert.match(latest, /focusedItem\.screenId/);
  assert.match(latest, /focusedSavedScreenId/);
  assert.match(latest, /return savedScreen \|\| state\.preview\?\.screen \|\| latestPersistedScreen\(\);/);
});

test('saved rows and overlays prefer server sequenceNumber', () => {
  assert.match(renderingSource, /function annotationDisplayNumber\(item,\s*index\)/);
  assert.match(renderingSource, /item\?\.sequenceNumber\s*\?\?\s*\(index \+ 1\)/);
  assert.doesNotMatch(renderingSource, /'#' \+ \(index \+ 1\)/);
  assert.doesNotMatch(renderingSource, /String\(index \+ 1\), false,[\s\S]*focusedSavedItemId/);
});
