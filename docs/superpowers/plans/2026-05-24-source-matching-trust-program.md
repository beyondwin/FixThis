# Source Matching Trust Program Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a phased trust program that improves source matching evaluation, agent handoff clarity, and focused Compose matcher coverage without increasing false confidence.

**Architecture:** First extend the local fixture lab's trust classifier and report schema, then make MCP handoffs clearer about target/source confidence, then add a narrow `Layout`/`SubcomposeLayout` pattern with confidence caps and tests. The core layer stays pure Kotlin and unaware of MCP, CLI, Android UI, `.fixthis/`, or local fixture paths.

**Tech Stack:** Node.js 20 ESM, `node:test`, Kotlin/JUnit, kotlinx serialization, Gradle plugin source scanning, FixThis MCP session formatting.

---

## File Structure

- Modify `scripts/source-matching-fixtures-test.mjs`
  - Adds fast offline tests for classifier labels, manifest trust fields, report summaries, and source-index case evaluation.
- Modify `scripts/source-matching-fixtures.mjs`
  - Extends manifest validation, case classification, report JSON, and Markdown summaries.
- Modify `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
  - Adds confidence cap and layout-owner source matching coverage.
- Modify `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculatorTest.kt`
  - Adds source-risk-to-target-confidence tests.
- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
  - Lets strict composable tags match owner-function entries and keeps weak owner matches capped.
- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`
  - Adds a medium-strength owner-function match reason.
- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt`
  - Treats owner-function matches as selected medium evidence, not strong evidence.
- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
  - Adds source-index signal enum values for layout renderer calls.
- Modify `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt`
  - Adds scanner tests for `Layout` and `SubcomposeLayout` call-site signals.
- Modify `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt`
  - Adds matching source-index asset enum values.
- Modify `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt`
  - Adds regex helpers for `Layout` and `SubcomposeLayout` calls.
- Modify `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt`
  - Emits typed layout renderer signals with owner metadata.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt`
  - Adds precise Markdown tests for confidence action wording and unknown fallback.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CopyPromptEditSurfaceRendererTest.kt`
  - Adds compact Markdown tests for source/edit-surface separation and verify-first warnings.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
  - Renders confidence as action guidance without changing JSON fields.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
  - Renders compact verify-first guidance beside confidence/warnings.
- Modify `docs/reference/source-matching.md`
  - Documents trust classifier behavior and layout renderer source-index signals.
- Modify `docs/reference/output-schema.md`
  - Documents additive handoff wording, not new persisted field names.
- Modify `docs/guides/source-matching-fixture-lab.md`
  - Documents new report labels and local-only status.

---

### Task 1: Expand Fixture Trust Classifier Contracts

**Files:**
- Modify: `scripts/source-matching-fixtures-test.mjs`
- Modify: `scripts/source-matching-fixtures.mjs`

- [ ] **Step 1: Add failing tests for trust fields and classifier labels**

Append these tests near the existing `classifyCaseOutcome` and `validateManifest` tests in `scripts/source-matching-fixtures-test.mjs`:

```js
test("validateManifest accepts trust calibration fields and rejects unsupported risk flags", () => {
  assert.doesNotThrow(() => validateManifest({
    schemaVersion: 1,
    fixtures: [{
      id: "trusty",
      repo: "https://github.com/android/compose-samples.git",
      commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
      projectDir: "Reply",
      modulePath: ":app",
      variant: "debug",
      applicationId: "com.example.reply",
      cases: [{
        id: "trust-case",
        mode: "source-index",
        expectedTop1PathContains: "ReplyApp.kt",
        expectedTop3PathContains: ["ReplyApp.kt", "ReplyList.kt"],
        expectedConfidence: "medium-or-high",
        expectedRiskFlags: ["AMBIGUOUS"],
        mustWarn: ["LOW_SOURCE_CANDIDATE_MARGIN"],
        mustNotWarn: ["POSSIBLE_VIEW_INTEROP"],
        mustNotHighConfidence: true,
      }],
    }],
  }));

  assert.throws(
    () => validateManifest({
      schemaVersion: 1,
      fixtures: [{
        id: "bad-risk",
        repo: "https://github.com/android/compose-samples.git",
        commit: "d3ff757b289f7036815978a8f7b16706ee3423b0",
        projectDir: "Reply",
        modulePath: ":app",
        variant: "debug",
        applicationId: "com.example.reply",
        cases: [{
          id: "bad-risk-case",
          mode: "source-index",
          expectedTop3PathContains: "ReplyApp.kt",
          expectedRiskFlags: ["NOT_A_REAL_RISK"],
        }],
      }],
    }),
    /bad-risk-case expectedRiskFlags contains unsupported value NOT_A_REAL_RISK/,
  );
});

