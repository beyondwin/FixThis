import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sseSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/sse.js'),
  'utf8',
);
const previewPollSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/previewPoll.js'),
  'utf8',
);
const eventsSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/events.js'),
  'utf8',
);
const previewSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/preview.js'),
  'utf8',
);

function extractFunction(source, signaturePrefix) {
  const start = source.indexOf(signaturePrefix);
  assert.notEqual(start, -1, `${signaturePrefix} not found in source`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  assert.fail(`${signaturePrefix} body did not close`);
}

function loadGate(source, signaturePrefix, fnName) {
  const fnSource = extractFunction(source, signaturePrefix);
  const factory = new Function('console', `
    ${fnSource}
    return ${fnName};
  `);
  return factory;
}

function fakeConsole() {
  const warnings = [];
  return {
    warn: (...args) => warnings.push(args),
    warnings,
  };
}

test('dropStaleSse drops when message sessionId mismatches active session', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(sseSource, 'function dropStaleSse', 'dropStaleSse');
  const dropStaleSse = factory(fakeWarn);

  const dropped = dropStaleSse({ sessionId: 'old-1' }, 'new-2');

  assert.equal(dropped, true);
  assert.equal(fakeWarn.warnings.length, 1, 'mismatch must emit a single warn for diagnostics');
});

test('dropStaleSse does NOT drop when sessionId matches', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(sseSource, 'function dropStaleSse', 'dropStaleSse');
  const dropStaleSse = factory(fakeWarn);

  const dropped = dropStaleSse({ sessionId: 'same' }, 'same');

  assert.equal(dropped, false);
  assert.equal(fakeWarn.warnings.length, 0);
});

test('dropStaleSse does NOT drop when message has no sessionId (broadcast)', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(sseSource, 'function dropStaleSse', 'dropStaleSse');
  const dropStaleSse = factory(fakeWarn);

  const dropped = dropStaleSse({}, 'active-1');

  assert.equal(dropped, false);
  assert.equal(fakeWarn.warnings.length, 0);
});

test('dropStalePreviewPoll drops when response sessionId mismatches active session', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(previewPollSource, 'function dropStalePreviewPoll', 'dropStalePreviewPoll');
  const dropStalePreviewPoll = factory(fakeWarn);

  const dropped = dropStalePreviewPoll({ sessionId: 'old-1', previewAvailable: true }, 'new-2');

  assert.equal(dropped, true);
  assert.equal(fakeWarn.warnings.length, 1);
});

test('dropStalePreviewPoll does NOT drop when sessionId matches', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(previewPollSource, 'function dropStalePreviewPoll', 'dropStalePreviewPoll');
  const dropStalePreviewPoll = factory(fakeWarn);

  const dropped = dropStalePreviewPoll({ sessionId: 'same', previewAvailable: true }, 'same');

  assert.equal(dropped, false);
  assert.equal(fakeWarn.warnings.length, 0);
});

test('dropStalePreviewPoll does NOT drop when response has no sessionId', () => {
  const fakeWarn = fakeConsole();
  const factory = loadGate(previewPollSource, 'function dropStalePreviewPoll', 'dropStalePreviewPoll');
  const dropStalePreviewPoll = factory(fakeWarn);

  const dropped = dropStalePreviewPoll({ previewAvailable: true }, 'active-1');

  assert.equal(dropped, false);
  assert.equal(fakeWarn.warnings.length, 0);
});

