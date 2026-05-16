import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['draftUseCases.js'],
  symbols: [
    'createEmptyDraftWorkspace',
    'ensureWorkspaceForSelection',
    'maybeFreeWorkspaceOnEmpty',
  ],
});

const selection = {
  context: {
    sessionId: 'session-a',
    previewId: 'preview-a',
    screenId: 'screen-a',
    screenFingerprint: 'fp-a',
  },
  screen: { screenId: 'screen-a', fingerprint: 'fp-a' },
  screenshotUrl: '/api/preview/preview-a/screenshot/full?sessionId=session-a',
};

test('ensureWorkspaceForSelection allocates exactly once after a target is pending', () => {
  const empty = m.createEmptyDraftWorkspace();
  assert.equal(empty.workspaceId, null);

  const first = m.ensureWorkspaceForSelection(
    { draftWorkspace: empty },
    selection,
    { nextWorkspaceId: () => 'workspace-1' },
  );
  assert.equal(first.workspaceId, 'workspace-1');
  assert.equal(first.context.sessionId, 'session-a');

  const second = m.ensureWorkspaceForSelection(
    { draftWorkspace: first },
    selection,
    { nextWorkspaceId: () => 'workspace-2' },
  );
  assert.equal(second.workspaceId, 'workspace-1');
});

test('maybeFreeWorkspaceOnEmpty frees only the last empty draft item', () => {
  const workspace = {
    workspaceId: 'workspace-1',
    items: [{ draftItemId: 'draft-1', comment: '' }],
  };
  assert.equal(m.maybeFreeWorkspaceOnEmpty(workspace, 'draft-1'), null);

  const keptForText = {
    workspaceId: 'workspace-1',
    items: [{ draftItemId: 'draft-1', comment: 'x' }],
  };
  assert.equal(m.maybeFreeWorkspaceOnEmpty(keptForText, 'draft-1'), keptForText);

  const keptForMultiple = {
    workspaceId: 'workspace-1',
    items: [
      { draftItemId: 'draft-1', comment: '' },
      { draftItemId: 'draft-2', comment: 'x' },
    ],
  };
  assert.equal(m.maybeFreeWorkspaceOnEmpty(keptForMultiple, 'draft-1'), keptForMultiple);
});
