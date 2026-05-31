# External Trust Matrix and Release Drift Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved umbrella for external Android fixture matrix evidence, Handoff Correctness Eval v2, and release source-of-truth drift detection.

**Architecture:** Extend the existing local evidence system rather than adding a parallel harness. Track C adds a pure release-drift checker and wires it into release readiness and release gate. Track A adds a small generated external-project fixture matrix with contract tests, non-connected setup validation, and strict connected deferral semantics. Track B upgrades the existing handoff eval corpus with explicit correctness dimensions and hard trust-failure reporting.

**Tech Stack:** Node.js 20 ESM scripts and `node:test`, Gradle/Kotlin tests for MCP handoff evaluation, JSON fixture manifests, existing `evidence-runner` and `release-gate` scripts, Android SDK/ADB only for strict connected evidence.

---

## File Structure

- Create `scripts/release-drift-guard.mjs`
  - Owns pure drift analysis, git/tag command adapters, changelog/unreleased parsing, release-readiness command validation, JSON/Markdown reports.
- Create `scripts/release-drift-guard-test.mjs`
  - Locks tag-distance drift, changelog/unreleased drift, missing command drift, strict/non-strict status mapping, and report rendering.
- Modify `scripts/check-release-readiness.mjs`
  - Adds a rule proving release drift guard is documented and package-exposed.
- Create `fixtures/external-project-matrix/manifest.json`
  - Declarative list of generated external project shapes.
- Create `scripts/external-fixture-matrix.mjs`
  - Owns matrix manifest validation, local fixture generation, setup command planning, non-connected execution, strict connected deferral, cleanup, and reports.
- Create `scripts/external-fixture-matrix-test.mjs`
  - Locks manifest schema, fixture generation, command planning, deferral taxonomy, report rendering, and cleanup behavior.
- Modify `scripts/evidence-runner.mjs`
  - Adds release drift and external fixture matrix steps to the right profiles.
- Modify `scripts/evidence-runner-test.mjs`
  - Locks profile command order and package script exposure.
- Modify `scripts/release-gate.mjs`
  - Adds release drift, external fixture matrix, and handoff correctness claims.
- Modify `scripts/release-gate-test.mjs`
  - Locks claim mapping and markdown rendering for the new evidence.
- Modify `package.json`
  - Adds `release:drift`, `release:drift:test`, `external-fixture:matrix`, `external-fixture:matrix:test`.
- Modify `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
  - Bumps corpus schema and adds correctness expectations.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`
  - Adds eval v2 metadata models and score helpers.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
  - Adds hard-failure tests for owner, role, confidence cap, caution, ranking, and prompt usability.
- Modify `docs/contributing/release-readiness.md`
  - Documents the new evidence manifest and commands.
- Modify `docs/releases/unreleased.md`
  - Records the unreleased validation surface after implementation lands.
- Modify `CONTRIBUTING.md`
  - Adds the new local verification commands to the contributor checklist.

## Task 1: Release Drift Guard Core

**Files:**
- Create: `scripts/release-drift-guard.mjs`
- Create: `scripts/release-drift-guard-test.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write failing drift guard tests**

Create `scripts/release-drift-guard-test.mjs`:

```js
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  analyzeReleaseDrift,
  buildReleaseDriftReport,
  commandTokensFromReleaseReadiness,
  releaseDriftStatus,
  renderReleaseDriftMarkdown,
  writeReleaseDriftReports,
} from './release-drift-guard.mjs';

const baseFiles = {
  'CHANGELOG.md': [
    '# Changelog',
    '',
    '## Unreleased',
    '',
    '- Added external matrix evidence.',
    '',
    '## v1.1.0 - 2026-05-31',
  ].join('\n'),
  'docs/releases/unreleased.md': [
    '# Unreleased changes',
    '',
    '## Highlights',
    '',
    '- Added external matrix evidence.',
  ].join('\n'),
  'docs/contributing/release-readiness.md': [
    '# Release Readiness',
    '',
    '| Claim | Required evidence |',
    '| --- | --- |',
    '| Matrix works. | `npm run external-fixture:matrix:test` and `npm run release:drift`. |',
  ].join('\n'),
  'package.json': JSON.stringify({
    scripts: {
      'external-fixture:matrix:test': 'node --test scripts/external-fixture-matrix-test.mjs',
      'release:drift': 'node scripts/release-drift-guard.mjs',
    },
  }, null, 2),
};

function analyze(overrides = {}) {
  const files = { ...baseFiles, ...(overrides.files || {}) };
  return analyzeReleaseDrift({
    files,
    git: {
      latestTag: overrides.latestTag ?? 'v1.1.0',
      commitsSinceTag: overrides.commitsSinceTag ?? ['abc1234 docs: add matrix'],
    },
    strict: overrides.strict ?? false,
  });
}

test('tag-distance drift is detected when unreleased notes still claim no changes', () => {
  const result = analyze({
    files: {
      'CHANGELOG.md': '# Changelog\n\n## Unreleased\n\nNo unreleased changes yet.\n',
      'docs/releases/unreleased.md': '# Unreleased changes\n\nNo unreleased changes have landed after v1.1.0 yet.\n',
    },
    commitsSinceTag: ['abc1234 feat: add evidence'],
  });

  assert.equal(result.findings[0].id, 'tag-distance-drift');
  assert.equal(result.findings[0].severity, 'warning');
  assert.match(result.findings[0].message, /1 commit exists after v1\.1\.0/);
});

test('strict mode fails tag-distance drift', () => {
  const result = analyze({
    strict: true,
    files: {
      'CHANGELOG.md': '# Changelog\n\n## Unreleased\n\nNo unreleased changes yet.\n',
      'docs/releases/unreleased.md': '# Unreleased changes\n\nNo unreleased changes have landed after v1.1.0 yet.\n',
    },
  });

  assert.equal(releaseDriftStatus(result.findings, true), 'fail');
});

test('changelog and unreleased notes disagreement is actionable', () => {
  const result = analyze({
    files: {
      'CHANGELOG.md': '# Changelog\n\n## Unreleased\n\n- Added drift guard.\n',
      'docs/releases/unreleased.md': '# Unreleased changes\n\nNo unreleased changes have landed after v1.1.0 yet.\n',
    },
  });

  assert.deepEqual(result.findings.map((finding) => finding.id), ['unreleased-notes-drift']);
  assert.match(result.findings[0].message, /CHANGELOG.md has unreleased content/);
});

test('release readiness command tokens must exist in package scripts or known binaries', () => {
  const tokens = commandTokensFromReleaseReadiness(baseFiles['docs/contributing/release-readiness.md']);
  assert.deepEqual(tokens.sort(), ['npm run external-fixture:matrix:test', 'npm run release:drift']);

  const result = analyze({
    files: {
      'docs/contributing/release-readiness.md': '| Claim | Required evidence |\n| --- | --- |\n| Bad. | `npm run missing:script`. |',
    },
  });

  assert.equal(result.findings[0].id, 'release-readiness-command-drift');
  assert.match(result.findings[0].message, /npm run missing:script/);
});

test('report normalizes warning and fail status', () => {
  const warning = buildReleaseDriftReport(analyze().findings, { strict: false, generatedAt: '2026-06-01T00:00:00.000Z' });
  assert.equal(warning.status, 'pass');

  const failing = buildReleaseDriftReport(analyze({ strict: true }).findings, { strict: true, generatedAt: '2026-06-01T00:00:00.000Z' });
  assert.equal(failing.status, 'pass');

  const drift = buildReleaseDriftReport(analyze({
    strict: true,
    files: {
      'CHANGELOG.md': '# Changelog\n\n## Unreleased\n\nNo unreleased changes yet.\n',
      'docs/releases/unreleased.md': '# Unreleased changes\n\nNo unreleased changes have landed after v1.1.0 yet.\n',
    },
  }).findings, { strict: true, generatedAt: '2026-06-01T00:00:00.000Z' });
  assert.equal(drift.status, 'fail');
});

