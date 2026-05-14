import { readFileSync } from 'node:fs';
import http from 'node:http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');

const indexHtml = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/index.html'), 'utf8');
const stylesCss = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');
const appJs = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/app.js'), 'utf8');

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

export async function startFakeBridge(options = {}) {
  const initial = options.scenario ?? 'happy-path';
  if (!SCENARIOS[initial]) {
    throw new Error(`unknown scenario: ${initial}`);
  }
  let activeScenario = initial;
  let scenarioState = SCENARIOS[activeScenario]();
  const requestLog = [];

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    const entry = { method: req.method, path: url.pathname, time: Date.now() };
    requestLog.push(entry);

    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
      res.end(pageHtml);
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

  return {
    url,
    getRequestLog: () => requestLog.slice(),
    applyScenario: async (name) => {
      if (!SCENARIOS[name]) {
        throw new Error(`unknown scenario: ${name}`);
      }
      activeScenario = name;
      scenarioState = SCENARIOS[name]();
    },
    close: async () => {
      await new Promise((resolveClose) => server.close(resolveClose));
    },
  };
}

export const FIXTURE_SCENARIO_KEYS = Object.keys(SCENARIOS);
