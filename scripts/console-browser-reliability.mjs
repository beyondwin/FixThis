#!/usr/bin/env node
import assert from 'node:assert/strict';
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';

async function loadPlaywright() {
  return import('playwright');
}

async function withBrowser(fn) {
  const playwright = await loadPlaywright();
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  const browser = await playwright.chromium.launch({ headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1024, height: 768 } });
    await fn({ fixture, context });
  } finally {
    await browser.close();
    await fixture.close();
  }
}

async function waitUntil(predicate, timeoutMs = 8000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error('timed out waiting for condition');
}

async function openConsolePage(context, url) {
  const page = await context.newPage();
  await page.goto(url, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => Boolean(window.FixThisConsoleDebug?.getState), null, { timeout: 8000 });
  return page;
}

async function postJson(page, path, body) {
  return page.evaluate(async ({ path, body }) => {
    const response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'If-Match': '*' },
      body: JSON.stringify(body),
    });
    return {
      status: response.status,
      json: await response.json(),
    };
  }, { path, body });
}

async function createDraftAnnotation(page, comment) {
  await page.waitForFunction(
    () => Boolean(window.FixThisConsoleDebug.getState().preview?.screen),
    null,
    { timeout: 8000 },
  );
  await page.click('#annotateToolButton');
  await page.waitForSelector('#snapshot[data-tool-mode="annotate"]');
  await page.waitForSelector('#snapshotImage');
  await page.waitForFunction(() => {
    const image = document.getElementById('snapshotImage');
    return Boolean(image?.complete && image.naturalWidth >= 0);
  }, null, { timeout: 8000 });
  const imageBox = await page.locator('#snapshotImage').boundingBox();
  assert.ok(imageBox, 'expected snapshot image before creating draft annotation');
  await page.mouse.move(imageBox.x + 40, imageBox.y + 40);
  await page.mouse.down();
  await page.mouse.move(imageBox.x + 130, imageBox.y + 110);
  await page.mouse.up();
  await page.waitForSelector('#annotationCommentInput');
  await page.fill('#annotationCommentInput', comment);
  await page.waitForFunction(
    () => !document.getElementById('sendAgentButton')?.disabled,
    null,
    { timeout: 8000 },
  );
}

async function testTwoTabSseSync() {
  await withBrowser(async ({ fixture, context }) => {
    const second = await openConsolePage(context, fixture.url);
    await openConsolePage(context, fixture.url);
    await waitUntil(() => fixture.eventClientCount() >= 2);

    const item = fixture.seedAnnotation({ itemId: 'item-sse-sync', comment: 'Sync me' });
    fixture.emitSessionUpdated();

    await second.waitForFunction(
      () => window.FixThisConsoleDebug.getState().session?.items?.some((candidate) => candidate.itemId === 'item-sse-sync'),
      null,
      { timeout: 8000 },
    );
    const visible = await second.evaluate(() => window.FixThisConsoleDebug.getState().session.items.map((candidate) => candidate.itemId));
    assert.ok(visible.includes(item.itemId), `receiver tab did not see ${item.itemId}: ${visible.join(', ')}`);
  });
}

async function testLatePreviewIsolation() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    fixture.createSession({ sessionId: 'session-a', title: 'Session A' });
    fixture.createSession({ sessionId: 'session-b', title: 'Session B' });
    await page.evaluate(async () => {
      await fetch('/api/session/open', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: 'session-b' }),
      });
    });
    await page.waitForFunction(
      () => window.FixThisConsoleDebug.getState().session?.sessionId === 'session-b',
      null,
      { timeout: 8000 },
    );
    fixture.emitPreviewReady('session-a', { previewId: 'old-preview' });
    fixture.emitPreviewReady('session-b', { previewId: 'new-preview' });

    await page.waitForFunction(
      () => window.FixThisConsoleDebug.getState().preview?.previewId === 'new-preview',
      null,
      { timeout: 8000 },
    );
    const preview = await page.evaluate(() => window.FixThisConsoleDebug.getState().preview);
    assert.equal(preview.previewId, 'new-preview');
    assert.notEqual(preview.previewId, 'old-preview');
  });
}

