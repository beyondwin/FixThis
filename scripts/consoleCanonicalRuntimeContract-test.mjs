import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function source(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

function extractFunctionBody(sourceText, signature) {
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

test('console:test:all includes the canonical group', () => {
  const pkg = JSON.parse(source('package.json'));
  assert.match(pkg.scripts['console:test:all'], /canonical/);
});

test('canonical group includes runtime contract test', () => {
  const groups = JSON.parse(source('scripts/console-tests.json'));
  assert.ok(groups.canonical.includes('scripts/consoleCanonicalRuntimeContract-test.mjs'));
});

test('main bootstrap creates canonical store, ports, renderer, and effect runner', () => {
  const main = source('fixthis-mcp/src/main/console/main.js');
  assert.match(main, /createBrowserConsolePorts\(/);
  assert.match(main, /createBrowserRenderer\(/);
  assert.match(main, /createConsoleStore\(/);
  assert.match(main, /runConsoleEffect\(/);
  assert.match(main, /store\.dispatch\(/);
});

test('console store checks invariants before rendering', () => {
  const store = source('fixthis-mcp/src/main/console/application/consoleStore.js');
  assert.match(store, /assertConsoleInvariants\(current\)/);
  assert.match(store, /assertConsoleInvariants\(result\.state\)/);
});

test('runtime modules do not mutate legacy draft globals', () => {
  const files = [
    'fixthis-mcp/src/main/console/main.js',
    'fixthis-mcp/src/main/console/annotations.js',
    'fixthis-mcp/src/main/console/preview.js',
    'fixthis-mcp/src/main/console/history.js',
    'fixthis-mcp/src/main/console/rendering.js',
    'fixthis-mcp/src/main/console/prompt.js',
    'fixthis-mcp/src/main/console/shortcuts.js',
  ];
  for (const file of files) {
    const text = source(file);
    assert.doesNotMatch(text, /\bactiveDraftFlow\b/, file);
    assert.doesNotMatch(text, /\bdraftFeedbackItems\b/, file);
    assert.doesNotMatch(text, /\bfocusedPendingItemIndex\b/, file);
    assert.doesNotMatch(text, /\bcurrentSelection\b/, file);
    assert.doesNotMatch(text, /resetCanonicalAnnotationComposerState\(/, file);
    assert.doesNotMatch(text, /invalidateCanonicalPreviewContext\(/, file);
  }
});

test('rendering bridge functions accept models and do not read legacy globals', () => {
  const rendering = source('fixthis-mcp/src/main/console/presentation/canonicalRenderingView.js');
  for (const fn of [
    'renderCanonicalHistoryModel',
    'renderCanonicalCanvasModel',
    'renderCanonicalInspectorModel',
    'renderCanonicalPromptModel',
    'renderCanonicalBoundaryModel',
  ]) {
    assert.match(rendering, new RegExp('function ' + fn + '\\(model'));
  }
});

test('state module no longer declares legacy draft runtime state', () => {
  const state = source('fixthis-mcp/src/main/console/state.js');
  assert.doesNotMatch(state, /\blet\s+activeDraftFlow\b/);
  assert.doesNotMatch(state, /\blet\s+draftFeedbackItems\b/);
  assert.doesNotMatch(state, /\blet\s+focusedPendingItemIndex\b/);
  assert.doesNotMatch(state, /\blet\s+currentSelection\b/);
  assert.doesNotMatch(state, /function\s+setDraftWorkspace\(/);
});

test('runtime draft helpers use readable canonical names', () => {
  const files = [
    'fixthis-mcp/src/main/console/state.js',
    'fixthis-mcp/src/main/console/main.js',
    'fixthis-mcp/src/main/console/annotations.js',
    'fixthis-mcp/src/main/console/preview.js',
    'fixthis-mcp/src/main/console/history.js',
    'fixthis-mcp/src/main/console/rendering.js',
    'fixthis-mcp/src/main/console/presentation/canonicalRenderingView.js',
    'fixthis-mcp/src/main/console/presentation/previewRegionView.js',
    'fixthis-mcp/src/main/console/presentation/selectionOverlayView.js',
    'fixthis-mcp/src/main/console/presentation/annotationListView.js',
    'fixthis-mcp/src/main/console/presentation/annotationDetailView.js',
    'fixthis-mcp/src/main/console/prompt.js',
    'fixthis-mcp/src/main/console/shortcuts.js',
    'scripts/draftPresentationContract-test.mjs',
    'scripts/pendingItemRecovery-test.mjs',
    'scripts/sessionScopedRequests-test.mjs',
  ];
  for (const file of files) {
    const text = source(file);
    assert.doesNotMatch(text, /\bdFlow\b/, file);
    assert.doesNotMatch(text, /\bdPins\b/, file);
    assert.doesNotMatch(text, /\bdFocus\b/, file);
    assert.doesNotMatch(text, /\bdSel\b/, file);
    assert.doesNotMatch(text, /\bsetWs\b/, file);
    assert.doesNotMatch(text, /\bdw\b/, file);
  }

  const state = source('fixthis-mcp/src/main/console/state.js');
  assert.match(state, /function draftFlow\(\)/);
  assert.match(state, /function draftItemList\(\)/);
  assert.match(state, /function draftFocusIndex\(\)/);
  assert.match(state, /function draftSelection\(\)/);
  assert.match(state, /function replaceDraftWorkspace\(nextWorkspace\)/);
  assert.match(state, /let draftWorkspace = createEmptyDraftWorkspace\(\);/);
});

test('legacy session navigation does not also dispatch canonical session effects', () => {
  const history = source('fixthis-mcp/src/main/console/history.js');
  const openBody = extractFunctionBody(history, 'async function openSession(sessionId)');
  assert.doesNotMatch(openBody, /store\.dispatch\(ConsoleEvents\.sessionRowClicked/);
  assert.match(openBody, /resolvePendingBeforeBoundary\('open-session',\s*sessionId\)/);
  assert.match(openBody, /requestJson\('\/api\/session\/open'/);
});

test('legacy annotate flow does not dispatch duplicate canonical preview capture', () => {
  const history = source('fixthis-mcp/src/main/console/history.js');
  const enterBody = extractFunctionBody(history, 'async function enterAnnotateMode()');
  assert.doesNotMatch(enterBody, /requestCanonicalPreviewCapture\(\)/);
  assert.match(enterBody, /await startDraftAnnotationFlow\(\)/);
});

test('browser console ports do not reference dead draft endpoints or obsolete recovery namespaces', () => {
  const ports = source('fixthis-mcp/src/main/console/adapters/browserPorts.js');
  assert.doesNotMatch(ports, /\/api\/feedback\/items/);
  assert.doesNotMatch(ports, /fixthis\.recovery\./);
  assert.doesNotMatch(ports, /fixthis\.draftWorkspace\./);
});

test('pending annotation detail edits route through draft workspace update use case', () => {
  const detail = source('fixthis-mcp/src/main/console/presentation/annotationDetailView.js');
  const pendingBody = extractFunctionBody(detail, 'function renderAnnotationDetail(item, index)');
  assert.doesNotMatch(pendingBody, /\bitem\.(label|comment|severity|status)\s*=/);
  assert.match(pendingBody, /updatePendingDraftItem\(/);
});

test('focused pending comment flush does not mutate draft item directly', () => {
  const annotations = source('fixthis-mcp/src/main/console/annotations.js');
  const flushBody = extractFunctionBody(annotations, 'function flushFocusedPendingComment()');
  assert.doesNotMatch(flushBody, /\bitem\.comment\s*=/);
  assert.match(flushBody, /updatePendingDraftItem\(/);
});

test('composer comment input does not mutate focused draft item directly', () => {
  const annotations = source('fixthis-mcp/src/main/console/annotations.js');
  const updateBody = extractFunctionBody(annotations, 'function updateSelectedAnnotationComment()');
  assert.doesNotMatch(updateBody, /\bitem\.comment\s*=/);
  assert.match(updateBody, /updatePendingDraftItem\(/);
});
