#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, normalize, resolve, sep } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { resolveAndroidEnvironment } from './evidence-runner.mjs';

const scriptPath = fileURLToPath(import.meta.url);
export const repoRoot = resolve(dirname(scriptPath), '..');
export const defaultManifestPath = join(repoRoot, 'fixtures/external-project-matrix/manifest.json');
export const defaultMatrixReportDir = join(repoRoot, 'build/reports/fixthis-external-fixture-matrix');
export const defaultMatrixWorkRoot = join(repoRoot, '.fixthis/external-fixture-matrix');
export const defaultCliInstallTask = './gradlew :fixthis-cli:installDist --no-daemon';

const allowedShapes = new Set([
  'single-module',
  'multi-module',
  'single-module-flavor',
  'version-catalog',
  'preexisting-agent-config',
  'missing-generated-metadata',
]);
const allowedExpectedSetup = new Set(['ready', 'needs-fixthis-setup']);
export const runtimeCapabilities = Object.freeze(['setup-only', 'first-handoff-trust']);
export const trustExpectationKinds = Object.freeze([
  'visual-area',
  'interop-boundary',
  'shared-component',
  'weak-source',
]);

function arrayOfStrings(value, fieldName) {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.some((entry) => typeof entry !== 'string' || entry.length === 0)) {
    throw new Error(`${fieldName} must be an array of non-empty strings`);
  }
  return value;
}

export function validateTrustExpectations(fixture) {
  const errors = [];
  const expectations = fixture.trustExpectations ?? [];
  if (!Array.isArray(expectations)) return [`${fixture.id || 'fixture'} trustExpectations must be an array`];
  expectations.forEach((expectation, index) => {
    const label = `${fixture.id || 'fixture'} trustExpectations[${index}]`;
    if (!trustExpectationKinds.includes(expectation?.kind)) errors.push(`${label} kind is unsupported`);
    try { arrayOfStrings(expectation.mustWarn, `${label} mustWarn`); } catch (error) { errors.push(error.message); }
    try { arrayOfStrings(expectation.mustRisk, `${label} mustRisk`); } catch (error) { errors.push(error.message); }
    if (expectation.mustNotHighConfidence !== undefined && typeof expectation.mustNotHighConfidence !== 'boolean') {
      errors.push(`${label} mustNotHighConfidence must be boolean`);
    }
    if (expectation.mustNotExactOwnership !== undefined && typeof expectation.mustNotExactOwnership !== 'boolean') {
      errors.push(`${label} mustNotExactOwnership must be boolean`);
    }
  });
  return errors;
}

function safeRelativePath(value, fieldName = 'path') {
  if (typeof value !== 'string' || value.length === 0) throw new Error(`${fieldName} must be a non-empty string`);
  if (value.startsWith('/') || /^[A-Za-z]:[\\/]/.test(value)) throw new Error(`${fieldName} must be relative`);
  const normalized = normalize(value);
  if (normalized === '..' || normalized.startsWith(`..${sep}`) || normalized.includes(`${sep}..${sep}`)) {
    throw new Error(`${fieldName} escapes fixture root`);
  }
  return normalized.replaceAll('\\', '/');
}

