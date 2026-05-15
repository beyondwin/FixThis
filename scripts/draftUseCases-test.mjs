import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'draftWorkspace.js',
  'draftWorkspaceHistory.js',
  'draftPorts.js',
  'draftApiAdapter.js',
  'draftUseCases.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');
const factory = new Function(`${sources}; return {
  createEmptyDraftWorkspace,
  createFakeDraftPorts,
  startDraftFreeze,
  addDraftItem,
  updateDraftItem,
  deleteDraftItem,
  persistDraftWorkspace,
  resolveDraftBoundary,
  resolveLifecycleBoundary
};`);
const m = factory();

test('startDraftFreeze captures session preview context through ports', async () => {
  const ports = m.createFakeDraftPorts({
    ids: { nextWorkspaceId: () => 'ws-a', nextDraftItemId: () => 'draft-unused' },
    clock: { now: () => 1234 },
    preview: {
      capture: async () => ({ previewId: 'preview-a', screen: { screenId: 'screen-a', fingerprint: 'fp-a' }, activity: 'MainActivity' }),
      screenshotUrl: (previewId, sessionId) => `/api/preview/${previewId}/screenshot/full?sessionId=${sessionId}`,
    },
  });
  const workspace = await m.startDraftFreeze(m.createEmptyDraftWorkspace(), {
    sessionId: 'session-a',
    selectedDeviceSerial: 'device-a',
  }, ports);
  assert.equal(workspace.workspaceId, 'ws-a');
  assert.equal(workspace.context.sessionId, 'session-a');
  assert.equal(workspace.context.previewId, 'preview-a');
  assert.equal(workspace.context.screenFingerprint, 'fp-a');
});

test('add update delete use cases use stable draft item ids and history', () => {
  const ports = m.createFakeDraftPorts({ ids: { nextDraftItemId: () => 'draft-1', nextWorkspaceId: () => 'ws-unused' } });
  let workspace = {
    workspaceId: 'ws-a',
    revision: 1,
    lifecycle: 'editing',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    screen: {},
    screenshotUrl: '/shot.png',
    items: [],
    history: { undoStack: [], redoStack: [] },
  };
  workspace = m.addDraftItem(workspace, { targetType: 'area', bounds: { left: 1, top: 1, right: 2, bottom: 2 }, label: 'Area' }, ports);
  assert.equal(workspace.items[0].draftItemId, 'draft-1');
  workspace = m.updateDraftItem(workspace, 'draft-1', { comment: 'fix' });
  assert.equal(workspace.items[0].comment, 'fix');
  workspace = m.deleteDraftItem(workspace, 'draft-1');
  assert.equal(workspace.items.length, 0);
  assert.equal(workspace.history.undoStack.at(-1).kind, 'delete');
});

test('updateDraftItem can update typing state without polluting undo history', () => {
  let workspace = {
    workspaceId: 'ws-a',
    revision: 1,
    lifecycle: 'editing',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    screen: {},
    screenshotUrl: '/shot.png',
    items: [{ draftItemId: 'draft-1', comment: '', label: 'Old label' }],
    history: { undoStack: [], redoStack: [] },
  };

  workspace = m.updateDraftItem(workspace, 'draft-1', { comment: 'typing' }, { recordHistory: false });

  assert.equal(workspace.revision, 2);
  assert.equal(workspace.items[0].comment, 'typing');
  assert.equal(workspace.history.undoStack.length, 0);
});

test('persistDraftWorkspace returns conflict without clearing workspace', async () => {
  const workspace = {
    workspaceId: 'ws-a',
    revision: 2,
    lifecycle: 'editing',
    context: { sessionId: 'session-a', previewId: 'preview-a', screenFingerprint: 'fp-a' },
    screen: { screenId: 'screen-a' },
    items: [{ draftItemId: 'draft-1', targetType: 'area', bounds: { left: 1, top: 1, right: 2, bottom: 2 }, comment: 'fix' }],
  };
  const ports = m.createFakeDraftPorts({
    feedbackApi: {
      saveDraftWorkspace: async () => ({ conflict: { error: 'screen_fingerprint_mismatch' } }),
    },
  });
  const result = await m.persistDraftWorkspace(workspace, ports);
  assert.equal(result.workspace.lifecycle, 'conflict');
  assert.equal(result.workspace.items.length, 1);
});

