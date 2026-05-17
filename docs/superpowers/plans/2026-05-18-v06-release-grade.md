# v0.6 Release Grade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## Change History

- 2026-05-18 — Audit vs source (`scripts/check-release-readiness.mjs`, `scripts/verify-ci-local-test.mjs`, `docs/contributing/release-readiness.md`, `docs/reference/{mcp-tools,output-schema}.md`):
  - Task 2 Step 4 — append the new `R22.v06-release-claim-manifest` / `R23.v06-release-evidence-command` rules at the tail of `check-release-readiness.mjs` (the existing file currently ends at `R20`/`R21`). The phrase "after `R2d.v05-first-run-smoke`" is misleading; literal placement next to `R2d` would put new rules in the middle of the R3…R21 sequence.
  - Task 4 Step 2 — `scripts/verify-ci-local-test.mjs` uses `assert.deepEqual` on the `--fast` command list (lines 32–42). The asserted list is already stale (missing `docs:agent-bootstrap:test`, `first-run:smoke:test`, `detekt:baseline:check`, `checks:observation:test`, `perf:test`) and `npm run ci:local:test` is currently failing on `main`; the task must reconcile both that drift and insert `"npm run release:v06:evidence:test"` right after `"node scripts/check-release-readiness.mjs"`.
  - Confirmed `docs/contributing/release-readiness.md` already has `## v0.5 Trustworthy Onboarding Claim` (line 49); `docs/releases/unreleased.md`, `docs/contributing/release-process.md`, `docs/reference/mcp-tools.md` (all four `fixthis_*` tool names), and `docs/reference/output-schema.md` (all seven protected fields) already exist.

**Goal:** Make every major v0.6 release claim traceable to executable evidence before release notes can claim it.

**Architecture:** Extend the existing release-readiness checker instead of creating a parallel release system. Add a v0.6 claim manifest, deterministic docs/schema/MCP drift checks, observation evidence policy, and artifact integrity evidence requirements.

**Tech Stack:** Node.js scripts, Markdown docs, existing `check-release-readiness.mjs`, existing `checks:observation` command, existing package and docs verification scripts.

---

## Scope Check

This plan implements Track C from `docs/superpowers/specs/2026-05-18-v06-umbrella-roadmap-design.md`.

It does not add handoff intelligence, change Studio behavior, alter Android runtime code, or promote connected/nightly workflows to PR-required checks. Track A and Track B have separate plans.

## File Structure

### Create

- `scripts/v06-release-claims-test.mjs`
  - Low-cost test for v0.6 release-claim manifest structure.
- `scripts/check-v06-release-evidence.mjs`
  - Deterministic checker that validates v0.6 evidence commands and protected doc references.
- `docs/contributing/v06-release-evidence-template.md`
  - Pasteable release issue evidence template for v0.6.

### Modify

- `docs/contributing/release-readiness.md`
  - Add v0.6 release claim manifest and required evidence.
- `docs/contributing/release-process.md`
  - Add v0.6 evidence capture step before release notes finalization.
- `docs/releases/unreleased.md`
  - Add a v0.6 claim note that must stay cautious until evidence is captured.
- `scripts/check-release-readiness.mjs`
  - Add v0.6 claim manifest and evidence command rules.
- `docs/reference/mcp-tools.md`
  - Keep MCP tool reference aligned with v0.6 handoff caution language.
- `docs/reference/output-schema.md`
  - Keep protected persisted JSON field names visible to the evidence checker.
- `package.json`
  - Add `release:v06:evidence:test`.
- `scripts/verify-ci-local.sh`
  - Add the cheap v0.6 evidence test after release-readiness checks.

## Task 1: Add The v0.6 Release Claim Manifest

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Create: `scripts/v06-release-claims-test.mjs`
- Modify: `package.json`

- [ ] **Step 1: Add a failing manifest test**

