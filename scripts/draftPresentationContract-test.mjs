import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const stateSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/state.js'), 'utf8');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
const renderingSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/rendering.js'), 'utf8');

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

test('state owns a draft workspace and command queue', () => {
  assert.match(stateSource, /let draftWorkspace = createEmptyDraftWorkspace\(\);/);
  assert.match(stateSource, /let draftCommandQueue = null;/);
  assert.match(stateSource, /function setDraftWorkspace\(nextWorkspace\)/);
});

test('annotation creation uses addDraftItem use case instead of direct push', () => {
  const createBody = body(annotationsSource, 'function createAnnotationFromSelection(selection)');
  assert.match(createBody, /addDraftItem\(/);
  assert.doesNotMatch(createBody, /pendingFeedbackItems\.push\(/);
});

test('pending delete uses deleteDraftItem use case instead of direct splice', () => {
  const deleteBody = body(annotationsSource, 'function deletePendingFeedbackItem(index)');
  assert.match(deleteBody, /deleteDraftItem\(/);
  assert.doesNotMatch(deleteBody, /pendingFeedbackItems\.splice\(/);
});

test('pending overlay renders from draft workspace selector', () => {
  assert.match(renderingSource, /draftWorkspaceItems\(draftWorkspace\)/);
});
