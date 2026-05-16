import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Spec S1.4.2 / Task 1 (Design §2): when `copyTextToClipboard` rejects inside
// `copyPrompt`, the caller MUST route the failure through NotificationCenter
// (severity:'warning', surface:'banner', dedupeKey:'clipboard_fallback') and
// must NOT fall back to legacy `showError` for that path.

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const promptSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/prompt.js'),
  'utf8',
);

function extractFunction(source, signaturePrefix) {
  const start = source.indexOf(signaturePrefix);
  assert.notEqual(start, -1, `${signaturePrefix} not found in source`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  assert.fail(`${signaturePrefix} body did not close`);
}

const copyPromptSource = extractFunction(promptSource, 'async function copyPrompt(');

test('copyPrompt routes clipboard failures through notificationCenter.notify with clipboard_fallback payload', () => {
  // Notification payload — spec verbatim.
  assert.match(
    copyPromptSource,
    /notificationCenter\.notify\s*\(\s*\{[\s\S]*?dedupeKey:\s*['"]clipboard_fallback['"]/,
    'copyPrompt must notify with dedupeKey "clipboard_fallback"',
  );
  assert.match(
    copyPromptSource,
    /notificationCenter\.notify\s*\(\s*\{[\s\S]*?severity:\s*['"]warning['"]/,
    'clipboard fallback notification must use severity "warning"',
  );
  assert.match(
    copyPromptSource,
    /notificationCenter\.notify\s*\(\s*\{[\s\S]*?surface:\s*['"]banner['"]/,
    'clipboard fallback notification must use surface "banner"',
  );
  assert.match(
    copyPromptSource,
    /notificationCenter\.notify\s*\(\s*\{[\s\S]*?title:\s*['"]Copy failed['"]/,
    'clipboard fallback notification must have title "Copy failed"',
  );
  assert.match(
    copyPromptSource,
    /notificationCenter\.notify\s*\(\s*\{[\s\S]*?detail:\s*['"][^'"]*manually[^'"]*['"]/,
    'clipboard fallback notification detail must instruct the user to copy manually',
  );
});

test('copyPrompt does NOT call legacy showError for the clipboard failure path', () => {
  // copyTextToClipboard must NOT have showError chained as the catch handler
  // anywhere (i.e. `copyTextToClipboard(...).catch(showError)` is forbidden).
  assert.doesNotMatch(
    promptSource,
    /copyTextToClipboard\([^)]*\)\s*\.catch\s*\(\s*showError\s*\)/,
    'copyTextToClipboard.catch(showError) must be replaced with notificationCenter.notify',
  );
  // Within copyPrompt itself, showError must not be invoked (no call, no
  // .catch handler reference). The clipboard path uses notificationCenter,
  // and non-clipboard failures propagate to the outer main.js .catch(showError).
  assert.doesNotMatch(
    copyPromptSource,
    /showError\s*[(),]/,
    'copyPrompt must not call showError or pass it as a handler',
  );
});

test('copyPrompt short-circuits after a clipboard failure (does not mark items handed off)', () => {
  // After notify, the function must return/exit so `copied = true`,
  // `markItemsHandedOff`, and the success label are skipped. We enforce this
  // structurally: the notify call must be followed by `return` before the
  // next `await markItemsHandedOff` line.
  const notifyIdx = copyPromptSource.search(/notificationCenter\.notify\s*\(/);
  assert.notEqual(notifyIdx, -1, 'notificationCenter.notify call site not found');
  const markIdx = copyPromptSource.indexOf('markItemsHandedOff');
  assert.notEqual(markIdx, -1, 'markItemsHandedOff call site not found');
  assert.ok(
    notifyIdx < markIdx,
    'notify must appear before markItemsHandedOff in copyPrompt source',
  );
  const between = copyPromptSource.slice(notifyIdx, markIdx);
  assert.match(
    between,
    /\breturn\b/,
    'copyPrompt must `return` after notifying clipboard fallback so handoff/success steps are skipped',
  );
});