test('SSE consumer: events.js wires dropStaleSse so mismatched preview-ready is silently dropped (no setConsolePreview, no notify)', () => {
  // events.js handler for preview-ready: the gate must precede setConsolePreview.
  // We load the handler under fakes and observe no state mutation / notify.
  const handlerSource = `
    on('preview-ready', (data) => {
      if (!data.preview || draftFlow()) return;
      ${(() => {
        // Pull only the line that calls dropStaleSse from the real source,
        // to ensure we're testing the actual wiring.
        const m = eventsSource.match(/if \(dropStaleSse\(data, state\.session\?\.sessionId \|\| null\)\) return;/);
        assert.ok(m, 'events.js must guard preview-ready via dropStaleSse');
        return m[0];
      })()}
      setConsolePreview({ ...data.preview });
      renderPreviewOnly();
    });
  `;
  const calls = { setConsolePreview: 0, renderPreviewOnly: 0, notify: 0, warnings: 0 };
  const handlers = {};
  const fakeOn = (name, fn) => { handlers[name] = fn; };
  const fakeDraftFlow = () => null;
  const fakeSetConsolePreview = () => { calls.setConsolePreview += 1; };
  const fakeRenderPreviewOnly = () => { calls.renderPreviewOnly += 1; };
  const fakeNotify = () => { calls.notify += 1; };
  const fakeConsoleObj = { warn: () => { calls.warnings += 1; } };
  const fakeState = { session: { sessionId: 'active' } };
  const dropStaleSseSource = extractFunction(sseSource, 'function dropStaleSse');

  const factory = new Function(
    'on', 'draftFlow', 'setConsolePreview', 'renderPreviewOnly', 'notify', 'state', 'console',
    `${dropStaleSseSource}\n${handlerSource}`,
  );
  factory(fakeOn, fakeDraftFlow, fakeSetConsolePreview, fakeRenderPreviewOnly, fakeNotify, fakeState, fakeConsoleObj);

  // Mismatched message: must be silently dropped.
  handlers['preview-ready']({ preview: { previewId: 'p1' }, sessionId: 'old' });
  assert.equal(calls.setConsolePreview, 0, 'mismatched preview-ready must NOT mutate preview state');
  assert.equal(calls.renderPreviewOnly, 0, 'mismatched preview-ready must NOT trigger render');
  assert.equal(calls.notify, 0, 'mismatched preview-ready must NOT trigger notification');
  assert.equal(calls.warnings, 1, 'mismatched preview-ready must log one warn for diagnostics');

  // Matching message: passes through.
  handlers['preview-ready']({ preview: { previewId: 'p2' }, sessionId: 'active' });
  assert.equal(calls.setConsolePreview, 1);
  assert.equal(calls.renderPreviewOnly, 1);
  assert.equal(calls.notify, 0);
});

test('preview-poll consumer: preview.js wires dropStalePreviewPoll so mismatched response is silently dropped before setConsolePreview', () => {
  // preview.js refreshPreview: the gate must precede applyPreviewReadinessToConnectionCard / setConsolePreview.
  const m = previewSource.match(/if \(dropStalePreviewPoll\(preview, state\.session\?\.sessionId \|\| null\)\) return;/);
  assert.ok(m, 'preview.js must guard refreshPreview via dropStalePreviewPoll');

  // Simulate the refreshPreview tail using fakes — gate runs before any side effect.
  const calls = { applyReadiness: 0, setConsolePreview: 0, markStale: 0, notify: 0, warnings: 0 };
  const fakeApply = () => { calls.applyReadiness += 1; };
  const fakeSetConsolePreview = () => { calls.setConsolePreview += 1; };
  const fakeMarkStale = () => { calls.markStale += 1; };
  const fakeNotify = () => { calls.notify += 1; };
  const fakeConsoleObj = { warn: () => { calls.warnings += 1; } };
  const fakeState = { session: { sessionId: 'active' } };

  const dropFnSource = extractFunction(previewPollSource, 'function dropStalePreviewPoll');
  const body = `
    function refreshPreviewTail(preview, state) {
      ${m[0]}
      applyPreviewReadinessToConnectionCard(preview);
      setConsolePreview(preview);
      markPreviewStale(false);
    }
    return refreshPreviewTail;
  `;
  const factory = new Function(
    'applyPreviewReadinessToConnectionCard', 'setConsolePreview', 'markPreviewStale', 'notify', 'console',
    `${dropFnSource}\n${body}`,
  );
  const refreshPreviewTail = factory(fakeApply, fakeSetConsolePreview, fakeMarkStale, fakeNotify, fakeConsoleObj);

  // Mismatched response: silent drop.
  refreshPreviewTail({ sessionId: 'old', previewAvailable: true }, fakeState);
  assert.equal(calls.applyReadiness, 0, 'mismatched preview poll must NOT mutate connection card');
  assert.equal(calls.setConsolePreview, 0, 'mismatched preview poll must NOT mutate preview state');
  assert.equal(calls.markStale, 0, 'mismatched preview poll must NOT mutate stale flag');
  assert.equal(calls.notify, 0, 'mismatched preview poll must NOT notify');
  assert.equal(calls.warnings, 1, 'mismatched preview poll must log one warn for diagnostics');

  // Matching response: passes through.
  refreshPreviewTail({ sessionId: 'active', previewAvailable: true }, fakeState);
  assert.equal(calls.applyReadiness, 1);
  assert.equal(calls.setConsolePreview, 1);
});
