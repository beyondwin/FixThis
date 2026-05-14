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

function session(id) {
  return {
    sessionId: id,
    status: 'active',
    items: [],
    screens: [],
    updatedAtEpochMillis: 1,
  };
}

function preview(id, screenId = 'screen-1') {
  return {
    previewId: id,
    screen: { screenId, fingerprint: 'fp-' + screenId, roots: [] },
    screenshotUrl: `/api/preview/${id}/screenshot/full?sessionId=session-a`,
    frozenAtEpochMillis: 10,
  };
}

test('same active session click is a no-op', () => {
  const initial = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [session('session-a')],
  });
  const before = JSON.stringify(initial);
  const result = m.reduceConsoleAppState(initial, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-a' });
  assert.equal(JSON.stringify(result.state), before);
  assert.deepEqual(result.effects, []);
  m.assertConsoleInvariants(result.state);
});

test('different session click with draft creates boundary without switching active session', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [session('session-a'), session('session-b')],
  });
  state = m.reduceConsoleAppState(state, {
    type: 'DRAFT_STARTED_FROM_PREVIEW',
    sessionId: 'session-a',
    preview: preview('preview-a'),
  }).state;
  const result = m.reduceConsoleAppState(state, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' });
  assert.equal(result.state.activeSessionId, 'session-a');
  assert.equal(result.state.workspace.kind, m.WorkspaceKind.DRAFT);
  assert.equal(result.state.pendingBoundary?.targetSessionId, 'session-b');
  assert.deepEqual(result.effects, []);
  m.assertConsoleInvariants(result.state);
});

test('late preview response cannot overwrite draft workspace', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [session('session-a')],
  });
  state = m.reduceConsoleAppState(state, {
    type: 'DRAFT_STARTED_FROM_PREVIEW',
    sessionId: 'session-a',
    preview: preview('preview-a'),
  }).state;
  const result = m.reduceConsoleAppState(state, {
    type: 'PREVIEW_CAPTURE_SUCCEEDED',
    requestId: 'stale',
    sessionId: 'session-a',
    generation: state.effectsGeneration - 1,
    preview: preview('preview-stale'),
  });
  assert.equal(result.state.workspace.kind, m.WorkspaceKind.DRAFT);
  assert.equal(result.state.workspace.context.previewId, 'preview-a');
  m.assertConsoleInvariants(result.state);
});
