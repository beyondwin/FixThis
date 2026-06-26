#!/usr/bin/env node
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { expandProfile, runPlan } from './evidence-runner.mjs';

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), '..');
export const defaultReleaseGateReportDir = join(repoRoot, 'build/reports/fixthis-release-gate');

export const releaseClaimDefinitions = Object.freeze([
  { id: 'release-reality', evidenceNames: ['Release reality'] },
  { id: 'release-source-drift', evidenceNames: ['Release drift strict'] },
  { id: 'external-agent-loop', evidenceNames: ['Agent loop smoke contracts', 'Connected Android proof'], requireAllEvidence: true },
  { id: 'external-first-handoff-recovery', evidenceNames: ['Agent loop smoke contracts', 'Connected Android proof'], requireAllEvidence: true },
  {
    id: 'first-handoff-autopilot',
    evidenceNames: ['First handoff autopilot CLI contract', 'Agent loop smoke contracts', 'Connected Android proof'],
    requireAllEvidence: true,
  },
  { id: 'external-fixture-matrix', evidenceNames: ['External fixture matrix contracts', 'Connected Android proof'], requireAllEvidence: true },
  { id: 'external-trust-matrix-v2', evidenceNames: ['External fixture matrix contracts', 'Connected Android proof'], requireAllEvidence: true },
  { id: 'handoff-correctness-v2', evidenceNames: ['Handoff evaluation'] },
  {
    id: 'android-agent-evidence-umbrella',
    evidenceNames: ['Handoff evaluation', 'Runtime evidence attachment', 'Plugin contract'],
    requireAllEvidence: true,
  },
  { id: 'connected-android-proof', evidenceNames: ['Connected Android proof'] },
  { id: 'runtime-source-trust', evidenceNames: ['Runtime trust boundary observations', 'Connected Android proof'], requireAllEvidence: true },
  { id: 'console-sse-reliability', evidenceNames: ['Console browser reliability'] },
  { id: 'adb-discovery', evidenceNames: ['ADB discovery tests'] },
  { id: 'source-evidence-depth', evidenceNames: ['Compose core source trust', 'Runtime trust boundary observations'] },
  { id: 'interop-boundary-context', evidenceNames: ['Interop boundary contracts'] },
  { id: 'sse-fallback-reliability', evidenceNames: ['Console reliability contracts', 'Console browser reliability'] },
  { id: 'required-check-readiness', evidenceNames: ['Release readiness'] },
]);

export function releaseGateSteps() {
  return expandProfile('gate').map((step) => ({ ...step }));
}

function resolveReportPath(reportPath, root = repoRoot) {
  if (!reportPath) return null;
  return resolve(root, reportPath);
}

export function childFailureReason(step) {
  const failure = step?.childFailures?.[0];
  if (!failure) return null;
  const detail = failure.nextAction || failure.reason || null;
  return [failure.failureCode, detail].filter(Boolean).join(': ') || null;
}

export function readConnectedAndroidProofDetails(step, { root = repoRoot } = {}) {
  if (step.name !== 'Connected Android proof') return null;
  const detailReportPath = step.reportPath || 'build/reports/fixthis-android-proof/report.json';
  const absolutePath = resolveReportPath(detailReportPath, root);
  if (!absolutePath || !existsSync(absolutePath)) return null;
  try {
    const report = JSON.parse(readFileSync(absolutePath, 'utf8'));
    const childFailures = Array.isArray(report.failures) ? report.failures : [];
    return {
      detailReportPath,
      childFailures,
    };
  } catch (_err) {
    return null;
  }
}

export function normalizeEvidenceStep(step, options = {}) {
  const rawStatus = step.status;
  const status = rawStatus === 'passed' || rawStatus === 'pass'
    ? 'pass'
    : rawStatus === 'deferred'
      ? 'deferred'
      : 'fail';
  const normalized = {
    name: step.name,
    command: step.command,
    status,
    durationMs: step.durationMs ?? 0,
    reason: step.reason || step.stderr?.split('\n').find(Boolean) || null,
    reportPath: step.reportPath || null,
  };
  const details = readConnectedAndroidProofDetails(normalized, options);
  if (!details) return normalized;
  const enriched = {
    ...normalized,
    detailReportPath: details.detailReportPath,
    childFailures: details.childFailures,
  };
  return {
    ...enriched,
    reason: childFailureReason(enriched) || enriched.reason,
  };
}

