#!/usr/bin/env node
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import http from 'node:http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const indexHtml = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/index.html'), 'utf8');
const stylesCss = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');
const appJs = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/app.js'), 'utf8');
const pageHtml = indexHtml
  .replace('<!-- FIXTHIS_STYLES -->', `<style>${stylesCss}</style>`)
  .replace('<!-- FIXTHIS_SCRIPT -->', `<script>${appJs}</script>`);

const now = 1_779_000_000_000;
const fake = {
  selectedSerial: null,
  connectionState: 'WELCOME',
  previewId: 1,
  openedSessionId: 'session-1',
  navigationCalls: 0,
  handoffPrompts: [],
  devices: [
    { serial: 'device-1', model: 'Pixel Smoke', state: 'device' },
    { serial: 'device-2', model: 'Fold Offline', state: 'offline' },
  ],
  availability: {
    screenInteractive: true,
    keyguardLocked: false,
    appForeground: true,
    pictureInPicture: false,
    rootsCount: 1,
    activity: 'MainActivity',
  },
  sessions: new Map(),
};

fake.sessions.set('session-1', makeSession('session-1', 0, []));
fake.sessions.set('session-2', makeSession('session-2', -10_000, [
  makePersistedItem('item-older', 'Earlier screen annotation', 'screen-session-2', { left: 120, top: 260, right: 320, bottom: 360 }),
  makePersistedItem('item-old', 'Existing history annotation', 'screen-session-2', { left: 40, top: 40, right: 160, bottom: 120 }),
]));

function makeSession(sessionId, timeOffset, items) {
  return {
    sessionId,
    packageName: 'io.beyondwin.fixthis.sample',
    status: 'open',
    createdAtEpochMillis: now + timeOffset,
    updatedAtEpochMillis: now + timeOffset,
    items,
    screens: [...new Set(items.map(item => item.screenId))].map(makeScreen),
    handoffBatches: [],
  };
}

function makePersistedItem(itemId, comment, screenId, bounds = { left: 40, top: 40, right: 160, bottom: 120 }) {
  return {
    itemId,
    sequenceNumber: 1,
    screenId,
    delivery: 'draft',
    comment,
    severity: 'med',
    status: 'open',
    target: {
      type: 'visual_area',
      boundsInWindow: bounds,
    },
    sourceCandidates: [],
  };
}

function currentSession() {
  return fake.sessions.get(fake.openedSessionId);
}

function makeScreen(screenId = 'screen-live') {
  return {
    screenId,
    capturedAtEpochMillis: now,
    screenshot: { desktopFullPath: '/tmp/fixthis-smoke.png' },
    roots: [
      {
        treeKind: 'MERGED',
        mergedNodes: [
          {
            uid: 'node-button',
            role: 'button',
            text: ['Checkout'],
            contentDescription: [],
            testTag: 'checkoutButton',
            boundsInWindow: { left: 70, top: 110, right: 330, bottom: 190 },
          },
        ],
        unmergedNodes: [],
      },
    ],
  };
}

function sessionSummary(session) {
  const unresolved = session.items.filter(item => item.delivery !== 'sent' && item.status !== 'resolved').length;
  return {
    sessionId: session.sessionId,
    status: session.status,
    createdAtEpochMillis: session.createdAtEpochMillis,
    updatedAtEpochMillis: session.updatedAtEpochMillis,
    itemsCount: session.items.length,
    unresolvedItemsCount: unresolved,
    sentItemsCount: session.items.filter(item => item.delivery === 'sent').length,
    sentBatchesCount: session.handoffBatches.length,
  };
}

function toConnectionDevice(device) {
  return {
    serial: device.serial,
    state: device.state,
    label: device.model || device.deviceName || device.product || device.serial,
    selected: device.serial === fake.selectedSerial,
  };
}

