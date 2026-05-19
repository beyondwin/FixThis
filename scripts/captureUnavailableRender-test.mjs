import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

function createElement(id) {
  const attributes = {};
  return {
    id,
    dataset: {},
    hidden: false,
    textContent: '',
    title: '',
    attributes,
    classList: { add() {}, remove() {}, toggle() {} },
    setAttribute(name, value) { attributes[name] = String(value); },
  };
}

function createDocument(ids) {
  const elements = new Map();
  ids.forEach((id) => elements.set(id, createElement(id)));
  return {
    getElementById(id) {
      return elements.get(id) || null;
    },
  };
}

function loadApplyPreviewReadiness(document) {
  return loadConsoleSymbols({
    modules: ['preview.js'],
    symbols: ['applyPreviewReadinessToConnectionCard'],
    args: ['document'],
    values: [document],
  });
}

test('preview.previewAvailable === false renders readiness slot with Retry capture CTA', () => {
  const document = createDocument(['connectionReadiness', 'connectionPrimaryAction']);
  const { applyPreviewReadinessToConnectionCard } = loadApplyPreviewReadiness(document);

  applyPreviewReadinessToConnectionCard({
    previewId: 'preview-1',
    previewAvailable: false,
    readiness: {
      state: 'CAPTURE_UNAVAILABLE',
      cause: 'Capture returned semantics with no screenshot bytes.',
      verify: 'Open the app foreground and tap Capture, or open doctor for the bridge log.',
      fix: 'Reopen the app foreground and tap Retry capture, or open doctor for the bridge log.',
      nextAction: 'Retry capture',
      details: {},
    },
    screen: { screenId: 'screen-1' },
  });

  const slot = document.getElementById('connectionReadiness');
  assert.equal(slot.hidden, false);
  assert.equal(slot.dataset.state, 'CAPTURE_UNAVAILABLE');
  assert.match(slot.textContent, /Capture returned semantics with no screenshot bytes\./);
  assert.match(slot.textContent, /Open the app foreground and tap Capture/);

  const button = document.getElementById('connectionPrimaryAction');
  assert.equal(button.textContent, 'Retry capture');
  assert.equal(button.attributes['aria-label'], 'Retry capture');
  assert.equal(button.dataset.connectionAction, 'CAPTURE');
});

test('preview.previewAvailable === true hides the readiness slot and restores Capture label', () => {
  const document = createDocument(['connectionReadiness', 'connectionPrimaryAction']);
  // Seed the elements as if a prior CAPTURE_UNAVAILABLE render had run.
  const slot = document.getElementById('connectionReadiness');
  slot.hidden = false;
  slot.dataset.state = 'CAPTURE_UNAVAILABLE';
  slot.textContent = 'leftover';
  const button = document.getElementById('connectionPrimaryAction');
  button.textContent = 'Retry capture';
  button.dataset.connectionAction = 'CAPTURE';

  const { applyPreviewReadinessToConnectionCard } = loadApplyPreviewReadiness(document);
  applyPreviewReadinessToConnectionCard({
    previewId: 'preview-2',
    previewAvailable: true,
    screen: { screenId: 'screen-2' },
  });

  assert.equal(slot.hidden, true);
  assert.equal(slot.textContent, '');
  // The button label is restored to the default Capture label when previewAvailable
  // recovers; the connection card's renderConnection owns the live label otherwise.
  assert.equal(button.textContent, 'Capture screen');
  assert.equal(button.attributes['aria-label'], 'Capture screen');
});
