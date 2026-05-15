import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const annotationsSource = [
  'fixthis-mcp/src/main/console/viewmodel/reliabilityPresentation.js',
  'fixthis-mcp/src/main/console/presentation/promptReadinessView.js',
].map(file => readFileSync(resolve(root, file), 'utf8')).join('\n');
const renderingSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/presentation/annotationDetailView.js'), 'utf8');
const stylesSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');

test('prompt readiness counts reliability warnings without blocking save', () => {
  assert.match(annotationsSource, /function reliabilityWarnings\(item\)/);
  assert.match(annotationsSource, /const warningCount = annotations\.reduce/);
  assert.match(annotationsSource, /state: missing > 0 \? 'blocked' : \(warningCount > 0 \? 'warn' : 'ready'\)/);
});

test('annotation rows render target reliability badges', () => {
  assert.match(renderingSource, /function reliabilityBadgeHtml\(item\)/);
  assert.match(renderingSource, /ann-row-reliability/);
  assert.match(renderingSource, /targetReliability/);
});

test('target reliability CSS is present', () => {
  assert.match(stylesSource, /\.ann-row-reliability/);
  assert.match(stylesSource, /\.ann-row-reliability\[data-confidence="low"\]/);
});
