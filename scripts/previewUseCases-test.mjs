import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const fsmSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/previewFsm.js'), 'utf8');
const ucSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/previewUseCases.js'), 'utf8');
const factory = new Function(`${fsmSrc}\n${ucSrc}; return {
  createInitialPreviewState,
  reducePreview,
  createPreviewUseCases,
  PreviewLifecycle,
  MinPreviewZoom,
  MaxPreviewZoom,
};`);
const m = factory();

function makeUseCases({ observer = () => {}, api = {}, storage = {} } = {}) {
  return m.createPreviewUseCases({
    onChange: observer,
    api,
    storage,
  });
}

test('createPreviewUseCases exposes getState seeded with initial state', () => {
  const uc = makeUseCases();
  const s = uc.getState();
  assert.equal(s.lifecycle, m.PreviewLifecycle.IDLE);
  assert.equal(s.requestGeneration, 0);
  assert.equal(s.zoom, 1);
});

test('request() dispatches REQUEST_STARTED and resolves with success', async () => {
  let resolved = null;
  const uc = makeUseCases({
    api: { capture: async () => ({ previewId: 'p1' }) },
    observer: (next) => { resolved = next; },
  });
  const promise = uc.request();
  assert.equal(uc.getState().lifecycle, m.PreviewLifecycle.REQUESTING);
  const preview = await promise;
  assert.equal(preview.previewId, 'p1');
  assert.equal(uc.getState().lifecycle, m.PreviewLifecycle.READY);
  assert.equal(resolved.lifecycle, m.PreviewLifecycle.READY);
});

test('request() during in-flight returns the same promise (dedup)', async () => {
  let resolveCapture;
  const captureFn = () => new Promise((resolve) => { resolveCapture = resolve; });
  const uc = makeUseCases({ api: { capture: captureFn } });
  const p1 = uc.request();
  const p2 = uc.request();
  assert.equal(p1, p2, 'in-flight request must be deduplicated');
  // Flush the Promise.resolve() microtask so captureFn() actually runs.
  await Promise.resolve();
  resolveCapture({ previewId: 'shared' });
  const result = await p1;
  assert.equal(result.previewId, 'shared');
});

test('contextChanged() bumps contextGeneration and clears inFlight', () => {
  const uc = makeUseCases({ api: { capture: async () => ({}) } });
  uc.request();
  assert.equal(uc.getState().contextGeneration, 0);
  uc.contextChanged();
  assert.equal(uc.getState().contextGeneration, 1);
  assert.equal(uc.getState().inFlight, null);
});

test('REQUEST_SUCCEEDED is dropped when contextGeneration changed mid-flight', async () => {
  let resolveCapture;
  const captureFn = () => new Promise((resolve) => { resolveCapture = resolve; });
  const uc = makeUseCases({ api: { capture: captureFn } });
  uc.request().catch(() => {}); // swallow
  // Flush so captureFn() runs and resolveCapture is assigned.
  await Promise.resolve();
  uc.contextChanged();
  resolveCapture({ previewId: 'stale' });
  // Allow microtasks to flush
  await new Promise((resolve) => setTimeout(resolve, 0));
  // current should NOT be set because the REQUEST_SUCCEEDED was dropped
  assert.equal(uc.getState().current, null);
});

test('applyReady() stores externally delivered preview without starting a fetch', () => {
  const seen = [];
  const uc = makeUseCases({
    api: { capture: async () => { throw new Error('capture must not run'); } },
    observer: (next) => { seen.push(next); },
  });

  const next = uc.applyReady({ previewId: 'sse-preview', sessionId: 'session-1' });

  assert.equal(next.lifecycle, m.PreviewLifecycle.READY);
  assert.equal(next.current.previewId, 'sse-preview');
  assert.equal(uc.getState().current.sessionId, 'session-1');
  assert.equal(seen.at(-1).current.previewId, 'sse-preview');
});

test('applyReady() ignores stale context generation', () => {
  const uc = makeUseCases();
  uc.contextChanged();

  const before = uc.getState();
  const next = uc.applyReady(
    { previewId: 'stale-preview', sessionId: 'session-1' },
    { contextGeneration: before.contextGeneration - 1 },
  );

  assert.equal(next, before);
  assert.equal(uc.getState().current, null);
});

test('applyReady() clears stale/error state on accepted external preview', async () => {
  const uc = makeUseCases({ api: { capture: async () => ({ previewId: 'poll-preview' }) } });
  await uc.request();
  uc.setStale(true);

  const next = uc.applyReady({ previewId: 'sse-preview', sessionId: 'session-1' });

  assert.equal(next.lifecycle, m.PreviewLifecycle.READY);
  assert.equal(next.current.previewId, 'sse-preview');
  assert.equal(next.error, null);
});

test('request() failure dispatches REQUEST_FAILED and lifecycle ERROR', async () => {
  const uc = makeUseCases({ api: { capture: async () => { throw new Error('boom'); } } });
  await assert.rejects(uc.request(), /boom/);
  const s = uc.getState();
  assert.equal(s.lifecycle, m.PreviewLifecycle.ERROR);
  assert.equal(s.error, 'boom');
});

test('setZoom() clamps via reducer (5 → 2)', () => {
  const uc = makeUseCases();
  uc.setZoom(5);
  assert.equal(uc.getState().zoom, 2);
});

test('setZoom() clamps via reducer (0.1 → 0.5)', () => {
  const uc = makeUseCases();
  uc.setZoom(0.1);
  assert.equal(uc.getState().zoom, 0.5);
});

test('setStale() toggles READY ↔ STALE', async () => {
  const uc = makeUseCases({ api: { capture: async () => ({ previewId: 'p' }) } });
  await uc.request();
  uc.setStale(true);
  assert.equal(uc.getState().lifecycle, m.PreviewLifecycle.STALE);
  uc.setStale(false);
  assert.equal(uc.getState().lifecycle, m.PreviewLifecycle.READY);
});

test('setPollInterval() persists via storage port', () => {
  const writes = [];
  const storage = { setItem: (k, v) => { writes.push([k, v]); } };
  const uc = makeUseCases({ storage });
  uc.setPollInterval(2000);
  assert.equal(uc.getState().pollIntervalMs, 2000);
  assert.deepEqual(writes, [['fixthis.previewIntervalMs.v2', '2000']]);
});

test('observer fires on each state change', async () => {
  const seen = [];
  const uc = makeUseCases({
    api: { capture: async () => ({}) },
    observer: (next) => { seen.push(next.lifecycle); },
  });
  await uc.request();
  uc.setZoom(1.5);
  // REQUEST_STARTED → REQUESTING, REQUEST_SUCCEEDED → READY, SET_ZOOM → READY (zoom changed)
  assert.ok(seen.length >= 3);
  assert.equal(seen[0], m.PreviewLifecycle.REQUESTING);
  assert.equal(seen[1], m.PreviewLifecycle.READY);
});
