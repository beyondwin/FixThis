import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

function createDocument() {
  const editorBack = { hidden: true };
  return {
    getElementById(id) {
      return id === 'editorBack' ? editorBack : null;
    },
  };
}

const events = [];
const ports = {
  showToast(text, options) {
    events.push({ kind: 'toast', text, options });
  },
  openBoundaryDialog(variant, context) {
    events.push({ kind: 'dialog', variant, context });
  },
  navigateToList() {
    events.push({ kind: 'nav' });
  },
  silentDiscard(context) {
    events.push({ kind: 'discard', context });
  },
};

const document = createDocument();
const { renderEditorBack, handleEditorBackClick } = loadConsoleSymbols({
  modules: ['editorBackButton.js'],
  symbols: ['renderEditorBack', 'handleEditorBackClick'],
  args: ['document'],
  values: [document],
});

function resetEvents() {
  events.length = 0;
}

test('renderEditorBack hides none and draft list states', () => {
  const editorBack = document.getElementById('editorBack');

  renderEditorBack({ state: 'none' });
  assert.equal(editorBack.hidden, true);

  renderEditorBack({ state: 'pendingTarget' });
  assert.equal(editorBack.hidden, false);

  renderEditorBack({ state: 'draft' });
  assert.equal(editorBack.hidden, true);

  renderEditorBack({ state: 'saved' });
  assert.equal(editorBack.hidden, false);
});

test('pending target back discards, toasts, and navigates', () => {
  resetEvents();

  handleEditorBackClick({ state: 'pendingTarget' }, ports);

  assert.ok(events.find((event) => event.kind === 'discard'));
  assert.ok(events.find((event) => event.kind === 'toast' && event.text === 'Cancelled'));
  assert.ok(events.find((event) => event.kind === 'nav'));
});

test('draft back opens the editorBack boundary dialog without navigation', () => {
  resetEvents();

  handleEditorBackClick({ state: 'draft' }, ports);

  assert.ok(events.find((event) => event.kind === 'dialog' && event.variant === 'editorBack'));
  assert.equal(events.find((event) => event.kind === 'nav'), undefined);
});

test('saved back navigates immediately', () => {
  resetEvents();

  handleEditorBackClick({ state: 'saved' }, ports);

  assert.ok(events.find((event) => event.kind === 'nav'));
});