function connectionPayload() {
  const selectedDevice = fake.devices.find(device => device.serial === fake.selectedSerial) || null;
  return {
    state: fake.connectionState,
    headline: fake.connectionState === 'READY' ? 'Connected' : 'Connect to your app',
    message: fake.connectionState === 'READY' ? 'FixThis is connected.' : "We'll find your phone and open the app for you.",
    primaryAction: fake.connectionState === 'READY' ? 'CAPTURE' : 'OPEN_APP',
    selectedDevice: selectedDevice ? toConnectionDevice(selectedDevice) : null,
    devices: fake.devices.map(toConnectionDevice),
    packageName: 'io.beyondwin.fixthis.sample',
    availability: fake.connectionState === 'READY' ? { ...fake.availability } : null,
    details: {
      bridgeState: fake.connectionState === 'READY' ? 'ready' : 'paused',
      rawError: fake.connectionState === 'OPEN_APP' ? 'Bridge closed before sending a response' : null,
    },
  };
}

function devicePayload() {
  return {
    selectedSerial: fake.selectedSerial,
    devices: fake.devices.map(device => ({
      ...device,
      selected: device.serial === fake.selectedSerial,
    })),
  };
}

function isObject(value) {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value));
}

const allowedNavigationRequestKeys = new Set(['action', 'x', 'y', 'direction', 'distance', 'captureAfter']);

function isValidSelectModeTapNavigation(body) {
  return Boolean(
    isObject(body) &&
      Object.keys(body).every(key => allowedNavigationRequestKeys.has(key)) &&
      body.action === 'tap' &&
      typeof body.x === 'number' &&
      Number.isFinite(body.x) &&
      typeof body.y === 'number' &&
      Number.isFinite(body.y)
  );
}

function isValidBounds(bounds) {
  return isObject(bounds) &&
    ['left', 'top', 'right', 'bottom'].every(key => typeof bounds[key] === 'number' && Number.isFinite(bounds[key]));
}

function validateSaveSnapshotRequest(body) {
  if (!isObject(body)) return 'Save snapshot request body must be a JSON object';
  if (typeof body.previewId !== 'string' || !body.previewId.trim()) return 'Save snapshot request previewId must be a nonblank string';
  if (!Array.isArray(body.items) || body.items.length === 0) return 'Save snapshot request items must be a non-empty array';
  for (const [index, item] of body.items.entries()) {
    if (!isObject(item)) return `Save snapshot item ${index + 1} must be a JSON object`;
    if (item.targetType !== 'node' && item.targetType !== 'area') return `Save snapshot item ${index + 1} has unsupported targetType`;
    if (!isValidBounds(item.bounds)) return `Save snapshot item ${index + 1} must include finite numeric bounds`;
    if (typeof item.comment !== 'string') return `Save snapshot item ${index + 1} comment must be a string`;
    if (item.targetType === 'node' && (typeof item.nodeUid !== 'string' || !item.nodeUid.trim())) {
      return `Save snapshot item ${index + 1} nodeUid must be a nonblank string`;
    }
  }
  return null;
}

function jsonResponse(response, value, status = 200) {
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  });
  response.end(JSON.stringify(value));
}

function textResponse(response, value, status = 200, contentType = 'text/plain; charset=utf-8') {
  response.writeHead(status, {
    'content-type': contentType,
    'cache-control': 'no-store',
  });
  response.end(value);
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function readJsonBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8');
  return raw ? JSON.parse(raw) : {};
}

function screenshotSvg() {
  return [
    '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="800" viewBox="0 0 400 800">',
    '<rect width="400" height="800" rx="28" fill="#f7fafc"/>',
    '<rect x="32" y="72" width="336" height="80" rx="14" fill="#dbeafe"/>',
    '<rect x="70" y="110" width="260" height="80" rx="16" fill="#2563eb"/>',
    '<text x="200" y="160" text-anchor="middle" font-family="Arial" font-size="28" fill="white">Checkout</text>',
    '<rect x="48" y="240" width="304" height="380" rx="18" fill="#ffffff" stroke="#cbd5e1"/>',
    '</svg>',
  ].join('');
}

