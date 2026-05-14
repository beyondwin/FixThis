# Console Harness Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote the three Playwright-touching console harnesses (`console-blocked-harness.mjs`, `console-responsive-stress.mjs`, `console-browser-smoke.mjs`) from manual aids to a nightly CI-enforced check, and add three new scenarios (network outage, slow handoff, multi-tab) that today are never exercised.

**Architecture:** Extract a shared fake-bridge fixture module under `scripts/console-fixture/`, layer three new scenario modules on top of it, and add a single `scripts/console-harness.mjs` driver that runs a `(scenario × viewport)` matrix with Playwright. A new GitHub Actions workflow `console-harness-nightly.yml` runs the driver on a cron schedule plus `workflow_dispatch` only (no `pull_request` trigger), uploads failure artifacts, and stays informational so PR latency is preserved.

**Tech Stack:** Playwright, Node.js, GitHub Actions

---

## File Structure

**Create:**
- `docs/superpowers/specs/2026-05-14-console-harness-automation-design.md` — design and rationale.
- `docs/superpowers/plans/2026-05-14-console-harness-automation-implementation.md` — this execution plan.
- `scripts/console-fixture/fakeBridgeServer.mjs` — extracted shared fixture.
- `scripts/console-fixture/fakeBridgeServer-test.mjs` — unit tests for the fixture.
- `scripts/console-fixture/scenarios/networkOutage.mjs`
- `scripts/console-fixture/scenarios/slowHandoff.mjs`
- `scripts/console-fixture/scenarios/multiTab.mjs`
- `scripts/console-fixture/scenarios-test.mjs` — scenario module unit tests.
- `scripts/console-harness.mjs` — matrix driver, CI entry point.
- `scripts/console-harness.test.mjs` — unit tests for `parseArgs`, `selectScenarios`, `emitJunit`.
- `.github/workflows/console-harness-nightly.yml` — nightly workflow (schedule + workflow_dispatch only).

**Modify:**
- `scripts/console-browser-smoke.mjs` — delegate fixture wiring to `startFakeBridge`.
- `scripts/console-blocked-harness.mjs` — delegate fixture wiring to `startFakeBridge`.
- `scripts/console-responsive-stress.mjs` — delegate fixture wiring to `startFakeBridge`.
- `package.json` — add `console:harness` npm script.
- `CONTRIBUTING.md` — document the nightly workflow and local override knobs.

---

## Task 1: Extract the Shared Fake Bridge Fixture (TDD scaffolding)

**Files:**
- Create: `scripts/console-fixture/fakeBridgeServer.mjs`
- Create: `scripts/console-fixture/fakeBridgeServer-test.mjs`

- [ ] **Step 1: Write a failing test for `startFakeBridge` startup and shutdown**

Create `scripts/console-fixture/fakeBridgeServer-test.mjs` with this content:

```javascript
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
```

- [ ] **Step 2: Verify the test FAILS**

Run:

```bash
node --test scripts/console-fixture/fakeBridgeServer-test.mjs
```

Expected: failure with `Cannot find module … fakeBridgeServer.mjs` because the implementation does not yet exist.

- [ ] **Step 3: Implement `startFakeBridge`**

Create `scripts/console-fixture/fakeBridgeServer.mjs`:

```javascript
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
```

- [ ] **Step 4: Verify the test now PASSES**

Run:

```bash
node --test scripts/console-fixture/fakeBridgeServer-test.mjs
```

Expected: `# pass 3`, `# fail 0`.

- [ ] **Step 5: Commit Task 1**

```bash
git add scripts/console-fixture/fakeBridgeServer.mjs scripts/console-fixture/fakeBridgeServer-test.mjs
git commit -m "test(console): add fake bridge fixture module"
```

---

## Task 2: Add Scenario Modules (network-outage, slow-handoff, multi-tab)

**Files:**
- Create: `scripts/console-fixture/scenarios/networkOutage.mjs`
- Create: `scripts/console-fixture/scenarios/slowHandoff.mjs`
- Create: `scripts/console-fixture/scenarios/multiTab.mjs`
- Create: `scripts/console-fixture/scenarios-test.mjs`

- [ ] **Step 1: Write a failing test for the three scenario modules**

