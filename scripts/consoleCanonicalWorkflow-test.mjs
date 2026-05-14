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
  const legacyResetPattern = new RegExp('reset' + 'AnnotationComposerState\\(');
  const legacyPreviewInvalidationPattern = new RegExp('invalidate' + 'PreviewContext\\(');
  for (const file of files) {
    const content = readFileSync(resolve(root, 'fixthis-mcp/src/main/console', file), 'utf8');
    assert.doesNotMatch(content, /state\.session\s*=/, `${file} must not assign state.session`);
    assert.doesNotMatch(content, /state\.preview\s*=/, `${file} must not assign state.preview`);
    assert.doesNotMatch(content, legacyResetPattern, `${file} must not orchestrate legacy reset`);
    assert.doesNotMatch(content, legacyPreviewInvalidationPattern, `${file} must not orchestrate legacy preview invalidation`);
  }
});

test('save during pending boundary opens target session after draft save succeeds', () => {
  let state = m.createInitialConsoleAppState({
    activeSessionId: 'session-a',
    sessions: [{ sessionId: 'session-a' }, { sessionId: 'session-b' }],
  });
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) }).state;
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_TARGET_SELECTED', itemId: 'item-1', selection: { label: 'CTA' }, comment: 'Fix' }).state;
  state = m.reduceConsoleAppState(state, { type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' }).state;
  const save = m.reduceConsoleAppState(state, { type: 'BOUNDARY_SAVE_DRAFT_CLICKED' });

  assert.equal(save.effects[0].kind, 'saveDraft');
  assert.equal(save.effects[0].sessionId, 'session-a');
  assert.equal(save.effects[0].targetSessionId, 'session-b');

  const saved = m.reduceConsoleAppState(save.state, {
    type: 'DRAFT_SAVE_SUCCEEDED',
    requestId: save.effects[0].requestId,
    sessionId: 'session-a',
    targetSessionId: 'session-b',
    workspaceId: save.effects[0].workspaceId,
    generation: save.effects[0].generation,
    session: { sessionId: 'session-a', items: [] },
  });
  assert.equal(saved.state.activeSessionId, 'session-b');
  assert.equal(saved.state.workspace.kind, m.WorkspaceKind.LIVE_PREVIEW);
  m.assertConsoleInvariants(saved.state);
});

test('annotate click plans preview capture and capture success starts draft', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  const annotate = m.reduceConsoleAppState(state, { type: 'ANNOTATE_CLICKED' });
  assert.equal(annotate.effects[0].kind, 'capturePreview');
  state = m.reduceConsoleAppState(annotate.state, {
    type: 'PREVIEW_CAPTURE_SUCCEEDED',
    requestId: annotate.effects[0].requestId,
    sessionId: 'session-a',
    generation: annotate.effects[0].generation,
    preview: preview(1),
  }).state;
  const draft = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) }).state;
  assert.equal(draft.workspace.kind, m.WorkspaceKind.DRAFT);
});

test('copy prompt is planned from draft workspace only', () => {
  let state = m.createInitialConsoleAppState({ activeSessionId: 'session-a', sessions: [{ sessionId: 'session-a' }] });
  assert.deepEqual(m.reduceConsoleAppState(state, { type: 'COPY_PROMPT_CLICKED' }).effects, []);
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_STARTED_FROM_PREVIEW', sessionId: 'session-a', preview: preview(1) }).state;
  state = m.reduceConsoleAppState(state, { type: 'DRAFT_TARGET_SELECTED', itemId: 'item-1', selection: { label: 'CTA' }, comment: 'Fix' }).state;
  const copy = m.reduceConsoleAppState(state, { type: 'COPY_PROMPT_CLICKED' });
  assert.equal(copy.effects[0].kind, 'copyPrompt');
  assert.match(copy.effects[0].markdown, /Fix/);
});
