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
const main = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
const previewSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');
const prompt = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/prompt.js'), 'utf8');
const stateSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/state.js'), 'utf8');

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

test('SSE connection state is explicit and controls preview fallback polling', () => {
  const sse = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/sse.js'), 'utf8');
  const preview = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');
  const events = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/events.js'), 'utf8');

  assert.match(sse, /function setConsoleEventsConnected\(connected\)/);
  assert.match(sse, /function shouldUsePreviewFallbackPolling\(\)/);
  assert.match(preview, /if \(!shouldUsePreviewFallbackPolling\(\)\) return;/);
  assert.match(events, /source\.onopen = \(\) => \{/);
  assert.match(events, /setConsoleEventsConnected\(true\)/);
  assert.match(events, /setConsoleEventsConnected\(false\)/);
  assert.match(events, /startLivePreviewPolling\(\)/);
});

test('automatic preview refreshes are fallback-only while SSE is connected', () => {
  assert.match(previewSource, /function shouldAutoFetchPreviewFallback\(\)/);
  assert.match(previewSource, /return shouldAutoFetchPreview\(\) && shouldUsePreviewFallbackPolling\(\);/);
  assert.match(main, /if \(!document\.hidden && shouldAutoFetchPreviewFallback\(\)\) refreshPreview\(\)\.catch\(showError\);/);
  assert.match(main, /if \(shouldAutoFetchPreviewFallback\(\)\) return refreshPreview\(\);/);
  assert.doesNotMatch(main, /if \(!document\.hidden && shouldAutoFetchPreview\(\)\) refreshPreview\(\)\.catch\(showError\);/);
  assert.doesNotMatch(main, /if \(shouldAutoFetchPreview\(\)\) return refreshPreview\(\);/);
});

test('item and handoff mutation paths use SSE-aware session refresh fallback', () => {
  const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');

  assert.match(history, /async function refreshSessionsWhenEventsDisconnected\(\)/);
  assert.match(history, /if \(isConsoleEventsConnected\(\) \|\| wasConsoleEventsRecentlyConnected\(\)\) return state\.sessionSummaries \|\| \[\];/);

  const savedUpdate = body(annotations, 'function applySavedSessionUpdate(updatedSession, sessionId)');
  assert.doesNotMatch(savedUpdate, /refreshSessions\(\)\.catch\(showError\)/);
  assert.match(savedUpdate, /refreshSessionsWhenEventsDisconnected\(\)\.catch\(showError\)/);

  const deleteSaved = body(annotations, 'async function deleteSavedEvidenceItem(itemId');
  assert.doesNotMatch(deleteSaved, /refreshSessions\(\)\.catch\(showError\)/);
  assert.match(deleteSaved, /refreshSessionsWhenEventsDisconnected\(\)\.catch\(showError\)/);

  const persistPending = body(annotations, 'async function persistPendingFeedbackItems(options = {})');
  assert.doesNotMatch(persistPending, /await refreshSessions\(\);/);
  assert.match(persistPending, /await refreshSessionsWhenEventsDisconnected\(\);/);

  const copyPromptBody = body(prompt, 'async function copyPrompt()');
  assert.doesNotMatch(copyPromptBody, /await refreshSessions\(\);/);
  assert.match(copyPromptBody, /await refreshSessionsWhenEventsDisconnected\(\);/);

  const sendAgentBody = body(prompt, 'async function sendAgentPrompt()');
  assert.doesNotMatch(sendAgentBody, /await refreshSessions\(\);/);
  assert.match(sendAgentBody, /await refreshSessionsWhenEventsDisconnected\(\);/);

  assert.match(stateSource, /refreshSessionsWhenEventsDisconnected\(\)\.catch\(showError\)/);
});

test('refreshSessionsWhenEventsDisconnected keeps pull refresh behind the SSE gate', () => {
  const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
  const helper = body(history, 'async function refreshSessionsWhenEventsDisconnected()');

  assert.match(helper, /isConsoleEventsConnected\(\) \|\| wasConsoleEventsRecentlyConnected\(\)/);
  assert.match(helper, /return state\.sessionSummaries \|\| \[\];/);
  assert.match(helper, /return refreshSessions\(\);/);
});

test('sessions-updated events apply pushed summary without pull refresh while SSE is healthy', () => {
  const events = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/events.js'), 'utf8');
  const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');

  assert.match(history, /function applySessionSummaryFromPayload\(summary\)/);
  assert.match(events, /if \(data\.summary\) \{/);
  assert.match(events, /applySessionSummaryFromPayload\(data\.summary\);/);
  assert.match(events, /refreshSessionsWhenEventsDisconnected\(\)\.catch\(showError\)/);
  assert.doesNotMatch(events, /on\('sessions-updated'[\s\S]*refreshSessions\(\)\.catch\(showError\)/);
});

test('session polling is fallback-only while SSE is healthy', () => {
  const sse = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/sse.js'), 'utf8');
  const sessionsPolling = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/sessions-polling.js'), 'utf8');
  const events = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/events.js'), 'utf8');

  assert.match(sse, /function shouldUseSessionFallbackPolling\(\)/);
  assert.match(sessionsPolling, /shouldUseSessionFallbackPolling\(\)/);
  assert.match(events, /source\.onopen = \(\) => \{[\s\S]*stopSessionsPolling\(\);/);
  assert.match(events, /source\.onerror = \(\) => \{[\s\S]*startSessionsPolling\(\);/);
});
