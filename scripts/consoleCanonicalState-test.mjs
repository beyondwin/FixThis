import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'domain/consoleEvents.js',
  'domain/workspaceState.js',
  'domain/consoleAppState.js',
  'domain/consoleInvariants.js',
  'domain/consoleReducer.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');

const factory = new Function(`${sources}; return {
  createInitialConsoleAppState,
  reduceConsoleAppState,
  assertConsoleInvariants,
  ConsoleEvents,
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

test('factory-created session row event opens session through reducer effect', () => {
  const state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [
      { sessionId: 'session-a', status: 'open' },
      { sessionId: 'session-b', status: 'open' },
    ],
  });

  const result = m.reduceConsoleAppState(
    state,
    m.ConsoleEvents.sessionRowClicked('session-b'),
  );

  assert.equal(result.effects.length, 1);
  assert.equal(result.effects[0].kind, 'openSession');
  assert.equal(result.effects[0].sessionId, 'session-b');
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

test('draft item focus, comment, delete, and selection clear stay inside workspace', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [session('session-a')] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview('a') }).state;
  state = m.reduceConsoleAppState(state, {
    type: 'DRAFT_TARGET_SELECTED',
    itemId: 'item-1',
    selection: { bounds: { left: 1, top: 2, right: 30, bottom: 40 }, label: 'Button' },
  }).state;
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_COMMENT_CHANGED', itemId: 'item-1', comment: 'Fix copy' }).state;
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_SELECTION_CLEARED' }).state;

  assert.equal(state.workspace.items[0].comment, 'Fix copy');
  assert.equal(state.workspace.focusedItemId, 'item-1');
  assert.equal(state.workspace.currentSelection, null);

  state = m.reduceConsoleAppState(state, { type: 'DRAFT_ITEM_DELETED', itemId: 'item-1' }).state;
  assert.deepEqual(state.workspace.items, []);
  assert.equal(state.workspace.focusedItemId, null);
  m.assertConsoleInvariants(state);
});

test('stale draft save failure clears in-flight only when generation matches', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [session('session-a')] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview('a') }).state;
  const save = m.reduceConsoleAppState(state, { type: 'SAVE_TO_MCP_CLICKED' });
  state = save.state;

  const stale = m.reduceConsoleAppState(state, {
    type: 'DRAFT_SAVE_FAILED',
    requestId: 'old',
    sessionId: 'session-a',
    workspaceId: state.workspace.context.workspaceId,
    generation: state.effectsGeneration - 1,
    error: 'old failure',
  }).state;
  assert.equal(stale.promptAction.inFlight, true);

  const current = m.reduceConsoleAppState(state, {
    type: 'DRAFT_SAVE_FAILED',
    requestId: save.effects[0].requestId,
    sessionId: 'session-a',
    workspaceId: state.workspace.context.workspaceId,
    generation: state.effectsGeneration,
    error: 'current failure',
  }).state;
  assert.equal(current.promptAction.inFlight, false);
  assert.equal(current.status.variant, 'error');
});
