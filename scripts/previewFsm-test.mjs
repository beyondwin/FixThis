import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/previewFsm.js'), 'utf8');
const factory = new Function(`${src}; return {
  createInitialPreviewState,
  reducePreview,
  PreviewLifecycle,
  MinPreviewZoom,
  MaxPreviewZoom,
};`);
const m = factory();

test('initial preview state matches FSM shape', () => {
  const s = m.createInitialPreviewState();
  assert.equal(s.lifecycle, m.PreviewLifecycle.IDLE);
  assert.equal(s.requestGeneration, 0);
  assert.equal(s.contextGeneration, 0);
  assert.equal(s.inFlight, null);
  assert.equal(s.current, null);
  assert.equal(s.zoom, 1);
  assert.equal(s.pollIntervalMs, null);
  assert.equal(s.error, null);
});

test('reducer is pure: unknown action returns same input', () => {
  const s = m.createInitialPreviewState();
  const next = m.reducePreview(s, { type: 'NOPE' });
  assert.equal(next, s);
});

test('REQUEST_STARTED bumps requestGeneration and sets inFlight tuple', () => {
  const s = m.createInitialPreviewState();
  const next = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  assert.equal(next.lifecycle, m.PreviewLifecycle.REQUESTING);
  assert.equal(next.requestGeneration, 1);
  assert.equal(next.contextGeneration, 0);
  assert.deepEqual(next.inFlight, { generation: 1, contextGeneration: 0 });
});

test('CONTEXT_CHANGED bumps contextGeneration, clears inFlight and current', () => {
  let s = m.createInitialPreviewState();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  s = m.reducePreview(s, { type: 'REQUEST_SUCCEEDED', generation: 1, contextGeneration: 0, preview: { previewId: 'p' } });
  const next = m.reducePreview(s, { type: 'CONTEXT_CHANGED' });
  assert.equal(next.contextGeneration, 1);
  assert.equal(next.requestGeneration, 2);
  assert.equal(next.inFlight, null);
  assert.equal(next.current, null);
  assert.equal(next.lifecycle, m.PreviewLifecycle.IDLE);
});

test('REQUEST_SUCCEEDED with current generation+contextGeneration applies preview', () => {
  let s = m.createInitialPreviewState();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  const captured = { generation: s.inFlight.generation, contextGeneration: s.inFlight.contextGeneration };
  const preview = { previewId: 'p', screen: {} };
  const next = m.reducePreview(s, {
    type: 'REQUEST_SUCCEEDED',
    generation: captured.generation,
    contextGeneration: captured.contextGeneration,
    preview,
  });
  assert.equal(next.lifecycle, m.PreviewLifecycle.READY);
  assert.equal(next.current, preview);
  assert.equal(next.inFlight, null);
  assert.equal(next.error, null);
});

test('REQUEST_SUCCEEDED with STALE generation only is dropped', () => {
  let s = m.createInitialPreviewState();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  // Simulate a later REQUEST_STARTED bumping generation
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  // generation is now 2; stale completion with generation=1 is dropped
  const next = m.reducePreview(s, {
    type: 'REQUEST_SUCCEEDED',
    generation: 1,
    contextGeneration: 0,
    preview: { previewId: 'stale' },
  });
  assert.equal(next, s, 'stale generation must be dropped');
});

test('REQUEST_SUCCEEDED with STALE contextGeneration only is dropped', () => {
  let s = m.createInitialPreviewState();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  const captured = { generation: s.inFlight.generation, contextGeneration: s.inFlight.contextGeneration };
  // Context changes while request is in flight
  s = m.reducePreview(s, { type: 'CONTEXT_CHANGED' });
  // contextGeneration is now 1; stale completion with contextGeneration=0 is dropped
  const next = m.reducePreview(s, {
    type: 'REQUEST_SUCCEEDED',
    generation: captured.generation,
    contextGeneration: captured.contextGeneration,
    preview: { previewId: 'stale-context' },
  });
  assert.equal(next, s, 'stale contextGeneration must be dropped');
});

test('REQUEST_SUCCEEDED with BOTH stale is dropped', () => {
  let s = m.createInitialPreviewState();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  s = m.reducePreview(s, { type: 'CONTEXT_CHANGED' });
  const next = m.reducePreview(s, {
    type: 'REQUEST_SUCCEEDED',
    generation: 1,
    contextGeneration: 0,
    preview: { previewId: 'both-stale' },
  });
  assert.equal(next, s, 'both stale must be dropped');
});

