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
  assert.deepEqual(rendered, ['session-a']);
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