test('markdown and json reports are written', () => {
  const dir = mkdtempSync(join(tmpdir(), 'fixthis-release-drift-'));
  try {
    const report = buildReleaseDriftReport(analyze().findings, { strict: false, generatedAt: '2026-06-01T00:00:00.000Z' });
    const paths = writeReleaseDriftReports(report, dir);
    assert.match(readFileSync(paths.json, 'utf8'), /"schemaVersion": "1.0"/);
    assert.match(readFileSync(paths.markdown, 'utf8'), /FixThis Release Drift Report/);
    assert.match(renderReleaseDriftMarkdown(report), /\| Status \| Strict \|/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
node --test scripts/release-drift-guard-test.mjs
```

Expected:

```text
Error [ERR_MODULE_NOT_FOUND]: Cannot find module
```

- [ ] **Step 3: Implement release drift guard**

Create `scripts/release-drift-guard.mjs`:

```js
#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), '..');
export const defaultReleaseDriftReportDir = join(repoRoot, 'build/reports/fixthis-release-drift');

const knownCommandPrefixes = [
  './gradlew',
  'bash ',
  'node ',
  'git ',
];

function readRepoFile(file, root = repoRoot) {
  const path = join(root, file);
  return existsSync(path) ? readFileSync(path, 'utf8') : null;
}

function latestSemverTag(root = repoRoot) {
  try {
    return execFileSync('git', ['describe', '--tags', '--abbrev=0', '--match', 'v[0-9]*'], {
      cwd: root,
      encoding: 'utf8',
    }).trim();
  } catch (_error) {
    return null;
  }
}

function commitsSince(tag, root = repoRoot) {
  if (!tag) return [];
  try {
    return execFileSync('git', ['log', '--oneline', `${tag}..HEAD`], {
      cwd: root,
      encoding: 'utf8',
    }).split('\n').filter(Boolean);
  } catch (_error) {
    return [];
  }
}

function sectionText(text, heading) {
  if (!text) return '';
  const start = text.match(new RegExp(`^##\\s+${heading.replace(/[.*+?^${}()|[\\]\\\\]/g, '\\\\$&')}\\s*$`, 'im'));
  if (!start) return '';
  const after = text.slice((start.index ?? 0) + start[0].length);
  const next = after.search(/^##\s+/m);
  return (next >= 0 ? after.slice(0, next) : after).trim();
}

function hasNoUnreleasedChanges(text) {
  return /No unreleased changes/i.test(text || '') || /No unreleased changes have landed/i.test(text || '');
}

function hasMeaningfulUnreleasedContent(text) {
  const stripped = String(text || '')
    .replace(/No unreleased changes[^\n]*/gi, '')
    .replace(/See\s+\[[^\]]+\]\([^)]+\)[^\n]*/gi, '')
    .trim();
  return /[-*]\s+\S/.test(stripped) || /###\s+\S/.test(stripped);
}

export function commandTokensFromReleaseReadiness(text) {
  const commands = [];
  const regex = /`([^`]+)`/g;
  let match;
  while ((match = regex.exec(text || '')) !== null) {
    const value = match[1].trim();
    if (
      value.startsWith('npm run ') ||
      value.startsWith('./gradlew') ||
      value.startsWith('node ') ||
      value.startsWith('bash ')
    ) {
      commands.push(value);
    }
  }
  return [...new Set(commands)];
}

function packageScripts(files) {
  try {
    return JSON.parse(files['package.json'] || '{}').scripts || {};
  } catch (_error) {
    return {};
  }
}

function npmScriptName(command) {
  const match = command.match(/^npm run ([^ ]+)/);
  return match?.[1] || null;
}

function commandExists(command, scripts) {
  const npmScript = npmScriptName(command);
  if (npmScript) return Object.prototype.hasOwnProperty.call(scripts, npmScript);
  return knownCommandPrefixes.some((prefix) => command.startsWith(prefix));
}

function finding(id, severity, message, files = []) {
  return { id, severity, message, files };
}

export function analyzeReleaseDrift({ files, git, strict = false } = {}) {
  const loadedFiles = files || {
    'CHANGELOG.md': readRepoFile('CHANGELOG.md'),
    'docs/releases/unreleased.md': readRepoFile('docs/releases/unreleased.md'),
    'docs/contributing/release-readiness.md': readRepoFile('docs/contributing/release-readiness.md'),
    'package.json': readRepoFile('package.json'),
  };
  const latestTag = git?.latestTag ?? latestSemverTag();
  const commits = git?.commitsSinceTag ?? commitsSince(latestTag);
  const changelogUnreleased = sectionText(loadedFiles['CHANGELOG.md'], 'Unreleased');
  const releaseNotes = loadedFiles['docs/releases/unreleased.md'] || '';
  const findings = [];

  if (!loadedFiles['CHANGELOG.md']) {
    findings.push(finding('missing-changelog', 'fail', 'CHANGELOG.md must exist.', ['CHANGELOG.md']));
  }
  if (!loadedFiles['docs/releases/unreleased.md']) {
    findings.push(finding('missing-unreleased-notes', 'fail', 'docs/releases/unreleased.md must exist.', ['docs/releases/unreleased.md']));
  }

  if (latestTag && commits.length > 0 && hasNoUnreleasedChanges(changelogUnreleased) && hasNoUnreleasedChanges(releaseNotes)) {
    findings.push(finding(
      'tag-distance-drift',
      strict ? 'fail' : 'warning',
      `${commits.length} commit exists after ${latestTag}, but CHANGELOG.md and docs/releases/unreleased.md still claim no unreleased changes.`,
      ['CHANGELOG.md', 'docs/releases/unreleased.md'],
    ));
  }

  if (hasMeaningfulUnreleasedContent(changelogUnreleased) && hasNoUnreleasedChanges(releaseNotes)) {
    findings.push(finding(
      'unreleased-notes-drift',
      strict ? 'fail' : 'warning',
      'CHANGELOG.md has unreleased content but docs/releases/unreleased.md still says no unreleased changes landed.',
      ['CHANGELOG.md', 'docs/releases/unreleased.md'],
    ));
  }

  const scripts = packageScripts(loadedFiles);
  const commandTokens = commandTokensFromReleaseReadiness(loadedFiles['docs/contributing/release-readiness.md']);
  const missingCommands = commandTokens.filter((command) => !commandExists(command, scripts));
  if (missingCommands.length > 0) {
    findings.push(finding(
      'release-readiness-command-drift',
      'fail',
      `Release readiness references missing command(s): ${missingCommands.join(', ')}`,
      ['docs/contributing/release-readiness.md', 'package.json'],
    ));
  }

  return {
    schemaVersion: '1.0',
    latestTag,
    commitsSinceTag: commits,
    strict,
    findings,
  };
}

export function releaseDriftStatus(findings, strict = false) {
  if ((findings || []).some((entry) => entry.severity === 'fail')) return 'fail';
  if (strict && (findings || []).some((entry) => entry.severity === 'warning')) return 'fail';
  return 'pass';
}

export function buildReleaseDriftReport(findings, { strict = false, generatedAt = new Date().toISOString() } = {}) {
  return {
    schemaVersion: '1.0',
    status: releaseDriftStatus(findings, strict),
    strict,
    generatedAt,
    findings: findings || [],
  };
}

function cell(value) {
  if (value === null || value === undefined || value === '') return '-';
  return String(value).replaceAll('|', '\\|');
}

export function renderReleaseDriftMarkdown(report) {
  const lines = [
    '# FixThis Release Drift Report',
    '',
    '| Status | Strict | Generated |',
    '| --- | --- | --- |',
    `| ${cell(report.status)} | ${cell(report.strict)} | ${cell(report.generatedAt)} |`,
    '',
    '## Findings',
    '',
    '| Id | Severity | Message | Files |',
    '| --- | --- | --- | --- |',
  ];
  if ((report.findings || []).length === 0) {
    lines.push('| - | pass | No release drift detected. | - |');
  } else {
    for (const item of report.findings || []) {
      lines.push(`| ${cell(item.id)} | ${cell(item.severity)} | ${cell(item.message)} | ${cell((item.files || []).join(', '))} |`);
    }
  }
  return `${lines.join('\n')}\n`;
}

export function writeReleaseDriftReports(report, reportDir = defaultReleaseDriftReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, 'report.json');
  const markdown = join(reportDir, 'report.md');
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderReleaseDriftMarkdown(report));
  return { json, markdown };
}

function parseArgs(argv) {
  const args = { strict: false };
  for (const arg of argv) {
    if (arg === '--strict') args.strict = true;
    else if (arg === '-h' || arg === '--help') {
      console.log('Usage: node scripts/release-drift-guard.mjs [--strict]');
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const analysis = analyzeReleaseDrift(args);
  const report = buildReleaseDriftReport(analysis.findings, args);
  const paths = writeReleaseDriftReports(report);
  console.log(`Release drift: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status === 'fail') process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main();
}
```

- [ ] **Step 4: Add package scripts**

Modify `package.json` scripts:

```json
"release:drift": "node scripts/release-drift-guard.mjs",
"release:drift:test": "node --test scripts/release-drift-guard-test.mjs",
```

Place them next to the existing `release:gate` scripts.

- [ ] **Step 5: Run focused tests**

Run:

```bash
npm run release:drift:test
```

Expected:

```text
# pass
```

- [ ] **Step 6: Commit**

```bash
git add package.json scripts/release-drift-guard.mjs scripts/release-drift-guard-test.mjs
git commit -m "test(release): add source drift guard"
```

## Task 2: Wire Release Drift Into Evidence And Release Gate

**Files:**
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`
- Modify: `scripts/check-release-readiness.mjs`