test("classifyCaseOutcome differentiates confidence and risk regressions", () => {
  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop1PathContains: "Home.kt",
      expectedTop3PathContains: "Home.kt",
      expectedConfidence: "medium-or-high",
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      confidence: "low",
      warnings: [],
      riskFlags: [],
    }).failures,
    ["underconfident"],
  );

  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: "Home.kt",
      expectedRiskFlags: ["ARBITRARY_LITERAL"],
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      confidence: "medium",
      warnings: [],
      riskFlags: [],
    }).failures,
    ["missing_risk_flag"],
  );

  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: "Home.kt",
      mustNotHighConfidence: true,
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      confidence: "high",
      warnings: [],
      riskFlags: ["ARBITRARY_LITERAL"],
    }).failures,
    ["unexpected_high_confidence", "weak_evidence_promoted"],
  );

  assert.deepEqual(
    classifyCaseOutcome({
      expectedTop3PathContains: "Home.kt",
      expectedRiskFlags: ["ARBITRARY_LITERAL"],
      mustWarn: ["LOW_SOURCE_CANDIDATE_MARGIN"],
      mustNotHighConfidence: true,
    }, {
      candidates: [{ path: "sample/Home.kt" }],
      confidence: "medium",
      warnings: ["LOW_SOURCE_CANDIDATE_MARGIN"],
      riskFlags: ["ARBITRARY_LITERAL"],
    }).metrics,
    ["top1_hit", "top3_hit", "risk_flag_present", "warning_present", "high_confidence_avoided"],
  );
});
```

- [ ] **Step 2: Run the tests and verify the new cases fail**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL with messages showing unsupported manifest fields are ignored or classifier failures do not match `underconfident`, `missing_risk_flag`, `unexpected_high_confidence`, or `weak_evidence_promoted`.

- [ ] **Step 3: Add manifest validation constants**

In `scripts/source-matching-fixtures.mjs`, add these constants near `confidenceExpectations`:

```js
const targetWarnings = new Set([
  "VISUAL_AREA_ONLY",
  "NO_MEANINGFUL_COMPOSE_TARGET",
  "POSSIBLE_VIEW_INTEROP",
  "LOW_SOURCE_CANDIDATE_MARGIN",
  "SOURCE_INDEX_STALE",
  "SCREEN_FINGERPRINT_MISMATCH_FORCED",
  "SCREEN_FINGERPRINT_UNAVAILABLE",
  "SENSITIVE_TEXT_REDACTED",
]);
const sourceRiskFlags = new Set([
  "AMBIGUOUS",
  "AREA_SELECTION",
  "TEXT_ONLY",
  "NEARBY_ONLY",
  "ARBITRARY_LITERAL",
  "ACTIVITY_ONLY",
  "LEGACY_FALLBACK",
  "UNTYPED_FALLBACK",
]);
```

- [ ] **Step 4: Add array validation helper**

Add this helper below `arrayOf` in `scripts/source-matching-fixtures.mjs`:

```js
function validateAllowedValues(values, allowed, fieldName, errors) {
  for (const value of arrayOf(values)) {
    if (!allowed.has(value)) {
      errors.push(`${fieldName} contains unsupported value ${value}`);
    }
  }
}
```

- [ ] **Step 5: Validate trust fields inside manifest cases**

Inside the `for (const entry of fixture.cases || [])` loop in `validateManifest`, after the existing `expectedSignal` validation, add:

```js
      validateAllowedValues(entry.mustWarn, targetWarnings, `${entry.id || "case"} mustWarn`, errors);
      validateAllowedValues(entry.mustNotWarn, targetWarnings, `${entry.id || "case"} mustNotWarn`, errors);
      validateAllowedValues(entry.expectedRiskFlags, sourceRiskFlags, `${entry.id || "case"} expectedRiskFlags`, errors);
      if (entry.mustNotHighConfidence !== undefined && typeof entry.mustNotHighConfidence !== "boolean") {
        errors.push(`${entry.id || "case"} mustNotHighConfidence must be boolean`);
      }
```

- [ ] **Step 6: Replace `classifyCaseOutcome` with the expanded classifier**

Replace the current `classifyCaseOutcome` function in `scripts/source-matching-fixtures.mjs` with:

```js
// F1-corrected: top1Needles falls back to expectedTop3PathContains; warning_absent NOT pushed as metric.
export function classifyCaseOutcome(expectation, observed) {
  const metrics = [];
  const failures = [];
  const candidates = observed.candidates || [];
  const warnings = new Set(observed.warnings || []);
  const riskFlags = new Set(observed.riskFlags || []);
  const top1Needles = arrayOf(
    expectation.expectedTop1PathContains
      || expectation.expectedEntryPathContains
      || expectation.expectedTop3PathContains,
  );
  const top3Needles = arrayOf(
    expectation.expectedTop3PathContains
      || expectation.expectedEntryPathContains,
  );

  if (top1Needles.length && candidates[0]?.path && top1Needles.some((needle) => candidates[0].path.includes(needle))) {
    metrics.push("top1_hit");
  } else if (expectation.expectedTop1PathContains) {
    failures.push("wrong_top1");
  }

  if (top3Needles.length && candidates.slice(0, 3).some((candidate) => top3Needles.some((needle) => candidate.path.includes(needle)))) {
    metrics.push("top3_hit");
  } else if (top3Needles.length) {
    failures.push("missing_top3");
  }

  if (expectation.expectedConfidence && observed.confidence) {
    if (confidenceMatches(expectation.expectedConfidence, observed.confidence)) {
      metrics.push("confidence_calibrated");
    } else if (observed.confidence === "high" && ["low-or-medium", "low", "unknown"].includes(expectation.expectedConfidence)) {
      failures.push("overconfident");
    } else if (observed.confidence === "low" && expectation.expectedConfidence === "medium-or-high") {
      failures.push("underconfident");
    }
  }

  for (const riskFlag of expectation.expectedRiskFlags || []) {
    if (riskFlags.has(riskFlag)) metrics.push("risk_flag_present");
    else failures.push("missing_risk_flag");
  }

  for (const warning of expectation.mustWarn || []) {
    if (warnings.has(warning)) metrics.push("warning_present");
    else failures.push("missing_warning");
  }
  for (const warning of expectation.mustNotWarn || []) {
    if (warnings.has(warning)) failures.push("unexpected_warning");
  }

  if (expectation.mustNotHighConfidence === true) {
    if (observed.confidence === "high") {
      failures.push("unexpected_high_confidence");
      if (riskFlags.size > 0 || warnings.size > 0) {
        failures.push("weak_evidence_promoted");
      }
    } else if (observed.confidence) {
      metrics.push("high_confidence_avoided");
    }
  }

  return { metrics, failures, environment: observed.environment || [] };
}
```

- [ ] **Step 7: Run the fixture tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 8: Commit Task 1**

Run:

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "feat: classify source matching trust regressions"
```

