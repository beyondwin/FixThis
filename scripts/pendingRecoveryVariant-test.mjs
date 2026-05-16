import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

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
    dataset: {},
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
    sheet,
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

function visibleButtons() {
  return document.buttons.filter((button) => !button.hidden);
}

test('pending recovery dialog labels resume, discard, and recapture explicitly', () => {
  renderBoundaryDialog('pendingRecovery', { canResume: true, itemCount: 2 });

  assert.equal(document.sheet.hidden, false);
  assert.match(document.title.textContent, /Recover unsaved draft/);
  assert.match(document.summary.textContent, /2 annotations preserved/);
  assert.deepEqual(visibleButtons().map((button) => button.textContent), [
    'Cancel',
    'Discard',
    'Recapture',
    'Resume draft',
  ]);
  assert.deepEqual(visibleButtons().map((button) => button.dataset.boundaryAction), [
    'cancel',
    'discard',
    'recapture',
    'resume',
  ]);
});

test('pending recovery prompt is rendered through boundary dialog actions', () => {
  const pendingRecoveryUi = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pendingRecoveryUi.js'), 'utf8');

  assert.match(pendingRecoveryUi, /renderBoundaryDialog\('pendingRecovery'/);
  assert.doesNotMatch(pendingRecoveryUi, /data-resume-pending|data-recapture-pending|data-clear-pending/);
});
