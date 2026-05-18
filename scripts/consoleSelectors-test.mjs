import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'studioWorkflow.js',
  'domain/workspaceState.js',
  'domain/consoleAppState.js',
  'domain/consoleInvariants.js',
  'domain/consoleWorkflowProjection.js',
  'domain/consoleReducer.js',
  'domain/consoleSelectors.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');

const factory = new Function(`${sources}; return {
  createInitialConsoleAppState,
  reduceConsoleAppState,
  selectInspectorModel,
  selectCanvasModel,
  selectBoundarySheet,
  selectPromptReadiness,
  selectHistoryModel,
  selectDraftLockModel,
  selectToolbarModel
};`);
const m = factory();

function preview() {
  return {
    previewId: 'preview-a',
    screen: { screenId: 'screen-a', fingerprint: 'fp-a', roots: [] },
    screenshotUrl: '/api/preview/preview-a/screenshot/full?sessionId=session-a',
    frozenAtEpochMillis: 1,
  };
}

test('draft workspace selects draft inspector and frozen canvas', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview() }).state;
  const inspector = m.selectInspectorModel(state);
  const canvas = m.selectCanvasModel(state);
  assert.equal(inspector.title, 'Draft Annotations');
  assert.equal(inspector.primaryAction, null);
  assert.equal(inspector.secondaryAction, null);
  assert.equal(canvas.mode, 'frozenDraft');
  assert.match(canvas.lockLabel, /Session/);
});

test('pending boundary selects session switch sheet', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [{ sessionId: 'session-a' }, { sessionId: 'session-b' }],
  });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview() }).state;
  state = m.reduceConsoleAppState(state, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' }).state;
  const boundary = m.selectBoundarySheet(state);
  assert.equal(boundary.kind, 'session-switch');
  assert.deepEqual(boundary.actions.map((action) => action.type), [
    'BOUNDARY_SAVE_DRAFT_CLICKED',
    'BOUNDARY_KEEP_RECOVERY_CLICKED',
    'BOUNDARY_DISCARD_CLICKED',
    'BOUNDARY_CANCEL_CLICKED',
  ]);
});

test('draft lock model describes canonical draft context', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview() }).state;
  const model = m.selectDraftLockModel(state);
  assert.equal(model.visible, true);
  assert.equal(model.sessionId, 'session-a');
  assert.equal(model.previewId, 'preview-a');
});

test('toolbar model disables live preview actions while draft is locked', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview() }).state;
  const model = m.selectToolbarModel(state);
  assert.equal(model.previewLocked, true);
  assert.equal(model.canAnnotate, false);
});
