# Android Release Evidence Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `release:gate` use `Connected Android proof` as the single connected Android runtime evidence step while preserving claim ids and child failure detail.

**Architecture:** Keep `android:proof` as the owner of connected Android preflight and child smoke execution. Trim the gate evidence profile to one connected runtime step, add report metadata to evidence steps, and let `release-gate.mjs` enrich the aggregate proof row with child failures from `build/reports/fixthis-android-proof/report.json`. Claim mapping keeps contract-only evidence separate from connected runtime proof.

**Tech Stack:** Node.js 20 ESM, `node:test`, `node:assert/strict`, existing evidence runner profiles, existing release-gate JSON/Markdown reports, Markdown documentation.

## Global Constraints

- FixThis remains debug-only and Jetpack Compose-only.
- Do not change the proof runner's child smoke behavior.
- Do not change MCP queue semantics or persisted feedback JSON fields.
- Do not change bridge protocol or source-matching compatibility fields.
- Do not remove focused smoke commands from package scripts.
- Existing release claim ids and top-level report fields remain stable.
- New release-gate detail fields must be additive.
- Focused commands remain available for debugging and non-gate evidence profiles.
- After modifying code, run `graphify update .`.

---

## File Structure

- Modify `scripts/evidence-runner.mjs`
  - Owns evidence profile definitions and step metadata.
  - Add optional `reportPath` metadata support to `step()`.
  - Remove duplicate connected strict commands from the `gate` profile.
- Modify `scripts/evidence-runner-test.mjs`
  - Pins the consolidated gate profile and reportPath metadata.
  - Keeps `trust` profile tests for focused Android evidence unchanged.
- Modify `scripts/release-gate.mjs`
  - Owns release claim definitions, evidence normalization, claim building, and Markdown rendering.
  - Remaps connected Android runtime claims to `Connected Android proof`.
  - Adds child failure enrichment for the connected proof report.
- Modify `scripts/release-gate-test.mjs`
  - Pins claim id stability, missing contract behavior, strict/deferred semantics, child failure propagation, and Markdown rendering.
- Modify `CONTRIBUTING.md`
  - Clarifies that release-decision connected evidence uses `android:proof`; individual smokes are debugging commands.
- Modify `docs/contributing/release-readiness.md`
  - Updates connected Android required-evidence rows to use `android:proof` plus contract tests.
- Modify `docs/releases/unreleased.md`
  - Describes connected evidence as the integrated proof report.

---

### Task 1: Consolidate The Gate Evidence Profile

**Files:**
- Modify: `scripts/evidence-runner-test.mjs`
- Modify: `scripts/evidence-runner.mjs`

**Interfaces:**
- Consumes: `expandProfile(profile: string): EvidenceStep[]`
- Produces:
  - `step(name: string, command: string, options?: { deferrable?: boolean, requiresAndroid?: boolean, reportPath?: string }): EvidenceStep`
  - Gate profile contains exactly one Android-connected runtime command: `npm run android:proof -- --strict --continue`
  - `Connected Android proof` step has `reportPath: "build/reports/fixthis-android-proof/report.json"`

- [ ] **Step 1: Write failing evidence-runner profile tests**

In `scripts/evidence-runner-test.mjs`, replace the current `trust and gate profiles include external trust matrix v2 strict evidence` test with:

```js
test('trust profile keeps focused external trust matrix v2 strict evidence', () => {
  const trustCommands = expandProfile('trust').map((step) => step.command);

  assert.ok(trustCommands.includes('npm run external-fixture:matrix -- --strict'));

  const trustStep = expandProfile('trust').find((step) => step.command === 'npm run external-fixture:matrix -- --strict');
  assert.equal(trustStep.name, 'External trust matrix v2 strict');
  assert.equal(trustStep.deferrable, true);
  assert.equal(trustStep.requiresAndroid, true);
});
```

Replace the current `trust and gate profiles include external fixture matrix` test with:

```js
test('trust profile includes focused external fixture matrix commands', () => {
  const trustCommands = expandProfile('trust').map((step) => step.command);

  assert.ok(trustCommands.includes('npm run external-fixture:matrix:test'));
  assert.ok(trustCommands.includes('npm run external-fixture:matrix -- --strict'));
});
```

Replace the current `gate profile includes Trust Loop runtime agent copy prompt docs and whitespace evidence` test with:

