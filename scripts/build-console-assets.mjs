#!/usr/bin/env node
import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

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

const buildHeaderRegex = /\/\/ build-header\nconst ConsoleBuildEpochMs = \d+;\nconst ConsoleBuildGitSha = '[^']+';\n/g;
const normalizedBuildHeader = "// build-header\nconst ConsoleBuildEpochMs = 0;\nconst ConsoleBuildGitSha = 'normalized';\n";
function normalizeBuildHeader(text) {
  return text.replace(buildHeaderRegex, normalizedBuildHeader);
}

const isMainModule = import.meta.url === `file://${process.argv[1]}`;
if (isMainModule) {
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

  // Compute build metadata for the header injected after state.js.
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

  const buildHeader = `// build-header\nconst ConsoleBuildEpochMs = ${epochMs};\nconst ConsoleBuildGitSha = '${gitSha}';\n`;

  const entries = sources.map((name) => {
    const path = resolve(sourceDir, name);
    return `//#region ${name}\n${readFileSync(path, 'utf8').trimEnd()}\n//#endregion ${name}\n`;
  });

  // Splice the build header immediately after state.js.
  entries.splice(sources.indexOf('state.js') + 1, 0, buildHeader);

  const concatenated = entries.join('\n');
  const output = concatenated;

  const target = resolve(root, 'fixthis-mcp/src/main/resources/console/app.js');

  if (process.argv.includes('--check')) {
    if (!existsSync(target)) {
      console.error('Generated console app.js is missing. Run node scripts/build-console-assets.mjs.');
      process.exit(1);
    }
    const current = readFileSync(target, 'utf8');
    if (normalizeBuildHeader(current) !== normalizeBuildHeader(output)) {
      console.error('Generated console app.js is out of date. Run node scripts/build-console-assets.mjs.');
      process.exit(1);
    }
    process.exit(0);
  }

  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, output);
}
