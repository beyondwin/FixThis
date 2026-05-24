# Source Matching Runtime Trust Fixtures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in runtime source-matching fixture tier that installs a debug fixture app, captures a real screen, resolves a semantics target, and validates observed trust fields instead of downgrading them as unobserved.

**Architecture:** Keep `scripts/source-matching-fixtures.mjs` as the local fixture orchestrator and report writer. Add a repository-local `fixthis-mcp` JavaExec runner for runtime capture because `fixthis-mcp` already depends on `fixthis-cli` and owns `TargetEvidenceService`; putting this in `fixthis-cli` would create a dependency cycle. Source-index fixtures stay build-time-only, while `runtime-trust` fixtures use the same MCP/session evidence path that production handoffs use.

**Tech Stack:** Node.js 20 test runner, Gradle Kotlin DSL, Kotlin/JVM 21, Clikt-free repository-local JavaExec runner, kotlinx.serialization JSON, FixThis MCP session services, ADB bridge through `BridgeClient`.

---

## File Structure

- Modify `docs/superpowers/specs/2026-05-24-source-matching-runtime-trust-fixtures-design.md` to keep the runner location aligned with the actual Gradle dependency graph.
- Modify `package.json` to add `source-matching:fixtures:runtime`.
- Modify `fixtures/source-matching/manifest.json` to use the new local schema and split `source-index` from `runtime-trust` cases.
- Modify `scripts/source-matching-fixtures.mjs` to validate the new schema, patch runtime-on builds only for runtime runs, call the MCP JavaExec runner, and report runtime observations.
- Modify `scripts/source-matching-fixtures-test.mjs` to lock the schema split, runtime command, report semantics, and default-vs-strict behavior.
- Modify `fixthis-mcp/build.gradle.kts` to add a repository-local `runRuntimeTrustFixture` JavaExec task.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt` for input/output DTOs.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTargetResolver.kt` for stable semantics selector resolution.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunner.kt` for capture, evidence building, and observed JSON output.
- Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTargetResolverTest.kt`.
- Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt`.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt` with one synthetic runtime-path confidence/risk assertion.
- Modify `docs/guides/source-matching-fixture-lab.md` so the runtime command and source-index/runtime boundary are discoverable.

## Task 1: Contract Cleanup

**Files:**
- Modify: `scripts/source-matching-fixtures-test.mjs`
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `fixtures/source-matching/manifest.json`
- Modify: `package.json`

- [ ] **Step 1: Write failing package and manifest contract tests**

Add these tests near the existing package and manifest tests in `scripts/source-matching-fixtures-test.mjs`:

```javascript
test("package.json exposes runtime source matching fixture script", () => {
  const pkg = readJson("package.json");
  assert.equal(pkg.scripts["source-matching:fixtures:runtime"], "node scripts/source-matching-fixtures.mjs runtime");
});

test("fixture manifest uses schema v2 with separated source-index and runtime-trust cases", () => {
  const manifest = readJson("fixtures/source-matching/manifest.json");
  assert.equal(manifest.schemaVersion, 2);
  const cases = manifest.fixtures.flatMap((fixture) => fixture.cases);
  assert.ok(cases.some((entry) => entry.mode === "source-index"));
  assert.ok(cases.some((entry) => entry.mode === "runtime-trust"));
  for (const entry of cases.filter((testCase) => testCase.mode === "source-index")) {
    assert.equal(entry.expectedConfidence, undefined);
    assert.equal(entry.expectedSourceConfidence, undefined);
    assert.equal(entry.expectedRiskFlags, undefined);
    assert.equal(entry.mustWarn, undefined);
    assert.equal(entry.mustNotWarn, undefined);
    assert.equal(entry.mustNotHighConfidence, undefined);
    assert.equal(entry.runtimeTarget, undefined);
  }
});

test("validateManifest rejects runtime-only fields on source-index cases", () => {
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
          id: "bad-source-index-trust",
          mode: "source-index",
          expectedTop3PathContains: "ReplyListContent.kt",
          expectedConfidence: "medium-or-high",
        }],
      }],
    }),
    /bad-source-index-trust source-index case contains runtime-only field expectedConfidence/,
  );
});

test("validateManifest requires runtimeTarget on runtime-trust cases", () => {
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
          id: "missing-runtime-target",
          mode: "runtime-trust",
          expectedTop3PathContains: "ReplyListContent.kt",
          expectedConfidence: "medium-or-high",
        }],
      }],
    }),
    /missing-runtime-target runtime-trust case must define runtimeTarget/,
  );
});
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because `source-matching:fixtures:runtime` is missing, manifest schema is still `1`, and source-index cases currently accept runtime trust fields.

- [ ] **Step 3: Add runtime script**

Modify `package.json` scripts:

```json
"source-matching:fixtures:runtime": "node scripts/source-matching-fixtures.mjs runtime"
```

Keep the existing `prepare`, `run`, `report`, and `test` scripts unchanged.

- [ ] **Step 4: Update manifest schema and migrate cases**

In `fixtures/source-matching/manifest.json`:

- Change `schemaVersion` to `2`.
- Remove `expectedConfidence`, `expectedRiskFlags`, `mustWarn`, `mustNotWarn`, and `mustNotHighConfidence` from every `source-index` case.
- Add one initial runtime case to the existing `reply` fixture:

```json
{
  "id": "reply-compose-fab-runtime",
  "mode": "runtime-trust",
  "runtimeTarget": { "text": "Compose", "role": "Button" },
  "expectedTop3PathContains": "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt",
  "expectedConfidence": "medium-or-high",
  "expectedSourceConfidence": "medium-or-high",
  "mustNotWarn": ["POSSIBLE_VIEW_INTEROP"]
}
```

This target comes from the pinned Reply sample's initial single-pane FAB text in `ReplyListContent.kt`. Keep it as the first runtime contract so selector drift is visible as `target_not_found` instead of being masked by a fallback.

- [ ] **Step 5: Implement schema validation**

In `scripts/source-matching-fixtures.mjs`, replace the schema/version checks and case-mode validation with this structure:

```javascript
const manifestSchemaVersion = 2;
const sourceIndexCaseFields = new Set([
  "id",
  "mode",
  "expectedEntryPathContains",
  "expectedTop1PathContains",
  "expectedTop3PathContains",
  "expectedSignal",
]);
const runtimeOnlyCaseFields = new Set([
  "runtimeTarget",
  "navigation",
  "expectedConfidence",
  "expectedSourceConfidence",
  "expectedRiskFlags",
  "mustWarn",
  "mustNotWarn",
  "mustNotHighConfidence",
]);
const runtimeTargetFields = new Set(["text", "testTag", "contentDescription", "role"]);

