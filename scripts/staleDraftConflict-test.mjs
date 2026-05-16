import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

// Spec S1.4.5 / Task 4 (Design §5):
// - When saveDraftWorkspace returns 412 with body
//   `{state:'STALE_PREVIEW', readiness, serverDraft}`, the client opens the
//   `staleDraftConflict` boundary dialog with the three labelled buttons.
// - "Keep mine (overwrite)" resubmits the save with `If-Match: *`.
// - "Use server's version" loads `serverDraft` into local state.
// - Cancel is a no-op (caller preserves local pending pins).

const { createDraftApiAdapter, resolveStaleDraftConflict } = loadConsoleSymbols({
  modules: ['draftApiAdapter.js'],
  symbols: ['createDraftApiAdapter', 'resolveStaleDraftConflict'],
});

function createMockResponse({ status, body, headers = {} }) {
  const headerMap = new Map(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get(name) { return headerMap.get(String(name).toLowerCase()) ?? null; } },
    json: async () => body,
    text: async () => JSON.stringify(body),
  };
}

function createFetchScript(scripted) {
  const calls = [];
  let i = 0;
  return {
    calls,
    fetch: async (url, init) => {
      calls.push({ url, init: { ...init, headers: collectHeaders(init?.headers) } });
      const next = scripted[i] ?? scripted[scripted.length - 1];
      i += 1;
      return next;
    },
  };
}

function collectHeaders(headers) {
  if (!headers) return {};
  if (typeof headers.entries === 'function') {
    const out = {};
    for (const [k, v] of headers.entries()) out[k] = v;
    return out;
  }
  return { ...headers };
}

test('saveDraftWorkspace returns staleDraft envelope on HTTP 412', async () => {
  const stalePayload = {
    state: 'STALE_PREVIEW',
    readiness: { state: 'STALE_PREVIEW', cause: 'newer save advanced', verify: '', fix: '', nextAction: '', details: {} },
    serverDraft: { sessionId: 'session-1', items: [{ itemId: 'item-server' }] },
  };
  const scripted = [createMockResponse({ status: 412, body: stalePayload, headers: { ETag: '"7"' } })];
  const { fetch, calls } = createFetchScript(scripted);
  const adapter = createDraftApiAdapter({ fetchImpl: fetch, consoleToken: 'tok' });

  const result = await adapter.saveDraftWorkspace({ previewId: 'preview-1', items: [] });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/items/batch');
  assert.equal(result.staleDraft?.state, 'STALE_PREVIEW');
  assert.equal(result.staleDraft?.serverDraft?.sessionId, 'session-1');
  assert.equal(result.etag, '"7"');
});

test('resolveStaleDraftConflict opens the staleDraftConflict boundary dialog', async () => {
  const renders = [];
  const adapter = { saveDraftWorkspace: async () => ({}) };
  const conflict = {
    state: 'STALE_PREVIEW',
    readiness: { state: 'STALE_PREVIEW' },
    serverDraft: { sessionId: 'session-1' },
  };

  await resolveStaleDraftConflict({
    adapter,
    request: { previewId: 'p' },
    staleDraft: conflict,
    prompt: async () => 'cancel',
    render: (variant, ctx) => { renders.push({ variant, ctx }); },
  });

  assert.equal(renders.length, 1);
  assert.equal(renders[0].variant, 'staleDraftConflict');
  assert.deepEqual(renders[0].ctx.readiness, conflict.readiness);
});

test('Keep mine (overwrite) resubmits saveDraftWorkspace with If-Match: *', async () => {
  let captured = null;
  const adapter = {
    saveDraftWorkspace: async (request, init) => {
      captured = { request, init };
      return { sessionId: 'session-1', items: [{ itemId: 'item-saved' }] };
    },
  };
  const resolved = await resolveStaleDraftConflict({
    adapter,
    request: { previewId: 'p' },
    staleDraft: { serverDraft: {} },
    prompt: async () => 'overwrite',
    render: () => {},
  });

  assert.equal(resolved.action, 'overwrite');
  assert.equal(typeof resolved.retry, 'function');
  const retryResult = await resolved.retry();
  assert.equal(captured.init.ifMatch, '*');
  assert.equal(retryResult.sessionId, 'session-1');
});

test("Use server's version returns serverDraft for the caller to load locally", async () => {
  const serverDraft = { sessionId: 'session-1', items: [{ itemId: 'item-server' }] };
  const resolved = await resolveStaleDraftConflict({
    adapter: { saveDraftWorkspace: async () => { throw new Error('should not refetch'); } },
    request: { previewId: 'p' },
    staleDraft: { serverDraft },
    prompt: async () => 'useServer',
    render: () => {},
  });

  assert.equal(resolved.action, 'useServer');
  assert.deepEqual(resolved.serverDraft, serverDraft);
});

test('Cancel preserves local state — no retry, no serverDraft load', async () => {
  let retried = false;
  const resolved = await resolveStaleDraftConflict({
    adapter: { saveDraftWorkspace: async () => { retried = true; return {}; } },
    request: { previewId: 'p' },
    staleDraft: { serverDraft: { sessionId: 'session-1' } },
    prompt: async () => 'cancel',
    render: () => {},
  });

  assert.equal(resolved.action, 'cancel');
  assert.equal(retried, false);
  assert.equal(resolved.serverDraft, undefined);
});

test('saveDraftWorkspace forwards If-Match header when init.ifMatch is provided', async () => {
  const scripted = [createMockResponse({ status: 200, body: { sessionId: 'session-1', items: [] } })];
  const { fetch, calls } = createFetchScript(scripted);
  const adapter = createDraftApiAdapter({ fetchImpl: fetch, consoleToken: 'tok' });

  await adapter.saveDraftWorkspace({ previewId: 'preview-1', items: [] }, { ifMatch: '*' });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].init.headers['If-Match'] || calls[0].init.headers['if-match'], '*');
});
