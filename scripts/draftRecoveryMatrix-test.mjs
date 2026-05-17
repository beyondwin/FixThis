import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = [
  'fixthis-mcp/src/main/console/draftWorkspace.js',
  'fixthis-mcp/src/main/console/draftWorkspaceHistory.js',
  'fixthis-mcp/src/main/console/boundaryPolicy.js',
  'fixthis-mcp/src/main/console/draftPorts.js',
  'fixthis-mcp/src/main/console/draftApiAdapter.js',
  'fixthis-mcp/src/main/console/draftUseCases.js',
].map(file => readFileSync(resolve(root, file), 'utf8')).join('\n');

function loadRuntime() {
  return new Function(`
    ${source}
    return {
      createDraftContext,
      createFrozenDraftWorkspace,
      draftWorkspaceRecoveryEnvelope,
      recoverDraftWorkspaceFromEnvelope,
      resolveLifecycleBoundary,
      hasCommentedRecovery,
      recoveryItems,
    };
  `)();
}

function workspaceWithItems(items) {
  const runtime = loadRuntime();
  return runtime.createFrozenDraftWorkspace({
    workspaceId: 'workspace-1',
    context: runtime.createDraftContext({
      sessionId: 'session-1',
      previewId: 'preview-1',
      screenId: 'screen-1',
      frozenAtEpochMillis: 1,
    }),
    screen: null,
    screenshotUrl: '/screenshot.png',
  }).constructor === Object
    ? {
        ...runtime.createFrozenDraftWorkspace({
          workspaceId: 'workspace-1',
          context: runtime.createDraftContext({
            sessionId: 'session-1',
            previewId: 'preview-1',
            screenId: 'screen-1',
            frozenAtEpochMillis: 1,
          }),
          screen: null,
          screenshotUrl: '/screenshot.png',
        }),
        items,
      }
    : assert.fail('workspace factory returned a non-object');
}

function ports(choice = 'discard') {
  const calls = [];
  return {
    calls,
    storage: {
      saveWorkspace: (...args) => calls.push(['saveWorkspace', ...args]),
      deleteWorkspace: (...args) => calls.push(['deleteWorkspace', ...args]),
    },
    boundaryPrompt: {
      choose: async () => choice,
    },
    recoveryPrompt: {
      choose: async () => choice,
    },
    feedbackApi: {
      saveDraftWorkspace: async () => ({ sessionId: 'session-1', items: [] }),
    },
  };
}

test('pin-only active draft is saved to browser recovery on session switch without prompting', async () => {
  const runtime = loadRuntime();
  const activeWorkspace = workspaceWithItems([
    { draftItemId: 'draft-1', comment: '', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]);
  const p = ports();

  const result = await runtime.resolveLifecycleBoundary({
    action: 'session-switch',
    targetSessionId: 'session-2',
    activeWorkspace,
    pendingRecovery: null,
    ports: p,
  });

  assert.equal(result.outcome, 'continue');
  assert.deepEqual(p.calls.map(call => call[0]), ['saveWorkspace']);
});

test('commented active draft opens boundary and preserves on cancel', async () => {
  const runtime = loadRuntime();
  const activeWorkspace = workspaceWithItems([
    { draftItemId: 'draft-1', comment: 'Make this smaller', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]);
  const p = ports('cancel');

  const result = await runtime.resolveLifecycleBoundary({
    action: 'session-switch',
    targetSessionId: 'session-2',
    activeWorkspace,
    pendingRecovery: null,
    ports: p,
  });

  assert.equal(result.outcome, 'cancel');
  assert.equal(result.nextWorkspace.workspaceId, 'workspace-1');
});

test('Save to MCP completion clears saved browser recovery', async () => {
  const runtime = loadRuntime();
  const activeWorkspace = workspaceWithItems([
    { draftItemId: 'draft-1', comment: 'Make this smaller', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]);
  const p = ports('save');

  const result = await runtime.resolveLifecycleBoundary({
    action: 'session-switch',
    targetSessionId: 'session-2',
    activeWorkspace,
    pendingRecovery: null,
    ports: p,
  });

  assert.equal(result.outcome, 'continue');
  assert.equal(result.savedSession.sessionId, 'session-1');
  assert.deepEqual(p.calls.map(call => call[0]), ['deleteWorkspace']);
});

test('pending recovery resume cancels navigation and restores workspace', async () => {
  const runtime = loadRuntime();
  const pendingRecovery = runtime.draftWorkspaceRecoveryEnvelope(workspaceWithItems([
    { draftItemId: 'draft-1', comment: 'Recover me', targetType: 'area', bounds: { left: 0, top: 0, right: 10, bottom: 10 } },
  ]));
  const p = ports('resume');

  const result = await runtime.resolveLifecycleBoundary({
    action: 'session-switch',
    targetSessionId: 'session-2',
    activeWorkspace: null,
    pendingRecovery,
    ports: p,
  });

  assert.equal(result.outcome, 'cancel');
  assert.equal(result.nextWorkspace.workspaceId, 'workspace-1');
});
