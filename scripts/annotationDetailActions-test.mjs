import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const detailSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/presentation/annotationDetailView.js'),
  'utf8',
);
const annotationsSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/annotations.js'),
  'utf8',
);

function functionBody(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const paramsEnd = source.indexOf(')', start);
  const bodyStart = source.indexOf('{', paramsEnd);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('pending annotation detail keeps destructive delete and removes redundant done', () => {
  const renderPendingDetail = functionBody(detailSource, 'function renderAnnotationDetail(item, index)');

  assert.match(renderPendingDetail, /data-delete-current>Delete annotation</);
  assert.doesNotMatch(renderPendingDetail, /data-back-annotations>Done</);
});

test('creating an annotation focuses its detail editor immediately', () => {
  const createAnnotation = functionBody(annotationsSource, 'function createAnnotationFromSelection(selection)');

  assert.match(createAnnotation, /setDraftFocusIndex\(nextWorkspace\.items\.length - 1\);/);
});

test('evidence details list shared-component call sites when present', () => {
  const evidenceDetailsHtml = functionBody(detailSource, 'function evidenceDetailsHtml(item)');
  assert.match(evidenceDetailsHtml, /callSites/);
});

test('source candidate call-site formatting tolerates absent callSites', () => {
  const helper = functionBody(detailSource, 'function sourceCandidateCallSites(candidate)');
  assert.match(helper, /candidate(\?\.|\.)callSites/);
  assert.match(helper, /\|\| \[\]/);
});

test('call-site formatter labels the most-likely entry', () => {
  const helper = functionBody(detailSource, 'function sourceCandidateCallSites(candidate)');
  assert.match(helper, /site\.mostLikely/);
  assert.match(helper, /most likely/);
});

test('returning from pending annotation detail refreshes session summary counts', () => {
  const renderPendingDetail = functionBody(detailSource, 'function renderAnnotationDetail(item, index)');
  const backAnnotationsStart = renderPendingDetail.indexOf("querySelectorAll('[data-back-annotations]')");
  assert.notEqual(backAnnotationsStart, -1, 'back annotations handler not found');
  const deleteStart = renderPendingDetail.indexOf("querySelector('[data-delete-current]')", backAnnotationsStart);
  assert.notEqual(deleteStart, -1, 'delete handler not found after back annotations handler');
  const backAnnotationsHandler = renderPendingDetail.slice(backAnnotationsStart, deleteStart);

  assert.match(
    backAnnotationsHandler,
    /renderCurrentSessionList\(\);/,
  );
});