- [ ] **Step 1: Add failing evidence profile tests**

Append to `scripts/evidence-runner-test.mjs`:

```js
test("release and gate profiles include release drift guard", () => {
  const releaseCommands = expandProfile("release").map((step) => step.command);
  const gateCommands = expandProfile("gate").map((step) => step.command);

  assert.ok(releaseCommands.includes("npm run release:drift"));
  assert.ok(gateCommands.includes("npm run release:drift -- --strict"));
});

test("package exposes release drift commands", () => {
  const pkg = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(pkg.scripts["release:drift"], "node scripts/release-drift-guard.mjs");
  assert.equal(pkg.scripts["release:drift:test"], "node --test scripts/release-drift-guard-test.mjs");
});
```

- [ ] **Step 2: Add failing release gate claim tests**

In `scripts/release-gate-test.mjs`, update the first test name to `releaseGateSteps include Trust Loop source agent release drift and console evidence`, then add these assertions:

```js
assert.ok(commands.includes('npm run release:drift -- --strict'));
```

In `release gate report maps evidence steps to unlocked claims`, add a drift step:

```js
{ name: 'Release drift strict', command: 'npm run release:drift -- --strict', status: 'pass' },
```

Update the expected claims to include:

```js
{
  id: 'release-source-drift',
  status: 'pass',
  evidence: ['Release drift strict'],
},
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```bash
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
```

Expected:

```text
not ok
```

At least one failure should mention that `npm run release:drift -- --strict` is missing.

- [ ] **Step 4: Update evidence runner profiles**

In `scripts/evidence-runner.mjs`, add to `release` profile after `Release reality`:

```js
step("Release drift", "npm run release:drift"),
```

Add to `gate` profile after `Release reality`:

```js
step("Release drift strict", "npm run release:drift -- --strict"),
```

- [ ] **Step 5: Update release gate claims**

In `scripts/release-gate.mjs`, update `releaseClaimDefinitions`:

```js
export const releaseClaimDefinitions = Object.freeze([
  { id: 'release-reality', evidenceNames: ['Release reality'] },
  { id: 'release-source-drift', evidenceNames: ['Release drift strict'] },
  { id: 'external-agent-loop', evidenceNames: ['Agent loop smoke'] },
  { id: 'runtime-source-trust', evidenceNames: ['Runtime trust strict'] },
  { id: 'console-sse-reliability', evidenceNames: ['Console browser reliability'] },
]);
```

- [ ] **Step 6: Add release readiness rule**

In `scripts/check-release-readiness.mjs`, append after `R37.release-gate-command`:

```js
requireIncludes(
  'R38.release-drift-command',
  'docs/contributing/release-readiness.md',
  '`npm run release:drift`',
);
```

- [ ] **Step 7: Run focused tests**

Run:

```bash
npm run release:drift:test
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
```

Expected:

```text
# pass
```

`node scripts/check-release-readiness.mjs` is expected to fail until Task 6 updates docs.

- [ ] **Step 8: Commit**

```bash
git add scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs scripts/release-gate.mjs scripts/release-gate-test.mjs scripts/check-release-readiness.mjs
git commit -m "test(release): wire drift guard into gate"
```

## Task 3: External Fixture Matrix Contracts

**Files:**
- Create: `fixtures/external-project-matrix/manifest.json`
- Create: `scripts/external-fixture-matrix.mjs`
- Create: `scripts/external-fixture-matrix-test.mjs`
- Modify: `package.json`

- [ ] **Step 1: Add fixture matrix manifest**

Create `fixtures/external-project-matrix/manifest.json`:

```json
{
  "schemaVersion": 1,
  "fixtures": [
    {
      "id": "single-kotlin-dsl",
      "projectShape": "single-module",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.single",
      "variant": "debug",
      "expectedSetup": "ready"
    },
    {
      "id": "multi-module-non-root-app",
      "projectShape": "multi-module",
      "dsl": "kotlin",
      "appModule": ":features:demo-app",
      "moduleDir": "features/demo-app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.multimodule",
      "variant": "debug",
      "expectedSetup": "ready"
    },
    {
      "id": "flavored-application-id",
      "projectShape": "single-module-flavor",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.flavor.demo",
      "variant": "demoDebug",
      "expectedSetup": "ready"
    },
    {
      "id": "version-catalog",
      "projectShape": "version-catalog",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.catalog",
      "variant": "debug",
      "expectedSetup": "ready"
    },
    {
      "id": "preexisting-agent-config",
      "projectShape": "preexisting-agent-config",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.config",
      "variant": "debug",
      "expectedSetup": "ready"
    },
    {
      "id": "missing-generated-metadata",
      "projectShape": "missing-generated-metadata",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.recovery",
      "variant": "debug",
      "expectedSetup": "needs-fixthis-setup"
    }
  ]
}
```

- [ ] **Step 2: Write failing contract tests**

Create `scripts/external-fixture-matrix-test.mjs`:

```js
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  buildMatrixReport,
  defaultManifestPath,
  externalMatrixStatusForEnvironment,
  loadExternalMatrixManifest,
  planFixtureCommands,
  renderMatrixMarkdown,
  validateExternalMatrixManifest,
  writeMatrixReports,
} from './external-fixture-matrix.mjs';

test('manifest covers required external project shapes', () => {
  const manifest = loadExternalMatrixManifest(defaultManifestPath);
  const shapes = new Set(manifest.fixtures.map((fixture) => fixture.projectShape));

  assert.equal(manifest.schemaVersion, 1);
  assert.ok(shapes.has('single-module'));
  assert.ok(shapes.has('multi-module'));
  assert.ok(shapes.has('single-module-flavor'));
  assert.ok(shapes.has('version-catalog'));
  assert.ok(shapes.has('preexisting-agent-config'));
  assert.ok(shapes.has('missing-generated-metadata'));
});

test('manifest rejects unsafe ids modules and expected setup values', () => {
  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 1,
      fixtures: [{
        id: 'Bad Id',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: 'app',
        moduleDir: '../app',
        applicationId: 'bad',
        variant: 'debug',
        expectedSetup: 'ready',
      }],
    }),
    /fixture id must use lowercase slug syntax.*appModule must start with :.*moduleDir escapes fixture root/s,
  );

  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 1,
      fixtures: [{
        id: 'bad-setup',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: ':app',
        moduleDir: 'app',
        applicationId: 'io.example.bad',
        variant: 'debug',
        expectedSetup: 'unknown',
      }],
    }),
    /expectedSetup must be ready or needs-fixthis-setup/,
  );
});

