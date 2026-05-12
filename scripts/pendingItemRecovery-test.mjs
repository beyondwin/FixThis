import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pendingPersistence.js'), 'utf8');

function buildLocalStorageStub() {
  const data = new Map();
  return {
    _data: data,
    getItem(k) { return data.has(k) ? data.get(k) : null; },
    setItem(k, v) { data.set(k, String(v)); },
    removeItem(k) { data.delete(k); },
  };
}

function loadModule(localStorage) {
  const factory = new Function('localStorage', `${source}; return { persistPendingItems, restorePendingItems, clearPendingMirror };`);
  return factory(localStorage);
}

test('persistPendingItems writes JSON-stringified items under fixthis.pending.<sid>', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  m.persistPendingItems('s1', [{ itemId: 'i1', comment: 'a' }]);
  const stored = JSON.parse(ls.getItem('fixthis.pending.s1'));
  assert.equal(stored.length, 1);
  assert.equal(stored[0].itemId, 'i1');
});

test('restorePendingItems reads back what was persisted', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  ls.setItem('fixthis.pending.s1', JSON.stringify([{ itemId: 'i1', comment: 'recovered' }]));
  const items = m.restorePendingItems('s1');
  assert.equal(items.length, 1);
  assert.equal(items[0].comment, 'recovered');
});

test('restorePendingItems returns [] when no data exists', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  assert.deepEqual(m.restorePendingItems('s1'), []);
});

test('restorePendingItems returns [] when payload is corrupt', () => {
  const ls = buildLocalStorageStub();
  ls.setItem('fixthis.pending.s1', '{not json');
  const m = loadModule(ls);
  assert.deepEqual(m.restorePendingItems('s1'), []);
});

test('clearPendingMirror removes the entry', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  m.persistPendingItems('s1', [{ itemId: 'i1' }]);
  m.clearPendingMirror('s1');
  assert.equal(ls.getItem('fixthis.pending.s1'), null);
});

test('null/undefined sessionId is a no-op (no throw)', () => {
  const ls = buildLocalStorageStub();
  const m = loadModule(ls);
  m.persistPendingItems(null, [{ itemId: 'i1' }]);
  m.persistPendingItems(undefined, [{ itemId: 'i2' }]);
  assert.equal(ls._data.size, 0);
  assert.deepEqual(m.restorePendingItems(null), []);
});
