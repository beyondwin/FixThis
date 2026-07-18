import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import {
  RELEASE_MAINTAINER_COMMANDS,
  REPOSITORY_ONLY_COMMAND_PREFIXES,
} from './agent-route-registry.mjs';

const pluginRoot = '.codex-plugin';
const requiredSkills = [
  'fixthis-install-agent',
  'fixthis-feedback-loop',
  'fixthis-android-evidence',
  'fixthis-release-smoke',
];
const externalAppSkills = [
  'fixthis-install-agent',
  'fixthis-feedback-loop',
  'fixthis-android-evidence',
];

function read(path) {
  return readFileSync(path, 'utf8');
}

test('plugin manifest names FixThis and lists workflow skills', () => {
  const manifest = JSON.parse(read(join(pluginRoot, 'plugin.json')));
  assert.equal(manifest.name, 'fixthis');
  assert.equal(manifest.display_name, 'FixThis');
  assert.equal(manifest.version, '0.1.1');
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

test('release smoke declares checkout audience and canonical commands', () => {
  const body = read(
    join(pluginRoot, 'skills', 'fixthis-release-smoke', 'SKILL.md'),
  );
  assert.match(body, /FixThis source checkout/);
  for (const command of RELEASE_MAINTAINER_COMMANDS) {
    assert.ok(body.includes(command), 'release smoke missing ' + command);
  }
});

test('external app skills exclude repository-only gates', () => {
  for (const skill of externalAppSkills) {
    const body = read(join(pluginRoot, 'skills', skill, 'SKILL.md'));
    for (const command of REPOSITORY_ONLY_COMMAND_PREFIXES) {
      assert.ok(!body.includes(command), `${skill} contains repository-only gate ${command}`);
    }
  }
});

test('external app policy blocks all repository gates without blocking valid workflows', () => {
  const valid = [
    'fixthis install-agent --project-dir . --target all',
    'fixthis doctor --project-dir . --json',
    './gradlew fixthisSetup',
  ].join('\n');
  for (const command of REPOSITORY_ONLY_COMMAND_PREFIXES) {
    assert.ok(!valid.includes(command), 'valid external workflow blocked by ' + command);
  }
  for (const command of [
    'npm run release:reality',
    'npm run evidence:release',
    'npm run release:check',
    'npm run android:proof -- --strict',
    'npm run agent:route',
  ]) {
    assert.ok(
      REPOSITORY_ONLY_COMMAND_PREFIXES.some((prefix) => command.startsWith(prefix)),
      'repository-only gate is not covered: ' + command,
    );
  }
});