function validateRuntimeTarget(target, label, errors) {
  if (!target || typeof target !== "object" || Array.isArray(target)) {
    errors.push(`${label} runtimeTarget must be an object`);
    return;
  }
  const keys = Object.keys(target);
  const supported = keys.filter((key) => runtimeTargetFields.has(key));
  if (supported.length === 0) {
    errors.push(`${label} runtimeTarget must include text, testTag, contentDescription, or role`);
  }
  for (const key of keys) {
    if (!runtimeTargetFields.has(key)) {
      errors.push(`${label} runtimeTarget contains unsupported field ${key}`);
    } else if (typeof target[key] !== "string" || target[key].length === 0) {
      errors.push(`${label} runtimeTarget.${key} must be a non-empty string`);
    }
  }
}
```

Inside `validateManifest(manifest)`:

```javascript
if (manifest?.schemaVersion !== manifestSchemaVersion) errors.push(`schemaVersion must be ${manifestSchemaVersion}`);
```

Inside each case validation:

```javascript
if (!["source-index", "runtime-trust"].includes(entry.mode)) {
  errors.push(`${entry.id || "case"} mode must be source-index or runtime-trust`);
}
if (entry.mode === "source-index") {
  for (const field of Object.keys(entry)) {
    if (runtimeOnlyCaseFields.has(field)) {
      errors.push(`${entry.id || "case"} source-index case contains runtime-only field ${field}`);
    } else if (!sourceIndexCaseFields.has(field)) {
      errors.push(`${entry.id || "case"} source-index case contains unsupported field ${field}`);
    }
  }
  if (!entry.expectedEntryPathContains && !entry.expectedTop1PathContains && !entry.expectedTop3PathContains) {
    errors.push(`${entry.id || "case"} must define expectedEntryPathContains, expectedTop1PathContains, or expectedTop3PathContains`);
  }
}
if (entry.mode === "runtime-trust") {
  validateRuntimeTarget(entry.runtimeTarget, entry.id || "case", errors);
  if (entry.expectedConfidence && !confidenceExpectations.has(entry.expectedConfidence)) {
    errors.push(`${entry.id} expectedConfidence is unsupported`);
  }
  if (entry.expectedSourceConfidence && !confidenceExpectations.has(entry.expectedSourceConfidence)) {
    errors.push(`${entry.id} expectedSourceConfidence is unsupported`);
  }
  validateAllowedValues(entry.expectedRiskFlags, sourceRiskFlags, `${entry.id || "case"} expectedRiskFlags`, errors);
  validateAllowedValues(entry.mustWarn, targetWarnings, `${entry.id || "case"} mustWarn`, errors);
  validateAllowedValues(entry.mustNotWarn, targetWarnings, `${entry.id || "case"} mustNotWarn`, errors);
  if (entry.mustNotHighConfidence !== undefined && typeof entry.mustNotHighConfidence !== "boolean") {
    errors.push(`${entry.id || "case"} mustNotHighConfidence must be boolean`);
  }
}
```

- [ ] **Step 6: Run tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS for the contract tests added in this task; later runtime/report tests may still be absent.

- [ ] **Step 7: Commit**

```bash
git add package.json fixtures/source-matching/manifest.json scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "feat: split source matching fixture contracts"
```

## Task 2: Report and Classifier Semantics

**Files:**
- Modify: `scripts/source-matching-fixtures-test.mjs`
- Modify: `scripts/source-matching-fixtures.mjs`

- [ ] **Step 1: Write failing report and classifier tests**

Append these tests near the existing `classifyCaseOutcome` and `buildFixtureReport` tests:

```javascript
test("classifyRuntimeTrustOutcome fails missing runtime observations", () => {
  assert.deepEqual(
    classifyRuntimeTrustOutcome({
      expectedTop3PathContains: "ReplyListContent.kt",
      expectedConfidence: "medium-or-high",
      expectedSourceConfidence: "medium-or-high",
      expectedRiskFlags: ["ARBITRARY_LITERAL"],
      mustWarn: ["LOW_SOURCE_CANDIDATE_MARGIN"],
    }, {
      candidates: [{ path: "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt" }],
    }).failures,
    [
      "missing_confidence_observation",
      "missing_source_confidence_observation",
      "missing_risk_observation",
      "missing_warning_observation",
    ],
  );
});

