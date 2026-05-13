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
