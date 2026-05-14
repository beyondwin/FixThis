import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pollingFsm.js'), 'utf8');
const factory = new Function(`${src}; return {
  createInitialPollingState,
  reducePolling,
  PollingLifecycle,
  MaxConsecutivePollFailures,
};`);
const m = factory();

test('initial polling state matches spec shape', () => {
  const s = m.createInitialPollingState();
  assert.equal(s.lifecycle, m.PollingLifecycle.STOPPED);
  assert.equal(s.pausedReturnLifecycle, null);
  assert.equal(s.lastSessionsEtag, null);
  assert.equal(s.lastSessionEtag, null);
  assert.equal(s.pendingMutationCount, 0);
  assert.equal(s.mutationGeneration, 0);
  assert.equal(s.consecutiveFailures, 0);
  assert.equal(s.promptActionInFlight, false);
});

test('PollingLifecycle enum exposes all 4 states', () => {
  assert.equal(m.PollingLifecycle.STOPPED, 'stopped');
  assert.equal(m.PollingLifecycle.POLLING_ACTIVE, 'polling_active');
  assert.equal(m.PollingLifecycle.POLLING_BACKOFF, 'polling_backoff');
  assert.equal(m.PollingLifecycle.POLLING_PAUSED, 'polling_paused');
});

test('MaxConsecutivePollFailures is the spec constant 5', () => {
  assert.equal(m.MaxConsecutivePollFailures, 5);
});

test('reducer is pure: unknown action returns same input', () => {
  const s = m.createInitialPollingState();
  const next = m.reducePolling(s, { type: 'NOPE' });
  assert.equal(next, s);
});

test('reducer returns frozen state', () => {
  const s = m.createInitialPollingState();
  const next = m.reducePolling(s, { type: 'START' });
  assert.equal(Object.isFrozen(next), true);
});

test('START transitions STOPPED → POLLING_ACTIVE and resets consecutiveFailures', () => {
  let s = m.createInitialPollingState();
  s = m.reducePolling(s, { type: 'TICK_FAILED' });
  s = m.reducePolling(s, { type: 'TICK_FAILED' });
  assert.equal(s.consecutiveFailures, 2);
  const next = m.reducePolling(s, { type: 'START' });
  assert.equal(next.lifecycle, m.PollingLifecycle.POLLING_ACTIVE);
  assert.equal(next.consecutiveFailures, 0);
});

test('STOP transitions to STOPPED from POLLING_ACTIVE', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
  const next = m.reducePolling(s, { type: 'STOP' });
  assert.equal(next.lifecycle, m.PollingLifecycle.STOPPED);
});

test('TICK_OK resets consecutiveFailures to 0 and writes etags', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
  s = m.reducePolling(s, { type: 'TICK_FAILED' });
  s = m.reducePolling(s, { type: 'TICK_FAILED' });
  const next = m.reducePolling(s, {
    type: 'TICK_OK',
    sessionsEtag: 'etag-1',
    sessionEtag: 'etag-2',
  });
  assert.equal(next.consecutiveFailures, 0);
  assert.equal(next.lastSessionsEtag, 'etag-1');
  assert.equal(next.lastSessionEtag, 'etag-2');
});

test('TICK_FAILED increments consecutiveFailures', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
  const next = m.reducePolling(s, { type: 'TICK_FAILED' });
  assert.equal(next.consecutiveFailures, 1);
});

test('TICK_FAILED at threshold transitions POLLING_ACTIVE → POLLING_BACKOFF', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
  for (let i = 0; i < m.MaxConsecutivePollFailures - 1; i++) {
    s = m.reducePolling(s, { type: 'TICK_FAILED' });
  }
  assert.equal(s.lifecycle, m.PollingLifecycle.POLLING_ACTIVE);
  const next = m.reducePolling(s, { type: 'TICK_FAILED' });
  assert.equal(next.lifecycle, m.PollingLifecycle.POLLING_BACKOFF);
  assert.equal(next.consecutiveFailures, m.MaxConsecutivePollFailures);
});

test('BACKOFF_TIMER_FIRED transitions POLLING_BACKOFF → POLLING_ACTIVE (counter unchanged)', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
  for (let i = 0; i < m.MaxConsecutivePollFailures; i++) {
    s = m.reducePolling(s, { type: 'TICK_FAILED' });
  }
  assert.equal(s.lifecycle, m.PollingLifecycle.POLLING_BACKOFF);
  const next = m.reducePolling(s, { type: 'BACKOFF_TIMER_FIRED' });
  assert.equal(next.lifecycle, m.PollingLifecycle.POLLING_ACTIVE);
  assert.equal(next.consecutiveFailures, m.MaxConsecutivePollFailures, 'BACKOFF_TIMER_FIRED must not reset counter');
});

