import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (file) => readFileSync(resolve(root, 'fixthis-mcp/src/main/console', file), 'utf8');
const preview = read('preview.js');
const history = read('history.js');
const annotations = read('annotations.js');
const events = read('events.js');

function functionBody(source, name) {
  const start = source.indexOf(`function ${name}(`);
  assert.notEqual(start, -1, `${name} not found`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${name} body did not close`);
}

test('preview module exposes reusable session preview context helpers', () => {
  assert.match(preview, /function capturePreviewContext\(\)/);
  assert.match(preview, /function previewContextStillCurrent\(context\)/);
  assert.match(preview, /sessionId: state\.session\?\.sessionId \|\| null/);
  assert.match(preview, /context\.sessionId === \(state\.session\?\.sessionId \|\| null\)/);
});

test('refreshPreview and startDraftAnnotationFlow both use the shared context helper', () => {
  const refreshPreview = functionBody(preview, 'refreshPreview');
  const startDraftAnnotationFlow = functionBody(annotations, 'startDraftAnnotationFlow');
  assert.match(refreshPreview, /const previewContext = capturePreviewContext\(\);/);
  assert.match(refreshPreview, /previewContextStillCurrent\(previewContext\)/);
  assert.match(startDraftAnnotationFlow, /const previewContext = capturePreviewContext\(\);/);
  assert.match(startDraftAnnotationFlow, /previewContextStillCurrent\(previewContext\)/);
});

test('session refresh clears preview when the server current session changes', () => {
  const refreshSessions = functionBody(history, 'refreshSessions');
  assert.match(refreshSessions, /const previousSessionId = state\.session\?\.sessionId \|\| null;/);
  assert.match(refreshSessions, /const nextSessionId = currentSession\?\.sessionId \|\| null;/);
  assert.match(refreshSessions, /if \(previousSessionId !== nextSessionId\) clearPreview\(\);/);
});

test('SSE snapshot and session updates clear preview when active session changes', () => {
  const startConsoleEvents = functionBody(events, 'startConsoleEvents');
  assert.match(startConsoleEvents, /applySessionFromServer\(data\.session \|\| null\)/);
  assert.match(startConsoleEvents, /applySessionFromServer\(session\)/);
  assert.match(startConsoleEvents, /function applySessionFromServer\(session\)/);
  assert.match(startConsoleEvents, /if \(previousSessionId !== nextSessionId\) clearPreview\(\);/);
});
