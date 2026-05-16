import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const boundarySrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/domain/consoleBoundary.js'), 'utf8');
const storageSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/draftStorageAdapter.js'), 'utf8');
const factory = new Function(`${boundarySrc}\n${storageSrc}; return {
  draftWorkspaceKey,
  draftWorkspaceIndexKey,
  createDraftStorageAdapter,
  dropEmptyEntries
};`);
const m = factory();

function fakeLocalStorage(seed = {}) {
  const values = new Map(Object.entries(seed));
  return {
    getItem: (key) => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key),
    key: (index) => Array.from(values.keys())[index] || null,
    get length() { return values.size; },
    dump: () => Object.fromEntries(values.entries()),
  };
}

test('workspace storage is keyed by captured session and workspace', () => {
  const localStorage = fakeLocalStorage();
  const adapter = m.createDraftStorageAdapter(localStorage);
  adapter.saveWorkspace({
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'ws-a',
    revision: 2,
    context: { sessionId: 'session-a' },
    items: [{ draftItemId: 'draft-1', comment: 'stored' }],
  });
  assert.ok(localStorage.getItem(m.draftWorkspaceKey('session-a', 'ws-a')));
  assert.deepEqual(adapter.loadWorkspacesForSession('session-a').map((w) => w.workspaceId), ['ws-a']);
  assert.deepEqual(adapter.loadWorkspacesForSession('session-b'), []);
});

test('deleteWorkspace removes stored workspace and index entry', () => {
  const localStorage = fakeLocalStorage();
  const adapter = m.createDraftStorageAdapter(localStorage);
  adapter.saveWorkspace({
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'ws-a',
    context: { sessionId: 'session-a' },
    items: [{ draftItemId: 'draft-1' }],
  });

  adapter.deleteWorkspace('session-a', 'ws-a');

  assert.equal(localStorage.getItem(m.draftWorkspaceKey('session-a', 'ws-a')), null);
  assert.deepEqual(adapter.loadWorkspacesForSession('session-a'), []);
});

test('deleteWorkspacesForSession removes every indexed workspace and the index', () => {
  const localStorage = fakeLocalStorage();
  const adapter = m.createDraftStorageAdapter(localStorage);
  adapter.saveWorkspace({
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'workspace-1',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    items: [{ draftItemId: 'draft-1', comment: 'one' }],
  });
  adapter.saveWorkspace({
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'workspace-2',
    context: { sessionId: 'session-a', previewId: 'preview-b' },
    items: [{ draftItemId: 'draft-2', comment: 'two' }],
  });

  adapter.deleteWorkspacesForSession('session-a');

  assert.equal(localStorage.getItem('fixthis.workspace.session-a.workspace-1'), null);
  assert.equal(localStorage.getItem('fixthis.workspace.session-a.workspace-2'), null);
  assert.equal(localStorage.getItem('fixthis.workspace.index.session-a'), null);
});

test('loadWorkspacesForSession drops malformed indexed workspace payloads', () => {
  const localStorage = fakeLocalStorage({
    'fixthis.workspace.index.session-a': JSON.stringify(['ws-bad']),
    'fixthis.workspace.session-a.ws-bad': '{bad json',
  });
  const adapter = m.createDraftStorageAdapter(localStorage);

  assert.deepEqual(adapter.loadWorkspacesForSession('session-a'), []);
  assert.equal(localStorage.getItem('fixthis.workspace.session-a.ws-bad'), null);
});

test('dropEmptyEntries removes exact empty comments while keeping whitespace comments', () => {
  const envelope = {
    workspaceId: 'ws-a',
    items: [
      { draftItemId: 'draft-1', comment: 'real' },
      { draftItemId: 'draft-2', comment: '' },
      { draftItemId: 'draft-3', comment: '  ' },
    ],
  };

  const filtered = m.dropEmptyEntries(envelope);

  assert.deepEqual(filtered.items.map((item) => item.draftItemId), ['draft-1', 'draft-3']);
});

test('loadWorkspacesForSession ignores stored workspaces with no non-empty entries', () => {
  const localStorage = fakeLocalStorage();
  const adapter = m.createDraftStorageAdapter(localStorage);
  adapter.saveWorkspace({
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'ws-empty',
    context: { sessionId: 'session-a' },
    items: [{ draftItemId: 'draft-1', comment: '' }],
  });

  assert.deepEqual(adapter.loadWorkspacesForSession('session-a'), []);
});

test('schema v1 pending envelope migrates into schema v2 workspace recovery', () => {
  const legacy = {
    schemaVersion: 1,
    sessionId: 'session-a',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    previewId: 'preview-a',
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/shot.png',
    frozenAtEpochMillis: 1000,
    items: [{ annotationId: 'local-1', comment: 'legacy' }],
  };
  const localStorage = fakeLocalStorage({ 'fixthis.pending.session-a': JSON.stringify(legacy) });
  const adapter = m.createDraftStorageAdapter(localStorage, { nextWorkspaceId: () => 'ws-migrated' });
  const migrated = adapter.migrateLegacyPending('session-a');
  assert.equal(migrated.length, 1);
  assert.equal(migrated[0].schemaVersion, 2);
  assert.equal(migrated[0].workspaceId, 'ws-migrated');
  assert.equal(migrated[0].context.sessionId, 'session-a');
  assert.equal(migrated[0].items[0].draftItemId, 'local-1');
});

test('schema v1 pending migration consumes the legacy mirror once', () => {
  const legacy = {
    schemaVersion: 1,
    sessionId: 'session-a',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    previewId: 'preview-a',
    screen: { screenId: 'screen-a' },
    screenshotUrl: '/shot.png',
    frozenAtEpochMillis: 1000,
    items: [{ annotationId: 'local-1', comment: 'legacy' }],
  };
  let workspaceSequence = 0;
  const localStorage = fakeLocalStorage({ 'fixthis.pending.session-a': JSON.stringify(legacy) });
  const adapter = m.createDraftStorageAdapter(localStorage, { nextWorkspaceId: () => 'ws-' + (++workspaceSequence) });

  const migrated = adapter.migrateLegacyPending('session-a');
  adapter.deleteWorkspace('session-a', migrated[0].workspaceId);

  assert.equal(localStorage.getItem('fixthis.pending.session-a'), null);
  assert.deepEqual(adapter.migrateLegacyPending('session-a'), []);
});
