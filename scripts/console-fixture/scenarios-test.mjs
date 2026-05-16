import { test } from 'node:test';
import assert from 'node:assert/strict';
import { startFakeBridge } from './fakeBridgeServer.mjs';
import { applyNetworkOutage } from './scenarios/networkOutage.mjs';
import { applySlowHandoff } from './scenarios/slowHandoff.mjs';
import { applyMultiTab, rapidSessionSwitchCancelsOldPreview } from './scenarios/multiTab.mjs';

test('network outage scenario blocks /api/handoff with 503', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    await applyNetworkOutage(fixture);
    const response = await fetch(`${fixture.url}/api/handoff`, { method: 'POST' });
    assert.equal(response.status, 503);
  } finally {
    await fixture.close();
  }
});

test('slow handoff scenario delays /api/handoff response', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    await applySlowHandoff(fixture, { delayMs: 250 });
    const start = Date.now();
    const response = await fetch(`${fixture.url}/api/handoff`, { method: 'POST' });
    const elapsed = Date.now() - start;
    assert.equal(response.status, 200);
    assert.ok(elapsed >= 200, `expected >= 200 ms, got ${elapsed}`);
  } finally {
    await fixture.close();
  }
});

test('multi-tab scenario records request log across two simulated tabs', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    await applyMultiTab(fixture);
    await fetch(`${fixture.url}/api/session/A`);
    await fetch(`${fixture.url}/api/session/B`);
    const log = fixture.getRequestLog();
    const sessionHits = log.filter((entry) => entry.path.startsWith('/api/session/'));
    assert.equal(sessionHits.length, 2);
  } finally {
    await fixture.close();
  }
});

test('first-run unsupported build scenario exposes readiness payload', async () => {
  const fixture = await startFakeBridge({ scenario: 'unsupported-build' });
  try {
    const status = await fetch(`${fixture.url}/api/connection`).then((response) => response.json());
    assert.equal(status.state, 'UNSUPPORTED_BUILD');
    assert.equal(status.readiness.state, 'UNSUPPORTED_BUILD');
    assert.match(status.readiness.nextAction, /debuggable build/);
  } finally {
    await fixture.close();
  }
});

test('rapid session switching fixture can delay stale previews by session', async () => {
  assert.equal(typeof rapidSessionSwitchCancelsOldPreview, 'function');
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    await fixture.createSession({ sessionId: 'session-a', title: 'Session A' });
    await fixture.createSession({ sessionId: 'session-b', title: 'Session B' });
    await fixture.delayPreviewForSession('session-a', 80);

    await fetch(`${fixture.url}/api/session/open`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sessionId: 'session-a' }),
    });
    const delayedStart = Date.now();
    const delayed = await fetch(`${fixture.url}/api/preview`).then((response) => response.json());
    const delayedElapsed = Date.now() - delayedStart;
    assert.equal(delayed.sessionId, 'session-a');
    assert.ok(delayedElapsed >= 60, `expected delayed session-a preview, got ${delayedElapsed} ms`);

    await fetch(`${fixture.url}/api/session/open`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sessionId: 'session-b' }),
    });
    const fastStart = Date.now();
    const fast = await fetch(`${fixture.url}/api/preview`).then((response) => response.json());
    const fastElapsed = Date.now() - fastStart;
    assert.equal(fast.sessionId, 'session-b');
    assert.ok(fastElapsed < 60, `expected session-b preview without stale delay, got ${fastElapsed} ms`);
  } finally {
    await fixture.close();
  }
});