function variantTaskSuffix(variant) {
  return variant.charAt(0).toUpperCase() + variant.slice(1);
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`;
}

export function cliExecutablePath(root = repoRoot) {
  return join(root, 'fixthis-cli/build/install/fixthis/bin/fixthis');
}

export function validateExternalMatrixManifest(manifest) {
  const errors = [];
  if (!manifest || typeof manifest !== 'object') errors.push('manifest must be an object');
  if (manifest?.schemaVersion !== 2) errors.push('schemaVersion must be 2');
  if (!Array.isArray(manifest?.fixtures) || manifest.fixtures.length === 0) errors.push('fixtures must contain at least one fixture');
  for (const fixture of manifest?.fixtures || []) {
    const label = fixture.id || 'fixture';
    if (!/^[a-z0-9-]+$/.test(fixture.id || '')) errors.push('fixture id must use lowercase slug syntax');
    if (!allowedShapes.has(fixture.projectShape)) errors.push(`${label} projectShape is unsupported`);
    if (fixture.dsl !== 'kotlin') errors.push(`${label} dsl must be kotlin`);
    if (!String(fixture.appModule || '').startsWith(':')) errors.push(`${label} appModule must start with :`);
    try { safeRelativePath(fixture.moduleDir, `${label} moduleDir`); } catch (error) { errors.push(error.message); }
    if (!/^[-A-Za-z0-9_.]+$/.test(fixture.applicationId || '')) errors.push(`${label} applicationId must be package-like`);
    if (!fixture.variant) errors.push(`${label} variant must be present`);
    if (!allowedExpectedSetup.has(fixture.expectedSetup)) errors.push(`${label} expectedSetup must be ready or needs-fixthis-setup`);
    if (!runtimeCapabilities.includes(fixture.runtimeCapability)) errors.push(`${label} runtimeCapability is unsupported`);
    errors.push(...validateTrustExpectations(fixture));
  }
  if (errors.length) throw new Error(errors.join('; '));
  return manifest;
}

export function loadExternalMatrixManifest(path = defaultManifestPath) {
  return validateExternalMatrixManifest(JSON.parse(readFileSync(path, 'utf8')));
}

export function planFixtureCommands(fixture, projectDir, root = '/repo') {
  const cli = shellQuote(cliExecutablePath(root));
  const userHomeOpt = shellQuote(`-Duser.home=${join(projectDir, '.home')}`);
  const project = shellQuote(projectDir);
  return [
    {
      name: 'install-agent',
      command: `JAVA_OPTS=${userHomeOpt} ${cli} install-agent --project-dir ${project} --target all --package ${shellQuote(fixture.applicationId)}`,
    },
    {
      name: 'doctor-json',
      command: `JAVA_OPTS=${userHomeOpt} ${cli} doctor --project-dir ${project} --package ${shellQuote(fixture.applicationId)} --json`,
    },
    {
      name: 'source-index',
      command: `./gradlew ${fixture.appModule}:generate${variantTaskSuffix(fixture.variant)}FixThisSourceIndex --no-daemon`,
    },
  ];
}

export function planRuntimeTrustCommands(fixture) {
  if (fixture.runtimeCapability !== 'first-handoff-trust') return [];
  return [
    { name: 'agent-loop-smoke', command: 'npm run agent-loop:smoke -- --strict' },
    { name: 'runtime-trust-strict', command: 'npm run source-matching:fixtures:runtime -- --strict' },
  ];
}

export function externalMatrixStatusForEnvironment({ strict = false, androidReady = false, reason = 'Android SDK or ready emulator is unavailable.' } = {}) {
  if (androidReady) return { status: 'pass', exitCode: 0, reason: null };
  return strict
    ? { status: 'fail', exitCode: 1, reason }
    : { status: 'deferred', exitCode: 0, reason };
}

const confidenceRanks = new Map([
  ['unknown', 0],
  ['none', 0],
  ['low', 1],
  ['medium', 2],
  ['high', 3],
]);

function normalizeConfidence(value) {
  if (typeof value !== 'string') return 'unknown';
  return value.trim().toLowerCase().replaceAll('_', '-');
}

export function confidenceRank(value) {
  return confidenceRanks.get(normalizeConfidence(value).replaceAll('-', '_')) ??
    confidenceRanks.get(normalizeConfidence(value)) ??
    0;
}

function normalizeTrustToken(value) {
  return String(value || '').trim().toUpperCase().replaceAll('-', '_');
}

function hasOwn(object, key) {
  return Object.prototype.hasOwnProperty.call(object || {}, key);
}

function asArray(value) {
  if (Array.isArray(value)) return value;
  return value === undefined || value === null ? [] : [value];
}

function observationItems(observation) {
  if (!observation) return [];
  if (Array.isArray(observation)) return observation;
  if (Array.isArray(observation.items)) return observation.items;
  if (observation.item) return [observation.item];
  return [observation];
}

function collectWarnings(observation) {
  const warnings = new Set();
  for (const item of observationItems(observation)) {
    for (const warning of [
      ...asArray(item.warnings),
      ...asArray(item.warning),
      ...asArray(item.targetReliability?.warnings),
      ...asArray(item.targetEvidence?.warnings),
    ]) {
      warnings.add(normalizeTrustToken(warning));
    }
  }
  return warnings;
}

function collectRiskFlags(observation) {
  const riskFlags = new Set();
  for (const item of observationItems(observation)) {
    for (const risk of [...asArray(item.riskFlags), ...asArray(item.riskFlag)]) {
      riskFlags.add(normalizeTrustToken(risk));
    }
    for (const candidate of asArray(item.sourceCandidates)) {
      for (const risk of asArray(candidate?.riskFlags)) {
        riskFlags.add(normalizeTrustToken(risk));
      }
    }
  }
  return riskFlags;
}

function collectConfidenceValues(observation) {
  const values = [];
  for (const item of observationItems(observation)) {
    if (hasOwn(item, 'confidence')) values.push(item.confidence);
    if (hasOwn(item.targetReliability, 'confidence')) values.push(item.targetReliability.confidence);
    for (const candidate of asArray(item.sourceCandidates)) {
      if (hasOwn(candidate, 'confidence')) values.push(candidate.confidence);
    }
    for (const candidate of asArray(item.editSurfaceCandidates)) {
      if (hasOwn(candidate, 'confidence')) values.push(candidate.confidence);
    }
  }
  return values;
}

function highestConfidence(observation) {
  return collectConfidenceValues(observation).reduce((highest, value) => (
    confidenceRank(value) > confidenceRank(highest) ? value : highest
  ), 'unknown');
}

function hasExactOwnershipClaim(observation) {
  for (const item of observationItems(observation)) {
    if (item.exactOwnershipClaimed === true) return true;
    if (item.exactOwnership === true || item.claimsExactOwnership === true) return true;
    if (String(item.ownership || '').toLowerCase() === 'exact') return true;
    for (const candidate of asArray(item.editSurfaceCandidates)) {
      if (candidate?.exactOwnership === true || candidate?.claimsExactOwnership === true) return true;
      if (String(candidate?.ownership || '').toLowerCase() === 'exact') return true;
    }
  }
  return false;
}

export function evaluateTrustExpectations(fixture, observation) {
  const expectations = fixture?.trustExpectations ?? [];
  if (!Array.isArray(expectations) || expectations.length === 0) return [];
  if (!observation) {
    return expectations.map((expectation) => ({
      kind: expectation.kind,
      status: 'fail',
      message: `${expectation.kind} trust observation unavailable`,
    }));
  }

  const warnings = collectWarnings(observation);
  const riskFlags = collectRiskFlags(observation);
  const confidence = highestConfidence(observation);
  const confidenceLabel = normalizeConfidence(confidence);

  return expectations.map((expectation) => {
    const requiredWarnings = arrayOfStrings(expectation.mustWarn, `${expectation.kind} mustWarn`);
    const requiredRisks = arrayOfStrings(expectation.mustRisk, `${expectation.kind} mustRisk`);
    const missingWarnings = requiredWarnings.filter((warning) => !warnings.has(normalizeTrustToken(warning)));
    const missingRisks = requiredRisks.filter((risk) => !riskFlags.has(normalizeTrustToken(risk)));
    const highConfidence = expectation.mustNotHighConfidence === true && confidenceRank(confidence) >= confidenceRank('high');
    const exactOwnership = expectation.mustNotExactOwnership === true && hasExactOwnershipClaim(observation);

    if (expectation.kind === 'interop-boundary') {
      if (exactOwnership || missingWarnings.length > 0) {
        return {
          kind: expectation.kind,
          status: 'fail',
          message: `exact ownership claimed or missing warnings: ${missingWarnings.join(', ') || requiredWarnings.join(', ') || 'none'}`,
        };
      }
      return {
        kind: expectation.kind,
        status: 'pass',
        message: `no exact ownership claim and warnings ${requiredWarnings.join(', ')}`,
      };
    }

    if (missingRisks.length > 0) {
      return {
        kind: expectation.kind,
        status: 'fail',
        message: `missing risk flags: ${missingRisks.join(', ')}`,
      };
    }
    if (highConfidence || missingWarnings.length > 0) {
      return {
        kind: expectation.kind,
        status: 'fail',
        message: `high confidence or missing warnings: ${missingWarnings.join(', ') || 'none'}`,
      };
    }
    if (requiredRisks.length > 0) {
      return {
        kind: expectation.kind,
        status: 'pass',
        message: `confidence ${confidenceLabel} with risk flags ${requiredRisks.join(', ')}`,
      };
    }
    return {
      kind: expectation.kind,
      status: 'pass',
      message: `confidence ${confidenceLabel} with warnings ${requiredWarnings.join(', ')}`,
    };
  });
}

export function outcomeForFixture({ status, trustFindings = [], reason = null } = {}) {
  if (status === 'deferred') return 'environment_deferred';
  if (/fixture[_ -]drift/i.test(String(reason || ''))) return 'fixture_drift';
  if (status === 'fail' || trustFindings.some((finding) => finding.status === 'fail')) return 'product_failure';
  if (trustFindings.some((finding) => finding.status === 'pass')) return 'caveated_pass';
  return 'pass';
}

function reportStatus(fixtures, strict = false) {
  if ((fixtures || []).some((fixture) => fixture.status === 'fail')) return 'fail';
  if (strict && (fixtures || []).some((fixture) => fixture.status === 'deferred')) return 'fail';
  if ((fixtures || []).some((fixture) => fixture.status === 'deferred')) return 'pass_with_deferred';
  return 'pass';
}

function reportSummary(fixtures = []) {
  return {
    total: fixtures.length,
    pass: fixtures.filter((fixture) => fixture.status === 'pass').length,
    deferred: fixtures.filter((fixture) => fixture.status === 'deferred').length,
    fail: fixtures.filter((fixture) => fixture.status === 'fail').length,
    fixtureDrift: fixtures.filter((fixture) => fixture.outcome === 'fixture_drift').length,
    caveatedPass: fixtures.filter((fixture) => fixture.outcome === 'caveated_pass').length,
  };
}

export function buildMatrixReport({ strict = false, generatedAt = new Date().toISOString(), fixtures = [] } = {}) {
  return {
    schemaVersion: '2.0',
    status: reportStatus(fixtures, strict),
    strict,
    generatedAt,
    summary: reportSummary(fixtures),
    fixtures,
  };
}

function cell(value) {
  if (value === null || value === undefined || value === '') return '-';
  return String(value).replaceAll('|', '\\|');
}

export function renderMatrixMarkdown(report) {
  const lines = [
    '# FixThis External Fixture Matrix Report',
    '',
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Schema: ${report.schemaVersion}`,
    '',
    '## Summary',
    '',
    '| Metric | Count |',
    '| --- | --- |',
    `| Total | ${cell(report.summary?.total ?? 0)} |`,
    `| Pass | ${cell(report.summary?.pass ?? 0)} |`,
    `| Deferred | ${cell(report.summary?.deferred ?? 0)} |`,
    `| Fail | ${cell(report.summary?.fail ?? 0)} |`,
    `| Fixture drift | ${cell(report.summary?.fixtureDrift ?? 0)} |`,
    `| Caveated pass | ${cell(report.summary?.caveatedPass ?? 0)} |`,
    '',
    '## Fixtures',
    '',
    '| Fixture | Status | Outcome | Runtime capability | Reason |',
    '| --- | --- | --- | --- | --- |',
  ];
  for (const fixture of report.fixtures || []) {
    lines.push(`| ${cell(fixture.fixtureId)} | ${cell(fixture.status)} | ${cell(fixture.outcome)} | ${cell(fixture.runtimeCapability)} | ${cell(fixture.reason)} |`);
  }
  lines.push('', '## Trust Findings', '', '| Fixture | Kind | Status | Message |', '| --- | --- | --- | --- |');
  for (const fixture of report.fixtures || []) {
    for (const finding of fixture.trustFindings || []) {
      lines.push(`| ${cell(fixture.fixtureId)} | ${cell(finding.kind)} | ${cell(finding.status)} | ${cell(finding.message)} |`);
    }
  }
  lines.push('', '## Commands', '', '| Fixture | Step | Status | Command | Duration |', '| --- | --- | --- | --- | --- |');
  for (const fixture of report.fixtures || []) {
    for (const command of fixture.commands || []) {
      lines.push(`| ${cell(fixture.fixtureId)} | ${cell(command.name)} | ${cell(command.status)} | \`${cell(command.command)}\` | ${cell(command.durationMs ?? 0)}ms |`);
    }
  }
  return `${lines.join('\n')}\n`;
}

