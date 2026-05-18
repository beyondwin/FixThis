import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const styles = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');

function createDocument() {
  const title = { textContent: '' };
  const summary = { textContent: '' };
  const buttons = Array.from({ length: 4 }, () => {
    const button = {
      className: '',
      hidden: false,
      textContent: '',
      _hasBoundaryAction: true,
    };
    button.dataset = new Proxy({}, {
      set(target, key, value) {
        target[key] = value;
        if (key === 'boundaryAction') button._hasBoundaryAction = true;
        return true;
      },
      deleteProperty(target, key) {
        delete target[key];
        if (key === 'boundaryAction') button._hasBoundaryAction = false;
        return true;
      },
      get(target, key) {
        return target[key];
      },
    });
    return button;
  });
  const sheet = {
    hidden: true,
    querySelector(selector) {
      if (selector === '[data-boundary-title]') return title;
      if (selector === '[data-boundary-summary]') return summary;
      return null;
    },
    querySelectorAll(selector) {
      if (selector === '.session-boundary-actions [data-boundary-action]') {
        return buttons.filter((button) => button._hasBoundaryAction);
      }
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
  assert.deepEqual(visibleLabels(), ['Cancel', 'Delete session']);
});

test('unknown boundary variants fail loudly', () => {
  assert.throws(() => renderBoundaryDialog('missingVariant', {}), /Unknown boundary variant/);
});

test('three-button dialog still renders all buttons after a two-button dialog', () => {
  // forgetDevice has only cancel + primary, leaving the middle two slots null.
  // Previously the renderer removed [data-boundary-action] from those buttons,
  // so the next render's querySelectorAll returned a shortened button list and
  // sessionSwitch's "Discard" / "Save and switch" never reached the DOM.
  renderBoundaryDialog('forgetDevice', { deviceName: 'Pixel' });
  renderBoundaryDialog('sessionSwitch', { currentSessionName: 'Session 1', targetSessionName: 'Session 2' });

  assert.deepEqual(visibleLabels(), ['Cancel', 'Discard', 'Save and switch']);
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

test('staleDraftConflict dialog explains a local saved-draft conflict without server wording', () => {
  renderBoundaryDialog('staleDraftConflict', {});

  assert.match(document.title.textContent, /saved draft changed/i);
  assert.match(document.summary.textContent, /another FixThis console/i);
  assert.doesNotMatch(document.title.textContent, /server/i);
  assert.doesNotMatch(document.summary.textContent, /server/i);
  // visibleLabels reads cancel -> tertiary -> secondary -> primary.
  // Cancel, Load saved draft, Overwrite saved draft.
  assert.deepEqual(visibleLabels(), ['Keep editing', 'Load saved draft', 'Overwrite saved draft']);
});

test('session boundary actions keep desktop buttons on one line', () => {
  assert.match(styles, /\.session-boundary-actions\s*\{[\s\S]*?flex-wrap:\s*nowrap;/);
  assert.match(styles, /\.session-boundary-actions \[data-boundary-action\]\s*\{[\s\S]*?white-space:\s*nowrap;/);
  assert.match(styles, /\.session-boundary-dialog\s*\{[\s\S]*?width:\s*min\(560px, calc\(100vw - 32px\)\);/);
});