```js
test('gate profile uses connected Android proof instead of duplicate connected smokes', () => {
  const commands = expandProfile('gate').map((step) => step.command);

  assert.ok(commands.includes('npm run release:reality'));
  assert.ok(commands.some((command) => command.includes('TargetBoundaryContextFormatterTest')));
  assert.ok(commands.includes('npm run source-matching:fixtures:test'));
  assert.ok(commands.includes('npm run agent-loop:smoke:test'));
  assert.ok(commands.includes('npm run android:proof -- --strict --continue'));
  assert.equal(commands.filter((command) => command === 'npm run android:proof -- --strict --continue').length, 1);
  assert.ok(!commands.includes('npm run source-matching:fixtures:runtime -- --strict'));
  assert.ok(!commands.includes('npm run agent-loop:smoke -- --strict'));
  assert.ok(!commands.includes('npm run real-copy-prompt:smoke -- --strict'));
  assert.ok(!commands.includes('npm run external-fixture:matrix -- --strict'));
  assert.ok(commands.includes('npm run handoff:eval:test'));
  assert.ok(commands.includes('npm run console:browser:reliability'));
  assert.ok(commands.includes('node scripts/check-doc-consistency.mjs'));
  assert.ok(commands.includes('node scripts/check-whitespace.mjs diff --check'));
});
```

Extend the current `gate profile includes connected Android proof runner` test:

```js
test("gate profile includes connected Android proof runner", () => {
  const proof = expandProfile("gate").find((step) => step.name === "Connected Android proof");
  assert.equal(proof.command, "npm run android:proof -- --strict --continue");
  assert.equal(proof.deferrable, true);
  assert.equal(proof.requiresAndroid, true);
  assert.equal(proof.reportPath, "build/reports/fixthis-android-proof/report.json");
});
```

- [ ] **Step 2: Run evidence-runner tests and verify failure**

Run:

```bash
npm run evidence:test
```

Expected: FAIL because the gate profile still contains duplicate connected strict commands and `Connected Android proof` lacks `reportPath`.

- [ ] **Step 3: Extend evidence step metadata**

In `scripts/evidence-runner.mjs`, change `step()` to preserve `reportPath`:

```js
function step(name, command, options = {}) {
  return Object.freeze({
    name,
    command,
    deferrable: options.deferrable === true,
    requiresAndroid: options.requiresAndroid === true,
    ...(options.reportPath ? { reportPath: options.reportPath } : {}),
  });
}
```

- [ ] **Step 4: Remove duplicate connected strict commands from the gate profile**

In `scripts/evidence-runner.mjs`, keep this gate step:

```js
step("Connected Android proof", "npm run android:proof -- --strict --continue", {
  deferrable: true,
  requiresAndroid: true,
  reportPath: "build/reports/fixthis-android-proof/report.json",
}),
```

Remove these gate profile steps:

```js
step("Runtime trust strict", "npm run source-matching:fixtures:runtime -- --strict", {
  deferrable: true,
  requiresAndroid: true,
}),
step("External trust matrix v2 strict", "npm run external-fixture:matrix -- --strict", {
  deferrable: true,
  requiresAndroid: true,
}),
step("Agent loop smoke", "npm run agent-loop:smoke -- --strict", {
  deferrable: true,
  requiresAndroid: true,
}),
step("Real copy prompt smoke", "npm run real-copy-prompt:smoke -- --strict", {
  deferrable: true,
  requiresAndroid: true,
}),
```

Do not remove the same commands from the `trust` profile.

- [ ] **Step 5: Run evidence-runner tests and verify pass**

Run:

```bash
npm run evidence:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git add scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs
git commit -m "test: consolidate android gate evidence profile"
```

---

### Task 2: Remap Release Claims And Propagate Proof Child Failures

**Files:**
- Modify: `scripts/release-gate-test.mjs`
- Modify: `scripts/release-gate.mjs`

**Interfaces:**
- Consumes:
  - `Connected Android proof` evidence step from Task 1.
  - `build/reports/fixthis-android-proof/report.json` with `failures[]`.
