import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const mainSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
const historySource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');

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

test('shared pending boundary resolver exists', () => {
  assert.match(mainSource, /async function resolvePendingBeforeBoundary\(action,\s*sessionId = null\)/);
  assert.match(mainSource, /Save draft/);
  assert.match(mainSource, /Keep editing/);
  assert.match(mainSource, /Discard/);
});

test('openSession and newSession use boundary resolver instead of immediate flush', () => {
  const openBody = body(historySource, 'async function openSession(sessionId)');
  const newBody = body(historySource, 'async function newSession()');
  assert.match(openBody, /await resolvePendingBeforeBoundary\('open-session',\s*sessionId\)/);
  assert.match(newBody, /await resolvePendingBeforeBoundary\('new-session'\)/);
  assert.doesNotMatch(openBody, /flushPendingAnnotationsBeforeSessionChange\(\)/);
  assert.doesNotMatch(newBody, /flushPendingAnnotationsBeforeSessionChange\(\)/);
});

test('closeSession uses boundary resolver before reset', () => {
  const closeBody = body(historySource, 'async function closeSession()');
  assert.match(closeBody, /await resolvePendingBeforeBoundary\('close-session'/);
  assert.doesNotMatch(closeBody, /resetAnnotationComposerState\(\);[\s\S]*requestJson\('\/api\/session\/close'/);
});

test('deleteHistorySession uses boundary resolver before reset', () => {
  const deleteBody = body(historySource, 'async function deleteHistorySession(sessionId)');
  assert.match(deleteBody, /await resolvePendingBeforeBoundary\('delete-session',\s*sessionId\)/);
  assert.doesNotMatch(deleteBody, /if \(isDisplayedSession\(\)\) \{\s*resetAnnotationComposerState\(\);/);
});
