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

    document.getElementById('sessionCount').textContent = '2';
    document.getElementById('sessions').innerHTML =
      '<div class="history-item session-row" role="button" tabindex="0" data-session-id="session-1">' +
        '<span class="hi-head">' +
          '<span class="hi-title">Session 1</span>' +
          '<button type="button" class="hi-del" aria-label="Delete history item Session 1">x</button>' +
        '</span>' +
        '<span class="hi-meta">1 screen · May 13 · 21:06</span>' +
        '<span class="hi-stats"><span class="hi-pip open">1 open</span></span>' +
        '<span class="hi-strip"><span class="hi-strip-cell"></span></span>' +
      '</div>' +
      '<div class="history-item session-row is-active" role="button" tabindex="0" data-session-id="session-2">' +
        '<span class="hi-head">' +
          '<span class="hi-title">Session 2</span>' +
          '<button type="button" class="hi-del" aria-label="Delete history item Session 2">x</button>' +
        '</span>' +
        '<span class="hi-meta">1 screen · May 13 · 21:06</span>' +
        '<span class="hi-stats"><span class="hi-pip open">2 open</span><span class="hi-pip done">0 resolved</span></span>' +
        '<span class="hi-strip"><span class="hi-strip-cell"></span><span class="hi-strip-cell"></span></span>' +
      '</div>' +
      '<button type="button" class="history-item history-add-row" aria-label="Start annotating">' +
        '<svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14"/><path d="M5 12h14"/></svg>' +
      '</button>';

    const previewSvg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="392" height="852" viewBox="0 0 392 852">' +
        '<rect width="392" height="852" fill="#f7faf8"/>' +
        '<text x="48" y="96" font-size="28" font-family="Arial" fill="#10201c">FixThis Studio</text>' +
        '<rect x="32" y="140" width="328" height="128" rx="12" fill="#ffffff"/>' +
        '<rect x="32" y="292" width="328" height="128" rx="12" fill="#ffffff"/>' +
        '<rect x="32" y="444" width="328" height="128" rx="12" fill="#ffffff"/>' +
      '</svg>';
    document.getElementById('snapshot').innerHTML =
      '<div id="snapshotFrame" class="snapshot-frame" data-mode="frozen">' +
        '<img id="snapshotImage" alt="FixThis preview" aria-label="FixThis preview" src="data:image/svg+xml,' + encodeURIComponent(previewSvg) + '">' +
        '<div id="selectionOverlay" class="selection-overlay"></div>' +
      '</div>';

    document.getElementById('pendingItems').innerHTML =
      '<div id="pendingRecoveryBanner" class="annotation-banner annotation-banner-warn pending-recovery-banner" role="status" aria-live="polite">' +
        '<div class="pending-recovery-copy" data-pending-recovery-copy>' +
          '<strong>Unsaved 1 annotation found</strong>' +
          '<div>Recover restores the frozen preview and pins from this session.</div>' +
        '</div>' +
        '<div class="annotation-actions pending-recovery-actions">' +
          '<button type="button" class="annotation-done">Recover</button>' +
          '<button type="button" class="annotation-done">Recapture</button>' +
          '<button type="button" class="annotation-danger">Discard</button>' +
        '</div>' +
      '</div>' +
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

async function assertHistoryRowsAreStacked(page, viewportName) {
  const failures = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.session-row')).map(element => {
      const style = getComputedStyle(element);
      const childBottom = Math.max(...Array.from(element.children).map(child => child.getBoundingClientRect().bottom));
      const rowBottom = element.getBoundingClientRect().bottom;
      return {
        display: style.display,
        clippedBy: childBottom - rowBottom,
        text: element.querySelector('.hi-title')?.textContent || '',
      };
    }).filter(result => result.display !== 'grid' || result.clippedBy > 1)
  );
  assert.deepEqual(failures, [], `${viewportName} has compressed history rows`);

  const activeRowClipping = await page.evaluate(() => {
    if (window.innerWidth > 900) return null;
    const activeRow = document.querySelector('.session-row.is-active');
    const history = document.querySelector('.studio-history');
    if (!activeRow || !history) return null;
    if (getComputedStyle(history).display === 'none') return null;
    return activeRow.getBoundingClientRect().bottom - history.getBoundingClientRect().bottom;
  });
  assert.ok(activeRowClipping == null || activeRowClipping <= 1, `${viewportName} clips the active history row`);
}

async function assertPreviewFrameMatchesImage(page, viewportName) {
  await page.locator('#snapshotImage').waitFor({ state: 'visible' });
  await page.waitForFunction(() => {
    const image = document.getElementById('snapshotImage');
    return image?.complete && image.naturalWidth > 0 && image.naturalHeight > 0;
  });
  const mismatch = await page.evaluate(() => {
    const frame = document.getElementById('snapshotFrame');
    const image = document.getElementById('snapshotImage');
    const overlay = document.getElementById('selectionOverlay');
    if (!frame || !image || !overlay) return { missing: true };
    const frameBox = frame.getBoundingClientRect();
    const imageBox = image.getBoundingClientRect();
    const overlayBox = overlay.getBoundingClientRect();
    return {
      frameImageWidthDelta: Math.abs(frameBox.width - imageBox.width),
      frameImageHeightDelta: Math.abs(frameBox.height - imageBox.height),
      overlayImageWidthDelta: Math.abs(overlayBox.width - imageBox.width),
      overlayImageHeightDelta: Math.abs(overlayBox.height - imageBox.height),
    };
  });
  assert.deepEqual(
    Object.fromEntries(Object.entries(mismatch).filter(([, value]) => value > 1 || value === true)),
    {},
    `${viewportName} preview frame does not match image bounds`,
  );
}

async function assertPendingRecoveryBannerIsReadable(page, viewportName) {
  const failure = await page.evaluate(() => {
    const banner = document.getElementById('pendingRecoveryBanner');
    const text = banner?.querySelector('[data-pending-recovery-copy]') || banner?.firstElementChild;
    const actions = banner?.querySelector('.annotation-actions');
    if (!banner || !text || !actions) return { missing: true };
    const textBox = text.getBoundingClientRect();
    const actionsBox = actions.getBoundingClientRect();
    const bannerBox = banner.getBoundingClientRect();
    const actionButtons = Array.from(actions.querySelectorAll('button'));
    const clippedButtons = actionButtons.filter(button => {
      const box = button.getBoundingClientRect();
      return box.left < bannerBox.left - 1 || box.right > bannerBox.right + 1;
    }).map(button => button.textContent);
    return {
      stacked: actionsBox.top >= textBox.bottom - 1,
      clippedButtons,
      textOverflow: text.scrollWidth - text.clientWidth,
      actionsOverflow: actions.scrollWidth - actions.clientWidth,
    };
  });
  assert.deepEqual(
    Object.fromEntries(Object.entries(failure).filter(([key, value]) => {
      if (key === 'missing') return value === true;
      if (Array.isArray(value)) return value.length > 0;
      if (typeof value === 'number') return value > 1;
      if (typeof value === 'boolean') return value === false;
      return true;
    })),
    {},
    `${viewportName} pending recovery banner is cramped`,
  );
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
      await assertHistoryRowsAreStacked(page, viewport.name);
      await assertPreviewFrameMatchesImage(page, viewport.name);
      await assertPendingRecoveryBannerIsReadable(page, viewport.name);
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