function gateStatus(steps, strict) {
  if (steps.some((step) => step.status === 'fail')) return 'fail';
  if (strict && steps.some((step) => step.status === 'deferred')) return 'fail';
  if (steps.some((step) => step.status === 'deferred')) return 'pass_with_deferred';
  return 'pass';
}

function statusRank(status) {
  if (status === 'fail') return 3;
  if (status === 'deferred') return 2;
  if (status === 'pass') return 1;
  return 0;
}

function evidenceReason(step) {
  return childFailureReason(step) || step.reason || null;
}

export function buildReleaseClaims(steps) {
  const byName = new Map((steps || []).map((step) => [step.name, step]));
  return releaseClaimDefinitions.map((definition) => {
    const missingEvidenceNames = definition.evidenceNames.filter((name) => !byName.has(name));
    const evidence = definition.evidenceNames
      .map((name) => byName.get(name))
      .filter(Boolean);
    const status = missingEvidenceNames.length > 0 && (definition.requireAllEvidence || evidence.length === 0)
      ? 'fail'
      : evidence.map((step) => step.status).sort((a, b) => statusRank(b) - statusRank(a))[0];
    const reason = missingEvidenceNames.length > 0 && (definition.requireAllEvidence || evidence.length === 0)
      ? evidence.length === 0
        ? 'missing evidence command'
        : `missing evidence command${missingEvidenceNames.length === 1 ? `: ${missingEvidenceNames[0]}` : `s: ${missingEvidenceNames.join(', ')}`}`
      : evidence.map(evidenceReason).find(Boolean) || null;
    return {
      id: definition.id,
      status,
      evidence: evidence.map((step) => step.name),
      ...(reason ? { reason } : {}),
    };
  });
}

export function buildReleaseGateReport({
  strict = false,
  steps,
  generatedAt = new Date().toISOString(),
  normalizeOptions = {},
} = {}) {
  const normalizedSteps = (steps || []).map((step) => normalizeEvidenceStep(step, normalizeOptions));
  return {
    schemaVersion: '1.0',
    status: gateStatus(normalizedSteps, strict),
    strict,
    generatedAt,
    claims: buildReleaseClaims(normalizedSteps),
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
  ];
  if ((report.claims || []).length > 0) {
    lines.push('## Release Claims', '');
    lines.push('| Claim | Status | Evidence | Reason |');
    lines.push('| --- | --- | --- | --- |');
    for (const claim of report.claims || []) {
      lines.push(`| ${cell(claim.id)} | ${cell(claim.status)} | ${cell((claim.evidence || []).join(', '))} | ${cell(claim.reason)} |`);
    }
    lines.push('');
  }
  lines.push('## Evidence Steps', '');
  lines.push('| Step | Status | Command | Duration | Reason |');
  lines.push('| --- | --- | --- | --- | --- |');
  for (const step of report.steps || []) {
    lines.push(`| ${cell(step.name)} | ${cell(step.status)} | \`${cell(step.command)}\` | ${cell(step.durationMs ?? 0)}ms | ${cell(step.reason)} |`);
  }
  const proofDetails = (report.steps || []).filter((step) => (step.childFailures || []).length > 0);
  if (proofDetails.length > 0) {
    lines.push('', '## Connected Android Proof Details', '');
    lines.push('| Evidence | Child step | Failure | Next action |');
    lines.push('| --- | --- | --- | --- |');
    for (const step of proofDetails) {
      for (const failure of step.childFailures || []) {
        lines.push(`| ${cell(step.name)} | ${cell(failure.step || failure.scope)} | ${cell(failure.failureCode)} | ${cell(failure.nextAction || failure.reason)} |`);
      }
    }
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
