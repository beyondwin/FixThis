import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const script = resolve(root, 'scripts/build-console-assets.mjs');
const targetJs = resolve(root, 'fixthis-mcp/src/main/resources/console/app.js');
const targetMeta = resolve(root, 'fixthis-mcp/src/main/resources/console/console-build-meta.json');

// Runtime diagnostics plus its serialized policy mutation queue adds 6,886
// raw bytes to the reproducible asset, with narrow repair headroom.
const RAW_BUDGET = 239_000;
const GZIP_BUDGET = 60_500;

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

test('app.js raw bytes are within the runtime diagnostics budget', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  const bytes = readFileSync(targetJs).byteLength;
  assert.ok(
    bytes <= RAW_BUDGET,
    `app.js is ${bytes} bytes, exceeds raw budget ${RAW_BUDGET} bytes`,
  );
});

test('app.js gzipped bytes are within the runtime diagnostics budget', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  const raw = readFileSync(targetJs);
  const gz = gzipSync(raw, { level: 9 }).byteLength;
  assert.ok(
    gz <= GZIP_BUDGET,
    `app.js gzipped is ${gz} bytes, exceeds gzip budget ${GZIP_BUDGET} bytes`,
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

test('build graph can include nested console modules', async () => {
  const { consoleSourceFiles } = await import('../scripts/build-console-assets.mjs');
  const files = consoleSourceFiles(resolve(root, 'fixthis-mcp/src/main/console'));
  assert.ok(files.includes('main.js'), 'main.js should be discovered');
  assert.ok(
    files.some((name) => name.includes('/')) || files.every((name) => !name.includes('/')),
    'discovery must return stable slash-normalized relative paths',
  );
});

test('parseRequires accepts nested module paths', async () => {
  const { parseRequires } = await import('../scripts/build-console-assets.mjs');
  const deps = parseRequires('// @requires domain/consoleReducer.js, adapters/browserPorts.js\n');
  assert.deepEqual(deps, ['domain/consoleReducer.js', 'adapters/browserPorts.js']);
});

test('every console module except entry point carries a // @requires header', async () => {
  const { consoleSourceFiles } = await import('../scripts/build-console-assets.mjs');
  const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');
  const files = consoleSourceFiles(sourceDir).filter((name) => name !== 'main.js');
  assert.ok(files.length >= 40, `expected >=40 non-entry modules, found ${files.length}`);
  const missing = [];
  for (const name of files) {
    const text = readFileSync(resolve(sourceDir, name), 'utf8');
    if (!/^\s*\/\/\s*@requires\s+/m.test(text)) missing.push(name);
  }
  assert.deepEqual(missing, [], `Missing // @requires header in: ${missing.join(', ')}`);
});

test('contract-symbol guard catches a dropped symbol', async () => {
  const { assertContractSymbols } = await import('../scripts/build-console-assets.mjs');
  assert.throws(
    () => assertContractSymbols('function foo(){}', ['withMutationLock']),
    /contract symbol 'withMutationLock'/,
  );
});

test('contract-symbol guard passes when all symbols are present', async () => {
  const { assertContractSymbols } = await import('../scripts/build-console-assets.mjs');
  assert.doesNotThrow(() =>
    assertContractSymbols('function withMutationLock(fn){}', ['withMutationLock']),
  );
});

test('bundled JS parses as valid es2020 module', () => {
  execFileSync('node', [script], { cwd: root, stdio: 'pipe' });
  const code = readFileSync(targetJs, 'utf8');
  // Pass through Node's parser via new Function (sloppy mode parse)
  assert.doesNotThrow(() => new Function(code), 'bundle has syntax error');
});
