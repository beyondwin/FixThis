import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  buildMatrixReport,
  cliExecutablePath,
  defaultManifestPath,
  externalMatrixStatusForEnvironment,
  generateFixtureProject,
  loadExternalMatrixManifest,
  normalizeFixtureCommandResult,
  planFixtureCommands,
  prepareCliDistribution,
  renderMatrixMarkdown,
  runExternalMatrix,
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
  assert.equal(commands[0].command, "JAVA_OPTS='-Duser.home=/tmp/fixthis-matrix/single-kotlin-dsl/.home' '/repo/fixthis-cli/build/install/fixthis/bin/fixthis' install-agent --project-dir '/tmp/fixthis-matrix/single-kotlin-dsl' --target all --package 'io.github.beyondwin.fixthis.matrix.single'");
  assert.equal(commands[1].command, "JAVA_OPTS='-Duser.home=/tmp/fixthis-matrix/single-kotlin-dsl/.home' '/repo/fixthis-cli/build/install/fixthis/bin/fixthis' doctor --project-dir '/tmp/fixthis-matrix/single-kotlin-dsl' --package 'io.github.beyondwin.fixthis.matrix.single' --json");
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

test('package exposes external fixture matrix commands', () => {
  const pkg = JSON.parse(readFileSync('package.json', 'utf8'));
  assert.equal(pkg.scripts['external-fixture:matrix'], 'node scripts/external-fixture-matrix.mjs');
  assert.equal(pkg.scripts['external-fixture:matrix:test'], 'node --test scripts/external-fixture-matrix-test.mjs');
});

