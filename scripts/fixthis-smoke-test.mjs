import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));

function writeExecutable(path, body) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

test('fixthis-smoke staleness MCP call keeps stdin open until status payload arrives', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-smoke-'));
  try {
    mkdirSync(join(root, 'scripts'), { recursive: true });
    mkdirSync(join(root, 'sample/src/main/java/io/github/beyondwin/fixthis/sample'), { recursive: true });
    mkdirSync(join(root, 'fixthis-cli/build/install/fixthis/bin'), { recursive: true });
    mkdirSync(join(root, 'fixthis-mcp/build/install/fixthis-mcp/bin'), { recursive: true });
    mkdirSync(join(root, 'bin'), { recursive: true });

    copyFileSync(join(repoRoot, 'scripts/fixthis-smoke.sh'), join(root, 'scripts/fixthis-smoke.sh'));
    chmodSync(join(root, 'scripts/fixthis-smoke.sh'), 0o755);
    writeFileSync(
      join(root, 'sample/src/main/java/io/github/beyondwin/fixthis/sample/MainActivity.kt'),
      'package io.github.beyondwin.fixthis.sample\n',
    );

    writeExecutable(
      join(root, 'gradlew'),
      `#!/usr/bin/env bash
set -euo pipefail
if [[ "\${1:-}" == "--version" ]]; then
  echo "Gradle 9.0"
  exit 0
fi
for arg in "$@"; do
  if [[ "$arg" == ":app:installDebug" ]]; then
    touch .fake-install-marker
  fi
done
exit 0
`,
    );

    writeExecutable(
      join(root, 'bin/adb'),
      `#!/usr/bin/env bash
set -euo pipefail
if [[ "\${1:-}" == "devices" ]]; then
  echo "List of devices attached"
  echo "fake-device device product:fake"
  exit 0
fi
if [[ "\${1:-}" == "-s" ]]; then shift 2; fi
if [[ "\${1:-}" == "shell" && "\${2:-}" == "dumpsys" ]]; then
  echo "mShowingLockscreen=false"
  exit 0
fi
exit 0
`,
    );

    writeExecutable(
      join(root, 'fixthis-cli/build/install/fixthis/bin/fixthis'),
      `#!/usr/bin/env bash
set -euo pipefail
echo "FixThis doctor OK"
`,
    );

    writeExecutable(
      join(root, 'fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp'),
      `#!/usr/bin/env node
const fs = require('node:fs');
const readline = require('node:readline');

const args = process.argv.slice(2);
const projectDir = args[args.indexOf('--project-dir') + 1];
const source = projectDir + '/sample/src/main/java/io/github/beyondwin/fixthis/sample/MainActivity.kt';
const marker = projectDir + '/.fake-install-marker';
let pendingStatus = null;

function statusPayload() {
  const sourceMtime = fs.statSync(source).mtimeMs;
  const markerMtime = fs.statSync(marker).mtimeMs;
  const installStale = sourceMtime > markerMtime;
  return {
    packageName: 'io.github.beyondwin.fixthis.sample',
    installStale,
    newerSourceFiles: installStale ? [source] : [],
  };
}

function write(value) {
  process.stdout.write(JSON.stringify(value) + '\\n');
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', line => {
  const message = JSON.parse(line);
  if (message.id === 1) {
    write({ jsonrpc: '2.0', id: 1, result: {} });
    return;
  }
  if (message.id === 2) {
    pendingStatus = setTimeout(() => {
      pendingStatus = null;
      write({
        jsonrpc: '2.0',
        id: 2,
        result: {
          content: [{ type: 'text', text: JSON.stringify(statusPayload()) }],
          isError: false,
        },
      });
    }, 100);
  }
});
rl.on('close', () => {
  if (pendingStatus) clearTimeout(pendingStatus);
});
`,
    );

    const result = spawnSync('bash', ['scripts/fixthis-smoke.sh', '--check-staleness', '--device', 'fake-device'], {
      cwd: root,
      env: {
        ...process.env,
        PATH: `${join(root, 'bin')}:${process.env.PATH}`,
        ANDROID_SERIAL: '',
      },
      encoding: 'utf8',
      timeout: 20_000,
    });

    assert.equal(
      result.status,
      0,
      `smoke script failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`,
    );
    assert.match(result.stdout, /Result: PASS/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
