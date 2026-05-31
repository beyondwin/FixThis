import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  analyzeReleaseDrift,
  buildReleaseDriftReport,
  commandTokensFromReleaseReadiness,
  releaseDriftStatus,
  renderReleaseDriftMarkdown,
  writeReleaseDriftReports,
} from './release-drift-guard.mjs';

const baseFiles = {
  'CHANGELOG.md': [
    '# Changelog',
    '',
    '## Unreleased',
    '',
    '- Added external matrix evidence.',
    '',
    '## v1.1.0 - 2026-05-31',
  ].join('\n'),
  'docs/releases/unreleased.md': [
    '# Unreleased changes',
    '',
    '## Highlights',
    '',
    '- Added external matrix evidence.',
  ].join('\n'),
  'docs/contributing/release-readiness.md': [
    '# Release Readiness',
    '',
    '| Claim | Required evidence |',
    '| --- | --- |',
    '| Matrix works. | `npm run external-fixture:matrix:test` and `npm run release:drift`. |',
  ].join('\n'),
  'package.json': JSON.stringify({
    scripts: {
      'external-fixture:matrix:test': 'node --test scripts/external-fixture-matrix-test.mjs',
      'release:drift': 'node scripts/release-drift-guard.mjs',
    },
  }, null, 2),
};

function analyze(overrides = {}) {
  const files = { ...baseFiles, ...(overrides.files || {}) };
  return analyzeReleaseDrift({
    files,
    git: {
      latestTag: overrides.latestTag ?? 'v1.1.0',
      commitsSinceTag: overrides.commitsSinceTag ?? ['abc1234 docs: add matrix'],
    },
    strict: overrides.strict ?? false,
  });
}

test('tag-distance drift is detected when unreleased notes still claim no changes', () => {
  const result = analyze({
    files: {
      'CHANGELOG.md': '# Changelog\n\n## Unreleased\n\nNo unreleased changes yet.\n',
      'docs/releases/unreleased.md': '# Unreleased changes\n\nNo unreleased changes have landed after v1.1.0 yet.\n',
    },
    commitsSinceTag: ['abc1234 feat: add evidence'],
  });

  assert.equal(result.findings[0].id, 'tag-distance-drift');
  assert.equal(result.findings[0].severity, 'warning');
  assert.match(result.findings[0].message, /1 commit exists after v1\.1\.0/);
});

test('strict mode fails tag-distance drift', () => {
  const result = analyze({
    strict: true,
    files: {
      'CHANGELOG.md': '# Changelog\n\n## Unreleased\n\nNo unreleased changes yet.\n',
      'docs/releases/unreleased.md': '# Unreleased changes\n\nNo unreleased changes have landed after v1.1.0 yet.\n',
    },
  });

  assert.equal(releaseDriftStatus(result.findings, true), 'fail');
});

test('changelog and unreleased notes disagreement is actionable', () => {
  const result = analyze({
    files: {
      'CHANGELOG.md': '# Changelog\n\n## Unreleased\n\n- Added drift guard.\n',
      'docs/releases/unreleased.md': '# Unreleased changes\n\nNo unreleased changes have landed after v1.1.0 yet.\n',
    },
  });

  assert.deepEqual(result.findings.map((finding) => finding.id), ['unreleased-notes-drift']);
  assert.match(result.findings[0].message, /CHANGELOG.md has unreleased content/);
});

test('release readiness command tokens must exist in package scripts or known binaries', () => {
  const tokens = commandTokensFromReleaseReadiness(baseFiles['docs/contributing/release-readiness.md']);
  assert.deepEqual(tokens.sort(), ['npm run external-fixture:matrix:test', 'npm run release:drift']);

  const result = analyze({
    files: {
      'docs/contributing/release-readiness.md': '| Claim | Required evidence |\n| --- | --- |\n| Bad. | `npm run missing:script`. |',
    },
  });

  assert.equal(result.findings[0].id, 'release-readiness-command-drift');
  assert.match(result.findings[0].message, /npm run missing:script/);
});

test('report normalizes warning and fail status', () => {
  const warning = buildReleaseDriftReport(analyze().findings, { strict: false, generatedAt: '2026-06-01T00:00:00.000Z' });
  assert.equal(warning.status, 'pass');

  const failing = buildReleaseDriftReport(analyze({ strict: true }).findings, { strict: true, generatedAt: '2026-06-01T00:00:00.000Z' });
  assert.equal(failing.status, 'pass');

  const drift = buildReleaseDriftReport(analyze({
    strict: true,
    files: {
      'CHANGELOG.md': '# Changelog\n\n## Unreleased\n\nNo unreleased changes yet.\n',
      'docs/releases/unreleased.md': '# Unreleased changes\n\nNo unreleased changes have landed after v1.1.0 yet.\n',
    },
  }).findings, { strict: true, generatedAt: '2026-06-01T00:00:00.000Z' });
  assert.equal(drift.status, 'fail');
});

test('markdown and json reports are written', () => {
  const dir = mkdtempSync(join(tmpdir(), 'fixthis-release-drift-'));
  try {
    const report = buildReleaseDriftReport(analyze().findings, { strict: false, generatedAt: '2026-06-01T00:00:00.000Z' });
    const paths = writeReleaseDriftReports(report, dir);
    assert.match(readFileSync(paths.json, 'utf8'), /"schemaVersion": "1.0"/);
    assert.match(readFileSync(paths.markdown, 'utf8'), /FixThis Release Drift Report/);
    assert.match(renderReleaseDriftMarkdown(report), /\| Status \| Strict \|/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
