# FixThis Trust Loop Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the approved Trust Loop Completion umbrella by hardening runtime trust fixtures, proving the external agent lifecycle after handoff, and aggregating the evidence into a release-gate report.

**Architecture:** Extend the existing evidence surfaces instead of adding parallel harnesses. `scripts/source-matching-fixtures.mjs` remains the runtime trust fixture runner, `scripts/agent-loop-smoke.mjs` remains the lifecycle smoke, and `scripts/release-gate.mjs` wraps the `gate` evidence profile into JSON/Markdown reports with release-claim metadata.

**Tech Stack:** Node.js 20 ESM scripts and `node:test`, Gradle/Kotlin tests for MCP/session rendering, Playwright for browser console smoke, Android SDK/ADB for strict connected runtime evidence.

---

## File Structure

- Modify `fixtures/source-matching/manifest.json`
  - Owns declarative runtime trust cases and their `trustPurpose`, confidence, risk, warning, and boundary expectations.
- Modify `scripts/source-matching-fixtures.mjs`
  - Owns manifest validation, fixture execution, trust classification, failure taxonomy, and report rendering.
- Modify `scripts/source-matching-fixtures-test.mjs`
  - Locks manifest coverage and runtime trust failure classification.
- Modify `scripts/agent-loop-smoke.mjs`
  - Owns connected external fixture lifecycle smoke from console handoff through MCP read/claim/resolve and console reflection.
- Modify `scripts/agent-loop-smoke-test.mjs`
  - Locks lifecycle helper contracts, failure categorization, queue-read assertions, and report rendering.
- Modify `scripts/evidence-runner.mjs`
  - Owns named evidence profiles. The `gate` profile must include Track A, Track B, docs, release reality, and console evidence.
- Modify `scripts/evidence-runner-test.mjs`
  - Locks the `gate` profile command list and Android deferral behavior.
- Modify `scripts/release-gate.mjs`
  - Owns aggregate gate report normalization, release-claim mapping, strict deferral policy, and JSON/Markdown output.
- Modify `scripts/release-gate-test.mjs`
  - Locks report status, release claims, strict/non-strict behavior, and report artifacts.
- Verify `package.json`
  - Confirms the public commands already exist: `agent-loop:smoke`, `agent-loop:smoke:test`, `release:gate`, and `release:gate:test`.
- Modify `docs/contributing/release-readiness.md`
  - Defines the Trust Loop Completion evidence claims and commands.
- Modify `docs/releases/unreleased.md`
  - Records the validation surface for the new gate without claiming a tagged release.
- Modify `CONTRIBUTING.md`
  - Adds the connected smoke and release gate commands to the contributor verification surface.

---

### Task 1: Runtime Trust Fixture Coverage And Failure Taxonomy

**Files:**
- Modify: `fixtures/source-matching/manifest.json`
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Add failing manifest coverage tests**

Append these tests near the existing runtime trust manifest tests in `scripts/source-matching-fixtures-test.mjs`:

```js
test("manifest covers every Trust Loop runtime trust risk class", () => {
  const manifest = loadManifest();
  const runtimeCases = manifest.fixtures.flatMap((fixture) =>
    fixture.cases
      .filter((entry) => entry.mode === "runtime-trust")
      .map((entry) => ({ fixtureId: fixture.id, ...entry })),
  );

  assert.ok(
    runtimeCases.some((entry) => entry.trustPurpose.includes("shared") && entry.mustNotHighConfidence === true),
    "shared component runtime trust case is required",
  );
  assert.ok(
    runtimeCases.some((entry) => (entry.mustWarn || []).includes("POSSIBLE_VIEW_INTEROP") && entry.mustNotHighConfidence === true),
    "interop-risk runtime trust case is required",
  );
  assert.ok(
    runtimeCases.some((entry) => (entry.mustWarn || []).includes("VISUAL_AREA_ONLY") && entry.mustNotHighConfidence === true),
    "visual-area runtime trust case is required",
  );
  assert.ok(
    runtimeCases.some((entry) => entry.expectedSourceConfidence === "low-or-medium"),
    "weak source-candidate runtime trust case is required",
  );
});

test("runtime trust report classifies setup capture and trust failures separately", () => {
  const setup = runtimeTrustFailureCase("fixture-setup-failed", false);
  const capture = runtimeTrustFailureCase("runtime-capture-failed", false);
  const strictCapture = runtimeTrustFailureCase("runtime-capture-failed", true);

  assert.deepEqual(setup, {
    failures: [],
    environment: ["fixture_setup_failed"],
  });
  assert.deepEqual(capture, {
    failures: [],
    environment: ["runtime_capture_failed"],
  });
  assert.deepEqual(strictCapture, {
    failures: ["runtime_capture_failed"],
    environment: [],
  });
});
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
node --test scripts/source-matching-fixtures-test.mjs
```

