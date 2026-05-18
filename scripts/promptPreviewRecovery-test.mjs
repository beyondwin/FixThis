import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const promptSource = readFileSync(
  resolve(root, 'fixthis-mcp/src/main/console/prompt.js'),
  'utf8',
);

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  let i = source.indexOf('(', start);
  let parenDepth = 0;
  for (; i < source.length; i += 1) {
    if (source[i] === '(') parenDepth += 1;
    if (source[i] === ')') {
      parenDepth -= 1;
      if (parenDepth === 0) {
        i += 1;
        break;
      }
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

test('prompt persistence restores live preview so the next Annotate click can freeze a screen', () => {
  const restore = body(promptSource, 'async function restoreLivePreviewAfterPromptPersistence()');

  assert.match(restore, /startLivePreviewPolling\(\)/);
  assert.match(restore, /await refreshPreview\(\)/);
  assert.match(restore, /draftFlow\(\)/);
});

test('Copy Prompt and Save to MCP both restore preview after clearing draft state', () => {
  const copyPrompt = body(promptSource, 'async function copyPrompt()');
  const sendAgentPrompt = body(promptSource, 'async function sendAgentPrompt()');

  assert.match(copyPrompt, /await restoreLivePreviewAfterPromptPersistence\(\);/);
  assert.match(sendAgentPrompt, /await restoreLivePreviewAfterPromptPersistence\(\);/);
});