test('planFixtureCommands uses install-agent doctor and source-index commands', () => {
  const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'single-kotlin-dsl');
  const commands = planFixtureCommands(fixture, '/tmp/fixthis-matrix/single-kotlin-dsl');

  assert.deepEqual(commands.map((entry) => entry.name), [
    'install-agent',
    'doctor-json',
    'source-index',
  ]);
  assert.equal(commands[0].command, 'node /repo/fixthis-cli/build/install/fixthis/bin/fixthis install-agent --project-dir /tmp/fixthis-matrix/single-kotlin-dsl --target all --package io.github.beyondwin.fixthis.matrix.single');
  assert.equal(commands[1].command, 'node /repo/fixthis-cli/build/install/fixthis/bin/fixthis doctor --project-dir /tmp/fixthis-matrix/single-kotlin-dsl --package io.github.beyondwin.fixthis.matrix.single --json');
  assert.equal(commands[2].command, './gradlew :app:generateDebugFixThisSourceIndex --no-daemon');
});

test('environment status distinguishes non-strict deferral and strict failure', () => {
  assert.deepEqual(externalMatrixStatusForEnvironment({ strict: false, androidReady: false, reason: 'Android SDK unavailable' }), {
    status: 'deferred',
    exitCode: 0,
    reason: 'Android SDK unavailable',
  });
  assert.deepEqual(externalMatrixStatusForEnvironment({ strict: true, androidReady: false, reason: 'Android SDK unavailable' }), {
    status: 'fail',
    exitCode: 1,
    reason: 'Android SDK unavailable',
  });
});

test('matrix report and markdown include fixture command statuses', () => {
  const report = buildMatrixReport({
    strict: false,
    generatedAt: '2026-06-01T00:00:00.000Z',
    fixtures: [{
      fixtureId: 'single-kotlin-dsl',
      status: 'pass',
      commands: [{ name: 'doctor-json', command: 'fixthis doctor --json', status: 'pass', durationMs: 5 }],
    }],
  });

  assert.equal(report.status, 'pass');
  assert.match(renderMatrixMarkdown(report), /\| single-kotlin-dsl \| pass \|/);
  assert.match(renderMatrixMarkdown(report), /fixthis doctor --json/);
});

test('writeMatrixReports writes json and markdown reports', () => {
  const dir = mkdtempSync(join(tmpdir(), 'fixthis-external-matrix-'));
  try {
    const paths = writeMatrixReports(buildMatrixReport({ fixtures: [] }), dir);
    assert.match(readFileSync(paths.json, 'utf8'), /"schemaVersion": "1.0"/);
    assert.match(readFileSync(paths.markdown, 'utf8'), /External Fixture Matrix Report/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```bash
node --test scripts/external-fixture-matrix-test.mjs
```

Expected:

```text
Error [ERR_MODULE_NOT_FOUND]: Cannot find module
```

- [ ] **Step 4: Implement manifest validation and reporting**

Create `scripts/external-fixture-matrix.mjs` with this first implementation:

```js
#!/usr/bin/env node
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, normalize, resolve, sep } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { resolveAndroidEnvironment } from './evidence-runner.mjs';

const scriptPath = fileURLToPath(import.meta.url);
export const repoRoot = resolve(dirname(scriptPath), '..');
export const defaultManifestPath = join(repoRoot, 'fixtures/external-project-matrix/manifest.json');
export const defaultMatrixReportDir = join(repoRoot, 'build/reports/fixthis-external-fixture-matrix');
export const defaultMatrixWorkRoot = join(repoRoot, '.fixthis/external-fixture-matrix');

const allowedShapes = new Set([
  'single-module',
  'multi-module',
  'single-module-flavor',
  'version-catalog',
  'preexisting-agent-config',
  'missing-generated-metadata',
]);
const allowedExpectedSetup = new Set(['ready', 'needs-fixthis-setup']);

function safeRelativePath(value, fieldName = 'path') {
  if (typeof value !== 'string' || value.length === 0) throw new Error(`${fieldName} must be a non-empty string`);
  if (value.startsWith('/') || /^[A-Za-z]:[\\/]/.test(value)) throw new Error(`${fieldName} must be relative`);
  const normalized = normalize(value);
  if (normalized === '..' || normalized.startsWith(`..${sep}`) || normalized.includes(`${sep}..${sep}`)) {
    throw new Error(`${fieldName} escapes fixture root`);
  }
  return normalized.replaceAll('\\', '/');
}

function variantTaskSuffix(variant) {
  return variant.charAt(0).toUpperCase() + variant.slice(1);
}

export function validateExternalMatrixManifest(manifest) {
  const errors = [];
  if (!manifest || typeof manifest !== 'object') errors.push('manifest must be an object');
  if (manifest?.schemaVersion !== 1) errors.push('schemaVersion must be 1');
  if (!Array.isArray(manifest?.fixtures) || manifest.fixtures.length === 0) errors.push('fixtures must contain at least one fixture');
  for (const fixture of manifest?.fixtures || []) {
    const label = fixture.id || 'fixture';
    if (!/^[a-z0-9-]+$/.test(fixture.id || '')) errors.push('fixture id must use lowercase slug syntax');
    if (!allowedShapes.has(fixture.projectShape)) errors.push(`${label} projectShape is unsupported`);
    if (fixture.dsl !== 'kotlin') errors.push(`${label} dsl must be kotlin`);
    if (!String(fixture.appModule || '').startsWith(':')) errors.push(`${label} appModule must start with :`);
    try { safeRelativePath(fixture.moduleDir, `${label} moduleDir`); } catch (error) { errors.push(error.message); }
    if (!/^[-A-Za-z0-9_.]+$/.test(fixture.applicationId || '')) errors.push(`${label} applicationId must be package-like`);
    if (!fixture.variant) errors.push(`${label} variant must be present`);
    if (!allowedExpectedSetup.has(fixture.expectedSetup)) errors.push(`${label} expectedSetup must be ready or needs-fixthis-setup`);
  }
  if (errors.length) throw new Error(errors.join('; '));
  return manifest;
}

export function loadExternalMatrixManifest(path = defaultManifestPath) {
  return validateExternalMatrixManifest(JSON.parse(readFileSync(path, 'utf8')));
}

export function planFixtureCommands(fixture, projectDir, root = '/repo') {
  const cli = `${root}/fixthis-cli/build/install/fixthis/bin/fixthis`;
  return [
    {
      name: 'install-agent',
      command: `node ${cli} install-agent --project-dir ${projectDir} --target all --package ${fixture.applicationId}`,
    },
    {
      name: 'doctor-json',
      command: `node ${cli} doctor --project-dir ${projectDir} --package ${fixture.applicationId} --json`,
    },
    {
      name: 'source-index',
      command: `./gradlew ${fixture.appModule}:generate${variantTaskSuffix(fixture.variant)}FixThisSourceIndex --no-daemon`,
    },
  ];
}

export function externalMatrixStatusForEnvironment({ strict = false, androidReady = false, reason = 'Android SDK or ready emulator is unavailable.' } = {}) {
  if (androidReady) return { status: 'pass', exitCode: 0, reason: null };
  return strict
    ? { status: 'fail', exitCode: 1, reason }
    : { status: 'deferred', exitCode: 0, reason };
}

function reportStatus(fixtures, strict = false) {
  if ((fixtures || []).some((fixture) => fixture.status === 'fail')) return 'fail';
  if (strict && (fixtures || []).some((fixture) => fixture.status === 'deferred')) return 'fail';
  if ((fixtures || []).some((fixture) => fixture.status === 'deferred')) return 'pass_with_deferred';
  return 'pass';
}

export function buildMatrixReport({ strict = false, generatedAt = new Date().toISOString(), fixtures = [] } = {}) {
  return {
    schemaVersion: '1.0',
    status: reportStatus(fixtures, strict),
    strict,
    generatedAt,
    fixtures,
  };
}

function cell(value) {
  if (value === null || value === undefined || value === '') return '-';
  return String(value).replaceAll('|', '\\|');
}

export function renderMatrixMarkdown(report) {
  const lines = [
    '# FixThis External Fixture Matrix Report',
    '',
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    '',
    '| Fixture | Status | Reason |',
    '| --- | --- | --- |',
  ];
  for (const fixture of report.fixtures || []) {
    lines.push(`| ${cell(fixture.fixtureId)} | ${cell(fixture.status)} | ${cell(fixture.reason)} |`);
  }
  lines.push('', '## Commands', '', '| Fixture | Step | Status | Command | Duration |', '| --- | --- | --- | --- | --- |');
  for (const fixture of report.fixtures || []) {
    for (const command of fixture.commands || []) {
      lines.push(`| ${cell(fixture.fixtureId)} | ${cell(command.name)} | ${cell(command.status)} | \`${cell(command.command)}\` | ${cell(command.durationMs ?? 0)}ms |`);
    }
  }
  return `${lines.join('\n')}\n`;
}