async function testSsePreviewPushDoesNotPollPreview() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    await waitUntil(() => fixture.eventClientCount() >= 1);
    const before = fixture.previewRequestCount();
    fixture.emitPreviewReady('session-1', { previewId: 'sse-push-only' });
    await page.waitForFunction(
      () => window.FixThisConsoleDebug.getState().preview?.previewId === 'sse-push-only',
      null,
      { timeout: 8000 },
    );
    await new Promise((resolve) => setTimeout(resolve, 1200));
    assert.equal(
      fixture.previewRequestCount(),
      before,
      'connected EventSource should update preview without fallback /api/preview polling',
    );
  });
}

async function testEventSourceReconnectRecovery() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    await waitUntil(() => fixture.eventClientCount() >= 1);
    fixture.closeEventClients();
    await waitUntil(() => fixture.eventClientCount() >= 1);

    const item = fixture.seedAnnotation({ itemId: 'item-after-reconnect', comment: 'Reconnect sync' });
    fixture.emitSessionUpdated();

    await page.waitForFunction(
      () => window.FixThisConsoleDebug.getState().session?.items?.some((candidate) => candidate.itemId === 'item-after-reconnect'),
      null,
      { timeout: 8000 },
    );
    const visible = await page.evaluate(() => window.FixThisConsoleDebug.getState().session.items.map((candidate) => candidate.itemId));
    assert.ok(visible.includes(item.itemId), `reconnected tab did not see ${item.itemId}: ${visible.join(', ')}`);
  });
}

async function testStalePreviewSaveRequiresConfirmation() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    await createDraftAnnotation(page, 'Do not save without confirmation');
    fixture.rejectNextBatchForFingerprintMismatch({
      frozenFingerprint: 'frozen-fingerprint',
      currentFingerprint: 'current-fingerprint',
    });

    await page.click('#sendAgentButton');
    await page.waitForFunction(
      () => {
        const sheet = document.getElementById('sessionBoundarySheet');
        return sheet?.dataset.boundaryVariant === 'fingerprintMismatch' && sheet.hidden === false;
      },
      null,
      { timeout: 8000 },
    );
    assert.equal(fixture.currentSession().items.length, 0, 'stale save must not persist before confirmation');

    await page.click('#sessionBoundarySheet [data-boundary-action="cancel"]');
    await page.waitForFunction(
      () => document.getElementById('sessionBoundarySheet')?.hidden === true,
      null,
      { timeout: 8000 },
    );
    assert.equal(fixture.currentSession().items.length, 0, 'cancelled stale save must not persist');
  });
}

async function testRepeatedSaveToMcpIdempotency() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    const preview = await page.evaluate(async () => {
      const response = await fetch('/api/preview');
      return response.json();
    });
    const body = {
      sessionId: 'session-1',
      previewId: preview.previewId,
      workspaceId: 'workspace-idempotent',
      screen: preview.screen,
      items: [{
        draftItemId: 'draft-idempotent',
        targetType: 'area',
        bounds: { left: 10, top: 10, right: 80, bottom: 80 },
        comment: 'Save once only',
      }],
    };

    const first = await postJson(page, '/api/items/batch', body);
    const duplicate = await postJson(page, '/api/items/batch', body);

    assert.equal(first.status, 200);
    assert.equal(duplicate.status, 200);
    const saved = fixture.currentSession().items.filter((item) => item.clientDraftItemId === 'draft-idempotent');
    assert.equal(saved.length, 1);
  });
}

async function run() {
  await testTwoTabSseSync();
  await testLatePreviewIsolation();
  await testSsePreviewPushDoesNotPollPreview();
  await testEventSourceReconnectRecovery();
  await testStalePreviewSaveRequiresConfirmation();
  await testRepeatedSaveToMcpIdempotency();
  console.log('PASS console browser reliability proof');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
