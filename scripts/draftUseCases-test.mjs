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
  resolveDraftBoundary
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