test("classifyRuntimeTrustOutcome validates target and source confidence separately", () => {
  const result = classifyRuntimeTrustOutcome({
    expectedTop3PathContains: "ReplyListContent.kt",
    expectedConfidence: "medium-or-high",
    expectedSourceConfidence: "low-or-medium",
    mustNotWarn: ["POSSIBLE_VIEW_INTEROP"],
  }, {
    candidates: [{ path: "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt" }],
    confidence: "high",
    sourceConfidence: "medium",
    warnings: [],
    riskFlags: [],
  });
  assert.deepEqual(result.failures, []);
  assert.deepEqual(result.metrics, ["top1_hit", "top3_hit", "confidence_calibrated", "source_confidence_calibrated"]);
});

test("buildFixtureReport splits source-index and runtime-trust summaries", () => {
  const report = buildFixtureReport([
    {
      fixtureId: "reply",
      mode: "mixed",
      status: "evaluated",
      cases: [
        { caseId: "source", mode: "source-index", metrics: ["top3_hit"], failures: [], environment: [] },
        { caseId: "runtime", mode: "runtime-trust", metrics: [], failures: [], environment: ["device_unavailable"] },
      ],
    },
  ], "2026-05-24T00:00:00.000Z");
  assert.equal(report.status, "pass_with_environment_downgrade");
  assert.equal(report.summary.sourceIndexCases, 1);
  assert.equal(report.summary.runtimeTrustCases, 1);
  assert.equal(report.summary.environmentCases, 1);
});
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because `classifyRuntimeTrustOutcome` is not exported and report summaries do not split source-index/runtime counts.

- [ ] **Step 3: Add runtime classifier**

In `scripts/source-matching-fixtures.mjs`, export a new function next to `classifyCaseOutcome`:

```javascript
export function classifyRuntimeTrustOutcome(expectation, observed) {
  const outcome = classifyCaseOutcome(expectation, observed);
  if (expectation.expectedConfidence && !hasOwn(observed, "confidence")) {
    removeLabel(outcome.environment, trustObservationNotConfigured);
    addUnique(outcome.failures, "missing_confidence_observation");
  }
  if (expectation.expectedSourceConfidence) {
    if (!hasOwn(observed, "sourceConfidence")) {
      addUnique(outcome.failures, "missing_source_confidence_observation");
    } else if (confidenceMatches(expectation.expectedSourceConfidence, observed.sourceConfidence)) {
      addUnique(outcome.metrics, "source_confidence_calibrated");
    } else {
      addUnique(outcome.failures, confidenceMismatchFailure(expectation.expectedSourceConfidence, observed.sourceConfidence));
    }
  }
  if ((expectation.expectedRiskFlags || []).length > 0 && !hasOwn(observed, "riskFlags")) {
    removeLabel(outcome.environment, trustObservationNotConfigured);
    addUnique(outcome.failures, "missing_risk_observation");
  }
  if (((expectation.mustWarn || []).length > 0 || (expectation.mustNotWarn || []).length > 0) && !hasOwn(observed, "warnings")) {
    removeLabel(outcome.environment, trustObservationNotConfigured);
    addUnique(outcome.failures, "missing_warning_observation");
  }
  if (expectation.mustNotHighConfidence === true && !hasOwn(observed, "confidence")) {
    removeLabel(outcome.environment, trustObservationNotConfigured);
    addUnique(outcome.failures, "missing_confidence_observation");
  }
  return outcome;
}

function removeLabel(values, label) {
  const index = values.indexOf(label);
  if (index >= 0) values.splice(index, 1);
}
```

Keep `classifyCaseOutcome` for source-index and existing unit coverage, but do not call it directly for runtime-trust results except through `classifyRuntimeTrustOutcome`.

- [ ] **Step 4: Split report summary counts**

Update `buildFixtureReport`:

```javascript
summary: {
  totalCases: caseResults.length,
  sourceIndexCases: caseResults.filter((testCase) => testCase.mode === "source-index").length,
  runtimeTrustCases: caseResults.filter((testCase) => testCase.mode === "runtime-trust").length,
  failedCases: caseResults.filter((testCase) => (testCase.failures || []).length > 0).length,
  environmentCases: caseResults.filter((testCase) => (testCase.environment || []).length > 0).length,
  failureCounts: countLabels(caseResults, "failures"),
  environmentCounts: countLabels(caseResults, "environment"),
},
```

Update `markdownReport(report)` summary lines:

```javascript
lines.push(`- Source-index cases: ${report.summary.sourceIndexCases}`);
lines.push(`- Runtime-trust cases: ${report.summary.runtimeTrustCases}`);
```

- [ ] **Step 5: Preserve source-index observed shape**

In `evaluateSourceIndexCase`, ensure returned cases include `mode: "source-index"` and never forward runtime-only expectations because Task 1 validation rejects them:

```javascript
return {
  caseId: testCase.id,
  mode: "source-index",
  metrics: outcome.metrics,
  failures: outcome.failures,
  environment: outcome.environment,
  observed: { candidates: observed.candidates },
};
```

- [ ] **Step 6: Run tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "feat: classify runtime trust fixture results"
```

## Task 3: No-Device Runtime Contracts

**Files:**
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculatorTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt`

