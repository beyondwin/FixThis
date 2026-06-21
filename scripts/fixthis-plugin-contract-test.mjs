import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const pluginRoot = '.codex-plugin';
const requiredSkills = [
  'fixthis-install-agent',
  'fixthis-feedback-loop',
  'fixthis-android-evidence',
  'fixthis-release-smoke',
];

function read(path) {
  return readFileSync(path, 'utf8');
}

test('plugin manifest names FixThis and lists workflow skills', () => {
  const manifest = JSON.parse(read(join(pluginRoot, 'plugin.json')));
  assert.equal(manifest.name, 'fixthis');
  assert.equal(manifest.display_name, 'FixThis');
  assert.equal(manifest.version, '0.1.0');
  assert.deepEqual(manifest.skills.map((skill) => skill.name).sort(), requiredSkills.toSorted());
});

test('skills preserve debug-only and no .fixthis commit safety language', () => {
  for (const skill of requiredSkills) {
    const body = read(join(pluginRoot, 'skills', skill, 'SKILL.md'));
    assert.match(body, /debug-only/i, `${skill} must mention debug-only`);
    assert.match(body, /Do not commit `.fixthis\/`/, `${skill} must mention .fixthis safety`);
  }
});

test('feedback loop skill requires claim before editing and resolve after work', () => {
  const body = read(join(pluginRoot, 'skills', 'fixthis-feedback-loop', 'SKILL.md'));
  assert.match(body, /fixthis_claim_feedback/);
  assert.match(body, /before making code changes/);
  assert.match(body, /fixthis_resolve_feedback/);
});
