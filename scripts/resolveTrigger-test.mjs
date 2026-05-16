import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const m = loadConsoleSymbols({
  modules: ['draftUseCases.js'],
  symbols: ['Trigger', 'resolveTrigger', 'toastTextForTrigger', 'boundaryVariantForTrigger'],
});

function makePorts(events) {
  return {
    showToast: (text, options) => events.push({ kind: 'toast', text, options }),
    openBoundaryDialog: (variant, context) => events.push({ kind: 'dialog', variant, context }),
    silentDiscard: () => events.push({ kind: 'discard' }),
    preserve: () => events.push({ kind: 'preserve' }),
    beforeUnloadGuard: () => events.push({ kind: 'beforeunload' }),
  };
}

test('resolveTrigger discards pending targets with contextual toast copy', () => {
  const events = [];
  m.resolveTrigger(m.Trigger.SESSION_SWITCH, { state: 'pendingTarget' }, makePorts(events));

  assert.deepEqual(events.map((event) => event.kind), ['discard', 'toast']);
  assert.match(events[1].text, /Switched/);
});

test('resolveTrigger opens a variant dialog for draft session switches', () => {
  const events = [];
  m.resolveTrigger(m.Trigger.SESSION_SWITCH, { state: 'draft', targetSessionId: 'session-b' }, makePorts(events));

  assert.equal(events.length, 1);
  assert.equal(events[0].kind, 'dialog');
  assert.equal(events[0].variant, 'sessionSwitch');
});

test('resolveTrigger preserves retain and beforeunload verdicts through explicit ports', () => {
  const retainEvents = [];
  m.resolveTrigger(m.Trigger.SERVER_DISCONNECT, { state: 'draft' }, makePorts(retainEvents));
  assert.deepEqual(retainEvents.map((event) => event.kind), ['preserve']);

  const unloadEvents = [];
  m.resolveTrigger(m.Trigger.BROWSER_REFRESH, { state: 'draft' }, makePorts(unloadEvents));
  assert.deepEqual(unloadEvents.map((event) => event.kind), ['beforeunload']);
});

test('toastTextForTrigger and boundaryVariantForTrigger expose stable mappings', () => {
  assert.match(m.toastTextForTrigger(m.Trigger.ESCAPE_KEY), /Cancelled/);
  assert.equal(m.boundaryVariantForTrigger(m.Trigger.SESSION_CREATE), 'sessionCreate');
  assert.equal(m.boundaryVariantForTrigger(m.Trigger.ROUTE_CHANGE), 'routeChange');
});
