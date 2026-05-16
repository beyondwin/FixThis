import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function source(file) {
  return readFileSync(resolve(root, file), 'utf8');
}

test('surface modules route named status surfaces through the registry', () => {
  const consoleApp = source('fixthis-mcp/src/main/console/consoleApp.js');
  const state = source('fixthis-mcp/src/main/console/state.js');
  const staleness = source('fixthis-mcp/src/main/console/staleness.js');
  const connection = source('fixthis-mcp/src/main/console/connection.js');
  const preview = source('fixthis-mcp/src/main/console/preview.js');
  const previewRegion = source('fixthis-mcp/src/main/console/presentation/previewRegionView.js');
  const boundary = source('fixthis-mcp/src/main/console/boundaryDialogVariants.js');
  const pendingRecoveryUi = source('fixthis-mcp/src/main/console/pendingRecoveryUi.js');

  assert.match(consoleApp, /@requires .*statusSurfaceRegistry\.js/);
  assert.match(consoleApp, /const statusSurfaceRegistry = createStatusSurfaceRegistry\(\)/);
  assert.match(consoleApp, /window\.__statusSurfaceRegistry = statusSurfaceRegistry/);

  assert.match(staleness, /statusSurfaceRegistry\.show\('stalenessBanner'/);
  assert.match(staleness, /statusSurfaceRegistry\.hide\('stalenessBanner'\)/);

  assert.match(connection, /statusSurfaceRegistry\.show\('previewStaleBadge'/);
  assert.match(connection, /statusSurfaceRegistry\.hide\('previewStaleBadge'\)/);

  assert.match(preview, /statusSurfaceRegistry\.show\('canvasBlockedOverlay'/);
  assert.match(preview, /statusSurfaceRegistry\.hide\('canvasBlockedOverlay'\)/);
  assert.match(preview, /statusSurfaceRegistry\.show\('canvasStaleNotice'/);
  assert.match(preview, /statusSurfaceRegistry\.hide\('canvasStaleNotice'\)/);

  assert.match(previewRegion, /statusSurfaceRegistry\.show\('draftLockBar'/);
  assert.match(previewRegion, /statusSurfaceRegistry\.hide\('draftLockBar'\)/);

  assert.match(boundary, /statusSurfaceRegistry\.show\('sessionBoundarySheet'/);
  assert.match(pendingRecoveryUi, /statusSurfaceRegistry\.hide\('sessionBoundarySheet'\)/);

  assert.match(state, /notificationCenter\.notify\(\{[\s\S]*?dedupeKey: 'global-error'/);
  assert.match(state, /notificationCenter\.hide\('global-error'\)/);
});

test('registry still suspends inline surfaces when a canvas modal is active', () => {
  const elements = new Map();
  function createElement() {
    return {
      children: [],
      dataset: {},
      hidden: true,
      textContent: '',
      appendChild(child) {
        this.children.push(child);
        child.parentNode = this;
      },
      remove() {},
    };
  }
  ['canvasBlockedOverlay', 'canvasStaleNotice', 'toastContainer'].forEach((id) => elements.set(id, createElement()));
  const document = {
    createElement,
    getElementById(id) {
      return elements.get(id) || null;
    },
  };
  const { createStatusSurfaceRegistry } = loadConsoleSymbols({
    modules: ['statusSurfaceRegistry.js'],
    symbols: ['createStatusSurfaceRegistry'],
    args: ['document'],
    values: [document],
  });
  const registry = createStatusSurfaceRegistry({ document });

  registry.show('canvasBlockedOverlay', {
    surfaceClass: 'modalCanvas',
    priority: 1,
    element: document.getElementById('canvasBlockedOverlay'),
    content: 'Device disconnected',
  });
  registry.show('canvasStaleNotice', {
    surfaceClass: 'inline',
    priority: 3,
    element: document.getElementById('canvasStaleNotice'),
    content: 'stale',
  });

  assert.equal(document.getElementById('canvasBlockedOverlay').hidden, false);
  assert.equal(document.getElementById('canvasStaleNotice').hidden, true);
});
