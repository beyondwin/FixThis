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
const pollingBrowserAdapterSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pollingBrowserAdapter.js'), 'utf8');
const renderingSource = [
  'fixthis-mcp/src/main/console/presentation/previewRegionView.js',
  'fixthis-mcp/src/main/console/presentation/canonicalRenderingView.js',
].map(file => readFileSync(resolve(root, file), 'utf8')).join('\n');

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
  const start = body(annotationsSource, 'async function startDraftAnnotationFlow()');
  assert.match(start, /startDraftFreeze\(/);
  assert.match(start, /sessionId:\s*state\.session\?\.sessionId\s*\|\|\s*null/);
  assert.match(start, /selectedDeviceSerial:\s*state\.selectedDeviceSerial\s*\|\|\s*null/);
  assert.match(start, /capture:\s*async \(\) => state\.preview/);
});

test('batch save uses draft workspace use case with explicit context', () => {
  const persist = body(annotationsSource, 'async function persistPendingFeedbackItems(options = {})');
  assert.match(persist, /ensureDraftCommandQueue\(\)\.enqueue/);
  assert.match(persist, /persistDraftWorkspace\(/);
  assert.match(persist, /draftWorkspace\.workspaceId/);
  assert.match(persist, /draftWorkspace\.revision/);
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
  assert.match(promptSource, /const sessionId = draftWorkspace\?\.context\?\.sessionId \|\| state\.session\?\.sessionId;/);
  assert.match(promptSource, /sessionId,\s*\n\s*itemIds/);
});

test('session summary updates rebuild the history list instead of only toggling active rows', () => {
  assert.match(pollingBrowserAdapterSource, /const data = await listResp\.json\(\);[\s\S]*?renderSessionsListFromPayload\(data\.sessions \|\| \[\]\);/);
  assert.doesNotMatch(pollingBrowserAdapterSource, /state\.sessionSummaries = data\.sessions \|\| \[\];\s*renderSessionsList\(\);/);
});

test('full render rebuilds history summaries from current state', () => {
  const renderSessionRegions = body(renderingSource, 'function renderSessionRegions()');
  assert.match(renderSessionRegions, /renderCurrentSessionList\(\);/);
  assert.doesNotMatch(renderSessionRegions, /renderSessionsList\(\);/);

  const fullRender = body(renderingSource, 'function render()');
  assert.match(fullRender, /renderSessionRegions\(\);[\s\S]*?renderPreviewRegion\(\);[\s\S]*?renderInspectorRegion\(\);/);
});

test('copy prompt refreshes session summaries after marking items handed off', () => {
  const copy = body(promptSource, 'async function copyPrompt()');
  assert.match(copy, /const updated = await markItemsHandedOff\(sessionId,\s*itemIds\);[\s\S]*?setConsoleSession\(updated\);[\s\S]*?await refreshSessions\(\);[\s\S]*?renderInspectorRegion\(\);/);
});

test('draft command queue fences stale pending save responses', () => {
  // sessionMutationGeneration migrated into pollingFsm.js (mutationGeneration
  // field on the polling FSM, bumped by bumpSessionMutationGeneration() via
  // MUTATION_GENERATION_BUMP). The legacy entry point stays as a top-level
  // function in state.js for callers.
  assert.match(stateSource, /function bumpSessionMutationGeneration\(\)/);
  assert.match(stateSource, /MUTATION_GENERATION_BUMP/);
  assert.match(stateSource, /let draftCommandQueue = null;/);
  assert.match(annotationsSource, /expectedRevision:\s*draftWorkspace\.revision/);
});
