import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const historySource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/history.js'),
  'utf8',
);

// Lightweight fake EventTarget: records every addEventListener / removeEventListener
// call so the test can confirm dispose() removes exactly what initHistory added.
function createFakeTarget(name) {
  const listeners = new Map(); // type -> count of currently-registered handlers
  return {
    name,
    listeners,
    addEventListener(type, fn) {
      listeners.set(type, (listeners.get(type) || 0) + 1);
      this._fns ??= new Map();
      if (!this._fns.has(type)) this._fns.set(type, new Set());
      this._fns.get(type).add(fn);
    },
    removeEventListener(type, fn) {
      listeners.set(type, (listeners.get(type) || 0) - 1);
      if (this._fns?.has(type)) this._fns.get(type).delete(fn);
    },
    setAttribute() {},
    focus() {},
    dataset: {},
  };
}

function loadInitHistory() {
  // history.js references many module-scope identifiers that other console
  // modules normally provide (state.js, draftWorkspace.js, toolModeUseCases.js,
  // rendering.js, ...). Stub only what history.js *references* without
  // declaring itself. Use `var` so the declarations don't clash with the
  // function declarations in history.js (which would be a redeclaration error
  // for `let`/`const`).
  const stubs = `
    'use strict';
    var state = { session: null };
    var draftFlow = () => null;
    var draftItemList = () => [];
    var draftWorkspace = null;
    var draftWorkspaceItems = () => [];
    var draftWorkspaceRecoveryEnvelope = () => ({});
    var replaceDraftWorkspace = () => {};
    var setDraftFocusIndex = () => {};
    var setDraftSelection = () => {};
    var draftFocusIndex = () => null;
    var draftSelection = () => null;
    var createBrowserDraftPorts = () => ({ storage: { loadWorkspacesForSession: () => [], saveWorkspace: () => {} } });
    var annotationStatus = () => 'open';
    var toolbarAnnotations = () => [];
    var text = (v) => String(v ?? '');
    var escapeHtml = (v) => String(v ?? '');
    var countLabel = (n, s, p) => n + ' ' + (n === 1 ? s : p);
    var formatHistoryDate = () => '';
    var renderCurrentSessionList = () => {};
    var renderInspectorRegion = () => {};
    var renderPreviewOnly = () => {};
    var requestCanonicalPreviewCapture = () => {};
    var startDraftAnnotationFlow = async () => {};
    var newSession = async () => {};
    var requestJson = async () => ({});
    var withMutationLock = async (fn) => fn();
    var setConsoleSession = () => {};
    var refreshDevices = async () => {};
    var refreshConnection = async () => {};
    var loadPendingRecoveryForCurrentSession = () => {};
    var render = () => {};
    var requirePendingRecoveryChoiceBeforeSessionChange = () => true;
    var resolvePendingBeforeBoundary = async () => 'continue';
    var bumpSessionMutationGeneration = () => {};
    var stopLivePreviewPolling = () => {};
    var startLivePreviewPolling = () => {};
    var resetComposer = () => {};
    var clearPreview = () => {};
    var refreshPreview = async () => {};
    var latestPersistedScreen = () => null;
    var shouldAutoFetchPreview = () => false;
    var showError = () => {};
    var error = { textContent: '' };
    var sessions = { querySelector: () => null, innerHTML: '' };
    var sessionCount = { textContent: '' };
    var emptySessionsHtml = () => '';
    var __fixthisDisposers = [];
    var store = { dispatch: () => {} };
    var historyToggleButton = null;
    var historyDrawerScrim = null;
    var toolMode = { getState: () => ({ historyDrawerOpen: false }), setHistoryDrawer: () => {}, focusSavedItem: () => {}, setHoveredTarget: () => {}, enterAnnotate: () => {}, setNewHistoryAnnotateModeStarting: () => {}, setAnnotationSequenceAtLeast: () => {} };
  `;
  const factory = new Function(
    'document',
    `${stubs}\n${historySource}\n; return { initHistory, __fixthisDisposers };`,
  );
  return factory;
}

test('initHistory dispose() removes every listener it added', () => {
  const factory = loadInitHistory();
  // The bundled history.js calls initHistory once at module-scope using the
  // stubbed document/historyToggleButton/historyDrawerScrim. Pass a throwaway
  // boot-time document to keep that boot wiring isolated from the targets the
  // test inspects below.
  const bootDoc = createFakeTarget('boot-doc');
  const docTarget = createFakeTarget('document');
  const toggle = createFakeTarget('toggle');
  const scrim = createFakeTarget('scrim');
  const { initHistory } = factory(bootDoc);

  const fakeToolMode = {
    getState: () => ({ historyDrawerOpen: false }),
    setHistoryDrawer: () => {},
  };

  const { dispose } = initHistory({
    document: docTarget,
    historyToggleButton: toggle,
    historyDrawerScrim: scrim,
    openHistoryDrawer: () => {},
    closeHistoryDrawer: () => {},
    syncHistoryDrawerState: () => {},
    toolMode: fakeToolMode,
  });

  const totalRegistered = (t) =>
    [...t.listeners.values()].reduce((sum, n) => sum + n, 0);
  assert.equal(totalRegistered(toggle), 1, 'toggle should have 1 listener');
  assert.equal(totalRegistered(scrim), 1, 'scrim should have 1 listener');
  assert.equal(totalRegistered(docTarget), 1, 'document should have 1 keydown listener');

  dispose();

  assert.equal(totalRegistered(toggle), 0, 'toggle listeners cleared');
  assert.equal(totalRegistered(scrim), 0, 'scrim listeners cleared');
  assert.equal(totalRegistered(docTarget), 0, 'document listeners cleared');
});

test('initHistory tolerates missing toggle / scrim targets', () => {
  const factory = loadInitHistory();
  const bootDoc = createFakeTarget('boot-doc');
  const docTarget = createFakeTarget('document');
  const { initHistory } = factory(bootDoc);

  const fakeToolMode = {
    getState: () => ({ historyDrawerOpen: false }),
    setHistoryDrawer: () => {},
  };

  const { dispose } = initHistory({
    document: docTarget,
    historyToggleButton: null,
    historyDrawerScrim: null,
    openHistoryDrawer: () => {},
    closeHistoryDrawer: () => {},
    syncHistoryDrawerState: () => {},
    toolMode: fakeToolMode,
  });

  const totalRegistered = (t) =>
    [...t.listeners.values()].reduce((sum, n) => sum + n, 0);
  assert.equal(totalRegistered(docTarget), 1);

  dispose();
  assert.equal(totalRegistered(docTarget), 0);
});
