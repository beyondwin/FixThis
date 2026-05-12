import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/activityDrift.js'),
  'utf8'
);
const factory = new Function(`${source}; return { checkActivityDrift };`);
const { checkActivityDrift } = factory();

test('returns drift:true when current activity differs from freeze activity', () => {
  const result = checkActivityDrift(
    { activity: 'com.example.HomeActivity' },
    { activity: 'com.example.DetailActivity' }
  );
  assert.equal(result.drift, true);
});

test('returns drift:false when activities match', () => {
  const result = checkActivityDrift(
    { activity: 'com.example.HomeActivity' },
    { activity: 'com.example.HomeActivity' }
  );
  assert.equal(result.drift, false);
});

test('returns drift:false when flow.activity missing (early-out safety)', () => {
  assert.equal(
    checkActivityDrift({}, { activity: 'com.example.HomeActivity' }).drift,
    false
  );
  assert.equal(
    checkActivityDrift({ activity: null }, { activity: 'com.example.HomeActivity' }).drift,
    false
  );
  assert.equal(
    checkActivityDrift(null, { activity: 'com.example.HomeActivity' }).drift,
    false
  );
});

test('returns drift:false when current.activity missing', () => {
  assert.equal(
    checkActivityDrift({ activity: 'com.example.HomeActivity' }, {}).drift,
    false
  );
  assert.equal(
    checkActivityDrift({ activity: 'com.example.HomeActivity' }, { activity: null }).drift,
    false
  );
  assert.equal(
    checkActivityDrift({ activity: 'com.example.HomeActivity' }, null).drift,
    false
  );
});

test('drift result shape includes expected and actual when drifted', () => {
  const result = checkActivityDrift(
    { activity: 'com.example.HomeActivity' },
    { activity: 'com.example.DetailActivity' }
  );
  assert.equal(result.drift, true);
  assert.equal(result.expected, 'com.example.HomeActivity');
  assert.equal(result.actual, 'com.example.DetailActivity');
});
