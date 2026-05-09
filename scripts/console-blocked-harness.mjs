#!/usr/bin/env node
// Tiny harness that reuses the smoke fixture so I can drive the console
// with Playwright MCP from the outside. Prints the URL and keeps running.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import http from 'node:http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const indexHtml = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/index.html'), 'utf8');
const stylesCss = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');
const appJs = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/app.js'), 'utf8');
const pageHtml = indexHtml
  .replace('<!-- FIXTHIS_STYLES -->', `<style>${stylesCss}</style>`)
  .replace('<!-- FIXTHIS_SCRIPT -->', `<script>${appJs}</script>`);

const now = 1_779_000_000_000;
const fake = {
  selectedSerial: 'device-1',
  connectionState: 'READY',
  previewId: 1,
  openedSessionId: 'session-1',
  navigationCalls: 0,
  handoffPrompts: [],
  devices: [
    { serial: 'device-1', model: 'Pixel Smoke', state: 'device' },
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

function makeSession(sessionId, timeOffset, items) {
  return {
    sessionId,
    packageName: 'io.beyondwin.fixthis.sample',
    createdAtEpochMillis: now + timeOffset,
    updatedAtEpochMillis: now + timeOffset + 1000,
    deliverySnapshot: { sentCount: 0, latestSentBatchId: null, latestSentAtEpochMillis: null },
    handoffBatches: [],
    items,
    screens: [
      { screenId: 'screen-' + sessionId, capturedAtEpochMillis: now + timeOffset, screenshotUrl: '/api/screenshot/full', sourceCandidates: [] },
    ],
    bridgeProtocolVersion: '1',
    sidekickVersion: '1',
    sourceIndexAvailable: true,
  };
}

fake.sessions.set('session-1', makeSession('session-1', 0, []));

function currentSession() { return fake.sessions.get(fake.openedSessionId); }

function toConnectionDevice(device) {
  return {
    serial: device.serial,
    state: device.state,
    label: device.model || device.serial,
    selected: device.serial === fake.selectedSerial,
  };
}

function connectionPayload() {
  const selectedDevice = fake.devices.find(d => d.serial === fake.selectedSerial) || null;
  return {
    state: fake.connectionState,
    headline: fake.connectionState === 'READY' ? 'Connected' : 'Connect to your app',
    message: fake.connectionState === 'READY' ? 'FixThis is connected.' : "We'll find your phone.",
    primaryAction: fake.connectionState === 'READY' ? 'CAPTURE' : 'OPEN_APP',
    selectedDevice: selectedDevice ? toConnectionDevice(selectedDevice) : null,
    devices: fake.devices.map(toConnectionDevice),
    packageName: 'io.beyondwin.fixthis.sample',
    canCapture: fake.connectionState === 'READY',
    canNavigate: fake.connectionState === 'READY',
    availability: fake.connectionState === 'READY' ? { ...fake.availability } : null,
    details: { bridgeState: fake.connectionState === 'READY' ? 'ready' : 'paused', rawError: null },
  };
}

function devicePayload() {
  return {
    selectedSerial: fake.selectedSerial,
    devices: fake.devices.map(d => ({ ...d, selected: d.serial === fake.selectedSerial })),
  };
}

function jsonResponse(response, value, status = 200) {
  const body = JSON.stringify(value);
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body) });
  response.end(body);
}
function textResponse(response, value, status = 200, contentType = 'text/plain; charset=utf-8') {
  response.writeHead(status, { 'Content-Type': contentType });
  response.end(value);
}
async function readJsonBody(request) {
  return new Promise((resolveBody, rejectBody) => {
    const chunks = [];
    request.on('data', c => chunks.push(c));
    request.on('end', () => {
      try { resolveBody(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); }
      catch (err) { rejectBody(err); }
    });
    request.on('error', rejectBody);
  });
}

async function handleApi(request, response, url) {
  // Diagnostic endpoint: GET /api/_fake → expose current fake.availability
  if (request.method === 'GET' && url.pathname === '/api/_fake') return jsonResponse(response, { availability: fake.availability, connectionState: fake.connectionState });
  // Mutation endpoint: POST /api/_fake { availability: {...} }
  if (request.method === 'POST' && url.pathname === '/api/_fake') {
    const body = await readJsonBody(request);
    if (body.availability) Object.assign(fake.availability, body.availability);
    if (typeof body.connectionState === 'string') fake.connectionState = body.connectionState;
    return jsonResponse(response, { availability: fake.availability, connectionState: fake.connectionState });
  }
  if (request.method === 'GET' && url.pathname === '/api/session') return jsonResponse(response, currentSession());
  if (request.method === 'GET' && url.pathname === '/api/connection') return jsonResponse(response, connectionPayload());
  if (request.method === 'GET' && url.pathname === '/api/devices') return jsonResponse(response, devicePayload());
  if (request.method === 'POST' && url.pathname === '/api/devices/select') {
    const body = await readJsonBody(request);
    fake.selectedSerial = body.serial;
    fake.connectionState = 'READY';
    return jsonResponse(response, connectionPayload());
  }
  if (request.method === 'GET' && url.pathname === '/api/sessions') {
    return jsonResponse(response, { sessions: Array.from(fake.sessions.values()) });
  }
  if (request.method === 'POST' && url.pathname === '/api/heartbeat') return jsonResponse(response, { ok: true });
  if (request.method === 'GET' && url.pathname === '/api/preview/latest') {
    const session = currentSession();
    return jsonResponse(response, {
      previewId: 'preview-' + fake.previewId++,
      screen: session.screens[0],
      screenshotUrl: '/api/screenshot/full',
      capturedAtEpochMillis: now + 1000,
    });
  }
  if (request.method === 'POST' && url.pathname === '/api/session/open') {
    const body = await readJsonBody(request);
    if (body.sessionId && fake.sessions.has(body.sessionId)) fake.openedSessionId = body.sessionId;
    return jsonResponse(response, currentSession());
  }
  if (request.method === 'POST' && url.pathname === '/api/session/close') return jsonResponse(response, { ok: true });
  return textResponse(response, 'Not found', 404);
}

const server = http.createServer(async (request, response) => {
  try {
    const url = new URL(request.url || '/', 'http://127.0.0.1');
    if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) return textResponse(response, pageHtml, 200, 'text/html; charset=utf-8');
    if (request.method === 'GET' && url.pathname.endsWith('/screenshot/full')) {
      const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="640" viewBox="0 0 320 640"><rect width="320" height="640" fill="#1f2937"/><text x="160" y="320" text-anchor="middle" fill="#e5e7eb" font-family="sans-serif">Sample preview</text></svg>';
      return textResponse(response, svg, 200, 'image/svg+xml; charset=utf-8');
    }
    if (url.pathname.startsWith('/api/')) return await handleApi(request, response, url);
    return textResponse(response, 'Not found', 404);
  } catch (error) {
    return textResponse(response, error?.stack || String(error), 500);
  }
});

server.listen(0, '127.0.0.1', () => {
  const { port } = server.address();
  console.log(`HARNESS_URL=http://127.0.0.1:${port}/`);
});