- [ ] **Step 1: Add SourceMatcher synthetic runtime regression test**

Append to `SourceMatcherTest`:

```kotlin
@Test
fun runtimeLikeWeakLiteralTargetDoesNotBecomeHighConfidence() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/ui/ReplyListContent.kt",
                    line = 95,
                    text = listOf("Compose"),
                    signals = listOf(
                        SourceSignal(
                            kind = SourceSignalKind.ARBITRARY_STRING_LITERAL,
                            value = "Compose",
                            confidenceWeight = 1.0,
                        ),
                    ),
                ),
            ),
        ),
    )

    val top = matcher.match(
        selectedNode = node(uid = "compose-fab", text = listOf("Compose"), role = "Button"),
        nearbyNodes = emptyList(),
        activityName = "com.example.reply.ui.MainActivity",
    ).single()

    assertEquals(SelectionConfidence.LOW, top.confidence)
    assertTrue(top.riskFlags.contains(SourceCandidateRisk.ARBITRARY_LITERAL))
    assertFalse(top.evidenceStrength == SourceEvidenceStrength.STRONG)
}
```

- [ ] **Step 2: Add TargetReliability defensive test**

Append to `TargetReliabilityCalculatorTest`:

```kotlin
@Test
fun highRiskSourceCandidateCannotCreateHighTargetReliability() {
    val result = TargetReliabilityCalculator.calculate(
        TargetReliabilityInput(
            targetKind = TargetKind.NODE,
            selectedNode = meaningfulNode(),
            sourceCandidates = listOf(
                SourceCandidate(
                    file = "sample/src/main/java/ReplyListContent.kt",
                    line = 95,
                    score = 0.95,
                    confidence = SelectionConfidence.HIGH,
                    scoreMargin = 0.02,
                    riskFlags = listOf(SourceCandidateRisk.AMBIGUOUS),
                ),
            ),
            targetEvidence = TargetEvidence(
                identityHint = IdentityHint(
                    composableNameHint = "ReplyInboxScreen",
                    source = IdentityHintSource.TEST_TAG_CONVENTION,
                    confidence = IdentityHintConfidence.HIGH,
                ),
                evidenceQuality = EvidenceQuality.STRUCTURED,
            ),
            screenFingerprintAvailable = true,
        ),
    )

    assertEquals(TargetConfidence.LOW, result.confidence)
    assertTrue(result.warnings.contains(TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN))
}
```

- [ ] **Step 3: Add TargetEvidenceService synthetic runtime path test**

Append to `TargetEvidenceServiceTest`:

```kotlin
@Test
fun buildFeedbackItemProducesRuntimeTrustFieldsFromSyntheticSnapshotAndSourceIndex() {
    val service = targetEvidenceService()
    val selected = node(uid = "compose-fab", text = listOf("Compose"), role = "Button")
    val screen = screenWith(selected)
    val sourceIndex = SourceIndex(
        entries = listOf(
            SourceIndexEntry(
                file = "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt",
                line = 95,
                text = listOf("Compose"),
                signals = listOf(SourceSignal(SourceSignalKind.UI_TEXT, "Compose")),
            ),
        ),
    )

    val item = service.buildFeedbackItem(
        screen = screen,
        sourceIndex = sourceIndex,
        targetType = FeedbackTargetType.NODE,
        bounds = selected.boundsInWindow,
        nodeUid = selected.uid,
        comment = "Verify runtime trust",
        allowBlankComment = false,
        writtenStatus = AnnotationStatusDto.OPEN,
    )

    assertEquals(TargetConfidence.MEDIUM, item.targetReliability?.confidence)
    assertTrue(item.sourceCandidates.isNotEmpty())
    assertEquals(SelectionConfidence.MEDIUM, item.sourceCandidates.first().confidence)
}
```

Also add missing imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.source.SourceSignal
import io.github.beyondwin.fixthis.compose.core.source.SourceSignalKind
```

- [ ] **Step 4: Run focused tests to verify failures or pass**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --tests "*TargetReliabilityCalculatorTest" --no-daemon
./gradlew :fixthis-mcp:test --tests "*TargetEvidenceServiceTest" --no-daemon
```

Expected: PASS. If a new assertion fails, stop in this task and adjust the minimal production policy needed so risky runtime-like evidence cannot produce high trust before committing.

- [ ] **Step 5: Commit**

```bash
git add fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculatorTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt
git commit -m "test: cover runtime trust contracts without device"
```

## Task 4: Runtime Target Resolver and Observed JSON Mapping

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTargetResolver.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTargetResolverTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt`

- [ ] **Step 1: Write resolver tests**

Create `RuntimeTargetResolverTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.SnapshotRootDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeTargetResolverTest {
    @Test
    fun resolvesByTextAndRole() {
        val screen = screenWith(
            node(uid = "compose", text = listOf("Compose"), role = "Button"),
            node(uid = "search", text = listOf("Search"), role = "Button"),
        )

        val resolved = RuntimeTargetResolver.resolve(screen, RuntimeTargetSelector(text = "Compose", role = "Button"))

        assertEquals("compose", resolved.uid)
    }

    @Test
    fun rejectsAmbiguousSelectors() {
        val screen = screenWith(
            node(uid = "compose-1", text = listOf("Compose"), role = "Button"),
            node(uid = "compose-2", text = listOf("Compose"), role = "Button"),
        )

        val error = assertFailsWith<RuntimeTargetResolutionException> {
            RuntimeTargetResolver.resolve(screen, RuntimeTargetSelector(text = "Compose", role = "Button"))
        }

        assertEquals("target_ambiguous", error.code)
    }

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 1L,
        displayName = "Runtime fixture",
        roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 400f, 800f), mergedNodes = nodes.toList())),
    )

    private fun node(uid: String, text: List<String> = emptyList(), role: String? = null): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(10f, 20f, 120f, 80f),
        text = text,
        role = role,
    )
}
```

- [ ] **Step 2: Add models**

Create `RuntimeTrustFixtureModels.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.fixture

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeTrustFixtureInput(
    val projectDir: String,
    val packageName: String,
    val cases: List<RuntimeTrustCaseInput>,
    val strict: Boolean = false,
)

