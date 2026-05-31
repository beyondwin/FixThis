import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  buildConsoleReliabilityReport,
  renderConsoleReliabilityMarkdown,
  summarizeRequests,
  writeConsoleReliabilityReports,
} from './console-reliability-report.mjs';

test('summarizeRequests counts fallback poll endpoints after marker time', () => {
  const summary = summarizeRequests([
    { method: 'GET', path: '/api/session', time: 1 },
    { method: 'GET', path: '/api/sessions', time: 10 },
    { method: 'GET', path: '/api/preview', time: 11 },
    { method: 'GET', path: '/api/preview/preview-1/screenshot/full', time: 12 },
    { method: 'GET', path: '/api/events', time: 13 },
  ], { since: 5 });

  assert.equal(summary.sessionPolls, 1);
  assert.equal(summary.previewPolls, 1);
  assert.equal(summary.eventConnections, 1);
});

test('buildConsoleReliabilityReport fails healthy SSE polling regressions', () => {
  const report = buildConsoleReliabilityReport({
    observations: [{
      name: 'healthy-sse',
      eventSourceConnected: true,
      requestSummary: { sessionPolls: 1, previewPolls: 0, eventConnections: 1 },
      fallbackReasons: [],
    }],
  });

  assert.equal(report.status, 'fail');
  assert.equal(report.observations[0].status, 'fail');
});

test('buildConsoleReliabilityReport passes explicit fallback observations', () => {
  const report = buildConsoleReliabilityReport({
    observations: [{
      name: 'sse-disconnect',
      eventSourceConnected: false,
      requestSummary: { sessionPolls: 2, previewPolls: 1, eventConnections: 2 },
      fallbackReasons: ['eventsource-disconnected'],
    }],
  });

  assert.equal(report.status, 'pass');
  assert.equal(report.observations[0].status, 'pass');
});

test('markdown report includes request counts and fallback reasons', () => {
  const text = renderConsoleReliabilityMarkdown({
    schemaVersion: '1.0',
    status: 'pass',
    generatedAt: '2026-05-31T00:00:00.000Z',
    observations: [{
      name: 'healthy-sse',
      status: 'pass',
      eventSourceConnected: true,
      requestSummary: { sessionPolls: 0, previewPolls: 0, eventConnections: 1 },
      fallbackReasons: [],
    }],
  });

  assert.match(text, /# FixThis Console Reliability Report/);
  assert.match(text, /\| healthy-sse \| pass \| true \| 0 \| 0 \| 1 \| - \|/);
});

test('writeConsoleReliabilityReports writes JSON and Markdown', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-console-reliability-'));
  try {
    const paths = writeConsoleReliabilityReports({
      schemaVersion: '1.0',
      status: 'pass',
      generatedAt: '2026-05-31T00:00:00.000Z',
      observations: [],
    }, root);
    assert.match(readFileSync(paths.json, 'utf8'), /"status": "pass"/);
    assert.match(readFileSync(paths.markdown, 'utf8'), /FixThis Console Reliability Report/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
