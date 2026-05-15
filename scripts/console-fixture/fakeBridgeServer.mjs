import { readFileSync } from 'node:fs';
import http from 'node:http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');

const indexHtml = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/index.html'), 'utf8');
const stylesCss = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');
const appJs = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/app.js'), 'utf8');
const transparentPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lm7F1wAAAABJRU5ErkJggg==',
  'base64',
);

const pageHtml = indexHtml
  .replace('<!-- FIXTHIS_STYLES -->', `<style>${stylesCss}</style>`)
  .replace('<!-- FIXTHIS_SCRIPT -->', `<script>${appJs}</script>`);

const SCENARIOS = {
  'happy-path': () => ({}),
  'network-outage': () => ({
    handoffStatus: 503,
    pollErrno: 'ECONNREFUSED',
  }),
  'slow-handoff': () => ({
    handoffDelayMs: 6000,
  }),
  'multi-tab': () => ({}),
  'blocked-welcome': () => ({
    forceState: 'WELCOME',
  }),
};

function initialSession() {
  const now = Date.now();
  return {
    schemaVersion: '1.0',
    sessionId: 'session-1',
    packageName: 'io.beyondwin.fixthis.sample',
    projectRoot: '/repo',
    ordinal: 1,
    status: 'active',
    createdAtEpochMillis: now,
    updatedAtEpochMillis: now,
    nextItemSequenceNumber: 1,
    screens: [],
    items: [],
    handoffBatches: [],
  };
}

function sessionSummary(session) {
  return {
    sessionId: session.sessionId,
    ordinal: session.ordinal,
    status: session.status,
    updatedAtEpochMillis: session.updatedAtEpochMillis,
    openItemCount: session.items.filter((item) => item.status !== 'resolved').length,
    resolvedItemCount: session.items.filter((item) => item.status === 'resolved').length,
    itemCount: session.items.length,
    screenCount: session.screens.length,
  };
}

function fakePreview(sessionId = 'session-1') {
  const now = Date.now();
  return {
    previewId: 'preview-1',
    sessionId,
    capturedAtEpochMillis: now,
    screen: {
      screenId: 'screen-1',
      capturedAtEpochMillis: now,
      displayName: 'Fake screen',
      roots: [],
      sourceIndexAvailable: false,
    },
    screenshotUrl: '/api/preview/preview-1/screenshot/full',
    fingerprint: 'fake-fingerprint',
  };
}

function json(res, body, status = 200) {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  res.end(JSON.stringify(body));
}

function readJson(req) {
  return new Promise((resolveRead) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      try {
        resolveRead(body ? JSON.parse(body) : {});
      } catch (_) {
        resolveRead({});
      }
    });
  });
}

