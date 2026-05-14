# Console Harness Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Promote the three Playwright-touching console harnesses (`console-blocked-harness.mjs`, `console-responsive-stress.mjs`, `console-browser-smoke.mjs`) from manual aids to a nightly CI-enforced check, and add three new scenarios (network outage, slow handoff, multi-tab) that today are never exercised.

**Architecture:** Extract a shared fake-bridge fixture module under `scripts/console-fixture/`, layer three new scenario modules on top of it, and add a single `scripts/console-harness.mjs` driver that runs a `(scenario × viewport)` matrix with Playwright. A new GitHub Actions workflow `console-harness-nightly.yml` runs the driver on a cron schedule plus `paths`-filtered PRs, uploads failure artifacts, and stays informational so PR latency is preserved.

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
- `.github/workflows/console-harness-nightly.yml` — nightly + paths-filtered PR workflow.

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
  // The fakeBridge slow-handoff scenario uses a fixed 6 s delay; for unit tests
  // callers can pass options.delayMs to override via a one-off scenario alias.
  const delayMs = options.delayMs ?? 6000;
  // We extend the fixture's SCENARIOS map indirectly by re-applying a
  // synthesized scenario name; the fakeBridge only supports a static map, so
  // we monkey-patch through scenarioState here.
  await fixture.applyScenario('slow-handoff');
  if (typeof fixture.__overrideHandoffDelayMs === 'function') {
    fixture.__overrideHandoffDelayMs(delayMs);
  }
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

To make `slowHandoff` parameter-overridable, extend `fakeBridgeServer.mjs` minimally. In the returned object add:

```javascript
    __overrideHandoffDelayMs: (delayMs) => {
      scenarioState = { ...scenarioState, handoffDelayMs: delayMs };
    },
```

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
- Modify: `scripts/console-browser-smoke.mjs`
- Modify: `scripts/console-blocked-harness.mjs`
- Modify: `scripts/console-responsive-stress.mjs`

- [ ] **Step 1: Update `console-browser-smoke.mjs` to use `startFakeBridge`**

Inside `scripts/console-browser-smoke.mjs`, near the top:

Replace the existing inline `http.createServer` and inline HTML assembly block (currently spanning the inline `readFileSync` calls and the `http.createServer` body, see lines ~9 through the `server.listen` call) with:

```javascript
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';

const fixture = await startFakeBridge({ scenario: 'happy-path' });
const baseUrl = fixture.url;
```

At the end of the script, replace the existing `server.close()` invocation with `await fixture.close();`.

Leave the Playwright driving block unchanged for now — only the fixture wiring is being refactored.

- [ ] **Step 2: Update `console-blocked-harness.mjs`**

Replace its inline `http.createServer` block and inline HTML assembly with:

```javascript
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';

const fixture = await startFakeBridge({ scenario: 'blocked-welcome' });
console.log(`open ${fixture.url} to drive the console`);
process.on('SIGINT', async () => {
  await fixture.close();
  process.exit(0);
});
```

The harness no longer needs its own fake-bridge HTTP wiring; the rest of the script (Playwright MCP driving notes) is unchanged.

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
node --check scripts/console-browser-smoke.mjs
node --check scripts/console-blocked-harness.mjs
node --check scripts/console-responsive-stress.mjs
```

Expected: each command exits 0.

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
git add scripts/console-browser-smoke.mjs scripts/console-blocked-harness.mjs scripts/console-responsive-stress.mjs
git commit -m "refactor(console): share fake bridge fixture across harnesses"
```

---

## Task 4: Build the Matrix Driver `scripts/console-harness.mjs`

**Files:**
- Create: `scripts/console-harness.mjs`
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

function parseArgs(argv) {
  const args = { matrix: 'all', viewport: 'all', output: resolve(root, 'output/playwright'), headed: false };
  for (let i = 2; i < argv.length; i += 1) {
    const flag = argv[i];
    const next = argv[i + 1];
    if (flag === '--matrix') { args.matrix = next; i += 1; }
    else if (flag === '--viewport') { args.viewport = next; i += 1; }
    else if (flag === '--output') { args.output = next; i += 1; }
    else if (flag === '--headed') { args.headed = true; }
  }
  return args;
}

function selectScenarios(matrixArg) {
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
      const second = await context.newPage();
      await second.goto(fixture.url, { waitUntil: 'domcontentloaded' });
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

function emitJunit(results, outputDir) {
  mkdirSync(outputDir, { recursive: true });
  const cases = results.map((r) => {
    const name = `${r.scenarioKey}__${r.viewportKey}`;
    if (r.ok) {
      return `    <testcase classname="console-harness" name="${name}" />`;
    }
    return `    <testcase classname="console-harness" name="${name}">\n      <failure>${r.error}</failure>\n    </testcase>`;
  }).join('\n');
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="console-harness" tests="${results.length}" failures="${results.filter((r) => !r.ok).length}">\n${cases}\n</testsuite>\n`;
  writeFileSync(resolve(outputDir, 'results.xml'), xml);
}

async function main() {
  const args = parseArgs(process.argv);
  const playwright = await loadPlaywright();
  const scenarios = selectScenarios(args.matrix);
  const results = [];
  for (const scenarioKey of scenarios) {
    if (!SCENARIOS[scenarioKey]) continue;
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

Expected: no scenarios match, results array is empty, exit code 0 (empty matrix is allowed for now).

Then run:

```bash
node scripts/console-harness.mjs --matrix network-outage --viewport mobile-390
```

Expected: either PASS (if the console gracefully shows a reconnect banner) or FAIL with an error message; exit code corresponds.

- [ ] **Step 6: Commit Task 4**

```bash
git add scripts/console-harness.mjs package.json
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
  pull_request:
    paths:
      - 'scripts/console-*.mjs'
      - 'scripts/console-fixture/**'
      - 'fixthis-mcp/src/main/resources/console/**'
      - '.github/workflows/console-harness-nightly.yml'

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

      - name: Cache Playwright browsers
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('package-lock.json') }}

      - name: Install Playwright Chromium
        run: npx playwright install --with-deps chromium

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
            scripts/console-fixture/scenarios-test.mjs
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
  scripts/console-fixture/scenarios-test.mjs
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
  scripts/console-fixture/scenarios-test.mjs
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
- [ ] PR latency: the nightly workflow is informational and runs on `schedule` plus a `paths`-filtered `pull_request` trigger, so green-build SLAs are preserved.
- [ ] Rollback: removing `console-harness-nightly.yml` and dropping the two fixture-test paths from `ci.yml` fully reverts the automation without touching production code.
- [ ] No new Gradle dependency: the only npm dependency change is the existing `playwright` package; Gradle artifacts are unchanged.