- Produces:
  - `normalizeEvidenceStep(step: EvidenceStep, options?: { root?: string }): NormalizedEvidenceStep`
  - `readConnectedAndroidProofDetails(step: NormalizedEvidenceStep, options?: { root?: string }): { detailReportPath: string, childFailures: object[] } | null`
  - `childFailureReason(step: NormalizedEvidenceStep): string | null`
  - Connected Android claim definitions use `Connected Android proof` plus contract evidence.

- [ ] **Step 1: Add release-gate tests for consolidated claim mapping**

In `scripts/release-gate-test.mjs`, replace the current `releaseGateSteps include Trust Loop source agent release drift and console evidence` test with:

```js
test('releaseGateSteps include release drift host contracts connected proof and console evidence', () => {
  const commands = releaseGateSteps().map((step) => step.command);
  assert.ok(commands.includes('npm run release:reality'));
  assert.ok(commands.includes('npm run release:drift -- --strict'));
  assert.ok(commands.includes('./gradlew :fixthis-cli:test --tests "*AdbTest" --no-daemon'));
  assert.ok(commands.includes('./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon'));
  assert.ok(commands.includes('npm run source-matching:fixtures:test'));
  assert.ok(commands.includes('./gradlew :fixthis-cli:test --tests "*AgentSetupVerificationServiceTest" --tests "*InstallAgentJsonReportTest" --tests "*TwoPhaseConfigCommitTest" --no-daemon'));
  assert.ok(commands.includes('npm run agent-loop:smoke:test'));
  assert.ok(commands.includes('npm run android:proof -- --strict --continue'));
  assert.ok(!commands.includes('npm run source-matching:fixtures:runtime -- --strict'));
  assert.ok(!commands.includes('npm run agent-loop:smoke -- --strict'));
  assert.ok(!commands.includes('npm run real-copy-prompt:smoke -- --strict'));
  assert.ok(!commands.includes('npm run external-fixture:matrix -- --strict'));
  assert.ok(commands.includes('npm run handoff:eval:test'));
  assert.ok(commands.includes('npm run console:browser:reliability'));
  assert.ok(commands.includes('node scripts/check-doc-consistency.mjs'));
  assert.ok(commands.includes('node scripts/check-whitespace.mjs diff --check'));
});
```

In `scripts/release-gate-test.mjs`, update the `release gate report maps evidence steps to unlocked claims` fixture steps to use `Connected Android proof` instead of `Agent loop smoke` and `Runtime trust strict`:

```js
steps: [
  { name: 'Release reality', command: 'npm run release:reality', status: 'pass' },
  { name: 'Release drift strict', command: 'npm run release:drift -- --strict', status: 'pass' },
  { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
  { name: 'Handoff evaluation', command: 'npm run handoff:eval:test', status: 'pass' },
  { name: 'Runtime trust boundary observations', command: 'npm run source-matching:fixtures:test', status: 'pass' },
  { name: 'Console browser reliability', command: 'npm run console:browser:reliability', status: 'pass' },
],
```

Update expected connected claim fragments in that test:

```js
{
  id: 'external-agent-loop',
  status: 'fail',
  evidence: ['Connected Android proof'],
  reason: 'missing evidence command: Agent loop smoke contracts',
},
{
  id: 'connected-android-proof',
  status: 'deferred',
  evidence: ['Connected Android proof'],
  reason: 'Android SDK unavailable',
},
{
  id: 'runtime-source-trust',
  status: 'deferred',
  evidence: ['Runtime trust boundary observations', 'Connected Android proof'],
  reason: 'Android SDK unavailable',
},
```

Replace `release gate maps external fixture matrix claim` with:

```js
test('release gate maps external fixture matrix claim through connected proof plus contracts', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-27T00:00:00.000Z',
    steps: [
      { name: 'External fixture matrix contracts', command: 'npm run external-fixture:matrix:test', status: 'pass' },
      { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'external-fixture-matrix'), {
    id: 'external-fixture-matrix',
    status: 'deferred',
    evidence: ['External fixture matrix contracts', 'Connected Android proof'],
    reason: 'Android SDK unavailable',
  });
});
```

Replace `release gate maps external trust matrix v2 claim` with the same evidence and expected shape, changing only `id` to `external-trust-matrix-v2`.

Update `release gate maps external first handoff recovery claim`:

```js
steps: [
  { name: 'Agent loop smoke contracts', command: 'npm run agent-loop:smoke:test', status: 'pass' },
  { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
],
```

Expected:

