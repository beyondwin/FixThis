# External Runtime Trust Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strengthen FixThis's external-project and risky-target evidence so future v1.x release claims are backed by matrix reports, runtime handoff observations, and release-gate readiness rules.

**Architecture:** Extend the existing external fixture matrix, runtime trust fixture lab, evidence runner, release gate, and release-readiness checks. Do not create a parallel evidence system. Keep `:fixthis-compose-core` free of MCP, CLI, Android UI, `.fixthis/`, and report-path dependencies.

**Tech Stack:** Node.js 20 ESM (`node:test`), Kotlin/JVM with kotlinx.serialization, Gradle, Jetpack Compose debug fixture apps, Markdown docs.

Design: [`docs/superpowers/specs/2026-06-01-external-runtime-trust-expansion-design.md`](../specs/2026-06-01-external-runtime-trust-expansion-design.md).

---

## File Structure

### Track A - External Matrix Report Metadata

- Modify: `scripts/external-fixture-matrix.mjs`
  - Add a small descriptor projection for fixture metadata.
  - Include `projectShape`, `expectedSetup`, `appModule`, `variant`, and `applicationId` in every fixture report entry.
  - Render that metadata in the Markdown report's fixture table.
- Modify: `scripts/external-fixture-matrix-test.mjs`
  - Lock report metadata, Markdown columns, and deferred fixture metadata.

### Track B - Runtime Handoff Trust Observations

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt`
  - Add optional `compactHandoff` and `preciseHandoff` strings to `RuntimeTrustObserved`.
  - Change `RuntimeTrustObservationMapper.fromAnnotation` to accept an optional `SessionDto`.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunner.kt`
  - Pass the current session into the observation mapper after adding each runtime feedback item.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt`
  - Lock compact and precise handoff observation serialization.
- Modify: `scripts/source-matching-fixtures.mjs`
  - Allow `mustHandoffContain` and `mustNotHandoffContain` on runtime-trust cases.
  - Classify missing or forbidden runtime handoff wording as trust failures.
- Modify: `scripts/source-matching-fixtures-test.mjs`
  - Add manifest validation and classification tests for runtime handoff wording.
- Modify: `fixtures/source-matching/manifest.json`
  - Add handoff wording expectations to the existing visual-area and interop-risk runtime cases.

### Track C - Release Evidence Claim Wiring

- Modify: `docs/contributing/release-readiness.md`
  - Add an "External Runtime Trust Expansion Evidence" manifest row that binds external matrix, risky runtime trust, and release gate evidence.
- Modify: `scripts/check-release-readiness.mjs`
  - Add readiness rules for the new section and commands.
- Modify: `scripts/release-gate-test.mjs`
  - Add a focused test that the release-gate claim map has both external matrix and runtime trust evidence for the new line.
- Modify: `docs/product/roadmap.md`
  - Add a short high-priority note pointing from v1.1 trust loop to this external-runtime expansion.
- Modify: `docs/releases/unreleased.md`
  - Add a planned evidence-line note after the commands exist.

---

## Task 1: Add External Matrix Fixture Metadata To Reports

**Files:**
- Modify: `scripts/external-fixture-matrix-test.mjs`
- Modify: `scripts/external-fixture-matrix.mjs`

- [ ] **Step 1: Write the failing report metadata tests**

Append these tests near the existing matrix report tests in `scripts/external-fixture-matrix-test.mjs`:

```js
test('matrix report includes fixture shape setup and app metadata', () => {
  const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'multi-module-non-root-app');
  const report = buildMatrixReport({
    strict: false,
    generatedAt: '2026-06-01T00:00:00.000Z',
    fixtures: [{
      fixtureId: fixture.id,
      projectShape: fixture.projectShape,
      expectedSetup: fixture.expectedSetup,
      appModule: fixture.appModule,
      variant: fixture.variant,
      applicationId: fixture.applicationId,
      status: 'pass',
      commands: [{ name: 'doctor-json', command: 'fixthis doctor --json', status: 'pass', durationMs: 5 }],
    }],
  });

  assert.deepEqual(report.fixtures[0], {
    fixtureId: 'multi-module-non-root-app',
    projectShape: 'multi-module',
    expectedSetup: 'ready',
    appModule: ':features:demo-app',
    variant: 'debug',
    applicationId: 'io.github.beyondwin.fixthis.matrix.multimodule',
    status: 'pass',
    commands: [{ name: 'doctor-json', command: 'fixthis doctor --json', status: 'pass', durationMs: 5 }],
  });
});

