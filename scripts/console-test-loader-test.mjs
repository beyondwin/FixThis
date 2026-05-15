import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  consoleModuleClosure,
  consoleModuleSourceOrder,
  loadConsoleSymbols,
} from './console-test-loader.mjs';

test('consoleModuleSourceOrder expands dependencies before requested modules', () => {
  const order = consoleModuleSourceOrder(['domain/consoleReducer.js']);
  const reducerIndex = order.indexOf('domain/consoleReducer.js');
  assert.ok(reducerIndex >= 0, 'requested reducer module is present');
  assert.ok(
    order.indexOf('domain/workspaceState.js') >= 0 &&
      order.indexOf('domain/workspaceState.js') < reducerIndex,
    'workspaceState dependency precedes reducer',
  );
  assert.ok(
    order.indexOf('domain/consoleAppState.js') >= 0 &&
      order.indexOf('domain/consoleAppState.js') < reducerIndex,
    'consoleAppState dependency precedes reducer',
  );
});

test('loadConsoleSymbols returns requested symbols from evaluated modules', () => {
  const m = loadConsoleSymbols({
    modules: ['undoRedo.js'],
    symbols: ['createHistory', 'recordAdd', 'undo', 'redo'],
  });
  const history = m.createHistory();
  m.recordAdd(history, { itemId: 'item-1' });
  assert.equal(history.undoStack.length, 1);
  assert.equal(typeof m.undo, 'function');
  assert.equal(typeof m.redo, 'function');
});

test('loadConsoleSymbols fails with a clear error for unknown symbols', () => {
  assert.throws(
    () => loadConsoleSymbols({ modules: ['undoRedo.js'], symbols: ['doesNotExist'] }),
    /Console symbol not found: doesNotExist/,
  );
});

test('consoleModuleClosure can be parsed by Function', () => {
  const source = consoleModuleClosure(['draftWorkspace.js']);
  assert.doesNotThrow(() => new Function(`${source}; return true;`));
});
