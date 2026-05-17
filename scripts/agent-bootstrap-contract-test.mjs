import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(new URL('..', import.meta.url).pathname);

function read(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

function indexOfCommand(text, command) {
  return text.indexOf(command);
}

const canonicalInstall = 'fixthis install-agent --project-dir . --target all';
const canonicalDoctor = 'fixthis doctor --project-dir . --json';
const restartLine = 'Restart Claude Code or Codex';
const consoleTool = 'fixthis_open_feedback_console';

function assertCanonicalOrder(file, text) {
  const installIndex = indexOfCommand(text, canonicalInstall);
  const doctorIndex = indexOfCommand(text, canonicalDoctor);
  assert.notEqual(installIndex, -1, `${file} must contain ${canonicalInstall}`);
  assert.notEqual(doctorIndex, -1, `${file} must contain ${canonicalDoctor}`);
  assert.ok(
    installIndex < doctorIndex,
    `${file} must put install-agent before doctor --json`,
  );
}

test('README exposes the canonical Claude/Codex bootstrap path', () => {
  const text = read('README.md');
  assert.match(text, /Claude Code[\s\S]*Codex/);
  assertCanonicalOrder('README.md', text);
  assert.match(text, new RegExp(restartLine));
  assert.match(text, new RegExp(consoleTool));
  assert.match(text, /Do not commit `?\.fixthis\/?`?/);
  assert.match(text, /debug-only/i);
});

test('agent install snippet matches README command order', () => {
  const readme = read('README.md');
  const snippet = read('docs/getting-started/agent-install-snippet.md');
  assertCanonicalOrder('docs/getting-started/agent-install-snippet.md', snippet);
  for (const command of [canonicalInstall, canonicalDoctor]) {
    assert.ok(readme.includes(command), `README missing ${command}`);
    assert.ok(snippet.includes(command), `snippet missing ${command}`);
  }
  assert.match(snippet, new RegExp(restartLine));
  assert.match(snippet, new RegExp(consoleTool));
});

test('agent-facing docs keep release and artifact constraints visible', () => {
  const combined = [
    read('README.md'),
    read('docs/getting-started/agent-install-snippet.md'),
  ].join('\n');
  assert.match(combined, /Never add FixThis to release builds|Do not configure release builds/);
  assert.match(combined, /Do not commit `?\.fixthis\/?`?/);
});
