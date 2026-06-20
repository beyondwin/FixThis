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

export function externalMatrixStatusForEnvironment({ strict = false, androidReady = false, reason = 'Android SDK or ready emulator is unavailable.' } = {}) {
  if (androidReady) return { status: 'pass', exitCode: 0, reason: null };
  return strict
    ? { status: 'fail', exitCode: 1, reason }
    : { status: 'deferred', exitCode: 0, reason };
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
  return 'pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name = "FixThisMatrix"\ninclude(":app")\n';
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
} = {}) {
  const fixtures = [];
  let cliPreparation = null;
  if (androidEnvironment.ready) {
    cliPreparation = prepareCliDistributionFn(root, androidEnvironment.envPatch, runCommandFn);
    if (cliPreparation.status === 'fail') {
      return buildMatrixReport({
        strict,
        fixtures: manifest.fixtures.map((fixture) => ({
          fixtureId: fixture.id,
          status: 'fail',
          reason: cliPreparation.stderr?.split('\n').find(Boolean) || cliPreparation.stdout?.split('\n').find(Boolean) || 'prepare-cli failed',
          commands: [
            cliPreparation,
            ...planFixtureCommands(fixture, join(workRoot, fixture.id), root).map((entry) => ({ ...entry, status: 'deferred', durationMs: 0 })),
          ],
        })),
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
      fixtures.push({
        fixtureId: fixture.id,
        status: environment.status,
        reason: environment.reason,
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
    cleanupFixtureFn(projectDir);
    fixtures.push({ fixtureId: fixture.id, status, reason, commands });
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
        commands: planFixtureCommands(fixture, join(defaultMatrixWorkRoot, fixture.id), repoRoot).map((entry) => ({ ...entry, status: 'planned', durationMs: 0 })),
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
