import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const candidates = [
  'scripts/connectionFsm-test.mjs',
  'scripts/connectionUseCases-test.mjs',
  'scripts/previewFsm-test.mjs',
  'scripts/previewUseCases-test.mjs',
  'scripts/pollingFsm-test.mjs',
  'scripts/pollingUseCases-test.mjs',
  'scripts/toolModeFsm-test.mjs',
  'scripts/toolModeUseCases-test.mjs',
  'scripts/consoleFsmContract-test.mjs',
];
const existing = candidates.filter((p) => existsSync(resolve(root, p)));
if (existing.length === 0) {
  console.error('no console FSM test files found');
  process.exit(1);
}
const result = spawnSync('node', ['--test', ...existing], { stdio: 'inherit', cwd: root });
process.exit(result.status ?? 1);
