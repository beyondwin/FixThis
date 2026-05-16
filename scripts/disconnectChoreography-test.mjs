import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

function createElement(id) {
  const slots = new Map();
  const element = {
    children: [],
    dataset: {},
    hidden: true,
    id,
    textContent: '',
    appendChild(child) {
      this.children.push(child);
      child.parentNode = this;
      return child;
    },
    querySelector(selector) {
      if (!slots.has(selector)) slots.set(selector, createElement(selector));
      return slots.get(selector);
    },
    remove() {},
    setAttribute() {},
  };
  return element;
}

function createDocument() {
  const elements = new Map();
  [
    'canvasBlockedOverlay',
    'canvasStaleNotice',
    'draftLockBar',
    'previewStaleBadge',
    'stalenessBanner',
    'toastContainer',
  ].forEach((id) => elements.set(id, createElement(id)));
  return {
    createElement,
    getElementById(id) {
      return elements.get(id) || null;
    },
  };
}

test('disconnect shows one canvas modal and preserves only dirty draft banner', () => {
  const document = createDocument();
  const { createStatusSurfaceRegistry } = loadConsoleSymbols({
    modules: ['statusSurfaceRegistry.js'],
    symbols: ['createStatusSurfaceRegistry'],
    args: ['document'],
    values: [document],
  });
  const statusSurfaceRegistry = createStatusSurfaceRegistry({ document });
  const { applyDisconnect, applyReconnect } = loadConsoleSymbols({
    modules: ['connection.js'],
    symbols: ['applyDisconnect', 'applyReconnect'],
    args: ['document', 'statusSurfaceRegistry'],
    values: [document, statusSurfaceRegistry],
  });

  statusSurfaceRegistry.show('canvasStaleNotice', {
    surfaceClass: 'inline',
    priority: 3,
    element: document.getElementById('canvasStaleNotice'),
    content: 'stale',
  });
  statusSurfaceRegistry.show('previewStaleBadge', {
    surfaceClass: 'badge',
    priority: 3,
    element: document.getElementById('previewStaleBadge'),
    content: 'paused',
  });

  applyDisconnect({ hasDirtyDraft: true });

  assert.equal(document.getElementById('canvasBlockedOverlay').hidden, false);
  assert.equal(document.getElementById('canvasStaleNotice').hidden, true);
  assert.equal(document.getElementById('previewStaleBadge').hidden, true);
  assert.equal(document.getElementById('stalenessBanner').hidden, false);
  assert.match(document.getElementById('stalenessBanner').textContent, /1 unsaved draft preserved locally/);

  applyReconnect({ targetStale: false });

  assert.equal(document.getElementById('canvasBlockedOverlay').hidden, true);
  assert.equal(document.getElementById('previewStaleBadge').hidden, false);
  assert.match(document.getElementById('previewStaleBadge').textContent, /Connection restored/);
});

test('disconnect without dirty draft hides the preserved-draft banner', () => {
  const document = createDocument();
  const { createStatusSurfaceRegistry } = loadConsoleSymbols({
    modules: ['statusSurfaceRegistry.js'],
    symbols: ['createStatusSurfaceRegistry'],
    args: ['document'],
    values: [document],
  });
  const statusSurfaceRegistry = createStatusSurfaceRegistry({ document });
  const { applyDisconnect } = loadConsoleSymbols({
    modules: ['connection.js'],
    symbols: ['applyDisconnect'],
    args: ['document', 'statusSurfaceRegistry'],
    values: [document, statusSurfaceRegistry],
  });

  statusSurfaceRegistry.show('stalenessBanner', {
    surfaceClass: 'banner',
    priority: 2,
    element: document.getElementById('stalenessBanner'),
    content: 'old',
  });
  applyDisconnect({ hasDirtyDraft: false });

  assert.equal(document.getElementById('canvasBlockedOverlay').hidden, false);
  assert.equal(document.getElementById('stalenessBanner').hidden, true);
});
