import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

function createElement(id) {
  return {
    id,
    attributes: new Map(),
    children: [],
    className: '',
    dataset: {},
    disabled: false,
    hidden: false,
    style: { setProperty(name, value) { this[name] = value; } },
    textContent: '',
    value: '',
    addEventListener() {},
    appendChild(child) { this.children.push(child); },
    focus() {},
    getAttribute(name) { return this.attributes.get(name) ?? null; },
    querySelector(selector) {
      if (selector === '[data-stale-title]') return this.staleTitle || null;
      if (selector === '[data-stale-detail]') return this.staleDetail || null;
      if (selector === '[data-use-latest]') return this.useLatest || null;
      return null;
    },
    querySelectorAll() { return []; },
    remove() {},
    setAttribute(name, value) { this.attributes.set(name, String(value)); },
  };
}

function createPreviewDocument() {
  const elements = new Map();
  const snapshot = createElement('snapshot');
  Object.defineProperty(snapshot, 'innerHTML', {
    get() { return this.innerHTMLValue || ''; },
    set(value) {
      this.innerHTMLValue = value;
      if (value.includes('snapshotFrame')) {
        for (const id of ['annotateHintSlot', 'snapshotFrame', 'previewFrameStatus', 'snapshotImage', 'selectionOverlay']) {
          elements.set(id, createElement(id));
        }
      } else if (value.includes('empty-stage')) {
        for (const id of ['annotateHintSlot', 'snapshotFrame', 'snapshotImage', 'selectionOverlay']) {
          elements.delete(id);
        }
        elements.set('previewFrameStatus', createElement('previewFrameStatus'));
      }
    },
  });

  for (const id of [
    'canvasBlockedOverlay',
    'copyPromptButton',
    'draftLockBar',
    'inspectorFooter',
    'selectToolButton',
    'sendAgentButton',
    'annotateToolButton',
    'toolStatus',
  ]) {
    elements.set(id, createElement(id));
  }
  elements.set('snapshot', snapshot);

  const staleNotice = createElement('canvasStaleNotice');
  staleNotice.staleTitle = createElement('staleTitle');
  staleNotice.staleDetail = createElement('staleDetail');
  staleNotice.useLatest = createElement('useLatest');
  elements.set('canvasStaleNotice', staleNotice);

  return {
    elements,
    document: {
      body: createElement('body'),
      createElement,
      getElementById(id) { return elements.get(id) || null; },
      hidden: false,
      querySelector() { return null; },
      querySelectorAll() { return []; },
      addEventListener() {},
      removeEventListener() {},
    },
    snapshot,
  };
}

function loadRenderPreviewRegion({ document, elements, snapshot, draft }) {
  const noop = () => {};
  const toolMode = {
    getState() { return {}; },
    isAnnotateMode() { return false; },
    isSelectMode() { return true; },
  };
  const statusSurfaceRegistry = {
    hide(name) {
      const element = elements.get(name);
      if (element) element.hidden = true;
    },
    show(_name, options) {
      if (options.element) {
        options.element.hidden = false;
        options.element.content = options.content;
      }
    },
  };
  return loadConsoleSymbols({
    modules: ['presentation/previewRegionView.js'],
    symbols: ['renderPreviewRegion'],
    args: [
      'document',
      'state',
      'snapshot',
      'toolMode',
      'draftFlow',
      'draftItemList',
      'draftFocusIndex',
      'draftSelection',
      'currentDraftWorkspace',
      'draftWorkspace',
      'savedEvidenceItems',
      'latestPersistedScreen',
      'attachSnapshotHandlers',
      'applyPreviewZoom',
      'statusSurfaceRegistry',
      'previewUseCases',
      'pollingUseCases',
      'renderInspectorFooter',
      'renderEditorBack',
      'copyPromptButton',
      'sendAgentButton',
      'annotateToolButton',
      'selectToolButton',
      'toolStatus',
      'selectionSummary',
      'comment',
      'pendingItems',
      'inspectorFooter',
      'renderPromptReadiness',
      'toolbarOpenCount',
      'toolbarResolvedCount',
      'zoomPercent',
      'zoomOutButton',
      'zoomInButton',
      'PreviewZoomMin',
      'PreviewZoomMax',
    ],
    values: [
      document,
      { connection: {}, preview: { stale: true }, session: { items: [], screens: [], sessionId: 'session-1', status: 'active' } },
      snapshot,
      toolMode,
      () => draft,
      () => [],
      () => null,
      () => null,
      () => ({}),
      {},
      () => [],
      () => null,
      noop,
      noop,
      statusSurfaceRegistry,
      { getState() { return { zoom: 1 }; } },
      { getState() { return {}; } },
      noop,
      noop,
      elements.get('copyPromptButton'),
      elements.get('sendAgentButton'),
      elements.get('annotateToolButton'),
      elements.get('selectToolButton'),
      elements.get('toolStatus'),
      createElement('selectionSummary'),
      createElement('comment'),
      createElement('pendingItems'),
      elements.get('inspectorFooter'),
      noop,
      () => 0,
      () => 0,
      createElement('zoomPercent'),
      createElement('zoomOutButton'),
      createElement('zoomInButton'),
      0.5,
      2,
    ],
  });
}