Expected on a fresh implementation:

```text
not ok ... runtimeTrustFailureCase is not defined
```

- [ ] **Step 3: Export a runtime trust failure classifier**

Add this helper near `classifyRuntimeTrustOutcome` in `scripts/source-matching-fixtures.mjs`:

```js
export function runtimeTrustFailureCase(reason, strict = false) {
  const normalized = String(reason || "runtime-capture-failed")
    .trim()
    .toLowerCase()
    .replaceAll("_", "-");
  const label = normalized.includes("setup") || normalized.includes("source-index") || normalized.includes("install")
    ? "fixture_setup_failed"
    : "runtime_capture_failed";
  return strict
    ? { failures: [label], environment: [] }
    : { failures: [], environment: [label] };
}
```

Update the import list in `scripts/source-matching-fixtures-test.mjs`:

```js
import {
  buildFixtureReport,
  classifyCaseOutcome,
  classifyRuntimeTrustOutcome,
  loadManifest,
  evaluateSourceIndexCase,
  fixturePaths,
  installRuntimeFixture,
  markdownReport,
  patchAppBuildFileText,
  patchSettingsText,
  reportStatus,
  runRuntimeTrustEvaluation,
  runtimeFixtureInput,
  runtimeFixtures,
  runtimeInstallGradleArgs,
  runtimeSourceIndexGradleArgs,
  runtimeTrustFailureCase,
  runtimeTrustFixtureGradleArgs,
  safeRelativePath,
  validateManifest,
  variantTaskSuffix,
} from "./source-matching-fixtures.mjs";
```

- [ ] **Step 4: Use the classifier in runtime command error handling**

Replace the runtime catch block inside `main()` in `scripts/source-matching-fixtures.mjs` with:

```js
      } catch (error) {
        const classified = runtimeTrustFailureCase(error.message, strict);
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
              trustPurpose: testCase.trustPurpose,
              metrics: [],
              failures: classified.failures,
              environment: classified.environment,
            })),
        });
      }
```

- [ ] **Step 5: Verify runtime trust contracts**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected:

```text
# pass
```

- [ ] **Step 6: Commit**

```bash
git add fixtures/source-matching/manifest.json scripts/source-matching-fixtures.mjs scripts/source-matching-fixtures-test.mjs
git commit -m "test: lock runtime trust fixture coverage"
```

---

### Task 2: Agent Loop Queue Read Before Claim

**Files:**
- Modify: `scripts/agent-loop-smoke.mjs`
- Modify: `scripts/agent-loop-smoke-test.mjs`

- [ ] **Step 1: Add failing queue-read helper tests**

Append to `scripts/agent-loop-smoke-test.mjs`:

```js
test("assertReadFeedbackQueueContains verifies saved MCP item ids before claim", () => {
  const queue = {
    items: [
      { itemId: "item-1", delivery: "sent", status: "open" },
      { itemId: "item-2", delivery: "sent", status: "open" },
    ],
  };

  assertReadFeedbackQueueContains(queue, ["item-1", "item-2"]);
  assert.throws(
    () => assertReadFeedbackQueueContains(queue, ["item-1", "item-404"]),
    /read_feedback_missing_items: item-404/,
  );
});
```

