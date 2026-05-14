import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const fsmSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pollingFsm.js'), 'utf8');
const ucSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pollingUseCases.js'), 'utf8');
const factory = new Function(`${fsmSrc}\n${ucSrc}; return {
  createInitialPollingState,
  reducePolling,
  createPollingUseCases,
  PollingLifecycle,
  MaxConsecutivePollFailures,
};`);
const m = factory();

function makeUseCases({ observer = () => {}, api = {} } = {}) {
  return m.createPollingUseCases({
    onChange: observer,
    api,
  });
}

test('createPollingUseCases exposes getState seeded with initial state', () => {
  const uc = makeUseCases();
  const s = uc.getState();
  assert.equal(s.lifecycle, m.PollingLifecycle.STOPPED);
  assert.equal(s.pendingMutationCount, 0);
});

test('startSessionsPolling dispatches START', () => {
  const uc = makeUseCases();
  uc.startSessionsPolling();
  assert.equal(uc.getState().lifecycle, m.PollingLifecycle.POLLING_ACTIVE);
});

test('stopSessionsPolling dispatches STOP', () => {
  const uc = makeUseCases();
  uc.startSessionsPolling();
  uc.stopSessionsPolling();
  assert.equal(uc.getState().lifecycle, m.PollingLifecycle.STOPPED);
});

test('visibilityHidden / visibilityVisible round-trip preserves lifecycle', () => {
  const uc = makeUseCases();
  uc.startSessionsPolling();
  uc.visibilityHidden();
  assert.equal(uc.getState().lifecycle, m.PollingLifecycle.POLLING_PAUSED);
  uc.visibilityVisible();
  assert.equal(uc.getState().lifecycle, m.PollingLifecycle.POLLING_ACTIVE);
});

test('disconnect transitions to STOPPED from any state', () => {
  const uc = makeUseCases();
  uc.startSessionsPolling();
  uc.visibilityHidden();
  uc.disconnect();
  assert.equal(uc.getState().lifecycle, m.PollingLifecycle.STOPPED);
});

test('withMutationLock bumps pendingMutationCount during fn, drains after', async () => {
  const uc = makeUseCases();
  let observedDuring = null;
  const result = await uc.withMutationLock(async () => {
    observedDuring = uc.getState().pendingMutationCount;
    return 'ok';
  });
  assert.equal(observedDuring, 1);
  assert.equal(uc.getState().pendingMutationCount, 0);
  assert.equal(result, 'ok');
});

test('withMutationLock decrements pendingMutationCount even on error', async () => {
  const uc = makeUseCases();
  await assert.rejects(uc.withMutationLock(async () => { throw new Error('boom'); }), /boom/);
  assert.equal(uc.getState().pendingMutationCount, 0);
});

test('withMutationLock bumps mutationGeneration', async () => {
  const uc = makeUseCases();
  const beforeGen = uc.getState().mutationGeneration;
  await uc.withMutationLock(async () => 'ok');
  assert.equal(uc.getState().mutationGeneration, beforeGen + 1);
});

test('pollSessionsTick on success dispatches TICK_OK with etags from api response', async () => {
  const uc = makeUseCases({
    api: { sessions: async () => ({ sessionsEtag: 'A', sessionEtag: 'B' }) },
  });
  uc.startSessionsPolling();
  await uc.pollSessionsTick();
  const s = uc.getState();
  assert.equal(s.lastSessionsEtag, 'A');
  assert.equal(s.lastSessionEtag, 'B');
  assert.equal(s.consecutiveFailures, 0);
});

test('pollSessionsTick passes prior etags to api.sessions', async () => {
  let received = null;
  const uc = makeUseCases({
    api: {
      sessions: async (args) => {
        received = args;
        return { sessionsEtag: args.sessionsEtag, sessionEtag: args.sessionEtag };
      },
    },
  });
  uc.startSessionsPolling();
  await uc.pollSessionsTick();
  // Seed etags
  const uc2 = makeUseCases({
    api: {
      sessions: async (args) => {
        received = args;
        return { sessionsEtag: 'new-A', sessionEtag: 'new-B' };
      },
    },
  });
  uc2.startSessionsPolling();
  await uc2.pollSessionsTick();
  // Now etags should be 'new-A' and 'new-B'; calling again should pass these in.
  await uc2.pollSessionsTick();
  assert.equal(received.sessionsEtag, 'new-A');
  assert.equal(received.sessionEtag, 'new-B');
});

test('pollSessionsTick failure dispatches TICK_FAILED', async () => {
  const uc = makeUseCases({
    api: { sessions: async () => { throw new Error('net'); } },
  });
  uc.startSessionsPolling();
  await assert.rejects(uc.pollSessionsTick(), /net/);
  assert.equal(uc.getState().consecutiveFailures, 1);
});

test('5 consecutive TICK_FAILED transitions to POLLING_BACKOFF', async () => {
  const uc = makeUseCases({
    api: { sessions: async () => { throw new Error('net'); } },
  });
  uc.startSessionsPolling();
  for (let i = 0; i < m.MaxConsecutivePollFailures; i++) {
    await assert.rejects(uc.pollSessionsTick());
  }
  assert.equal(uc.getState().lifecycle, m.PollingLifecycle.POLLING_BACKOFF);
});

test('backoffTimerFired moves BACKOFF → ACTIVE without resetting counter', async () => {
  const uc = makeUseCases({
    api: { sessions: async () => { throw new Error('net'); } },
  });
  uc.startSessionsPolling();
  for (let i = 0; i < m.MaxConsecutivePollFailures; i++) {
    await assert.rejects(uc.pollSessionsTick());
  }
  assert.equal(uc.getState().consecutiveFailures, m.MaxConsecutivePollFailures);
  uc.backoffTimerFired();
  assert.equal(uc.getState().lifecycle, m.PollingLifecycle.POLLING_ACTIVE);
  assert.equal(uc.getState().consecutiveFailures, m.MaxConsecutivePollFailures);
});

test('setPromptActionInFlight(true/false) toggles promptActionInFlight', () => {
  const uc = makeUseCases();
  uc.setPromptActionInFlight(true);
  assert.equal(uc.getState().promptActionInFlight, true);
  uc.setPromptActionInFlight(false);
  assert.equal(uc.getState().promptActionInFlight, false);
});

test('observer fires on each state change', () => {
  const seen = [];
  const uc = makeUseCases({ observer: (next) => seen.push(next.lifecycle) });
  uc.startSessionsPolling();
  uc.stopSessionsPolling();
  assert.deepEqual(seen, [m.PollingLifecycle.POLLING_ACTIVE, m.PollingLifecycle.STOPPED]);
});

test('concurrent withMutationLock calls stack correctly', async () => {
  const uc = makeUseCases();
  let mid = null;
  const p1 = uc.withMutationLock(async () => {
    await new Promise((r) => setTimeout(r, 5));
    return 1;
  });
  const p2 = uc.withMutationLock(async () => {
    mid = uc.getState().pendingMutationCount;
    return 2;
  });
  await Promise.all([p1, p2]);
  assert.equal(mid, 2, 'second lock observes count of 2');
  assert.equal(uc.getState().pendingMutationCount, 0);
});
