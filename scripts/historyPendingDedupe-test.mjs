import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const helperSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/historyPendingDedupe.js'), 'utf8');
const factory = new Function(`
  let state = {};
  ${helperSrc}
  return {
    setState(next) { state = next; },
    dedupePendingHistoryItemsForSession
  };
`);
const m = factory();

const bounds = { left: 1, top: 2, right: 30, bottom: 40 };

test('history pending items skip server items with the same draft identity', () => {
  m.setState({
    session: {
      sessionId: 'session-a',
      items: [{
        clientWorkspaceId: 'ws-a',
        clientDraftItemId: 'draft-1',
        target: { type: 'visual_area', boundsInWindow: bounds },
        comment: 'Fix label',
      }],
    },
  });

  const pending = m.dedupePendingHistoryItemsForSession(
    { sessionId: 'session-a' },
    [{ draftItemId: 'draft-1', targetType: 'area', bounds, comment: 'Fix label' }],
    'ws-a',
  );

  assert.deepEqual(pending, []);
});

test('history pending items skip legacy recovery that semantically matches a server item', () => {
  m.setState({
    session: {
      sessionId: 'session-a',
      items: [{
        target: { type: 'visual_area', boundsInWindow: bounds },
        label: 'Custom area',
        comment: 'Fix label',
      }],
    },
  });

  const pending = m.dedupePendingHistoryItemsForSession(
    { sessionId: 'session-a' },
    [{ targetType: 'area', bounds, label: 'Hero CTA', comment: 'Fix label' }],
    null,
  );

  assert.deepEqual(pending, []);
});