@Serializable
data class RuntimeTrustCaseInput(
    val caseId: String,
    val runtimeTarget: RuntimeTargetSelector,
)

@Serializable
data class RuntimeTargetSelector(
    val text: String? = null,
    val testTag: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
)

@Serializable
data class RuntimeTrustFixtureOutput(
    val schemaVersion: Int = 1,
    val status: String,
    val cases: List<RuntimeTrustCaseOutput>,
)

@Serializable
data class RuntimeTrustCaseOutput(
    val caseId: String,
    val observed: RuntimeTrustObserved? = null,
    val failures: List<String> = emptyList(),
    val environment: List<String> = emptyList(),
)

@Serializable
data class RuntimeTrustObserved(
    val candidates: List<RuntimeTrustCandidate> = emptyList(),
    val confidence: String? = null,
    val sourceConfidence: String? = null,
    val riskFlags: List<String>? = null,
    val warnings: List<String>? = null,
)

@Serializable
data class RuntimeTrustCandidate(
    val path: String,
    val line: Int? = null,
    val confidence: String? = null,
    val riskFlags: List<String> = emptyList(),
)
```

- [ ] **Step 3: Add resolver implementation**

Create `RuntimeTargetResolver.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.mcp.session.SnapshotDto

class RuntimeTargetResolutionException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

object RuntimeTargetResolver {
    fun resolve(screen: SnapshotDto, selector: RuntimeTargetSelector): FixThisNode {
        val matches = screen.roots
            .flatMap { it.mergedNodes }
            .filter { node -> selector.matches(node) }
            .sortedWith(compareBy<FixThisNode> { it.rootIndex }.thenBy { it.boundsInWindow.top }.thenBy { it.boundsInWindow.left }.thenBy { it.uid })

        return when (matches.size) {
            0 -> throw RuntimeTargetResolutionException("target_not_found", "No runtime target matched selector $selector")
            1 -> matches.single()
            else -> throw RuntimeTargetResolutionException("target_ambiguous", "Runtime target selector matched ${matches.size} nodes: $selector")
        }
    }

    private fun RuntimeTargetSelector.matches(node: FixThisNode): Boolean =
        text.matchesValue(node.text) &&
            testTag.matchesNullable(node.testTag) &&
            contentDescription.matchesValue(node.contentDescription) &&
            role.matchesNullable(node.role)

    private fun String?.matchesValue(values: List<String>): Boolean =
        this == null || values.any { value -> value == this }

    private fun String?.matchesNullable(value: String?): Boolean =
        this == null || value == this
}
```

- [ ] **Step 4: Add observed mapper test and implementation**

Create `RuntimeTrustObservationMapperTest.kt` with:

```kotlin
package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.session.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeTrustObservationMapperTest {
    @Test
    fun mapsAnnotationTrustFieldsToObservedOutput() {
        val observed = RuntimeTrustObservationMapper.fromAnnotation(
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                status = AnnotationStatusDto.OPEN,
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "Reply/app/src/main/java/com/example/reply/ui/ReplyListContent.kt",
                        line = 95,
                        confidence = SelectionConfidence.MEDIUM,
                        riskFlags = listOf(SourceCandidateRisk.ARBITRARY_LITERAL),
                    ),
                ),
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.MEDIUM,
                    warnings = listOf(TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN),
                ),
            ),
        )

        assertEquals("medium", observed.confidence)
        assertEquals("medium", observed.sourceConfidence)
        assertEquals(listOf("ARBITRARY_LITERAL"), observed.riskFlags)
        assertEquals(listOf("LOW_SOURCE_CANDIDATE_MARGIN"), observed.warnings)
    }
}
```

Append this mapper to `RuntimeTrustFixtureModels.kt`:

```kotlin
object RuntimeTrustObservationMapper {
    fun fromAnnotation(item: io.github.beyondwin.fixthis.mcp.session.AnnotationDto): RuntimeTrustObserved {
        val top = item.sourceCandidates.firstOrNull()
        return RuntimeTrustObserved(
            candidates = item.sourceCandidates.take(3).map { candidate ->
                RuntimeTrustCandidate(
                    path = candidate.repoFile ?: candidate.file,
                    line = candidate.line,
                    confidence = candidate.confidence.name.lowercase(),
                    riskFlags = candidate.riskFlags.map { it.name },
                )
            },
            confidence = item.targetReliability?.confidence?.name?.lowercase(),
            sourceConfidence = top?.confidence?.name?.lowercase(),
            riskFlags = top?.riskFlags?.map { it.name },
            warnings = item.targetReliability?.warnings?.map { it.name },
        )
    }
}
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTargetResolverTest" --tests "*RuntimeTrustObservationMapperTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture
git commit -m "feat: add runtime trust fixture mapping"
```

## Task 5: MCP Runtime Fixture Runner

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunner.kt`
- Modify: `fixthis-mcp/build.gradle.kts`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunnerTest.kt`

- [ ] **Step 1: Add JavaExec task**

Modify `fixthis-mcp/build.gradle.kts` after the existing `application` block:

```kotlin
tasks.register<JavaExec>("runRuntimeTrustFixture") {
    group = "verification"
    description = "Runs repository-local runtime source matching trust fixture capture."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.beyondwin.fixthis.mcp.fixture.RuntimeTrustFixtureRunnerKt")
}
```

- [ ] **Step 2: Add runner argument parser test**

Create `RuntimeTrustFixtureRunnerTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.fixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeTrustFixtureRunnerTest {
    @Test
    fun parsesInputOutputAndStrictArgs() {
        val options = RuntimeTrustFixtureRunnerOptions.parse(
            arrayOf("--input", "/tmp/input.json", "--output", "/tmp/output.json", "--strict"),
        )

        assertEquals("/tmp/input.json", options.inputPath)
        assertEquals("/tmp/output.json", options.outputPath)
        assertEquals(true, options.strict)
    }

    @Test
    fun rejectsMissingOutputArg() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeTrustFixtureRunnerOptions.parse(arrayOf("--input", "/tmp/input.json"))
        }
    }
}
```

- [ ] **Step 3: Add runner implementation**

Create `RuntimeTrustFixtureRunner.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.cli.BridgeClient
import io.github.beyondwin.fixthis.mcp.McpProtocol
import io.github.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.tools.CliFixThisBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import kotlin.system.exitProcess