---

### Task 2: Make Fixture Reports Explain Trust Outcomes

**Files:**
- Modify: `scripts/source-matching-fixtures-test.mjs`
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `docs/guides/source-matching-fixture-lab.md`

- [ ] **Step 1: Add failing tests for report summaries and environment split**

Append these tests to `scripts/source-matching-fixtures-test.mjs` near the existing `markdownReport` test:

```js
test("writeFixtureReport style summary separates failures and environment downgrades", () => {
  const report = buildFixtureReport([{
    fixtureId: "reply",
    mode: "source-index",
    status: "evaluated",
    sourceIndexSchemaVersion: "1.2",
    cases: [
      {
        caseId: "ok",
        metrics: ["top3_hit", "confidence_calibrated"],
        failures: [],
        environment: [],
      },
      {
        caseId: "drift",
        metrics: [],
        failures: ["fixture_drift"],
        environment: ["upstream_path_missing"],
      },
    ],
  }], "2026-05-24T00:00:00.000Z");

  assert.equal(report.status, "fail");
  assert.deepEqual(report.summary.failureCounts, { fixture_drift: 1 });
  assert.deepEqual(report.summary.environmentCounts, { upstream_path_missing: 1 });
  assert.equal(report.summary.totalCases, 2);
  assert.equal(report.summary.failedCases, 1);
});

test("markdownReport prints summary counts before fixture tables", () => {
  const text = markdownReport({
    schemaVersion: 1,
    generatedAt: "2026-05-24T00:00:00.000Z",
    status: "fail",
    summary: {
      totalCases: 2,
      failedCases: 1,
      environmentCases: 1,
      failureCounts: { overconfident: 1 },
      environmentCounts: { device_unavailable: 1 },
    },
    fixtures: [{
      fixtureId: "reply",
      status: "evaluated",
      cases: [{
        caseId: "bad-confidence",
        metrics: [],
        failures: ["overconfident"],
        environment: ["device_unavailable"],
      }],
    }],
  });

  assert.match(text, /## Summary/);
  assert.match(text, /- Total cases: 2/);
  assert.match(text, /- Failed cases: 1/);
  assert.match(text, /- Environment downgrade cases: 1/);
  assert.match(text, /- Failure counts: overconfident=1/);
  assert.match(text, /- Environment counts: device_unavailable=1/);
});
```

- [ ] **Step 2: Update the import list in the test file**

Add `buildFixtureReport` to the existing import from `./source-matching-fixtures.mjs`:

```js
  buildFixtureReport,
  classifyCaseOutcome,
```

- [ ] **Step 3: Run the tests and verify failure**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL with `buildFixtureReport` not exported.

- [ ] **Step 4: Add report summary helpers**

In `scripts/source-matching-fixtures.mjs`, add these functions above `markdownReport`:

```js
function countLabels(cases, fieldName) {
  return cases
    .flatMap((testCase) => testCase[fieldName] || [])
    .reduce((counts, label) => {
      counts[label] = (counts[label] || 0) + 1;
      return counts;
    }, {});
}

export function buildFixtureReport(fixtures, generatedAt = new Date().toISOString()) {
  const caseResults = fixtures.flatMap((fixture) => fixture.cases || []);
  return {
    schemaVersion: 1,
    generatedAt,
    status: reportStatus(caseResults),
    deviceBackedCapture: "not_configured",
    summary: {
      totalCases: caseResults.length,
      failedCases: caseResults.filter((testCase) => (testCase.failures || []).length > 0).length,
      environmentCases: caseResults.filter((testCase) => (testCase.environment || []).length > 0).length,
      failureCounts: countLabels(caseResults, "failures"),
      environmentCounts: countLabels(caseResults, "environment"),
    },
    fixtures,
  };
}
```

- [ ] **Step 5: Use `buildFixtureReport` in `writeFixtureReport`**

Replace `writeFixtureReport` with:

```js
export function writeFixtureReport(fixtures) {
  const report = buildFixtureReport(fixtures);
  writeJson(join(defaultReportDir, "report.json"), report);
  writeFileSync(join(defaultReportDir, "report.md"), markdownReport(report));
  return report;
}
```