```js
{
  id: 'external-first-handoff-recovery',
  status: 'deferred',
  evidence: ['Agent loop smoke contracts', 'Connected Android proof'],
  reason: 'Android SDK unavailable',
}
```

Update `release gate maps first handoff autopilot claim` in the same way, replacing `Agent loop smoke` with `Connected Android proof`.

Update `release gate fails external first handoff recovery when contract evidence is missing`:

```js
steps: [
  { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
],
```

Expected evidence is `['Connected Android proof']`, and reason remains `missing evidence command: Agent loop smoke contracts`.

- [ ] **Step 2: Add tests for child failure enrichment**

Append this test to `scripts/release-gate-test.mjs`:

```js
test('release gate enriches connected Android proof with child failures', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-proof-report-'));
  try {
    const reportDir = join(root, 'build/reports/fixthis-android-proof');
    mkdirSync(reportDir, { recursive: true });
    const reportPath = join(reportDir, 'report.json');
    writeFileSync(reportPath, `${JSON.stringify({
      schemaVersion: '1.0',
      status: 'fail',
      strict: true,
      failures: [{
        scope: 'step',
        step: 'Agent loop smoke',
        failureCode: 'agent_loop_failed',
        reason: 'Save to MCP through MCP failed.',
        nextAction: 'Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.',
      }],
    }, null, 2)}\n`);

    const normalized = normalizeEvidenceStep({
      name: 'Connected Android proof',
      command: 'npm run android:proof -- --strict --continue',
      status: 'failed',
      reportPath: 'build/reports/fixthis-android-proof/report.json',
    }, { root });

    assert.equal(normalized.detailReportPath, 'build/reports/fixthis-android-proof/report.json');
    assert.deepEqual(normalized.childFailures, [{
      scope: 'step',
      step: 'Agent loop smoke',
      failureCode: 'agent_loop_failed',
      reason: 'Save to MCP through MCP failed.',
      nextAction: 'Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.',
    }]);
    assert.equal(normalized.reason, 'agent_loop_failed: Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
```

Update imports at the top of `scripts/release-gate-test.mjs`:

```js
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
```

- [ ] **Step 3: Add tests for Markdown child failure rendering**

Append this test:

```js
test('release gate markdown renders connected proof child failures', () => {
  const text = renderReleaseGateMarkdown({
    schemaVersion: '1.0',
    status: 'fail',
    strict: true,
    generatedAt: '2026-06-27T00:00:00.000Z',
    claims: [],
    steps: [{
      name: 'Connected Android proof',
      command: 'npm run android:proof -- --strict --continue',
      status: 'fail',
      durationMs: 10,
      reason: 'agent_loop_failed: Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.',
      childFailures: [{
        scope: 'step',
        step: 'Agent loop smoke',
        failureCode: 'agent_loop_failed',
        reason: 'Save to MCP through MCP failed.',
        nextAction: 'Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.',
      }],
    }],
  });

  assert.match(text, /## Connected Android Proof Details/);
  assert.match(text, /\| Connected Android proof \| Agent loop smoke \| agent_loop_failed \| Inspect the agent-loop smoke report/);
});
```

- [ ] **Step 4: Run release-gate tests and verify failure**

Run:

```bash
npm run release:gate:test
```

Expected: FAIL because claim definitions still point at old evidence names and child failure enrichment does not exist.

- [ ] **Step 5: Import filesystem helpers in release-gate**

In `scripts/release-gate.mjs`, change the import to:

```js
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
```

- [ ] **Step 6: Remap release claim definitions**

Replace the connected Android claim definitions in `scripts/release-gate.mjs` with:

```js
  { id: 'external-agent-loop', evidenceNames: ['Agent loop smoke contracts', 'Connected Android proof'], requireAllEvidence: true },
  { id: 'external-first-handoff-recovery', evidenceNames: ['Agent loop smoke contracts', 'Connected Android proof'], requireAllEvidence: true },
  {
    id: 'first-handoff-autopilot',
    evidenceNames: ['First handoff autopilot CLI contract', 'Agent loop smoke contracts', 'Connected Android proof'],
    requireAllEvidence: true,
  },
  { id: 'external-fixture-matrix', evidenceNames: ['External fixture matrix contracts', 'Connected Android proof'], requireAllEvidence: true },
  { id: 'external-trust-matrix-v2', evidenceNames: ['External fixture matrix contracts', 'Connected Android proof'], requireAllEvidence: true },
```

