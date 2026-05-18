import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sseSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/sse.js'),
  'utf8',
);
const previewPollSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/previewPoll.js'),
  'utf8',
);
const eventsSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/events.js'),
  'utf8',
);
const previewSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/preview.js'),
  'utf8',
);

function extractFunction(source, signaturePrefix) {
  const start = source.indexOf(signaturePrefix);
  assert.notEqual(start, -1, `${signaturePrefix} not found in source`);
  let parenDepth = 0;
  let bodyStart = -1;
  for (let i = start; i < source.length; i += 1) {
    if (source[i] === '(') parenDepth += 1;
    if (source[i] === ')') parenDepth -= 1;
    if (source[i] === '{' && parenDepth === 0) {
      bodyStart = i;
      break;
    }
  }
  assert.notEqual(bodyStart, -1, `${signaturePrefix} body start not found`);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  assert.fail(`${signaturePrefix} body did not close`);
}

function loadGate(source, signaturePrefix, fnName) {
  const fnSource = extractFunction(source, signaturePrefix);
  const factory = new Function('console', `
    ${fnSource}
    return ${fnName};
  `);
  return factory;
}

function fakeConsole() {
  const warnings = [];
  return {
    warn: (...args) => warnings.push(args),
    warnings,
  };
}

test('dropStaleSse drops when message sessionId mismatches active session', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(sseSource, 'function dropStaleSse', 'dropStaleSse');
  const dropStaleSse = factory(fakeWarn);

  const dropped = dropStaleSse({ sessionId: 'old-1' }, 'new-2');

  assert.equal(dropped, true);
  assert.equal(fakeWarn.warnings.length, 1, 'mismatch must emit a single warn for diagnostics');
});

test('dropStaleSse does NOT drop when sessionId matches', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(sseSource, 'function dropStaleSse', 'dropStaleSse');
  const dropStaleSse = factory(fakeWarn);

  const dropped = dropStaleSse({ sessionId: 'same' }, 'same');

  assert.equal(dropped, false);
  assert.equal(fakeWarn.warnings.length, 0);
});

test('dropStaleSse does NOT drop when message has no sessionId (broadcast)', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(sseSource, 'function dropStaleSse', 'dropStaleSse');
  const dropStaleSse = factory(fakeWarn);

  const dropped = dropStaleSse({}, 'active-1');

  assert.equal(dropped, false);
  assert.equal(fakeWarn.warnings.length, 0);
});

test('dropStalePreviewPoll drops when response sessionId mismatches active session', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(previewPollSource, 'function dropStalePreviewPoll', 'dropStalePreviewPoll');
  const dropStalePreviewPoll = factory(fakeWarn);

  const dropped = dropStalePreviewPoll({ sessionId: 'old-1', previewAvailable: true }, 'new-2');

  assert.equal(dropped, true);
  assert.equal(fakeWarn.warnings.length, 1);
});

test('dropStalePreviewPoll does NOT drop when sessionId matches', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(previewPollSource, 'function dropStalePreviewPoll', 'dropStalePreviewPoll');
  const dropStalePreviewPoll = factory(fakeWarn);

  const dropped = dropStalePreviewPoll({ sessionId: 'same', previewAvailable: true }, 'same');

  assert.equal(dropped, false);
  assert.equal(fakeWarn.warnings.length, 0);
});

test('dropStalePreviewPoll does NOT drop when response has no sessionId', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(previewPollSource, 'function dropStalePreviewPoll', 'dropStalePreviewPoll');
  const dropStalePreviewPoll = factory(fakeWarn);

  const dropped = dropStalePreviewPoll({ previewAvailable: true }, 'active-1');

  assert.equal(dropped, false);
  assert.equal(fakeWarn.warnings.length, 0);
});

test('SSE and preview polling share applyLivePreview stale-session gate', () => {
  const previewApply = extractFunction(previewSource, 'function applyLivePreview');
  assert.match(previewApply, /options\.source === 'sse'/);
  assert.match(previewApply, /dropStaleSse\(\{ sessionId: ownerSessionId \}, activeSessionId\)/);
  assert.match(previewApply, /dropStalePreviewPoll\(\{ sessionId: ownerSessionId \}, activeSessionId\)/);
  assert.match(previewApply, /previewUseCases\.applyReady\(/);
  assert.match(previewApply, /setConsolePreview\(\{/);
});

test('applyLivePreview surfaces preview-unavailable readiness without replacing preview state', () => {
  const previewApply = extractFunction(previewSource, 'function applyLivePreview');
  assert.match(previewApply, /applyPreviewReadinessToConnectionCard\(preview\);/);
  assert.match(previewApply, /if \(preview\?\.previewAvailable === false\) \{[\s\S]*?renderPreviewOnly\(\);[\s\S]*?return true;[\s\S]*?\}/);
  assert.ok(
    previewApply.indexOf('if (preview?.previewAvailable === false)') < previewApply.indexOf('setConsolePreview({'),
    'preview-unavailable must be gated before state.preview is replaced',
  );
  assert.ok(
    previewApply.indexOf('if (preview?.previewAvailable === false)') < previewApply.indexOf('previewUseCases.applyReady('),
    'preview-unavailable must also be gated before the preview FSM accepts the payload as current',
  );
});

test('console reducer drops stale save and capture completions by generation and session', () => {
  const reducerSource = readFileSync(
    resolve(root, 'fixthis-mcp/src/main/console/domain/consoleReducer.js'),
    'utf8',
  );
  const capture = extractFunction(reducerSource, 'function reducePreviewCaptureSucceeded');
  const save = extractFunction(reducerSource, 'function reduceDraftSaveSucceeded');

  assert.match(capture, /event\.generation !== state\.effectsGeneration/);
  assert.match(capture, /event\.sessionId !== state\.activeSessionId/);
  assert.match(save, /event\.generation !== state\.effectsGeneration/);
  assert.match(save, /event\.workspaceId !== state\.workspace\.context\.workspaceId/);
});

test('session polling cannot keep a deleted displayed session alive', () => {
  const pollingSource = readFileSync(
    resolve(root, 'fixthis-mcp/src/main/console/pollingBrowserAdapter.js'),
    'utf8',
  );
  assert.match(pollingSource, /const activeDisplayedSessionId = displayedSessionId\(\);/);
  assert.match(pollingSource, /const fresh = await sessResp\.json\(\);/);
  assert.match(pollingSource, /fresh && fresh\.sessionId === activeDisplayedSessionId && !isClosedSession\(fresh\)/);
  assert.match(pollingSource, /clearDisplayedSessionState\(\);/);
});
