import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const mainSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
const historySource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
const inspectorFooterActionsSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/inspectorFooterActions.js'),
  'utf8',
);

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const paramsEnd = source.indexOf(')', start);
  const bodyStart = source.indexOf('{', paramsEnd);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

function functionText(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const paramsEnd = source.indexOf(')', start);
  const bodyStart = source.indexOf('{', paramsEnd);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  assert.fail(`${signature} body did not close`);
}

function promptPendingBoundaryChoiceWithWindow(windowLike) {
  return new Function('window', `${functionText(mainSource, 'function promptPendingBoundaryChoice(action, count)')}; return promptPendingBoundaryChoice;`)(windowLike);
}

function promptScreenFingerprintMismatchWithWindow(windowLike) {
  return new Function('window', `${functionText(annotationsSource, 'function promptScreenFingerprintMismatch(frozenFingerprint, currentFingerprint)')}; return promptScreenFingerprintMismatch;`)(windowLike);
}

test('shared pending boundary resolver exists', () => {
  assert.match(mainSource, /async function resolvePendingBeforeBoundary\(action,\s*sessionId = null\)/);
  assert.match(mainSource, /promptBoundaryDialogChoice\('sessionDelete'/);
  assert.match(mainSource, /promptBoundaryDialogChoice\('sessionCreate'/);
  assert.match(mainSource, /promptBoundaryDialogChoice\('sessionSwitch'/);
});

test('shared pending boundary resolver delegates to draft boundary use case', () => {
  assert.match(mainSource, /async function resolvePendingBeforeBoundary\(action,\s*sessionId = null\)/);
  assert.match(mainSource, /resolveLifecycleBoundary\(/);
  assert.match(mainSource, /ensureDraftCommandQueue\(\)\.enqueue/);
});

test('delete session draft boundary routes through boundary dialog hook', async () => {
  const calls = [];
  const promptPendingBoundaryChoice = promptPendingBoundaryChoiceWithWindow({
    fixThisPromptPendingBoundary: (payload) => {
      calls.push(payload);
      return 'discard';
    },
  });

  const choice = await promptPendingBoundaryChoice('delete-session', 1);

  assert.equal(choice, 'discard');
  assert.deepEqual(calls, [{ action: 'delete-session', count: 1 }]);
});

test('new session draft boundary cancel returns cancel via boundary dialog hook', async () => {
  const calls = [];
  const promptPendingBoundaryChoice = promptPendingBoundaryChoiceWithWindow({
    fixThisPromptPendingBoundary: (payload) => {
      calls.push(payload);
      return 'cancel';
    },
  });

  const choice = await promptPendingBoundaryChoice('new-session', 4);

  assert.equal(choice, 'cancel');
  assert.deepEqual(calls, [{ action: 'new-session', count: 4 }]);
});

test('default session draft boundary cancel returns cancel via boundary dialog hook', async () => {
  const calls = [];
  const promptPendingBoundaryChoice = promptPendingBoundaryChoiceWithWindow({
    fixThisPromptPendingBoundary: (payload) => {
      calls.push(payload);
      return 'cancel';
    },
  });

  const choice = await promptPendingBoundaryChoice('close-session', 2);

  assert.equal(choice, 'cancel');
  assert.deepEqual(calls, [{ action: 'close-session', count: 2 }]);
});

test('fingerprint mismatch cancel returns cancel via boundary dialog hook', async () => {
  const calls = [];
  const promptScreenFingerprintMismatch = promptScreenFingerprintMismatchWithWindow({
    fixThisPromptFingerprintMismatch: (payload) => {
      calls.push(payload);
      return 'cancel';
    },
  });

  const choice = await promptScreenFingerprintMismatch('frozen-fp', 'current-fp');

  assert.equal(choice, 'cancel');
  assert.deepEqual(calls, [{ frozenFingerprint: 'frozen-fp', currentFingerprint: 'current-fp' }]);
});

test('openSession and newSession use boundary resolver instead of immediate flush', () => {
  const openBody = body(historySource, 'async function openSession(sessionId)');
  const newBody = body(historySource, 'async function newSession()');
  assert.match(openBody, /await resolvePendingBeforeBoundary\('open-session',\s*sessionId\)/);
  assert.match(newBody, /await resolvePendingBeforeBoundary\('new-session'\)/);
  assert.doesNotMatch(openBody, /flushPendingAnnotationsBeforeSessionChange\(\)/);
  assert.doesNotMatch(newBody, /flushPendingAnnotationsBeforeSessionChange\(\)/);
});

test('new history annotate stops when new session creation is cancelled', () => {
  const enterBody = body(historySource, 'async function enterNewHistoryAnnotateMode()');
  const newBody = body(historySource, 'async function newSession()');

  assert.match(newBody, /return false;/);
  assert.match(newBody, /return true;/);
  assert.match(enterBody, /const openedNewSession = await newSession\(\);/);
  assert.match(enterBody, /if \(!openedNewSession\) \{[\s\S]*?return;[\s\S]*?\}/);
  assert.match(enterBody, /if \(!wasAnnotating\) toolMode\.enterSelect\(\);/);
});

test('session navigation exposes in-flight state instead of silently racing clicks', () => {
  const openBody = body(historySource, 'async function openSession(sessionId)');
  assert.match(historySource, /let sessionNavigationInFlight = false;/);
  assert.match(historySource, /let pendingSessionNavigationId = null;/);
  assert.match(historySource, /function isSessionNavigationInFlight\(\)/);
  assert.match(openBody, /if \(sessionNavigationInFlight\) \{/);
  assert.match(openBody, /pendingSessionNavigationId = sessionId;/);
  assert.match(openBody, /sessionNavigationInFlight = true;/);
  assert.match(openBody, /sessionNavigationInFlight = false;/);
  assert.match(openBody, /const queuedSessionId = pendingSessionNavigationId;/);
  assert.match(openBody, /if \(queuedSessionId && queuedSessionId !== state\.session\?\.sessionId\)/);
  assert.match(historySource, /aria-busy/);
});

test('session navigation completion fully rerenders history controls', () => {
  const openBody = body(historySource, 'async function openSession(sessionId)');
  assert.match(
    openBody,
    /sessionNavigationInFlight = false;[\s\S]*?pendingSessionNavigationId = null;[\s\S]*?renderCurrentSessionList\(\);/,
  );
});

test('session list render reenables delete controls when navigation settles', () => {
  const renderBody = body(historySource, 'function renderSessionsList()');
  assert.match(renderBody, /const navigationInFlight = isSessionNavigationInFlight\(\);/);
  assert.match(renderBody, /button\.disabled = navigationInFlight;/);
});

test('reopening the active history session does not reset pending annotation flow', () => {
  const openBody = body(historySource, 'async function openSession(sessionId)');
  assert.match(openBody, /if \(sessionId === state\.session\?\.sessionId\) \{/);
  assert.match(openBody, /if \(sessionId === state\.session\?\.sessionId\) \{[\s\S]*?return;[\s\S]*?resetComposer\(true,\s*false\);/);
});

test('closeSession uses boundary resolver before reset', () => {
  const closeBody = body(historySource, 'async function closeSession()');
  assert.match(closeBody, /await resolvePendingBeforeBoundary\('close-session'/);
  assert.doesNotMatch(closeBody, /resetComposer\(\);[\s\S]*requestJson\('\/api\/session\/close'/);
});

test('deleteHistorySession uses boundary resolver before reset', () => {
  const deleteBody = body(historySource, 'async function deleteHistorySession(sessionId, options = {})');
  assert.match(deleteBody, /await resolvePendingBeforeBoundary\('delete-session',\s*sessionId\)/);
  assert.doesNotMatch(deleteBody, /if \(isDisplayedSession\(\)\) \{\s*resetComposer\(\);/);
});

test('deleteHistorySession clears local recovery for the deleted session', () => {
  const deleteBody = body(historySource, 'async function deleteHistorySession(sessionId, options = {})');
  assert.match(deleteBody, /deleteWorkspacesForSession\(sessionId\)/);
});

test('deleteHistorySession resets a draft workspace even when no session is displayed', () => {
  const deleteBody = body(historySource, 'async function deleteHistorySession(sessionId, options = {})');
  assert.match(deleteBody, /const hasDisplayedDraftForDeletedSession = \(\) => draftWorkspace\?\.context\?\.sessionId === sessionId;/);
  assert.match(deleteBody, /const wasDisplayedDraft = hasDisplayedDraftForDeletedSession\(\);/);
  assert.match(deleteBody, /if \(wasDisplayedSession \|\| wasDisplayedDraft\) \{/);
});

test('pending recovery only path does not silently continue past lifecycle boundary', () => {
  const mainSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
  const resolveBody = body(mainSource, 'async function resolvePendingBeforeBoundary(action, sessionId = null)');
  assert.doesNotMatch(
    resolveBody,
    /if \(pendingRecoveryItems\(pendingRecovery\)\.length && !hasActivePending\) \{[\s\S]*?return 'continue';[\s\S]*?\}/,
  );
  assert.match(resolveBody, /resolveLifecycleBoundary\(/);
});

test('inspector footer cancel action routes by derived editor state', () => {
  assert.match(mainSource, /inspectorFooter\?\.addEventListener\('click'/);
  const actionBody = body(inspectorFooterActionsSource, 'function handleInspectorFooterAction(action)');
  assert.match(actionBody, /deriveEditorState\(currentDraftWorkspace\(\), draftSelection\(\), selectedSavedAnnotation\(\)\)/);
  assert.match(actionBody, /if \(editorState === 'pendingTarget'\) clearSelection\(\);/);
  assert.match(actionBody, /else cancelAddItemsFlow\(\);/);
});

test('composer footer visibility is delegated to renderInspectorFooter', () => {
  const previewRegion = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/presentation/previewRegionView.js'), 'utf8');
  const composerBody = body(previewRegion, 'function renderComposerInspector()');
  const annotations = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
  const updateBody = body(annotations, 'function updateComposerState()');
  assert.doesNotMatch(composerBody, /clearDraftButton/);
  assert.match(updateBody, /renderInspectorFooter\(/);
});
