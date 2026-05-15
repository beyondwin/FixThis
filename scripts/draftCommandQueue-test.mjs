import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftCommandQueue.js'), 'utf8');
const factory = new Function(`${src}; return { createDraftCommandQueue };`);
const m = factory();

test('queue serializes commands and fences stale workspace responses', async () => {
  let workspace = { workspaceId: 'ws-a', revision: 1 };
  const events = [];
  const queue = m.createDraftCommandQueue({
    getWorkspace: () => workspace,
    setWorkspace: (next) => { workspace = next; },
    onStaleResponse: () => events.push('stale'),
  });

  const first = queue.enqueue({ kind: 'save', workspaceId: 'ws-a', expectedRevision: 1 }, async () => {
    events.push('first-start');
    workspace = { workspaceId: 'ws-b', revision: 1 };
    return { workspace: { workspaceId: 'ws-a', revision: 2 } };
  });
  const second = queue.enqueue({ kind: 'edit', workspaceId: 'ws-b', expectedRevision: 1 }, async () => {
    events.push('second-start');
    return { workspace: { workspaceId: 'ws-b', revision: 2 } };
  });

  await Promise.all([first, second]);
  assert.deepEqual(events, ['first-start', 'stale', 'second-start']);
  assert.equal(workspace.workspaceId, 'ws-b');
  assert.equal(workspace.revision, 2);
});

test('save response is stale when workspace revision changed while request was in flight', async () => {
  let workspace = { workspaceId: 'ws-a', revision: 2, lifecycle: 'editing', items: [{ draftItemId: 'draft-1', comment: 'newer local edit' }] };
  const events = [];
  const queue = m.createDraftCommandQueue({
    getWorkspace: () => workspace,
    setWorkspace: (next) => { workspace = next; events.push(['set', next.lifecycle]); },
    onStaleResponse: () => events.push(['stale']),
  });

  const result = await queue.enqueue(
    { kind: 'save', workspaceId: 'ws-a', expectedRevision: 1 },
    async () => ({ workspace: { lifecycle: 'empty', items: [] }, session: { sessionId: 'session-a' } }),
  );

  assert.equal(result.applied, false);
  assert.equal(result.reason, 'stale_before');
  assert.deepEqual(events, [['stale']]);
  assert.equal(workspace.items[0].comment, 'newer local edit');
});

test('in-flight save cannot clear workspace after reducer edit increments revision', async () => {
  let workspace = {
    workspaceId: 'ws-a',
    revision: 2,
    lifecycle: 'editing',
    items: [{ draftItemId: 'draft-1', comment: 'old' }],
  };
  const events = [];
  let releaseSave;
  const queue = m.createDraftCommandQueue({
    getWorkspace: () => workspace,
    setWorkspace: (next) => { workspace = next; events.push(['set', next.lifecycle]); },
    onStaleResponse: () => events.push(['stale']),
  });

  const save = queue.enqueue({ kind: 'save', workspaceId: 'ws-a', expectedRevision: 2 }, async () => {
    await new Promise((resolve) => { releaseSave = resolve; });
    return { workspace: { workspaceId: 'ws-a', lifecycle: 'empty', items: [] }, session: { sessionId: 'session-a' } };
  });
  await new Promise((resolve) => setImmediate(resolve));

  workspace = {
    ...workspace,
    revision: 3,
    items: [{ draftItemId: 'draft-1', comment: 'newer edit' }],
  };
  releaseSave();
  const result = await save;

  assert.equal(result.applied, false);
  assert.equal(result.reason, 'stale_after');
  assert.deepEqual(events, [['stale']]);
  assert.equal(workspace.revision, 3);
  assert.equal(workspace.items[0].comment, 'newer edit');
});
