import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const css = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');

function cssRule(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = css.match(new RegExp(escaped + '\\s*\\{([^}]*)\\}', 'm'));
  assert.ok(match, `missing CSS rule: ${selector}`);
  return match[1];
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

function canvasFixture() {
  return `
    <!doctype html>
    <html>
      <head>
        <style>
          :root {
            --bg-0: #080a0d;
            --bg-1: #11151b;
            --bg-2: #181d25;
            --bg-3: #222936;
            --line: #2b313d;
            --txt-0: #f4f6f8;
            --txt-1: #c8cdd5;
            --txt-2: #8d95a2;
            --accent: #c9e56d;
          }
          * { box-sizing: border-box; }
          body { margin: 0; background: var(--bg-0); }
          ${css}
          .studio-canvas {
            width: 720px;
            height: 520px;
            border: 1px solid var(--line);
          }
          @media (max-width: 520px) {
            .studio-canvas {
              width: 390px;
              height: 520px;
            }
          }
        </style>
      </head>
      <body>
        <section class="studio-canvas">
          <div class="canvas-toolbar">
            <div class="tool-group"></div>
            <div class="tool-status">Annotate mode</div>
            <div class="zoom-control">100%</div>
          </div>
          <div id="draftLockBar" class="draft-lock-bar">
            Locked: Session d7de3e5a-95f1-4322-8d0a-2a760e4d4d82 · Preview 1f93165a-96d8-49c8-ac67-2a87a529e845
          </div>
          <span id="previewStaleBadge" class="preview-stale-badge">Connection paused - showing last preview</span>
          <div id="snapshot" class="snapshot-stage">
            <div id="annotateHintSlot" class="annotate-hint-slot" aria-live="polite">
              <div id="annotateHint" class="annotate-hint">Annotate mode</div>
            </div>
            <div id="snapshotFrame" class="snapshot-frame" data-mode="frozen">
              <span id="previewFrameStatus" class="preview-frame-status" data-state="frozen">Frozen for annotation</span>
              <svg id="snapshotImage" width="300" height="420" viewBox="0 0 300 420" role="img" aria-label="FixThis preview">
                <rect width="300" height="420" rx="28" fill="#f7faf8"></rect>
              </svg>
              <div id="selectionOverlay" class="selection-overlay"></div>
            </div>
          </div>
        </section>
      </body>
    </html>
  `;
}

async function measure(page, viewport) {
  await page.setViewportSize(viewport);
  await page.setContent(canvasFixture(), { waitUntil: 'domcontentloaded' });
  return await page.evaluate(() => {
    const lock = document.getElementById('draftLockBar').getBoundingClientRect();
    const stale = document.getElementById('previewStaleBadge').getBoundingClientRect();
    const snapshot = document.getElementById('snapshot').getBoundingClientRect();
    const overlaps = !(
      stale.right <= lock.left + 1 ||
      stale.left >= lock.right - 1 ||
      stale.bottom <= lock.top + 1 ||
      stale.top >= lock.bottom - 1
    );
    return {
      overlaps,
      staleBelowLock: stale.top >= lock.bottom - 1,
      snapshotBelowStale: snapshot.top >= stale.bottom - 1,
      staleOverflow: document.getElementById('previewStaleBadge').scrollWidth -
        document.getElementById('previewStaleBadge').clientWidth,
    };
  });
}

async function measureAnnotateHintGap(page, zoom) {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.setContent(canvasFixture(), { waitUntil: 'domcontentloaded' });
  return await page.evaluate((value) => {
    const frame = document.getElementById('snapshotFrame');
    frame.style.setProperty('--preview-zoom', String(value));
    const hint = document.getElementById('annotateHint').getBoundingClientRect();
    const preview = frame.getBoundingClientRect();
    return Math.round(preview.top - hint.bottom);
  }, zoom);
}

async function measureDefaultPreviewScale(page, viewport) {
  await page.setViewportSize(viewport);
  await page.setContent(canvasFixture(), { waitUntil: 'domcontentloaded' });
  return await page.evaluate(() => {
    const transform = getComputedStyle(document.getElementById('snapshotFrame')).transform;
    if (transform === 'none') return 1;
    return Number(transform.match(/matrix\(([^,]+)/)?.[1] || '1');
  });
}

async function measureDraftLockToAnnotateGap(page) {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.setContent(canvasFixture(), { waitUntil: 'domcontentloaded' });
  return await page.evaluate(() => {
    document.getElementById('previewStaleBadge').hidden = true;
    const lock = document.getElementById('draftLockBar').getBoundingClientRect();
    const hint = document.getElementById('annotateHint').getBoundingClientRect();
    return Math.round(hint.top - lock.bottom);
  });
}

async function measureDraftLockPadding(page, viewport) {
  await page.setViewportSize(viewport);
  await page.setContent(canvasFixture(), { waitUntil: 'domcontentloaded' });
  return await page.evaluate(() => {
    const style = getComputedStyle(document.getElementById('draftLockBar'));
    return {
      paddingTop: style.paddingTop,
      paddingBottom: style.paddingBottom,
    };
  });
}

test('canvas stale preview badge does not overlap draft lock banner', async () => {
  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  try {
    for (const viewport of [
      { width: 1280, height: 800 },
      { width: 390, height: 844 },
    ]) {
      const page = await browser.newPage();
      try {
        const result = await measure(page, viewport);
        assert.deepEqual(
          Object.fromEntries(Object.entries(result).filter(([key, value]) => {
            if (key === 'overlaps') return value === true;
            if (key === 'staleBelowLock') return value !== true;
            if (key === 'snapshotBelowStale') return value !== true;
            if (typeof value === 'number') return value > 1;
            return true;
          })),
          {},
          `bad canvas badge layout at ${viewport.width}x${viewport.height}`,
        );
      } finally {
        await page.close();
      }
    }
  } finally {
    await browser.close();
  }
});

test('preview zoom anchors to the top edge so annotate badge gap does not expand', () => {
  assert.match(cssRule('.snapshot-frame'), /transform-origin:\s*center top;/);
});

test('desktop canvas toolbar uses compact vertical padding', () => {
  assert.match(cssRule('.canvas-toolbar'), /padding:\s*5\.5px 16px;/);
});

test('mobile draft lock banner keeps vertical breathing room', async () => {
  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    try {
      const padding = await measureDraftLockPadding(page, { width: 390, height: 844 });

      assert.deepEqual(padding, { paddingTop: '4px', paddingBottom: '4px' });
    } finally {
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('desktop default preview zoom renders at 95 percent visual scale', async () => {
  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    try {
      const scale = await measureDefaultPreviewScale(page, { width: 1280, height: 800 });

      assert.equal(scale, 0.95);
    } finally {
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('mobile default preview zoom keeps full visual scale', async () => {
  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    try {
      const scale = await measureDefaultPreviewScale(page, { width: 390, height: 844 });

      assert.equal(scale, 1);
    } finally {
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('draft lock banner sits close to annotate mode badge', async () => {
  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    try {
      const gap = await measureDraftLockToAnnotateGap(page);

      assert.ok(gap <= 10, `expected draft lock to annotate gap <= 10px, got ${gap}px`);
    } finally {
      await page.close();
    }
  } finally {
    await browser.close();
  }
});

test('annotate mode badge keeps the same visual gap when preview is zoomed out', async () => {
  const { chromium } = await loadPlaywright();
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    try {
      const normalGap = await measureAnnotateHintGap(page, 1);
      const zoomedOutGap = await measureAnnotateHintGap(page, 0.8);

      assert.equal(zoomedOutGap, normalGap);
    } finally {
      await page.close();
    }
  } finally {
    await browser.close();
  }
});
