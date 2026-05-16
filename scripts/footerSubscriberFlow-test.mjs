import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function source(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

function functionBody(sourceText, signature) {
  const start = sourceText.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const bodyStart = sourceText.indexOf('{', sourceText.indexOf(')', start));
  let depth = 0;
  for (let i = bodyStart; i < sourceText.length; i += 1) {
    if (sourceText[i] === '{') depth += 1;
    if (sourceText[i] === '}') {
      depth -= 1;
      if (depth === 0) return sourceText.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('composer render derives editor state and renders inspector footer', () => {
  const annotations = source('fixthis-mcp/src/main/console/annotations.js');
  const updateBody = functionBody(annotations, 'function updateComposerState()');

  assert.match(annotations, /@requires .*editorState\.js.*inspectorFooter\.js/);
  assert.match(updateBody, /deriveEditorState\(/);
  assert.match(updateBody, /renderInspectorFooter\(/);
});

test('footer actions are delegated through data-action buttons', () => {
  const main = source('fixthis-mcp/src/main/console/main.js');

  assert.match(main, /inspectorFooter\?\.addEventListener\('click'/);
  assert.match(main, /dataset\.action/);
  assert.doesNotMatch(main, /clearSelectionButton\?\.addEventListener\('click'/);
  assert.doesNotMatch(main, /cancelAddFlowButton\?\.addEventListener\('click'/);
  assert.doesNotMatch(main, /clearDraftButton\?\.addEventListener\('click'/);
});