async function handleApi(request, response, url) {
  if (request.method === 'GET' && url.pathname === '/api/session') return jsonResponse(response, currentSession());
  if (request.method === 'GET' && url.pathname === '/api/server-version') {
    return jsonResponse(response, {
      serverBuildEpochMs: 1_778_639_340_000,
      serverGitSha: 'f51f1a7',
    });
  }
  if (request.method === 'GET' && url.pathname === '/api/sessions') {
    return jsonResponse(response, {
      sessions: Array.from(fake.sessions.values())
        .map(sessionSummary)
        .sort((left, right) =>
          (right.updatedAtEpochMillis || 0) - (left.updatedAtEpochMillis || 0) ||
          String(left.sessionId || '').localeCompare(String(right.sessionId || ''))
        ),
    });
  }
  if (request.method === 'GET' && url.pathname === '/api/devices') return jsonResponse(response, devicePayload());
  if (request.method === 'GET' && url.pathname === '/api/connection') return jsonResponse(response, connectionPayload());
  if (request.method === 'POST' && url.pathname === '/api/device/select') {
    const body = await readJsonBody(request);
    fake.selectedSerial = body.serial;
    fake.connectionState = 'READY';
    return jsonResponse(response, devicePayload());
  }
  if (request.method === 'POST' && url.pathname === '/api/device/disconnect') {
    fake.selectedSerial = null;
    fake.connectionState = 'WELCOME';
    return jsonResponse(response, devicePayload());
  }
  if (request.method === 'POST' && url.pathname === '/api/app/launch') {
    fake.connectionState = 'READY';
    return jsonResponse(response, connectionPayload());
  }
  if (request.method === 'GET' && url.pathname === '/api/preview') {
    const previewId = 'preview-' + fake.previewId++;
    return jsonResponse(response, {
      previewId,
      screen: makeScreen('screen-' + previewId),
    });
  }
  if (request.method === 'POST' && url.pathname === '/api/navigation') {
    let body;
    try {
      body = await readJsonBody(request);
    } catch (_) {
      return jsonResponse(response, { error: 'Invalid JSON request body' }, 400);
    }
    if (!isValidSelectModeTapNavigation(body)) {
      return jsonResponse(response, { error: 'Invalid navigation request' }, 400);
    }
    fake.navigationCalls++;
    return jsonResponse(response, { ok: true });
  }
  if (request.method === 'POST' && url.pathname === '/api/items/batch') {
    let body;
    try {
      body = await readJsonBody(request);
    } catch (_) {
      return jsonResponse(response, { error: 'Invalid JSON request body' }, 400);
    }
    const validationError = validateSaveSnapshotRequest(body);
    if (validationError) {
      return jsonResponse(response, { error: validationError }, 400);
    }
    const session = currentSession();
    const screen = body.screen || makeScreen('screen-batch');
    session.screens = [screen];
    const items = (body.items || []).map((item, index) => ({
      itemId: 'item-' + (session.items.length + index + 1),
      sequenceNumber: session.items.length + index + 1,
      screenId: screen.screenId,
      delivery: 'draft',
      comment: item.comment,
      severity: item.severity || 'med',
      status: item.status || 'open',
      label: item.label || null,
      targetType: item.targetType,
      selectedNode: item.nodeUid ? screen.roots[0].mergedNodes.find(node => node.uid === item.nodeUid) : null,
      target: {
        type: item.targetType === 'node' ? 'semantics_node' : 'visual_area',
        boundsInWindow: item.bounds,
      },
      sourceCandidates: [],
    }));
    session.items = session.items.concat(items);
    session.updatedAtEpochMillis = now + 20_000;
    return jsonResponse(response, session);
  }
  if (request.method === 'PUT' && url.pathname.startsWith('/api/items/')) {
    const itemId = decodeURIComponent(url.pathname.slice('/api/items/'.length));
    const body = await readJsonBody(request);
    const session = fake.sessions.get(body.sessionId) || currentSession();
    await delay(100);
    session.items = session.items.map(item => item.itemId === itemId
      ? {
          ...item,
          label: body.label ?? item.label,
          severity: body.severity ?? item.severity,
          comment: body.comment ?? item.comment,
          status: body.status ?? item.status,
          updatedAtEpochMillis: now + 25_000,
        }
      : item);
    session.updatedAtEpochMillis = now + 25_000;
    return jsonResponse(response, session);
  }
  if (request.method === 'DELETE' && url.pathname.startsWith('/api/items/')) {
    const itemId = decodeURIComponent(url.pathname.slice('/api/items/'.length));
    const session = currentSession();
    session.items = session.items.filter(item => item.itemId !== itemId);
    session.updatedAtEpochMillis = now + 25_000;
    return jsonResponse(response, session);
  }
  if (request.method === 'POST' && url.pathname === '/api/agent-handoffs') {
    const body = await readJsonBody(request);
    const session = currentSession();
    const ids = new Set(Array.isArray(body.itemIds) ? body.itemIds : []);
    const selectedItems = session.items.filter(item => ids.has(item.itemId));
    const prompt = body.prompt || selectedItems.map(item => '- ' + item.comment).join('\n');
    fake.handoffPrompts.push(prompt);
    const batchId = 'batch-' + (session.handoffBatches.length + 1);
    session.items = session.items.map(item => ids.has(item.itemId) ? { ...item, delivery: 'sent', handoffBatchId: batchId } : item);
    session.handoffBatches = [{
      batchId,
      sequenceNumber: session.handoffBatches.length + 1,
      itemIds: Array.from(ids),
      createdAtEpochMillis: now + 30_000,
    }];
    session.updatedAtEpochMillis = now + 30_000;
    return jsonResponse(response, { session });
  }
  if (request.method === 'POST' && url.pathname.match(/^\/api\/sessions\/[^/]+\/handoff-preview$/)) {
    const body = await readJsonBody(request);
    const sessionId = decodeURIComponent(url.pathname.split('/')[3]);
    const session = fake.sessions.get(sessionId) || currentSession();
    const ids = new Set(Array.isArray(body.itemIds) ? body.itemIds : []);
    const selectedItems = session.items.filter(item => ids.has(item.itemId));
    const markdown = selectedItems.map(item => '- ' + item.comment).join('\n') || '- No annotations selected';
    return textResponse(response, markdown, 200, 'text/markdown; charset=utf-8');
  }
  if (request.method === 'POST' && url.pathname.match(/^\/api\/sessions\/[^/]+\/items\/mark-handed-off$/)) {
    const body = await readJsonBody(request);
    const sessionId = decodeURIComponent(url.pathname.split('/')[3]);
    const session = fake.sessions.get(sessionId) || currentSession();
    const ids = new Set(Array.isArray(body.itemIds) ? body.itemIds : []);
    session.items = session.items.map(item => ids.has(item.itemId)
      ? {
          ...item,
          delivery: 'sent',
          lastHandedOffAtEpochMillis: now + 31_000,
          updatedAtEpochMillis: now + 31_000,
        }
      : item);
    session.updatedAtEpochMillis = now + 31_000;
    return jsonResponse(response, session);
  }
  if (request.method === 'POST' && url.pathname === '/api/session/open') {
    const body = await readJsonBody(request);
    if (body.newSession) {
      const id = 'session-' + (fake.sessions.size + 1);
      fake.sessions.set(id, makeSession(id, 40_000, []));
      fake.openedSessionId = id;
    } else if (body.sessionId && fake.sessions.has(body.sessionId)) {
      fake.openedSessionId = body.sessionId;
    }
    return jsonResponse(response, currentSession());
  }
  if (request.method === 'DELETE' && url.pathname === '/api/items/draft') {
    currentSession().items = [];
    return jsonResponse(response, currentSession());
  }
  if (request.method === 'POST' && url.pathname === '/api/session/close') return jsonResponse(response, { ok: true });
  return textResponse(response, 'Not found', 404);
}

