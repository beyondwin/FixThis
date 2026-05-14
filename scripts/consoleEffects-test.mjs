import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'domain/workspaceState.js',
  'domain/consoleAppState.js',
  'domain/consoleInvariants.js',
  'domain/consoleReducer.js',
  'application/consoleStore.js',
  'application/consoleEffects.js',
].map((name) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', name), 'utf8')).join('\n');

const factory = new Function(`${sources}; return {
  createConsoleStore,
  runConsoleEffect,
  createInitialConsoleAppState
};`);
const m = factory();

test('store dispatch queues reducer effects with current state', () => {
  const rendered = [];
  const store = m.createConsoleStore({
    initialState: m.createInitialConsoleAppState({
      activeSessionId: 'session-a',
      sessions: [{ sessionId: 'session-a' }, { sessionId: 'session-b' }],
    }),
    render: (state) => rendered.push(state.activeSessionId),
  });
  const effects = store.dispatch({ type: 'SESSION_ROW_CLICKED', sessionId: 'session-b' });
  assert.equal(effects.length, 1);
  assert.equal(effects[0].kind, 'openSession');
  assert.deepEqual(rendered, ['session-a', 'session-a']);
});

test('openSession effect dispatches success event with request context', async () => {
  const dispatched = [];
  const effect = { kind: 'openSession', requestId: 'r1', sessionId: 'session-b', generation: 2 };
  await m.runConsoleEffect(effect, {
    dispatch: (event) => dispatched.push(event),
    ports: {
      sessionApi: {
        openSession: async (sessionId) => ({ sessionId, items: [], screens: [] }),
      },
    },
  });
  assert.deepEqual(dispatched, [{
    type: 'SESSION_OPEN_SUCCEEDED',
    requestId: 'r1',
    sessionId: 'session-b',
    generation: 2,
    session: { sessionId: 'session-b', items: [], screens: [] },
  }]);
});

test('copyPrompt effect writes clipboard and dispatches success', async () => {
  const dispatched = [];
  await m.runConsoleEffect(
    { kind: 'copyPrompt', requestId: 'copy-1', sessionId: 'session-a', workspaceId: 'ws-1', markdown: 'hello', generation: 3 },
    {
      dispatch: (event) => dispatched.push(event),
      ports: { clipboard: { writeText: async (text) => assert.equal(text, 'hello') } },
    },
  );
  assert.deepEqual(dispatched, [{
    type: 'PROMPT_COPY_SUCCEEDED',
    requestId: 'copy-1',
    sessionId: 'session-a',
    workspaceId: 'ws-1',
    generation: 3,
  }]);
});

test('deleteRecovery effect uses draft storage port', async () => {
  const calls = [];
  const dispatched = [];
  await m.runConsoleEffect(
    { kind: 'deleteRecovery', sessionId: 'session-a', workspaceId: 'ws-1' },
    {
      dispatch: (event) => dispatched.push(event),
      ports: { draftStorage: { deleteRecovery: async (...args) => calls.push(args) } },
    },
  );
  assert.deepEqual(calls, [['session-a', 'ws-1']]);
  assert.deepEqual(dispatched, [{ type: 'RECOVERY_DELETED', sessionId: 'session-a', workspaceId: 'ws-1' }]);
});