export async function startFakeBridge(options = {}) {
  const initial = options.scenario ?? 'happy-path';
  if (!SCENARIOS[initial]) {
    throw new Error(`unknown scenario: ${initial}`);
  }
  let activeScenario = initial;
  let scenarioState = SCENARIOS[activeScenario]();
  let session = initialSession();
  const sessionsById = new Map([[session.sessionId, session]]);
  const previewDelays = new Map();
  const requestLog = [];

  function createSession({ sessionId, title } = {}) {
    const now = Date.now();
    const id = sessionId || `session-${sessionsById.size + 1}`;
    const next = {
      ...initialSession(),
      sessionId: id,
      title: title || id,
      ordinal: sessionsById.size + 1,
      createdAtEpochMillis: now,
      updatedAtEpochMillis: now,
    };
    sessionsById.set(id, next);
    return next;
  }

  function activeSession() {
    return session;
  }

  function openSession(sessionId) {
    const next = sessionsById.get(sessionId);
    if (next) {
      session = next;
    }
    return activeSession();
  }

  function delayPreviewForSession(sessionId, delayMs) {
    previewDelays.set(sessionId, Number(delayMs) || 0);
  }

  async function maybeDelayPreview(sessionId) {
    const delayMs = previewDelays.get(sessionId) || 0;
    if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs));
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    const entry = { method: req.method, path: url.pathname, time: Date.now() };
    requestLog.push(entry);

    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
      res.end(pageHtml);
      return;
    }
    if (url.pathname === '/api/server-version' && req.method === 'GET') {
      json(res, { serverBuildEpochMs: 0, serverGitSha: 'fake' });
      return;
    }
    if (url.pathname === '/api/events' && req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'text/event-stream; charset=utf-8', 'cache-control': 'no-store' });
      res.end('event: snapshot\ndata: {}\n\n');
      return;
    }
    if (url.pathname.endsWith('/screenshot/full') && req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'image/png', 'cache-control': 'no-store' });
      res.end(transparentPng);
      return;
    }

    const outageApiPaths = ['/api/session', '/api/sessions', '/api/agent-handoffs'];
    if (scenarioState.pollErrno === 'ECONNREFUSED' && outageApiPaths.includes(url.pathname)) {
      req.socket.destroy();
      return;
    }

    if (url.pathname === '/api/session' && req.method === 'GET') {
      json(res, session);
      return;
    }
    if (url.pathname === '/api/session/open' && req.method === 'POST') {
      const body = await readJson(req);
      if (body.newSession) {
        const next = createSession();
        session = next;
      } else if (body.sessionId) {
        openSession(body.sessionId);
      }
      session.updatedAtEpochMillis = Date.now();
      json(res, session);
      return;
    }
    if (url.pathname === '/api/sessions' && req.method === 'GET') {
      json(res, { sessions: Array.from(sessionsById.values()).map(sessionSummary) });
      return;
    }
    if (url.pathname === '/api/connection' && req.method === 'GET') {
      json(res, {
        state: scenarioState.forceState || 'READY',
        connection: 'connected',
        headline: 'Connected',
        message: 'Fake bridge connected',
        primaryAction: 'CAPTURE',
        packageName: session.packageName,
        selectedDevice: { serial: 'fake-device', label: 'Fake Device', state: 'device', selected: true },
        devices: [{ serial: 'fake-device', label: 'Fake Device', state: 'device', selected: true }],
        details: { bridgeState: 'connected' },
      });
      return;
    }
    if (url.pathname === '/api/devices' && req.method === 'GET') {
      json(res, {
        devices: [{ serial: 'fake-device', label: 'Fake Device', state: 'device', selected: true }],
        selectedSerial: 'fake-device',
      });
      return;
    }
    if (url.pathname === '/api/device/select' && req.method === 'POST') {
      json(res, {
        devices: [{ serial: 'fake-device', label: 'Fake Device', state: 'device', selected: true }],
        selectedSerial: 'fake-device',
      });
      return;
    }
    if (url.pathname === '/api/preview' && req.method === 'GET') {
      const previewSessionId = url.searchParams.get('sessionId') || session.sessionId;
      await maybeDelayPreview(previewSessionId);
      json(res, fakePreview(previewSessionId));
      return;
    }
    if (url.pathname === '/api/agent-handoffs' && req.method === 'POST') {
      const body = await readJson(req);
      if (scenarioState.handoffStatus) {
        json(res, { error: 'outage' }, scenarioState.handoffStatus);
        return;
      }
      if (scenarioState.handoffDelayMs) {
        await new Promise((r) => setTimeout(r, scenarioState.handoffDelayMs));
      }
      const itemIds = Array.isArray(body.itemIds) ? body.itemIds : [];
      const now = Date.now();
      session.updatedAtEpochMillis = now;
      session.items = session.items.map((item) => itemIds.includes(item.itemId)
        ? { ...item, delivery: 'sent', sentAtEpochMillis: now, lastHandedOffAtEpochMillis: now }
        : item);
      json(res, { session, prompt: 'Fake handoff prompt' });
      return;
    }

    if (url.pathname === '/api/handoff' && req.method === 'POST') {
      if (scenarioState.handoffStatus) {
        res.writeHead(scenarioState.handoffStatus, { 'content-type': 'text/plain' });
        res.end('outage');
        return;
      }
      if (scenarioState.handoffDelayMs) {
        await new Promise((r) => setTimeout(r, scenarioState.handoffDelayMs));
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    if (url.pathname.startsWith('/api/session/')) {
      if (scenarioState.pollErrno === 'ECONNREFUSED') {
        req.socket.destroy();
        return;
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found');
  });

  await new Promise((resolveListen) => server.listen(0, '127.0.0.1', resolveListen));
  const address = server.address();
  const url = `http://127.0.0.1:${address.port}`;

    const fixture = {
    url,
    consoleUrl: url,
    getRequestLog: () => requestLog.slice(),
    createSession,
    delayPreviewForSession,
    seedAnnotation: (overrides = {}) => {
      const now = Date.now();
      const screen = {
        screenId: overrides.screenId || 'screen-1',
        capturedAtEpochMillis: now,
        displayName: 'Fake screen',
        roots: [],
        sourceIndexAvailable: false,
      };
      if (!session.screens.some((candidate) => candidate.screenId === screen.screenId)) {
        session.screens.push(screen);
      }
      const item = {
        itemId: overrides.itemId || `item-${session.items.length + 1}`,
        screenId: screen.screenId,
        createdAtEpochMillis: now,
        updatedAtEpochMillis: now,
        target: {
          type: 'visual_area',
          boundsInWindow: { left: 10, top: 10, right: 80, bottom: 80 },
        },
        label: overrides.label || 'Fake button',
        severity: 'med',
        comment: overrides.comment || 'Seeded annotation',
        sequenceNumber: session.items.length + 1,
        delivery: 'draft',
        status: 'open',
      };
      session.items.push(item);
      session.updatedAtEpochMillis = now;
      session.nextItemSequenceNumber = session.items.length + 1;
      return item;
    },
    runScenario: async ({ scenario, overrides = {} } = {}) => {
      if (!SCENARIOS[scenario]) {
        throw new Error(`unknown scenario: ${scenario}`);
      }
      activeScenario = scenario;
      scenarioState = { ...SCENARIOS[scenario](), ...overrides };
    },
    applyScenario: async (name) => fixture.runScenario({ scenario: name }),
    close: async () => {
      await new Promise((resolveClose) => server.close(resolveClose));
    },
  };
  return fixture;
}

export const FIXTURE_SCENARIO_KEYS = Object.keys(SCENARIOS);
