#!/usr/bin/env node
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';

async function main() {
  const fixture = await startFakeBridge({ scenario: 'first-run-ready' });
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const consoleErrors = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    page.on('pageerror', (error) => consoleErrors.push(error.message));
    await page.goto(fixture.url, { waitUntil: 'domcontentloaded' });
    // Connection card uses `display: none` for the ready state, so wait for it
    // to be attached rather than visually visible.
    await page.waitForSelector('[data-testid="connection-card"][data-connection-state="ready"]', {
      timeout: 5000,
      state: 'attached',
    });
    // Enter annotate mode and let the snapshot frame finish loading the preview
    // image before we attempt to drag a selection on it.
    await page.click('#annotateToolButton');
    await page.waitForSelector('#snapshot[data-tool-mode="annotate"]');
    await page.waitForFunction(() => {
      const image = document.getElementById('snapshotImage');
      return image && image.complete && image.naturalWidth > 0 && image.naturalHeight > 0 &&
        document.getElementById('annotateToolButton')?.disabled === false;
    }, { timeout: 10000 });
    const imageBox = await page.locator('#snapshotImage').boundingBox();
    assert.ok(imageBox, 'expected preview image to have a bounding box');
    assert.ok(imageBox.width > 20 && imageBox.height > 20, `expected preview image to have non-trivial size, got ${imageBox.width}x${imageBox.height}`);
    const startX = imageBox.x + imageBox.width * 0.25;
    const startY = imageBox.y + imageBox.height * 0.25;
    const endX = imageBox.x + imageBox.width * 0.6;
    const endY = imageBox.y + imageBox.height * 0.6;
    await page.mouse.move(startX, startY);
    await page.mouse.down();
    await page.mouse.move(endX, endY, { steps: 5 });
    await page.mouse.up();
    // The drag creates a pending annotation pin and reveals the per-item comment
    // editor (#annotationCommentInput) for the newly added draft item.
    await page.waitForFunction(
      () => document.querySelectorAll('.selection-box.annotation-pin').length === 1,
      { timeout: 10000 },
    );
    await page.waitForSelector('#annotationCommentInput');
    await page.fill('#annotationCommentInput', 'Make the primary button label clearer');
    // Save-to-MCP button starts `disabled` in index.html; once the draft has a
    // comment, prompt-readiness should advance and the save button should
    // enable.
    await page.waitForSelector('[data-testid="save-to-mcp-button"]:not([disabled])', { timeout: 5000 });
    const handoffResponse = page.waitForResponse((response) =>
      response.url().includes('/api/agent-handoffs') && response.request().method() === 'POST',
      { timeout: 10000 },
    );
    await page.click('[data-testid="save-to-mcp-button"]');
    await handoffResponse;
    const sessionAfterHandoff = await page.evaluate(async () => {
      const response = await fetch('/api/session');
      if (!response.ok) throw new Error(`session read failed: ${response.status}`);
      return response.json();
    });
    assert.equal(sessionAfterHandoff.items.length, 1);
    assert.equal(sessionAfterHandoff.items[0].delivery, 'sent');
    assert.match(sessionAfterHandoff.items[0].comment, /primary button label/);
    await page.waitForFunction(() => {
      const el = document.querySelector('[data-testid="prompt-readiness"]');
      return el && !/no annotations ready/i.test(el.textContent || '');
    }, { timeout: 5000 });

    const handoffs = fixture.getRequestLog().filter((entry) => entry.path === '/api/agent-handoffs');
    assert.equal(handoffs.length, 1);
    assert.deepEqual(consoleErrors, []);
    console.log('First-run smoke passed.');
  } finally {
    await browser.close();
    await fixture.close();
  }
}

main().catch((error) => {
  console.error(error?.stack || error);
  process.exit(1);
});
