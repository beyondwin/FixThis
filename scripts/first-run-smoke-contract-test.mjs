import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(new URL('..', import.meta.url).pathname);

test('first-run smoke verifies sent feedback after Save to MCP', () => {
  const source = readFileSync(resolve(root, 'scripts/first-run-smoke.mjs'), 'utf8');
  assert.match(source, /\/api\/agent-handoffs/);
  assert.match(source, /\/api\/session/);
  // Bind the contract to the literal `delivery` property name on a session item — a
  // rename of the field (e.g. `delivery` → `dispatchStatus`) must trip this test, not
  // just slip through because the word `delivery` happens to appear in a comment.
  assert.match(source, /\bitems\[\d+\]\.delivery\b/);
  // And bind the asserted post-handoff value to the literal string `'sent'` paired with
  // the same property — guards against the script being relaxed to assert `delivery`
  // alone without the success state.
  assert.match(source, /\.delivery[^\n]*['"]sent['"]/);
  assert.match(source, /Make the primary button label clearer/);
});
