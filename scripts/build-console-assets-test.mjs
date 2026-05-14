import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const script = resolve(root, 'scripts/build-console-assets.mjs');
const targetJs = resolve(root, 'fixthis-mcp/src/main/resources/console/app.js');
const targetMeta = resolve(root, 'fixthis-mcp/src/main/resources/console/console-build-meta.json');

const RAW_BUDGET = 110 * 1024;   // 112,640 bytes
const GZIP_BUDGET = 40 * 1024;   // 40,960 bytes

test('build script runs without arguments and produces app.js', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  assert.ok(existsSync(targetJs), 'app.js was not produced');
});

test('build script produces console-build-meta.json sidecar', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  assert.ok(existsSync(targetMeta), 'console-build-meta.json was not produced');
  const meta = JSON.parse(readFileSync(targetMeta, 'utf8'));
  assert.equal(typeof meta.buildEpochMs, 'number');
  assert.equal(typeof meta.gitSha, 'string');
});

test('app.js raw bytes are within the 110 KiB budget', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  const bytes = readFileSync(targetJs).byteLength;
  assert.ok(
    bytes <= RAW_BUDGET,
    `app.js is ${bytes} bytes, exceeds raw budget ${RAW_BUDGET} bytes (110 KiB)`,
  );
});

test('app.js gzipped bytes are within the 40 KiB budget', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  const raw = readFileSync(targetJs);
  const gz = gzipSync(raw, { level: 9 }).byteLength;
  assert.ok(
    gz <= GZIP_BUDGET,
    `app.js gzipped is ${gz} bytes, exceeds gzip budget ${GZIP_BUDGET} bytes (40 KiB)`,
  );
});

test('--check returns 0 immediately after a fresh build', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  execFileSync('node', [script, '--check'], { cwd: root, stdio: 'pipe' });
});

test('topological order matches legacy hand-curated array', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  const generated = readFileSync(targetJs, 'utf8');
  const stateIdx = generated.indexOf('//#region state.js');
  const annotationsIdx = generated.indexOf('//#region annotations.js');
  const renderingIdx = generated.indexOf('//#region rendering.js');
  const mainIdx = generated.indexOf('//#region main.js');
  assert.ok(stateIdx >= 0 && stateIdx < annotationsIdx, 'state.js must precede annotations.js');
  assert.ok(annotationsIdx < renderingIdx, 'annotations.js must precede rendering.js');
  assert.ok(renderingIdx < mainIdx, 'main.js must be last');
});

test('cycle in @requires aborts the build', async () => {
  const { topoSort } = await import('../scripts/build-console-assets.mjs');
  const cyclic = new Map([
    ['a.js', { content: '', deps: ['b.js'] }],
    ['b.js', { content: '', deps: ['a.js'] }],
  ]);
  assert.throws(() => topoSort(cyclic), /Cycle in .* @requires graph/);
});

test('every console module (except entry point) carries a // @requires header', () => {
  const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');
  const ENTRY_POINT = 'main.js';
  const files = readdirSync(sourceDir).filter((f) => f.endsWith('.js') && f !== ENTRY_POINT);
  assert.ok(files.length >= 26, `expected >=26 non-entry modules, found ${files.length}`);
  const missing = [];
  for (const name of files) {
    const text = readFileSync(resolve(sourceDir, name), 'utf8');
    if (!/^\s*\/\/\s*@requires\s+/m.test(text)) missing.push(name);
  }
  assert.deepEqual(missing, [], `Missing // @requires header in: ${missing.join(', ')}`);
});
