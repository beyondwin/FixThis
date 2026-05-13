import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const workspaceSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftWorkspace.js'), 'utf8');
const apiSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftApiAdapter.js'), 'utf8');
const factory = new Function(`${workspaceSrc}; ${apiSrc}; return {
  buildDraftWorkspaceSaveRequest,
  createDraftApiAdapter
};`);
const m = factory();

const workspace = {
  workspaceId: 'ws-a',
  revision: 2,
  context: {
    sessionId: 'session-a',
    previewId: 'preview-a',
    screenFingerprint: 'fp-a',
  },
  screen: { screenId: 'screen-a' },
  items: [{
    draftItemId: 'draft-1',
    targetType: 'area',
    bounds: { left: 1, top: 2, right: 30, bottom: 40 },
    label: 'Custom area',
    severity: 'med',
    status: 'open',
    comment: 'fix',
  }],
};

test('save request includes explicit session context', () => {
  const request = m.buildDraftWorkspaceSaveRequest(workspace);
  assert.equal(request.sessionId, 'session-a');
  assert.equal(request.previewId, 'preview-a');
  assert.equal(request.frozenFingerprint, 'fp-a');
  assert.equal(request.items[0].comment, 'fix');
});

test('save request rejects missing sessionId and previewId', () => {
  assert.throws(() => m.buildDraftWorkspaceSaveRequest({ ...workspace, context: { previewId: 'p' } }), /sessionId/);
  assert.throws(() => m.buildDraftWorkspaceSaveRequest({ ...workspace, context: { sessionId: 's' } }), /previewId/);
});

test('browser API adapter posts explicit session payloads', async () => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return { ok: true, json: async () => ({ sessionId: 'session-a' }) };
  };
  const adapter = m.createDraftApiAdapter({ fetchImpl, consoleToken: 'token' });
  await adapter.saveDraftWorkspace(m.buildDraftWorkspaceSaveRequest(workspace));
  assert.equal(calls[0].url, '/api/items/batch');
  assert.equal(JSON.parse(calls[0].init.body).sessionId, 'session-a');
  assert.equal(calls[0].init.headers.get('X-FixThis-Console-Token'), 'token');
});