Replace:

```js
  { id: 'runtime-source-trust', evidenceNames: ['Runtime trust strict'] },
```

with:

```js
  { id: 'runtime-source-trust', evidenceNames: ['Runtime trust boundary observations', 'Connected Android proof'], requireAllEvidence: true },
```

- [ ] **Step 7: Add proof detail helpers**

Add these helpers below `releaseGateSteps()`:

```js
function resolveReportPath(reportPath, root = repoRoot) {
  if (!reportPath) return null;
  return resolve(root, reportPath);
}

export function childFailureReason(step) {
  const failure = step?.childFailures?.[0];
  if (!failure) return null;
  const detail = failure.nextAction || failure.reason || null;
  return [failure.failureCode, detail].filter(Boolean).join(': ') || null;
}

export function readConnectedAndroidProofDetails(step, { root = repoRoot } = {}) {
  if (step.name !== 'Connected Android proof') return null;
  const detailReportPath = step.reportPath || 'build/reports/fixthis-android-proof/report.json';
  const absolutePath = resolveReportPath(detailReportPath, root);
  if (!absolutePath || !existsSync(absolutePath)) return null;
  try {
    const report = JSON.parse(readFileSync(absolutePath, 'utf8'));
    const childFailures = Array.isArray(report.failures) ? report.failures : [];
    return {
      detailReportPath,
      childFailures,
    };
  } catch (_err) {
    return null;
  }
}
```

- [ ] **Step 8: Enrich normalized evidence steps**

Replace `normalizeEvidenceStep` with:

```js
export function normalizeEvidenceStep(step, options = {}) {
  const rawStatus = step.status;
  const status = rawStatus === 'passed' || rawStatus === 'pass'
    ? 'pass'
    : rawStatus === 'deferred'
      ? 'deferred'
      : 'fail';
  const normalized = {
    name: step.name,
    command: step.command,
    status,
    durationMs: step.durationMs ?? 0,
    reason: step.reason || step.stderr?.split('\n').find(Boolean) || null,
    reportPath: step.reportPath || null,
  };
  const details = readConnectedAndroidProofDetails(normalized, options);
  if (!details) return normalized;
  const enriched = {
    ...normalized,
    detailReportPath: details.detailReportPath,
    childFailures: details.childFailures,
  };
  return {
    ...enriched,
    reason: childFailureReason(enriched) || enriched.reason,
  };
}
```

- [ ] **Step 9: Prefer child failure reasons in claim building**

Add this helper below `statusRank`:

```js
function evidenceReason(step) {
  return childFailureReason(step) || step.reason || null;
}
```

In `buildReleaseClaims`, replace:

```js
      : evidence.find((step) => step.reason)?.reason || null;
```

with:

```js
      : evidence.map(evidenceReason).find(Boolean) || null;
```

- [ ] **Step 10: Let buildReleaseGateReport pass normalization options**

Change the signature and normalization line:

```js
export function buildReleaseGateReport({
  strict = false,
  steps,
  generatedAt = new Date().toISOString(),
  normalizeOptions = {},
} = {}) {
  const normalizedSteps = (steps || []).map((step) => normalizeEvidenceStep(step, normalizeOptions));
```

- [ ] **Step 11: Render child failures in Markdown**

In `renderReleaseGateMarkdown`, after the evidence step table loop and before the final return, add:

```js
  const proofDetails = (report.steps || []).filter((step) => (step.childFailures || []).length > 0);
  if (proofDetails.length > 0) {
    lines.push('', '## Connected Android Proof Details', '');
    lines.push('| Evidence | Child step | Failure | Next action |');
    lines.push('| --- | --- | --- | --- |');
    for (const step of proofDetails) {
      for (const failure of step.childFailures || []) {
        lines.push(`| ${cell(step.name)} | ${cell(failure.step || failure.scope)} | ${cell(failure.failureCode)} | ${cell(failure.nextAction || failure.reason)} |`);
      }
    }
  }
```

- [ ] **Step 12: Run release-gate tests and verify pass**

Run:

```bash
npm run release:gate:test
```

Expected: PASS.

- [ ] **Step 13: Commit Task 2**

Run:

```bash
git add scripts/release-gate.mjs scripts/release-gate-test.mjs
git commit -m "feat: consolidate android release gate claims"
```