- [ ] **Step 6: Add the Markdown summary block**

In `markdownReport`, after the `Generated:` line block and before the fixture loop, insert:

```js
  if (report.summary) {
    lines.push("## Summary");
    lines.push("");
    lines.push(`- Total cases: ${report.summary.totalCases}`);
    lines.push(`- Failed cases: ${report.summary.failedCases}`);
    lines.push(`- Environment downgrade cases: ${report.summary.environmentCases}`);
    lines.push(`- Failure counts: ${formatCounts(report.summary.failureCounts)}`);
    lines.push(`- Environment counts: ${formatCounts(report.summary.environmentCounts)}`);
    lines.push("");
  }
```

Then add this helper below `markdownReport`:

```js
function formatCounts(counts) {
  const entries = Object.entries(counts || {});
  return entries.length === 0
    ? "-"
    : entries.map(([label, count]) => `${label}=${count}`).join(", ");
}
```

- [ ] **Step 7: Document report labels**

In `docs/guides/source-matching-fixture-lab.md`, replace the "Important failure labels" list with:

```markdown
Important failure labels:

- `missing_top3`: expected source entry did not appear in the evaluated source index.
- `wrong_top1`: a case required a top-1 match and the first candidate did not match.
- `missing_source_signal`: expected typed source signal was missing.
- `overconfident`: observed confidence was higher than the case allowed.
- `underconfident`: observed confidence was lower than the case expected.
- `missing_warning`: a required target warning was absent.
- `unexpected_warning`: a warning that should not appear was present.
- `unexpected_high_confidence`: a case marked `mustNotHighConfidence` became high confidence.
- `weak_evidence_promoted`: weak evidence carried a risk or warning but still became high confidence.
- `fixture_build_failed`: the external fixture did not build in this local environment.
- `source_index_missing`: Gradle completed without producing the expected FixThis source index.
- `fixture_drift`: the pinned upstream fixture no longer matches the case contract and should be re-pinned or corrected.
- `case_contract_invalid`: the committed manifest case is invalid.
```

- [ ] **Step 8: Run the fixture tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 9: Commit Task 2**

Run:

```bash
git add scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs docs/guides/source-matching-fixture-lab.md
git commit -m "docs: explain source matching trust reports"
```

---

### Task 3: Improve Precise And Compact Handoff Trust Wording

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CopyPromptEditSurfaceRendererTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`

- [ ] **Step 1: Add precise Markdown tests for action wording**

Append this test to `FeedbackQueueFormatterTest.kt`:

```kotlin
@Test
fun preciseMarkdownRendersTargetConfidenceActionGuidance() {
    val session = SessionDto(
        sessionId = "session-1",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        items = listOf(
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("pay-button", FixThisRect(10f, 20f, 120f, 80f)),
                comment = "Make the button wider",
                targetReliability = TargetReliability(confidence = TargetConfidence.MEDIUM),
            ),
            AnnotationDto(
                itemId = "item-2",
                screenId = "screen-1",
                createdAtEpochMillis = 2L,
                updatedAtEpochMillis = 2L,
                target = AnnotationTargetDto.Area(FixThisRect(20f, 90f, 260f, 180f)),
                comment = "Fix native chart spacing",
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.LOW,
                    warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                ),
            ),
        ),
    )

    val markdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)

    assertTrue(markdown.contains("Target confidence: medium - inspect the candidate and corroborate with screenshot or surrounding code."))
    assertTrue(markdown.contains("Target confidence: low - treat source paths as hints only."))
    assertTrue(markdown.contains("Warning: possible AndroidView/WebView area; source candidates may not explain rendered pixels"))
}
```

- [ ] **Step 2: Add compact Markdown test for source/edit-surface separation and warning guidance**

Append this test to `CopyPromptEditSurfaceRendererTest.kt`:

```kotlin
@Test
fun compactHandoffSeparatesEditSurfaceFromSourceCandidateAndWarnsWhenLowConfidence() {
    val targetNode = node(
        "metric-label",
        bounds = FixThisRect(79f, 1204f, 348f, 1241f),
        text = listOf("Resolved this week"),
    )
    val item = AnnotationDto(
        itemId = "item-1",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node(targetNode.uid, targetNode.boundsInWindow),
        selectedNode = targetNode,
        sourceCandidates = listOf(dataSourceCandidate("Resolved this week", 59)),
        editSurfaceCandidates = listOf(
            EditSurfaceCandidateDto(
                kind = EditSurfaceKindDto.TEXT_COLOR,
                role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
                file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/MetricCard.kt",
                line = 26,
                confidence = SelectionConfidence.MEDIUM,
                reasons = listOf(EditSurfaceReasonDto.STYLE_INTENT, EditSurfaceReasonDto.TARGET_OWNER),
                note = "source candidate identifies data text; editSurface identifies likely rendering code",
            ),
        ),
        targetReliability = TargetReliability(
            confidence = TargetConfidence.LOW,
            warnings = listOf(TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN),
        ),
        comment = "여기 글자 파란색",
        sequenceNumber = 1,
    )

    val markdown = CompactHandoffRenderer.render(oneItemSession(item))

    assertTrue(markdown.contains("editSurface: textColor  role=layout-or-style -> sample/src/main/java/io/github/beyondwin/fixthis/sample/components/MetricCard.kt:26"))
    assertTrue(markdown.contains("sample/src/main/java/io/github/beyondwin/fixthis/sample/model/FixThisDemoData.kt:59"))
    assertTrue(markdown.contains("targetConfidence=low; action=treat-source-paths-as-hints"))
    assertTrue(markdown.contains("warning: source candidates are close; verify before editing"))
}
```

Add these imports to `CopyPromptEditSurfaceRendererTest.kt`:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
```