Update the import list in that file:

```js
import {
  annotationDragPoints,
  assertCopiedPromptProtocol,
  assertLifecycleSessionState,
  assertReadFeedbackQueueContains,
  buildReport,
  categorizeFeedbackLifecycleFailure,
  combineVisibleAnnotationStatusText,
  expectedResolutionPlan,
  handoffLifecycleActionOrder,
  parseArgs,
  renderMarkdownReport,
  selectResolutionItemIds,
  selectAgentLoopFixture,
  statusForEnvironment,
} from "./agent-loop-smoke.mjs";
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
node --test scripts/agent-loop-smoke-test.mjs
```

Expected on a fresh implementation:

```text
not ok ... assertReadFeedbackQueueContains is not defined
```

- [ ] **Step 3: Export the queue-read assertion helper**

Add this helper after `assertLifecycleSessionState()` in `scripts/agent-loop-smoke.mjs`:

```js
export function assertReadFeedbackQueueContains(queue, expectedItemIds) {
  const items = Array.isArray(queue?.items) ? queue.items : [];
  const seen = new Set(items.map((item) => item.itemId).filter(Boolean));
  const missing = expectedItemIds.filter((itemId) => !seen.has(itemId));
  if (missing.length > 0) {
    throw new Error(`read_feedback_missing_items: ${missing.join(", ")}`);
  }
  return {
    itemCount: items.length,
    sentCount: items.filter((item) => item.delivery === "sent").length,
  };
}
```

- [ ] **Step 4: Call `fixthis_read_feedback` before claiming items**

In `runSmoke()` in `scripts/agent-loop-smoke.mjs`, after:

```js
    const resolutionItemIds = selectResolutionItemIds(protocol.itemIds, browserFlow.session);
    const plan = expectedResolutionPlan(resolutionItemIds);
```

insert:

```js
    const readFeedback = await mcp.callTool("fixthis_read_feedback", {
      sessionId: protocol.sessionId,
      includeAll: true,
    });
    const queueStats = assertReadFeedbackQueueContains(readFeedback, resolutionItemIds);
```

Then include queue stats in the passing fixture result:

```js
    Object.assign(fixtureResult, {
      status: "pass",
      savedItemCount: protocol.itemIds.length,
      readFeedbackItemCount: queueStats.itemCount,
      readFeedbackSentCount: queueStats.sentCount,
      ...counts,
    });
```

- [ ] **Step 5: Render queue stats in the Markdown report**

Change the table in `renderMarkdownReport()` from:

```js
    "| Fixture | Package | Status | Saved | Resolved | Needs clarification | Won't fix |",
    "|---|---|---:|---:|---:|---:|---:|",
```

to:

```js
    "| Fixture | Package | Status | Saved | Read | Sent | Resolved | Needs clarification | Won't fix |",
    "|---|---|---:|---:|---:|---:|---:|---:|---:|",
```

Change the fixture row to:

```js
  lines.push(`| ${fixture.fixtureId || ""} | ${fixture.packageName || ""} | ${fixture.status || ""} | ${fixture.savedItemCount || 0} | ${fixture.readFeedbackItemCount || 0} | ${fixture.readFeedbackSentCount || 0} | ${fixture.resolved || 0} | ${fixture.needsClarification || 0} | ${fixture.wontFix || 0} |`);
```

- [ ] **Step 6: Update the report test**

In the `buildReport and markdown summarize lifecycle counts` test, add these fields:

```js
      readFeedbackItemCount: 3,
      readFeedbackSentCount: 3,
```

Update the assertion:

```js
  assert.match(renderMarkdownReport(report), /\| reply \| com\.example\.reply \| pass \| 3 \| 3 \| 3 \| 1 \| 1 \| 1 \|/);
```

