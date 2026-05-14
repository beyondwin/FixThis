import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/connectionFsm.js'), 'utf8');
const factory = new Function(`${src}; return {
  createInitialConnectionState,
  reduceConnection,
  ConnectionLifecycle,
  MaxHeartbeatFailures,
};`);
const m = factory();

test('initial connection state matches legacy state.connection shape', () => {
  const s = m.createInitialConnectionState();
  assert.equal(s.current, null);
  assert.equal(s.hasEverConnected, false);
  assert.equal(s.lastReadyAt, null);
  assert.equal(s.launchInFlight, false);
  assert.equal(s.availability, null);
  assert.equal(s.interactionBlockedReason, null);
  assert.equal(s.previousBlockedReason, null);
  assert.equal(s.sessionsPollingPaused, false);
});

test('reducer is pure: returns the same input for unknown action', () => {
  const s = m.createInitialConnectionState();
  const next = m.reduceConnection(s, { type: 'NOPE' });
  assert.equal(next, s);
});

test('LAUNCH_REQUESTED sets launchInFlight true', () => {
  const s = m.createInitialConnectionState();
  const next = m.reduceConnection(s, { type: 'LAUNCH_REQUESTED' });
  assert.equal(next.launchInFlight, true);
  assert.notEqual(next, s);
});

test('LAUNCH_SUCCEEDED clears launchInFlight', () => {
  const s = m.reduceConnection(m.createInitialConnectionState(), { type: 'LAUNCH_REQUESTED' });
  const next = m.reduceConnection(s, { type: 'LAUNCH_SUCCEEDED' });
  assert.equal(next.launchInFlight, false);
});

test('LAUNCH_FAILED clears launchInFlight', () => {
  const s = m.reduceConnection(m.createInitialConnectionState(), { type: 'LAUNCH_REQUESTED' });
  const next = m.reduceConnection(s, { type: 'LAUNCH_FAILED' });
  assert.equal(next.launchInFlight, false);
});

test('STATUS_RECEIVED writes current, availability, blocked reasons', () => {
  const s = m.createInitialConnectionState();
  const status = { state: 'STARTING', availability: { activity: 'A' } };
  const next = m.reduceConnection(s, {
    type: 'STATUS_RECEIVED',
    status,
    nowMs: 5000,
    blockedReason: 'unresponsive',
  });
  assert.equal(next.current, status);
  assert.deepEqual(next.availability, { activity: 'A' });
  assert.equal(next.interactionBlockedReason, 'unresponsive');
  assert.equal(next.previousBlockedReason, null);
  assert.equal(next.hasEverConnected, false);
  assert.equal(next.lastReadyAt, null);
});

test('STATUS_RECEIVED rolls previousBlockedReason from prior interactionBlockedReason', () => {
  let s = m.createInitialConnectionState();
  s = m.reduceConnection(s, {
    type: 'STATUS_RECEIVED',
    status: { state: 'STARTING' },
    nowMs: 1000,
    blockedReason: 'unresponsive',
  });
  s = m.reduceConnection(s, {
    type: 'STATUS_RECEIVED',
    status: { state: 'STARTING' },
    nowMs: 2000,
    blockedReason: null,
  });
  assert.equal(s.previousBlockedReason, 'unresponsive');
  assert.equal(s.interactionBlockedReason, null);
});

test('STATUS_RECEIVED with READY state marks hasEverConnected and lastReadyAt', () => {
  const s = m.createInitialConnectionState();
  const next = m.reduceConnection(s, {
    type: 'STATUS_RECEIVED',
    status: { state: 'READY', availability: null },
    nowMs: 9000,
    blockedReason: null,
  });
  assert.equal(next.hasEverConnected, true);
  assert.equal(next.lastReadyAt, 9000);
});