- [ ] **Step 3: Run MCP tests and verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatterTest" --tests "*CopyPromptEditSurfaceRendererTest" --no-daemon
```

Expected: FAIL because confidence action wording is not rendered yet.

- [ ] **Step 4: Add precise confidence guidance**

In `FeedbackQueueFormatter.kt`, replace `appendTargetReliability` with:

```kotlin
private fun StringBuilder.appendTargetReliability(reliability: TargetReliability?) {
    if (reliability == null) return
    if (reliability.confidence == TargetConfidence.UNKNOWN && reliability.warnings.isEmpty()) {
        appendLine("- Target confidence: unknown - verify manually before editing.")
        return
    }
    appendLine("- Target confidence: ${reliability.confidence.name.lowercase()} - ${reliability.preciseActionGuidance()}")
    reliability.warnings.forEach { warning ->
        appendLine("- Warning: ${warning.handoffMessage().inlineSafe()}")
    }
}

private fun TargetReliability.preciseActionGuidance(): String = when (confidence) {
    TargetConfidence.HIGH -> "inspect the source candidate first."
    TargetConfidence.MEDIUM -> "inspect the candidate and corroborate with screenshot or surrounding code."
    TargetConfidence.LOW -> "treat source paths as hints only."
    TargetConfidence.UNKNOWN -> "verify manually before editing."
}
```

- [ ] **Step 5: Add compact confidence action tokens**

In `CompactHandoffRenderer.kt`, replace `appendReliabilityBlock` with:

```kotlin
private fun StringBuilder.appendReliabilityBlock(reliability: TargetReliability?) {
    if (reliability == null) return
    val confidence = reliability.confidence.name.lowercase()
    if (confidence == "unknown" && reliability.warnings.isEmpty()) {
        appendLine("  targetConfidence=unknown; action=verify-manually")
        return
    }
    appendLine("  targetConfidence=$confidence; action=${reliability.compactActionToken()}")
    reliability.warnings.forEach { warning ->
        appendLine("  warning: ${warning.handoffMessage().inlineSafe()}")
    }
}

private fun TargetReliability.compactActionToken(): String = when (confidence) {
    io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.HIGH -> "inspect-source-first"
    io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.MEDIUM -> "inspect-and-corroborate"
    io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW -> "treat-source-paths-as-hints"
    io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.UNKNOWN -> "verify-manually"
}
```

- [ ] **Step 6: Run MCP tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatterTest" --tests "*CopyPromptEditSurfaceRendererTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit Task 3**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CopyPromptEditSurfaceRendererTest.kt
git commit -m "feat: clarify source matching handoff trust"
```

---

### Task 4: Strengthen Core Confidence Cap Tests

**Files:**
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculatorTest.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`

- [ ] **Step 1: Add source matcher confidence cap regression tests**

Append these tests to `SourceMatcherTest.kt` before the private `node` helper:

```kotlin
@Test
fun nearbyOnlyAndActivityOnlyMatchesStayLowConfidence() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/java/DiagnosticsScreen.kt",
                    line = 20,
                    text = listOf("Retry"),
                    activityNames = listOf("MainActivity"),
                    signals = listOf(
                        SourceSignal(SourceSignalKind.UI_TEXT, "Retry"),
                        SourceSignal(SourceSignalKind.ACTIVITY_NAME, "MainActivity"),
                    ),
                ),
            ),
        ),
    )

    val nearbyOnly = matcher.match(
        selectedNode = node(uid = "empty-target"),
        nearbyNodes = listOf(node(uid = "retry-label", text = listOf("Retry"))),
        activityName = null,
    ).single()

    val activityOnly = matcher.match(
        selectedNode = node(uid = "empty-target"),
        nearbyNodes = emptyList(),
        activityName = "io.github.beyondwin.fixthis.sample.MainActivity",
    ).single()

    assertEquals(SelectionConfidence.LOW, nearbyOnly.confidence)
    assertTrue(nearbyOnly.riskFlags.contains(SourceCandidateRisk.NEARBY_ONLY))
    assertEquals(SelectionConfidence.LOW, activityOnly.confidence)
    assertTrue(activityOnly.riskFlags.contains(SourceCandidateRisk.ACTIVITY_ONLY))
}