Create `scripts/console-fixture/scenarios-test.mjs`:

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { startFakeBridge } from './fakeBridgeServer.mjs';
import { applyNetworkOutage } from './scenarios/networkOutage.mjs';
import { applySlowHandoff } from './scenarios/slowHandoff.mjs';
import { applyMultiTab } from './scenarios/multiTab.mjs';

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
```

- [ ] **Step 2: Verify the test FAILS**

Run:

```bash
node --test scripts/console-fixture/scenarios-test.mjs
```

Expected: failure with `Cannot find module … networkOutage.mjs` because the scenario modules do not yet exist.

- [ ] **Step 3: Implement the three scenario modules**

Create `scripts/console-fixture/scenarios/networkOutage.mjs`:

```javascript
export async function applyNetworkOutage(fixture, options = {}) {
  await fixture.applyScenario('network-outage');
  return {
    label: 'network-outage',
    recoveryDelayMs: options.recoveryDelayMs ?? 3000,
    restore: async () => fixture.applyScenario('happy-path'),
  };
}
```

Create `scripts/console-fixture/scenarios/slowHandoff.mjs`:

```javascript
export async function applySlowHandoff(fixture, options = {}) {
  const delayMs = options.delayMs ?? 6000;
  // Use the fixture's first-class config-injection API. Each scenario accepts
  // an `overrides` object; the active scenario state is recomputed by merging
  // the factory output with `overrides`.
  await fixture.runScenario({
    scenario: 'slow-handoff',
    overrides: { handoffDelayMs: delayMs },
  });
  return {
    label: 'slow-handoff',
    delayMs,
  };
}
```

Create `scripts/console-fixture/scenarios/multiTab.mjs`:

```javascript
export async function applyMultiTab(fixture) {
  await fixture.applyScenario('multi-tab');
  return {
    label: 'multi-tab',
  };
}
```

To make `slowHandoff` parameter-overridable, extend `fakeBridgeServer.mjs` with a first-class `runScenario` config-injection method (no monkey-patches). In the returned object add:

```javascript
    runScenario: async ({ scenario, overrides = {} } = {}) => {
      if (!SCENARIOS[scenario]) {
        throw new Error(`unknown scenario: ${scenario}`);
      }
      activeScenario = scenario;
      scenarioState = { ...SCENARIOS[scenario](), ...overrides };
    },
```

Keep `applyScenario(name)` as a thin shim that calls `runScenario({ scenario: name })` for back-compat with existing callers.

- [ ] **Step 4: Verify the test now PASSES**

Run:

```bash
node --test scripts/console-fixture/scenarios-test.mjs scripts/console-fixture/fakeBridgeServer-test.mjs
```

Expected: `# pass 6`, `# fail 0`.

- [ ] **Step 5: Commit Task 2**

```bash
git add scripts/console-fixture/scenarios/ scripts/console-fixture/scenarios-test.mjs scripts/console-fixture/fakeBridgeServer.mjs
git commit -m "test(console): add network outage, slow handoff, multi-tab scenarios"
```

---

## Task 3: Delegate Existing Harnesses to the Shared Fixture

**Files:**
- Modify: `scripts/console-blocked-harness.mjs`
- Modify: `scripts/console-responsive-stress.mjs`

**Deferred to Phase 2:** `scripts/console-browser-smoke.mjs` is intentionally NOT migrated in Phase 1. Its `handleApi` covers a much richer surface (`/api/connection`, `/api/devices`, `/api/sessions`, `/api/preview/...`, `/api/session/open`, `/api/items/...`, `/api/navigation`, `/api/agent-handoffs`, etc.) than the shared fixture supports, and spec §3.6 ("legacy entry points stay functional") forbids end-to-end smoke regression. Folding the smoke's API surface into `startFakeBridge` is a Phase 2 follow-up; do NOT touch `scripts/console-browser-smoke.mjs` in this task.

- [ ] **Step 1: Deferred — `console-browser-smoke.mjs` is out of scope for Phase 1 (see note above).**

- [ ] **Step 2: Update `console-blocked-harness.mjs`**