data class RuntimeTrustFixtureRunnerOptions(
    val inputPath: String,
    val outputPath: String,
    val strict: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): RuntimeTrustFixtureRunnerOptions {
            val values = args.toList()
            fun valueAfter(name: String): String {
                val index = values.indexOf(name)
                require(index >= 0 && index + 1 < values.size) { "$name is required" }
                return values[index + 1]
            }
            return RuntimeTrustFixtureRunnerOptions(
                inputPath = valueAfter("--input"),
                outputPath = valueAfter("--output"),
                strict = values.contains("--strict"),
            )
        }
    }
}

fun main(args: Array<String>) {
    val options = runCatching { RuntimeTrustFixtureRunnerOptions.parse(args) }
        .getOrElse { error ->
            System.err.println(error.message ?: "invalid arguments")
            exitProcess(2)
        }
    val inputFile = File(options.inputPath).canonicalFile
    val outputFile = File(options.outputPath).canonicalFile
    val input = inputFile.inputStream().use {
        McpProtocol.json.decodeFromStream<RuntimeTrustFixtureInput>(it)
    }.copy(strict = options.strict)

    val output = RuntimeTrustFixtureRunner().run(input)
    outputFile.parentFile.mkdirs()
    outputFile.outputStream().use {
        McpProtocol.json.encodeToStream(RuntimeTrustFixtureOutput.serializer(), output, it)
    }
    if (output.status == "fail" || (input.strict && output.cases.any { it.environment.isNotEmpty() })) {
        exitProcess(1)
    }
}

class RuntimeTrustFixtureRunner {
    fun run(input: RuntimeTrustFixtureInput): RuntimeTrustFixtureOutput = runBlocking {
        val projectRoot = File(input.projectDir).canonicalFile
        val bridge = CliFixThisBridge(BridgeClient(projectRoot = projectRoot))
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(),
            projectRoot = projectRoot.absolutePath,
            defaultPackageName = input.packageName,
        )
        val session = service.openSession(input.packageName, newSession = true)
        val screen = try {
            service.captureScreen(session.sessionId)
        } catch (error: Throwable) {
            return@runBlocking RuntimeTrustFixtureOutput(
                status = if (input.strict) "fail" else "pass_with_environment_downgrade",
                cases = input.cases.map {
                    RuntimeTrustCaseOutput(caseId = it.caseId, environment = listOf("capture_failed"))
                },
            )
        }

        val cases = input.cases.map { testCase ->
            try {
                val node = RuntimeTargetResolver.resolve(screen, testCase.runtimeTarget)
                val item = service.addFeedbackItem(
                    sessionId = session.sessionId,
                    screenId = screen.screenId,
                    targetType = io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType.NODE,
                    bounds = node.boundsInWindow,
                    nodeUid = node.uid,
                    comment = "Runtime trust fixture ${testCase.caseId}",
                )
                RuntimeTrustCaseOutput(
                    caseId = testCase.caseId,
                    observed = RuntimeTrustObservationMapper.fromAnnotation(item),
                )
            } catch (error: RuntimeTargetResolutionException) {
                RuntimeTrustCaseOutput(caseId = testCase.caseId, failures = listOf(error.code))
            }
        }
        RuntimeTrustFixtureOutput(
            status = if (cases.any { it.failures.isNotEmpty() }) "fail" else "evaluated",
            cases = cases,
        )
    }
}
```

- [ ] **Step 4: Run runner tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustFixtureRunnerTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/build.gradle.kts \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunner.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureRunnerTest.kt
git commit -m "feat: add runtime trust fixture runner"
```

## Task 6: JavaScript Runtime Orchestration