test('matrix markdown renders fixture shape and expected setup columns', () => {
  const text = renderMatrixMarkdown(buildMatrixReport({
    strict: false,
    generatedAt: '2026-06-01T00:00:00.000Z',
    fixtures: [{
      fixtureId: 'missing-generated-metadata',
      projectShape: 'missing-generated-metadata',
      expectedSetup: 'needs-fixthis-setup',
      appModule: ':app',
      variant: 'debug',
      applicationId: 'io.github.beyondwin.fixthis.matrix.recovery',
      status: 'deferred',
      reason: 'Android SDK unavailable',
      commands: [],
    }],
  }));

  assert.match(text, /\| Fixture \| Shape \| Expected setup \| App module \| Variant \| Status \| Reason \|/);
  assert.match(text, /\| missing-generated-metadata \| missing-generated-metadata \| needs-fixthis-setup \| :app \| debug \| deferred \| Android SDK unavailable \|/);
});
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
npm run external-fixture:matrix:test
```

Expected: FAIL because the Markdown header still lacks `Shape`, `Expected setup`, `App module`, and `Variant` columns, or because report entries created by `runExternalMatrix` do not yet include the metadata.

- [ ] **Step 3: Add the fixture descriptor projection**

In `scripts/external-fixture-matrix.mjs`, add this helper after `planFixtureCommands`:

```js
function fixtureReportBase(fixture) {
  return {
    fixtureId: fixture.id,
    projectShape: fixture.projectShape,
    expectedSetup: fixture.expectedSetup,
    appModule: fixture.appModule,
    variant: fixture.variant,
    applicationId: fixture.applicationId,
  };
}
```

Then replace every object literal in `runExternalMatrix` that starts with `fixtureId: fixture.id` with a spread of this helper.

For the CLI preparation failure branch, use:

```js
manifest.fixtures.map((fixture) => ({
  ...fixtureReportBase(fixture),
  status: 'fail',
  reason: cliPreparation.stderr?.split('\n').find(Boolean) || cliPreparation.stdout?.split('\n').find(Boolean) || 'prepare-cli failed',
  commands: [
    cliPreparation,
    ...planFixtureCommands(fixture, join(workRoot, fixture.id), root).map((entry) => ({ ...entry, status: 'deferred', durationMs: 0 })),
  ],
}))
```

For the Android-missing branch, use:

```js
fixtures.push({
  ...fixtureReportBase(fixture),
  status: environment.status,
  reason: environment.reason,
  commands: planFixtureCommands(fixture, projectDir, root).map((entry) => ({ ...entry, status: 'deferred', durationMs: 0 })),
});
```

For the normal branch, replace the final push with:

```js
fixtures.push({ ...fixtureReportBase(fixture), status, reason, commands });
```

In the dry-run branch in `main`, use:

```js
fixtures: manifest.fixtures.map((fixture) => ({
  ...fixtureReportBase(fixture),
  status: 'planned',
  commands: planFixtureCommands(fixture, join(defaultMatrixWorkRoot, fixture.id), repoRoot).map((entry) => ({ ...entry, status: 'planned', durationMs: 0 })),
})),
```

- [ ] **Step 4: Render the metadata in Markdown**

In `renderMatrixMarkdown`, replace the first table header:

```js
'| Fixture | Status | Reason |',
'| --- | --- | --- |',
```

with:

```js
'| Fixture | Shape | Expected setup | App module | Variant | Status | Reason |',
'| --- | --- | --- | --- | --- | --- | --- |',
```

Then replace the row renderer:

```js
lines.push(`| ${cell(fixture.fixtureId)} | ${cell(fixture.status)} | ${cell(fixture.reason)} |`);
```

with:

```js
lines.push(`| ${cell(fixture.fixtureId)} | ${cell(fixture.projectShape)} | ${cell(fixture.expectedSetup)} | ${cell(fixture.appModule)} | ${cell(fixture.variant)} | ${cell(fixture.status)} | ${cell(fixture.reason)} |`);
```

- [ ] **Step 5: Run the focused test and verify it passes**

Run:

```bash
npm run external-fixture:matrix:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add scripts/external-fixture-matrix.mjs scripts/external-fixture-matrix-test.mjs
git commit -m "test(matrix): expose external fixture metadata"
```

---

## Task 2: Add Runtime Handoff Text Observations

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunner.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt`

