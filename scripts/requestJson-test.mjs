import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

test('requestJson preserves structured error body', async () => {
  const responseBody = {
    error: 'forbidden_origin',
    message: 'Forbidden origin',
    action: 'reload_console',
    details: { expectedHost: '127.0.0.1' },
  };
  const fetchImpl = async () => ({
    ok: false,
    status: 403,
    headers: new Map([['content-type', 'application/json']]),
    async text() { return JSON.stringify(responseBody); },
    async json() { return responseBody; },
  });
  const { requestJson, ConsoleRequestError } = loadConsoleSymbols({
    modules: ['api.js'],
    symbols: ['requestJson', 'ConsoleRequestError'],
    args: ['fetch'],
    values: [fetchImpl],
  });

  await assert.rejects(
    () => requestJson('/api/items', { method: 'POST' }),
    (error) => {
      assert.ok(error instanceof ConsoleRequestError);
      assert.equal(error.status, 403);
      assert.equal(error.error, 'forbidden_origin');
      assert.equal(error.action, 'reload_console');
      assert.equal(error.details.expectedHost, '127.0.0.1');
      return true;
    },
  );
});
