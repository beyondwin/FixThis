import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/sse.js'), 'utf8');
const factory = new Function(`${src}; return {
  setConsoleEventsConnected,
  isConsoleEventsConnected,
  wasConsoleEventsRecentlyConnected,
  shouldUsePreviewFallbackPolling,
  shouldUseSessionFallbackPolling,
  recordConsoleEventsOverflow,
  consoleEventsDiagnostics,
};`);

function fresh() {
  return factory();
}

test('initial diagnostics are zeroed and fallback gates are open', () => {
  const m = fresh();
  assert.equal(m.isConsoleEventsConnected(), false);
  assert.equal(m.shouldUsePreviewFallbackPolling(), true);
  assert.equal(m.shouldUseSessionFallbackPolling(), true);
  assert.deepEqual(m.consoleEventsDiagnostics(), {
    connected: false,
    connectCount: 0,
    disconnectCount: 0,
    reconnectCount: 0,
    replayOverflowCount: 0,
    lastConnectedAt: null,
    lastDisconnectedAt: null,
    lastFallbackReason: null,
  });
});

test('connect disconnect reconnect and overflow update diagnostics', () => {
  const m = fresh();

  assert.equal(m.setConsoleEventsConnected(true, { nowMs: 1000 }), true);
  assert.equal(m.isConsoleEventsConnected(), true);
  assert.equal(m.shouldUsePreviewFallbackPolling(), false);
  assert.equal(m.shouldUseSessionFallbackPolling(), false);
  assert.deepEqual(m.consoleEventsDiagnostics(), {
    connected: true,
    connectCount: 1,
    disconnectCount: 0,
    reconnectCount: 0,
    replayOverflowCount: 0,
    lastConnectedAt: 1000,
    lastDisconnectedAt: null,
    lastFallbackReason: null,
  });

  assert.equal(m.setConsoleEventsConnected(false, { reason: 'eventsource_error', nowMs: 1500 }), false);
  assert.equal(m.isConsoleEventsConnected(), false);
  assert.equal(m.shouldUsePreviewFallbackPolling(), true);
  assert.equal(m.shouldUseSessionFallbackPolling(), true);

  assert.equal(m.setConsoleEventsConnected(true, { nowMs: 2500 }), true);
  m.recordConsoleEventsOverflow({ nowMs: 2600 });

  assert.deepEqual(m.consoleEventsDiagnostics(), {
    connected: true,
    connectCount: 2,
    disconnectCount: 1,
    reconnectCount: 1,
    replayOverflowCount: 1,
    lastConnectedAt: 2500,
    lastDisconnectedAt: 1500,
    lastFallbackReason: 'replay_overflow',
  });
});

test('diagnostics snapshots are copies', () => {
  const m = fresh();
  m.setConsoleEventsConnected(true, { nowMs: 100 });
  const snapshot = m.consoleEventsDiagnostics();
  snapshot.connectCount = 99;
  assert.equal(m.consoleEventsDiagnostics().connectCount, 1);
});
