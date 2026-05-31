import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  buildReleaseGateReport,
  normalizeEvidenceStep,
  renderReleaseGateMarkdown,
  releaseGateSteps,
  runReleaseGate,
  writeReleaseGateReports,
} from './release-gate.mjs';

test('releaseGateSteps include Trust Loop source agent release and console evidence', () => {
  const commands = releaseGateSteps().map((step) => step.command);
  assert.ok(commands.includes('npm run release:reality'));
  assert.ok(commands.includes('npm run source-matching:fixtures:test'));
  assert.ok(commands.includes('npm run source-matching:fixtures:runtime -- --strict'));
  assert.ok(commands.includes('npm run agent-loop:smoke:test'));
  assert.ok(commands.includes('npm run agent-loop:smoke -- --strict'));
  assert.ok(commands.includes('npm run real-copy-prompt:smoke -- --strict'));
  assert.ok(commands.includes('npm run handoff:eval:test'));
  assert.ok(commands.includes('npm run console:browser:reliability'));
  assert.ok(commands.includes('node scripts/check-doc-consistency.mjs'));
  assert.ok(commands.includes('node scripts/check-whitespace.mjs diff --check'));
});

test('normalizeEvidenceStep maps passed failed and deferred statuses', () => {
  assert.equal(normalizeEvidenceStep({ name: 'ok', command: 'true', status: 'passed' }).status, 'pass');
  assert.equal(normalizeEvidenceStep({ name: 'skip', command: 'android', status: 'deferred', reason: 'no emulator' }).status, 'deferred');
  assert.equal(normalizeEvidenceStep({ name: 'bad', command: 'false', status: 'failed', exitCode: 1 }).status, 'fail');
});

test('strict release gate fails deferred evidence', () => {
  const report = buildReleaseGateReport({
    strict: true,
    steps: [
      normalizeEvidenceStep({ name: 'Release reality', command: 'npm run release:reality', status: 'pass' }),
      normalizeEvidenceStep({ name: 'Runtime trust', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', reason: 'Android SDK unavailable' }),
    ],
  });

  assert.equal(report.status, 'fail');
});

test('non-strict release gate passes with deferred evidence', () => {
  const report = buildReleaseGateReport({
    strict: false,
    steps: [
      normalizeEvidenceStep({ name: 'Release reality', command: 'npm run release:reality', status: 'pass' }),
      normalizeEvidenceStep({ name: 'Runtime trust', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', reason: 'Android SDK unavailable' }),
    ],
  });

  assert.equal(report.status, 'pass_with_deferred');
});

test('markdown report renders deferred reasons', () => {
  const text = renderReleaseGateMarkdown({
    schemaVersion: '1.0',
    status: 'pass_with_deferred',
    strict: false,
    generatedAt: '2026-05-31T00:00:00.000Z',
    steps: [{
      name: 'Runtime trust',
      command: 'npm run source-matching:fixtures:runtime -- --strict',
      status: 'deferred',
      durationMs: 0,
      reason: 'Android SDK unavailable',
    }],
  });

  assert.match(text, /# FixThis Release Gate Report/);
  assert.match(text, /\| Runtime trust \| deferred \| `npm run source-matching:fixtures:runtime -- --strict` \| 0ms \| Android SDK unavailable \|/);
});

test('writeReleaseGateReports writes json and markdown artifacts', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-release-gate-'));
  try {
    const paths = writeReleaseGateReports({
      schemaVersion: '1.0',
      status: 'pass',
      strict: false,
      generatedAt: '2026-05-31T00:00:00.000Z',
      steps: [],
    }, root);
    assert.match(readFileSync(paths.json, 'utf8'), /"status": "pass"/);
    assert.match(readFileSync(paths.markdown, 'utf8'), /FixThis Release Gate Report/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('runReleaseGate supports injected runner for tests', () => {
  const report = runReleaseGate({
    strict: false,
    runEvidenceProfile: () => ({
      steps: [
        { name: 'Release reality', command: 'npm run release:reality', status: 'passed', durationMs: 1 },
        { name: 'Runtime trust', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', durationMs: 0, reason: 'Android SDK unavailable' },
      ],
    }),
  });

  assert.equal(report.status, 'pass_with_deferred');
});
