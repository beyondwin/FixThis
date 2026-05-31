#!/usr/bin/env node
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, normalize, resolve, sep } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { resolveAndroidEnvironment } from './evidence-runner.mjs';

const scriptPath = fileURLToPath(import.meta.url);
export const repoRoot = resolve(dirname(scriptPath), '..');
export const defaultManifestPath = join(repoRoot, 'fixtures/external-project-matrix/manifest.json');
export const defaultMatrixReportDir = join(repoRoot, 'build/reports/fixthis-external-fixture-matrix');
export const defaultMatrixWorkRoot = join(repoRoot, '.fixthis/external-fixture-matrix');

const allowedShapes = new Set([
  'single-module',
  'multi-module',
  'single-module-flavor',
  'version-catalog',
  'preexisting-agent-config',
  'missing-generated-metadata',
]);
const allowedExpectedSetup = new Set(['ready', 'needs-fixthis-setup']);

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

export function validateExternalMatrixManifest(manifest) {
  const errors = [];
  if (!manifest || typeof manifest !== 'object') errors.push('manifest must be an object');
  if (manifest?.schemaVersion !== 1) errors.push('schemaVersion must be 1');
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
  }
  if (errors.length) throw new Error(errors.join('; '));
  return manifest;
}

export function loadExternalMatrixManifest(path = defaultManifestPath) {
  return validateExternalMatrixManifest(JSON.parse(readFileSync(path, 'utf8')));
}

export function planFixtureCommands(fixture, projectDir, root = '/repo') {
  const cli = `${root}/fixthis-cli/build/install/fixthis/bin/fixthis`;
  return [
    {
      name: 'install-agent',
      command: `node ${cli} install-agent --project-dir ${projectDir} --target all --package ${fixture.applicationId}`,
    },
    {
      name: 'doctor-json',
      command: `node ${cli} doctor --project-dir ${projectDir} --package ${fixture.applicationId} --json`,
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

export function buildMatrixReport({ strict = false, generatedAt = new Date().toISOString(), fixtures = [] } = {}) {
  return {
    schemaVersion: '1.0',
    status: reportStatus(fixtures, strict),
    strict,
    generatedAt,
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
    '',
    '| Fixture | Status | Reason |',
    '| --- | --- | --- |',
  ];
  for (const fixture of report.fixtures || []) {
    lines.push(`| ${cell(fixture.fixtureId)} | ${cell(fixture.status)} | ${cell(fixture.reason)} |`);
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
  const android = resolveAndroidEnvironment();
  const fixtures = manifest.fixtures.map((fixture) => {
    const projectDir = join(defaultMatrixWorkRoot, fixture.id);
    const commands = planFixtureCommands(fixture, projectDir, repoRoot).map((entry) => ({
      ...entry,
      status: args.dryRun ? 'planned' : 'deferred',
      durationMs: 0,
    }));
    const environment = externalMatrixStatusForEnvironment({ strict: args.strict, androidReady: android.ready, reason: android.reason });
    return {
      fixtureId: fixture.id,
      status: args.dryRun ? 'planned' : environment.status,
      reason: args.dryRun ? null : environment.reason,
      commands,
    };
  });
  const report = buildMatrixReport({ strict: args.strict, fixtures });
  const paths = writeMatrixReports(report);
  console.log(`External fixture matrix: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status === 'fail') process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main();
}