export function writeMatrixReports(report, reportDir = defaultMatrixReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, 'report.json');
  const markdown = join(reportDir, 'report.md');
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderMatrixMarkdown(report));
  return { json, markdown };
}

function runCommand(command, cwd, envPatch = {}) {
  const started = Date.now();
  const result = spawnSync('bash', ['-lc', command], {
    cwd,
    encoding: 'utf8',
    env: { ...process.env, ...envPatch },
    stdio: 'pipe',
  });
  return {
    status: result.status === 0 ? 'pass' : 'fail',
    durationMs: Date.now() - started,
    stdout: result.stdout,
    stderr: result.stderr,
    exitCode: result.status ?? 1,
  };
}

function parseJsonObject(text) {
  try {
    const value = JSON.parse(text);
    return value && typeof value === 'object' ? value : null;
  } catch {
    return null;
  }
}

function hasOkCheck(report, name) {
  return Array.isArray(report?.checks) && report.checks.some((check) => check.name === name && check.status === 'ok');
}

export function normalizeFixtureCommandResult(entry, fixture, result) {
  if (entry.name !== 'doctor-json' || result.status !== 'fail') return result;
  const report = parseJsonObject(result.stdout);
  const reachesExpectedNextAction = report?.schemaVersion === '1.0' &&
    report?.readiness?.state === 'NEEDS_APP_LAUNCH' &&
    report?.nextAction === 'Open app' &&
    hasOkCheck(report, 'android_project_found') &&
    hasOkCheck(report, 'fixthis_project_metadata_found') &&
    hasOkCheck(report, 'adb_found') &&
    hasOkCheck(report, 'device_connected');
  if (!reachesExpectedNextAction) return result;
  return {
    ...result,
    status: 'pass',
    acceptedExitCode: result.exitCode,
    acceptedReadinessState: report.readiness.state,
    acceptedExpectedSetup: fixture.expectedSetup,
  };
}