test('resolveDraftBoundary keep saves recoverable workspace without discard', async () => {
  const saved = [];
  const workspace = { workspaceId: 'ws-a', context: { sessionId: 'session-a' }, items: [{ draftItemId: 'draft-1' }] };
  const ports = m.createFakeDraftPorts({
    boundaryPrompt: { choose: async () => 'keep' },
    storage: { saveWorkspace: (value) => saved.push(value) },
  });
  const result = await m.resolveDraftBoundary(workspace, { kind: 'open-session' }, ports);
  assert.equal(result.choice, 'keep');
  assert.equal(saved[0].workspaceId, 'ws-a');
});

test('resolveLifecycleBoundary cancels context change when pending recovery has comments', async () => {
  const recovery = {
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'ws-recovery',
    revision: 1,
    lifecycle: 'editing',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    screen: {},
    screenshotUrl: '/shot.png',
    items: [{ draftItemId: 'draft-1', comment: 'recover this' }],
  };
  const prompts = [];
  const ports = m.createFakeDraftPorts({
    recoveryPrompt: {
      choose: async (nextRecovery, action) => {
        prompts.push([nextRecovery.workspaceId, action.kind]);
        return 'cancel';
      },
    },
  });

  const result = await m.resolveLifecycleBoundary({
    action: 'new-session',
    activeWorkspace: m.createEmptyDraftWorkspace(),
    pendingRecovery: recovery,
    ports,
  });

  assert.equal(result.outcome, 'cancel');
  assert.equal(result.nextPendingRecovery, recovery);
  assert.deepEqual(prompts, [['ws-recovery', 'new-session']]);
});

test('resolveLifecycleBoundary clears pending recovery only after explicit clear choice', async () => {
  const deleted = [];
  const recovery = {
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'ws-recovery',
    context: { sessionId: 'session-a' },
    items: [{ draftItemId: 'draft-1', comment: 'recover this' }],
  };
  const ports = m.createFakeDraftPorts({
    recoveryPrompt: { choose: async () => 'clear' },
    storage: { deleteWorkspace: (sessionId, workspaceId) => deleted.push([sessionId, workspaceId]) },
  });

  const result = await m.resolveLifecycleBoundary({
    action: 'delete-session',
    targetSessionId: 'session-a',
    activeWorkspace: m.createEmptyDraftWorkspace(),
    pendingRecovery: recovery,
    ports,
  });

  assert.equal(result.outcome, 'continue');
  assert.equal(result.nextPendingRecovery, null);
  assert.deepEqual(deleted, [['session-a', 'ws-recovery']]);
});

test('resolveLifecycleBoundary save conflict cancels and keeps active workspace', async () => {
  const workspace = {
    workspaceId: 'ws-a',
    revision: 2,
    lifecycle: 'editing',
    context: { sessionId: 'session-a', previewId: 'preview-a', screenFingerprint: 'fp-a' },
    screen: { screenId: 'screen-a' },
    items: [{ draftItemId: 'draft-1', targetType: 'area', bounds: { left: 1, top: 1, right: 2, bottom: 2 }, comment: 'save me' }],
  };
  const ports = m.createFakeDraftPorts({
    boundaryPrompt: { choose: async () => 'save' },
    feedbackApi: {
      saveDraftWorkspace: async () => ({ conflict: { error: 'screen_fingerprint_mismatch' } }),
    },
  });

  const result = await m.resolveLifecycleBoundary({
    action: 'open-session',
    targetSessionId: 'session-b',
    activeWorkspace: workspace,
    pendingRecovery: null,
    ports,
  });

  assert.equal(result.outcome, 'cancel');
  assert.equal(result.nextWorkspace.lifecycle, 'conflict');
  assert.equal(result.nextWorkspace.items.length, 1);
});

test('resolveLifecycleBoundary keep stores active draft and clears active workspace projection', async () => {
  const saved = [];
  const workspace = {
    workspaceId: 'ws-a',
    revision: 2,
    lifecycle: 'editing',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    screen: {},
    screenshotUrl: '/shot.png',
    items: [{ draftItemId: 'draft-1', comment: 'keep me local' }],
  };
  const ports = m.createFakeDraftPorts({
    boundaryPrompt: { choose: async () => 'keep' },
    storage: { saveWorkspace: (value) => saved.push(value) },
  });

  const result = await m.resolveLifecycleBoundary({
    action: 'open-session',
    targetSessionId: 'session-b',
    activeWorkspace: workspace,
    pendingRecovery: null,
    ports,
  });

  assert.equal(result.outcome, 'continue');
  assert.equal(result.nextWorkspace.lifecycle, 'empty');
  assert.equal(saved[0].workspaceId, 'ws-a');
});
