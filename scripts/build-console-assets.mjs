#!/usr/bin/env node
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'state.js',
  'api.js',
  'connection.js',
  'availability.js',
  'devices.js',
  'preview.js',
  'annotations.js',
  'history.js',
  'prompt.js',
  'rendering.js',
  'sessions-polling.js',
  'shortcuts.js',
  'main.js',
];

const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');
const declared = new Set(sources);
const onDisk = readdirSync(sourceDir)
  .filter((name) => name.endsWith('.js'));

const undeclared = onDisk.filter((name) => !declared.has(name));
if (undeclared.length > 0) {
  console.error(
    `build-console-assets: ${undeclared.length} .js file(s) on disk are not declared in the sources array:\n` +
    undeclared.map((n) => `  - ${n}`).join('\n') +
    `\nAdd them explicitly to the sources array (order matters — later modules can reference earlier ones).`
  );
  process.exit(1);
}

const output = sources
  .map((name) => {
    const path = resolve(root, 'fixthis-mcp/src/main/console', name);
    return `// ${name}\n${readFileSync(path, 'utf8').trimEnd()}\n`;
  })
  .join('\n');

const target = resolve(root, 'fixthis-mcp/src/main/resources/console/app.js');

if (process.argv.includes('--check')) {
  if (!existsSync(target)) {
    console.error('Generated console app.js is missing. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  const current = readFileSync(target, 'utf8');
  if (current !== output) {
    console.error('Generated console app.js is out of date. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  process.exit(0);
}

mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, output);