Replace its inline `http.createServer` block and inline HTML assembly with the
shared fixture, but **preserve** the existing interactive parking behavior. The
harness today prints the URL and parks so a human can drive it via Playwright
MCP; that flow must keep working. Add a `--non-interactive` flag so CI (and
ad-hoc scripted runs) can exit after fixture startup without manual SIGINT.

```javascript
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';

const nonInteractive =
  process.argv.includes('--non-interactive') ||
  process.env.FIXTHIS_BLOCKED_NON_INTERACTIVE === '1';

const fixture = await startFakeBridge({ scenario: 'blocked-welcome' });
console.log(`open ${fixture.url} to drive the console`);

if (nonInteractive) {
  // CI path: exit cleanly after asserting the fixture came up. No parking.
  await fixture.close();
  process.exit(0);
}

// Interactive path (default): preserve any prompts, status lines, or
// keep-alive timers from the previous implementation, then park on SIGINT.
// Migrate the existing interactive scaffolding verbatim — do NOT collapse it
// to a single SIGINT handler. Examples to preserve: connection status logging,
// 'press Ctrl+C to stop' hint, periodic 'still alive' heartbeat if present.
process.on('SIGINT', async () => {
  await fixture.close();
  process.exit(0);
});
```

The harness no longer needs its own fake-bridge HTTP wiring; the rest of the
interactive scaffolding (Playwright MCP driving notes, status logging, prompts)
is unchanged.

- [ ] **Step 3: Update `console-responsive-stress.mjs`**

Replace its inline HTML assembly and `createServer` factory with:

```javascript
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';

const fixture = await startFakeBridge({ scenario: 'happy-path' });
const pageUrl = fixture.url;
```

At the end of the script, change `server.close()` to `await fixture.close()`.

- [ ] **Step 4: Verify the existing harnesses still start (smoke run, no Playwright assertions yet)**

Run:

```bash
node --check scripts/console-blocked-harness.mjs
node --check scripts/console-responsive-stress.mjs
```

Expected: each command exits 0. (`console-browser-smoke.mjs` is unmodified by this task and continues to use its private fixture.)

- [ ] **Step 5: Verify the existing `console-js` CI tests still pass**

Run:

```bash
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs
```

Expected: all tests pass; no regression.

- [ ] **Step 6: Commit Task 3**

```bash
git add scripts/console-blocked-harness.mjs scripts/console-responsive-stress.mjs
git commit -m "refactor(console): share fake bridge fixture across blocked/responsive harnesses"
```

---

## Task 4: Build the Matrix Driver `scripts/console-harness.mjs`

**Files:**
- Create: `scripts/console-harness.mjs`
- Create: `scripts/console-harness.test.mjs`
- Modify: `package.json`

- [ ] **Step 1: Add npm script**

Edit `package.json`. In the `"scripts"` block, add the new entry after `"console:smoke"`:

```json
    "console:harness": "node scripts/console-harness.mjs",
```

- [ ] **Step 2: Write a failing smoke test for the driver**

Add a temporary one-off check command (no test file yet). Run:

```bash
node scripts/console-harness.mjs --matrix happy-path --viewport mobile-390
```

Expected: failure with `Cannot find module … console-harness.mjs`.

- [ ] **Step 2b: Verify selectors exist in the live console bundle**

Before wiring the driver to specific selectors, confirm the assumed
`data-testid` and class names actually exist in `fixthis-mcp/src/main/resources/console/`.
Run:

```bash
grep -rn 'data-testid="reconnect-banner"' fixthis-mcp/src/main/resources/console/ || echo "MISSING: reconnect-banner"
grep -rn 'pending-recovery'              fixthis-mcp/src/main/resources/console/ || echo "MISSING: pending-recovery"
grep -rn 'data-testid="send-button"'     fixthis-mcp/src/main/resources/console/ || echo "MISSING: send-button"
```

For each `MISSING:` line, mark the corresponding scenario in the SCENARIOS map with
`status: '@blocked-pending-impl'` and have `runCell` skip it with a warning instead of
running Playwright assertions against it. Re-enable the scenario in a follow-up plan
once the selector exists in the console bundle. The driver code below assumes the
selectors exist; if any are missing, prefer `getByRole`/`getByText` fallbacks over
inventing new `data-testid`s in the console source from this plan.

