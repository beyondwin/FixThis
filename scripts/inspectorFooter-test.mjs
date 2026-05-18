import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

class FakeElement {
  constructor(tagName) {
    this.tagName = tagName;
    this.attributes = {};
    this.children = [];
    this.className = '';
    this.dataset = {};
    this.hidden = false;
    this.textContent = '';
    this.type = '';
  }

  replaceChildren(...children) {
    this.children = children;
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
  }

  querySelector(selector) {
    const action = selector.match(/^\[data-action="([^"]+)"\]$/)?.[1];
    if (!action) return null;
    return this.children.find((child) => child.dataset?.action === action) || null;
  }
}

function createDocument() {
  const footer = new FakeElement('div');
  footer.hidden = true;
  return {
    createElement(tagName) {
      return new FakeElement(tagName);
    },
    getElementById(id) {
      return id === 'inspectorFooter' ? footer : null;
    },
  };
}

const document = createDocument();
const { renderInspectorFooter } = loadConsoleSymbols({
  modules: ['inspectorFooter.js'],
  symbols: ['renderInspectorFooter'],
  args: ['document'],
  values: [document],
});

const footer = document.getElementById('inspectorFooter');

test('none hides the inspector footer and clears actions', () => {
  renderInspectorFooter('none', {});

  assert.equal(footer.hidden, true);
  assert.equal(footer.children.length, 0);
  assert.equal(footer.dataset.editorState, 'none');
});

test('pendingTarget renders cancel and add annotation', () => {
  renderInspectorFooter('pendingTarget', {});

  assert.equal(footer.hidden, false);
  assert.ok(footer.querySelector('[data-action="cancel"]'));
  assert.equal(footer.querySelector('[data-action="cancel"]').textContent, 'Cancel');
  assert.ok(footer.querySelector('[data-action="addAnnotation"]'));
  assert.equal(footer.querySelector('[data-action="addAnnotation"]').textContent, 'Add annotation');
});

test('draft hides the footer because annotations are created immediately', () => {
  renderInspectorFooter('draft', {});

  assert.equal(footer.hidden, true);
  assert.equal(footer.querySelector('[data-action="cancel"]'), null);
  assert.equal(footer.querySelector('[data-action="addAnnotation"]'), null);
});

test('pendingTarget rerender preserves add annotation button node', () => {
  renderInspectorFooter('pendingTarget', {});
  const first = footer.querySelector('[data-action="addAnnotation"]');

  renderInspectorFooter('pendingTarget', {});
  const second = footer.querySelector('[data-action="addAnnotation"]');

  assert.equal(second, first);
});

test('saved renders only delete annotation', () => {
  renderInspectorFooter('saved', { deletable: true, editable: true });

  assert.equal(footer.hidden, false);
  assert.equal(footer.querySelector('[data-action="cancel"]'), null);
  assert.equal(footer.querySelector('[data-action="addAnnotation"]'), null);
  assert.equal(footer.querySelector('[data-action="overflowToggle"]'), null);
  const del = footer.querySelector('[data-action="delete"]');
  assert.ok(del, 'expected delete button');
  assert.equal(del.textContent, 'Delete annotation');
  assert.equal(footer.querySelector('[data-action="done"]'), null);
  assert.equal(del.disabled, false);
});

test('saved disables delete when deletable is false', () => {
  renderInspectorFooter('saved', { deletable: false, editable: false });

  const del = footer.querySelector('[data-action="delete"]');
  assert.ok(del);
  assert.equal(del.disabled, true);
  assert.equal(footer.querySelector('[data-action="done"]'), null);
});
