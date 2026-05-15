import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['draftWorkspace.js'],
  symbols: [
    'DraftLifecycle',
    'createEmptyDraftWorkspace',
    'createDraftContext',
    'createFrozenDraftWorkspace',
    'reduceDraftWorkspace',
    'draftWorkspaceItems',
    'requireDraftContext',
  ],
});

const context = {
  sessionId: 'session-a',
  previewId: 'preview-a',
  screenId: 'screen-a',
  screenFingerprint: 'fp-a',
  deviceSerial: 'device-a',
  frozenAtEpochMillis: 1000,
  activityName: 'MainActivity',
};

test('empty workspace has no mutable browser dependencies', () => {
  const workspace = m.createEmptyDraftWorkspace();
  assert.equal(workspace.lifecycle, m.DraftLifecycle.EMPTY);
  assert.deepEqual(workspace.items, []);
  assert.equal(workspace.revision, 0);
  assert.equal(workspace.context, null);
});

test('freeze creates immutable context-bound workspace', () => {
  const workspace = m.createFrozenDraftWorkspace({
    workspaceId: 'ws-a',
    context,
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/api/preview/preview-a/screenshot/full?sessionId=session-a',
  });
  assert.equal(workspace.workspaceId, 'ws-a');
  assert.equal(workspace.lifecycle, m.DraftLifecycle.EDITING);
  assert.equal(workspace.revision, 1);
  assert.deepEqual(workspace.context, context);
  assert.equal(m.requireDraftContext(workspace).sessionId, 'session-a');
});

test('add update delete item preserves stable draftItemId and increments revision', () => {
  let workspace = m.createFrozenDraftWorkspace({
    workspaceId: 'ws-a',
    context,
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/screenshot.png',
  });
  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'ADD_ITEM',
    workspaceId: 'ws-a',
    draftItem: {
      draftItemId: 'draft-1',
      targetType: 'area',
      bounds: { left: 1, top: 2, right: 30, bottom: 40 },
      label: 'Custom area',
      comment: '',
      severity: 'med',
      status: 'open',
    },
  });
  assert.equal(workspace.revision, 2);
  assert.equal(workspace.items[0].draftItemId, 'draft-1');

  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'UPDATE_ITEM',
    workspaceId: 'ws-a',
    draftItemId: 'draft-1',
    patch: { comment: 'fix this button' },
  });
  assert.equal(workspace.revision, 3);
  assert.equal(workspace.items[0].comment, 'fix this button');

  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'DELETE_ITEM',
    workspaceId: 'ws-a',
    draftItemId: 'draft-1',
  });
  assert.equal(workspace.revision, 4);
  assert.equal(workspace.items.length, 0);
});

test('stale workspace id and revision actions are ignored', () => {
  let workspace = m.createFrozenDraftWorkspace({
    workspaceId: 'ws-a',
    context,
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/screenshot.png',
  });
  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'ADD_ITEM',
    workspaceId: 'ws-b',
    draftItem: { draftItemId: 'draft-1' },
  });
  assert.equal(workspace.items.length, 0);

  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'SAVE_SUCCEEDED',
    workspaceId: 'ws-a',
    expectedRevision: 999,
  });
  assert.equal(workspace.lifecycle, m.DraftLifecycle.EDITING);
});

test('save conflict keeps items and moves lifecycle to conflict', () => {
  let workspace = m.createFrozenDraftWorkspace({
    workspaceId: 'ws-a',
    context,
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/screenshot.png',
  });
  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'ADD_ITEM',
    workspaceId: 'ws-a',
    draftItem: { draftItemId: 'draft-1', comment: 'copy' },
  });
  workspace = m.reduceDraftWorkspace(workspace, {
    type: 'SAVE_CONFLICT',
    workspaceId: 'ws-a',
    expectedRevision: workspace.revision,
    conflict: { error: 'screen_fingerprint_mismatch' },
  });
  assert.equal(workspace.lifecycle, m.DraftLifecycle.CONFLICT);
  assert.equal(workspace.items.length, 1);
  assert.equal(workspace.lastError.error, 'screen_fingerprint_mismatch');
});