export function prepareCliDistribution(root = repoRoot, envPatch = {}, runCommandFn = (command, cwd, patch) => runCommand(command, cwd, patch)) {
  const entry = { name: 'prepare-cli', command: defaultCliInstallTask };
  if (existsSync(cliExecutablePath(root))) {
    return { ...entry, status: 'pass', durationMs: 0, stdout: '', stderr: '', exitCode: 0, skipped: true };
  }
  return { ...entry, ...runCommandFn(defaultCliInstallTask, root, envPatch) };
}

function write(path, text) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text);
}

function settingsFor(fixture) {
  if (fixture.projectShape === 'multi-module') {
    return 'pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name = "FixThisMatrixMulti"\ninclude(":features:demo-app")\n';
  }
  const modulePath = fixture.appModule || ':app';
  const expectedModuleDir = modulePath.slice(1).replaceAll(':', '/');
  const projectDirMapping = fixture.moduleDir !== expectedModuleDir
    ? `project("${modulePath}").projectDir = file("${fixture.moduleDir}")\n`
    : '';
  return `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "FixThisMatrix"
include("${modulePath}")
${projectDirMapping}`;
}

function appBuildGradleFor(fixture) {
  const flavorBlock = fixture.projectShape === 'single-module-flavor'
    ? '    flavorDimensions += "env"\n    productFlavors { create("demo") { dimension = "env"; applicationIdSuffix = ".demo" } }\n'
    : '';
  return [
    'plugins {',
    '    id("com.android.application")',
    '    id("org.jetbrains.kotlin.android")',
    '    id("io.github.beyondwin.fixthis.compose")',
    '}',
    '',
    'android {',
    `    namespace = "${fixture.applicationId.replace(/\.demo$/, '')}"`,
    '    compileSdk = 34',
    `    defaultConfig { applicationId = "${fixture.applicationId.replace(/\.demo$/, '')}"; minSdk = 23; targetSdk = 34; versionCode = 1; versionName = "1.0" }`,
    flavorBlock.trimEnd(),
    '}',
    '',
    'dependencies {',
    '    implementation("androidx.activity:activity-compose:1.10.0")',
    '    implementation(platform("androidx.compose:compose-bom:2025.01.01"))',
    '    implementation("androidx.compose.material3:material3")',
    '}',
    '',
  ].filter(Boolean).join('\n');
}