**Files:**
- Modify: `scripts/source-matching-fixtures-test.mjs`
- Modify: `scripts/source-matching-fixtures.mjs`

- [ ] **Step 1: Write failing JS orchestration tests**

Append:

```javascript
test("patchAppBuildFileText can enable debug runtime for runtime fixtures", () => {
  const original = ["plugins {", "    alias(libs.plugins.android.application)", "}", ""].join("\n");
  const patched = patchAppBuildFileText(original, { addDebugRuntime: true });
  assert.match(patched, /addDebugRuntime\.set\(true\)/);
  assert.equal(patchAppBuildFileText(patched, { addDebugRuntime: true }), patched);
});

test("runtimeFixtureInput contains only runtime-trust cases", () => {
  const input = runtimeFixtureInput({
    applicationId: "com.example.reply",
    cases: [
      { id: "source", mode: "source-index" },
      { id: "runtime", mode: "runtime-trust", runtimeTarget: { text: "Compose" } },
    ],
  }, "/tmp/reply", false);

  assert.equal(input.packageName, "com.example.reply");
  assert.equal(input.projectDir, "/tmp/reply");
  assert.deepEqual(input.cases, [{ caseId: "runtime", runtimeTarget: { text: "Compose" } }]);
});
```

Add `runtimeFixtureInput` to the import list.

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because `patchAppBuildFileText` has no option and `runtimeFixtureInput` is missing.

- [ ] **Step 3: Make Gradle patch configurable**

Change the function signature and config block in `scripts/source-matching-fixtures.mjs`:

```javascript
export function patchAppBuildFileText(text, options = {}) {
  const addDebugRuntime = options.addDebugRuntime === true;
  const pluginLine = "    id(\"io.github.beyondwin.fixthis.compose\")";
  const configBlock = [
    "",
    "fixthis {",
    `    addDebugRuntime.set(${addDebugRuntime ? "true" : "false"})`,
    "    generateSourceIndex.set(true)",
    "    generateProjectMetadata.set(true)",
    "}",
    "",
  ].join("\n");
  let next = text;
  if (!next.includes("io.github.beyondwin.fixthis.compose")) {
    const pluginsMatch = next.match(/plugins\s*\{/);
    if (!pluginsMatch) throw new Error("Could not find plugins block in app build file");
    const insertAt = next.indexOf("\n", pluginsMatch.index + pluginsMatch[0].length);
    next = `${next.slice(0, insertAt + 1)}${pluginLine}\n${next.slice(insertAt + 1)}`;
  }
  if (next.includes("addDebugRuntime.set(")) {
    next = next.replace(/addDebugRuntime\.set\((true|false)\)/, `addDebugRuntime.set(${addDebugRuntime ? "true" : "false"})`);
  } else {
    next = `${next.trimEnd()}\n${configBlock}`;
  }
  return next;
}
```

Update `prepareFixture(fixture, options = {})` so it passes the runtime flag:

```javascript
writeFileSync(appBuildPath, patchAppBuildFileText(
  readFileSync(appBuildPath, "utf8"),
  { addDebugRuntime: options.addDebugRuntime === true },
));
```

- [ ] **Step 4: Add runtime input and runner call helpers**

Add:

```javascript
export function runtimeFixtureInput(fixture, projectDir, strict = false) {
  return {
    projectDir,
    packageName: fixture.applicationId,
    strict,
    cases: (fixture.cases || [])
      .filter((testCase) => testCase.mode === "runtime-trust")
      .map((testCase) => ({
        caseId: testCase.id,
        runtimeTarget: testCase.runtimeTarget,
      })),
  };
}

function hasRuntimeCases(fixture) {
  return (fixture.cases || []).some((testCase) => testCase.mode === "runtime-trust");
}
```

- [ ] **Step 5: Add runtime evaluation**

