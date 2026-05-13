import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
const previewSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');
const promptSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/prompt.js'), 'utf8');
const persistenceSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pendingPersistence.js'), 'utf8');
const stateSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/state.js'), 'utf8');

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

test('annotate flow captures immutable session preview context', () => {
  const start = body(annotationsSource, 'async function startAddItemsFlow()');
  assert.match(start, /context:\s*\{/);
  assert.match(start, /sessionId:\s*state\.session\?\.sessionId\s*\|\|\s*null/);
  assert.match(start, /previewId:\s*state\.preview\.previewId/);
  assert.match(start, /screenId:\s*state\.preview\.screen\?\.screenId\s*\|\|\s*null/);
  assert.match(start, /screenFingerprint:\s*state\.preview\.screen\?\.fingerprint\s*\?\?\s*null/);
});

test('batch save sends captured sessionId and fingerprint from addItemsFlow context', () => {
  const persist = body(annotationsSource, 'async function persistPendingFeedbackItems(options = {})');
  assert.match(persist, /sessionId:\s*(addItemsFlow\.context\.sessionId|expectedSessionId)/);
  assert.match(persist, /previewId:\s*addItemsFlow\.context\.previewId/);
  assert.match(persist, /frozenFingerprint:\s*addItemsFlow\.context\.screenFingerprint/);
});

test('preview and screen URLs include explicit sessionId query', () => {
  assert.match(previewSource, /function previewScreenshotUrl\(previewId,\s*sessionId/);
  assert.match(previewSource, /sessionId=.*encodeURIComponent\(sessionId\)/);
  assert.match(previewSource, /function screenScreenshotUrl\(screenId,\s*sessionId/);
});

test('pending persistence envelope stores captured context', () => {
  assert.match(persistenceSource, /context:\s*value\?\.context\s*\?\?\s*null/);
});

test('agent handoff includes sessionId in request body', () => {
  assert.match(promptSource, /sessionId:\s*state\.session\?\.sessionId\s*\|\|\s*null/);
});

test('session mutation generation fences stale async responses', () => {
  assert.match(stateSource, /let sessionMutationGeneration = 0;/);
  assert.match(annotationsSource, /const requestGeneration = sessionMutationGeneration;/);
  assert.match(annotationsSource, /if \(requestGeneration !== sessionMutationGeneration\)/);
});
