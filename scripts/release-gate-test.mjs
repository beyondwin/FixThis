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

test('releaseGateSteps include Trust Loop source agent release drift and console evidence', () => {
  const commands = releaseGateSteps().map((step) => step.command);
  assert.ok(commands.includes('npm run release:reality'));
  assert.ok(commands.includes('npm run release:drift -- --strict'));
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

test('release gate report maps evidence steps to unlocked claims', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-05-31T00:00:00.000Z',
    steps: [
      { name: 'Release reality', command: 'npm run release:reality', status: 'pass' },
      { name: 'Release drift strict', command: 'npm run release:drift -- --strict', status: 'pass' },
      { name: 'Agent loop smoke', command: 'npm run agent-loop:smoke -- --strict', status: 'deferred', reason: 'Android SDK unavailable' },
      { name: 'Runtime trust strict', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', reason: 'Android SDK unavailable' },
      { name: 'Console browser reliability', command: 'npm run console:browser:reliability', status: 'pass' },
    ],
  });

  assert.deepEqual(report.claims, [
    {
      id: 'release-reality',
      status: 'pass',
      evidence: ['Release reality'],
    },
    {
      id: 'release-source-drift',
      status: 'pass',
      evidence: ['Release drift strict'],
    },
    {
      id: 'external-agent-loop',
      status: 'deferred',
      evidence: ['Agent loop smoke'],
      reason: 'Android SDK unavailable',
    },
    {
      id: 'external-fixture-matrix',
      status: 'fail',
      evidence: [],
      reason: 'missing evidence command',
    },
    {
      id: 'runtime-source-trust',
      status: 'deferred',
      evidence: ['Runtime trust strict'],
      reason: 'Android SDK unavailable',
    },
    {
      id: 'console-sse-reliability',
      status: 'pass',
      evidence: ['Console browser reliability'],
    },
  ]);
});

test('release gate maps external fixture matrix claim', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-01T00:00:00.000Z',
    steps: [
      { name: 'External fixture matrix strict', command: 'npm run external-fixture:matrix -- --strict', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'external-fixture-matrix'), {
    id: 'external-fixture-matrix',
    status: 'deferred',
    evidence: ['External fixture matrix strict'],
    reason: 'Android SDK unavailable',
  });
});

test('release gate markdown renders claim statuses', () => {
  const text = renderReleaseGateMarkdown({
    schemaVersion: '1.0',
    status: 'pass_with_deferred',
    strict: false,
    generatedAt: '2026-05-31T00:00:00.000Z',
    claims: [{
      id: 'external-agent-loop',
      status: 'deferred',
      evidence: ['Agent loop smoke'],
      reason: 'Android SDK unavailable',
    }],
    steps: [],
  });

  assert.match(text, /## Release Claims/);
  assert.match(text, /\| external-agent-loop \| deferred \| Agent loop smoke \| Android SDK unavailable \|/);
});
