# SSE Preview Phase 2 — Runtime Trust Expansion (Tasks 5–10) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 2 of the SSE preview + runtime trust expansion. Phase 1 (preview push-first refactor, tasks 1–4) is already on `main` via commit `c4729b56`. This phase expands runtime source-matching trust fixtures beyond the initial Reply case: documented runtime trust purposes, local-project fixture support for the bundled FixThis sample, additional runtime cases (local sample + Jetsnack), supporting docs, and final verification.

**Architecture:** Extend the local fixture lab with documented runtime trust purposes, local-project fixture support, and additional runtime-trust cases that exercise controlled local and external sample behavior.

**Tech Stack:** Node.js test runner, Kotlin MCP fixture runner, local ADB-backed runtime fixtures, Markdown docs.

**Task numbering note:** Task IDs in this plan start at `task_5` and continue through `task_10`, matching the original SSE preview + runtime trust expansion plan. Phase 1 tasks (`task_1`..`task_4`) are intentionally omitted — they already landed in `main` (commit `c4729b56`).

---

## File Structure

- Modify `scripts/source-matching-fixtures-test.mjs`
  - Add fixture contract tests for `trustPurpose`, local-project fixtures, module directory mapping, runtime report purpose rendering, and the new runtime cases.
- Modify `scripts/source-matching-fixtures.mjs`
  - Add local-project fixture support, optional `moduleDir`, required runtime `trustPurpose`, and report propagation for runtime case purpose.
- Modify `fixtures/source-matching/manifest.json`
  - Add `trustPurpose` to runtime cases, add the local FixThis sample runtime fixture, and add a Jetsnack runtime case.
- Modify `docs/guides/source-matching-fixture-lab.md`
  - Document runtime `trustPurpose`, local-project fixtures, and the expanded runtime fixture set.
- Modify `docs/reference/source-matching.md`
  - Mention that runtime trust fixtures validate runtime confidence/warnings through MCP/session evidence while source-index fixtures remain build-time-only.

## Task 5: Lock Runtime Fixture Purpose And Local-Project Contracts

**Files:**
- Modify: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Update the manifest shape test for local-project support**

In `scripts/source-matching-fixtures-test.mjs`, update the existing `fixture manifest uses schema v2 with separated source-index and runtime-trust cases` test:

```javascript
assert.equal(manifest.fixtures.length, 4);
```

Then replace the fixture loop with:

```javascript
for (const fixture of manifest.fixtures) {
  const source = fixture.source || "external-github";
  assert.ok(["external-github", "local-project"].includes(source));
  if (source === "external-github") {
    assert.match(fixture.repo, /^https:\/\/github\.com\/android\//);
    assert.match(fixture.commit, /^[a-f0-9]{40}$/);
  } else {
    assert.equal(fixture.repo, undefined);
    assert.equal(fixture.commit, undefined);
  }
  assert.ok(fixture.id.length > 0);
  assert.ok(fixture.projectDir.length > 0);
  assert.ok(fixture.modulePath.startsWith(":"));
  assert.ok(fixture.variant.length > 0);
  assert.ok(fixture.applicationId.length > 0);
  assert.ok(fixture.cases.length > 0);
}
```

Add this assertion after the source-index runtime-field loop:

```javascript
for (const entry of cases.filter((testCase) => testCase.mode === "runtime-trust")) {
  assert.equal(typeof entry.trustPurpose, "string");
  assert.ok(entry.trustPurpose.length > 0);
}
```

- [ ] **Step 2: Add validation tests for `trustPurpose`, `source`, and `moduleDir`**

Add these tests after `validateManifest requires runtimeTarget on runtime-trust cases`:

```javascript
test("validateManifest requires trustPurpose on runtime-trust cases", () => {
  assert.throws(
    () => validateManifest({
      schemaVersion: 2,
      fixtures: [{
        id: "reply",
        repo: "https://github.com/android/compose-samples.git",
        commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
        projectDir: "Reply",
        modulePath: ":app",
        variant: "debug",
        applicationId: "com.example.reply",
        cases: [{
          id: "missing-purpose",
          mode: "runtime-trust",
          runtimeTarget: { text: "Compose" },
          expectedTop3PathContains: "ReplyListContent.kt",
          expectedConfidence: "medium-or-high",
        }],
      }],
    }),
    /missing-purpose runtime-trust case must define trustPurpose/,
  );
});

test("validateManifest accepts local-project fixtures with moduleDir", () => {
  assert.doesNotThrow(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "fixthis-sample",
      source: "local-project",
      projectDir: ".",
      modulePath: ":app",
      moduleDir: "sample",
      variant: "debug",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "fixthis-sample-home-primary-runtime",
        mode: "runtime-trust",
        trustPurpose: "controlled local strict component identity case",
        runtimeTarget: { testTag: "comp:HomePrimaryAction:primary" },
        expectedTop3PathContains: "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
        expectedConfidence: "medium-or-high",
        expectedSourceConfidence: "medium-or-high",
        mustNotWarn: ["POSSIBLE_VIEW_INTEROP"],
      }],
    }],
  }));
});

test("validateManifest rejects unsupported fixture sources and unsafe moduleDir", () => {
  assert.throws(
    () => validateManifest({
      schemaVersion: 2,
      fixtures: [{
        id: "bad-local",
        source: "zip-file",
        projectDir: ".",
        modulePath: ":app",
        moduleDir: "../sample",
        variant: "debug",
        applicationId: "io.github.beyondwin.fixthis.sample",
        cases: [{ id: "bad", mode: "source-index", expectedTop3PathContains: "HomeScreen.kt" }],
      }],
    }),
    /bad-local source must be external-github or local-project.*bad-local moduleDir escapes fixture root/,
  );
});
```

- [ ] **Step 3: Add report tests for runtime trust purpose**

Add this test near the existing report tests:

```javascript
test("markdownReport renders runtime trust purpose when present", () => {
  const text = markdownReport({
    schemaVersion: 2,
    generatedAt: "2026-05-25T00:00:00.000Z",
    status: "pass",
    summary: {
      totalCases: 1,
      sourceIndexCases: 0,
      runtimeTrustCases: 1,
      failedCases: 0,
      environmentCases: 0,
      failureCounts: {},
      environmentCounts: {},
    },
    fixtures: [{
      fixtureId: "fixthis-sample",
      status: "evaluated",
      cases: [{
        caseId: "fixthis-sample-home-primary-runtime",
        mode: "runtime-trust",
        trustPurpose: "controlled local strict component identity case",
        metrics: ["top3_hit"],
        failures: [],
        environment: [],
      }],
    }],
  });

  assert.match(text, /Purpose/);
  assert.match(text, /controlled local strict component identity case/);
});
```

- [ ] **Step 4: Run the focused fixture tests and verify failure**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because `trustPurpose`, local-project fixtures, `moduleDir`, and report purpose rendering are not implemented yet.

- [ ] **Step 5: Commit the failing fixture contracts**

```bash
git add scripts/source-matching-fixtures-test.mjs
git commit -m "test: lock runtime fixture purpose contracts"
```

## Task 6: Implement Runtime Fixture Purpose And Local-Project Support

**Files:**
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `fixtures/source-matching/manifest.json`

- [ ] **Step 1: Add fixture source constants and runtime case fields**

In `scripts/source-matching-fixtures.mjs`, add these constants after `manifestSchemaVersion`:

```javascript
const externalFixtureSource = "external-github";
const localFixtureSource = "local-project";
const fixtureSources = new Set([externalFixtureSource, localFixtureSource]);
```

Add `trustPurpose` to `runtimeOnlyCaseFields`:

```javascript
const runtimeOnlyCaseFields = new Set([
  "runtimeTarget",
  "navigation",
  "trustPurpose",
  "expectedConfidence",
  "expectedSourceConfidence",
  "expectedRiskFlags",
  "mustWarn",
  "mustNotWarn",
  "mustNotHighConfidence",
]);
```

Add this new set after `runtimeOnlyCaseFields`:

```javascript
const runtimeTrustCaseFields = new Set([
  "id",
  "mode",
  ...runtimeOnlyCaseFields,
  "expectedTop1PathContains",
  "expectedTop3PathContains",
]);
```

- [ ] **Step 2: Validate fixture source and moduleDir**

Inside `validateManifest(manifest)`, replace the fixture-level `repo` and `commit` validation block with:

```javascript
const source = fixture.source || externalFixtureSource;
if (!fixtureSources.has(source)) {
  errors.push(`${fixture.id || "fixture"} source must be external-github or local-project`);
}
if (source === externalFixtureSource) {
  if (!fixture.repo || !fixture.repo.startsWith("https://github.com/android/")) errors.push(`${fixture.id || "fixture"} repo must be an Android HTTPS GitHub URL`);
  if (!fullShaPattern.test(fixture.commit || "")) errors.push(`${fixture.id || "fixture"} commit must be a 40-character SHA`);
} else {
  if (fixture.repo !== undefined) errors.push(`${fixture.id || "fixture"} local-project fixture must not define repo`);
  if (fixture.commit !== undefined) errors.push(`${fixture.id || "fixture"} local-project fixture must not define commit`);
}
try { safeRelativePath(fixture.projectDir, `${fixture.id || "fixture"} projectDir`); } catch (error) { errors.push(error.message); }
if (fixture.moduleDir !== undefined) {
  try { safeRelativePath(fixture.moduleDir, `${fixture.id || "fixture"} moduleDir`); } catch (error) { errors.push(error.message); }
}
```

Keep the existing `modulePath`, `variant`, `applicationId`, and `cases` checks immediately after this block.

- [ ] **Step 3: Validate runtime-trust fields and purpose**

Inside the `if (entry.mode === "runtime-trust")` block, add this field validation before `validateRuntimeTarget(...)`:

```javascript
for (const field of Object.keys(entry)) {
  if (!runtimeTrustCaseFields.has(field)) {
    errors.push(`${entry.id || "case"} runtime-trust case contains unsupported field ${field}`);
  }
}
if (typeof entry.trustPurpose !== "string" || entry.trustPurpose.length === 0) {
  errors.push(`${entry.id || "case"} runtime-trust case must define trustPurpose`);
}
```

- [ ] **Step 4: Add moduleDir path resolution**

Replace the module directory calculation in `generatedSourceIndexPath(projectRoot, fixture)` with:

```javascript
const moduleDir = fixture.moduleDir
  ? safeRelativePath(fixture.moduleDir, `${fixture.id || "fixture"} moduleDir`)
  : fixture.modulePath.replace(/^:/, "").replaceAll(":", "/");
return join(projectRoot, moduleDir, "build/generated/fixthis", fixture.variant, "assets/fixthis/fixthis-source-index.json");
```

Add this helper before `fixturePaths(fixture)`:

```javascript
function isLocalProjectFixture(fixture) {
  return (fixture.source || externalFixtureSource) === localFixtureSource;
}
```

Replace `fixturePaths(fixture)` with:

```javascript
export function fixturePaths(fixture) {
  if (isLocalProjectFixture(fixture)) {
    const projectWorkDir = fixture.projectDir === "."
      ? repoRoot
      : join(repoRoot, safeRelativePath(fixture.projectDir, `${fixture.id} projectDir`));
    return {
      repoDir: repoRoot,
      workDir: repoRoot,
      projectWorkDir,
    };
  }
  const repoDir = join(fixtureRepoRoot, `${repoCacheKey(fixture.repo)}`);
  const workDir = join(fixtureWorkRoot, fixture.id);
  const projectWorkDir = fixture.projectDir === "."
    ? workDir
    : join(workDir, safeRelativePath(fixture.projectDir, `${fixture.id} projectDir`));
  return { repoDir, workDir, projectWorkDir };
}
```

- [ ] **Step 5: Skip clone and patch for local-project fixtures**

At the top of `prepareFixture(fixture, options = {})`, immediately after `const paths = fixturePaths(fixture);`, add:

```javascript
if (isLocalProjectFixture(fixture)) {
  return paths;
}
```

- [ ] **Step 6: Propagate `trustPurpose` into runtime results**

Replace `evaluateRuntimeTrustFixture(fixture, runnerOutput)` with this implementation:

```javascript
export function evaluateRuntimeTrustFixture(fixture, runnerOutput) {
  const casesById = new Map((runnerOutput.cases || []).map((testCase) => [testCase.caseId, testCase]));
  const cases = fixture.cases
    .filter((testCase) => testCase.mode === "runtime-trust")
    .map((testCase) => {
      const captured = casesById.get(testCase.id);
      if (!captured) {
        return {
          caseId: testCase.id,
          mode: "runtime-trust",
          trustPurpose: testCase.trustPurpose,
          metrics: [],
          failures: ["missing_runtime_case_output"],
          environment: [],
        };
      }
      if (captured.failures?.length || captured.environment?.length) {
        return {
          caseId: testCase.id,
          mode: "runtime-trust",
          trustPurpose: testCase.trustPurpose,
          metrics: [],
          failures: captured.failures || [],
          environment: captured.environment || [],
          observed: captured.observed || null,
        };
      }
      const outcome = classifyRuntimeTrustOutcome(testCase, captured.observed || {});
      return {
        caseId: testCase.id,
        mode: "runtime-trust",
        trustPurpose: testCase.trustPurpose,
        metrics: outcome.metrics,
        failures: outcome.failures,
        environment: outcome.environment,
        observed: captured.observed,
      };
    });
  return {
    fixtureId: fixture.id,
    mode: "runtime-trust",
    status: cases.some((testCase) => testCase.failures.length > 0) ? "fail" : "evaluated",
    cases,
  };
}
```

- [ ] **Step 7: Render purpose in Markdown reports**

In `markdownReport(report)`, change the table header from:

```javascript
lines.push("| Case | Metrics | Failures | Environment |");
lines.push("| --- | --- | --- | --- |");
```

to:

```javascript
lines.push("| Case | Purpose | Metrics | Failures | Environment |");
lines.push("| --- | --- | --- | --- | --- |");
```

Then change the row builder from:

```javascript
lines.push([
  testCase.caseId,
  (testCase.metrics || []).join(", ") || "-",
  (testCase.failures || []).join(", ") || "-",
  (testCase.environment || []).join(", ") || "-",
].join(" | ").replace(/^/, "| ").replace(/$/, " |"));
```

to:

```javascript
lines.push([
  testCase.caseId,
  testCase.trustPurpose || "-",
  (testCase.metrics || []).join(", ") || "-",
  (testCase.failures || []).join(", ") || "-",
  (testCase.environment || []).join(", ") || "-",
].join(" | ").replace(/^/, "| ").replace(/$/, " |"));
```

- [ ] **Step 8: Backfill `trustPurpose` on the existing Reply runtime-trust case**

After Step 3 turns on the `trustPurpose` requirement, the existing `reply-compose-fab-runtime` case in `fixtures/source-matching/manifest.json` violates the new validator. Update that case in place by adding a `trustPurpose` field immediately after `mode` so the runtime case documents what it asserts:

```json
{
  "id": "reply-compose-fab-runtime",
  "mode": "runtime-trust",
  "trustPurpose": "external Compose Material button identity case proving runtime trust matches the source-index winner",
  "runtimeTarget": {
    "text": "Compose",
    "role": "Button"
  },
  "expectedTop3PathContains": "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt",
  "expectedConfidence": "medium-or-high",
  "expectedSourceConfidence": "medium-or-high",
  "mustNotWarn": [
    "POSSIBLE_VIEW_INTEROP"
  ]
}
```

Leave every other field of that case unchanged. Do not introduce any new fixture, case, or `source`/`moduleDir` field here — those land in Task 7.

- [ ] **Step 9: Run fixture tests and verify they pass**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS. Both the new Task 5 contracts and the previously passing `reply-compose-fab-runtime` case must validate cleanly under the new `trustPurpose` requirement.

- [ ] **Step 10: Commit the fixture runner support**

```bash
git add scripts/source-matching-fixtures.mjs fixtures/source-matching/manifest.json
git commit -m "feat: support documented local runtime fixtures"
```

## Task 7: Add Focused Runtime-Trust Cases

**Files:**
- Modify: `fixtures/source-matching/manifest.json`
- Modify: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Refine `trustPurpose` on the existing Reply runtime case**

In `fixtures/source-matching/manifest.json`, replace the Task 6 backfill `trustPurpose` on `reply-compose-fab-runtime` with the focused description:

```json
{
  "id": "reply-compose-fab-runtime",
  "mode": "runtime-trust",
  "trustPurpose": "baseline external runtime target with visible button text and no interop warning",
  "runtimeTarget": { "text": "Compose", "role": "Button" },
  "expectedTop3PathContains": "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt",
  "expectedConfidence": "medium-or-high",
  "expectedSourceConfidence": "medium-or-high",
  "mustNotWarn": ["POSSIBLE_VIEW_INTEROP"]
}
```

- [ ] **Step 2: Add the local FixThis sample runtime fixture**

Add this fixture object after the Reply fixture:

```json
{
  "id": "fixthis-sample",
  "source": "local-project",
  "projectDir": ".",
  "modulePath": ":app",
  "moduleDir": "sample",
  "variant": "debug",
  "applicationId": "io.github.beyondwin.fixthis.sample",
  "cases": [
    {
      "id": "fixthis-sample-home-primary-runtime",
      "mode": "runtime-trust",
      "trustPurpose": "controlled local strict component identity case",
      "runtimeTarget": { "testTag": "comp:HomePrimaryAction:primary" },
      "expectedTop3PathContains": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
      "expectedConfidence": "medium-or-high",
      "expectedSourceConfidence": "medium-or-high",
      "mustNotWarn": ["POSSIBLE_VIEW_INTEROP"]
    }
  ]
}
```

- [ ] **Step 3: Add a Jetsnack runtime copy/text case**

Add this case to the existing `jetsnack.cases` array after `jetsnack-search-category-copy`:

```json
{
  "id": "jetsnack-desserts-runtime",
  "mode": "runtime-trust",
  "trustPurpose": "external copy/data runtime target from a non-Reply Compose sample",
  "runtimeTarget": { "text": "Desserts" },
  "expectedTop3PathContains": "Jetsnack/app/src/main/java/com/example/jetsnack/model/Search.kt",
  "expectedConfidence": "medium-or-high",
  "expectedSourceConfidence": "low-or-medium",
  "mustNotWarn": ["POSSIBLE_VIEW_INTEROP"]
}
```

- [ ] **Step 4: Add a fixture test for the chosen runtime case ids**

In `scripts/source-matching-fixtures-test.mjs`, add this test after the manifest shape test:

```javascript
test("runtime trust manifest includes focused Reply, local sample, and Jetsnack cases", () => {
  const manifest = readJson("fixtures/source-matching/manifest.json");
  const cases = manifest.fixtures.flatMap((fixture) => fixture.cases.map((testCase) => ({
    fixtureId: fixture.id,
    ...testCase,
  })));

  assert.ok(cases.some((testCase) => testCase.fixtureId === "reply" && testCase.id === "reply-compose-fab-runtime"));
  assert.ok(cases.some((testCase) => testCase.fixtureId === "fixthis-sample" && testCase.id === "fixthis-sample-home-primary-runtime"));
  assert.ok(cases.some((testCase) => testCase.fixtureId === "jetsnack" && testCase.id === "jetsnack-desserts-runtime"));
});
```

- [ ] **Step 5: Run fixture tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 6: Run the fast source-index fixture evaluation**

Run:

```bash
npm run source-matching:fixtures
```

Expected: PASS or `pass_with_environment_downgrade` is not expected here because this command is source-index-only. If it fails due external fixture drift, inspect `build/reports/fixthis-source-matching/report.md` before changing expectations.

- [ ] **Step 7: Commit the runtime cases**

```bash
git add fixtures/source-matching/manifest.json scripts/source-matching-fixtures-test.mjs
git commit -m "test: expand runtime trust fixture cases"
```

## Task 8: Verify Runtime Evaluation Behavior

**Files:**
- No required source edits unless the commands expose an implementation defect from Task 6 or Task 7.

- [ ] **Step 1: Run runtime fixtures in default mode**

Run:

```bash
npm run source-matching:fixtures:runtime
```

Expected with a configured emulator/device: PASS or FAIL with concrete runtime case labels that point to matcher or selector behavior.

Expected without a usable emulator/device: `pass_with_environment_downgrade` with `capture_failed`, `app_launch_failed`, or another environment label in `build/reports/fixthis-source-matching/report.md`.

- [ ] **Step 2: Inspect the runtime report**

Run:

```bash
npm run source-matching:fixtures:report
```

Expected: the Markdown table includes a `Purpose` column and shows the three runtime case purposes:

```text
baseline external runtime target with visible button text and no interop warning
controlled local strict component identity case
external copy/data runtime target from a non-Reply Compose sample
```

- [ ] **Step 3: Run strict runtime only when a device is intentionally ready**

Run this command only when `adb devices` shows a usable emulator or device:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

Expected with a configured emulator/device: exits 0. If it exits non-zero with `target_not_found` or `target_ambiguous`, fix the corresponding `runtimeTarget` selector in `fixtures/source-matching/manifest.json` and rerun `npm run source-matching:fixtures:test`.

- [ ] **Step 4: Commit selector/report corrections if runtime execution required them**

If Step 3 required selector or expectation corrections, commit them:

```bash
git add fixtures/source-matching/manifest.json scripts/source-matching-fixtures-test.mjs
git commit -m "fix: calibrate runtime trust fixture selectors"
```

If no corrections were required, do not create an empty commit.

## Task 9: Document Expanded Runtime Fixtures

**Files:**
- Modify: `docs/guides/source-matching-fixture-lab.md`
- Modify: `docs/reference/source-matching.md`

- [ ] **Step 1: Document runtime `trustPurpose` and local-project fixtures**

In `docs/guides/source-matching-fixture-lab.md`, add this section after `## What It Uses`:

```markdown
## Runtime Trust Case Purpose

Every `runtime-trust` case includes `trustPurpose`. The field explains which
trust failure mode the case protects, such as baseline runtime confidence,
controlled local component identity, external copy/data matching, selector
drift, or warning/risk observation. Reports render this purpose so failures are
actionable without reopening the manifest.

The lab supports two fixture sources:

- `external-github` fixtures clone a pinned Android sample repository into
  `.fixthis/eval-fixtures/`.
- `local-project` fixtures run against this checkout, currently for the bundled
  FixThis sample app. They do not clone or patch the project.
```

- [ ] **Step 2: Document the current runtime fixture set**

In `docs/guides/source-matching-fixture-lab.md`, add this paragraph after the runtime command examples:

```markdown
The initial runtime set covers Reply as the external happy path, the bundled
FixThis sample app as a controlled local component-identity case, and Jetsnack
as a non-Reply copy/data case. Now in Android remains source-index-only until a
stable launch-state runtime selector is verified without coordinates, scroll
setup, account state, network state, or fragile timing.
```

- [ ] **Step 3: Update the source matching reference**

In `docs/reference/source-matching.md`, add this paragraph after the `## Agent Handoff` section:

```markdown
## Runtime Trust Fixtures

Runtime trust fixtures are local-only checks that install a debug app, capture a
real screen through the MCP/session path, resolve a semantics target, and
validate observed `sourceCandidates` plus `targetReliability`. They complement
source-index fixtures; they do not replace the fast build-time source-index
checks and they are not a CI or release requirement.
```

- [ ] **Step 4: Run documentation checks**

Run:

```bash
git diff --check
```

Expected: exits 0.

- [ ] **Step 5: Commit docs**

```bash
git add docs/guides/source-matching-fixture-lab.md docs/reference/source-matching.md
git commit -m "docs: explain expanded runtime trust fixtures"
```

## Task 10: Final Verification

**Files:**
- No source edits unless verification exposes failures.

- [ ] **Step 1: Run fixture tests**

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 2: Run fast source-index fixture evaluation**

```bash
npm run source-matching:fixtures
```

Expected: PASS.

- [ ] **Step 3: Run optional runtime fixture evaluation**

```bash
npm run source-matching:fixtures:runtime
```

Expected with a device: PASS. Expected without a device: `pass_with_environment_downgrade`; this is acceptable for local default mode.

- [ ] **Step 4: Final whitespace and status checks**

```bash
git diff --check
git status --short
```

Expected: `git diff --check` exits 0. `git status --short` shows only intentional tracked changes before the final commit, and no `.fixthis/`, `build/reports/fixthis-source-matching/`, Android build output, or `graphify-out/` files are staged.

- [ ] **Step 5: Final commit if verification caused follow-up edits**

If verification required fixes after the earlier commits:

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs fixtures/source-matching/manifest.json docs/guides/source-matching-fixture-lab.md docs/reference/source-matching.md
git commit -m "fix: close runtime fixture verification gaps"
```

Do not create a final empty commit when no files changed.
