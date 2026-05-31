#!/usr/bin/env node
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { expandProfile, runPlan } from './evidence-runner.mjs';

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), '..');
export const defaultReleaseGateReportDir = join(repoRoot, 'build/reports/fixthis-release-gate');

export function releaseGateSteps() {
  return expandProfile('gate').map((step) => ({ ...step }));
}

export function normalizeEvidenceStep(step) {
  const rawStatus = step.status;
  const status = rawStatus === 'passed' || rawStatus === 'pass'
    ? 'pass'
    : rawStatus === 'deferred'
      ? 'deferred'
      : 'fail';
  return {
    name: step.name,
    command: step.command,
    status,
    durationMs: step.durationMs ?? 0,
    reason: step.reason || step.stderr?.split('\n').find(Boolean) || null,
    reportPath: step.reportPath || null,
  };
}

function gateStatus(steps, strict) {
  if (steps.some((step) => step.status === 'fail')) return 'fail';
  if (strict && steps.some((step) => step.status === 'deferred')) return 'fail';
  if (steps.some((step) => step.status === 'deferred')) return 'pass_with_deferred';
  return 'pass';
}

export function buildReleaseGateReport({
  strict = false,
  steps,
  generatedAt = new Date().toISOString(),
} = {}) {
  const normalizedSteps = (steps || []).map(normalizeEvidenceStep);
  return {
    schemaVersion: '1.0',
    status: gateStatus(normalizedSteps, strict),
    strict,
    generatedAt,
    steps: normalizedSteps,
  };
}

function cell(value) {
  if (value === null || value === undefined || value === '') return '-';
  return String(value).replaceAll('|', '\\|');
}

export function renderReleaseGateMarkdown(report) {
  const lines = [
    '# FixThis Release Gate Report',
    '',
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Generated: ${report.generatedAt}`,
    '',
    '| Step | Status | Command | Duration | Reason |',
    '| --- | --- | --- | --- | --- |',
  ];
  for (const step of report.steps || []) {
    lines.push(`| ${cell(step.name)} | ${cell(step.status)} | \`${cell(step.command)}\` | ${cell(step.durationMs ?? 0)}ms | ${cell(step.reason)} |`);
  }
  return `${lines.join('\n')}\n`;
}

export function writeReleaseGateReports(report, reportDir = defaultReleaseGateReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, 'report.json');
  const markdown = join(reportDir, 'report.md');
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderReleaseGateMarkdown(report));
  return { json, markdown };
}

export function runReleaseGate({
  strict = false,
  continueOnFailure = true,
  runEvidenceProfile = (options) => runPlan({
    schemaVersion: '1.0',
    profile: 'gate',
    strictRuntime: strict,
    steps: releaseGateSteps(),
  }, options),
} = {}) {
  const evidenceReport = runEvidenceProfile({ continueOnFailure });
  return buildReleaseGateReport({ strict, steps: evidenceReport.steps || [] });
}

function parseArgs(argv) {
  const args = { strict: false };
  for (const arg of argv) {
    if (arg === '--strict') args.strict = true;
    else if (arg === '-h' || arg === '--help') {
      console.log('Usage: node scripts/release-gate.mjs [--strict]');
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const report = runReleaseGate(args);
  const paths = writeReleaseGateReports(report);
  console.log(`Release gate: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status === 'fail') process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main();
}
