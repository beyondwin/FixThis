import { test } from 'node:test';
import assert from 'node:assert/strict';
import { startFakeBridge } from './fakeBridgeServer.mjs';

test('startFakeBridge serves the console index and closes cleanly', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    const response = await fetch(`${fixture.url}/`);
    assert.equal(response.status, 200);
    const body = await response.text();
    assert.match(body, /<title>FixThis/);
  } finally {
    await fixture.close();
  }
});

test('startFakeBridge records POST request log entries', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    await fetch(`${fixture.url}/api/handoff`, {
      method: 'POST',
      body: JSON.stringify({ ping: 1 }),
      headers: { 'content-type': 'application/json' },
    });
    const log = fixture.getRequestLog();
    const entry = log.find((e) => e.path === '/api/handoff');
    assert.ok(entry, 'expected /api/handoff in request log');
    assert.equal(entry.method, 'POST');
  } finally {
    await fixture.close();
  }
});

test('applyScenario swaps active scenario', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    await fixture.applyScenario('network-outage');
    const response = await fetch(`${fixture.url}/api/handoff`, { method: 'POST' });
    assert.equal(response.status, 503);
  } finally {
    await fixture.close();
  }
});

test('fake bridge serves core console routes', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    for (const path of ['/api/session', '/api/sessions', '/api/connection', '/api/devices', '/api/preview']) {
      const response = await fetch(fixture.url + path);
      assert.equal(response.status, 200, path);
      assert.match(response.headers.get('content-type') || '', /application\/json/);
    }
  } finally {
    await fixture.close();
  }
});

test('fake bridge records handoff request count', async () => {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    const response = await fetch(fixture.url + '/api/agent-handoffs', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sessionId: 'session-1', itemIds: ['item-1'] }),
    });
    assert.equal(response.status, 200);
    assert.equal(fixture.getRequestLog().filter((r) => r.path === '/api/agent-handoffs').length, 1);
  } finally {
    await fixture.close();
  }
});
