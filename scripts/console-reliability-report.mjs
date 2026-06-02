import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..');
export const defaultConsoleReliabilityReportDir = join(repoRoot, 'build/reports/fixthis-console-reliability');

export function summarizeRequests(requests, { since = 0 } = {}) {
  const relevant = (requests || []).filter((entry) => Number(entry.time || 0) >= since);
  return {
    sessionPolls: relevant.filter((entry) =>
      entry.method === 'GET' && (entry.path === '/api/session' || entry.path === '/api/sessions')
    ).length,
    historyPolls: relevant.filter((entry) =>
      entry.method === 'GET' && entry.path === '/api/history'
    ).length,
    previewPolls: relevant.filter((entry) =>
      entry.method === 'GET' && entry.path === '/api/preview'
    ).length,
    eventConnections: relevant.filter((entry) =>
      entry.method === 'GET' && entry.path === '/api/events'
    ).length,
  };
}

function fallbackPollingFailures(observation) {
  const summary = observation.requestSummary || {};
  const failures = [];
  if (Number(summary.sessionPolls || 0) > 0) failures.push('fallback polling used /api/session while EventSource was healthy');
  if (Number(summary.historyPolls || 0) > 0) failures.push('fallback polling used /api/history while EventSource was healthy');
  if (Number(summary.previewPolls || 0) > 0) failures.push('fallback polling used /api/preview while EventSource was healthy');
  return failures;
}

function observationStatus(observation) {
  const fallbackReasons = observation.fallbackReasons || [];
  if (observation.eventSourceConnected && fallbackReasons.length === 0 && fallbackPollingFailures(observation).length > 0) return 'fail';
  return 'pass';
}

export function buildConsoleReliabilityReport({
  observations,
  generatedAt = new Date().toISOString(),
} = {}) {
  const normalized = (observations || []).map((observation) => ({
    ...observation,
    status: observationStatus(observation),
    failures: observation.eventSourceConnected && (observation.fallbackReasons || []).length === 0
      ? fallbackPollingFailures(observation)
      : [],
  }));
  const failures = normalized.flatMap((observation) =>
    (observation.failures || []).map((failure) => `${observation.name || 'observation'}: ${failure}`)
  );
  return {
    schemaVersion: '1.0',
    status: normalized.some((observation) => observation.status === 'fail') ? 'fail' : 'pass',
    generatedAt,
    failures,
    observations: normalized,
  };
}

function cell(value) {
  if (value === null || value === undefined || value === '') return '-';
  if (Array.isArray(value)) return value.length ? value.join(', ') : '-';
  return String(value).replaceAll('|', '\\|');
}

export function renderConsoleReliabilityMarkdown(report) {
  const lines = [
    '# FixThis Console Reliability Report',
    '',
    `- Status: ${report.status}`,
    `- Generated: ${report.generatedAt}`,
    '',
    '| Observation | Status | EventSource connected | Session polls | Preview polls | Event connections | Fallback reasons |',
    '| --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const observation of report.observations || []) {
    const summary = observation.requestSummary || {};
    lines.push([
      cell(observation.name),
      cell(observation.status),
      cell(observation.eventSourceConnected),
      cell(summary.sessionPolls || 0),
      cell(summary.previewPolls || 0),
      cell(summary.eventConnections || 0),
      cell(observation.fallbackReasons || []),
    ].join(' | ').replace(/^/, '| ').replace(/$/, ' |'));
  }
  return `${lines.join('\n')}\n`;
}

export function writeConsoleReliabilityReports(report, reportDir = defaultConsoleReliabilityReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, 'report.json');
  const markdown = join(reportDir, 'report.md');
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderConsoleReliabilityMarkdown(report));
  return { json, markdown };
}
