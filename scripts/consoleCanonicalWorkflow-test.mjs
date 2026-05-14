import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'domain/workspaceState.js',
  'domain/consoleAppState.js',
  'domain/consoleInvariants.js',
  'domain/consoleReducer.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');

const factory = new Function(`${sources}; return {
  createInitialConsoleAppState,
  reduceConsoleAppState,
  assertConsoleInvariants,
  WorkspaceKind
};`);
const m = factory();

function preview(n) {
  return {
    previewId: 'preview-' + n,
    screen: { screenId: 'screen-' + n, fingerprint: 'fp-' + n, roots: [] },
    screenshotUrl: '/api/preview/preview-' + n + '/screenshot/full?sessionId=session-a',
    frozenAtEpochMillis: n,
  };
}

test('active session reclick preserves draft workflow', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) }).state;
  const before = JSON.stringify(state.workspace);
  state = m.reduceConsoleAppState(state, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-a' }).state;
  assert.equal(JSON.stringify(state.workspace), before);
  assert.equal(state.workspace.kind, m.WorkspaceKind.DRAFT);
  m.assertConsoleInvariants(state);
});

test('random-style boundary sequence never switches while boundary is unresolved', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [{ sessionId: 'session-a' }, { sessionId: 'session-b' }],
  });
  const events = [
    { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) },
    { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' },
    { type: 'PREVIEW_CAPTURE_SUCCEEDED', requestId: 'late', sessionId: 'session-b', generation: 999, preview: preview(2) },
    { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' },
    { type: 'SESSION_ROW_CLICKED', sessionId: 'session-a' },
  ];
  for (const event of events) {
    state = m.reduceConsoleAppState(state, event).state;
    m.assertConsoleInvariants(state);
  }
  assert.equal(state.activeSessionId, 'session-a');
  assert.equal(state.pendingBoundary.targetSessionId, 'session-b');
  assert.equal(state.workspace.context.previewId, 'preview-1');
});

test('browser console modules do not directly mutate legacy session or preview state', () => {
  const files = ['history.js', 'annotations.js', 'preview.js', 'prompt.js', 'rendering.js', 'main.js'];
  for (const file of files) {
    const content = readFileSync(resolve(root, 'fixthis-mcp/src/main/console', file), 'utf8');
    assert.doesNotMatch(content, /state\.session\s*=/, `${file} must not assign state.session`);
    assert.doesNotMatch(content, /state\.preview\s*=/, `${file} must not assign state.preview`);
    assert.doesNotMatch(content, /resetAnnotationComposerState\(/, `${file} must not orchestrate legacy reset`);
    assert.doesNotMatch(content, /invalidatePreviewContext\(/, `${file} must not orchestrate legacy preview invalidation`);
  }
});
