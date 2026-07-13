import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const promptSource = readFileSync('fixthis-mcp/src/main/console/prompt.js', 'utf8');
const runtimeModule = loadConsoleSymbols({
  modules: ['runtimeEvidence.js'],
  symbols: ['createRuntimeEvidenceController'],
});
const promptModule = loadConsoleSymbols({
  modules: ['prompt.js'],
  symbols: ['handoffContextMatches', 'runtimeEvidenceHandoffSuccessMessage'],
});

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((next, fail) => { resolve = next; reject = fail; });
  return { promise, resolve, reject };
}

function functionBody(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `missing ${signature}`);
  const open = source.indexOf('{', start);
  let depth = 0;
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    if (source[index] === '}' && --depth === 0) return source.slice(open + 1, index);
  }
  throw new Error(`unterminated ${signature}`);
}

test('Save waits for the latest durable policy before persisting annotations', async () => {
  let activeSession = { sessionId: 's1', runtimeEvidencePolicy: 'auto_on_handoff' };
  const pending = deferred();
  const controller = runtimeModule.createRuntimeEvidenceController({
    activeSession: () => activeSession,
    request: () => pending.promise,
    applySession: session => { activeSession = session; },
    render: () => {},
  });
  const update = controller.updatePolicy('off');
  let settled = false;
  controller.awaitPolicySettled('s1').then(() => { settled = true; });
  await Promise.resolve();
  assert.equal(settled, false);
  pending.resolve({ sessionId: 's1', runtimeEvidencePolicy: 'off' });
  await update;
  await controller.awaitPolicySettled('s1');
  assert.equal(settled, true);

  const send = functionBody(promptSource, 'async function sendAgentPrompt()');
  const awaitPolicy = send.indexOf('await runtimeEvidenceController().awaitPolicySettled(sessionId)');
  const persist = send.indexOf('await persistAndCollectItemIds');
  const handoff = send.indexOf("requestJson('/api/agent-handoffs'");
  assert.ok(awaitPolicy >= 0 && awaitPolicy < persist && persist < handoff);
});

test('failed Off persistence prevents Save from crossing the policy barrier', async () => {
  const pending = deferred();
  const controller = runtimeModule.createRuntimeEvidenceController({
    activeSession: () => ({ sessionId: 's1', runtimeEvidencePolicy: 'auto_on_handoff' }),
    request: () => pending.promise,
    applySession: () => {},
    render: () => {},
  });
  const update = controller.updatePolicy('off');
  pending.reject(new Error('policy write failed'));
  await assert.rejects(update, /policy write failed/);
  await assert.rejects(controller.awaitPolicySettled('s1'), /policy write failed/);
});

test('handoff terminal copy is allowlisted and never projects raw collector output', () => {
  const message = promptModule.runtimeEvidenceHandoffSuccessMessage({
    attempted: true,
    status: 'partial',
    stdout: 'secret raw stdout',
    stderr: 'secret raw stderr',
  });
  assert.equal(message, 'Saved to MCP ✓ — diagnostics partial');
  assert.doesNotMatch(message, /secret|stdout|stderr/);
  assert.equal(
    promptModule.runtimeEvidenceHandoffSuccessMessage(undefined),
    'Saved to MCP ✓ — agent will pick up',
  );
});

test('Save shows bounded collection status while Copy Prompt stays outside automatic collection', () => {
  const send = functionBody(promptSource, 'async function sendAgentPrompt()');
  assert.ok(send.indexOf("showStatus('Collecting diagnostics…'") < send.indexOf("requestJson('/api/agent-handoffs'"));
  assert.match(send, /runtimeEvidenceHandoffSuccessMessage\(result\.runtimeEvidence\)/);

  const copy = functionBody(promptSource, 'async function copyPrompt()');
  assert.doesNotMatch(copy, /runtimeEvidenceController|agent-handoffs|collectRuntimeEvidence/);
});

test('session switch fences every asynchronous Save boundary and late responses', () => {
  assert.equal(promptModule.handoffContextMatches({ sessionId: 's1', generation: 4 }, 's1', 4), true);
  assert.equal(promptModule.handoffContextMatches({ sessionId: 's1', generation: 4 }, 's2', 4), false);
  assert.equal(promptModule.handoffContextMatches({ sessionId: 's1', generation: 4 }, 's1', 5), false);

  const send = functionBody(promptSource, 'async function sendAgentPrompt()');
  const policyBarrier = send.indexOf('await runtimeEvidenceController().awaitPolicySettled(sessionId)');
  const persist = send.indexOf('await persistAndCollectItemIds');
  const request = send.indexOf("requestJson('/api/agent-handoffs'");
  const apply = send.indexOf('setConsoleSession(result.session)');
  const fences = [...send.matchAll(/handoffContextMatches\(handoffContext\)/g)].map(match => match.index);
  assert.equal(fences.length, 3);
  assert.ok(policyBarrier < fences[0] && fences[0] < persist);
  assert.ok(persist < fences[1] && fences[1] < request);
  assert.ok(request < fences[2] && fences[2] < apply);
});
