import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  buildMatrixReport,
  defaultManifestPath,
  externalMatrixStatusForEnvironment,
  loadExternalMatrixManifest,
  planFixtureCommands,
  renderMatrixMarkdown,
  validateExternalMatrixManifest,
  writeMatrixReports,
} from './external-fixture-matrix.mjs';

test('manifest covers required external project shapes', () => {
  const manifest = loadExternalMatrixManifest(defaultManifestPath);
  const shapes = new Set(manifest.fixtures.map((fixture) => fixture.projectShape));

  assert.equal(manifest.schemaVersion, 1);
  assert.ok(shapes.has('single-module'));
  assert.ok(shapes.has('multi-module'));
  assert.ok(shapes.has('single-module-flavor'));
  assert.ok(shapes.has('version-catalog'));
  assert.ok(shapes.has('preexisting-agent-config'));
  assert.ok(shapes.has('missing-generated-metadata'));
});

test('manifest rejects unsafe ids modules and expected setup values', () => {
  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 1,
      fixtures: [{
        id: 'Bad Id',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: 'app',
        moduleDir: '../app',
        applicationId: 'bad',
        variant: 'debug',
        expectedSetup: 'ready',
      }],
    }),
    /fixture id must use lowercase slug syntax.*appModule must start with :.*moduleDir escapes fixture root/s,
  );

  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 1,
      fixtures: [{
        id: 'bad-setup',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: ':app',
        moduleDir: 'app',
        applicationId: 'io.example.bad',
        variant: 'debug',
        expectedSetup: 'unknown',
      }],
    }),
    /expectedSetup must be ready or needs-fixthis-setup/,
  );
});

test('planFixtureCommands uses install-agent doctor and source-index commands', () => {
  const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'single-kotlin-dsl');
  const commands = planFixtureCommands(fixture, '/tmp/fixthis-matrix/single-kotlin-dsl');

  assert.deepEqual(commands.map((entry) => entry.name), [
    'install-agent',
    'doctor-json',
    'source-index',
  ]);
  assert.equal(commands[0].command, 'node /repo/fixthis-cli/build/install/fixthis/bin/fixthis install-agent --project-dir /tmp/fixthis-matrix/single-kotlin-dsl --target all --package io.github.beyondwin.fixthis.matrix.single');
  assert.equal(commands[1].command, 'node /repo/fixthis-cli/build/install/fixthis/bin/fixthis doctor --project-dir /tmp/fixthis-matrix/single-kotlin-dsl --package io.github.beyondwin.fixthis.matrix.single --json');
  assert.equal(commands[2].command, './gradlew :app:generateDebugFixThisSourceIndex --no-daemon');
});

test('environment status distinguishes non-strict deferral and strict failure', () => {
  assert.deepEqual(externalMatrixStatusForEnvironment({ strict: false, androidReady: false, reason: 'Android SDK unavailable' }), {
    status: 'deferred',
    exitCode: 0,
    reason: 'Android SDK unavailable',
  });
  assert.deepEqual(externalMatrixStatusForEnvironment({ strict: true, androidReady: false, reason: 'Android SDK unavailable' }), {
    status: 'fail',
    exitCode: 1,
    reason: 'Android SDK unavailable',
  });
});

test('matrix report and markdown include fixture command statuses', () => {
  const report = buildMatrixReport({
    strict: false,
    generatedAt: '2026-06-01T00:00:00.000Z',
    fixtures: [{
      fixtureId: 'single-kotlin-dsl',
      status: 'pass',
      commands: [{ name: 'doctor-json', command: 'fixthis doctor --json', status: 'pass', durationMs: 5 }],
    }],
  });

  assert.equal(report.status, 'pass');
  assert.match(renderMatrixMarkdown(report), /\| single-kotlin-dsl \| pass \|/);
  assert.match(renderMatrixMarkdown(report), /fixthis doctor --json/);
});

test('writeMatrixReports writes json and markdown reports', () => {
  const dir = mkdtempSync(join(tmpdir(), 'fixthis-external-matrix-'));
  try {
    const paths = writeMatrixReports(buildMatrixReport({ fixtures: [] }), dir);
    assert.match(readFileSync(paths.json, 'utf8'), /"schemaVersion": "1.0"/);
    assert.match(readFileSync(paths.markdown, 'utf8'), /External Fixture Matrix Report/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
