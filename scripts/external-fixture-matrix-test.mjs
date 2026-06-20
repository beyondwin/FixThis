import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  buildMatrixReport,
  cliExecutablePath,
  confidenceRank,
  defaultManifestPath,
  evaluateTrustExpectations,
  externalMatrixStatusForEnvironment,
  generateFixtureProject,
  loadExternalMatrixManifest,
  normalizeFixtureCommandResult,
  outcomeForFixture,
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

  assert.equal(manifest.schemaVersion, 2);
  assert.ok(shapes.has('single-module'));
  assert.ok(shapes.has('multi-module'));
  assert.ok(shapes.has('single-module-flavor'));
  assert.ok(shapes.has('version-catalog'));
  assert.ok(shapes.has('preexisting-agent-config'));
  assert.ok(shapes.has('missing-generated-metadata'));
});

test('manifest v2 declares runtime capability and trust expectations', () => {
  const manifest = loadExternalMatrixManifest(defaultManifestPath);
  const single = manifest.fixtures.find((fixture) => fixture.id === 'single-kotlin-dsl');
  const weak = manifest.fixtures.find((fixture) => fixture.id === 'weak-source-caveated');
  const runtime = manifest.fixtures.find((fixture) => fixture.id === 'local-sample-first-handoff');

  assert.equal(manifest.schemaVersion, 2);
  assert.equal(single.runtimeCapability, 'setup-only');
  assert.equal(weak.runtimeCapability, 'setup-only');
  assert.deepEqual(weak.trustExpectations, [{
    kind: 'weak-source',
    mustNotHighConfidence: true,
    mustWarn: ['WEAK_SOURCE_EVIDENCE'],
  }]);
  assert.equal(runtime.runtimeCapability, 'first-handoff-trust');
  assert.deepEqual(runtime.trustExpectations.map((entry) => entry.kind), [
    'visual-area',
    'interop-boundary',
    'shared-component',
  ]);
});

test('manifest v2 rejects invalid runtime capability and trust expectations', () => {
  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 2,
      fixtures: [{
        id: 'bad-runtime',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: ':app',
        moduleDir: 'app',
        applicationId: 'io.example.bad',
        variant: 'debug',
        expectedSetup: 'ready',
        runtimeCapability: 'browser-cloud',
        trustExpectations: [],
      }],
    }),
    /bad-runtime runtimeCapability is unsupported/,
  );

  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 2,
      fixtures: [{
        id: 'bad-trust',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: ':app',
        moduleDir: 'app',
        applicationId: 'io.example.bad',
        variant: 'debug',
        expectedSetup: 'ready',
        runtimeCapability: 'setup-only',
        trustExpectations: [{ kind: 'exact-webview-source', mustWarn: 'POSSIBLE_VIEW_INTEROP' }],
      }],
    }),
    /bad-trust trustExpectations\[0\] kind is unsupported.*mustWarn must be an array/s,
  );
});