test('recovered draft renders from its screenshotUrl even when screen artifact metadata is missing', () => {
  const { document, elements, snapshot } = createPreviewDocument();
  const draft = {
    context: { previewId: 'preview-1', sessionId: 'session-1' },
    previewId: 'preview-1',
    screen: { screenId: 'screen-1' },
    screenshotUrl: '/api/preview/preview-1/screenshot/full?sessionId=session-1',
  };
  const { renderPreviewRegion } = loadRenderPreviewRegion({ document, elements, snapshot, draft });

  renderPreviewRegion();

  assert.ok(elements.get('snapshotFrame'), 'expected the recovered draft to create a preview frame');
  assert.equal(
    elements.get('snapshotImage')?.getAttribute('src'),
    '/api/preview/preview-1/screenshot/full?sessionId=session-1',
  );
  assert.doesNotMatch(snapshot.innerHTML, /No screenshot artifact/);
});

function mutateObject(target, next) {
  for (const key of Object.keys(target)) delete target[key];
  Object.assign(target, next);
}

function loadLatestFrameRefreshRuntime({ state, workspace, startDraftAnnotationFlow }) {
  const elements = new Map();
  const staleNotice = createElement('canvasStaleNotice');
  staleNotice.useLatest = createElement('useLatest');
  elements.set('canvasStaleNotice', staleNotice);
  const document = {
    getElementById(id) { return elements.get(id) || null; },
  };
  const toolMode = {
    focusSavedItem() {},
    getState() { return {}; },
    isAnnotateMode() { return false; },
    setHoveredTarget() {},
  };
  return loadConsoleSymbols({
    modules: ['preview.js'],
    symbols: ['useLatestStaleFrame', 'hasPendingLatestStaleFrameRefresh', 'resumeLatestStaleFrameRefresh'],
    args: [
      'document',
      'state',
      'draftWorkspace',
      'draftFlow',
      'replaceDraftWorkspace',
      'setConsolePreview',
      'previewUseCases',
      'flushFocusedPendingComment',
      'startDraftAnnotationFlow',
      'updateAnnotationSequenceFromPendingItems',
      'setDraftFocusIndex',
      'toolMode',
      'setDraftSelection',
      'persistCurrentPendingState',
      'render',
      'userConnectionState',
      'showStatus',
      'refreshConnection',
    ],
    values: [
      document,
      state,
      workspace,
      () => (workspace.workspaceId
        ? {
            context: workspace.context,
            previewId: workspace.context?.previewId || null,
            screen: workspace.screen,
            screenshotUrl: workspace.screenshotUrl,
          }
        : null),
      (nextWorkspace) => mutateObject(workspace, nextWorkspace),
      (preview) => { state.preview = preview; },
      { contextChanged() {}, getState() { return { zoom: 1 }; } },
      () => {},
      startDraftAnnotationFlow,
      () => {},
      () => {},
      toolMode,
      () => {},
      () => {},
      () => {},
      (status) => String(status?.state || '').toLowerCase(),
      () => {},
      async () => state.connection.current,
    ],
  });
}

test('Use latest frame resumes recapture after launching the app and preserves recovered items', async () => {
  const recoveredItems = [
    { bounds: { bottom: 20, left: 10, right: 20, top: 10 }, comment: 'keep me', draftItemId: 'draft-1', targetType: 'area' },
  ];
  const workspace = {
    workspaceId: 'old-workspace',
    revision: 4,
    lifecycle: 'editing',
    context: { previewId: 'old-preview', sessionId: 'session-1' },
    screen: { screenId: 'old-screen' },
    screenshotUrl: '/old.png',
    items: recoveredItems,
    history: { redoStack: [], undoStack: [{ type: 'old' }] },
  };
  const state = {
    connection: { current: { state: 'OPEN_APP' } },
    preview: { previewId: 'old-preview', stale: true },
    session: { sessionId: 'session-1' },
  };
  let startCalls = 0;
  const runtime = loadLatestFrameRefreshRuntime({
    state,
    workspace,
    startDraftAnnotationFlow: async () => {
      startCalls += 1;
      if (String(state.connection.current.state).toLowerCase() !== 'ready') return;
      mutateObject(workspace, {
        workspaceId: 'new-workspace',
        revision: 1,
        lifecycle: 'editing',
        context: { previewId: 'new-preview', sessionId: 'session-1' },
        screen: { screenId: 'new-screen', screenshot: { desktopFullPath: '/tmp/new.png' } },
        screenshotUrl: '/new.png',
        items: [],
        history: { redoStack: [], undoStack: [] },
      });
    },
  });

  await runtime.useLatestStaleFrame();

  assert.equal(startCalls, 1);
  assert.equal(runtime.hasPendingLatestStaleFrameRefresh(), true);
  assert.equal(workspace.workspaceId, 'old-workspace', 'old draft remains visible while the app is launching');

  state.connection.current = { state: 'READY' };
  await runtime.resumeLatestStaleFrameRefresh();

  assert.equal(startCalls, 2);
  assert.equal(runtime.hasPendingLatestStaleFrameRefresh(), false);
  assert.equal(workspace.workspaceId, 'new-workspace');
  assert.deepEqual(workspace.items, recoveredItems);
  assert.deepEqual(workspace.history, { redoStack: [], undoStack: [] });
});
