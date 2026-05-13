#!/usr/bin/env node
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync } from 'node:fs';
import http from 'node:http';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outputDir = resolve(root, 'output/playwright');
mkdirSync(outputDir, { recursive: true });

const indexHtml = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/index.html'), 'utf8');
const stylesCss = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');
const pageHtml = indexHtml
  .replace('<!-- FIXTHIS_STYLES -->', `<style>${stylesCss}</style>`)
  .replace('<!-- FIXTHIS_SCRIPT -->', '<script>window.FixThisConsoleConfig = { consoleToken: "stress" };</script>');

const viewports = [
  { name: 'mobile-390', width: 390, height: 844 },
  { name: 'breakpoint-900', width: 900, height: 900 },
  { name: 'compact-1024', width: 1024, height: 768 },
  { name: 'desktop-1280', width: 1280, height: 800 },
];

function textResponse(response, value, status = 200, contentType = 'text/html; charset=utf-8') {
  response.writeHead(status, {
    'content-type': contentType,
    'cache-control': 'no-store',
  });
  response.end(value);
}

function createServer() {
  return http.createServer((request, response) => {
    const url = new URL(request.url || '/', 'http://127.0.0.1');
    if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
      return textResponse(response, pageHtml);
    }
    return textResponse(response, 'Not found', 404, 'text/plain; charset=utf-8');
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
        // Keep scanning package-manager injected node_modules paths.
      }
    }
    throw directImportError;
  }
}

async function injectStressState(page) {
  await page.evaluate(() => {
    const longPath = '/Users/kws/source/android/FixThis/sample/src/main/java/io/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt:63:VeryLongComposableNameWithoutNaturalBreakpoints'.repeat(2);
    const stalenessBanner = document.getElementById('stalenessBanner');
    stalenessBanner.hidden = false;
    stalenessBanner.dataset.severity = 'critical';
    stalenessBanner.querySelector('[data-headline]').textContent = 'Build output is stale';
    stalenessBanner.querySelector('[data-detail]').textContent = longPath;

    const connectionCard = document.getElementById('connectionCard');
    connectionCard.dataset.connectionState = 'reconnect';
    document.getElementById('connectionHeadline').textContent = 'Connection paused';
    document.getElementById('connectionMessage').textContent = 'Could not connect to FixThis bridge while preserving the current annotation workspace.';
    document.getElementById('connectionDetails').open = true;
    document.getElementById('connectionDetailsBody').textContent =
      'Bridge timed out while reading current screen. Raw detail: ' + longPath;

    document.getElementById('deviceName').textContent = 'Pixel Fold Extremely Long Device Name';
    document.getElementById('deviceConnectionState').textContent = 'Unavailable because bridge timed out';
    const status = document.getElementById('error');
    status.hidden = false;
    status.className = 'global-status status-warning';
    status.textContent =
      'Copied, but MCP handoff status was not updated. Copy again after the connection recovers to update item lifecycle metadata. ' + longPath;

    document.getElementById('pendingItems').innerHTML =
      '<div class="activity-drift-warning" role="status" aria-live="polite" data-activity-drift>' +
        '<div class="activity-drift-warning-body">' +
          '<div class="activity-drift-warning-title">Activity changed during freeze</div>' +
          '<div class="activity-drift-warning-detail">Frozen: io.beyondwin.fixthis.sample.MainActivity · Now: io.beyondwin.fixthis.sample.DeepLinkReviewActivityWithLongName</div>' +
        '</div>' +
        '<button type="button" class="activity-drift-warning-button" data-activity-drift-restart>Start new freeze</button>' +
      '</div>';

    document.getElementById('draftItems').innerHTML =
      '<div class="ann-list">' +
        '<button type="button" class="ann-row saved-item-row" data-phase="in_progress">' +
          '<span class="ann-row-num">1</span><span class="ann-row-body"><span class="ann-row-title">Agent working</span><span class="ann-row-comment">Agent note should wrap and remain readable.</span></span><span class="ann-row-status st-in-progress">In Progress</span>' +
        '</button>' +
        '<button type="button" class="ann-row saved-item-row" data-phase="needs_clarification">' +
          '<span class="ann-row-num">2</span><span class="ann-row-body"><span class="ann-row-title">Question for user</span><span class="ann-row-comment">Needs clarification should not look resolved.</span></span><span class="ann-row-status st-needs-clarification">Needs Clarification</span>' +
        '</button>' +
        '<button type="button" class="ann-row saved-item-row" data-phase="wont_fix">' +
          "<span class=\"ann-row-num\">3</span><span class=\"ann-row-body\"><span class=\"ann-row-title\">Won't fix</span><span class=\"ann-row-comment\">Terminal but not successful.</span></span><span class=\"ann-row-status st-wont-fix\">Won't Fix</span>" +
        '</button>' +
      '</div>' +
      '<div class="annotation-detail" data-phase="resolved">' +
        '<div class="annotation-banner annotation-banner-done">' +
          '<div>Agent completed</div>' +
          '<pre class="annotation-summary">' + longPath + '</pre>' +
        '</div>' +
      '</div>';
  });
}

async function assertNoHorizontalOverflow(page, viewportName) {
  const failures = await page.evaluate(() => {
    const selectors = [
      'html',
      'body',
      '.studio-shell',
      '.studio-topbar',
      '.studio-context',
      '.studio-actions',
      '.studio-body',
      '#error',
      '#connectionDetailsBody',
      '#stalenessBanner',
      '.annotation-summary',
      '.activity-drift-warning',
    ];
    return selectors.flatMap(selector => Array.from(document.querySelectorAll(selector)).map(element => {
      const overflow = element.scrollWidth - element.clientWidth;
      return {
        selector,
        overflow,
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
      };
    })).filter(result => result.overflow > 1);
  });
  assert.deepEqual(failures, [], `${viewportName} has horizontal overflow`);
}

async function run(baseUrl) {
  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  try {
    for (const viewport of viewports) {
      const page = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height } });
      await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
      await injectStressState(page);
      await page.screenshot({
        path: resolve(outputDir, `fixthis-responsive-stress-${viewport.name}.png`),
        fullPage: true,
      });
      await assertNoHorizontalOverflow(page, viewport.name);
      await page.close();
    }
  } finally {
    await browser.close();
  }
}

const server = createServer();
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const { port } = server.address();
try {
  await run(`http://127.0.0.1:${port}/`);
  console.log('Console responsive stress passed.');
} catch (error) {
  console.error(`CONSOLE_RESPONSIVE_STRESS_FAILED: ${error?.message || error}`);
  process.exitCode = 1;
} finally {
  await new Promise(resolve => server.close(resolve));
}