- [ ] **Step 3: Implement the driver**

Create `scripts/console-harness.mjs`:

```javascript
#!/usr/bin/env node
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';
import { applyNetworkOutage } from './console-fixture/scenarios/networkOutage.mjs';
import { applySlowHandoff } from './console-fixture/scenarios/slowHandoff.mjs';
import { applyMultiTab } from './console-fixture/scenarios/multiTab.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const VIEWPORTS = {
  'mobile-390': { width: 390, height: 844 },
  'breakpoint-900': { width: 900, height: 900 },
  'compact-1024': { width: 1024, height: 768 },
  'desktop-1280': { width: 1280, height: 800 },
};

const SCENARIOS = {
  'happy-path': { apply: null, requiredViewports: Object.keys(VIEWPORTS) },
  'network-outage': { apply: applyNetworkOutage, requiredViewports: Object.keys(VIEWPORTS) },
  'slow-handoff': { apply: applySlowHandoff, requiredViewports: Object.keys(VIEWPORTS) },
  'multi-tab': { apply: applyMultiTab, requiredViewports: ['breakpoint-900', 'compact-1024', 'desktop-1280'] },
};

export function parseArgs(argv, env = process.env) {
  // Defaults can be overridden by env vars, which can in turn be overridden by
  // explicit flags. Order: defaults < env < flags.
  const args = {
    matrix: env.FIXTHIS_HARNESS_MATRIX ?? 'all',
    viewport: env.FIXTHIS_HARNESS_VIEWPORTS ?? 'all',
    output: env.FIXTHIS_HARNESS_OUTPUT ?? resolve(root, 'output/playwright'),
    headed: env.FIXTHIS_HARNESS_HEADED === '1',
    trace: env.FIXTHIS_HARNESS_TRACE === '1',
    updateBaselines: env.FIXTHIS_HARNESS_UPDATE_BASELINES === '1',
  };
  for (let i = 2; i < argv.length; i += 1) {
    const flag = argv[i];
    const next = argv[i + 1];
    if (flag === '--matrix') { args.matrix = next; i += 1; }
    else if (flag === '--viewport') { args.viewport = next; i += 1; }
    else if (flag === '--output') { args.output = next; i += 1; }
    else if (flag === '--headed') { args.headed = true; }
    else if (flag === '--trace') { args.trace = true; }
    else if (flag === '--update-baselines') { args.updateBaselines = true; }
  }
  return args;
}

function xmlEscape(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

export function selectScenarios(matrixArg) {
  if (matrixArg === 'all') return Object.keys(SCENARIOS);
  return matrixArg.split(',').map((s) => s.trim()).filter(Boolean);
}

function selectViewports(viewportArg, scenarioKey) {
  const allowed = SCENARIOS[scenarioKey].requiredViewports;
  if (viewportArg === 'all') return allowed;
  return viewportArg.split(',').map((v) => v.trim()).filter((v) => allowed.includes(v));
}

async function loadPlaywright() {
  return import('playwright');
}

async function runCell({ playwright, scenarioKey, viewportKey, args }) {
  const fixture = await startFakeBridge({ scenario: 'happy-path' });
  try {
    if (SCENARIOS[scenarioKey].apply) {
      await SCENARIOS[scenarioKey].apply(fixture);
    }
    const browser = await playwright.chromium.launch({ headless: !args.headed });
    const context = await browser.newContext({ viewport: VIEWPORTS[viewportKey] });
    const page = await context.newPage();
    const consoleErrors = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    page.on('pageerror', (err) => consoleErrors.push(err.message));
    await page.goto(fixture.url, { waitUntil: 'domcontentloaded' });

    if (scenarioKey === 'network-outage') {
      await page.waitForSelector('[data-testid="reconnect-banner"], .pending-recovery', { timeout: 8000 });
    } else if (scenarioKey === 'slow-handoff') {
      await page.waitForSelector('[data-testid="send-button"], button.send', { timeout: 8000 });
    } else if (scenarioKey === 'multi-tab') {
      // Two Pages in the same BrowserContext share localStorage; the DOM
      // `storage` event only fires in *other* pages of the same origin, so
      // `page` (the writer) will never see it. We always assert cross-tab
      // signals on the *receiver* page (here: `second`).
      const second = await context.newPage();
      await second.goto(fixture.url, { waitUntil: 'domcontentloaded' });
      // TODO: trigger a draft write on `page` and assert that `second`
      // observes the change (storage event or polling fallback). Receiver
      // must be `second`, never `page`.
      await second.close();
    } else {
      await page.waitForSelector('body');
    }

    if (consoleErrors.length > 0) {
      throw new Error(`console errors in ${scenarioKey}/${viewportKey}: ${consoleErrors.join(' | ')}`);
    }

    await browser.close();
    return { scenarioKey, viewportKey, ok: true };
  } catch (error) {
    return { scenarioKey, viewportKey, ok: false, error: String(error?.message ?? error) };
  } finally {
    await fixture.close();
  }
}

export function emitJunit(results, outputDir) {
  mkdirSync(outputDir, { recursive: true });
  const cases = results.map((r) => {
    const name = xmlEscape(`${r.scenarioKey}__${r.viewportKey}`);
    if (r.ok) {
      return `    <testcase classname="console-harness" name="${name}" />`;
    }
    return `    <testcase classname="console-harness" name="${name}">\n      <failure>${xmlEscape(r.error)}</failure>\n    </testcase>`;
  }).join('\n');
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="console-harness" tests="${results.length}" failures="${results.filter((r) => !r.ok).length}">\n${cases}\n</testsuite>\n`;
  writeFileSync(resolve(outputDir, 'results.xml'), xml);
}