Add:

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
          metrics: [],
          failures: ["missing_runtime_case_output"],
          environment: [],
        };
      }
      if (captured.failures?.length || captured.environment?.length) {
        return {
          caseId: testCase.id,
          mode: "runtime-trust",
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

Add `runRuntimeTrustEvaluation(fixture, options = {})` that prepares with runtime on, runs install, writes input/output JSON, invokes the MCP JavaExec task, and evaluates the runner output:

```javascript
export function runRuntimeTrustEvaluation(fixture, options = {}) {
  const paths = prepareFixture(fixture, { stdio: "inherit", addDebugRuntime: true });
  const strict = options.strict === true;
  runCommand("./gradlew", [sourceIndexTaskPath(fixture), "--no-daemon"], { cwd: paths.projectWorkDir, stdio: "inherit" });
  const installTask = `${fixture.modulePath}:install${variantTaskSuffix(fixture.variant)}`;
  runCommand("./gradlew", [installTask, "--no-daemon"], { cwd: paths.projectWorkDir, stdio: "inherit" });

  const inputPath = join(defaultReportDir, `${fixture.id}-runtime-input.json`);
  const outputPath = join(defaultReportDir, `${fixture.id}-runtime-output.json`);
  writeJson(inputPath, runtimeFixtureInput(fixture, paths.projectWorkDir, strict));
  const args = [
    ":fixthis-mcp:runRuntimeTrustFixture",
    "--args",
    `--input ${inputPath} --output ${outputPath}${strict ? " --strict" : ""}`,
    "--no-daemon",
  ];
  runCommand("./gradlew", args, { cwd: repoRoot, stdio: "pipe" });
  const runnerOutput = JSON.parse(readFileSync(outputPath, "utf8"));
  return evaluateRuntimeTrustFixture(fixture, runnerOutput);
}
```

The Kotlin runner exits `0` for default environment downgrades and exits non-zero only for strict failures, so `runCommand` exceptions map cleanly to strict-mode failures.

- [ ] **Step 6: Wire `runtime` command**

Update `usage()`:

```javascript
return "Usage: node scripts/source-matching-fixtures.mjs <prepare|run|runtime|report> [--strict]";
```

Update command validation:

```javascript
if (!command || !["prepare", "run", "runtime", "report"].includes(command)) {
```

Add in `main(argv)` before `report`:

```javascript
if (command === "runtime") {
  const strict = argv.includes("--strict");
  const fixtures = [];
  for (const fixture of manifest.fixtures.filter(hasRuntimeCases)) {
    try {
      fixtures.push(runRuntimeTrustEvaluation(fixture, { strict }));
    } catch (error) {
      fixtures.push({
        fixtureId: fixture.id,
        mode: "runtime-trust",
        status: strict ? "fail" : "environment_downgrade",
        error: error.message,
        cases: fixture.cases
          .filter((testCase) => testCase.mode === "runtime-trust")
          .map((testCase) => ({
            caseId: testCase.id,
            mode: "runtime-trust",
            metrics: [],
            failures: strict ? ["capture_failed"] : [],
            environment: strict ? [] : ["capture_failed"],
          })),
      });
    }
  }
  const report = writeFixtureReport(fixtures);
  return report.status === "fail" ? 1 : 0;
}
```

- [ ] **Step 7: Run JS tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "feat: orchestrate runtime source matching fixtures"
```

## Task 7: Documentation and Final Verification

**Files:**
- Modify: `docs/guides/source-matching-fixture-lab.md`
- Modify: `docs/superpowers/specs/2026-05-24-source-matching-runtime-trust-fixtures-design.md` if implementation discovered any final terminology correction

- [ ] **Step 1: Update guide**

In `docs/guides/source-matching-fixture-lab.md`, update the command section to include:

````markdown
Run device-backed runtime trust evaluation:

```bash
npm run source-matching:fixtures:runtime
```

Run runtime trust evaluation as a strict local gate:

```bash
npm run source-matching:fixtures:runtime -- --strict
```
````

Update the result labels so `trust_observation_not_configured` is described as removed from the new schema, and add:

```markdown
- `target_not_found`: runtime selector did not match a captured semantics node.
- `target_ambiguous`: runtime selector matched more than one captured semantics node.
- `missing_confidence_observation`: runtime target reliability confidence was absent.
- `missing_source_confidence_observation`: runtime top source candidate confidence was absent.
- `missing_risk_observation`: runtime source risk flags were absent.
- `missing_warning_observation`: runtime target reliability warnings were absent.
```

- [ ] **Step 2: Run documentation contract tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS, including source-matching fixture guide checks.

- [ ] **Step 3: Run focused Kotlin tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --tests "*TargetReliabilityCalculatorTest" --no-daemon
./gradlew :fixthis-mcp:test --tests "*TargetEvidenceServiceTest" --tests "*RuntimeTargetResolverTest" --tests "*RuntimeTrustObservationMapperTest" --tests "*RuntimeTrustFixtureRunnerTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 4: Run formatting and diff checks**

Run:

```bash
./gradlew spotlessCheck --no-daemon
git diff --check
```

Expected: PASS.

- [ ] **Step 5: Run graphify update**

Run:

```bash
graphify update .
```

Expected: command completes or reports a non-blocking graphify issue. Do not stage `graphify-out/`.

- [ ] **Step 6: Optional runtime verification**

If an emulator or device is connected and the local Android SDK is ready, run:

```bash
npm run source-matching:fixtures:runtime
```

Expected with device: report includes the initial `runtime-trust` case and validates observed trust fields.
Expected without device: report status is `pass_with_environment_downgrade`.

Then run strict only when a device is intentionally available:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

Expected with device: PASS.
Expected without device: non-zero exit and visible device/capture failure.

- [ ] **Step 7: Commit docs and final cleanup**

```bash
git status --short
git add docs/guides/source-matching-fixture-lab.md docs/superpowers/specs/2026-05-24-source-matching-runtime-trust-fixtures-design.md
git commit -m "docs: explain runtime source matching fixtures"
```

Only stage files that actually changed. Do not stage `.fixthis/`, `build/reports/fixthis-source-matching/`, fixture workspaces, or `graphify-out/`.

## Final Verification Checklist

Run these before claiming implementation complete:

```bash
npm run source-matching:fixtures:test
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --tests "*TargetReliabilityCalculatorTest" --no-daemon
./gradlew :fixthis-mcp:test --tests "*TargetEvidenceServiceTest" --tests "*RuntimeTargetResolverTest" --tests "*RuntimeTrustObservationMapperTest" --tests "*RuntimeTrustFixtureRunnerTest" --no-daemon
./gradlew spotlessCheck --no-daemon
git diff --check
graphify update .
git status --short --branch
```

Optional when a device is available:

```bash
npm run source-matching:fixtures:runtime
npm run source-matching:fixtures:runtime -- --strict
```

If optional runtime strict cannot run because no device is connected, report that as an environment limitation, not as a silent skip.
