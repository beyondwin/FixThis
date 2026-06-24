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

const canonicalInstall = 'fixthis install-agent --project-dir . --target all --verify --json';
const canonicalDoctor = 'fixthis doctor --project-dir . --json';
const restartLine = 'Restart Claude Code or Codex';
const consoleTool = 'fixthis_open_feedback_console';
const actionContract = 'actions[]';
const readinessGate = 'readyForMcpTooling';

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
  assert.match(text, /actions\[\]/);
  assert.match(text, /readyForMcpTooling/);
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
  assert.match(snippet, /actions\[\]/);
  assert.match(snippet, /readyForMcpTooling/);
});

test('agent-facing docs keep release and artifact constraints visible', () => {
  const combined = [
    read('README.md'),
    read('docs/getting-started/agent-install-snippet.md'),
  ].join('\n');
  assert.match(combined, /Never add FixThis to release builds|Do not configure release builds/);
  assert.match(combined, /Do not commit `?\.fixthis\/?`?/);
});

test('agent setup schema separates handoff file and verify stdout report', () => {
  const text = read('docs/reference/agent-setup-schema.md');
  assert.match(text, /Setup handoff files/);
  assert.match(text, /\.fixthis\/agent-setup\.json/);
  assert.match(text, /Verify stdout report/);
  assert.match(text, /schemaVersion.*1\.1/);
  assert.match(text, /readyForMcpTooling/);
  assert.match(text, /actions\[\]/);
});