async function main() {
  const args = parseArgs(process.argv);
  const playwright = await loadPlaywright();
  const requested = selectScenarios(args.matrix);
  const unknown = requested.filter((s) => !SCENARIOS[s]);
  const known = requested.filter((s) => SCENARIOS[s]);
  if (unknown.length > 0) {
    console.warn(`warn: unknown scenario(s) requested: ${unknown.join(', ')}`);
  }
  if (known.length === 0) {
    console.error(`error: no known scenarios selected for matrix='${args.matrix}'`);
    process.exitCode = 2;
    return;
  }
  const results = [];
  for (const scenarioKey of known) {
    const viewports = selectViewports(args.viewport, scenarioKey);
    for (const viewportKey of viewports) {
      const result = await runCell({ playwright, scenarioKey, viewportKey, args });
      results.push(result);
      const status = result.ok ? 'PASS' : 'FAIL';
      console.log(`[${status}] ${result.scenarioKey} / ${result.viewportKey}${result.ok ? '' : ` — ${result.error}`}`);
    }
  }
  emitJunit(results, args.output);
  const failed = results.filter((r) => !r.ok);
  if (failed.length > 0) {
    process.exitCode = 1;
  }
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
```

- [ ] **Step 3b: Add driver self-tests (`node --test`) for the pure helpers**

Create `scripts/console-harness.test.mjs`:

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseArgs, selectScenarios, emitJunit } from './console-harness.mjs';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

test('parseArgs honors defaults', () => {
  const args = parseArgs(['node', 'script'], {});
  assert.equal(args.matrix, 'all');
  assert.equal(args.viewport, 'all');
  assert.equal(args.headed, false);
});

test('parseArgs honors env vars', () => {
  const args = parseArgs(['node', 'script'], {
    FIXTHIS_HARNESS_MATRIX: 'network-outage',
    FIXTHIS_HARNESS_VIEWPORTS: 'mobile-390',
    FIXTHIS_HARNESS_HEADED: '1',
  });
  assert.equal(args.matrix, 'network-outage');
  assert.equal(args.viewport, 'mobile-390');
  assert.equal(args.headed, true);
});

test('parseArgs flags override env vars', () => {
  const args = parseArgs(
    ['node', 'script', '--matrix', 'slow-handoff', '--viewport', 'desktop-1280'],
    { FIXTHIS_HARNESS_MATRIX: 'network-outage' }
  );
  assert.equal(args.matrix, 'slow-handoff');
  assert.equal(args.viewport, 'desktop-1280');
});

test('selectScenarios expands all and trims CSV', () => {
  const all = selectScenarios('all');
  assert.ok(all.includes('happy-path'));
  const csv = selectScenarios(' slow-handoff , multi-tab ');
  assert.deepEqual(csv, ['slow-handoff', 'multi-tab']);
});

test('emitJunit XML-escapes failure messages', () => {
  const dir = mkdtempSync(join(tmpdir(), 'harness-'));
  try {
    emitJunit(
      [{ scenarioKey: 'x<y', viewportKey: 'm', ok: false, error: 'bad & worse "<>" \'q\'' }],
      dir
    );
    const xml = readFileSync(join(dir, 'results.xml'), 'utf8');
    assert.match(xml, /name="x&lt;y__m"/);
    assert.match(xml, /bad &amp; worse &quot;&lt;&gt;&quot; &apos;q&apos;/);
    assert.doesNotMatch(xml, /<failure>bad & /);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
```

Run:

```bash
node --test scripts/console-harness.test.mjs
```

Expected: all five tests pass (`# fail 0`). These pure-function tests run before the Playwright integration step, in the existing `console-js` CI lane.

- [ ] **Step 4: Verify the driver runs for a single scenario**

Run:

```bash
npx playwright install chromium
node scripts/console-harness.mjs --matrix happy-path --viewport mobile-390
```

Expected: `[PASS] happy-path / mobile-390` and `output/playwright/results.xml` is created with one `<testcase>` element. Exit code 0.

- [ ] **Step 5: Verify failure path emits non-zero exit code**

Run:

```bash
node scripts/console-harness.mjs --matrix nonexistent-scenario
```

Expected: warning `unknown scenario(s) requested: nonexistent-scenario` followed by `error: no known scenarios selected for matrix='nonexistent-scenario'`. Exit code 2. Typos must never silently succeed.

Then run:

```bash
node scripts/console-harness.mjs --matrix network-outage --viewport mobile-390
```

Expected: either PASS (if the console gracefully shows a reconnect banner) or FAIL with an error message; exit code corresponds.

- [ ] **Step 6: Document the baseline screenshot procedure (deferred until Phase 2)**

Visual-regression baselines (Appendix B in the spec) are a Phase 2 deliverable
because `responsive-stress` is deferred. Record the procedure now so Phase 2
inherits a single source of truth:

```bash
# Regenerate baselines locally. The env var gate is mandatory; without it the
# driver refuses to overwrite committed baseline PNGs even if --update-baselines
# is passed, so accidental updates from CI runs are impossible.
FIXTHIS_HARNESS_UPDATE_BASELINES=1 node scripts/console-harness.mjs \
  --matrix responsive-stress --update-baselines

# Review the diff manually, then commit:
git add scripts/console-fixture/baseline-screenshots/
git commit -m "test(console): refresh harness baseline screenshots"
```

Driver behavior: `--update-baselines` is only honored when
`process.env.FIXTHIS_HARNESS_UPDATE_BASELINES === '1'`. CI never sets this
variable, so `--update-baselines` is a no-op on the nightly workflow. This is
documented here for the Phase 2 follow-up; Task 4 itself does not produce any
baseline PNG.

- [ ] **Step 7: Commit Task 4**

```bash
git add scripts/console-harness.mjs scripts/console-harness.test.mjs package.json
git commit -m "test(console): add harness matrix driver"
```

---

## Task 5: Add the Nightly GitHub Actions Workflow

**Files:**
- Create: `.github/workflows/console-harness-nightly.yml`

- [ ] **Step 1: Write the workflow file**

Create `.github/workflows/console-harness-nightly.yml`:

```yaml
name: Console harness (nightly)

on:
  schedule:
    - cron: '17 9 * * *'
  workflow_dispatch: {}

# Intentionally NO pull_request trigger. G4 (PR latency unchanged) is non-
# negotiable; PR runs would push latency from ~1.5 min to ~25 min. PRs that
# touch console assets are validated by the existing console-js node --test
# lane. Promotion to PR gating must be a separate, explicit design.

concurrency:
  group: console-harness-${{ github.ref }}
  cancel-in-progress: true

jobs:
  console-harness:
    name: Console harness
    runs-on: ubuntu-latest
    timeout-minutes: 25

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up Node
        uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: 'npm'

      - name: Install npm dependencies
        run: npm ci

      - name: Resolve Playwright version
        id: pw-version
        # Read the resolved playwright version from package-lock.json so the
        # cache key invalidates the moment we bump Playwright (and therefore
        # the Chromium build it pins).
        run: |
          PW_VERSION=$(node -e "const p=require('./package-lock.json');\
            const pkg=p.packages && (p.packages['node_modules/playwright']||p.packages['node_modules/playwright-core']);\
            if(!pkg)throw new Error('playwright not in lockfile');\
            console.log(pkg.version)")
          echo "version=$PW_VERSION" >> "$GITHUB_OUTPUT"

      - name: Cache Playwright browsers
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          # Include the Playwright version so a Chromium bump (which is pinned
          # by Playwright) invalidates the cache automatically.
          key: playwright-${{ runner.os }}-${{ steps.pw-version.outputs.version }}-${{ hashFiles('package-lock.json') }}

      - name: Install Playwright Chromium
        run: npx playwright install --with-deps chromium

      - name: Verify bridge protocol version sync
        # Re-uses the existing JVM test that enforces equality across all four
        # mirrored sites (BridgeProtocol.kt, console MinimumSupportedProtocolVersion,
        # BridgeClient.kt, ServerVersionRoutes.kt). Catches drift before the
        # harness exercises the fixture against an already-stale console bundle.
        run: ./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"

      - name: Run console harness matrix
        run: node scripts/console-harness.mjs --matrix all --output output/playwright

      - name: Upload failure artifacts
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: console-harness-failure-${{ github.run_id }}
          path: output/playwright/**
          retention-days: 14
```

- [ ] **Step 2: Validate YAML locally**

Run:

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/console-harness-nightly.yml"); puts "ok"'
```

Expected: `ok`.

- [ ] **Step 3: Verify the workflow does NOT alter `.github/workflows/ci.yml`**

Run:

```bash
git diff -- .github/workflows/ci.yml
```

Expected: no output. The nightly workflow is fully additive.

- [ ] **Step 4: Commit Task 5**

```bash
git add .github/workflows/console-harness-nightly.yml
git commit -m "ci: add nightly console harness workflow"
```

---

## Task 6: Wire the Fixture Tests Into the `console-js` CI Job

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add the two new test files to the existing `node --test` invocation**

In `.github/workflows/ci.yml`, the `console-js` job's `Run console JavaScript tests` step currently lists seven files. Add the two new fixture-level tests to the list:

```yaml
      - name: Run console JavaScript tests
        run: |
          node --test \
            scripts/console-availability-test.mjs \
            scripts/pendingItemRecovery-test.mjs \
            scripts/beforeunloadGuard-test.mjs \
            scripts/undoRedo-test.mjs \
            scripts/undoKeymatch-test.mjs \
            scripts/activityDrift-test.mjs \
            scripts/previewStaleness-test.mjs \
            scripts/console-fixture/fakeBridgeServer-test.mjs \
            scripts/console-fixture/scenarios-test.mjs \
            scripts/console-harness.test.mjs
```

- [ ] **Step 2: Validate YAML locally**

Run:

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/ci.yml"); puts "ok"'
```

Expected: `ok`.

- [ ] **Step 3: Run the full `console-js` test set locally**

Run:

```bash
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs \
  scripts/console-fixture/fakeBridgeServer-test.mjs \
  scripts/console-fixture/scenarios-test.mjs \
  scripts/console-harness.test.mjs
```

Expected: every file reports `pass`; total `# fail 0`.

- [ ] **Step 4: Commit Task 6**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(console): include fixture tests in console-js job"
```

---

## Task 7: Document the Local Workflow in `CONTRIBUTING.md`

**Files:**
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Add a "Console Harness" section under `## Required Local Checks`**

Insert this block immediately after the `### Focused Test Loops` section that was added by the test-speed plan:

```markdown
### Console Harness

The nightly `Console harness` workflow runs the full Playwright matrix against a
fake bridge fixture. Run it locally before pushing changes that touch
`scripts/console-*` or `fixthis-mcp/src/main/resources/console/**`.

```bash
# Full matrix (all scenarios × all viewports):
npm run console:harness

# Single scenario across all viewports:
node scripts/console-harness.mjs --matrix network-outage

# Single scenario at one viewport (great for debugging):
node scripts/console-harness.mjs --matrix slow-handoff --viewport mobile-390 --headed
```

Environment knobs:

| Env var | Effect |
| --- | --- |
| `FIXTHIS_HARNESS_MATRIX` | CSV of scenario keys; default `all`. |
| `FIXTHIS_HARNESS_VIEWPORTS` | CSV of viewport keys; default `all`. |
| `FIXTHIS_HARNESS_HEADED` | `1` to launch headed Chromium for debugging. |

Failure artifacts (screenshots, traces, console logs) land under
`output/playwright/` and upload to GitHub Actions on nightly failures.
```

- [ ] **Step 2: Verify whitespace is clean**

Run:

```bash
git diff --check CONTRIBUTING.md
```

Expected: no output.

- [ ] **Step 3: Commit Task 7**

```bash
git add CONTRIBUTING.md
git commit -m "docs(console): document nightly harness workflow"
```

---

## Task 8: Final Verification Sweep

**Files:** All files touched by Tasks 1 through 7.

- [ ] **Step 1: Run the full `console-js` test set**

Run:

```bash
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs \
  scripts/console-fixture/fakeBridgeServer-test.mjs \
  scripts/console-fixture/scenarios-test.mjs \
  scripts/console-harness.test.mjs
```

Expected: `# fail 0`.

- [ ] **Step 2: Run the full harness matrix locally**

Run:

```bash
npx playwright install chromium
node scripts/console-harness.mjs --matrix all --output output/playwright
```

Expected: all cells report `PASS`. `output/playwright/results.xml` contains a `<testsuite>` with no `<failure>` elements.

- [ ] **Step 3: Confirm legacy harnesses still launch**

Run:

```bash
node --check scripts/console-browser-smoke.mjs
node --check scripts/console-blocked-harness.mjs
node --check scripts/console-responsive-stress.mjs
```

Expected: each exits 0.

- [ ] **Step 4: Validate both workflow files**

Run:

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/ci.yml"); YAML.load_file(".github/workflows/console-harness-nightly.yml"); puts "ok"'
```

Expected: `ok`.

- [ ] **Step 5: Run Gradle's required local matrix to confirm no Kotlin regression**

Run:

```bash
./gradlew \
  spotlessCheck \
  detekt \
  :fixthis-compose-core:test \
  :fixthis-cli:test \
  :fixthis-mcp:test \
  :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test \
  :app:assembleDebug \
  :fixthis-cli:installDist \
  :fixthis-mcp:installDist \
  --no-daemon
```

Expected: `BUILD SUCCESSFUL`. The harness work is Node-only, so this is a sanity check that nothing leaked into the Kotlin lane.

- [ ] **Step 6: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 7: Commit final verification notes if the plan or spec were edited**

If you adjusted spec or plan during execution, commit the doc changes:

```bash
git add docs/superpowers/specs/2026-05-14-console-harness-automation-design.md \
        docs/superpowers/plans/2026-05-14-console-harness-automation-implementation.md
git commit -m "docs(console): record harness automation verification"
```

If neither doc changed, skip this step.

---

## Self-Review Checklist

- [ ] Spec coverage: every goal in `2026-05-14-console-harness-automation-design.md` (G1–G5) maps to at least one task above.
- [ ] Placeholder scan: this plan contains no unfinished placeholder markers or generic instructions.
- [ ] Type consistency: `startFakeBridge`, `applyNetworkOutage`, `applySlowHandoff`, `applyMultiTab`, `FIXTURE_SCENARIO_KEYS` are named identically in implementation, tests, and driver.
- [ ] CI ordering: the new `console-harness-nightly.yml` workflow is fully additive; `.github/workflows/ci.yml` only gains two extra `node --test` paths.
- [ ] PR latency: the nightly workflow runs on `schedule` + `workflow_dispatch` only; no `pull_request` trigger, so green-build SLAs are preserved (G4).
- [ ] Rollback: removing `console-harness-nightly.yml` and dropping the two fixture-test paths from `ci.yml` fully reverts the automation without touching production code.
- [ ] No new Gradle dependency: the only npm dependency change is the existing `playwright` package; Gradle artifacts are unchanged.