- [ ] **Step 7: Verify agent loop contract tests**

Run:

```bash
npm run agent-loop:smoke:test
```

Expected:

```text
# pass
```

- [ ] **Step 8: Commit**

```bash
git add scripts/agent-loop-smoke.mjs scripts/agent-loop-smoke-test.mjs
git commit -m "test: prove agent loop reads queue before claim"
```

---

### Task 3: Release Gate Includes All Trust Loop Evidence

**Files:**
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`
- Modify: `scripts/release-gate-test.mjs`

- [ ] **Step 1: Add a failing gate profile test**

In `scripts/evidence-runner-test.mjs`, update the gate profile test to require Track A and existing Copy Prompt evidence:

```js
test("gate profile includes Trust Loop runtime agent copy prompt docs and whitespace evidence", () => {
  const commands = expandProfile("gate").map((step) => step.command);
  assert.ok(commands.includes("npm run release:reality"));
  assert.ok(commands.some((command) => command.includes("TargetBoundaryContextFormatterTest")));
  assert.ok(commands.includes("npm run source-matching:fixtures:test"));
  assert.ok(commands.includes("npm run source-matching:fixtures:runtime -- --strict"));
  assert.ok(commands.includes("npm run agent-loop:smoke:test"));
  assert.ok(commands.includes("npm run agent-loop:smoke -- --strict"));
  assert.ok(commands.includes("npm run real-copy-prompt:smoke -- --strict"));
  assert.ok(commands.includes("npm run handoff:eval:test"));
  assert.ok(commands.includes("npm run console:browser:reliability"));
  assert.ok(commands.includes("node scripts/check-doc-consistency.mjs"));
  assert.ok(commands.includes("node scripts/check-whitespace.mjs diff --check"));
});
```

- [ ] **Step 2: Add a release gate step-list test**

In `scripts/release-gate-test.mjs`, update `releaseGateSteps include...` to assert the same public gate surface:

```js
test("releaseGateSteps include Trust Loop source agent release and console evidence", () => {
  const commands = releaseGateSteps().map((step) => step.command);
  assert.ok(commands.includes("npm run release:reality"));
  assert.ok(commands.includes("npm run source-matching:fixtures:test"));
  assert.ok(commands.includes("npm run source-matching:fixtures:runtime -- --strict"));
  assert.ok(commands.includes("npm run agent-loop:smoke:test"));
  assert.ok(commands.includes("npm run agent-loop:smoke -- --strict"));
  assert.ok(commands.includes("npm run real-copy-prompt:smoke -- --strict"));
  assert.ok(commands.includes("npm run handoff:eval:test"));
  assert.ok(commands.includes("npm run console:browser:reliability"));
  assert.ok(commands.includes("node scripts/check-doc-consistency.mjs"));
  assert.ok(commands.includes("node scripts/check-whitespace.mjs diff --check"));
});
```

- [ ] **Step 3: Run tests and verify the profile is incomplete**

Run:

```bash
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
```

Expected on a fresh implementation:

```text
not ok ... gate profile includes Trust Loop runtime agent copy prompt docs and whitespace evidence
```

- [ ] **Step 4: Extend the gate profile**

In `scripts/evidence-runner.mjs`, replace the `gate` profile with:

```js
  gate: [
    step("Release reality", "npm run release:reality"),
    step(
      "Interop boundary contracts",
      "./gradlew :fixthis-mcp:test --tests \"*TargetBoundaryContextFormatterTest\" --tests \"*TargetBoundaryGuidanceTest\" --tests \"*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost\" --no-daemon",
    ),
    step("Handoff evaluation", "npm run handoff:eval:test"),
    step("Runtime trust boundary observations", "npm run source-matching:fixtures:test"),
    step("Runtime trust strict", "npm run source-matching:fixtures:runtime -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Agent loop smoke contracts", "npm run agent-loop:smoke:test"),
    step("Agent loop smoke", "npm run agent-loop:smoke -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Real copy prompt smoke", "npm run real-copy-prompt:smoke -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Console reliability contracts", "node --test scripts/console-reliability-report-test.mjs scripts/studioReliabilityContract-test.mjs"),
    step("Console browser reliability", "npm run console:browser:reliability"),
    step("Release readiness", "node scripts/check-release-readiness.mjs"),
    step("Docs consistency", "node scripts/check-doc-consistency.mjs"),
    step("Workspace whitespace", "node scripts/check-whitespace.mjs diff --check"),
  ],