export function writeMatrixReports(report, reportDir = defaultMatrixReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, 'report.json');
  const markdown = join(reportDir, 'report.md');
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderMatrixMarkdown(report));
  return { json, markdown };
}

function parseArgs(argv) {
  const args = { strict: false, dryRun: false };
  for (const arg of argv) {
    if (arg === '--strict') args.strict = true;
    else if (arg === '--dry-run') args.dryRun = true;
    else if (arg === '-h' || arg === '--help') {
      console.log('Usage: node scripts/external-fixture-matrix.mjs [--strict] [--dry-run]');
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const manifest = loadExternalMatrixManifest();
  const android = resolveAndroidEnvironment();
  const fixtures = manifest.fixtures.map((fixture) => {
    const projectDir = join(defaultMatrixWorkRoot, fixture.id);
    const commands = planFixtureCommands(fixture, projectDir, repoRoot).map((entry) => ({
      ...entry,
      status: args.dryRun ? 'planned' : 'deferred',
      durationMs: 0,
    }));
    const environment = externalMatrixStatusForEnvironment({ strict: args.strict, androidReady: android.ready, reason: android.reason });
    return {
      fixtureId: fixture.id,
      status: args.dryRun ? 'planned' : environment.status,
      reason: args.dryRun ? null : environment.reason,
      commands,
    };
  });
  const report = buildMatrixReport({ strict: args.strict, fixtures });
  const paths = writeMatrixReports(report);
  console.log(`External fixture matrix: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status === 'fail') process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main();
}
```

- [ ] **Step 5: Add package scripts**

Modify `package.json` scripts:

```json
"external-fixture:matrix": "node scripts/external-fixture-matrix.mjs",
"external-fixture:matrix:test": "node --test scripts/external-fixture-matrix-test.mjs",
```

Place them near the other fixture and agent-loop scripts.

- [ ] **Step 6: Run focused tests**

Run:

```bash
npm run external-fixture:matrix:test
```

Expected:

```text
# pass
```

- [ ] **Step 7: Commit**

```bash
git add package.json fixtures/external-project-matrix/manifest.json scripts/external-fixture-matrix.mjs scripts/external-fixture-matrix-test.mjs
git commit -m "test(fixtures): define external project matrix"
```

## Task 4: External Matrix Setup Runner And Gate Wiring

**Files:**
- Modify: `scripts/external-fixture-matrix.mjs`
- Modify: `scripts/external-fixture-matrix-test.mjs`
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`

- [ ] **Step 1: Add failing runner tests**

Append to `scripts/external-fixture-matrix-test.mjs`:

```js
import { existsSync } from 'node:fs';
import {
  generateFixtureProject,
  runExternalMatrix,
} from './external-fixture-matrix.mjs';

test('generateFixtureProject creates a minimal Gradle Compose project shape', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-matrix-generate-'));
  try {
    const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'multi-module-non-root-app');
    const projectDir = join(root, fixture.id);
    generateFixtureProject(fixture, projectDir);

    assert.equal(existsSync(join(projectDir, 'settings.gradle.kts')), true);
    assert.equal(existsSync(join(projectDir, 'features/demo-app/build.gradle.kts')), true);
    assert.match(readFileSync(join(projectDir, 'features/demo-app/src/main/AndroidManifest.xml'), 'utf8'), /io\.github\.beyondwin\.fixthis\.matrix\.multimodule/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('runExternalMatrix executes non-connected commands with injected runner', () => {
  const calls = [];
  const manifest = {
    schemaVersion: 1,
    fixtures: [loadExternalMatrixManifest(defaultManifestPath).fixtures[0]],
  };
  const report = runExternalMatrix({
    manifest,
    strict: false,
    workRoot: '/tmp/fixthis-matrix',
    androidEnvironment: { ready: true, reason: null, envPatch: {} },
    root: '/repo',
    runCommandFn: (command) => {
      calls.push(command);
      return { status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 };
    },
    generateFixtureProjectFn: () => {},
    cleanupFixtureFn: () => {},
  });

  assert.equal(report.status, 'pass');
  assert.equal(report.fixtures[0].status, 'pass');
  assert.equal(calls.length, 3);
});

test('runExternalMatrix keeps deferred fixtures out of command execution when Android is missing', () => {
  const calls = [];
  const manifest = {
    schemaVersion: 1,
    fixtures: [loadExternalMatrixManifest(defaultManifestPath).fixtures[0]],
  };
  const report = runExternalMatrix({
    manifest,
    strict: false,
    workRoot: '/tmp/fixthis-matrix',
    androidEnvironment: { ready: false, reason: 'Android SDK unavailable', envPatch: {} },
    runCommandFn: (command) => {
      calls.push(command);
      return { status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 };
    },
    generateFixtureProjectFn: () => {},
    cleanupFixtureFn: () => {},
  });

  assert.equal(report.status, 'pass_with_deferred');
  assert.equal(report.fixtures[0].status, 'deferred');
  assert.equal(calls.length, 0);
});
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
npm run external-fixture:matrix:test
```

Expected:

```text
not ok
```

The failure should mention missing `generateFixtureProject` or `runExternalMatrix`.

- [ ] **Step 3: Implement fixture generation and runner**

In `scripts/external-fixture-matrix.mjs`, add imports:

```js
import { rmSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
```

Add these functions before `parseArgs`:

```js
function runCommand(command, cwd, envPatch = {}) {
  const started = Date.now();
  const result = spawnSync('bash', ['-lc', command], {
    cwd,
    encoding: 'utf8',
    env: { ...process.env, ...envPatch },
    stdio: 'pipe',
  });
  return {
    status: result.status === 0 ? 'pass' : 'fail',
    durationMs: Date.now() - started,
    stdout: result.stdout,
    stderr: result.stderr,
    exitCode: result.status ?? 1,
  };
}

function write(path, text) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text);
}

function settingsFor(fixture) {
  if (fixture.projectShape === 'multi-module') {
    return 'pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name = "FixThisMatrixMulti"\ninclude(":features:demo-app")\n';
  }
  return 'pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name = "FixThisMatrix"\ninclude(":app")\n';
}

function appBuildGradleFor(fixture) {
  const flavorBlock = fixture.projectShape === 'single-module-flavor'
    ? '    flavorDimensions += "env"\n    productFlavors { create("demo") { dimension = "env"; applicationIdSuffix = ".demo" } }\n'
    : '';
  return [
    'plugins {',
    '    id("com.android.application")',
    '    id("org.jetbrains.kotlin.android")',
    '    id("io.github.beyondwin.fixthis.compose")',
    '}',
    '',
    'android {',
    '    namespace = "' + fixture.applicationId.replace(/\.demo$/, '') + '"',
    '    compileSdk = 34',
    '    defaultConfig { applicationId = "' + fixture.applicationId.replace(/\.demo$/, '') + '"; minSdk = 23; targetSdk = 34; versionCode = 1; versionName = "1.0" }',
    flavorBlock.trimEnd(),
    '}',
    '',
    'dependencies {',
    '    implementation("androidx.activity:activity-compose:1.10.0")',
    '    implementation(platform("androidx.compose:compose-bom:2025.01.01"))',
    '    implementation("androidx.compose.material3:material3")',
    '}',
    '',
  ].filter(Boolean).join('\n');
}

export function generateFixtureProject(fixture, projectDir) {
  rmSync(projectDir, { recursive: true, force: true });
  mkdirSync(projectDir, { recursive: true });
  write(join(projectDir, 'settings.gradle.kts'), settingsFor(fixture));
  write(join(projectDir, 'build.gradle.kts'), 'plugins { id("com.android.application") version "8.7.3" apply false; id("org.jetbrains.kotlin.android") version "2.0.21" apply false; id("io.github.beyondwin.fixthis.compose") version "1.1.0" apply false }\n');
  const moduleDir = join(projectDir, fixture.moduleDir);
  write(join(moduleDir, 'build.gradle.kts'), appBuildGradleFor(fixture));
  write(join(moduleDir, 'src/main/AndroidManifest.xml'), '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:theme="@style/AppTheme" /></manifest>\n');
  write(join(moduleDir, 'src/main/java/io/github/beyondwin/fixthis/matrix/MainActivity.kt'), 'package io.github.beyondwin.fixthis.matrix\n\nimport android.app.Activity\n\nclass MainActivity : Activity()\n');
  if (fixture.projectShape === 'preexisting-agent-config') {
    write(join(projectDir, '.claude/settings.json'), '{ "mcpServers": {} }\n');
    write(join(projectDir, '.cursor/mcp.json'), '{ "mcpServers": {} }\n');
  }
}

function cleanupFixture(projectDir) {
  rmSync(projectDir, { recursive: true, force: true });
}

export function runExternalMatrix({
  manifest = loadExternalMatrixManifest(),
  strict = false,
  workRoot = defaultMatrixWorkRoot,
  root = repoRoot,
  androidEnvironment = resolveAndroidEnvironment(),
  runCommandFn = (command, cwd, envPatch) => runCommand(command, cwd, envPatch),
  generateFixtureProjectFn = generateFixtureProject,
  cleanupFixtureFn = cleanupFixture,
} = {}) {
  const fixtures = [];
  for (const fixture of manifest.fixtures) {
    const projectDir = join(workRoot, fixture.id);
    const environment = externalMatrixStatusForEnvironment({
      strict,
      androidReady: androidEnvironment.ready,
      reason: androidEnvironment.reason,
    });
    if (!androidEnvironment.ready) {
      fixtures.push({
        fixtureId: fixture.id,
        status: environment.status,
        reason: environment.reason,
        commands: planFixtureCommands(fixture, projectDir, root).map((entry) => ({ ...entry, status: 'deferred', durationMs: 0 })),
      });
      continue;
    }
    generateFixtureProjectFn(fixture, projectDir);
    const commands = [];
    let status = 'pass';
    let reason = null;
    for (const entry of planFixtureCommands(fixture, projectDir, root)) {
      const result = runCommandFn(entry.command, projectDir, androidEnvironment.envPatch);
      commands.push({ ...entry, ...result });
      if (result.status === 'fail') {
        status = 'fail';
        reason = result.stderr?.split('\n').find(Boolean) || result.stdout?.split('\n').find(Boolean) || `${entry.name} failed`;
        break;
      }
    }
    cleanupFixtureFn(projectDir);
    fixtures.push({ fixtureId: fixture.id, status, reason, commands });
  }
  return buildMatrixReport({ strict, fixtures });
}
```

Replace the body of `main()` after `const args = parseArgs(...)`:

```js
const manifest = loadExternalMatrixManifest();
const report = args.dryRun
  ? buildMatrixReport({
      strict: args.strict,
      fixtures: manifest.fixtures.map((fixture) => ({
        fixtureId: fixture.id,
        status: 'planned',
        commands: planFixtureCommands(fixture, join(defaultMatrixWorkRoot, fixture.id), repoRoot).map((entry) => ({ ...entry, status: 'planned', durationMs: 0 })),
      })),
    })
  : runExternalMatrix({ manifest, strict: args.strict });
const paths = writeMatrixReports(report);
console.log(`External fixture matrix: ${report.status}`);
console.log(`JSON: ${paths.json}`);
console.log(`Markdown: ${paths.markdown}`);
if (report.status === 'fail') process.exit(1);
```

- [ ] **Step 4: Add evidence and release gate tests**

Append to `scripts/evidence-runner-test.mjs`:

```js
test("trust and gate profiles include external fixture matrix", () => {
  const trustCommands = expandProfile("trust").map((step) => step.command);
  const gateCommands = expandProfile("gate").map((step) => step.command);

  assert.ok(trustCommands.includes("npm run external-fixture:matrix:test"));
  assert.ok(gateCommands.includes("npm run external-fixture:matrix -- --strict"));
});
```

Append to `scripts/release-gate-test.mjs`:

```js
test('release gate maps external fixture matrix claim', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-01T00:00:00.000Z',
    steps: [
      { name: 'External fixture matrix strict', command: 'npm run external-fixture:matrix -- --strict', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'external-fixture-matrix'), {
    id: 'external-fixture-matrix',
    status: 'deferred',
    evidence: ['External fixture matrix strict'],
    reason: 'Android SDK unavailable',
  });
});
```

- [ ] **Step 5: Wire evidence runner and release gate**

In `scripts/evidence-runner.mjs`, add to `trust` profile before runtime strict:

```js
step("External fixture matrix contracts", "npm run external-fixture:matrix:test"),
step("External fixture matrix strict", "npm run external-fixture:matrix -- --strict", {
  deferrable: true,
  requiresAndroid: true,
}),
```

Add to `gate` profile after `Agent loop smoke contracts`:

```js
step("External fixture matrix contracts", "npm run external-fixture:matrix:test"),
step("External fixture matrix strict", "npm run external-fixture:matrix -- --strict", {
  deferrable: true,
  requiresAndroid: true,
}),
```

In `scripts/release-gate.mjs`, add the claim after `external-agent-loop`:

```js
{ id: 'external-fixture-matrix', evidenceNames: ['External fixture matrix strict'] },
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
npm run external-fixture:matrix:test
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
npm run evidence:trust -- --dry-run
```

Expected:

```text
# pass
DRY RUN profile=trust
```

- [ ] **Step 7: Commit**

```bash
git add scripts/external-fixture-matrix.mjs scripts/external-fixture-matrix-test.mjs scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs scripts/release-gate.mjs scripts/release-gate-test.mjs
git commit -m "feat(fixtures): add external matrix runner"
```

## Task 5: Handoff Correctness Eval v2

**Files:**
- Modify: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
- Modify: `scripts/evidence-runner-test.mjs`
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`

- [ ] **Step 1: Add failing eval v2 tests**

In `HandoffEvaluationCorpusTest.kt`, add these imports:

```kotlin
import kotlin.test.assertFalse
```

Add tests before the private `renderToken()` helper:

```kotlin
    @Test
    fun corpusV2DefinesCorrectnessDimensionsForEveryCase() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()

        assertEquals(2, corpus.schemaVersion)
        corpus.cases.forEach { case ->
            assertTrue(case.correctness.ownerContains.isNotBlank(), "${case.id} owner expectation missing")
            assertEquals(case.expectedRole, case.correctness.expectedRole, "${case.id} role expectation must match existing gate")
            assertTrue(case.correctness.maxConfidence.isNotBlank(), "${case.id} max confidence missing")
            assertTrue(case.correctness.promptUsabilityRequired, "${case.id} must require prompt usability")
        }
    }

    @Test
    fun corpusV2HardFailuresCatchTrustBreakingRegressions() {
        val failures = HandoffEvaluationFixtures.loadCorpus().cases.flatMap { case ->
            HandoffEvaluationFixtures.evaluateCorrectness(case).hardFailures
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n") { it.message })
    }

    @Test
    fun corpusV2CoversRequiredRiskCategories() {
        val categories = HandoffEvaluationFixtures.loadCorpus().cases.map { it.correctness.category }.toSet()

        assertTrue("shared-component" in categories)
        assertTrue("interop-risk" in categories)
        assertTrue("visual-area" in categories)
        assertTrue("weak-or-ambiguous-source" in categories)
        assertTrue("lazy-list-owner" in categories)
        assertTrue("navigation-destination-owner" in categories)
    }

    @Test
    fun compactPromptUsabilityIncludesProtocolSessionAndItemIds() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()
        val items = corpus.cases.map { HandoffEvaluationFixtures.annotationFor(it) }
        val session = SessionDto(
            sessionId = "handoff-v2",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = corpus.cases.map { HandoffEvaluationFixtures.screenFor(it) },
            items = items.mapIndexed { index, item -> item.copy(sequenceNumber = index + 1) },
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("session_id: handoff-v2"), markdown)
        assertTrue(markdown.contains("agent_protocol: fixthis-feedback/v1"), markdown)
        corpus.cases.forEach { case ->
            assertTrue(markdown.contains("id: item-${case.id}"), "Missing item id for ${case.id}")
        }
    }

    @Test
    fun correctnessReportSeparatesScoreFromHardFailures() {
        val case = HandoffEvaluationFixtures.loadCorpus().cases.single { it.id == "ambiguous-repeated-text" }
        val result = HandoffEvaluationFixtures.evaluateCorrectness(case)

        assertTrue(result.score in 0..100)
        assertTrue(result.dimensions.any { it.name == "confidence" })
        assertFalse(result.hardFailures.any { it.id == "overconfident-evidence" })
    }
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*HandoffEvaluationCorpusTest" --no-daemon
```

Expected:

```text
Compilation failed
```

The missing names should include `correctness`, `evaluateCorrectness`, or `schemaVersion` mismatch.

- [ ] **Step 3: Update corpus schema and expectations**

Modify `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`:

1. Change `"schemaVersion": 1` to `"schemaVersion": 2`.
2. Add a `correctness` object to every case.
3. Add two cases for lazy-list and navigation owner coverage.

Use these `correctness` objects:

```json
"correctness": {
  "category": "copy-or-data",
  "ownerContains": "CheckoutScreen.kt",
  "expectedRole": "COPY_OR_DATA",
  "maxConfidence": "HIGH",
  "requiredCautions": [],
  "releaseCritical": true,
  "promptUsabilityRequired": true
}
```

```json
"correctness": {
  "category": "shared-component",
  "ownerContains": "MetricCard.kt",
  "expectedRole": "COMPONENT_DEFINITION",
  "maxConfidence": "HIGH",
  "requiredCautions": [],
  "releaseCritical": true,
  "promptUsabilityRequired": true
}
```

```json
"correctness": {
  "category": "layout-or-style",
  "ownerContains": "QueueScreen.kt",
  "expectedRole": "LAYOUT_OR_STYLE",
  "maxConfidence": "MEDIUM",
  "requiredCautions": [],
  "releaseCritical": true,
  "promptUsabilityRequired": true
}
```

```json
"correctness": {
  "category": "visual-area",
  "ownerContains": "visual-area",
  "expectedRole": "VISUAL_AREA",
  "maxConfidence": "LOW",
  "requiredCautions": ["targetBoundary=visual-area"],
  "releaseCritical": true,
  "promptUsabilityRequired": true
}
```

```json
"correctness": {
  "category": "interop-risk",
  "ownerContains": "NativeChartHost.kt",
  "expectedRole": "INTEROP_RISK",
  "maxConfidence": "MEDIUM",
  "requiredCautions": ["targetBoundary=interop-risk"],
  "releaseCritical": true,
  "promptUsabilityRequired": true
}
```

```json
"correctness": {
  "category": "weak-or-ambiguous-source",
  "ownerContains": "HomeScreen.kt",
  "expectedRole": "COPY_OR_DATA",
  "maxConfidence": "MEDIUM",
  "requiredCautions": [],
  "releaseCritical": true,
  "promptUsabilityRequired": true
}
```

Add lazy-list case:

```json
{
  "id": "lazy-list-owner",
  "comment": "Make this list item title larger",
  "targetType": "node",
  "selectedText": ["Featured product"],
  "selectedTestTag": "comp:ProductRow:title",
  "sourceCandidates": [
    {
      "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ProductListScreen.kt",
      "line": 88,
      "score": 81.0,
      "confidence": "MEDIUM",
      "matchReasons": ["lazy item owner"],
      "matchedTerms": ["Featured product"],
      "ownerComposable": "ProductRow"
    }
  ],
  "expectedRole": "LAYOUT_OR_STYLE",
  "expectedTop3Contains": "ProductListScreen.kt",
  "allowHighConfidence": false,
  "correctness": {
    "category": "lazy-list-owner",
    "ownerContains": "ProductListScreen.kt",
    "expectedRole": "LAYOUT_OR_STYLE",
    "maxConfidence": "MEDIUM",
    "requiredCautions": [],
    "releaseCritical": true,
    "promptUsabilityRequired": true
  }
}
```

Add navigation case:

```json
{
  "id": "navigation-destination-owner",
  "comment": "Rename this destination heading",
  "targetType": "node",
  "selectedText": ["Settings"],
  "selectedTestTag": "screen:Settings:heading",
  "sourceCandidates": [
    {
      "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/navigation/AppNavGraph.kt",
      "line": 54,
      "score": 84.0,
      "confidence": "MEDIUM",
      "matchReasons": ["navigation destination owner"],
      "matchedTerms": ["Settings"],
      "ownerComposable": "SettingsRoute"
    }
  ],
  "expectedRole": "COPY_OR_DATA",
  "expectedTop3Contains": "AppNavGraph.kt",
  "allowHighConfidence": false,
  "correctness": {
    "category": "navigation-destination-owner",
    "ownerContains": "AppNavGraph.kt",
    "expectedRole": "COPY_OR_DATA",
    "maxConfidence": "MEDIUM",
    "requiredCautions": [],
    "releaseCritical": true,
    "promptUsabilityRequired": true
  }
}
```

- [ ] **Step 4: Implement eval v2 models and scoring**

In `HandoffEvaluationFixtures.kt`, add data classes after `HandoffEvaluationCase`:

```kotlin
@Serializable
internal data class HandoffCorrectnessExpectation(
    val category: String,
    val ownerContains: String,
    val expectedRole: EditSurfaceRoleDto,
    val maxConfidence: String,
    val requiredCautions: List<String> = emptyList(),
    val releaseCritical: Boolean,
    val promptUsabilityRequired: Boolean,
)

internal data class HandoffCorrectnessResult(
    val score: Int,
    val dimensions: List<HandoffCorrectnessDimension>,
    val hardFailures: List<HandoffCorrectnessFailure>,
)

internal data class HandoffCorrectnessDimension(
    val name: String,
    val passed: Boolean,
    val message: String,
)

internal data class HandoffCorrectnessFailure(
    val id: String,
    val message: String,
)
```

Add a property to `HandoffEvaluationCase`:

```kotlin
val correctness: HandoffCorrectnessExpectation,
```

Add helper functions inside `HandoffEvaluationFixtures`:

```kotlin
    fun evaluateCorrectness(case: HandoffEvaluationCase): HandoffCorrectnessResult {
        val item = annotationFor(case)
        val session = SessionDto(
            sessionId = "handoff-eval-${case.id}",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(screenFor(case)),
            items = listOf(item.copy(sequenceNumber = 1)),
        )
        val compact = CompactHandoffRenderer.render(session)
        val topRole = item.editSurfaceCandidates.firstOrNull()?.role
        val topConfidence = item.editSurfaceCandidates.firstOrNull()?.confidence
        val candidateFiles = (item.editSurfaceCandidates.map { it.file } + item.sourceCandidates.map { it.file })
        val maxAllowed = SelectionConfidence.valueOf(case.correctness.maxConfidence)
        val dimensions = listOf(
            HandoffCorrectnessDimension(
                name = "owner",
                passed = candidateFiles.any { it.contains(case.correctness.ownerContains) } ||
                    compact.contains(case.correctness.ownerContains) ||
                    case.correctness.ownerContains == "visual-area",
                message = "expected owner containing ${case.correctness.ownerContains}",
            ),
            HandoffCorrectnessDimension(
                name = "role",
                passed = topRole == case.correctness.expectedRole,
                message = "expected role ${case.correctness.expectedRole}, got $topRole",
            ),
            HandoffCorrectnessDimension(
                name = "confidence",
                passed = topConfidence == null || topConfidence.ordinal <= maxAllowed.ordinal,
                message = "expected confidence <= $maxAllowed, got $topConfidence",
            ),
            HandoffCorrectnessDimension(
                name = "caution",
                passed = case.correctness.requiredCautions.all { compact.contains(it) },
                message = "expected caution tokens ${case.correctness.requiredCautions}",
            ),
            HandoffCorrectnessDimension(
                name = "prompt-usability",
                passed = !case.correctness.promptUsabilityRequired ||
                    (compact.contains("session_id:") &&
                        compact.contains("id: item-${case.id}") &&
                        compact.contains("agent_protocol: fixthis-feedback/v1")),
                message = "expected compact handoff protocol fields",
            ),
        )
        val hardFailures = dimensions
            .filter { !it.passed && case.correctness.releaseCritical }
            .map { dimension ->
                HandoffCorrectnessFailure(
                    id = when (dimension.name) {
                        "confidence" -> "overconfident-evidence"
                        "caution" -> "missing-required-caution"
                        "owner" -> "missing-expected-owner"
                        "role" -> "wrong-edit-surface-role"
                        else -> "missing-prompt-usability"
                    },
                    message = "${case.id}: ${dimension.message}",
                )
            }
        val score = (dimensions.count { it.passed } * 100) / dimensions.size
        return HandoffCorrectnessResult(score, dimensions, hardFailures)
    }
```

- [ ] **Step 5: Update stable coverage list**

In `corpusHasStableV06Coverage`, update the expected case id list to include the new cases:

```kotlin
listOf(
    "button-copy-call-site",
    "reusable-card-color",
    "spacing-layout",
    "visual-area-gap",
    "interop-risk",
    "interop-risk-with-compose-host",
    "ambiguous-repeated-text",
    "lazy-list-owner",
    "navigation-destination-owner",
)
```

- [ ] **Step 6: Wire release gate claim**

In `scripts/release-gate.mjs`, add:

```js
{ id: 'handoff-correctness-v2', evidenceNames: ['Handoff evaluation'] },
```

In `scripts/release-gate-test.mjs`, add a report step to the claim mapping test:

```js
{ name: 'Handoff evaluation', command: 'npm run handoff:eval:test', status: 'pass' },
```

Add the expected claim:

```js
{
  id: 'handoff-correctness-v2',
  status: 'pass',
  evidence: ['Handoff evaluation'],
},
```

- [ ] **Step 7: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*HandoffEvaluationCorpusTest" --no-daemon
node --test scripts/release-gate-test.mjs
npm run handoff:eval:test
```

Expected:

```text
BUILD SUCCESSFUL
# pass
BUILD SUCCESSFUL
```

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt scripts/release-gate.mjs scripts/release-gate-test.mjs
git commit -m "test(handoff): score correctness eval v2"
```

## Task 6: Docs, Readiness, Final Gate, And Graph Update

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/releases/unreleased.md`
- Modify: `CONTRIBUTING.md`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `package.json`
- Generated but not committed: `graphify-out/`

- [ ] **Step 1: Add release-readiness manifest**

In `docs/contributing/release-readiness.md`, add this section before `Required Before Next Source Release`:

```markdown
## External Trust Matrix And Release Drift Evidence

This umbrella may be claimed only when each area below has matching local
evidence from the release commit. The evidence pack does not tag or publish by
itself.

| Claim | Required evidence |
| --- | --- |
| External Android project shapes complete setup validation and, when Android runtime prerequisites are available, strict lifecycle validation. | `npm run external-fixture:matrix:test` and `npm run external-fixture:matrix -- --strict`. |
| Handoff correctness evaluation covers owner, role, confidence, caution, ranking, and prompt usability without exact-ownership overclaiming. | `npm run handoff:eval:test`. |
| Release metadata does not drift from tag distance, changelog, unreleased notes, readiness claims, and package command evidence. | `npm run release:drift`, `npm run release:drift:test`, and `npm run release:gate`. |

When Android SDK or an unlocked emulator is unavailable, non-strict reports must
record the exact deferred reason and strict connected evidence must fail.
```

- [ ] **Step 2: Add readiness rules for the new manifest**

In `scripts/check-release-readiness.mjs`, append after `R38.release-drift-command`:

```js
requireIncludes(
  'R39.external-trust-matrix-section',
  'docs/contributing/release-readiness.md',
  '## External Trust Matrix And Release Drift Evidence',
);
requireIncludes(
  'R40.external-fixture-matrix-command',
  'docs/contributing/release-readiness.md',
  '`npm run external-fixture:matrix -- --strict`',
);
requireIncludes(
  'R41.handoff-correctness-command',
  'docs/contributing/release-readiness.md',
  '`npm run handoff:eval:test`',
);
```

- [ ] **Step 3: Update unreleased notes**

In `docs/releases/unreleased.md`, replace the Highlights text that says no changes landed with:

```markdown
## Highlights

- Added the External Trust Matrix and Release Drift evidence line: external
  project matrix contracts, handoff correctness scoring, and release drift
  checks now feed the local release gate.
```

In the Validation Surface command block, add:

```bash
npm run release:drift
npm run release:drift:test
npm run external-fixture:matrix:test
```

In the connected Android block, add:

```bash
npm run external-fixture:matrix -- --strict
```

- [ ] **Step 4: Update contributor checklist**

In `CONTRIBUTING.md`, find the local evidence or release gate command list near the existing `npm run release:gate` references and add:

```bash
npm run release:drift
npm run release:drift:test
npm run external-fixture:matrix:test
```

In the connected runtime evidence section, add:

```bash
npm run external-fixture:matrix -- --strict
```

- [ ] **Step 5: Keep package script exposure in the matrix test**

Do not modify `scripts/source-matching-fixtures-test.mjs` for external matrix script exposure. The package script assertions added in `scripts/external-fixture-matrix-test.mjs` are the single owner for these commands:

```js
assert.equal(pkg.scripts["external-fixture:matrix"], "node scripts/external-fixture-matrix.mjs");
assert.equal(pkg.scripts["external-fixture:matrix:test"], "node --test scripts/external-fixture-matrix-test.mjs");
```

- [ ] **Step 6: Run local verification**

Run:

```bash
npm run release:drift:test
npm run external-fixture:matrix:test
./gradlew :fixthis-mcp:test --tests "*HandoffEvaluationCorpusTest" --no-daemon
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected:

```text
# pass
# pass
BUILD SUCCESSFUL
# pass
All release-readiness rules pass.
All doc-consistency rules pass.
```

- [ ] **Step 7: Run release gate dry checks**

Run:

```bash
npm run evidence:trust -- --dry-run
npm run evidence:release -- --dry-run
npm run release:gate:test
```

Expected:

```text
DRY RUN profile=trust
DRY RUN profile=release
# pass
```

- [ ] **Step 8: Update graph**

Run:

```bash
graphify update .
```

Expected:

```text
Graphify update completed successfully.
```

Any dirty `graphify-out/` files are expected local graph artifacts and must not be staged.

- [ ] **Step 9: Commit docs and final wiring**

Run:

```bash
git status --short
git add docs/contributing/release-readiness.md docs/releases/unreleased.md CONTRIBUTING.md scripts/check-release-readiness.mjs package.json
git commit -m "docs(release): document external trust evidence"
```

- [ ] **Step 10: Final status check**

Run:

```bash
git status --short
```

Expected:

```text
```

Only ignored/local artifacts such as `.fixthis/`, `build/`, or `graphify-out/` may remain outside git status.

## Self-Review

- Spec coverage: Track A maps to Tasks 3-4, Track B maps to Task 5, Track C maps to Tasks 1-2, and final release/docs integration maps to Task 6.
- Compatibility: All persisted MCP/session JSON changes remain additive; Track B changes only test corpus metadata and test-only eval helpers.
- Scope: No release-build support, XML/View exact source targeting, WebView DOM inspection, new package channels, cloud calls, or automatic app edits are introduced.
- Verification: Each task has focused RED/GREEN commands and a commit. Final verification includes doc consistency, release readiness, release gate tests, handoff eval, external matrix contracts, and graph update.