Create `scripts/v06-release-claims-test.mjs`:

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function read(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

test('release readiness defines v0.6 claim manifest and evidence commands', () => {
  const text = read('docs/contributing/release-readiness.md');
  assert.match(text, /## v0\.6 Release Claim Manifest/);
  assert.match(text, /Handoff Intelligence/);
  assert.match(text, /Studio Reliability/);
  assert.match(text, /Release Grade/);
  assert.match(text, /npm run handoff:eval:test/);
  assert.match(text, /npm run console:reliability:test/);
  assert.match(text, /npm run release:v06:evidence:test/);
  assert.match(text, /npm run checks:observation -- --json/);
});

test('v0.6 manifest requires narrowing claims when evidence is missing', () => {
  const text = read('docs/contributing/release-readiness.md');
  assert.match(text, /If evidence is missing, narrow or remove the corresponding v0\.6 release note claim\./);
});
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
node --test scripts/v06-release-claims-test.mjs
```

Expected: FAIL because the v0.6 manifest does not exist yet.

- [ ] **Step 3: Add the manifest**

In `docs/contributing/release-readiness.md`, add this section immediately after `## v0.5 Trustworthy Onboarding Claim`:

```markdown
## v0.6 Release Claim Manifest

v0.6 may claim the umbrella roadmap only when the release issue includes
evidence for each claim below. If evidence is missing, narrow or remove the
corresponding v0.6 release note claim.

| Claim | Required evidence |
| --- | --- |
| Handoff Intelligence improves measured edit-surface usefulness. | `npm run handoff:eval:test` and the Track A corpus summary. |
| Studio Reliability preserves sessions, drafts, saves, recovery, stale-preview, and blocked-device state under repeated use. | `npm run console:reliability:test`, `npm run console:session:test`, `npm run console:draft:test`, and the reliability harness result. |
| Release Grade keeps docs, CLI, MCP, output schema, artifacts, and release notes aligned. | `npm run release:v06:evidence:test`, `node scripts/check-release-readiness.mjs`, `bash scripts/check-docs-cli-surface.sh`, and `npm run checks:observation -- --json`. |

Required v0.6 evidence before tagging:

- `npm run handoff:eval:test`
- `npm run console:reliability:test`
- `npm run release:v06:evidence:test`
- `node scripts/check-release-readiness.mjs`
- `bash scripts/check-docs-cli-surface.sh`
- `npm run checks:observation -- --json`
```

- [ ] **Step 4: Add the npm script**

In `package.json`, add:

```json
"release:v06:claims:test": "node --test scripts/v06-release-claims-test.mjs"
```

Place it near the other release scripts.

- [ ] **Step 5: Run the test and verify it passes**

Run:

```bash
npm run release:v06:claims:test
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add docs/contributing/release-readiness.md scripts/v06-release-claims-test.mjs package.json
git commit -m "docs: add v0.6 release claim manifest"
```

## Task 2: Add The v0.6 Evidence Checker

**Files:**
- Create: `scripts/check-v06-release-evidence.mjs`
- Modify: `package.json`
- Modify: `scripts/check-release-readiness.mjs`

- [ ] **Step 1: Create the checker**

Create `scripts/check-v06-release-evidence.mjs`:

```js
#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const root = process.cwd();
const failures = [];

function read(file) {
  return fs.readFileSync(path.join(root, file), 'utf8');
}

function requireIncludes(rule, file, needle) {
  const text = read(file);
  if (!text.includes(needle)) {
    failures.push(`FAIL ${rule}: ${file} must include ${JSON.stringify(needle)}`);
  } else {
    console.log(`PASS ${rule}`);
  }
}

function requireRegex(rule, file, regex, description) {
  const text = read(file);
  if (!regex.test(text)) {
    failures.push(`FAIL ${rule}: ${file} must include ${description}`);
  } else {
    console.log(`PASS ${rule}`);
  }
}

requireIncludes(
  'V06.R1.handoff-eval-script',
  'package.json',
  '"handoff:eval:test"',
);
requireIncludes(
  'V06.R2.console-reliability-script',
  'package.json',
  '"console:reliability:test"',
);
requireIncludes(
  'V06.R3.release-evidence-script',
  'package.json',
  '"release:v06:evidence:test"',
);
requireIncludes(
  'V06.R4.protected-output-fields',
  'docs/reference/output-schema.md',
  'sourceCandidates',
);
requireIncludes(
  'V06.R5.mcp-tools-reference',
  'docs/reference/mcp-tools.md',
  'fixthis_read_feedback',
);
requireRegex(
  'V06.R6.release-readiness-claim-table',
  'docs/contributing/release-readiness.md',
  /Handoff Intelligence[\s\S]*Studio Reliability[\s\S]*Release Grade/,
  'v0.6 claim table in Handoff -> Studio -> Release order',
);
requireRegex(
  'V06.R7.observation-policy',
  'docs/contributing/release-readiness.md',
  /checks:observation -- --json[\s\S]*If evidence is missing, narrow or remove/,
  'observation evidence and claim narrowing policy',
);
requireRegex(
  'V06.R8.unreleased-caution',
  'docs/releases/unreleased.md',
  /v0\.6[\s\S]*(evidence|claim|release)/i,
  'a v0.6 unreleased note with evidence or claim wording',
);

if (failures.length > 0) {
  for (const failure of failures) console.error(failure);
  process.exit(1);
}

console.log('\nAll v0.6 release evidence rules pass.');
```

- [ ] **Step 2: Add npm script**

In `package.json`, add:

```json
"release:v06:evidence:test": "node scripts/check-v06-release-evidence.mjs && npm run release:v06:claims:test"
```

- [ ] **Step 3: Run and verify failure**

Run:

```bash
npm run release:v06:evidence:test
```

Expected: FAIL until `docs/releases/unreleased.md` and the Track A/B scripts exist.

- [ ] **Step 4: Add release-readiness delegation rule**

In `scripts/check-release-readiness.mjs`, append the new rules at the tail of
the file (the current tail rules are `R20.github-namespace-paths` and the
loop that emits `R21.no-stale-prepublication-claims:<file>`). Do not place
them next to `R2d.v05-first-run-smoke` — that would split the existing
R3…R21 sequence:

```js
requireIncludes(
  'R22.v06-release-claim-manifest',
  'docs/contributing/release-readiness.md',
  '## v0.6 Release Claim Manifest',
);
requireIncludes(
  'R23.v06-release-evidence-command',
  'docs/contributing/release-readiness.md',
  '`npm run release:v06:evidence:test`',
);
```

- [ ] **Step 5: Run focused checks**

Run:

```bash
node scripts/check-release-readiness.mjs
npm run release:v06:evidence:test
```

Expected: `check-release-readiness.mjs` PASS. `release:v06:evidence:test` remains FAIL until Task 3 adds the unreleased note and Track A/B scripts are present.

- [ ] **Step 6: Commit the checker**

Run:

```bash
git add scripts/check-v06-release-evidence.mjs package.json scripts/check-release-readiness.mjs
git commit -m "test: add v0.6 release evidence checker"
```

## Task 3: Add Release Evidence Template And Unreleased Guard

**Files:**
- Create: `docs/contributing/v06-release-evidence-template.md`
- Modify: `docs/releases/unreleased.md`
- Modify: `docs/contributing/release-process.md`

- [ ] **Step 1: Add evidence template**

Create `docs/contributing/v06-release-evidence-template.md`:

```markdown
# v0.6 Release Evidence Template

Paste this into the v0.6 release issue before tagging.

## Handoff Intelligence

- [ ] `npm run handoff:eval:test`
- [ ] Corpus summary attached:
  - total cases:
  - high-confidence wrong edit surfaces:
  - top-3 source candidate coverage:
  - prompt budget result:

## Studio Reliability

- [ ] `npm run console:reliability:test`
- [ ] `npm run console:session:test`
- [ ] `npm run console:draft:test`
- [ ] `npm run console:preview:test`
- [ ] `node scripts/console-harness.mjs reliability`

## Release Grade

- [ ] `npm run release:v06:evidence:test`
- [ ] `node scripts/check-release-readiness.mjs`
- [ ] `bash scripts/check-docs-cli-surface.sh`
- [ ] `npm run checks:observation -- --json`

## Observation Results

Paste the JSON from:

```bash
npm run checks:observation -- --json
```

If connected tests or nightly compatibility are not ready, either narrow the
release note claim or record the explicit acceptance here.

## Artifact Integrity

- [ ] GitHub CLI/MCP tarball exists.
- [ ] SHA-256 sidecar exists.
- [ ] npm wrapper points at the intended release.
- [ ] Homebrew formula points at the intended release.
- [ ] Gradle plugin and Maven coordinates match README.
- [ ] MCP Registry metadata matches `server.json`.
- [ ] Release/minify consumer fixture passed or deferral is recorded.
```

- [ ] **Step 2: Add unreleased caution**

In `docs/releases/unreleased.md`, add under `## Highlights`:

```markdown
- v0.6 planning is tracked as an evidence-gated umbrella milestone. Do not
  claim Handoff Intelligence, Studio Reliability, or Release Grade completion
  in release notes until the v0.6 evidence commands in
  `docs/contributing/release-readiness.md` have passed.
```

- [ ] **Step 3: Add release process step**

In `docs/contributing/release-process.md`, add a step before release notes finalization:

```markdown
For a v0.6 release, copy
[`v06-release-evidence-template.md`](v06-release-evidence-template.md) into the
release issue and fill every command result before finalizing release notes.
Claims without evidence are removed or narrowed before tagging.
```

- [ ] **Step 4: Run evidence checker**

Run:

```bash
npm run release:v06:evidence:test
```

Expected: PASS once Track A and Track B scripts exist in `package.json`.

- [ ] **Step 5: Commit**

Run:

```bash
git add docs/contributing/v06-release-evidence-template.md \
  docs/releases/unreleased.md \
  docs/contributing/release-process.md
git commit -m "docs: add v0.6 release evidence template"
```

## Task 4: Wire The Cheap v0.6 Gate Into Local Verification

**Files:**
- Modify: `scripts/verify-ci-local.sh`
- Modify: `scripts/verify-ci-local-test.mjs`

- [ ] **Step 1: Add local verification step**

In `scripts/verify-ci-local.sh`, after:

```bash
run_step "node scripts/check-release-readiness.mjs"
```

add:

```bash
run_step "npm run release:v06:evidence:test"
```

- [ ] **Step 2: Update script contract test**

In `scripts/verify-ci-local-test.mjs`, the `--fast` test asserts the expected
command list with `assert.deepEqual` (lines 32–42). As of audit, the asserted
list is already stale — it is missing five commands the script currently
emits (`docs:agent-bootstrap:test`, `first-run:smoke:test`,
`detekt:baseline:check`, `checks:observation:test`, `perf:test`), so
`npm run ci:local:test` is failing on `main`. This task should fix that
drift while inserting the new entry:

```js
// inside the --fast test (currently lines 32-42)
assert.deepEqual(commands, [
  "node scripts/check-doc-consistency.mjs",
  "node scripts/check-release-readiness.mjs",
  "npm run release:v06:evidence:test",
  "npm run docs:agent-bootstrap:test",
  "npm run first-run:smoke:test",
  "npm run detekt:baseline:check",
  "npm run checks:observation:test",
  "node scripts/build-console-assets.mjs --check",
  "node --check fixthis-mcp/src/main/resources/console/app.js",
  "npm run console:test:all",
  "node --test scripts/fixthis-smoke-test.mjs",
  "npm run release:package:test",
  "npm run perf:test",
  "git diff --check HEAD..HEAD",
  "git diff --check",
]);
```

The `--full` test only matches the Gradle line via regex, so it does not need
to change.

- [ ] **Step 3: Run script contract test**

Run:

```bash
npm run ci:local:test
```

Expected: PASS.

- [ ] **Step 4: Run cheap release checks**

Run:

```bash
node scripts/check-release-readiness.mjs
npm run release:v06:evidence:test
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add scripts/verify-ci-local.sh scripts/verify-ci-local-test.mjs
git commit -m "test: run v0.6 evidence gate in local checks"
```

## Task 5: Lock Docs, MCP, And Schema Drift Checks

**Files:**
- Modify: `scripts/check-v06-release-evidence.mjs`
- Modify: `docs/reference/mcp-tools.md`
- Modify: `docs/reference/output-schema.md`

- [ ] **Step 1: Add protected field checks**

In `scripts/check-v06-release-evidence.mjs`, add these checks after `V06.R5.mcp-tools-reference`:

```js
for (const field of [
  'items',
  'screens',
  'itemId',
  'screenId',
  'targetEvidence',
  'targetReliability',
  'sourceCandidates',
]) {
  requireIncludes(
    `V06.R5.schema-field.${field}`,
    'docs/reference/output-schema.md',
    field,
  );
}
```

- [ ] **Step 2: Add MCP tool name checks**

In the same script, add:

```js
for (const tool of [
  'fixthis_read_feedback',
  'fixthis_claim_feedback',
  'fixthis_resolve_feedback',
  'fixthis_open_feedback_console',
]) {
  requireIncludes(
    `V06.R5.mcp-tool.${tool}`,
    'docs/reference/mcp-tools.md',
    tool,
  );
}
```

- [ ] **Step 3: Ensure docs include caution language**

If `docs/reference/mcp-tools.md` does not already say source hints are candidates, add:

```markdown
Source hints are candidates. Agents should verify the screenshot, selected
target, and code before editing, especially for visual-area, interop,
ambiguous, or stale-source cases.
```

If `docs/reference/output-schema.md` does not already name the protected fields, add a short protected-fields paragraph using the same field names from Step 1.

- [ ] **Step 4: Run evidence and docs checks**

Run:

```bash
npm run release:v06:evidence:test
bash scripts/check-docs-cli-surface.sh
node scripts/check-doc-consistency.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add scripts/check-v06-release-evidence.mjs docs/reference/mcp-tools.md docs/reference/output-schema.md
git commit -m "test: guard v0.6 docs MCP schema drift"
```

## Task 6: Run The Track C Verification Set

**Files:**
- No file edits.

- [ ] **Step 1: Run v0.6 release evidence checks**

Run:

```bash
npm run release:v06:claims:test
npm run release:v06:evidence:test
```

Expected: PASS.

- [ ] **Step 2: Run existing release checks**

Run:

```bash
node scripts/check-release-readiness.mjs
bash scripts/check-docs-cli-surface.sh
node scripts/check-doc-consistency.mjs
```

Expected: PASS.

- [ ] **Step 3: Capture observation JSON**

Run:

```bash
npm run checks:observation -- --json
```

Expected: command exits 0 and prints JSON. If a workflow is not ready, keep the JSON and record the narrowed release claim in the release issue.

- [ ] **Step 4: Run local verification contract**

Run:

```bash
npm run ci:local:test
```

Expected: PASS.

- [ ] **Step 5: Record verification in PR notes**

Use this exact summary in the PR body:

```markdown
Track C verification:
- `npm run release:v06:claims:test`
- `npm run release:v06:evidence:test`
- `node scripts/check-release-readiness.mjs`
- `bash scripts/check-docs-cli-surface.sh`
- `node scripts/check-doc-consistency.mjs`
- `npm run checks:observation -- --json`
- `npm run ci:local:test`
```