```

- [ ] **Step 5: Verify gate profile tests**

Run:

```bash
npm run evidence:test
npm run release:gate:test
```

Expected:

```text
# pass
```

- [ ] **Step 6: Commit**

```bash
git add scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
git commit -m "test: include trust loop evidence in release gate"
```

---

### Task 4: Release Gate Claim Mapping

**Files:**
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`

- [ ] **Step 1: Add failing claim mapping tests**

Append to `scripts/release-gate-test.mjs`:

```js
test("release gate report maps evidence steps to unlocked claims", () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: "2026-05-31T00:00:00.000Z",
    steps: [
      { name: "Release reality", command: "npm run release:reality", status: "pass" },
      { name: "Agent loop smoke", command: "npm run agent-loop:smoke -- --strict", status: "deferred", reason: "Android SDK unavailable" },
      { name: "Runtime trust strict", command: "npm run source-matching:fixtures:runtime -- --strict", status: "deferred", reason: "Android SDK unavailable" },
      { name: "Console browser reliability", command: "npm run console:browser:reliability", status: "pass" },
    ],
  });

  assert.deepEqual(report.claims, [
    {
      id: "release-reality",
      status: "pass",
      evidence: ["Release reality"],
    },
    {
      id: "external-agent-loop",
      status: "deferred",
      evidence: ["Agent loop smoke"],
      reason: "Android SDK unavailable",
    },
    {
      id: "runtime-source-trust",
      status: "deferred",
      evidence: ["Runtime trust strict"],
      reason: "Android SDK unavailable",
    },
    {
      id: "console-sse-reliability",
      status: "pass",
      evidence: ["Console browser reliability"],
    },
  ]);
});

test("release gate markdown renders claim statuses", () => {
  const text = renderReleaseGateMarkdown({
    schemaVersion: "1.0",
    status: "pass_with_deferred",
    strict: false,
    generatedAt: "2026-05-31T00:00:00.000Z",
    claims: [{
      id: "external-agent-loop",
      status: "deferred",
      evidence: ["Agent loop smoke"],
      reason: "Android SDK unavailable",
    }],
    steps: [],
  });

  assert.match(text, /## Release Claims/);
  assert.match(text, /\| external-agent-loop \| deferred \| Agent loop smoke \| Android SDK unavailable \|/);
});
```

- [ ] **Step 2: Run the focused tests and verify they fail**

Run:

```bash
node --test scripts/release-gate-test.mjs
```

Expected on a fresh implementation:

```text
not ok ... Expected values to be strictly deep-equal
```

- [ ] **Step 3: Add claim definitions and claim status aggregation**

Add these exports near the top of `scripts/release-gate.mjs`:

```js
export const releaseClaimDefinitions = Object.freeze([
  { id: "release-reality", evidenceNames: ["Release reality"] },
  { id: "external-agent-loop", evidenceNames: ["Agent loop smoke"] },
  { id: "runtime-source-trust", evidenceNames: ["Runtime trust strict"] },
  { id: "console-sse-reliability", evidenceNames: ["Console browser reliability"] },
]);

function statusRank(status) {
  if (status === "fail") return 3;
  if (status === "deferred") return 2;
  if (status === "pass") return 1;
  return 0;
}

export function buildReleaseClaims(steps) {
  const byName = new Map((steps || []).map((step) => [step.name, step]));
  return releaseClaimDefinitions.map((definition) => {
    const evidence = definition.evidenceNames
      .map((name) => byName.get(name))
      .filter(Boolean);
    const status = evidence.length === 0
      ? "fail"
      : evidence.map((step) => step.status).sort((a, b) => statusRank(b) - statusRank(a))[0];
    const reason = evidence.find((step) => step.reason)?.reason || (evidence.length === 0 ? "missing evidence command" : null);
    return {
      id: definition.id,
      status,
      evidence: evidence.map((step) => step.name),
      ...(reason ? { reason } : {}),
    };
  });
}
```

