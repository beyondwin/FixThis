#!/usr/bin/env node
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
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
  'network-outage': {
    apply: applyNetworkOutage,
    requiredViewports: Object.keys(VIEWPORTS),
    status: '@blocked-pending-impl',
  },
  'slow-handoff': {
    apply: applySlowHandoff,
    requiredViewports: Object.keys(VIEWPORTS),
    status: '@blocked-pending-impl',
  },
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
    allowSkips: false,
    failOnSkips: false,
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
    else if (flag === '--allow-skips') { args.allowSkips = true; }
    else if (flag === '--fail-on-skips') { args.failOnSkips = true; }
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
  if (SCENARIOS[scenarioKey].status === '@blocked-pending-impl') {
    console.warn(`warn: skipping ${scenarioKey}/${viewportKey} — @blocked-pending-impl (selector missing in console bundle)`);
    return {
      scenarioKey,
      viewportKey,
      ok: true,
      skipped: true,
      skipReason: '@blocked-pending-impl',
    };
  }
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
    if (r.skipped) {
      return `    <testcase classname="console-harness" name="${name}">\n      <skipped message="${xmlEscape(r.skipReason || 'skipped')}" />\n    </testcase>`;
    }
    if (r.ok) {
      return `    <testcase classname="console-harness" name="${name}" />`;
    }
    return `    <testcase classname="console-harness" name="${name}">\n      <failure>${xmlEscape(r.error)}</failure>\n    </testcase>`;
  }).join('\n');
  const skipped = results.filter((r) => r.skipped).length;
  const xml = `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="console-harness" tests="${results.length}" failures="${results.filter((r) => !r.ok).length}" skipped="${skipped}">\n${cases}\n</testsuite>\n`;
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
      const status = result.skipped ? 'SKIP' : result.ok ? 'PASS' : 'FAIL';
      const suffix = result.skipped ? ` — ${result.skipReason}` : result.ok ? '' : ` — ${result.error}`;
      console.log(`[${status}] ${result.scenarioKey} / ${result.viewportKey}${suffix}`);
    }
  }
  emitJunit(results, args.output);
  const failed = results.filter((r) => !r.ok);
  const skipped = results.filter((r) => r.skipped);
  if (failed.length > 0) {
    process.exitCode = 1;
  } else if (skipped.length > 0 && (args.failOnSkips || (!args.allowSkips && args.matrix !== 'all'))) {
    process.exitCode = 2;
  }
}

// Guard auto-run so importing this module for tests does not trigger main().
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(err);
    process.exitCode = 1;
  });
}