function createServer() {
  return http.createServer(async (request, response) => {
    try {
      const url = new URL(request.url || '/', 'http://127.0.0.1');
      if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
        return textResponse(response, pageHtml, 200, 'text/html; charset=utf-8');
      }
      if (request.method === 'GET' && url.pathname.endsWith('/screenshot/full')) {
        return textResponse(response, screenshotSvg(), 200, 'image/svg+xml; charset=utf-8');
      }
      if (url.pathname.startsWith('/api/')) return await handleApi(request, response, url);
      return textResponse(response, 'Not found', 404);
    } catch (error) {
      return textResponse(response, error?.stack || String(error), 500);
    }
  });
}

async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch (directImportError) {
    for (const pathEntry of (process.env.PATH || '').split(':')) {
      if (!pathEntry.endsWith('/node_modules/.bin')) continue;
      const candidate = resolve(pathEntry, '..', 'playwright/index.mjs');
      try {
        return await import(pathToFileURL(candidate).href);
      } catch (_) {
        // Keep scanning PATH entries created by npm exec/npx --package.
      }
    }
    throw directImportError;
  }
}

function browserBlocker(error) {
  const message = String(error?.message || error);
  if (message.includes('Executable doesn') || message.includes('playwright install')) {
    return 'BROWSER_BINARIES_UNAVAILABLE';
  }
  if (message.includes('Cannot find package') || message.includes('Cannot find module')) {
    return 'PLAYWRIGHT_PACKAGE_UNAVAILABLE';
  }
  return 'BROWSER_SMOKE_FAILED';
}

