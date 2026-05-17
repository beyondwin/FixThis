import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(new URL('..', import.meta.url).pathname);

test('first-run smoke verifies sent feedback after Save to MCP', () => {
  const source = readFileSync(resolve(root, 'scripts/first-run-smoke.mjs'), 'utf8');
  assert.match(source, /\/api\/agent-handoffs/);
  assert.match(source, /\/api\/session/);
  assert.match(source, /delivery/);
  assert.match(source, /sent/);
  assert.match(source, /Make the primary button label clearer/);
});