@Test
fun ownerFunctionMatchWithoutDirectUiEvidenceDoesNotBecomeHighConfidence() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/java/AdaptiveGrid.kt",
                    line = 38,
                    signals = listOf(
                        SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "AdaptiveGrid"),
                    ),
                ),
                SourceIndexEntry(
                    file = "sample/src/main/java/AdaptiveGrid.kt",
                    line = 12,
                    symbols = listOf("AdaptiveGrid"),
                    signals = listOf(
                        SourceSignal(SourceSignalKind.COMPOSABLE_SYMBOL, "AdaptiveGrid"),
                    ),
                ),
            ),
        ),
    )

    val matches = matcher.match(
        selectedNode = node(uid = "tile", testTag = "comp:AdaptiveGrid:tile"),
        nearbyNodes = emptyList(),
        activityName = null,
    )

    assertEquals("sample/src/main/java/AdaptiveGrid.kt", matches.first().file)
    assertEquals(SelectionConfidence.MEDIUM, matches.first().confidence)
    assertTrue(matches.first().matchReasons.contains("selected owner composable"))
}
```

- [ ] **Step 2: Add target reliability test for weak source flags**

Append this test to `TargetReliabilityCalculatorTest.kt`:

```kotlin
@Test
fun mediumSourceCandidateWithWeakRiskDoesNotRaiseTargetAboveMedium() {
    val result = TargetReliabilityCalculator.calculate(
        TargetReliabilityInput(
            targetKind = TargetKind.NODE,
            selectedNode = meaningfulNode(),
            sourceCandidates = listOf(
                SourceCandidate(
                    file = "sample/src/main/java/AdaptiveGrid.kt",
                    line = 38,
                    score = 0.72,
                    matchReasons = listOf("selected testTag convention composable"),
                    confidence = SelectionConfidence.MEDIUM,
                    scoreMargin = 0.18,
                    riskFlags = listOf(SourceCandidateRisk.ARBITRARY_LITERAL),
                ),
            ),
            screenFingerprintAvailable = true,
        ),
    )

    assertEquals(TargetConfidence.MEDIUM, result.confidence)
    assertTrue(result.reasons.contains(TargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE))
}
```

- [ ] **Step 3: Run core tests and verify failure or current behavior**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --tests "*TargetReliabilityCalculatorTest" --no-daemon
```

Expected: The `ownerFunctionMatchWithoutDirectUiEvidenceDoesNotBecomeHighConfidence` test FAILS until owner-function convention matching and medium-strength owner evidence are added. The other tests may pass if current caps already enforce the intended behavior.

- [ ] **Step 4: Add owner-function match reason**

In `SourceMatchReason.kt`, add this enum value after `SELECTED_TEST_TAG_CONVENTION_COMPOSABLE`:

```kotlin
SELECTED_OWNER_FUNCTION("selected owner composable"),
```

- [ ] **Step 5: Treat owner-function matches as medium evidence**

In `EvidenceProfile.kt`, add this property after `hasStrictCompTag`:

```kotlin
val hasSelectedOwnerFunction: Boolean = SourceMatchReason.SELECTED_OWNER_FUNCTION in reasons
```

Add `hasSelectedOwnerFunction` into `hasAnySelected`:

```kotlin
hasSelectedOwnerFunction ||
```

Add owner-function evidence into `distinctSelectedMediumKinds`:

```kotlin
            (if (hasSelectedOwnerFunction) 1 else 0) +
```

Do not add `hasSelectedOwnerFunction` to `selectedStrongCount`.

In `SourceMatcher.kt`, add an owner-function branch to `baseConfidenceFor` after the selected text/content/state branch and before `profile.selectedStrongCount > 0`:

```kotlin
profile.hasSelectedOwnerFunction -> SelectionConfidence.MEDIUM
```

- [ ] **Step 6: Let strict composable tags match owner-function entries**

In `SourceMatcher.kt`, replace `conventionComposableWeightHit` with:

```kotlin
private fun SourceIndexEntry.conventionComposableWeightHit(composableName: String): WeightHit {
    val (signalMatchWeight, signalKind) = bestSignalHit(
        terms = listOf(composableName),
        kinds = setOf(
            SourceSignalKind.COMPOSABLE_SYMBOL,
            SourceSignalKind.STRICT_COMP_TEST_TAG,
            SourceSignalKind.LAMBDA_OWNER_FUNCTION,
        ),
    )
    if (signalMatchWeight > 0.0) {
        return WeightHit(
            weight = if (signalKind == SourceSignalKind.LAMBDA_OWNER_FUNCTION) signalMatchWeight * 0.85 else signalMatchWeight,
            signalKind = signalKind,
            viaLegacy = false,
        )
    }

    val legacyMatches = matchesAny(composableName, symbols + listOf(file) + listOfNotNull(excerpt))
    return WeightHit(legacyWeight(legacyMatches), signalKind = null, viaLegacy = legacyMatches)
}
```

- [ ] **Step 7: Render owner-function reason when a LAMBDA owner caused the match**

In `SourceMatcher.kt`, replace the current `effectiveReason` assignment in `recordMatch` with:

```kotlin
val effectiveReason = when {
    hit.signalKind == SourceSignalKind.STRING_RESOURCE_RESOLVED &&
        reason == SourceMatchReason.SELECTED_TEXT ->
        SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE
    hit.signalKind == SourceSignalKind.LAMBDA_OWNER_FUNCTION &&
        reason == SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE ->
        SourceMatchReason.SELECTED_OWNER_FUNCTION
    else -> reason
}
```

- [ ] **Step 8: Run core tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --tests "*TargetReliabilityCalculatorTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit Task 4**

Run:

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
  fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt \
  fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/EvidenceProfile.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculatorTest.kt