async function readyDiagnostics(page) {
  return await page.evaluate(() => {
    const card = document.getElementById('connectionCard');
    const image = document.getElementById('snapshotImage');
    return {
      connectionState: card?.dataset.connectionState || null,
      connectionText: card?.textContent?.replace(/\s+/g, ' ').trim() || null,
      imagePresent: Boolean(image),
      imageComplete: Boolean(image?.complete),
      naturalWidth: image?.naturalWidth || 0,
      naturalHeight: image?.naturalHeight || 0,
      deviceName: document.getElementById('deviceName')?.textContent || null,
      error: document.getElementById('error')?.textContent || null,
    };
  });
}

async function waitForReady(page) {
  try {
    await page.waitForFunction(() => {
      const card = document.getElementById('connectionCard');
      const image = document.getElementById('snapshotImage');
      return card?.dataset.connectionState === 'ready' &&
        image &&
        image.complete &&
        image.naturalWidth > 0 &&
        image.naturalHeight > 0;
    }, { timeout: 15_000 });
  } catch (error) {
    const diagnostics = await readyDiagnostics(page);
    throw new Error('Console did not reach ready preview state: ' + JSON.stringify(diagnostics));
  }
}

async function pendingDiagnostics(page) {
  return await page.evaluate(() => ({
    pendingRows: document.querySelectorAll('.pending-item-row').length,
    pins: document.querySelectorAll('.selection-box.annotation-pin').length,
    toolMode: document.getElementById('snapshot')?.dataset.toolMode || null,
    hint: document.getElementById('annotateHint')?.textContent || null,
    summary: document.getElementById('selectionSummary')?.textContent || null,
    error: document.getElementById('error')?.textContent || null,
    blocked: !document.getElementById('canvasBlockedOverlay')?.hidden,
  }));
}

async function waitForPendingPins(page, expected, context) {
  try {
    await page.waitForFunction(
      count => document.querySelectorAll('.selection-box.annotation-pin').length === count,
      expected,
      { timeout: 10_000 },
    );
  } catch (error) {
    const diagnostics = await pendingDiagnostics(page);
    throw new Error(context + ': ' + JSON.stringify(diagnostics));
  }
}