test('VISIBILITY_HIDDEN from POLLING_ACTIVE transitions to POLLING_PAUSED and preserves return state', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
  const next = m.reducePolling(s, { type: 'VISIBILITY_HIDDEN' });
  assert.equal(next.lifecycle, m.PollingLifecycle.POLLING_PAUSED);
  assert.equal(next.pausedReturnLifecycle, m.PollingLifecycle.POLLING_ACTIVE);
});

test('VISIBILITY_HIDDEN from POLLING_BACKOFF preserves pausedReturnLifecycle = BACKOFF', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
  for (let i = 0; i < m.MaxConsecutivePollFailures; i++) {
    s = m.reducePolling(s, { type: 'TICK_FAILED' });
  }
  assert.equal(s.lifecycle, m.PollingLifecycle.POLLING_BACKOFF);
  const next = m.reducePolling(s, { type: 'VISIBILITY_HIDDEN' });
  assert.equal(next.lifecycle, m.PollingLifecycle.POLLING_PAUSED);
  assert.equal(next.pausedReturnLifecycle, m.PollingLifecycle.POLLING_BACKOFF);
});

test('VISIBILITY_VISIBLE restores the stored pausedReturnLifecycle', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
  s = m.reducePolling(s, { type: 'VISIBILITY_HIDDEN' });
  const next = m.reducePolling(s, { type: 'VISIBILITY_VISIBLE' });
  assert.equal(next.lifecycle, m.PollingLifecycle.POLLING_ACTIVE);
  assert.equal(next.pausedReturnLifecycle, null);
});

test('VISIBILITY_VISIBLE from BACKOFF-pause restores BACKOFF', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
  for (let i = 0; i < m.MaxConsecutivePollFailures; i++) {
    s = m.reducePolling(s, { type: 'TICK_FAILED' });
  }
  s = m.reducePolling(s, { type: 'VISIBILITY_HIDDEN' });
  const next = m.reducePolling(s, { type: 'VISIBILITY_VISIBLE' });
  assert.equal(next.lifecycle, m.PollingLifecycle.POLLING_BACKOFF);
});

test('MUTATION_START increments pendingMutationCount and mutationGeneration', () => {
  const s = m.createInitialPollingState();
  const next = m.reducePolling(s, { type: 'MUTATION_START' });
  assert.equal(next.pendingMutationCount, 1);
  assert.equal(next.mutationGeneration, 1);
});

test('MUTATION_END decrements pendingMutationCount; clamps at 0', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'MUTATION_START' });
  s = m.reducePolling(s, { type: 'MUTATION_END' });
  assert.equal(s.pendingMutationCount, 0);
  // double END clamps at 0
  const next = m.reducePolling(s, { type: 'MUTATION_END' });
  assert.equal(next.pendingMutationCount, 0);
});

test('MUTATION_GENERATION_BUMP bumps mutationGeneration only', () => {
  const s = m.createInitialPollingState();
  const next = m.reducePolling(s, { type: 'MUTATION_GENERATION_BUMP' });
  assert.equal(next.mutationGeneration, 1);
  assert.equal(next.pendingMutationCount, 0);
});

test('MUTATION_END does NOT decrement mutationGeneration (monotonic)', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'MUTATION_START' });
  assert.equal(s.mutationGeneration, 1);
  s = m.reducePolling(s, { type: 'MUTATION_END' });
  assert.equal(s.mutationGeneration, 1, 'mutationGeneration is monotonic');
});

test('PROMPT_ACTION_START sets promptActionInFlight true; END clears', () => {
  let s = m.reducePolling(m.createInitialPollingState(), { type: 'PROMPT_ACTION_START' });
  assert.equal(s.promptActionInFlight, true);
  s = m.reducePolling(s, { type: 'PROMPT_ACTION_END' });
  assert.equal(s.promptActionInFlight, false);
});

test('DISCONNECT_REQUESTED moves to STOPPED from any state', () => {
  for (const seedAction of [{ type: 'START' }, { type: 'VISIBILITY_HIDDEN' }, { type: 'TICK_FAILED' }]) {
    let s = m.reducePolling(m.createInitialPollingState(), { type: 'START' });
    s = m.reducePolling(s, seedAction);
    const next = m.reducePolling(s, { type: 'DISCONNECT_REQUESTED' });
    assert.equal(next.lifecycle, m.PollingLifecycle.STOPPED);
  }
});