test('STATUS_RECEIVED with lowercase ready state also marks ready', () => {
  const s = m.createInitialConnectionState();
  const next = m.reduceConnection(s, {
    type: 'STATUS_RECEIVED',
    status: { state: 'ready' },
    nowMs: 7000,
    blockedReason: null,
  });
  assert.equal(next.hasEverConnected, true);
  assert.equal(next.lastReadyAt, 7000);
});

test('STATUS_RECEIVED with null status nulls out availability and current', () => {
  let s = m.createInitialConnectionState();
  s = m.reduceConnection(s, {
    type: 'STATUS_RECEIVED',
    status: { state: 'READY', availability: { activity: 'X' } },
    nowMs: 1000,
    blockedReason: null,
  });
  const next = m.reduceConnection(s, {
    type: 'STATUS_RECEIVED',
    status: null,
    nowMs: 2000,
    blockedReason: null,
  });
  assert.equal(next.current, null);
  assert.equal(next.availability, null);
  // hasEverConnected persists across non-ready statuses
  assert.equal(next.hasEverConnected, true);
});

test('AVAILABILITY_UPDATED writes availability without changing current', () => {
  const s = m.createInitialConnectionState();
  const next = m.reduceConnection(s, {
    type: 'AVAILABILITY_UPDATED',
    availability: { activity: 'B' },
  });
  assert.deepEqual(next.availability, { activity: 'B' });
  assert.equal(next.current, null);
});

test('INTERACTION_BLOCKED writes reason; UNBLOCKED clears with previous capture', () => {
  let s = m.createInitialConnectionState();
  s = m.reduceConnection(s, { type: 'INTERACTION_BLOCKED', reason: 'busy' });
  assert.equal(s.interactionBlockedReason, 'busy');
  s = m.reduceConnection(s, { type: 'INTERACTION_UNBLOCKED' });
  assert.equal(s.interactionBlockedReason, null);
  assert.equal(s.previousBlockedReason, 'busy');
});

test('POLLING_PAUSED_CHANGED toggles sessionsPollingPaused', () => {
  const s = m.createInitialConnectionState();
  const paused = m.reduceConnection(s, { type: 'POLLING_PAUSED_CHANGED', paused: true });
  assert.equal(paused.sessionsPollingPaused, true);
  const resumed = m.reduceConnection(paused, { type: 'POLLING_PAUSED_CHANGED', paused: false });
  assert.equal(resumed.sessionsPollingPaused, false);
});

test('DISCONNECT_REQUESTED clears current and availability but preserves hasEverConnected', () => {
  let s = m.createInitialConnectionState();
  s = m.reduceConnection(s, {
    type: 'STATUS_RECEIVED',
    status: { state: 'READY' },
    nowMs: 100,
    blockedReason: null,
  });
  const next = m.reduceConnection(s, { type: 'DISCONNECT_REQUESTED' });
  assert.equal(next.current, null);
  assert.equal(next.availability, null);
  assert.equal(next.hasEverConnected, true);
});

test('reducer returns frozen state', () => {
  const s = m.createInitialConnectionState();
  const next = m.reduceConnection(s, { type: 'LAUNCH_REQUESTED' });
  assert.equal(Object.isFrozen(next), true);
});

test('MaxHeartbeatFailures is the spec constant 3', () => {
  assert.equal(m.MaxHeartbeatFailures, 3);
});

test('HEARTBEAT_FAILED tracks lastHeartbeatError', () => {
  const s = m.createInitialConnectionState();
  const next = m.reduceConnection(s, { type: 'HEARTBEAT_FAILED', error: 'oops' });
  assert.equal(next.lastHeartbeatError, 'oops');
});

test('HEARTBEAT_OK clears lastHeartbeatError', () => {
  let s = m.createInitialConnectionState();
  s = m.reduceConnection(s, { type: 'HEARTBEAT_FAILED', error: 'oops' });
  s = m.reduceConnection(s, { type: 'HEARTBEAT_OK' });
  assert.equal(s.lastHeartbeatError, null);
});
