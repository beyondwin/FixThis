import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const reducer = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/domain/consoleReducer.js'), 'utf8');
const draftUseCases = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftUseCases.js'), 'utf8');
const polling = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pollingBrowserAdapter.js'), 'utf8');
const annotations = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  // Walk past the parameter list (matching parens) to skip any default-value `{}` braces.
  let i = source.indexOf('(', start);
  assert.notEqual(i, -1, `${signature} parameter list not found`);
  let parenDepth = 0;
  for (; i < source.length; i += 1) {
    if (source[i] === '(') parenDepth += 1;
    else if (source[i] === ')') {
      parenDepth -= 1;
      if (parenDepth === 0) { i += 1; break; }
    }
  }
  const bodyStart = source.indexOf('{', i);
  let depth = 0;
  for (let j = bodyStart; j < source.length; j += 1) {
    if (source[j] === '{') depth += 1;
    if (source[j] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, j);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('preview capture success is fenced by generation and active session', () => {
  const capture = body(reducer, 'function reducePreviewCaptureSucceeded(state, event)');
  assert.match(capture, /event\.generation !== state\.effectsGeneration/);
  assert.match(capture, /event\.sessionId !== state\.activeSessionId/);
  assert.match(capture, /isDraftWorkspace\(state\.workspace\)/);
});

test('draft save success is fenced by generation and original workspace id', () => {
  const save = body(reducer, 'function reduceDraftSaveSucceeded(state, event)');
  assert.match(save, /event\.generation !== state\.effectsGeneration/);
  assert.match(save, /!isDraftWorkspace\(state\.workspace\)/);
  assert.match(save, /event\.workspaceId !== state\.workspace\.context\.workspaceId/);
});

test('Save to MCP completion clears browser recovery for the saved workspace', () => {
  const persist = body(draftUseCases, 'async function persistDraftWorkspace(workspace, ports, options = {})');
  assert.match(persist, /ports\.storage\?\.deleteWorkspace\?\.\(started\.context\.sessionId,\s*started\.workspaceId\)/);
  assert.match(persist, /SAVE_SUCCEEDED/);
});

test('session polling clears displayed session only after checking the current displayed id', () => {
  assert.match(polling, /const activeDisplayedSessionId = displayedSessionId\(\);/);
  assert.match(polling, /clearDisplayedSessionState\(\);/);
  assert.match(polling, /fresh && fresh\.sessionId === activeDisplayedSessionId && !isClosedSession\(fresh\)/);
});

test('batch save goes through command queue and expected revision', () => {
  const persist = body(annotations, 'async function persistPendingFeedbackItems(options = {})');
  assert.match(persist, /ensureDraftCommandQueue\(\)\.enqueue/);
  assert.match(persist, /expectedRevision:\s*draftWorkspace\.revision/);
});