- [ ] **Step 4: Attach claims to the report**

In `buildReleaseGateReport()` in `scripts/release-gate.mjs`, change the return value to:

```js
  return {
    schemaVersion: "1.0",
    status: gateStatus(normalizedSteps, strict),
    strict,
    generatedAt,
    claims: buildReleaseClaims(normalizedSteps),
    steps: normalizedSteps,
  };
```

- [ ] **Step 5: Render claims in Markdown**

In `renderReleaseGateMarkdown()`, insert this block before the step table:

```js
  if ((report.claims || []).length > 0) {
    lines.push("## Release Claims", "");
    lines.push("| Claim | Status | Evidence | Reason |");
    lines.push("| --- | --- | --- | --- |");
    for (const claim of report.claims || []) {
      lines.push(`| ${cell(claim.id)} | ${cell(claim.status)} | ${cell((claim.evidence || []).join(", "))} | ${cell(claim.reason)} |`);
    }
    lines.push("");
  }
  lines.push("## Evidence Steps", "");
```

- [ ] **Step 6: Verify release gate tests**

Run:

```bash
npm run release:gate:test
```

Expected:

```text
# pass
```

- [ ] **Step 7: Commit**

```bash
git add scripts/release-gate.mjs scripts/release-gate-test.mjs
git commit -m "feat: map release gate evidence to claims"
```

---

### Task 5: Documentation And Contributor Verification Surface

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/releases/unreleased.md`
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Add release-readiness claim rows**

In `docs/contributing/release-readiness.md`, add this section before the existing `v1.1 Trust Loop Evidence` section:

```markdown
## Trust Loop Completion Evidence

The Trust Loop Completion umbrella may be claimed only when the release commit
has evidence for runtime source-trust calibration, external agent lifecycle
completion, and release-gate aggregation. This evidence pack does not tag or
publish by itself.

| Claim | Required evidence |
| --- | --- |
| Runtime source-trust fixtures prevent shared-component, interop-risk, visual-area, and weak-candidate evidence from overclaiming exact edit ownership. | `npm run source-matching:fixtures:test`, `npm run source-matching:fixtures:runtime -- --strict`, and `npm run handoff:eval:test`. |
| External Android agent lifecycle completes from handoff through queue read, claim, resolution, persistence, and console reflection. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict`. |
| Maintainers can classify release evidence and public claims as pass, deferred, or fail in one report. | `npm run release:gate`, `npm run release:gate:test`, and `node scripts/check-release-readiness.mjs`. |

When Android SDK or an unlocked emulator is unavailable, non-strict reports must
record the exact deferred reason and strict connected evidence must fail.
```

- [ ] **Step 2: Add unreleased validation commands**

In `docs/releases/unreleased.md`, extend the named local evidence report block to:

````markdown
For named local evidence reports, use:

```bash
npm run evidence:fast -- --dry-run
npm run evidence:test
npm run agent-loop:smoke:test
npm run release:gate
npm run release:gate:test
```
````

Add this paragraph after that block:

````markdown
When validating Trust Loop Completion on a connected Android device or unlocked
emulator, also run:

```bash
npm run source-matching:fixtures:runtime -- --strict
npm run agent-loop:smoke -- --strict
npm run real-copy-prompt:smoke -- --strict
```

If the Android environment is unavailable, attach the non-strict release-gate
report and record the connected evidence as deferred with the report reason.
````