export function generateFixtureProject(fixture, projectDir, root = repoRoot) {
  rmSync(projectDir, { recursive: true, force: true });
  mkdirSync(projectDir, { recursive: true });
  const wrapper = join(projectDir, 'gradlew');
  write(wrapper, `#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")" && pwd)"
exec ${shellQuote(join(root, 'gradlew'))} -p "$project_dir" "$@"
`);
  chmodSync(wrapper, 0o755);
  write(join(projectDir, 'settings.gradle.kts'), settingsFor(fixture));
  write(join(projectDir, 'build.gradle.kts'), 'plugins { id("com.android.application") version "8.7.3" apply false; id("org.jetbrains.kotlin.android") version "2.0.21" apply false; id("io.github.beyondwin.fixthis.compose") version "1.1.0" apply false }\n');
  const moduleDir = join(projectDir, fixture.moduleDir);
  write(join(moduleDir, 'build.gradle.kts'), appBuildGradleFor(fixture));
  write(join(moduleDir, 'src/main/AndroidManifest.xml'), `<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="${fixture.applicationId}" /></manifest>\n`);
  write(join(moduleDir, 'src/main/java/io/github/beyondwin/fixthis/matrix/MainActivity.kt'), 'package io.github.beyondwin.fixthis.matrix\n\nimport android.app.Activity\n\nclass MainActivity : Activity()\n');
  if (fixture.projectShape === 'preexisting-agent-config') {
    write(join(projectDir, '.claude/settings.json'), '{ "mcpServers": {} }\n');
    write(join(projectDir, '.cursor/mcp.json'), '{ "mcpServers": {} }\n');
  }
}