git commit -m "test: lock source matching confidence caps"
```

---

### Task 5: Add Layout And SubcomposeLayout Renderer Signals

**Files:**
- Modify: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`

- [ ] **Step 1: Add scanner tests for layout renderer signals**

Append this test to `KotlinSourceScannerTest.kt`:

```kotlin
@Test
fun `indexes Layout and SubcomposeLayout renderer calls with owner function`() {
    val file = tempDir.newFile("AdaptiveGrid.kt").apply {
        writeText(
            """
            package com.example
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.layout.Layout
            import androidx.compose.ui.layout.SubcomposeLayout

            @Composable
            fun AdaptiveGrid() {
                Layout(content = {}, measurePolicy = { _, _ -> layout(0, 0) {} })
            }

            @Composable
            fun DeferredTabs() {
                SubcomposeLayout { _, _ -> layout(0, 0) {} }
            }
            """.trimIndent(),
        )
    }

    val entries = KotlinSourceScanner(tempDir.root, tempDir.root, json).scan(file)
    val layoutEntry = entries.single { entry ->
        entry.signals.any { it.kind == SourceSignalKindAsset.LAYOUT_RENDERER && it.value == "Layout" }
    }
    val subcomposeEntry = entries.single { entry ->
        entry.signals.any { it.kind == SourceSignalKindAsset.LAYOUT_RENDERER && it.value == "SubcomposeLayout" }
    }

    assertTrue(layoutEntry.signals.any { it.kind == SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION && it.value == "AdaptiveGrid" })
    assertTrue(subcomposeEntry.signals.any { it.kind == SourceSignalKindAsset.LAMBDA_OWNER_FUNCTION && it.value == "DeferredTabs" })
}
```

- [ ] **Step 2: Add matcher test for layout call-site owner matching**

Append this test to `SourceMatcherTest.kt` before the private `node` helper:

```kotlin
@Test
fun strictComposableTagCanSurfaceLayoutRendererEntryAsMediumConfidence() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/java/AdaptiveGrid.kt",
                    line = 38,
                    signals = listOf(
                        SourceSignal(SourceSignalKind.LAYOUT_RENDERER, "Layout"),
                        SourceSignal(SourceSignalKind.LAMBDA_OWNER_FUNCTION, "AdaptiveGrid"),
                    ),
                    excerpt = "Layout(content = {}, measurePolicy = { ... })",
                ),
            ),
        ),
    )

    val match = matcher.match(
        selectedNode = node(uid = "tile", testTag = "comp:AdaptiveGrid:tile"),
        nearbyNodes = emptyList(),
        activityName = null,
    ).single()

    assertEquals("sample/src/main/java/AdaptiveGrid.kt", match.file)
    assertEquals(38, match.line)
    assertEquals(SelectionConfidence.MEDIUM, match.confidence)
    assertTrue(match.matchReasons.contains("selected owner composable"))
    assertTrue(match.ownerComposable == null || match.ownerComposable == "AdaptiveGrid")
}
```

- [ ] **Step 3: Run scanner and core tests and verify failures**

Run:

```bash
./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
```

Expected: FAIL because `LAYOUT_RENDERER` is not defined or emitted yet.

- [ ] **Step 4: Add `LAYOUT_RENDERER` to source-index enums**

In both `SourceSignalKindAsset` in `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt` and `SourceSignalKind` in `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt`, add the enum value after `LAMBDA_OWNER_FUNCTION`:

```kotlin
LAYOUT_RENDERER,
```

- [ ] **Step 5: Add layout call scanner helpers**

In `KotlinSemanticSignalScanner.kt`, add this data class after `KotlinRoleSignal`:

```kotlin
internal data class KotlinLayoutRendererSignal(
    val range: IntRange,
    val renderer: String,
)
```

Add this function after `roleSignals`:

```kotlin
internal fun layoutRendererSignals(source: String): List<KotlinLayoutRendererSignal> =
    layoutRendererRegex.findAll(source)
        .map { match ->
            KotlinLayoutRendererSignal(
                range = match.range,
                renderer = match.groupValues[1],
            )
        }
        .toList()
```

Add this regex near `roleRegex`:

```kotlin
private val layoutRendererRegex = Regex("\\b(Layout|SubcomposeLayout)\\s*\\(")
```

- [ ] **Step 6: Emit layout renderer entries**

In `KotlinSourceScanner.kt`, after `collectModifierSignals(...)` and before `collectSemanticModifierSignals(...)`, insert:

```kotlin
collectLayoutRendererSignals(
    file = file,
    source = source,
    lines = lines,
    lineStartOffsets = lineStartOffsets,
    entriesByLine = entriesByLine,
    packageName = packageName,
    classDeclarations = classDeclarations,
)
```

Then add this private function inside `KotlinSourceScanner` after `collectModifierSignals`:

```kotlin
private fun collectLayoutRendererSignals(
    file: File,
    source: String,
    lines: List<String>,
    lineStartOffsets: IntArray,
    entriesByLine: MutableMap<Int, SourceIndexEntryBuilder>,
    packageName: String?,
    classDeclarations: List<Pair<Int, String>>,
) {
    layoutRendererSignals(source).forEach { signal ->
        entriesByLine.entryFor(
            file = file,
            lineNumber = signal.range.startLine(lineStartOffsets),
            lines = lines,
            packageName = packageName,
            className = classNameAt(signal.range.first, classDeclarations),
        ).apply {
            addSignal(SourceSignalKindAsset.LAYOUT_RENDERER, signal.renderer)
        }
    }
}
```

