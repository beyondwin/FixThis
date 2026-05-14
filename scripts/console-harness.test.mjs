import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseArgs, selectScenarios, emitJunit } from './console-harness.mjs';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

test('parseArgs honors defaults', () => {
  const args = parseArgs(['node', 'script'], {});
  assert.equal(args.matrix, 'all');
  assert.equal(args.viewport, 'all');
  assert.equal(args.headed, false);
});

test('parseArgs honors env vars', () => {
  const args = parseArgs(['node', 'script'], {
    FIXTHIS_HARNESS_MATRIX: 'network-outage',
    FIXTHIS_HARNESS_VIEWPORTS: 'mobile-390',
    FIXTHIS_HARNESS_HEADED: '1',
  });
  assert.equal(args.matrix, 'network-outage');
  assert.equal(args.viewport, 'mobile-390');
  assert.equal(args.headed, true);
});

test('parseArgs flags override env vars', () => {
  const args = parseArgs(
    ['node', 'script', '--matrix', 'slow-handoff', '--viewport', 'desktop-1280'],
    { FIXTHIS_HARNESS_MATRIX: 'network-outage' }
  );
  assert.equal(args.matrix, 'slow-handoff');
  assert.equal(args.viewport, 'desktop-1280');
});

test('selectScenarios expands all and trims CSV', () => {
  const all = selectScenarios('all');
  assert.ok(all.includes('happy-path'));
  const csv = selectScenarios(' slow-handoff , multi-tab ');
  assert.deepEqual(csv, ['slow-handoff', 'multi-tab']);
});

test('emitJunit XML-escapes failure messages', () => {
  const dir = mkdtempSync(join(tmpdir(), 'harness-'));
  try {
    emitJunit(
      [{ scenarioKey: 'x<y', viewportKey: 'm', ok: false, error: 'bad & worse "<>" \'q\'' }],
      dir
    );
    const xml = readFileSync(join(dir, 'results.xml'), 'utf8');
    assert.match(xml, /name="x&lt;y__m"/);
    assert.match(xml, /bad &amp; worse &quot;&lt;&gt;&quot; &apos;q&apos;/);
    assert.doesNotMatch(xml, /<failure>bad & /);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
