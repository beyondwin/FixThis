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
  'draftCommandQueue.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');
const factory = new Function(`${sources}; return {
  createEmptyDraftWorkspace,
  createFakeDraftPorts,
  startDraftFreeze,
  addDraftItem,
  updateDraftItem,
  deleteDraftItem,
  persistDraftWorkspace,
  draftWorkspaceItems
};`);
const m = factory();

function rng(seed) {
  let value = seed;
  return () => {
    value = (value * 48271) % 0x7fffffff;
    return value / 0x7fffffff;
  };
}

test('random draft workflow never crosses captured session context', async () => {
  const random = rng(42);
  const savedRequests = [];
  let currentSessionId = 'session-a';
  let nextId = 0;
  const ports = m.createFakeDraftPorts({
    ids: {
      nextWorkspaceId: () => 'ws-' + (++nextId),
      nextDraftItemId: () => 'draft-' + (++nextId),
    },
    preview: {
      capture: async () => ({ previewId: 'preview-' + nextId, screen: { screenId: 'screen-' + nextId, fingerprint: 'fp-' + nextId } }),
      screenshotUrl: (previewId, sessionId) => `/api/preview/${previewId}/screenshot/full?sessionId=${sessionId}`,
    },
    feedbackApi: {
      saveDraftWorkspace: async (request) => {
        savedRequests.push(request);
        return { sessionId: request.sessionId, items: request.items.map((_, index) => ({ itemId: `item-${index + 1}` })) };
      },
    },
  });
  let workspace = m.createEmptyDraftWorkspace();
  for (let i = 0; i < 100; i += 1) {
    const op = Math.floor(random() * 6);
    if (op === 0 || workspace.lifecycle === 'empty') {
      workspace = await m.startDraftFreeze(workspace, { sessionId: currentSessionId }, ports);
    } else if (op === 1) {
      workspace = m.addDraftItem(workspace, { targetType: 'area', bounds: { left: 1, top: 1, right: 10, bottom: 10 }, label: 'Area' }, ports);
    } else if (op === 2 && m.draftWorkspaceItems(workspace)[0]) {
      workspace = m.updateDraftItem(workspace, m.draftWorkspaceItems(workspace)[0].draftItemId, { comment: 'comment-' + i });
    } else if (op === 3 && m.draftWorkspaceItems(workspace)[0]) {
      workspace = m.deleteDraftItem(workspace, m.draftWorkspaceItems(workspace)[0].draftItemId);
    } else if (op === 4) {
      currentSessionId = currentSessionId === 'session-a' ? 'session-b' : 'session-a';
    } else if (op === 5 && m.draftWorkspaceItems(workspace).some((item) => String(item.comment || '').trim())) {
      const beforeSession = workspace.context.sessionId;
      const result = await m.persistDraftWorkspace(workspace, ports);
      assert.equal(savedRequests.at(-1).sessionId, beforeSession);
      workspace = result.workspace;
    }
    if (workspace.context?.sessionId) {
      assert.match(workspace.context.sessionId, /^session-/);
    }
    for (const request of savedRequests) {
      assert.ok(request.sessionId);
      assert.equal(request.sessionId, request.sessionId.trim());
    }
  }
});
