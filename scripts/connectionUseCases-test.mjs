import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const fsmSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/connectionFsm.js'), 'utf8');
const ucSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/connectionUseCases.js'), 'utf8');
const factory = new Function(`${fsmSrc}\n${ucSrc}; return {
  createInitialConnectionState,
  reduceConnection,
  createConnectionUseCases,
};`);
const m = factory();

function makeUseCases(observer = () => {}) {
  return m.createConnectionUseCases({
    initialState: m.createInitialConnectionState(),
    onChange: observer,
  });
}

test('createConnectionUseCases exposes getState seeded with initial state', () => {
  const uc = makeUseCases();
  const state = uc.getState();
  assert.equal(state.current, null);
  assert.equal(state.hasEverConnected, false);
});

test('launchRequested updates state and notifies observer', () => {
  let observed = null;
  const uc = makeUseCases((next) => { observed = next; });
  uc.launchRequested();
  assert.equal(uc.getState().launchInFlight, true);
  assert.equal(observed.launchInFlight, true);
});

test('setStatus dispatches STATUS_RECEIVED and updates current/availability/blockedReason', () => {
  let observed = null;
  const uc = makeUseCases((next) => { observed = next; });
  const status = { state: 'STARTING', availability: { activity: 'A' } };
  uc.setStatus(status, 'unresponsive', { nowMs: 4242 });
  const s = uc.getState();
  assert.equal(s.current, status);
  assert.deepEqual(s.availability, { activity: 'A' });
  assert.equal(s.interactionBlockedReason, 'unresponsive');
  assert.equal(observed.current, status);
});

test('setStatus with READY sets hasEverConnected and lastReadyAt from nowMs', () => {
  const uc = makeUseCases();
  uc.setStatus({ state: 'READY' }, null, { nowMs: 12345 });
  const s = uc.getState();
  assert.equal(s.hasEverConnected, true);
  assert.equal(s.lastReadyAt, 12345);
});

test('setSessionsPollingPaused toggles polling flag through reducer', () => {
  let observed = null;
  const uc = makeUseCases((next) => { observed = next; });
  uc.setSessionsPollingPaused(true);
  assert.equal(uc.getState().sessionsPollingPaused, true);
  assert.equal(observed.sessionsPollingPaused, true);
  uc.setSessionsPollingPaused(false);
  assert.equal(uc.getState().sessionsPollingPaused, false);
});

test('interactionBlocked / interactionUnblocked roundtrip', () => {
  const uc = makeUseCases();
  uc.interactionBlocked('busy');
  assert.equal(uc.getState().interactionBlockedReason, 'busy');
  uc.interactionUnblocked();
  assert.equal(uc.getState().interactionBlockedReason, null);
  assert.equal(uc.getState().previousBlockedReason, 'busy');
});

test('availabilityUpdated writes availability', () => {
  const uc = makeUseCases();
  uc.availabilityUpdated({ activity: 'Z' });
  assert.deepEqual(uc.getState().availability, { activity: 'Z' });
});

test('compat shim contract: onChange receives a fresh object each dispatch', () => {
  const seen = [];
  const uc = makeUseCases((next) => { seen.push(next); });
  uc.launchRequested();
  uc.setStatus({ state: 'STARTING' }, null, { nowMs: 1 });
  assert.equal(seen.length, 2);
  assert.notEqual(seen[0], seen[1]);
  assert.equal(seen[1].current.state, 'STARTING');
});
