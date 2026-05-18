import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['editorState.js'],
  symbols: ['deriveEditorState'],
});

test('deriveEditorState maps selection, workspace, and saved item to editor ladder states', () => {
  const cases = [
    {
      name: 'no selection, no workspace, no saved item',
      input: { workspace: null, selection: null, savedItem: null },
      expected: 'none',
    },
    {
      name: 'selection only',
      input: { workspace: null, selection: { id: 'el-1' }, savedItem: null },
      expected: 'pendingTarget',
    },
    {
      name: 'empty workspace plus selection',
      input: { workspace: { lifecycle: 'empty', items: [] }, selection: { id: 'el-1' }, savedItem: null },
      expected: 'pendingTarget',
    },
    {
      name: 'workspace with non-empty item',
      input: { workspace: { lifecycle: 'editing', items: [{ comment: 'x' }] }, selection: { id: 'el-1' }, savedItem: null },
      expected: 'draft',
    },
    {
      name: 'workspace with item but no current selection',
      input: { workspace: { lifecycle: 'editing', items: [{ comment: 'x' }] }, selection: null, savedItem: null },
      expected: 'draft',
    },
    {
      name: 'saved item without workspace',
      input: { workspace: null, selection: null, savedItem: { itemId: 'item-1' } },
      expected: 'saved',
    },
    {
      name: 'saved item selected while a draft workspace remains active',
      input: {
        workspace: { lifecycle: 'editing', workspaceId: 'workspace-1', items: [{ comment: 'new draft' }] },
        selection: null,
        savedItem: { itemId: 'item-1' },
      },
      expected: 'saved',
    },
  ];

  for (const item of cases) {
    assert.equal(
      m.deriveEditorState(item.input.workspace, item.input.selection, item.input.savedItem),
      item.expected,
      item.name,
    );
  }
});