---

### Task 3: Update Release Evidence Documentation

**Files:**
- Modify: `CONTRIBUTING.md`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/releases/unreleased.md`
- Test: `scripts/check-release-readiness.mjs` only if existing documentation guards require new phrases

**Interfaces:**
- Consumes:
  - `npm run android:proof -- --strict`
  - `npm run release:gate`
  - `npm run release:gate:test`
- Produces:
  - Contributor docs describe integrated proof as the connected release-decision command.
  - Release readiness claim tables use integrated proof plus contract tests.
  - Unreleased notes tell maintainers to attach the integrated proof report for connected evidence.

- [ ] **Step 1: Update CONTRIBUTING connected device wording**

In `CONTRIBUTING.md`, under `## Connected Device Checks`, keep the existing `npm run android:proof -- --strict` section and replace the release-decision evidence command block:

```markdown
For release-decision evidence, run:

```bash
npm run release:drift
npm run release:drift:test
npm run external-fixture:matrix:test
npm run release:gate
npm run release:gate:test
```
```

with:

````markdown
For release-decision evidence, run:

```bash
npm run android:proof -- --strict
npm run release:drift
npm run release:drift:test
npm run external-fixture:matrix:test
npm run release:gate
npm run release:gate:test
```

`release:gate` consumes the integrated connected Android proof report. It should
not be treated as a request to run every connected smoke independently; use the
focused smoke commands above only when debugging the failing child row named in
`build/reports/fixthis-android-proof/report.json`.
````

- [ ] **Step 2: Update release-readiness Trust Loop Completion Evidence**

In `docs/contributing/release-readiness.md`, in `## Trust Loop Completion Evidence`, replace the table rows:

```markdown
| Runtime source-trust fixtures prevent shared-component, interop-risk, visual-area, and weak-candidate evidence from overclaiming exact edit ownership. | `npm run source-matching:fixtures:test`, `npm run source-matching:fixtures:runtime -- --strict`, and `npm run handoff:eval:test`. |
| External Android agent lifecycle completes from handoff through queue read, claim, resolution, persistence, and console reflection. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict`. |
```

with:

```markdown
| Runtime source-trust fixtures prevent shared-component, interop-risk, visual-area, and weak-candidate evidence from overclaiming exact edit ownership. | `npm run source-matching:fixtures:test`, `npm run handoff:eval:test`, and `npm run android:proof -- --strict`. |
| External Android agent lifecycle completes from handoff through queue read, claim, resolution, persistence, and console reflection. | `npm run agent-loop:smoke:test` and `npm run android:proof -- --strict`. |
```

- [ ] **Step 3: Update release-readiness v1.1 Trust Loop Evidence**

In `## v1.1 Trust Loop Evidence`, replace:

```markdown
| External Android agent lifecycle completes from handoff through claim, resolution, persistence, and console reflection. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict`. |
| Runtime source trust keeps shared-component, interop-risk, and visual-area guidance caveated instead of overclaiming exact ownership. | `npm run source-matching:fixtures:test`, `npm run source-matching:fixtures:runtime -- --strict`, and `npm run handoff:eval:test`. |
```

with:

```markdown
| External Android agent lifecycle completes from handoff through claim, resolution, persistence, and console reflection. | `npm run agent-loop:smoke:test` and `npm run android:proof -- --strict`. |
| Runtime source trust keeps shared-component, interop-risk, and visual-area guidance caveated instead of overclaiming exact ownership. | `npm run source-matching:fixtures:test`, `npm run handoff:eval:test`, and `npm run android:proof -- --strict`. |
```

- [ ] **Step 4: Update release-readiness first-handoff sections**

In `## External App First Handoff Recovery`, replace:

```markdown
| External app first handoff reaches one MCP-readable sent item, and failure reports carry `failureCode`, `readiness`, `nextAction`, `verify`, and `fix`. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict`. |
```

with:

```markdown
| External app first handoff reaches one MCP-readable sent item, and failure reports carry `failureCode`, `readiness`, `nextAction`, `verify`, and `fix`. | `npm run agent-loop:smoke:test` and `npm run android:proof -- --strict`. |
```

In `## First Handoff Autopilot Evidence`, replace:

```markdown
| First handoff evidence checks the verify-report action semantics and reaches one MCP-readable sent feedback item. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict` |
```

with:

```markdown
| First handoff evidence checks the verify-report action semantics and reaches one MCP-readable sent feedback item. | `npm run agent-loop:smoke:test` and `npm run android:proof -- --strict` |
```

- [ ] **Step 5: Update release-readiness connected proof and gate closure sections**

In `## Connected Android Proof Runner Evidence`, keep `npm run android:proof:test` and `npm run android:proof -- --strict`.

In `## Release Gate, Interop Evidence, And SSE Closure`, replace the final paragraph:

```markdown
Connected Android evidence remains local-only. If Android SDK or an unlocked
emulator is unavailable, non-strict reports must record the exact deferred
reason and strict reports must fail. When Android is ready,
`npm run runtime-evidence:smoke -- --strict` must create a bounded logcat
evidence row with the raw artifact stored only under ignored `.fixthis/`
storage.
```

with:

```markdown
Connected Android evidence remains local-only and is represented in the release
gate by `npm run android:proof -- --strict`. If Android SDK or an unlocked
emulator is unavailable, non-strict reports must record the exact deferred
reason and strict reports must fail. When Android is ready, the integrated proof
must identify the failing child smoke and point to its local report path.
```

- [ ] **Step 6: Update unreleased connected evidence wording**

In `docs/releases/unreleased.md`, replace the final paragraph:

```markdown
Connected Android evidence remains local-only. If Android SDK or an unlocked
emulator is unavailable, attach the non-strict release-gate report and record
the connected evidence as deferred with the report reason.
```

with:

```markdown
Connected Android evidence remains local-only and is recorded through
`npm run android:proof -- --strict` plus the release-gate report. If Android SDK
or an unlocked emulator is unavailable, attach the non-strict release-gate report
and record the connected evidence as deferred with the proof report reason.
```

- [ ] **Step 7: Run documentation guards**

Run:

```bash
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
```

Expected: both PASS. If `check-release-readiness.mjs` fails because it pins old connected commands, update only the relevant assertion strings in `scripts/check-release-readiness.mjs` and add that file to this task's commit.

- [ ] **Step 8: Commit Task 3**

Run:

```bash
git add CONTRIBUTING.md docs/contributing/release-readiness.md docs/releases/unreleased.md scripts/check-release-readiness.mjs
git commit -m "docs: document consolidated android release evidence"
```

If `scripts/check-release-readiness.mjs` was not modified, omit it from `git add`.

---

### Task 4: Final Verification And Graph Update

**Files:**
- Modify: `graphify-out/` ignored local output only through `graphify update .`
- No committed source file changes expected after prior task commits

**Interfaces:**
- Consumes:
  - Task 1 profile consolidation
  - Task 2 release-gate claim/detail changes
  - Task 3 documentation updates
- Produces:
  - Passing local focused verification
  - Refreshed ignored Graphify graph output
  - Clean committed worktree except expected ignored graph artifacts

- [ ] **Step 1: Run focused verification**

Run:

```bash
npm run evidence:test
npm run release:gate:test
npm run android:proof:test
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: all commands PASS.

- [ ] **Step 2: Run non-strict release gate smoke**

Run:

```bash
npm run release:gate
```

Expected:

- If Android is ready, report status is `pass` or fails only for a real product regression.
- If Android is unavailable, non-strict report status may be `pass_with_deferred`.
- `build/reports/fixthis-release-gate/report.json` contains one connected Android runtime evidence step named `Connected Android proof`.
- The report does not contain separate `Agent loop smoke`, `Real copy prompt smoke`, `External trust matrix v2 strict`, or `Runtime trust strict` gate steps.

- [ ] **Step 3: Refresh Graphify**

Run:

```bash
graphify update .
```

Expected: command completes. Dirty `graphify-out/` files are expected and remain uncommitted.

- [ ] **Step 4: Inspect git status**

Run:

```bash
git status --short
```

Expected: no tracked uncommitted changes. Ignored generated artifacts under `build/`, `.fixthis/`, or `graphify-out/` are not committed.

- [ ] **Step 5: Record final verification in the closeout message**

Include these command results in the implementation closeout:

```text
npm run evidence:test
npm run release:gate:test
npm run android:proof:test
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
git diff --check
npm run release:gate
graphify update .
```
