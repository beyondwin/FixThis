import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

function createElement(tagName = 'div') {
  return {
    children: [],
    className: '',
    dataset: {},
    hidden: true,
    parentNode: null,
    tagName,
    textContent: '',
    appendChild(child) {
      child.parentNode = this;
      this.children.push(child);
      return child;
    },
    remove() {
      if (!this.parentNode) return;
      this.parentNode.children = this.parentNode.children.filter((child) => child !== this);
      this.parentNode = null;
    },
  };
}

function createDocument() {
  const elements = new Map([
    ['surface-modal', createElement()],
    ['surface-overlay', createElement()],
    ['surface-inline', createElement()],
    ['surface-badge', createElement()],
    ['toastContainer', createElement()],
  ]);
  return {
    createElement,
    getElementById(id) {
      return elements.get(id) || null;
    },
  };
}

test('status surface registry coordinates modal exclusion and stack limits', () => {
  const document = createDocument();
  const { createStatusSurfaceRegistry } = loadConsoleSymbols({
    modules: ['statusSurfaceRegistry.js'],
    symbols: ['createStatusSurfaceRegistry'],
    args: ['document'],
    values: [document],
  });
  const registry = createStatusSurfaceRegistry({ document });
  const inline = document.getElementById('surface-inline');
  const modal = document.getElementById('surface-modal');

  registry.show('inline', {
    surfaceClass: 'inline',
    priority: 3,
    element: inline,
    content: 'inline notice',
  });
  assert.equal(inline.hidden, false);

  registry.show('modal', {
    surfaceClass: 'modal',
    priority: 1,
    element: modal,
    content: 'modal notice',
  });
  assert.equal(modal.hidden, false);
  assert.equal(inline.hidden, true);

  registry.hide('modal');
  assert.equal(inline.hidden, false);

  for (let i = 0; i < 5; i += 1) {
    registry.show('toast-' + i, {
      surfaceClass: 'toast',
      priority: 3,
      content: 'toast ' + i,
      autoDismissMs: 99999,
    });
  }
  assert.equal(registry.size('toast'), 3);
  assert.equal(document.getElementById('toastContainer').children.length, 3);

  registry.show('banner-1', { surfaceClass: 'banner', priority: 2, content: 'first' });
  registry.show('banner-2', { surfaceClass: 'banner', priority: 2, content: 'second' });
  assert.equal(registry.size('banner'), 1);
});