test('manifest rejects unsafe ids modules and expected setup values', () => {
  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 2,
      fixtures: [{
        id: 'Bad Id',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: 'app',
        moduleDir: '../app',
        applicationId: 'bad',
        variant: 'debug',
        expectedSetup: 'ready',
        runtimeCapability: 'setup-only',
        trustExpectations: [],
      }],
    }),
    /fixture id must use lowercase slug syntax.*appModule must start with :.*moduleDir escapes fixture root/s,
  );

  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 2,
      fixtures: [{
        id: 'bad-setup',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: ':app',
        moduleDir: 'app',
        applicationId: 'io.example.bad',
        variant: 'debug',
        expectedSetup: 'unknown',
        runtimeCapability: 'setup-only',
        trustExpectations: [],
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

test('matrix v2 report summarizes outcomes and trust findings', () => {
  const report = buildMatrixReport({
    strict: false,
    generatedAt: '2026-06-20T00:00:00.000Z',
    fixtures: [
      {
        fixtureId: 'weak-source-caveated',
        status: 'pass',
        outcome: 'caveated_pass',
        runtimeCapability: 'setup-only',
        trustFindings: [{ kind: 'weak-source', status: 'pass', message: 'weak evidence stayed caveated' }],
        commands: [{ name: 'doctor-json', command: 'fixthis doctor --json', status: 'pass', durationMs: 5 }],
      },
      {
        fixtureId: 'local-sample-first-handoff',
        status: 'deferred',
        outcome: 'environment_deferred',
        runtimeCapability: 'first-handoff-trust',
        reason: 'Android SDK or ready emulator is unavailable.',
        trustFindings: [],
        commands: [],
      },
    ],
  });

  assert.equal(report.schemaVersion, '2.0');
  assert.deepEqual(report.summary, {
    total: 2,
    pass: 1,
    deferred: 1,
    fail: 0,
    fixtureDrift: 0,
    caveatedPass: 1,
  });
  const markdown = renderMatrixMarkdown(report);
  assert.match(markdown, /## Summary/);
  assert.match(markdown, /\| Caveated pass \| 1 \|/);
  assert.match(markdown, /## Trust Findings/);
  assert.match(markdown, /\| weak-source-caveated \| weak-source \| pass \| weak evidence stayed caveated \|/);
});

test('confidenceRank orders handoff confidence levels', () => {
  assert.equal(confidenceRank('unknown'), 0);
  assert.equal(confidenceRank('low'), 1);
  assert.equal(confidenceRank('medium'), 2);
  assert.equal(confidenceRank('high'), 3);
  assert.equal(confidenceRank('HIGH'), 3);
  assert.equal(confidenceRank(undefined), 0);
});

test('evaluateTrustExpectations passes caveated visual interop and shared-component observations', () => {
  const fixture = {
    id: 'local-sample-first-handoff',
    trustExpectations: [
      { kind: 'visual-area', mustNotHighConfidence: true, mustWarn: ['VISUAL_AREA_ONLY'] },
      { kind: 'interop-boundary', mustNotExactOwnership: true, mustWarn: ['POSSIBLE_VIEW_INTEROP'] },
      { kind: 'shared-component', mustNotHighConfidence: true, mustRisk: ['SHARED_COMPONENT'] },
    ],
  };

  assert.deepEqual(evaluateTrustExpectations(fixture, {
    targetReliability: {
      confidence: 'medium',
      warnings: ['VISUAL_AREA_ONLY', 'POSSIBLE_VIEW_INTEROP'],
    },
    sourceCandidates: [
      {
        confidence: 'medium',
        riskFlags: ['SHARED_COMPONENT'],
        callSites: [{ file: 'sample/Home.kt', line: 42, recommendedEditSite: true }],
      },
    ],
    exactOwnershipClaimed: false,
  }), [
    { kind: 'visual-area', status: 'pass', message: 'confidence medium with warnings VISUAL_AREA_ONLY' },
    { kind: 'interop-boundary', status: 'pass', message: 'no exact ownership claim and warnings POSSIBLE_VIEW_INTEROP' },
    { kind: 'shared-component', status: 'pass', message: 'confidence medium with risk flags SHARED_COMPONENT' },
  ]);
});

test('evaluateTrustExpectations fails high confidence missing warning and exact ownership', () => {
  const fixture = {
    id: 'bad-runtime',
    trustExpectations: [
      { kind: 'visual-area', mustNotHighConfidence: true, mustWarn: ['VISUAL_AREA_ONLY'] },
      { kind: 'interop-boundary', mustNotExactOwnership: true, mustWarn: ['POSSIBLE_VIEW_INTEROP'] },
    ],
  };

  assert.deepEqual(evaluateTrustExpectations(fixture, {
    targetReliability: { confidence: 'high', warnings: [] },
    sourceCandidates: [{ confidence: 'high', riskFlags: [] }],
    exactOwnershipClaimed: true,
  }), [
    { kind: 'visual-area', status: 'fail', message: 'high confidence or missing warnings: VISUAL_AREA_ONLY' },
    { kind: 'interop-boundary', status: 'fail', message: 'exact ownership claimed or missing warnings: POSSIBLE_VIEW_INTEROP' },
  ]);
});

test('outcomeForFixture classifies trust outcomes without hiding deferred or drift states', () => {
  assert.equal(outcomeForFixture({ status: 'deferred', reason: 'Android SDK unavailable', trustFindings: [] }), 'environment_deferred');
  assert.equal(outcomeForFixture({ status: 'fail', reason: 'fixture_drift: expected path disappeared', trustFindings: [] }), 'fixture_drift');
  assert.equal(outcomeForFixture({ status: 'fail', reason: 'doctor-json failed', trustFindings: [] }), 'product_failure');
  assert.equal(outcomeForFixture({ status: 'pass', trustFindings: [{ kind: 'weak-source', status: 'pass', message: 'caveated' }] }), 'caveated_pass');
  assert.equal(outcomeForFixture({ status: 'pass', trustFindings: [] }), 'pass');
});

test('writeMatrixReports writes json and markdown reports', () => {
  const dir = mkdtempSync(join(tmpdir(), 'fixthis-external-matrix-'));
  try {
    const paths = writeMatrixReports(buildMatrixReport({ fixtures: [] }), dir);
    assert.match(readFileSync(paths.json, 'utf8'), /"schemaVersion": "2.0"/);
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

test('runExternalMatrix attaches trust findings from injected observations', () => {
  const fixture = {
    ...loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'local-sample-first-handoff'),
    trustExpectations: [
      { kind: 'visual-area', mustNotHighConfidence: true, mustWarn: ['VISUAL_AREA_ONLY'] },
    ],
  };
  const report = runExternalMatrix({
    manifest: {
      schemaVersion: 2,
      fixtures: [fixture],
    },
    strict: true,
    workRoot: '/tmp/fixthis-matrix',
    androidEnvironment: { ready: true, reason: null, envPatch: {} },
    root: '/repo',
    runCommandFn: () => ({ status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 }),
    prepareCliDistributionFn: () => ({ name: 'prepare-cli', command: './gradlew :fixthis-cli:installDist --no-daemon', status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 }),
    generateFixtureProjectFn: () => {},
    cleanupFixtureFn: () => {},
    trustObservationFn: (activeFixture) => {
      assert.equal(activeFixture.id, 'local-sample-first-handoff');
      return {
        targetReliability: {
          confidence: 'medium',
          warnings: ['VISUAL_AREA_ONLY'],
        },
      };
    },
  });

  assert.equal(report.status, 'pass');
  assert.equal(report.fixtures[0].outcome, 'caveated_pass');
  assert.deepEqual(report.fixtures[0].trustFindings, [
    { kind: 'visual-area', status: 'pass', message: 'confidence medium with warnings VISUAL_AREA_ONLY' },
  ]);
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
