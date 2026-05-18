import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/studioWorkflow.js'), 'utf8');
const factory = new Function(`${src}; return {
  StudioWorkflowAction,
  StudioWorkflowDecisionType,
  decideStudioWorkflow,
};`);
const m = factory();

function decide(action, patch = {}) {
  return m.decideStudioWorkflow(action, {
    connection: 'ready',
    workspace: 'live-preview',
    operation: 'idle',
    risks: [],
    activeSessionStatus: 'active',
    targetItemExists: true,
    ...patch,
  });
}

test('allows annotate when connection is ready and workspace is live preview', () => {
  const decision = decide(m.StudioWorkflowAction.ANNOTATE_CLICKED);
  assert.equal(decision.type, m.StudioWorkflowDecisionType.ALLOW);
  assert.equal(decision.effect, 'capture-preview');
});

test('blocks annotate when device is blocked', () => {
  const decision = decide(m.StudioWorkflowAction.ANNOTATE_CLICKED, {
    connection: 'blocked',
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.BLOCK);
  assert.equal(decision.reason, 'connection-blocked');
  assert.equal(decision.displaySurface, 'connection-card');
});

test('confirms Save to MCP from stale preview', () => {
  const decision = decide(m.StudioWorkflowAction.SAVE_TO_MCP_CLICKED, {
    workspace: 'frozen-draft',
    risks: ['stale-preview'],
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.CONFIRM);
  assert.equal(decision.boundaryKind, 'fingerprintMismatch');
  assert.deepEqual(decision.choices, ['recapture', 'force', 'cancel']);
});

test('confirms session switch with dirty draft', () => {
  const decision = decide(m.StudioWorkflowAction.SESSION_SWITCH_REQUESTED, {
    workspace: 'frozen-draft',
    risks: ['dirty-draft'],
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.CONFIRM);
  assert.equal(decision.boundaryKind, 'sessionSwitch');
});

test('blocks session switch while durable mutation is in flight', () => {
  const decision = decide(m.StudioWorkflowAction.SESSION_SWITCH_REQUESTED, {
    operation: 'saving-handoff',
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.BLOCK);
  assert.equal(decision.reason, 'operation-in-flight');
  assert.equal(decision.displaySurface, 'prompt-readiness');
});

test('ignores late async response for stale generation', () => {
  const decision = decide(m.StudioWorkflowAction.ASYNC_RESPONSE_RECEIVED, {
    currentGeneration: 7,
    eventGeneration: 6,
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.IGNORE);
  assert.equal(decision.reason, 'stale-generation');
});

test('ignores late async response for inactive session', () => {
  const decision = decide(m.StudioWorkflowAction.ASYNC_RESPONSE_RECEIVED, {
    activeSessionId: 'session-a',
    eventSessionId: 'session-b',
    currentGeneration: 7,
    eventGeneration: 7,
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.IGNORE);
  assert.equal(decision.reason, 'session-mismatch');
});

test('blocks resolve when item is missing', () => {
  const decision = decide(m.StudioWorkflowAction.RESOLVE_ITEM_CLICKED, {
    targetItemExists: false,
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.BLOCK);
  assert.equal(decision.reason, 'missing-item');
});

test('blocks durable mutations for closed session', () => {
  const decision = decide(m.StudioWorkflowAction.CLAIM_ITEM_CLICKED, {
    activeSessionStatus: 'closed',
  });
  assert.equal(decision.type, m.StudioWorkflowDecisionType.BLOCK);
  assert.equal(decision.reason, 'closed-session');
});
