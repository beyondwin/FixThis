// Pins the Task 5 (S1.4.6) contract: when the connection transitions out of
// `ready` and the user has connected at least once (`hasEverConnected === true`),
// the console must show the previewStaleBadge — even if `state.preview` is
// null at that instant, as long as some prior preview surface
// (`latestPersistedScreen()` or `draftFlow()?.screen`) survives.
//
// Two checks here:
//   1. Behavior: `markPreviewStale(true)` shows the badge when at least one
//      preview surface exists, even if `state.preview` is null.
//   2. Wiring: the outer gate at the OPEN_APP transition triggers on
//      `state.connection.hasEverConnected`, not on `state.preview`.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const connectionSrc = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/connection.js'),
  'utf8',
);

function createElement(id) {
  return {
    id,
    dataset: {},
    hidden: true,
    textContent: '',
    appendChild() {},
    querySelector() { return null; },
    remove() {},
    setAttribute() {},
  };
}

function createDocument(ids) {
  const elements = new Map();
  ids.forEach((id) => elements.set(id, createElement(id)));
  return {
    getElementById(id) { return elements.get(id) || null; },
  };
}

function loadMarkPreviewStale({ state, statusSurfaceRegistry, draftFlow, latestPersistedScreen, previewStaleBadge }) {
  return loadConsoleSymbols({
    modules: ['connection.js'],
    symbols: ['markPreviewStale'],
    args: ['state', 'statusSurfaceRegistry', 'draftFlow', 'latestPersistedScreen', 'previewStaleBadge'],
    values: [state, statusSurfaceRegistry, draftFlow, latestPersistedScreen, previewStaleBadge],
  });
}

test('markPreviewStale(true) shows the badge when state.preview is null but a persisted screen exists', () => {
  const shown = [];
  const hidden = [];
  const state = { preview: null };
  const statusSurfaceRegistry = {
    show(name, opts) { shown.push({ name, opts }); },
    hide(name) { hidden.push(name); },
  };
  const previewStaleBadge = createElement('previewStaleBadge');
  const { markPreviewStale } = loadMarkPreviewStale({
    state,
    statusSurfaceRegistry,
    draftFlow: () => null,
    latestPersistedScreen: () => ({ screenId: 'screen-1' }),
    previewStaleBadge,
  });

  markPreviewStale(true);

  assert.equal(shown.length, 1, 'previewStaleBadge.show should fire once');
  assert.equal(shown[0].name, 'previewStaleBadge');
  assert.equal(shown[0].opts.element, previewStaleBadge);
  assert.match(shown[0].opts.content, /Connection paused - showing last preview/);
  assert.equal(hidden.length, 0, 'no hide should fire when stale=true and a surface exists');
});

test('markPreviewStale(true) hides the badge when no preview surface is available', () => {
  const shown = [];
  const hidden = [];
  const state = { preview: null };
  const statusSurfaceRegistry = {
    show(name, opts) { shown.push({ name, opts }); },
    hide(name) { hidden.push(name); },
  };
  const { markPreviewStale } = loadMarkPreviewStale({
    state,
    statusSurfaceRegistry,
    draftFlow: () => null,
    latestPersistedScreen: () => null,
    previewStaleBadge: createElement('previewStaleBadge'),
  });

  markPreviewStale(true);

  assert.equal(shown.length, 0, 'no show when there is no preview surface');
  assert.deepEqual(hidden, ['previewStaleBadge']);
});

test('markPreviewStale(false) hides the badge regardless of surface availability', () => {
  const hidden = [];
  const state = { preview: { screen: 'x' } };
  const statusSurfaceRegistry = {
    show() { throw new Error('show should not be called when stale=false'); },
    hide(name) { hidden.push(name); },
  };
  const { markPreviewStale } = loadMarkPreviewStale({
    state,
    statusSurfaceRegistry,
    draftFlow: () => ({ screen: 'y' }),
    latestPersistedScreen: () => ({ screenId: 'persisted' }),
    previewStaleBadge: createElement('previewStaleBadge'),
  });

  markPreviewStale(false);

  assert.deepEqual(hidden, ['previewStaleBadge']);
});

test('applyConnectionStatus non-ready gate fires markPreviewStale(true) on hasEverConnected, not on state.preview', () => {
  // The OPEN_APP transition (viewState !== 'ready') must call
  // markPreviewStale(true) when hasEverConnected is true, even when
  // state.preview is null. We pin this by reading the source of the
  // non-ready branch in applyConnectionStatus.
  //
  // The previous gate was `(draftItemList().length || state.preview)` which
  // fails for the OPEN_APP smoke at line 768 when state.preview is cleared.
  // The spec (§6) requires switching the trigger to hasEverConnected.
  const lines = connectionSrc.split('\n');
  const elseIdx = lines.findIndex((line, i) =>
    line.includes('stopLivePreviewPolling()') &&
    i > 0 &&
    lines[i - 1].includes('} else {'),
  );
  assert.ok(elseIdx > 0, 'expected to find the non-ready branch of applyConnectionStatus');
  // The very next non-blank line should be the markPreviewStale(true) gate.
  const gateLine = lines.slice(elseIdx + 1, elseIdx + 4)
    .find((line) => line.includes('markPreviewStale(true)'));
  assert.ok(gateLine, 'expected markPreviewStale(true) call in non-ready branch');
  assert.match(
    gateLine,
    /hasEverConnected/,
    'non-ready markPreviewStale(true) must be gated on hasEverConnected (Task 5 / S1.4.6)',
  );
  assert.doesNotMatch(
    gateLine,
    /draftItemList\(\)\.length\s*\|\|\s*state\.preview/,
    'old `draftItemList().length || state.preview` gate must be removed (Task 5 / S1.4.6)',
  );
});

test('applyConnectionStatus skips applyDisconnect when state.preview is present', () => {
  // The OPEN_APP/reconnect transition with a salvageable preview must let the
  // previewStaleBadge own the UX. Calling applyDisconnect would show the
  // canvasBlockedOverlay (modalCanvas), which suspends the 'badge' surface
  // class via statusSurfaceRegistry and hides previewStaleBadge — directly
  // contradicting the smoke contract at line 769 (badge hidden === false).
  //
  // Pin: the disconnect-choreography gate must include `!state.preview`.
  const lines = connectionSrc.split('\n');
  // The gate sits on an `else if` line that mentions both the choreography
  // check and the prior-state condition (hadEverConnected/hasDirtyDraft).
  const disconnectIdx = lines.findIndex((line) =>
    line.includes('else if') &&
    line.includes('shouldShowDisconnectChoreography(viewState)') &&
    (line.includes('hadEverConnected') || line.includes('hasDirtyDraft')),
  );
  assert.ok(disconnectIdx > 0, 'expected to find the applyDisconnect gate (else if shouldShowDisconnectChoreography...)');
  const gate = lines[disconnectIdx];
  assert.match(
    gate,
    /!state\.preview/,
    'applyDisconnect gate must require !state.preview so the badge owns the recoverable-preview UX (Task 5 / S1.4.6)',
  );
});
