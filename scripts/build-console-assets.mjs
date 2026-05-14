#!/usr/bin/env node
import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { build as esbuild } from 'esbuild';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');

export function parseRequires(content) {
  const headerMatch = content.match(/^\s*\/\/\s*@requires\s+(.+)$/m);
  if (!headerMatch) return [];
  const value = headerMatch[1].trim();
  if (value === '(none)' || value === '') return [];
  return value.split(',').map((s) => s.trim()).filter(Boolean);
}

export function topoSort(g) {
  const visited = new Set();
  const visiting = new Set();
  const order = [];
  function visit(name, stack = []) {
    if (visited.has(name)) return;
    if (visiting.has(name)) {
      throw new Error(
        `Cycle in // @requires graph: ${[...stack, name].join(' -> ')}`,
      );
    }
    visiting.add(name);
    const node = g.get(name);
    if (!node) throw new Error(`Unknown module referenced: ${name}`);
    for (const dep of node.deps) visit(dep, [...stack, name]);
    visiting.delete(name);
    visited.add(name);
    order.push(name);
  }
  for (const name of [...g.keys()].sort()) visit(name);
  return order;
}

const isMainModule = import.meta.url === `file://${process.argv[1]}`;
if (isMainModule) {
  main().catch((e) => { console.error(e); process.exit(1); });
}

async function main() {
  const onDisk = readdirSync(sourceDir).filter((name) => name.endsWith('.js'));

  // Enforce that every non-entry-point module has a // @requires header.
  for (const name of onDisk) {
    if (name === 'main.js') continue;
    const content = readFileSync(resolve(sourceDir, name), 'utf8');
    if (!/^\s*\/\/\s*@requires\s+/m.test(content)) {
      console.error(`ERROR: fixthis-mcp/src/main/console/${name} has no // @requires header.`);
      process.exit(1);
    }
  }

  const graph = new Map();
  for (const name of onDisk) {
    const content = readFileSync(resolve(sourceDir, name), 'utf8');
    graph.set(name, { content, deps: parseRequires(content) });
  }

  const sources = topoSort(graph);

  // Compute build metadata for the sidecar JSON.
  const epochMs = Math.floor(Date.now() / 60000) * 60000;

  let gitSha;
  try {
    gitSha = execSync('git rev-parse --short HEAD', {
      cwd: root,
      stdio: ['ignore', 'pipe', 'ignore'],
    })
      .toString()
      .trim();
  } catch (_) {
    gitSha = 'unknown';
  }

  const entries = sources.map((name) => {
    const path = resolve(sourceDir, name);
    return `//#region ${name}\n${readFileSync(path, 'utf8').trimEnd()}\n//#endregion ${name}\n`;
  });

  const concatenated = entries.join('\n');

  const target = resolve(root, 'fixthis-mcp/src/main/resources/console/app.js');
  const mapPath = `${target}.map`;
  const metaPath = resolve(root, 'fixthis-mcp/src/main/resources/console/console-build-meta.json');
  const reproducible = process.env.FIXTHIS_BUNDLE_REPRODUCIBLE === '1'
    || process.argv.includes('--check');
  const meta = reproducible
    ? { buildEpochMs: 0, gitSha: 'reproducible' }
    : { buildEpochMs: epochMs, gitSha };
  const metaSerialized = JSON.stringify(meta, null, 2) + '\n';

  const result = await esbuild({
    stdin: { contents: concatenated, loader: 'js', sourcefile: 'app.js' },
    bundle: false,
    write: false,
    outfile: target,
    minify: false,
    minifyWhitespace: true,
    minifySyntax: true,
    minifyIdentifiers: false,
    target: ['es2020'],
    sourcemap: 'linked',
    legalComments: 'inline',
  });

  const jsBytes = result.outputFiles.find((f) => f.path.endsWith('.js')).contents;
  const mapBytes = result.outputFiles.find((f) => f.path.endsWith('.map')).contents;

  const RAW_BUDGET_BYTES = 170 * 1024;
  const GZIP_BUDGET_BYTES = 40 * 1024;
  if (jsBytes.byteLength > RAW_BUDGET_BYTES) {
    console.error(`Bundle (raw) is ${jsBytes.byteLength} bytes, exceeds raw budget of ${RAW_BUDGET_BYTES} bytes (170 KiB).`);
    process.exit(1);
  }
  const gzBytes = gzipSync(Buffer.from(jsBytes), { level: 9 }).byteLength;
  if (gzBytes > GZIP_BUDGET_BYTES) {
    console.error(`Bundle (gzipped) is ${gzBytes} bytes, exceeds gzip budget of ${GZIP_BUDGET_BYTES} bytes (40 KiB).`);
    process.exit(1);
  }

  if (process.argv.includes('--check')) {
    if (!existsSync(target)) {
      console.error('Generated console app.js is missing. Run node scripts/build-console-assets.mjs.');
      process.exit(1);
    }
    const current = readFileSync(target);
    if (!Buffer.from(jsBytes).equals(current)) {
      console.error('Generated console app.js is out of date. Run node scripts/build-console-assets.mjs.');
      process.exit(1);
    }
    if (!existsSync(mapPath)) {
      console.error('Generated console app.js.map is missing. Run node scripts/build-console-assets.mjs.');
      process.exit(1);
    }
    const currentMap = readFileSync(mapPath);
    if (!Buffer.from(mapBytes).equals(currentMap)) {
      console.error('Generated console app.js.map is out of date. Run node scripts/build-console-assets.mjs.');
      process.exit(1);
    }
    if (!existsSync(metaPath)) {
      console.error('Generated console-build-meta.json is missing. Run FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs.');
      process.exit(1);
    }
    const currentMeta = readFileSync(metaPath, 'utf8');
    if (currentMeta !== metaSerialized) {
      console.error('Generated console-build-meta.json is out of date. Run FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs.');
      process.exit(1);
    }
    process.exit(0);
  }

  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, jsBytes);
  writeFileSync(mapPath, mapBytes);
  writeFileSync(metaPath, metaSerialized);
}
