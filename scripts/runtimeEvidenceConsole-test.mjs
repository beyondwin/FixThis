import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const source = readFileSync('fixthis-mcp/src/main/console/runtimeEvidence.js', 'utf8');
const detailSource = readFileSync('fixthis-mcp/src/main/console/presentation/annotationDetailView.js', 'utf8');
const html = readFileSync('fixthis-mcp/src/main/resources/console/index.html', 'utf8');
const module = loadConsoleSymbols({
  modules: ['runtimeEvidence.js'],
  symbols: [
    'createRuntimeEvidenceController',
    'runtimeEvidenceStatusModel',
    'renderRuntimeEvidenceRows',
  ],
});

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((next, fail) => { resolve = next; reject = fail; });
  return { promise, resolve, reject };
}

function fixture(initialSession) {
  let activeSession = initialSession;
  const requests = [];
  const pending = [];
  const ports = {
    activeSession: () => activeSession,
    request: (path, options) => {
      const next = deferred();
      requests.push({ path, options });
      pending.push(next);
      return next.promise;
    },
    applySession: session => { activeSession = session; },
    render: () => {},
  };
  return {
    ports,
    requests,
    pending,
    setActiveSession: session => { activeSession = session; },
  };
}

test('baseline manual recapture uses allowlisted request and reaches complete', async () => {
  const f = fixture({ sessionId: 's1', runtimeEvidencePolicy: 'manual', items: [{ itemId: 'i1', screenId: 'screen-1' }] });
  const controller = module.createRuntimeEvidenceController(f.ports);
  const first = controller.collect({ sessionId: 's1', itemId: 'i1', screenId: 'screen-1', preset: 'baseline' });
  assert.equal(controller.stateFor('i1').status, 'collecting');
  assert.match(f.requests[0].path, /\/api\/items\/i1\/runtime-evidence\/collect$/);
  assert.deepEqual(JSON.parse(f.requests[0].options.body), { sessionId: 's1', preset: 'baseline' });
  f.pending[0].resolve({ attempted: true, status: 'complete', attachmentIds: ['e1'], linkedItemIds: ['i1'] });
  await first;
  assert.equal(controller.stateFor('i1').status, 'complete');

  const again = controller.collect({ sessionId: 's1', itemId: 'i1', screenId: 'screen-1', preset: 'logs' });
  f.pending[1].resolve({ attempted: true, status: 'partial', warnings: ['output_truncated'] });
  await again;
  assert.equal(controller.stateFor('i1').status, 'partial');
});

test('controller renders every terminal state without raw collector output', () => {
  for (const status of ['complete', 'partial', 'failed', 'unsupported']) {
    const model = module.runtimeEvidenceStatusModel({ status, warnings: ['redaction_applied'] });
    assert.equal(model.status, status);
    assert.match(model.label.toLowerCase(), new RegExp(status));
  }
  const rows = module.renderRuntimeEvidenceRows({
    status: 'partial',
    summary: '<img src=x onerror=alert(1)>'.repeat(100),
    artifactPath: '<script>safe-path</script>',
    warnings: ['redaction_applied', 'one', 'two', 'three', 'must_not_render'],
    stdout: 'Bearer raw-secret',
    stderr: 'password=hunter2',
  });
  assert.match(rows, /redaction applied/i);
  assert.doesNotMatch(rows, /<img|<script|raw-secret|hunter2|stdout|stderr|must not render/i);
  assert.ok(rows.length < 2_000, 'rendered diagnostics rows must remain bounded');
});

test('late capture is dropped after session, screen, or item context changes', async () => {
  const cases = [
    { sessionId: 's2', items: [{ itemId: 'i1', screenId: 'screen-1' }] },
    { sessionId: 's1', items: [{ itemId: 'i1', screenId: 'screen-2' }] },
    { sessionId: 's1', items: [] },
  ];
  for (const changed of cases) {
    const f = fixture({ sessionId: 's1', items: [{ itemId: 'i1', screenId: 'screen-1' }] });
    const controller = module.createRuntimeEvidenceController(f.ports);
    const pending = controller.collect({ sessionId: 's1', itemId: 'i1', screenId: 'screen-1', preset: 'baseline' });
    f.setActiveSession(changed);
    f.pending[0].resolve({ attempted: true, status: 'complete' });
    await pending;
    assert.equal(controller.stateFor('i1'), null);
  }
});

test('older recapture completion or rejection cannot erase the newer terminal state', async () => {
  for (const olderOutcome of ['resolve', 'reject']) {
    const f = fixture({ sessionId: 's1', items: [{ itemId: 'i1', screenId: 'screen-1' }] });
    const controller = module.createRuntimeEvidenceController(f.ports);
    const older = controller.collect({ sessionId: 's1', itemId: 'i1', screenId: 'screen-1', preset: 'baseline' });
    const newer = controller.collect({ sessionId: 's1', itemId: 'i1', screenId: 'screen-1', preset: 'logs' });
    f.pending[1].resolve({ attempted: true, status: 'complete', attachmentIds: ['new'] });
    await newer;
    if (olderOutcome === 'resolve') f.pending[0].resolve({ attempted: true, status: 'partial', attachmentIds: ['old'] });
    else f.pending[0].reject(new Error('old request failed'));
    await older;
    assert.equal(controller.stateFor('i1').status, 'complete');
    assert.deepEqual(controller.stateFor('i1').attachmentIds, ['new']);
  }
});

test('rapid policy updates apply only the latest response and never use localStorage', async () => {
  const f = fixture({ sessionId: 's1', runtimeEvidencePolicy: 'manual', items: [] });
  const controller = module.createRuntimeEvidenceController(f.ports);
  const first = controller.updatePolicy('auto_on_handoff');
  const second = controller.updatePolicy('off');
  f.pending[1].resolve({ sessionId: 's1', runtimeEvidencePolicy: 'off', items: [] });
  await second;
  f.pending[0].resolve({ sessionId: 's1', runtimeEvidencePolicy: 'auto_on_handoff', items: [] });
  await first;
  assert.equal(f.ports.activeSession().runtimeEvidencePolicy, 'off');
  assert.doesNotMatch(source, /localStorage/);
});

test('stale policy rejection cannot overwrite or report over the newer policy', async () => {
  const f = fixture({ sessionId: 's1', runtimeEvidencePolicy: 'manual', items: [] });
  const controller = module.createRuntimeEvidenceController(f.ports);
  const older = controller.updatePolicy('auto_on_handoff');
  const newer = controller.updatePolicy('off');
  f.pending[1].resolve({ sessionId: 's1', runtimeEvidencePolicy: 'off', items: [] });
  await newer;
  f.pending[0].reject(new Error('stale failure'));
  assert.equal(await older, null);
  assert.equal(f.ports.activeSession().runtimeEvidencePolicy, 'off');
});

test('annotation and top bar expose accessible diagnostics controls', () => {
  assert.match(source, /Capture diagnostics/);
  assert.match(source, /Capture again/);
  assert.match(detailSource, /runtimeEvidenceSectionHtml\(item\)/);
  assert.match(source, /role="status" aria-live="polite"/);
  assert.match(source, /aria-busy=/);
  assert.match(html, /id="runtimeEvidencePolicy"/);
  assert.match(html, /aria-label="Runtime diagnostics policy"/);
  assert.match(source, /\/\/ @requires api\.js, state\.js/);
});