test('generateFixtureProject creates a minimal Gradle Compose project shape', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-matrix-generate-'));
  try {
    const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'multi-module-non-root-app');
    const projectDir = join(root, fixture.id);
    generateFixtureProject(fixture, projectDir, '/repo');

    assert.equal(existsSync(join(projectDir, 'gradlew')), true);
    assert.match(readFileSync(join(projectDir, 'gradlew'), 'utf8'), /exec '\/repo\/gradlew' -p "\$project_dir" "\$@"/);
    assert.equal(existsSync(join(projectDir, 'settings.gradle.kts')), true);
    assert.equal(existsSync(join(projectDir, 'features/demo-app/build.gradle.kts')), true);
    assert.match(readFileSync(join(projectDir, 'features/demo-app/src/main/AndroidManifest.xml'), 'utf8'), /io\.github\.beyondwin\.fixthis\.matrix\.multimodule/);
    assert.doesNotMatch(readFileSync(join(projectDir, 'features/demo-app/src/main/AndroidManifest.xml'), 'utf8'), /@style\/AppTheme/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('prepareCliDistribution builds the local CLI when installDist output is missing', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-matrix-cli-'));
  const calls = [];
  try {
    const result = prepareCliDistribution(root, { ANDROID_HOME: '/sdk' }, (command, cwd, envPatch) => {
      calls.push({ command, cwd, envPatch });
      return { status: 'pass', durationMs: 4, stdout: '', stderr: '', exitCode: 0 };
    });

    assert.equal(result.status, 'pass');
    assert.equal(result.command, './gradlew :fixthis-cli:installDist --no-daemon');
    assert.deepEqual(calls, [{
      command: './gradlew :fixthis-cli:installDist --no-daemon',
      cwd: root,
      envPatch: { ANDROID_HOME: '/sdk' },
    }]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('prepareCliDistribution skips the build when installDist output already exists', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-matrix-cli-'));
  try {
    const executable = cliExecutablePath(root);
    assert.equal(existsSync(executable), false);
    const binDir = join(root, 'fixthis-cli/build/install/fixthis/bin');
    mkdirSync(binDir, { recursive: true });
    writeFileSync(join(binDir, 'fixthis'), '#!/usr/bin/env bash\n');

    const result = prepareCliDistribution(root, {}, () => {
      throw new Error('runner should not be called');
    });

    assert.equal(result.status, 'pass');
    assert.equal(result.skipped, true);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('normalizeFixtureCommandResult accepts doctor reaching app launch readiness', () => {
  const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures[0];
  const result = normalizeFixtureCommandResult(
    { name: 'doctor-json' },
    fixture,
    {
      status: 'fail',
      durationMs: 1,
      exitCode: 1,
      stderr: '1 doctor check(s) failed\n',
      stdout: JSON.stringify({
        schemaVersion: '1.0',
        ok: false,
        nextAction: 'Open app',
        readiness: { state: 'NEEDS_APP_LAUNCH' },
        checks: [
          { name: 'android_project_found', status: 'ok' },
          { name: 'fixthis_project_metadata_found', status: 'ok' },
          { name: 'adb_found', status: 'ok' },
          { name: 'device_connected', status: 'ok' },
          { name: 'sidekick_session_found', status: 'fail' },
        ],
      }),
    },
  );

  assert.equal(result.status, 'pass');
  assert.equal(result.acceptedExitCode, 1);
  assert.equal(result.acceptedReadinessState, 'NEEDS_APP_LAUNCH');
});

test('runExternalMatrix executes non-connected commands with injected runner', () => {
  const calls = [];
  const lifecycle = [];
  const manifest = {
    schemaVersion: 1,
    fixtures: [loadExternalMatrixManifest(defaultManifestPath).fixtures[0]],
  };
  const report = runExternalMatrix({
    manifest,
    strict: false,
    workRoot: '/tmp/fixthis-matrix',
    androidEnvironment: { ready: true, reason: null, envPatch: {} },
    root: '/repo',
    runCommandFn: (command) => {
      lifecycle.push(`run:${command}`);
      calls.push(command);
      return { status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 };
    },
    prepareCliDistributionFn: () => {
      lifecycle.push('prepare-cli');
      return { name: 'prepare-cli', command: './gradlew :fixthis-cli:installDist --no-daemon', status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 };
    },
    generateFixtureProjectFn: () => {},
    cleanupFixtureFn: () => {},
  });

  assert.equal(report.status, 'pass');
  assert.equal(report.fixtures[0].status, 'pass');
  assert.equal(calls.length, 3);
  assert.equal(report.fixtures[0].commands[0].name, 'prepare-cli');
  assert.equal(lifecycle[0], 'prepare-cli');
});

test('runExternalMatrix continues after accepted doctor next action', () => {
  const calls = [];
  const manifest = {
    schemaVersion: 1,
    fixtures: [loadExternalMatrixManifest(defaultManifestPath).fixtures[0]],
  };
  const report = runExternalMatrix({
    manifest,
    strict: true,
    workRoot: '/tmp/fixthis-matrix',
    androidEnvironment: { ready: true, reason: null, envPatch: {} },
    root: '/repo',
    runCommandFn: (command) => {
      calls.push(command);
      if (command.includes(' doctor ')) {
        return {
          status: 'fail',
          durationMs: 1,
          stdout: JSON.stringify({
            schemaVersion: '1.0',
            nextAction: 'Open app',
            readiness: { state: 'NEEDS_APP_LAUNCH' },
            checks: [
              { name: 'android_project_found', status: 'ok' },
              { name: 'fixthis_project_metadata_found', status: 'ok' },
              { name: 'adb_found', status: 'ok' },
              { name: 'device_connected', status: 'ok' },
            ],
          }),
          stderr: '1 doctor check(s) failed\n',
          exitCode: 1,
        };
      }
      return { status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 };
    },
    prepareCliDistributionFn: () => ({ name: 'prepare-cli', command: './gradlew :fixthis-cli:installDist --no-daemon', status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 }),
    generateFixtureProjectFn: () => {},
    cleanupFixtureFn: () => {},
  });

  assert.equal(report.status, 'pass');
  assert.equal(report.fixtures[0].status, 'pass');
  assert.equal(calls.length, 3);
  assert.equal(report.fixtures[0].commands.find((command) => command.name === 'doctor-json').acceptedReadinessState, 'NEEDS_APP_LAUNCH');
});

test('runExternalMatrix keeps deferred fixtures out of command execution when Android is missing', () => {
  const calls = [];
  const manifest = {
    schemaVersion: 1,
    fixtures: [loadExternalMatrixManifest(defaultManifestPath).fixtures[0]],
  };
  const report = runExternalMatrix({
    manifest,
    strict: false,
    workRoot: '/tmp/fixthis-matrix',
    androidEnvironment: { ready: false, reason: 'Android SDK unavailable', envPatch: {} },
    runCommandFn: (command) => {
      calls.push(command);
      return { status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 };
    },
    generateFixtureProjectFn: () => {},
    cleanupFixtureFn: () => {},
  });

  assert.equal(report.status, 'pass_with_deferred');
  assert.equal(report.fixtures[0].status, 'deferred');
  assert.equal(calls.length, 0);
});