- [ ] **Step 1: Write the failing Kotlin observation mapper test**

In `RuntimeTrustObservationMapperTest.kt`, add these imports:

```kotlin
import io.github.beyondwin.fixthis.mcp.session.SessionDto
import io.github.beyondwin.fixthis.mcp.session.SnapshotDto
import kotlin.test.assertNotNull
```

Then append this test to the class:

```kotlin
@Test
fun runtimeTrustObservationIncludesCompactAndPreciseHandoffTextWhenSessionIsAvailable() {
    val item = AnnotationDto(
        itemId = "interop",
        screenId = "screen",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(40f, 120f, 220f, 220f)),
        nearbyNodes = listOf(
            FixThisNode(
                uid = "host",
                composeNodeId = 1,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = FixThisRect(24f, 112f, 360f, 260f),
                testTag = "comp:NativeChartHost:chart",
                role = "Image",
            ),
        ),
        comment = "Fix the native chart",
        targetReliability = TargetReliability(
            confidence = TargetConfidence.LOW,
            warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
        ),
    )
    val session = SessionDto(
        sessionId = "session-runtime-trust",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(SnapshotDto("screen", 1L, displayName = "Diagnostics")),
        items = listOf(item),
    )

    val observed = RuntimeTrustObservationMapper.fromAnnotation(item, session)

    assertNotNull(observed.compactHandoff)
    assertNotNull(observed.preciseHandoff)
    assertTrue(observed.compactHandoff.orEmpty().contains("targetBoundary=interop-risk"))
    assertTrue(observed.compactHandoff.orEmpty().contains("targetAction=treat-source-paths-as-hints"))
    assertTrue(observed.preciseHandoff.orEmpty().contains("possible AndroidView/WebView target"))
    assertTrue(observed.preciseHandoff.orEmpty().contains("source candidates are verification hints, not exact ownership"))
}
```

- [ ] **Step 2: Run the focused Kotlin test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustObservationMapperTest" --no-daemon
```

Expected: FAIL because `RuntimeTrustObserved` does not yet expose `compactHandoff` or `preciseHandoff`, and `fromAnnotation` accepts only `AnnotationDto`.

- [ ] **Step 3: Add handoff text to the runtime observation model**

In `RuntimeTrustFixtureModels.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.format.DetailMode
import io.github.beyondwin.fixthis.mcp.session.FeedbackQueueFormatter
import io.github.beyondwin.fixthis.mcp.session.SessionDto
```

Add fields to `RuntimeTrustObserved`:

```kotlin
    val compactHandoff: String? = null,
    val preciseHandoff: String? = null,