- [ ] **Step 3: Add contributor command guidance**

In `CONTRIBUTING.md`, under connected device checks, add:

````markdown
Run the external agent lifecycle smoke when validating MCP queue lifecycle
behavior after feedback handoff:

```bash
npm run agent-loop:smoke -- --strict
```

For release-decision evidence, run:

```bash
npm run release:gate
npm run release:gate:test
```

The release gate writes JSON and Markdown reports under
`build/reports/fixthis-release-gate/`. These reports are ignored build
artifacts and should not be committed.
````

- [ ] **Step 4: Verify docs consistency**

Run:

```bash
node scripts/check-doc-consistency.mjs
bash scripts/check-docs-cli-surface.sh
```

Expected:

```text
docs consistency check passed
```

and:

```text
CLI docs surface check passed
```

- [ ] **Step 5: Commit**

```bash
git add docs/contributing/release-readiness.md docs/releases/unreleased.md CONTRIBUTING.md
git commit -m "docs: document trust loop completion evidence"
```

---

### Task 6: Final Evidence Pass And Graph Refresh

**Files:**
- Modify: `graphify-out/` local generated graph files only; do not commit them.

- [ ] **Step 1: Run fast host-side verification**

Run:

```bash
npm run source-matching:fixtures:test
npm run agent-loop:smoke:test
npm run evidence:test
npm run release:gate:test
node scripts/check-release-readiness.mjs
git diff --check
```

Expected:

```text
# pass
```

for Node test commands, release readiness exits `0`, and `git diff --check` exits `0`.

- [ ] **Step 2: Run connected evidence when Android is ready**

Run:

```bash
npm run source-matching:fixtures:runtime -- --strict
npm run agent-loop:smoke -- --strict
npm run real-copy-prompt:smoke -- --strict
```

Expected with an unlocked emulator or device:

```text
Status: pass
```

in each generated Markdown report.

Expected without Android readiness:

```text
Android SDK or ready emulator is unavailable.
```

In that case, run the non-strict aggregate report:

```bash
npm run release:gate
```

and confirm `build/reports/fixthis-release-gate/report.md` records `deferred` reasons for connected Android evidence.

- [ ] **Step 3: Run the aggregate release gate in strict mode when Android is ready**

Run:

```bash
node scripts/release-gate.mjs --strict
```

Expected with all required evidence available:

```text
Release gate: pass
```

Expected when connected evidence is unavailable:

```text
Release gate: fail
```

- [ ] **Step 4: Refresh Graphify navigation output**

Run:

```bash
graphify update .
```

Expected:

```text
command exits 0; graph update progress output is acceptable
```

Leave `graphify-out/` uncommitted.

- [ ] **Step 5: Inspect generated artifact residue**

Run:

```bash
git status --short
```

Expected tracked changes are limited to source, docs, tests, and plan/spec files. Do not commit `.fixthis/`, `graphify-out/`, Android build output, local fixture workspaces, or `build/reports/`.

- [ ] **Step 6: Commit final verification notes only when docs changed**

When final verification requires documentation updates, commit them with:

```bash
git add CONTRIBUTING.md docs/contributing/release-readiness.md docs/releases/unreleased.md
git commit -m "docs: record trust loop completion verification"
```

When no documentation changed, do not create an empty commit.

---

## Implementation Notes

- `needs_clarification` and `wont_fix` are persisted MCP status tokens. User-facing copy may render them as "Needs Clarification" and "Won't Fix".
- Strict connected commands must fail when Android is unavailable. Non-strict aggregate reports may defer connected evidence with a concrete reason.
- `Copy Prompt` parity means compact Markdown includes `session_id`, per-item `id`, and the `agent_protocol:` footer. Lifecycle operations still use MCP.
- Release gate reports are local build artifacts under `build/reports/`; they are evidence for a release issue, not committed source.
- Preserve compatibility fields: `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `targetReliability`, and `sourceCandidates`.
