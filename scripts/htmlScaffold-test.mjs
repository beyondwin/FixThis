import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const html = readFileSync('fixthis-mcp/src/main/resources/console/index.html', 'utf8');

test('console HTML exposes dynamic editor/footer/toast scaffolds', () => {
  assert.match(html, /id="inspectorFooter"/);
  assert.match(html, /id="editorBack"/);
  assert.match(html, /id="toastContainer"/);
  assert.doesNotMatch(html, /id="clearSelectionButton"/);
  assert.doesNotMatch(html, /id="cancelAddFlowButton"/);
  assert.doesNotMatch(html, /id="addItemButton"/);
  assert.doesNotMatch(html, /id="clearDraftButton"/);
});
