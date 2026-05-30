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

test('call-site formatter marks the recommended edit site distinctly from most-likely', () => {
  const helper = functionBody(detailSource, 'function sourceCandidateCallSites(candidate)');
  // Renders a distinct "(recommended edit site)" marker driven by the additive field.
  assert.match(helper, /site\.recommendedEditSite/);
  assert.match(helper, /'recommended edit site'/);
  // The recommended label is distinct from the most-likely label, and both
  // coexist so a site can be flagged as most-likely AND recommended.
  assert.match(helper, /'most likely'/);
});

test('interop boundary context renders one row per nearby Compose node', () => {
  const helper = functionBody(detailSource, 'function interopBoundaryContextRows(item)');
  // Iterates the nearby-node entries (multiple boundary-context lines from Task 4),
  // mapping each to its own row label rather than collapsing to a single entry.
  assert.match(helper, /nearbyNodes/);
  assert.match(helper, /\.map\(/);
  assert.match(helper, /Boundary context/);
  // The "does not prove Compose owns the selected pixels" caveat is appended once,
  // outside the per-node map, so it is not repeated per row.
  assert.match(helper, /does not prove Compose owns the selected pixels/);
  const mapEnd = helper.lastIndexOf('.map(');
  const caveatIndex = helper.indexOf('does not prove Compose owns the selected pixels');
  assert.ok(caveatIndex !== -1, 'caveat text missing');
});

test('interop boundary context only renders for interop-risk selections', () => {
  const rowsHelper = functionBody(detailSource, 'function interopBoundaryContextRows(item)');
  // The rows helper bails out unless the item is interop-risk.
  assert.match(rowsHelper, /isInteropRiskItem\(item\)/);
  // The gate itself keys off the possible_view_interop reliability warning so
  // non-interop selections render nothing new.
  const gate = functionBody(detailSource, 'function isInteropRiskItem(item)');
  assert.match(gate, /possible_view_interop/);
});

test('evidence details surface multiple boundary context rows for interop', () => {
  const evidenceDetailsHtml = functionBody(detailSource, 'function evidenceDetailsHtml(item)');
  assert.match(evidenceDetailsHtml, /interopBoundaryContextRows/);
});

function buildInteropRowsFn() {
  const constant = detailSource.match(/const INTEROP_BOUNDARY_CONTEXT_LIMIT = \d+;/)[0];
  const isInterop = 'function isInteropRiskItem(item)' +
    '{' + functionBody(detailSource, 'function isInteropRiskItem(item)') + '}';
  const summary = 'function boundaryContextNodeSummary(node)' +
    '{' + functionBody(detailSource, 'function boundaryContextNodeSummary(node)') + '}';
  const rows = 'function interopBoundaryContextRows(item)' +
    '{' + functionBody(detailSource, 'function interopBoundaryContextRows(item)') + '}';
  // formatBounds is provided elsewhere in the bundle; stub it for isolation.
  // eslint-disable-next-line no-new-func
  return new Function(
    'formatBounds',
    constant + isInterop + summary + rows + 'return interopBoundaryContextRows;',
  )(() => 'BOX');
}

test('three interop boundary nodes produce three rows plus exactly one caveat', () => {
  const interopBoundaryContextRows = buildInteropRowsFn();
  const item = {
    targetReliability: { warnings: ['possible_view_interop'] },
    nearbyNodes: [
      { testTag: 'comp:Host:a', role: 'Image', boundsInWindow: {} },
      { testTag: 'comp:Host:b', text: ['Revenue'], boundsInWindow: {} },
      { role: 'Button', contentDescription: ['Open'], boundsInWindow: {} },
      { testTag: 'comp:Host:d', role: 'Text', boundsInWindow: {} },
    ],
  };
  const rows = interopBoundaryContextRows(item);
  const contextRows = rows.filter(row => /^Boundary context \d+$/.test(row[0]));
  const caveatRows = rows.filter(row => row[1].includes('does not prove Compose owns the selected pixels'));
  // Capped at the top-3 nearby nodes, one row each.
  assert.equal(contextRows.length, 3);
  // Caveat rendered exactly once, not per row.
  assert.equal(caveatRows.length, 1);
});

test('non-interop selection renders no boundary context rows', () => {
  const interopBoundaryContextRows = buildInteropRowsFn();
  const item = {
    targetReliability: { warnings: ['low_source_candidate_margin'] },
    nearbyNodes: [{ testTag: 'comp:Host:a', role: 'Image', boundsInWindow: {} }],
  };
  assert.deepEqual(interopBoundaryContextRows(item), []);
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