async function waitForSnapshotImageReady(page, context) {
  try {
    await page.waitForFunction(() => {
      const image = document.getElementById('snapshotImage');
      return image &&
        image.complete &&
        image.naturalWidth > 0 &&
        image.naturalHeight > 0 &&
        document.getElementById('annotateToolButton')?.disabled === false;
    }, { timeout: 10_000 });
  } catch (error) {
    const diagnostics = await readyDiagnostics(page);
    throw new Error(context + ': ' + JSON.stringify(diagnostics));
  }
}

async function runSmoke(baseUrl) {
  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: {
          writeText: async text => {
            window.__fixthisCopiedText = String(text);
          },
        },
      });
    });
    page.on('dialog', dialog => dialog.dismiss());
    const consoleErrors = [];
    page.on('pageerror', error => consoleErrors.push(error.message));
    page.on('console', message => {
      if (message.type() === 'error') consoleErrors.push(message.text());
    });

    await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#connectionCard[data-connection-state="welcome"]');
    await page.waitForFunction(() => document.querySelectorAll('#sessions .session-row').length >= 2);
    assert.deepEqual(
      await page.$$eval('#sessions .session-row', rows => rows.slice(0, 2).map(row => row.dataset.sessionId)),
      ['session-2', 'session-1'],
      'History sessions should stay in creation order, not API updated order',
    );

    await page.locator('#sessions .session-row[data-session-id="session-2"]').click();
    await page.waitForFunction(() => document.querySelector('#sessions .session-row.is-active')?.dataset.sessionId === 'session-2');
    await page.waitForFunction(() => document.querySelectorAll('.saved-item-row').length === 2);
    await page.waitForFunction(() =>
      Array.from(document.querySelectorAll('#selectionOverlay .selection-label'))
        .map(label => label.textContent)
        .filter(Boolean)
        .join(',') === '1,2'
    );
    await page.locator('.saved-item-row').nth(1).click();
    await page.waitForFunction(() => document.activeElement?.id === 'annotationCommentInput');
    const editedHistoryComment = 'Edited history annotation';
    await page.fill('#annotationCommentInput', editedHistoryComment);
    const historyUpdateResponse = page.waitForResponse(response =>
      response.url().includes('/api/items/item-old') && response.request().method() === 'PUT'
    );
    await page.click('.annotation-done');
    await page.locator('#sessions .session-row[data-session-id="session-1"]').click();
    assert.ok((await historyUpdateResponse).ok(), 'Saved annotation edit should persist');
    await page.waitForFunction(() => document.querySelector('#sessions .session-row.is-active')?.dataset.sessionId === 'session-1');
    assert.equal(fake.sessions.get('session-2').items[1].comment, editedHistoryComment);
    await page.locator('#sessions .session-row[data-session-id="session-2"]').click();
    await page.waitForSelector('.saved-item-row');
    assert.match(await page.locator('#draftItems').textContent(), /Edited history annotation/);
    await page.locator('#sessions .session-row[data-session-id="session-1"]').click();
    await page.waitForFunction(() => document.querySelector('#sessions .session-row.is-active')?.dataset.sessionId === 'session-1');
    await page.locator('#sessions .session-row[data-session-id="session-2"]').click();
    await page.waitForFunction(comment => document.querySelector('#draftItems')?.textContent.includes(comment), editedHistoryComment);
    await page.locator('#sessions .session-row[data-session-id="session-1"]').click();
    await page.waitForFunction(() => document.querySelector('#sessions .session-row.is-active')?.dataset.sessionId === 'session-1');

    const readyConnectionResponse = page.waitForResponse(response =>
      response.url().includes('/api/connection') && response.request().method() === 'GET'
    );
    await page.selectOption('#devicePicker', 'device-1');
    await waitForReady(page);
    const readyConnection = await (await readyConnectionResponse).json();
    assert.deepEqual(readyConnection.selectedDevice, {
      serial: 'device-1',
      state: 'device',
      label: 'Pixel Smoke',
      selected: true,
    });
    assert.deepEqual(readyConnection.devices, [
      {
        serial: 'device-1',
        state: 'device',
        label: 'Pixel Smoke',
        selected: true,
      },
      {
        serial: 'device-2',
        state: 'offline',
        label: 'Fold Offline',
        selected: false,
      },
    ]);
    assert.equal(await page.locator('#deviceName').textContent(), 'Pixel Smoke');

    const imageBox = await page.locator('#snapshotImage').boundingBox();
    assert.ok(imageBox, 'Expected preview image to be visible');
    const initialItemsCount = currentSession().items.length;
    const invalidBatch = await page.request.post(`${baseUrl}api/items/batch`, {
      data: {
        items: [
          {
            targetType: 'node',
            bounds: { left: 70, top: 110, right: 330, bottom: 190 },
            nodeUid: 'node-button',
            comment: 'Missing preview id should be rejected',
          },
        ],
      },
    });
    assert.equal(invalidBatch.status(), 400);
    assert.equal(currentSession().items.length, initialItemsCount, 'Invalid batch should not add items');
    const invalidNavigation = await page.request.post(`${baseUrl}api/navigation`, {
      data: { action: 'tap', x: Number.NaN, y: imageBox.y },
    });
    assert.equal(invalidNavigation.status(), 400);
    assert.equal(fake.navigationCalls, 0, 'Invalid navigation should not be accepted');
    const unsupportedNavigation = await page.request.post(`${baseUrl}api/navigation`, {
      data: { action: 'tap', x: imageBox.x, y: imageBox.y, unsupported: true },
    });
    assert.equal(unsupportedNavigation.status(), 400);
    assert.equal(fake.navigationCalls, 0, 'Unsupported navigation fields should not be accepted');
    const navigationResponse = page.waitForResponse(response =>
      response.url().includes('/api/navigation') && response.request().method() === 'POST'
    );
    await page.mouse.click(imageBox.x + imageBox.width / 2, imageBox.y + imageBox.height / 2);
    const navigation = await navigationResponse;
    assert.ok(navigation.ok(), 'Select-mode tap navigation should succeed');
    assert.equal(navigation.status(), 200);
    assert.equal(fake.navigationCalls, 1, 'Select-mode preview click should send navigation');

    fake.connectionState = 'OPEN_APP';
    await page.click('#refreshDevicesButton');
    await page.waitForFunction(() => document.getElementById('connectionCard')?.dataset.connectionState === 'reconnect');
    await page.waitForFunction(() => document.getElementById('previewStaleBadge')?.hidden === false);
    await page.click('#connectionPrimaryAction');
    await waitForReady(page);
    assert.equal(await page.locator('#deviceName').textContent(), 'Pixel Smoke');
    await page.waitForFunction(() => document.getElementById('previewStaleBadge')?.hidden === true);

    await page.click('#annotateToolButton');
    await page.waitForSelector('#snapshot[data-tool-mode="annotate"]');
    await page.waitForSelector('#annotateHint');
    await waitForSnapshotImageReady(page, 'Annotate snapshot image was not ready');
    await page.mouse.click(
      imageBox.x + imageBox.width * 200 / 400,
      imageBox.y + imageBox.height * 150 / 800,
    );
    await waitForPendingPins(page, 1, 'Node annotation was not added');
    await page.mouse.move(imageBox.x + 40, imageBox.y + 40);
    await page.mouse.down();
    await page.mouse.move(imageBox.x + 120, imageBox.y + 90);
    await page.mouse.up();
    await waitForPendingPins(page, 2, 'Area annotation was not added');
    await page.waitForSelector('#annotationCommentInput');
    const annotationComment = 'Make the checkout button clearer';
    await page.fill('#annotationCommentInput', annotationComment);
    await page.waitForFunction(() => !document.getElementById('copyPromptButton').disabled);
    await page.click('#copyPromptButton');
    await page.waitForFunction(() => window.__fixthisCopiedText?.includes('Make the checkout button clearer'));
    const copiedText = await page.evaluate(() => window.__fixthisCopiedText);
    assert.match(copiedText, /Make the checkout button clearer/);

    await page.click('#annotateToolButton');
    await page.waitForSelector('#snapshot[data-tool-mode="annotate"]');
    await waitForSnapshotImageReady(page, 'Send-agent snapshot image was not ready');
    const sendImageBox = await page.locator('#snapshotImage').boundingBox();
    assert.ok(sendImageBox, 'Expected preview image to be visible before send-agent annotation');
    await page.mouse.move(sendImageBox.x + 60, sendImageBox.y + 220);
    await page.mouse.down();
    await page.mouse.move(sendImageBox.x + 160, sendImageBox.y + 290);
    await page.mouse.up();
    await waitForPendingPins(page, 1, 'Send-agent annotation was not added');
    await page.waitForSelector('#annotationCommentInput');
    const sendComment = 'Send this annotation to the agent';
    await page.fill('#annotationCommentInput', sendComment);
    await page.waitForFunction(() => !document.getElementById('sendAgentButton').disabled);
    const agentHandoffResponse = page.waitForResponse(response =>
      response.url().includes('/api/agent-handoffs') && response.request().method() === 'POST'
    );
    await page.click('#sendAgentButton');
    assert.ok((await agentHandoffResponse).ok(), 'Agent handoff should persist');
    await page.waitForFunction(() => document.getElementById('error')?.textContent.includes('Saved to MCP'));
    assert.equal(fake.handoffPrompts.length, 1, 'Expected one agent handoff prompt');
    assert.match(fake.handoffPrompts[0], /Send this annotation to the agent/);

    // Scenario: device interaction availability — screen-off blocks annotation, auto-resumes.
    // Toggles `availability.screenInteractive` between false and true while in Annotate mode.
    // Asserts: overlay visible during off, overlay gone during on, no spurious error,
    // and `state.preview` plus pending pins survive the round trip.
    await page.evaluate(() => { window.__fakeAvailability = window.__fakeAvailability || {}; });
    fake.availability.screenInteractive = false;
    await page.waitForFunction(
      () => document.getElementById('canvasBlockedOverlay')?.hidden === false,
      { timeout: 10000 }
    );
    const headlineWhileBlocked = await page.evaluate(() =>
      document.querySelector('#canvasBlockedOverlay [data-headline]')?.textContent
    );
    assert.equal(headlineWhileBlocked, 'Device screen is off',
      'Blocked overlay should show screenOff headline');
    const errorBeforeClick = await page.evaluate(() =>
      document.getElementById('error')?.textContent ?? ''
    );
    await page.locator('#snapshotImage').click({ position: { x: 50, y: 50 }, force: true });
    const errorAfterClick = await page.evaluate(() =>
      document.getElementById('error')?.textContent ?? ''
    );
    assert.equal(errorAfterClick, errorBeforeClick,
      'Click while blocked must not surface a new error');
    const pendingCountWhileBlocked = await page.evaluate(() =>
      document.querySelectorAll('.pending-item-row').length
    );
    fake.availability.screenInteractive = true;
    await page.waitForFunction(
      () => document.getElementById('canvasBlockedOverlay')?.hidden === true,
      { timeout: 10000 }
    );
    const pendingCountAfterUnblock = await page.evaluate(() =>
      document.querySelectorAll('.pending-item-row').length
    );
    assert.equal(pendingCountAfterUnblock, pendingCountWhileBlocked,
      'Pending pins must survive blocked → unblocked round trip');
    const chipSuffixCleared = await page.evaluate(() =>
      document.getElementById('deviceConnectionState')?.textContent
    );
    assert.equal(chipSuffixCleared.includes('·'), false,
      'Chip suffix must clear once interaction is unblocked');

    assert.deepEqual(consoleErrors, []);
  } finally {
    await browser.close();
  }
}

const server = createServer();
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const { port } = server.address();

try {
  await runSmoke(`http://127.0.0.1:${port}/`);
  console.log('Console browser smoke passed.');
} catch (error) {
  console.error(`${browserBlocker(error)}: ${error?.message || error}`);
  process.exitCode = 1;
} finally {
  await new Promise(resolve => server.close(resolve));
}
