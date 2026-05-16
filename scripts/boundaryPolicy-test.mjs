import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['boundaryPolicy.js'],
  symbols: ['Trigger', 'boundaryPolicy'],
});

test('boundaryPolicy resolves the trigger by editor state matrix', () => {
  const groups = [
    {
      triggers: ['SESSION_SWITCH', 'SESSION_CREATE', 'SESSION_DELETE', 'ROUTE_CHANGE', 'EDITOR_BACK'],
      none: 'pass',
      pendingTarget: 'discardWithToast',
      draft: 'boundaryDialog',
    },
    {
      triggers: ['SCREEN_SWITCH', 'NEW_CAPTURE'],
      none: 'pass',
      pendingTarget: 'discardWithToast',
      draft: 'preserve',
    },
    {
      triggers: ['ELEMENT_CLICK'],
      none: 'pass',
      pendingTarget: 'silentSwap',
      draft: 'preserve',
    },
    {
      triggers: ['ESCAPE_KEY'],
      none: 'pass',
      pendingTarget: 'discardWithToast',
      draft: 'discardConfirm',
    },
    {
      triggers: ['BROWSER_REFRESH', 'TAB_CLOSE'],
      none: 'pass',
      pendingTarget: 'silentLoss',
      draft: 'beforeunloadGuard',
    },
    {
      triggers: ['SERVER_DISCONNECT'],
      none: 'pass',
      pendingTarget: 'retainWithBadge',
      draft: 'retainWithBadge',
    },
    {
      triggers: ['RECONNECT'],
      none: 'pass',
      pendingTarget: 'staleRevalidate',
      draft: 'staleRevalidate',
    },
    {
      triggers: ['BRIDGE_MISMATCH'],
      none: 'pass',
      pendingTarget: 'discardWithToast',
      draft: 'retainAndMigrate',
    },
    {
      triggers: ['ACTIVITY_DRIFT', 'INACTIVITY'],
      none: 'pass',
      pendingTarget: 'retain',
      draft: 'retain',
    },
  ];

  for (const group of groups) {
    for (const triggerName of group.triggers) {
      for (const state of ['none', 'pendingTarget', 'draft']) {
        assert.equal(
          m.boundaryPolicy(m.Trigger[triggerName], state, {}),
          group[state],
          `${triggerName} x ${state}`,
        );
      }
    }
  }
});

test('boundaryPolicy treats saved as pass for context transitions', () => {
  assert.equal(m.boundaryPolicy(m.Trigger.SESSION_SWITCH, 'saved', {}), 'pass');
});
