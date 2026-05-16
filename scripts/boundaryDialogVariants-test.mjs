import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

function createDocument() {
  const title = { textContent: '' };
  const summary = { textContent: '' };
  const buttons = Array.from({ length: 4 }, () => ({
    className: '',
    dataset: {},
    hidden: false,
    textContent: '',
  }));
  const sheet = {
    hidden: true,
    querySelector(selector) {
      if (selector === '[data-boundary-title]') return title;
      if (selector === '[data-boundary-summary]') return summary;
      return null;
    },
    querySelectorAll(selector) {
      if (selector === '.session-boundary-actions [data-boundary-action]') return buttons;
      return [];
    },
  };
  return {
    buttons,
    summary,
    title,
    getElementById(id) {
      return id === 'sessionBoundarySheet' ? sheet : null;
    },
  };
}

const document = createDocument();
const { renderBoundaryDialog } = loadConsoleSymbols({
  modules: ['boundaryDialogVariants.js'],
  symbols: ['renderBoundaryDialog'],
  args: ['document'],
  values: [document],
});

function visibleLabels() {
  return document.buttons.filter((button) => !button.hidden).map((button) => button.textContent);
}

test('session switch dialog labels save, discard, and cancel explicitly', () => {
  renderBoundaryDialog('sessionSwitch', { currentSessionName: 'Login', targetSessionName: 'Profile' });

  assert.match(document.title.textContent, /Save draft before switching/);
  assert.match(document.summary.textContent, /Login/);
  assert.match(document.summary.textContent, /Profile/);
  assert.deepEqual(visibleLabels(), ['Cancel', 'Discard', 'Save and switch']);
});

test('session create summary names the unsaved draft consequence', () => {
  renderBoundaryDialog('sessionCreate', {});

  assert.match(document.summary.textContent, /unsaved draft/);
  assert.deepEqual(visibleLabels(), ['Cancel', 'Discard', 'Save and create']);
});

test('session delete title and summary include destructive scope', () => {
  renderBoundaryDialog('sessionDelete', { currentSessionName: 'Settings', annotationCount: 5, screenCount: 3 });

  assert.match(document.title.textContent, /Settings/);
  assert.match(document.summary.textContent, /5 annotations/);
  assert.match(document.summary.textContent, /3 screens/);
});

test('unknown boundary variants fail loudly', () => {
  assert.throws(() => renderBoundaryDialog('missingVariant', {}), /Unknown boundary variant/);
});

test('destructive utility dialogs label confirm and cancel explicitly', () => {
  renderBoundaryDialog('clearLocalDraft', { itemCount: 2 });
  assert.match(document.title.textContent, /Clear local draft/);
  assert.deepEqual(visibleLabels(), ['Cancel', 'Clear local draft']);

  renderBoundaryDialog('clearServerDrafts', { itemCount: 3 });
  assert.match(document.summary.textContent, /3 saved draft/);
  assert.deepEqual(visibleLabels(), ['Cancel', 'Delete drafts']);

  renderBoundaryDialog('fingerprintMismatch', {});
  assert.deepEqual(visibleLabels(), ['Cancel', 'Force save', 'Recapture']);
});

test('recapture recovered draft dialog labels recapture confirmation and remap copy', () => {
  renderBoundaryDialog('recaptureRecoveredDraft', {});

  assert.match(document.title.textContent, /Recapture recovered draft/);
  assert.match(document.summary.textContent, /remapped onto the latest app screen/);
  assert.deepEqual(visibleLabels(), ['Cancel', 'Recapture']);
});
