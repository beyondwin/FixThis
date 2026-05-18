import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const adapterSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/studioWorkflowAdapter.js'), 'utf8');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
const historySource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
const promptSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/prompt.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  let i = source.indexOf('(', start);
  let parenDepth = 0;
  for (; i < source.length; i += 1) {
    if (source[i] === '(') parenDepth += 1;
    if (source[i] === ')') {
      parenDepth -= 1;
      if (parenDepth === 0) { i += 1; break; }
    }
  }
  const bodyStart = source.indexOf('{', i);
  let depth = 0;
  for (let j = bodyStart; j < source.length; j += 1) {
    if (source[j] === '{') depth += 1;
    if (source[j] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, j);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('browser adapter derives workflow snapshot and surfaces decisions', () => {
  assert.match(adapterSource, /function currentStudioWorkflowSnapshot\(/);
  assert.match(adapterSource, /function decideCurrentStudioWorkflow\(/);
  assert.match(adapterSource, /function surfaceStudioWorkflowDecision\(/);
  assert.match(adapterSource, /decideStudioWorkflow\(action,\s*currentStudioWorkflowSnapshot/);
});

test('annotate flow consults workflow before mutating UI state', () => {
  const start = body(annotationsSource, 'async function startDraftAnnotationFlow()');
  assert.match(start, /decideCurrentStudioWorkflow\(StudioWorkflowAction\.ANNOTATE_CLICKED/);
  assert.match(start, /surfaceStudioWorkflowDecision\(decision\)/);
});

test('annotate mode is entered only after a draft freeze exists', () => {
  const enter = body(historySource, 'async function enterAnnotateMode()');
  const startIndex = enter.indexOf('await startDraftAnnotationFlow();');
  assert.notEqual(startIndex, -1, 'enterAnnotateMode should start the draft freeze flow');
  assert.doesNotMatch(
    enter.slice(0, startIndex),
    /toolMode\.enterAnnotate\(\)/,
    'blocked capture should not leave the toolbar in annotate mode without a frozen draft',
  );
  assert.match(
    enter,
    /if \(!draftFlow\(\)\) \{\s*await startDraftAnnotationFlow\(\);\s*\} else \{\s*toolMode\.enterAnnotate\(\);/,
  );
});

test('new-session annotate does not pre-enter annotate mode before capture can start', () => {
  const enterNew = body(historySource, 'async function enterNewHistoryAnnotateMode()');
  const openIndex = enterNew.indexOf('const openedNewSession = await newSession();');
  assert.notEqual(openIndex, -1, 'enterNewHistoryAnnotateMode should open a new session');
  assert.doesNotMatch(
    enterNew.slice(0, openIndex),
    /toolMode\.enterAnnotate\(\)/,
    'Open app from a blocked capture path should not inherit a fake annotate state',
  );
});

test('blocked draft capture exits an empty annotate shell', () => {
  const start = body(annotationsSource, 'async function startDraftAnnotationFlow()');
  assert.match(
    start,
    /if \(surfaceStudioWorkflowDecision\(decision\)\) \{\s*resetComposer\(false,\s*false\);\s*render\(\);\s*return;\s*\}/,
  );
});

test('composer disables prompt buttons using workflow decisions', () => {
  const update = body(annotationsSource, 'function updateComposerState()');
  assert.match(update, /decideCurrentStudioWorkflow\(StudioWorkflowAction\.SAVE_TO_MCP_CLICKED/);
  assert.match(update, /promptDecision\.type === StudioWorkflowDecisionType\.BLOCK/);
});

test('copy and Save to MCP consult workflow before durable mutation', () => {
  const copy = body(promptSource, 'async function copyPrompt()');
  const send = body(promptSource, 'async function sendAgentPrompt()');
  assert.match(copy, /decideCurrentStudioWorkflow\(StudioWorkflowAction\.COPY_PROMPT_CLICKED/);
  assert.match(copy, /surfaceStudioWorkflowDecision\(decision\)/);
  assert.match(send, /decideCurrentStudioWorkflow\(StudioWorkflowAction\.SAVE_TO_MCP_CLICKED/);
  assert.match(send, /surfaceStudioWorkflowDecision\(decision\)/);
});

const feedbackConsoleContract = readFileSync(resolve(root, 'docs/reference/feedback-console-contract.md'), 'utf8');
const troubleshooting = readFileSync(resolve(root, 'docs/guides/troubleshooting.md'), 'utf8');

test('docs describe automatic recovery and confirmation-required mutations', () => {
  assert.match(feedbackConsoleContract, /Automatic recovery/);
  assert.match(feedbackConsoleContract, /Confirmation-required mutations/);
  assert.match(feedbackConsoleContract, /Connection loss never clears a draft workspace/);
  assert.match(troubleshooting, /Connection paused - draft preserved/);
  assert.match(troubleshooting, /Reopen the session or create a new active session/);
});