test('REQUEST_FAILED with current generation+contextGeneration moves to ERROR', () => {
  let s = m.createInitialPreviewState();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  const captured = { generation: s.inFlight.generation, contextGeneration: s.inFlight.contextGeneration };
  const next = m.reducePreview(s, {
    type: 'REQUEST_FAILED',
    generation: captured.generation,
    contextGeneration: captured.contextGeneration,
    error: 'boom',
  });
  assert.equal(next.lifecycle, m.PreviewLifecycle.ERROR);
  assert.equal(next.error, 'boom');
  assert.equal(next.inFlight, null);
});

test('REQUEST_FAILED with stale generation is dropped', () => {
  let s = m.createInitialPreviewState();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  const next = m.reducePreview(s, {
    type: 'REQUEST_FAILED',
    generation: 1,
    contextGeneration: 0,
    error: 'stale-error',
  });
  assert.equal(next, s);
});

test('REQUEST_FAILED with stale contextGeneration is dropped', () => {
  let s = m.createInitialPreviewState();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  const captured = { generation: s.inFlight.generation, contextGeneration: s.inFlight.contextGeneration };
  s = m.reducePreview(s, { type: 'CONTEXT_CHANGED' });
  const next = m.reducePreview(s, {
    type: 'REQUEST_FAILED',
    generation: captured.generation,
    contextGeneration: captured.contextGeneration,
    error: 'stale-context-error',
  });
  assert.equal(next, s);
});

test('SET_STALE marks lifecycle STALE when ready', () => {
  let s = m.createInitialPreviewState();
  s = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  s = m.reducePreview(s, {
    type: 'REQUEST_SUCCEEDED',
    generation: 1,
    contextGeneration: 0,
    preview: { previewId: 'p' },
  });
  const next = m.reducePreview(s, { type: 'SET_STALE', stale: true });
  assert.equal(next.lifecycle, m.PreviewLifecycle.STALE);
  const cleared = m.reducePreview(next, { type: 'SET_STALE', stale: false });
  assert.equal(cleared.lifecycle, m.PreviewLifecycle.READY);
});

test('SET_ZOOM clamps high to MaxPreviewZoom', () => {
  const s = m.createInitialPreviewState();
  const next = m.reducePreview(s, { type: 'SET_ZOOM', zoom: 5 });
  assert.equal(next.zoom, m.MaxPreviewZoom);
  assert.equal(m.MaxPreviewZoom, 2);
});

test('SET_ZOOM clamps low to MinPreviewZoom', () => {
  const s = m.createInitialPreviewState();
  const next = m.reducePreview(s, { type: 'SET_ZOOM', zoom: 0.1 });
  assert.equal(next.zoom, m.MinPreviewZoom);
  assert.equal(m.MinPreviewZoom, 0.5);
});

test('SET_ZOOM rounds to 0.1 increments', () => {
  const s = m.createInitialPreviewState();
  const next = m.reducePreview(s, { type: 'SET_ZOOM', zoom: 1.23 });
  assert.equal(next.zoom, 1.2);
});

test('SET_POLL_INTERVAL writes pollIntervalMs', () => {
  const s = m.createInitialPreviewState();
  const next = m.reducePreview(s, { type: 'SET_POLL_INTERVAL', intervalMs: 2000 });
  assert.equal(next.pollIntervalMs, 2000);
  const cleared = m.reducePreview(next, { type: 'SET_POLL_INTERVAL', intervalMs: null });
  assert.equal(cleared.pollIntervalMs, null);
});

test('reducer returns frozen state', () => {
  const s = m.createInitialPreviewState();
  const next = m.reducePreview(s, { type: 'REQUEST_STARTED' });
  assert.equal(Object.isFrozen(next), true);
});

test('PreviewLifecycle enum exposes all 5 states', () => {
  assert.equal(m.PreviewLifecycle.IDLE, 'idle');
  assert.equal(m.PreviewLifecycle.REQUESTING, 'requesting');
  assert.equal(m.PreviewLifecycle.READY, 'ready');
  assert.equal(m.PreviewLifecycle.STALE, 'stale');
  assert.equal(m.PreviewLifecycle.ERROR, 'error');
});
