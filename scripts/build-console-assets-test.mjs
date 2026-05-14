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

const RAW_BUDGET = 170 * 1024;   // 174,080 bytes
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

test('app.js raw bytes are within the 170 KiB budget', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  const bytes = readFileSync(targetJs).byteLength;
  assert.ok(
    bytes <= RAW_BUDGET,
    `app.js is ${bytes} bytes, exceeds raw budget ${RAW_BUDGET} bytes (170 KiB)`,
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

test('app.js links its external source map via //# sourceMappingURL', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  const generated = readFileSync(targetJs, 'utf8');
  assert.ok(
    /\/\/# sourceMappingURL=app\.js\.map\s*$/.test(generated.trimEnd()),
    `app.js missing //# sourceMappingURL=app.js.map trailer (last 100 chars: ${generated.slice(-100)})`,
  );
});

test('--check returns 0 immediately after a reproducible build', () => {
  execFileSync('node', [script], {
    cwd: root,
    stdio: 'pipe',
    env: { ...process.env, FIXTHIS_BUNDLE_REPRODUCIBLE: '1' },
  });
  execFileSync('node', [script, '--check'], { cwd: root, stdio: 'pipe' });
});

test('topological order matches legacy hand-curated array', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  const generated = readFileSync(targetJs, 'utf8');
  // Use stable identifiers that survive minifyWhitespace (no //#region markers after minification)
  const stateIdx = generated.indexOf('DefaultLivePreviewIntervalMs');
  const annotationsIdx = generated.indexOf('isInteractionBlocked');
  const renderingIdx = generated.indexOf('renderOverlayBox');
  const mainIdx = generated.indexOf('FixThisConsoleDebug');
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