```

Change the mapper signature:

```kotlin
fun fromAnnotation(item: AnnotationDto, session: SessionDto? = null): RuntimeTrustObserved {
```

Add these values in the returned `RuntimeTrustObserved`:

```kotlin
            compactHandoff = session?.let { FeedbackQueueFormatter.toMarkdown(it, DetailMode.COMPACT) },
            preciseHandoff = session?.let { FeedbackQueueFormatter.toMarkdown(it, DetailMode.PRECISE) },
```

The resulting return should still keep the existing `candidates`, `confidence`, `sourceConfidence`, `riskFlags`, `warnings`, `callSites`, and `boundaryContext` fields unchanged.

- [ ] **Step 4: Pass the session into the mapper from the runner**

In `RuntimeTrustFixtureRunner.kt`, replace:

```kotlin
                RuntimeTrustCaseOutput(
                    caseId = testCase.caseId,
                    observed = RuntimeTrustObservationMapper.fromAnnotation(item),
                )
```

with:

```kotlin
                RuntimeTrustCaseOutput(
                    caseId = testCase.caseId,
                    observed = RuntimeTrustObservationMapper.fromAnnotation(
                        item = item,
                        session = runtime.service.getSession(session.sessionId),
                    ),
                )
```

- [ ] **Step 5: Run the focused Kotlin test and verify it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustObservationMapperTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunner.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt
git commit -m "test(runtime): observe risky target handoff text"
```

---

## Task 3: Classify Runtime Handoff Wording In Fixture Reports

**Files:**
- Modify: `scripts/source-matching-fixtures-test.mjs`
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `fixtures/source-matching/manifest.json`

- [ ] **Step 1: Write failing manifest validation and classification tests**

Append these tests to `scripts/source-matching-fixtures-test.mjs` near the existing runtime trust classification tests:

```js
test("validateManifest accepts runtime handoff wording expectations", () => {
  assert.doesNotThrow(() => validateManifest({
    schemaVersion: 2,
    fixtures: [{
      id: "local",
      source: "local-project",
      projectDir: ".",
      modulePath: ":app",
      moduleDir: "sample",
      variant: "debug",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "interop-handoff",
        mode: "runtime-trust",
        trustPurpose: "interop handoff wording",
        runtimeTarget: { visualArea: { left: 1, top: 1, right: 2, bottom: 2 } },
        mustHandoffContain: ["targetBoundary=interop-risk", "possible AndroidView/WebView target"],
        mustNotHandoffContain: ["exact source owner"],
      }],
    }],
  }));
});

test("classifyRuntimeTrustOutcome checks runtime compact and precise handoff wording", () => {
  const result = classifyRuntimeTrustOutcome({
    mustHandoffContain: ["targetBoundary=visual-area", "verify before editing"],
    mustNotHandoffContain: ["edit this exact source"],
  }, {
    compactHandoff: "targetBoundary=visual-area\nAction: verify before editing",
    preciseHandoff: "Action: verify before editing",
  });

  assert.deepEqual(result.failures, []);
  assert.ok(result.metrics.includes("handoff_required_text_present"));
  assert.ok(result.metrics.includes("handoff_forbidden_text_absent"));
});

test("classifyRuntimeTrustOutcome fails missing and forbidden runtime handoff wording", () => {
  const result = classifyRuntimeTrustOutcome({
    mustHandoffContain: ["targetBoundary=interop-risk"],
    mustNotHandoffContain: ["exact source owner"],
  }, {
    compactHandoff: "exact source owner",
    preciseHandoff: "",
  });

  assert.deepEqual(result.failures, ["missing_handoff_text", "forbidden_handoff_text"]);
});
```

- [ ] **Step 2: Run the focused JS test and verify it fails**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because runtime manifest validation rejects `mustHandoffContain` / `mustNotHandoffContain`, and classification ignores handoff text.

- [ ] **Step 3: Add the runtime manifest fields**

In `scripts/source-matching-fixtures.mjs`, add these field names to `runtimeOnlyCaseFields`:

```js
  "mustHandoffContain",
  "mustNotHandoffContain",
```

Add this helper near `validateAllowedValues`:

```js
function validateStringList(values, fieldName, errors) {
  for (const value of arrayOf(values)) {
    if (typeof value !== "string" || value.length === 0) {
      errors.push(`${fieldName} must contain non-empty strings`);
    }
  }
}
```

Inside the `entry.mode === "runtime-trust"` validation block, after the warning validation calls, add:

```js
        validateStringList(entry.mustHandoffContain, `${entry.id || "case"} mustHandoffContain`, errors);
        validateStringList(entry.mustNotHandoffContain, `${entry.id || "case"} mustNotHandoffContain`, errors);
```

- [ ] **Step 4: Classify handoff wording**

In `classifyRuntimeTrustOutcome`, before the final `return outcome`, add:

```js
  const requiredHandoffText = arrayOf(expectation.mustHandoffContain);
  const forbiddenHandoffText = arrayOf(expectation.mustNotHandoffContain);
  if (requiredHandoffText.length > 0 || forbiddenHandoffText.length > 0) {
    const hasHandoffObservation = hasOwn(observed, "compactHandoff") || hasOwn(observed, "preciseHandoff");
    const handoffText = [observed.compactHandoff, observed.preciseHandoff]
      .filter((value) => typeof value === "string")
      .join("\n");
    if (!hasHandoffObservation) {
      addUnique(outcome.failures, "missing_handoff_observation");
    } else {
      for (const needle of requiredHandoffText) {
        if (handoffText.includes(needle)) addUnique(outcome.metrics, "handoff_required_text_present");
        else addUnique(outcome.failures, "missing_handoff_text");
      }
      for (const needle of forbiddenHandoffText) {
        if (handoffText.includes(needle)) addUnique(outcome.failures, "forbidden_handoff_text");
        else addUnique(outcome.metrics, "handoff_forbidden_text_absent");
      }
    }
  }
```

- [ ] **Step 5: Add wording expectations to risky runtime manifest cases**

In `fixtures/source-matching/manifest.json`, update the existing `fixthis-sample-diagnostics-visual-area` case with:

```json
          "mustHandoffContain": ["targetBoundary=visual-area", "VISUAL_AREA_ONLY"],
          "mustNotHandoffContain": ["exact source owner", "edit this exact source"]
```

Update the existing `fixthis-sample-diagnostics-androidview-interop` case with:

```json
          "mustHandoffContain": ["targetBoundary=interop-risk", "POSSIBLE_VIEW_INTEROP"],
          "mustNotHandoffContain": ["exact source owner", "edit this exact source"]
```

Keep the existing `mustWarn`, `expectedBoundaryContextKinds`, and `mustNotHighConfidence` fields.

- [ ] **Step 6: Run focused tests**

Run:

```bash
npm run source-matching:fixtures:test
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustObservationMapperTest" --tests "*RuntimeTrustFixtureRunnerTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit Task 3**

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs fixtures/source-matching/manifest.json
git commit -m "test(runtime): assert risky target handoff wording"
```

---

## Task 4: Wire The External Runtime Trust Claim Into Release Readiness

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `scripts/release-gate-test.mjs`
- Modify: `docs/product/roadmap.md`
- Modify: `docs/releases/unreleased.md`

- [ ] **Step 1: Write failing readiness and release-gate tests**

In `scripts/check-release-readiness.mjs`, do not edit rules yet.

First append this test to `scripts/release-gate-test.mjs`:

```js
test('release gate keeps external matrix and runtime source trust claims independently mapped', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-01T00:00:00.000Z',
    steps: [
      { name: 'External fixture matrix strict', command: 'npm run external-fixture:matrix -- --strict', status: 'deferred', reason: 'Android SDK unavailable' },
      { name: 'Runtime trust strict', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', reason: 'Android SDK unavailable' },
      { name: 'Handoff evaluation', command: 'npm run handoff:eval:test', status: 'pass' },
    ],
  });

  assert.equal(report.claims.find((claim) => claim.id === 'external-fixture-matrix').status, 'deferred');
  assert.equal(report.claims.find((claim) => claim.id === 'runtime-source-trust').status, 'deferred');
  assert.equal(report.claims.find((claim) => claim.id === 'handoff-correctness-v2').status, 'pass');
});
```

- [ ] **Step 2: Run readiness tests and verify the new section is missing**

Run:

```bash
npm run release:gate:test
node scripts/check-release-readiness.mjs
```

Expected: `release:gate:test` may already pass if claim mapping is present. `check-release-readiness.mjs` must still lack the new external runtime trust section rule until the next step.

- [ ] **Step 3: Add release-readiness rules**

In `scripts/check-release-readiness.mjs`, after rule `R41.handoff-correctness-command`, add:

```js
requireIncludes(
  'R42.external-runtime-trust-section',
  'docs/contributing/release-readiness.md',
  '## External Runtime Trust Expansion Evidence',
);
requireIncludes(
  'R43.external-runtime-trust-matrix-command',
  'docs/contributing/release-readiness.md',
  '`npm run external-fixture:matrix -- --strict`',
);
requireIncludes(
  'R44.external-runtime-trust-runtime-command',
  'docs/contributing/release-readiness.md',
  '`npm run source-matching:fixtures:runtime -- --strict`',
);
requireIncludes(
  'R45.external-runtime-trust-release-gate-command',
  'docs/contributing/release-readiness.md',
  '`npm run release:gate`',
);
```

- [ ] **Step 4: Add the release-readiness section**

In `docs/contributing/release-readiness.md`, after `## External Trust Matrix And Release Drift Evidence`, add:

```markdown
## External Runtime Trust Expansion Evidence

This umbrella may be claimed only when external project setup evidence and
risky-target runtime evidence both feed the release gate. It extends the
external trust matrix line; it does not add a new package channel or exact
XML/View/WebView source targeting.

| Claim | Required evidence |
| --- | --- |
| Representative external Android project shapes complete setup validation and report fixture metadata, readiness expectations, commands, and deferred reasons. | `npm run external-fixture:matrix:test` and `npm run external-fixture:matrix -- --strict`. |
| Runtime trust fixtures keep interop-risk, visual-area, and weak source-candidate handoffs caveated in JSON and Markdown instead of claiming exact source ownership. | `npm run source-matching:fixtures:test`, `npm run source-matching:fixtures:runtime -- --strict`, and `npm run handoff:eval:test`. |
| Maintainers can use one release decision report to see external matrix and risky runtime trust status as pass, deferred, or fail. | `npm run release:gate`, `npm run release:gate:test`, and `node scripts/check-release-readiness.mjs`. |

Connected Android evidence remains local-only. If Android SDK or an unlocked
emulator is unavailable, non-strict reports must record the exact deferred
reason and strict connected commands must fail.
```

- [ ] **Step 5: Update roadmap and unreleased notes**

In `docs/product/roadmap.md`, under `## High-priority Work`, add this short section after `v1.1 trust loop evidence`:

```markdown
### External runtime trust expansion

The next hardening line expands the external project matrix and ties risky
target runtime trust to release-gate evidence. It verifies that interop-risk,
visual-area, and weak source-candidate handoffs stay caveated in realistic
external setup paths instead of becoming exact source ownership claims.
```

In `docs/releases/unreleased.md`, under `## Highlights`, add:

```markdown
- Planned the External Runtime Trust Expansion evidence line: external matrix
  reports will carry fixture shape/readiness metadata, and risky runtime trust
  fixtures will assert interop-risk and visual-area handoff wording through the
  release gate.
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
npm run release:gate:test
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit Task 4**

```bash
git add docs/contributing/release-readiness.md scripts/check-release-readiness.mjs scripts/release-gate-test.mjs docs/product/roadmap.md docs/releases/unreleased.md
git commit -m "docs(release): add external runtime trust evidence gate"
```

---

## Task 5: Final Verification And Graph Refresh

**Files:**
- Modify: `docs/superpowers/plans/2026-06-01-external-runtime-trust-expansion.md` only if task checkboxes are updated during execution.
- Do not commit: `.fixthis/`, `build/reports/`, `graphify-out/`, Android build output, screenshots, generated fixture workspaces.

- [ ] **Step 1: Run fast focused verification**

Run:

```bash
npm run external-fixture:matrix:test
npm run source-matching:fixtures:test
npm run release:gate:test
node scripts/check-release-readiness.mjs
git diff --check
```

Expected: PASS.

- [ ] **Step 2: Run dry-run evidence verification**

Run:

```bash
npm run evidence:trust -- --dry-run
```

Expected: PASS. Output should include these commands:

```text
RUN npm run external-fixture:matrix:test
RUN npm run external-fixture:matrix -- --strict
RUN npm run source-matching:fixtures:test
RUN npm run source-matching:fixtures:runtime -- --strict
RUN npm run handoff:eval:test
```

- [ ] **Step 3: Run connected strict verification if Android runtime prerequisites are available**

First check:

```bash
adb devices
```

If a ready device or emulator is listed, run:

```bash
npm run external-fixture:matrix -- --strict
npm run source-matching:fixtures:runtime -- --strict
```

Expected: PASS.

If no ready device or emulator is listed, do not claim strict connected evidence. Record the exact `adb devices` output and the deferred reason in the final implementation notes.

- [ ] **Step 4: Refresh Graphify after code changes**

Run:

```bash
graphify update .
```

Expected: command completes. Do not stage `graphify-out/`.

- [ ] **Step 5: Inspect generated artifacts and worktree**

Run:

```bash
git status --short
```

Expected: only intentional source/docs files are modified. Do not stage `.fixthis/`, `build/reports/`, `graphify-out/`, Android build output, screenshots, or generated fixture workspaces.

- [ ] **Step 6: Commit final cleanup if needed**

If Task 5 changed only plan checkboxes or docs, commit them separately:

```bash
git add docs/superpowers/plans/2026-06-01-external-runtime-trust-expansion.md
git commit -m "docs: record external runtime trust execution"
```

Skip this commit if no source-controlled files changed in Task 5.

---

## Plan Self-Review

- **Spec coverage:** Track A is covered by Task 1; Track B is covered by Tasks 2 and 3; Track C is covered by Task 4; final verification and graph refresh are covered by Task 5.
- **Placeholder scan:** This plan intentionally contains no placeholder markers, deferred implementation wording, or unspecified test-writing steps.
- **Type consistency:** Runtime handoff fields are named `compactHandoff` and `preciseHandoff` in Kotlin observed output and JavaScript classifier expectations. Manifest fields are named `mustHandoffContain` and `mustNotHandoffContain`.
- **Scope check:** The plan is one coherent hardening line over existing evidence infrastructure. It does not add new package channels, runtime product behavior, or exact View/WebView targeting.
