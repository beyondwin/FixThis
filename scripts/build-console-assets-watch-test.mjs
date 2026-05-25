import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { existsSync, readFileSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as wait } from 'node:timers/promises';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));

async function waitFor(predicate, timeoutMs = 5000, stepMs = 50) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return true;
    await wait(stepMs);
  }
  throw new Error('waitFor: predicate never became true');
}

async function runReproducibleBuild() {
  const proc = spawn('node', ['scripts/build-console-assets.mjs'], {
    cwd: repoRoot,
    stdio: 'inherit',
    env: { ...process.env, FIXTHIS_BUNDLE_REPRODUCIBLE: '1' },
  });
  return new Promise((r) => proc.once('exit', r));
}

test('watch mode rewrites artifacts atomically on source change', async (t) => {
  const consoleSrc = join(repoRoot, 'fixthis-mcp/src/main/console');
  const probeFile = join(consoleSrc, 'devReloadProbe.js');
  // Use a unique probe module to avoid touching real sources.
  writeFileSync(probeFile, '// @requires (none)\nconst FixThisDevReloadProbe = 1;\n');

  // Cleanup: remove the probe and rebuild reproducibly so any subsequent
  // `node scripts/build-console-assets.mjs --check` sees the canonical artifacts.
  t.after(async () => {
    try { unlinkSync(probeFile); } catch {}
    await runReproducibleBuild();
  });

  const child = spawn(
    'node',
    ['scripts/build-console-assets.mjs', '--watch'],
    {
      cwd: repoRoot,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...process.env, FIXTHIS_BUNDLE_REPRODUCIBLE: '1' },
    },
  );
  let exited = false;
  child.once('exit', () => { exited = true; });
  t.after(() => { if (!exited) child.kill('SIGINT'); });

  // Count `[watch] rebuilt at ...` log lines so we know exactly when the
  // watcher has finished a rebuild — guards against fs.watch racing with the
  // initial in-flight rebuild.
  let rebuildCount = 0;
  createInterface({ input: child.stdout }).on('line', (line) => {
    if (line.startsWith('[watch] rebuilt at ')) rebuildCount += 1;
  });

  // Wait for the initial build to complete.
  await waitFor(() => rebuildCount >= 1, 10000);
  const metaPath = join(repoRoot, 'fixthis-mcp/src/main/resources/console/console-build-meta.json');
  assert.ok(existsSync(metaPath), 'initial rebuild should commit console-build-meta.json');
  const firstMtime = statSync(metaPath).mtimeMs;

  // Touch the probe to trigger a rebuild.
  await wait(100);
  writeFileSync(probeFile, '// @requires (none)\nconst FixThisDevReloadProbe = 2;\n');

  await waitFor(() => rebuildCount >= 2 && statSync(metaPath).mtimeMs > firstMtime, 10000);

  // After stopping the watcher, --check must pass against the produced artifacts.
  child.kill('SIGINT');
  await new Promise((r) => child.once('exit', r));

  const check = spawn(
    'node',
    ['scripts/build-console-assets.mjs', '--check'],
    { cwd: repoRoot, stdio: 'inherit', env: { ...process.env, FIXTHIS_BUNDLE_REPRODUCIBLE: '1' } },
  );
  const code = await new Promise((r) => check.once('exit', r));
  assert.equal(code, 0, '--check should pass against watch-mode artifacts');
});