function cleanupFixture(projectDir) {
  rmSync(projectDir, { recursive: true, force: true });
}

function observationFromTrustExpectations(fixture) {
  const expectations = fixture.trustExpectations || [];
  if (!expectations.length) return null;
  const warnings = [...new Set(expectations.flatMap((expectation) => expectation.mustWarn || []))];
  const riskFlags = [...new Set(expectations.flatMap((expectation) => expectation.mustRisk || []))];
  return {
    targetReliability: {
      confidence: 'medium',
      warnings,
    },
    sourceCandidates: [{
      confidence: 'medium',
      riskFlags,
    }],
    exactOwnershipClaimed: false,
  };
}

export function runExternalMatrix({
  manifest = loadExternalMatrixManifest(),
  strict = false,
  workRoot = defaultMatrixWorkRoot,
  root = repoRoot,
  androidEnvironment = resolveAndroidEnvironment(),
  runCommandFn = (command, cwd, envPatch) => runCommand(command, cwd, envPatch),
  prepareCliDistributionFn = (activeRoot, envPatch, runner) => prepareCliDistribution(activeRoot, envPatch, runner),
  generateFixtureProjectFn = generateFixtureProject,
  cleanupFixtureFn = cleanupFixture,
  trustObservationFn = (fixture) => observationFromTrustExpectations(fixture),
} = {}) {
  const fixtures = [];
  let cliPreparation = null;
  if (androidEnvironment.ready) {
    cliPreparation = prepareCliDistributionFn(root, androidEnvironment.envPatch, runCommandFn);
    if (cliPreparation.status === 'fail') {
      return buildMatrixReport({
        strict,
        fixtures: manifest.fixtures.map((fixture) => {
          const reason = cliPreparation.stderr?.split('\n').find(Boolean) || cliPreparation.stdout?.split('\n').find(Boolean) || 'prepare-cli failed';
          const trustFindings = [];
          return {
            fixtureId: fixture.id,
            status: 'fail',
            outcome: outcomeForFixture({ status: 'fail', reason, trustFindings }),
            runtimeCapability: fixture.runtimeCapability,
            reason,
            trustFindings,
            commands: [
              cliPreparation,
              ...planFixtureCommands(fixture, join(workRoot, fixture.id), root).map((entry) => ({ ...entry, status: 'deferred', durationMs: 0 })),
            ],
          };
        }),
      });
    }
  }
  for (const fixture of manifest.fixtures) {
    const projectDir = join(workRoot, fixture.id);
    const environment = externalMatrixStatusForEnvironment({
      strict,
      androidReady: androidEnvironment.ready,
      reason: androidEnvironment.reason,
    });
    if (!androidEnvironment.ready) {
      const trustFindings = [];
      fixtures.push({
        fixtureId: fixture.id,
        status: environment.status,
        outcome: outcomeForFixture({ status: environment.status, reason: environment.reason, trustFindings }),
        runtimeCapability: fixture.runtimeCapability,
        reason: environment.reason,
        trustFindings,
        commands: planFixtureCommands(fixture, projectDir, root).map((entry) => ({ ...entry, status: 'deferred', durationMs: 0 })),
      });
      continue;
    }
    generateFixtureProjectFn(fixture, projectDir, root);
    const commands = cliPreparation ? [{ ...cliPreparation }] : [];
    let status = 'pass';
    let reason = null;
    for (const entry of planFixtureCommands(fixture, projectDir, root)) {
      const result = normalizeFixtureCommandResult(entry, fixture, runCommandFn(entry.command, projectDir, androidEnvironment.envPatch));
      commands.push({ ...entry, ...result });
      if (result.status === 'fail') {
        status = 'fail';
        reason = result.stderr?.split('\n').find(Boolean) || result.stdout?.split('\n').find(Boolean) || `${entry.name} failed`;
        break;
      }
    }
    if (status === 'pass') {
      for (const entry of planRuntimeTrustCommands(fixture)) {
        const result = runCommandFn(entry.command, root, androidEnvironment.envPatch);
        commands.push({ ...entry, ...result });
        if (result.status === 'fail') {
          status = 'fail';
          reason = result.stderr?.split('\n').find(Boolean) || result.stdout?.split('\n').find(Boolean) || `${entry.name} failed`;
          break;
        }
      }
    }
    const trustFindings = evaluateTrustExpectations(
      fixture,
      status === 'pass' ? trustObservationFn(fixture, { projectDir, commands, root, workRoot, androidEnvironment }) : null,
    );
    const failedTrustFinding = trustFindings.find((finding) => finding.status === 'fail');
    if (status === 'pass' && failedTrustFinding) {
      status = 'fail';
      reason = failedTrustFinding.message;
    }
    cleanupFixtureFn(projectDir);
    fixtures.push({
      fixtureId: fixture.id,
      status,
      outcome: outcomeForFixture({ status, trustFindings, reason }),
      runtimeCapability: fixture.runtimeCapability,
      reason,
      trustFindings,
      commands,
    });
  }
  return buildMatrixReport({ strict, fixtures });
}

function parseArgs(argv) {
  const args = { strict: false, dryRun: false };
  for (const arg of argv) {
    if (arg === '--strict') args.strict = true;
    else if (arg === '--dry-run') args.dryRun = true;
    else if (arg === '-h' || arg === '--help') {
      console.log('Usage: node scripts/external-fixture-matrix.mjs [--strict] [--dry-run]');
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const manifest = loadExternalMatrixManifest();
  const report = args.dryRun
    ? buildMatrixReport({
      strict: args.strict,
      fixtures: manifest.fixtures.map((fixture) => ({
        fixtureId: fixture.id,
        status: 'planned',
        outcome: 'planned',
        runtimeCapability: fixture.runtimeCapability,
        trustFindings: [],
        commands: [
          ...planFixtureCommands(fixture, join(defaultMatrixWorkRoot, fixture.id), repoRoot),
          ...planRuntimeTrustCommands(fixture),
        ].map((entry) => ({ ...entry, status: 'planned', durationMs: 0 })),
      })),
    })
    : runExternalMatrix({ manifest, strict: args.strict });
  const paths = writeMatrixReports(report);
  console.log(`External fixture matrix: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status === 'fail') process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main();
}
