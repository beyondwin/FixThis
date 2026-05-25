import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));

function loadChip({ initialBuildHash }) {
  const src = readFileSync(resolve(repoRoot, 'fixthis-mcp/src/main/console/serverBuildChip.js'), 'utf8');
  const windowStub = { FixThisConsoleConfig: { buildHash: initialBuildHash } };
  const documentStub = { createElement: () => mkNode(), getElementById: () => null };
  const body = src.replace(/^\s*\/\/\s*@requires[^\n]*\n/, '');
  const fn = new Function(
    'window',
    'document',
    `${body}; return { renderServerBuildChip, updateServerBuildChipState };`,
  );
  const node = mkNode();
  return { api: fn(windowStub, documentStub), node };
}

function mkNode() {
  return { textContent: '', dataset: {} };
}

test('renders initial connected state with build sha suffix', () => {
  const { api, node } = loadChip({ initialBuildHash: 'abc1234' });
  api.renderServerBuildChip(node);
  assert.match(node.textContent, /connected.*abc1234/);
  assert.equal(node.dataset.state, 'connected');
});

test('renders bare connected state when no buildHash', () => {
  const { api, node } = loadChip({ initialBuildHash: undefined });
  api.renderServerBuildChip(node);
  assert.equal(node.textContent, 'connected');
  assert.equal(node.dataset.state, 'connected');
});

test('updates to reconnecting state', () => {
  const { api, node } = loadChip({ initialBuildHash: 'abc1234' });
  api.renderServerBuildChip(node);
  api.updateServerBuildChipState(node, { state: 'reconnecting' });
  assert.match(node.textContent, /reconnecting/i);
  assert.equal(node.dataset.state, 'reconnecting');
});

test('updates build sha after reconnect to new server', () => {
  const { api, node } = loadChip({ initialBuildHash: 'abc1234' });
  api.renderServerBuildChip(node);
  api.updateServerBuildChipState(node, { state: 'connected', buildHash: 'def5678' });
  assert.match(node.textContent, /def5678/);
  assert.equal(node.dataset.state, 'connected');
});

test('transitions reconnecting -> connected with new sha', () => {
  const { api, node } = loadChip({ initialBuildHash: 'abc1234' });
  api.renderServerBuildChip(node);
  api.updateServerBuildChipState(node, { state: 'reconnecting' });
  assert.equal(node.dataset.state, 'reconnecting');
  api.updateServerBuildChipState(node, { state: 'connected', buildHash: 'newsha' });
  assert.equal(node.dataset.state, 'connected');
  assert.match(node.textContent, /newsha/);
});