- [ ] **Step 7: Keep layout renderer signals from changing direct text matching**

In `SourceMatcher.kt`, add `LAYOUT_RENDERER` to the `baseMatchWeight` `when` expression with a conservative weight:

```kotlin
SourceSignalKind.LAYOUT_RENDERER -> 0.75
```

Do not add `LAYOUT_RENDERER` to `textLikeWeightHit`, `contentDescriptionWeightHit`, `roleWeightHit`, or `activityWeightHit`. The intended path is strict composable tag to `LAMBDA_OWNER_FUNCTION`, with `LAYOUT_RENDERER` serving as typed evidence on the call-site entry.

- [ ] **Step 8: Run scanner and core tests**

Run:

```bash
./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit Task 5**

Run:

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt \
  fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSemanticSignalScanner.kt \
  fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt \
  fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScannerTest.kt \
  fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt \
  fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "feat: index Compose layout renderer call sites"
```

---

### Task 6: Update References And Run Final Verification

**Files:**
- Modify: `docs/reference/source-matching.md`
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/guides/source-matching-fixture-lab.md`
- Generated locally only: `graphify-out/` after `graphify update .`

- [ ] **Step 1: Update source matching reference**

In `docs/reference/source-matching.md`, update the signal list by adding:

```markdown
- `LAYOUT_RENDERER`: a `Layout(...)` or `SubcomposeLayout(...)` renderer call
  inside a composable owner. This is used as call-site evidence; it does not
  by itself make a source candidate high confidence.
```

Also add this paragraph under "Owner Composable Resolution":

```markdown
Layout renderer call sites inherit `LAMBDA_OWNER_FUNCTION` like other entries.
When a selected target uses a strict `comp:<ComposableName>:...` test tag, the
matcher may surface a `Layout` or `SubcomposeLayout` call inside that owner as a
medium-confidence edit surface hint. The layout renderer signal is conservative:
it is typed evidence for where layout work may live, not proof that the selected
pixels map exactly to that line.
```

- [ ] **Step 2: Update output schema reference**

In `docs/reference/output-schema.md`, under `targetReliability`, add:

```markdown
Agent-facing Markdown may render `targetReliability.confidence` with action
guidance such as inspect-source-first, inspect-and-corroborate,
treat-source-paths-as-hints, or verify-manually. These are renderer phrases,
not persisted JSON fields.
```

Under `Source Candidates`, add:

```markdown
`LAYOUT_RENDERER` source-index signals are typed call-site evidence for
`Layout` and `SubcomposeLayout` usage. They should be interpreted with owner
composable and confidence warnings; a layout renderer signal alone is not an
exact source-line guarantee.
```

- [ ] **Step 3: Update fixture lab guide if Task 2 did not already cover all labels**

Confirm `docs/guides/source-matching-fixture-lab.md` includes the labels from Task 2. If any of these labels are missing, add them to the "Important failure labels" list:

```markdown
- `underconfident`: observed confidence was lower than the case expected.
- `unexpected_high_confidence`: a case marked `mustNotHighConfidence` became high confidence.
- `weak_evidence_promoted`: weak evidence carried a risk or warning but still became high confidence.
- `fixture_drift`: the pinned upstream fixture no longer matches the case contract and should be re-pinned or corrected.
- `case_contract_invalid`: the committed manifest case is invalid.
```

- [ ] **Step 4: Run targeted verification**

Run:

```bash
npm run source-matching:fixtures:test
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --tests "*TargetReliabilityCalculatorTest" --no-daemon
./gradlew :fixthis-gradle-plugin:test --tests "*KotlinSourceScannerTest" --no-daemon
./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatterTest" --tests "*CopyPromptEditSurfaceRendererTest" --no-daemon
git diff --check
```

Expected:

- `npm run source-matching:fixtures:test`: PASS
- `:fixthis-compose-core:test` targeted tests: PASS
- `:fixthis-gradle-plugin:test` targeted tests: PASS
- `:fixthis-mcp:test` targeted tests: PASS
- `git diff --check`: no output and exit 0

- [ ] **Step 5: Run Graphify update because code changed**

Run:

```bash
graphify update .
```

Expected: exits 0. Dirty `graphify-out/` files may appear and must not be committed.

- [ ] **Step 6: Confirm no generated local artifacts are staged**

Run:

```bash
git status --short
```

Expected: source, test, and docs files from this plan may be modified; `.fixthis/`, `build/reports/fixthis-source-matching/`, and `graphify-out/` are not staged.

- [ ] **Step 7: Commit Task 6**

Run:

```bash
git add docs/reference/source-matching.md docs/reference/output-schema.md docs/guides/source-matching-fixture-lab.md
git commit -m "docs: document source matching trust signals"
```

- [ ] **Step 8: Run final status check**

Run:

```bash
git status --short
```

Expected: clean except ignored local fixture/build/graph files. If `graphify-out/` appears as tracked or staged, stop and unstage it before proceeding.
