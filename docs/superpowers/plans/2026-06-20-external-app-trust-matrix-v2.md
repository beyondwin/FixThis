# External App Trust Matrix v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the external fixture matrix so setup evidence, first-handoff runtime evidence, and source/target trust findings are reported as one release-gate claim without overclaiming exact edit ownership.

**Architecture:** Keep `scripts/external-fixture-matrix.mjs` as the matrix owner and add v2 catalog/report helpers there before wiring runtime evidence. Reuse existing proof surfaces (`agent-loop:smoke`, `source-matching:fixtures:runtime`, `release:gate`) instead of inventing a new Android workflow. Release gate consumes the matrix v2 report as an explicit claim and continues to distinguish pass, deferred, fail, and fixture drift.

**Tech Stack:** Node.js 20 test runner, existing FixThis CLI/MCP scripts, Gradle test commands, JSON/Markdown reports under `build/reports`, Markdown release-readiness docs.

## Global Constraints

- FixThis V1 stays local, debug-only Jetpack Compose.
- No public CLI, MCP, bridge, or persisted feedback JSON field rename.
- No new package channel such as PyPI or Docker.
- No release-build support.
- No exact XML/View or WebView source ownership claim.
- No CI-required connected Android gate. Strict connected runs remain local release evidence.
- Preserve persisted feedback fields: `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `targetReliability`, and `sourceCandidates`.
- `:fixthis-compose-core` remains independent of MCP, CLI, Android UI, and `.fixthis/` paths.

---

## File Structure

- Modify `fixtures/external-project-matrix/manifest.json`: add schema-v2 catalog fields for setup-only vs runtime-capable cases and trust expectations.
- Modify `scripts/external-fixture-matrix.mjs`: add manifest v2 validation, trust expectation evaluation, runtime evidence command planning, report rendering, and strict/deferred status aggregation.
- Modify `scripts/external-fixture-matrix-test.mjs`: add fast contract tests for schema v2, trust finding classification, runtime evidence deferral, Markdown report sections, and backward-compatible command planning.
- Modify `scripts/evidence-runner.mjs`: rename or add evidence profile steps so the trust/gate profiles expose the matrix v2 command and report name.
- Modify `scripts/evidence-runner-test.mjs`: assert the evidence profile includes the matrix v2 strict step and preserves Android deferral behavior.
- Modify `scripts/release-gate.mjs`: add `external-trust-matrix-v2` claim mapped to the new evidence step.
- Modify `scripts/release-gate-test.mjs`: test pass/deferred/fail mapping for the new claim.
- Modify `docs/contributing/release-readiness.md`: add an External App Trust Matrix v2 evidence section after the existing external matrix/release drift section.
- Modify `scripts/check-release-readiness.mjs`: enforce the new release-readiness claim text and command references.
- Modify `docs/product/roadmap.md` only if the implementation lands user-visible roadmap language. The default plan does not require it.

## Task 1: Matrix v2 Catalog And Report Shape

**Files:**
- Modify: `fixtures/external-project-matrix/manifest.json`
- Modify: `scripts/external-fixture-matrix.mjs`
- Modify: `scripts/external-fixture-matrix-test.mjs`

**Interfaces:**
- Consumes: Existing `loadExternalMatrixManifest(path)`, `validateExternalMatrixManifest(manifest)`, `buildMatrixReport({ strict, generatedAt, fixtures })`, and `renderMatrixMarkdown(report)`.
- Produces:
  - `runtimeCapabilities`: accepted manifest values `setup-only` and `first-handoff-trust`.
  - `trustExpectationKinds`: accepted values `visual-area`, `interop-boundary`, `shared-component`, and `weak-source`.
  - `validateTrustExpectations(fixture): string[]`.
  - Matrix report fields `schemaVersion: "2.0"`, `summary`, `fixtures[].runtimeCapability`, `fixtures[].trustFindings`, and `fixtures[].outcome`.

- [ ] **Step 1: Write failing schema v2 manifest tests**

Append these tests to `scripts/external-fixture-matrix-test.mjs` after `manifest covers required external project shapes`:

```js
test('manifest v2 declares runtime capability and trust expectations', () => {
  const manifest = loadExternalMatrixManifest(defaultManifestPath);
  const single = manifest.fixtures.find((fixture) => fixture.id === 'single-kotlin-dsl');
  const weak = manifest.fixtures.find((fixture) => fixture.id === 'weak-source-caveated');
  const runtime = manifest.fixtures.find((fixture) => fixture.id === 'local-sample-first-handoff');

  assert.equal(manifest.schemaVersion, 2);
  assert.equal(single.runtimeCapability, 'setup-only');
  assert.equal(weak.runtimeCapability, 'setup-only');
  assert.deepEqual(weak.trustExpectations, [{
    kind: 'weak-source',
    mustNotHighConfidence: true,
    mustWarn: ['WEAK_SOURCE_EVIDENCE'],
  }]);
  assert.equal(runtime.runtimeCapability, 'first-handoff-trust');
  assert.deepEqual(runtime.trustExpectations.map((entry) => entry.kind), [
    'visual-area',
    'interop-boundary',
    'shared-component',
  ]);
});

test('manifest v2 rejects invalid runtime capability and trust expectations', () => {
  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 2,
      fixtures: [{
        id: 'bad-runtime',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: ':app',
        moduleDir: 'app',
        applicationId: 'io.example.bad',
        variant: 'debug',
        expectedSetup: 'ready',
        runtimeCapability: 'browser-cloud',
        trustExpectations: [],
      }],
    }),
    /bad-runtime runtimeCapability is unsupported/,
  );

  assert.throws(
    () => validateExternalMatrixManifest({
      schemaVersion: 2,
      fixtures: [{
        id: 'bad-trust',
        projectShape: 'single-module',
        dsl: 'kotlin',
        appModule: ':app',
        moduleDir: 'app',
        applicationId: 'io.example.bad',
        variant: 'debug',
        expectedSetup: 'ready',
        runtimeCapability: 'setup-only',
        trustExpectations: [{ kind: 'exact-webview-source', mustWarn: 'POSSIBLE_VIEW_INTEROP' }],
      }],
    }),
    /bad-trust trustExpectations\[0\] kind is unsupported.*mustWarn must be an array/s,
  );
});
```

- [ ] **Step 2: Run tests to verify the manifest tests fail**

Run:

```bash
npm run external-fixture:matrix:test
```

Expected: FAIL because `schemaVersion` is still `1`, `runtimeCapability` is missing, and `weak-source-caveated` / `local-sample-first-handoff` do not exist.

- [ ] **Step 3: Update the manifest to schema v2**

Replace `fixtures/external-project-matrix/manifest.json` with this content:

```json
{
  "schemaVersion": 2,
  "fixtures": [
    {
      "id": "single-kotlin-dsl",
      "projectShape": "single-module",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.single",
      "variant": "debug",
      "expectedSetup": "ready",
      "runtimeCapability": "setup-only",
      "trustExpectations": []
    },
    {
      "id": "multi-module-non-root-app",
      "projectShape": "multi-module",
      "dsl": "kotlin",
      "appModule": ":features:demo-app",
      "moduleDir": "features/demo-app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.multimodule",
      "variant": "debug",
      "expectedSetup": "ready",
      "runtimeCapability": "setup-only",
      "trustExpectations": []
    },
    {
      "id": "flavored-application-id",
      "projectShape": "single-module-flavor",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.flavor.demo",
      "variant": "demoDebug",
      "expectedSetup": "ready",
      "runtimeCapability": "setup-only",
      "trustExpectations": []
    },
    {
      "id": "version-catalog",
      "projectShape": "version-catalog",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.catalog",
      "variant": "debug",
      "expectedSetup": "ready",
      "runtimeCapability": "setup-only",
      "trustExpectations": []
    },
    {
      "id": "preexisting-agent-config",
      "projectShape": "preexisting-agent-config",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.config",
      "variant": "debug",
      "expectedSetup": "ready",
      "runtimeCapability": "setup-only",
      "trustExpectations": []
    },
    {
      "id": "missing-generated-metadata",
      "projectShape": "missing-generated-metadata",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.recovery",
      "variant": "debug",
      "expectedSetup": "needs-fixthis-setup",
      "runtimeCapability": "setup-only",
      "trustExpectations": []
    },
    {
      "id": "weak-source-caveated",
      "projectShape": "single-module",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "app",
      "applicationId": "io.github.beyondwin.fixthis.matrix.weaksource",
      "variant": "debug",
      "expectedSetup": "ready",
      "runtimeCapability": "setup-only",
      "trustExpectations": [
        {
          "kind": "weak-source",
          "mustNotHighConfidence": true,
          "mustWarn": ["WEAK_SOURCE_EVIDENCE"]
        }
      ]
    },
    {
      "id": "local-sample-first-handoff",
      "projectShape": "single-module",
      "dsl": "kotlin",
      "appModule": ":app",
      "moduleDir": "sample",
      "applicationId": "io.github.beyondwin.fixthis.sample",
      "variant": "debug",
      "expectedSetup": "ready",
      "runtimeCapability": "first-handoff-trust",
      "trustExpectations": [
        {
          "kind": "visual-area",
          "mustNotHighConfidence": true,
          "mustWarn": ["VISUAL_AREA_ONLY"]
        },
        {
          "kind": "interop-boundary",
          "mustNotExactOwnership": true,
          "mustWarn": ["POSSIBLE_VIEW_INTEROP"]
        },
        {
          "kind": "shared-component",
          "mustNotHighConfidence": true,
          "mustRisk": ["SHARED_COMPONENT"]
        }
      ]
    }
  ]
}
```

- [ ] **Step 4: Implement schema v2 validation**

In `scripts/external-fixture-matrix.mjs`, replace the manifest constants and validation with this code:

```js
const allowedShapes = new Set([
  'single-module',
  'multi-module',
  'single-module-flavor',
  'version-catalog',
  'preexisting-agent-config',
  'missing-generated-metadata',
]);
const allowedExpectedSetup = new Set(['ready', 'needs-fixthis-setup']);
export const runtimeCapabilities = Object.freeze(['setup-only', 'first-handoff-trust']);
export const trustExpectationKinds = Object.freeze([
  'visual-area',
  'interop-boundary',
  'shared-component',
  'weak-source',
]);

function arrayOfStrings(value, fieldName) {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.some((entry) => typeof entry !== 'string' || entry.length === 0)) {
    throw new Error(`${fieldName} must be an array of non-empty strings`);
  }
  return value;
}

export function validateTrustExpectations(fixture) {
  const errors = [];
  const expectations = fixture.trustExpectations ?? [];
  if (!Array.isArray(expectations)) return [`${fixture.id || 'fixture'} trustExpectations must be an array`];
  expectations.forEach((expectation, index) => {
    const label = `${fixture.id || 'fixture'} trustExpectations[${index}]`;
    if (!trustExpectationKinds.includes(expectation?.kind)) errors.push(`${label} kind is unsupported`);
    try { arrayOfStrings(expectation.mustWarn, `${label} mustWarn`); } catch (error) { errors.push(error.message); }
    try { arrayOfStrings(expectation.mustRisk, `${label} mustRisk`); } catch (error) { errors.push(error.message); }
    if (expectation.mustNotHighConfidence !== undefined && typeof expectation.mustNotHighConfidence !== 'boolean') {
      errors.push(`${label} mustNotHighConfidence must be boolean`);
    }
    if (expectation.mustNotExactOwnership !== undefined && typeof expectation.mustNotExactOwnership !== 'boolean') {
      errors.push(`${label} mustNotExactOwnership must be boolean`);
    }
  });
  return errors;
}
```

Then update `validateExternalMatrixManifest`:

```js
export function validateExternalMatrixManifest(manifest) {
  const errors = [];
  if (!manifest || typeof manifest !== 'object') errors.push('manifest must be an object');
  if (manifest?.schemaVersion !== 2) errors.push('schemaVersion must be 2');
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
    if (!runtimeCapabilities.includes(fixture.runtimeCapability)) errors.push(`${label} runtimeCapability is unsupported`);
    errors.push(...validateTrustExpectations(fixture));
  }
  if (errors.length) throw new Error(errors.join('; '));
  return manifest;
}
```

- [ ] **Step 5: Add report v2 summary rendering tests**

Append this test to `scripts/external-fixture-matrix-test.mjs` after `matrix report and markdown include fixture command statuses`:

```js
test('matrix v2 report summarizes outcomes and trust findings', () => {
  const report = buildMatrixReport({
    strict: false,
    generatedAt: '2026-06-20T00:00:00.000Z',
    fixtures: [
      {
        fixtureId: 'weak-source-caveated',
        status: 'pass',
        outcome: 'caveated_pass',
        runtimeCapability: 'setup-only',
        trustFindings: [{ kind: 'weak-source', status: 'pass', message: 'weak evidence stayed caveated' }],
        commands: [{ name: 'doctor-json', command: 'fixthis doctor --json', status: 'pass', durationMs: 5 }],
      },
      {
        fixtureId: 'local-sample-first-handoff',
        status: 'deferred',
        outcome: 'environment_deferred',
        runtimeCapability: 'first-handoff-trust',
        reason: 'Android SDK or ready emulator is unavailable.',
        trustFindings: [],
        commands: [],
      },
    ],
  });

  assert.equal(report.schemaVersion, '2.0');
  assert.deepEqual(report.summary, {
    total: 2,
    pass: 1,
    deferred: 1,
    fail: 0,
    fixtureDrift: 0,
    caveatedPass: 1,
  });
  const markdown = renderMatrixMarkdown(report);
  assert.match(markdown, /## Summary/);
  assert.match(markdown, /\| Caveated pass \| 1 \|/);
  assert.match(markdown, /## Trust Findings/);
  assert.match(markdown, /\| weak-source-caveated \| weak-source \| pass \| weak evidence stayed caveated \|/);
});
```

- [ ] **Step 6: Implement report v2 summary and Markdown sections**

In `scripts/external-fixture-matrix.mjs`, replace `reportStatus` and `buildMatrixReport` with:

```js
function reportStatus(fixtures, strict = false) {
  if ((fixtures || []).some((fixture) => fixture.status === 'fail')) return 'fail';
  if (strict && (fixtures || []).some((fixture) => fixture.status === 'deferred')) return 'fail';
  if ((fixtures || []).some((fixture) => fixture.status === 'deferred')) return 'pass_with_deferred';
  return 'pass';
}

function reportSummary(fixtures = []) {
  return {
    total: fixtures.length,
    pass: fixtures.filter((fixture) => fixture.status === 'pass').length,
    deferred: fixtures.filter((fixture) => fixture.status === 'deferred').length,
    fail: fixtures.filter((fixture) => fixture.status === 'fail').length,
    fixtureDrift: fixtures.filter((fixture) => fixture.outcome === 'fixture_drift').length,
    caveatedPass: fixtures.filter((fixture) => fixture.outcome === 'caveated_pass').length,
  };
}

export function buildMatrixReport({ strict = false, generatedAt = new Date().toISOString(), fixtures = [] } = {}) {
  return {
    schemaVersion: '2.0',
    status: reportStatus(fixtures, strict),
    strict,
    generatedAt,
    summary: reportSummary(fixtures),
    fixtures,
  };
}
```

Then replace `renderMatrixMarkdown(report)` with:

```js
export function renderMatrixMarkdown(report) {
  const lines = [
    '# FixThis External Fixture Matrix Report',
    '',
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Schema: ${report.schemaVersion}`,
    '',
    '## Summary',
    '',
    '| Metric | Count |',
    '| --- | --- |',
    `| Total | ${cell(report.summary?.total ?? 0)} |`,
    `| Pass | ${cell(report.summary?.pass ?? 0)} |`,
    `| Deferred | ${cell(report.summary?.deferred ?? 0)} |`,
    `| Fail | ${cell(report.summary?.fail ?? 0)} |`,
    `| Fixture drift | ${cell(report.summary?.fixtureDrift ?? 0)} |`,
    `| Caveated pass | ${cell(report.summary?.caveatedPass ?? 0)} |`,
    '',
    '## Fixtures',
    '',
    '| Fixture | Status | Outcome | Runtime capability | Reason |',
    '| --- | --- | --- | --- | --- |',
  ];
  for (const fixture of report.fixtures || []) {
    lines.push(`| ${cell(fixture.fixtureId)} | ${cell(fixture.status)} | ${cell(fixture.outcome)} | ${cell(fixture.runtimeCapability)} | ${cell(fixture.reason)} |`);
  }
  lines.push('', '## Trust Findings', '', '| Fixture | Kind | Status | Message |', '| --- | --- | --- | --- |');
  for (const fixture of report.fixtures || []) {
    for (const finding of fixture.trustFindings || []) {
      lines.push(`| ${cell(fixture.fixtureId)} | ${cell(finding.kind)} | ${cell(finding.status)} | ${cell(finding.message)} |`);
    }
  }
  lines.push('', '## Commands', '', '| Fixture | Step | Status | Command | Duration |', '| --- | --- | --- | --- | --- |');
  for (const fixture of report.fixtures || []) {
    for (const command of fixture.commands || []) {
      lines.push(`| ${cell(fixture.fixtureId)} | ${cell(command.name)} | ${cell(command.status)} | \`${cell(command.command)}\` | ${cell(command.durationMs ?? 0)}ms |`);
    }
  }
  return `${lines.join('\n')}\n`;
}
```

- [ ] **Step 7: Run matrix contract tests**

Run:

```bash
npm run external-fixture:matrix:test
```

Expected: PASS.

- [ ] **Step 8: Commit Task 1**

```bash
git add fixtures/external-project-matrix/manifest.json scripts/external-fixture-matrix.mjs scripts/external-fixture-matrix-test.mjs
git commit -m "feat: add external trust matrix v2 catalog"
```

## Task 2: Trust Expectation Classification

**Files:**
- Modify: `scripts/external-fixture-matrix.mjs`
- Modify: `scripts/external-fixture-matrix-test.mjs`

**Interfaces:**
- Consumes: `fixture.trustExpectations` from Task 1.
- Produces:
  - `confidenceRank(value): number`.
  - `evaluateTrustExpectations(fixture, observation): Array<{ kind, status, message }>` where status is `pass` or `fail`.
  - `outcomeForFixture({ status, trustFindings, reason }): string`.

- [ ] **Step 1: Write failing pure trust classification tests**

Append these tests to `scripts/external-fixture-matrix-test.mjs` after `matrix v2 report summarizes outcomes and trust findings`:

```js
test('evaluateTrustExpectations passes caveated visual interop and shared-component observations', () => {
  const fixture = {
    id: 'local-sample-first-handoff',
    trustExpectations: [
      { kind: 'visual-area', mustNotHighConfidence: true, mustWarn: ['VISUAL_AREA_ONLY'] },
      { kind: 'interop-boundary', mustNotExactOwnership: true, mustWarn: ['POSSIBLE_VIEW_INTEROP'] },
      { kind: 'shared-component', mustNotHighConfidence: true, mustRisk: ['SHARED_COMPONENT'] },
    ],
  };
  const observation = {
    targetReliability: {
      confidence: 'medium',
      warnings: ['VISUAL_AREA_ONLY', 'POSSIBLE_VIEW_INTEROP'],
    },
    sourceCandidates: [{
      confidence: 'medium',
      riskFlags: ['SHARED_COMPONENT'],
      callSites: [{ file: 'sample/Home.kt', line: 42, recommendedEditSite: true }],
    }],
    exactOwnershipClaimed: false,
  };

  assert.deepEqual(evaluateTrustExpectations(fixture, observation), [
    { kind: 'visual-area', status: 'pass', message: 'confidence medium with warnings VISUAL_AREA_ONLY' },
    { kind: 'interop-boundary', status: 'pass', message: 'no exact ownership claim and warnings POSSIBLE_VIEW_INTEROP' },
    { kind: 'shared-component', status: 'pass', message: 'confidence medium with risk flags SHARED_COMPONENT' },
  ]);
});

test('evaluateTrustExpectations fails high confidence missing warning and exact ownership', () => {
  const fixture = {
    id: 'bad-runtime',
    trustExpectations: [
      { kind: 'visual-area', mustNotHighConfidence: true, mustWarn: ['VISUAL_AREA_ONLY'] },
      { kind: 'interop-boundary', mustNotExactOwnership: true, mustWarn: ['POSSIBLE_VIEW_INTEROP'] },
    ],
  };
  const observation = {
    targetReliability: {
      confidence: 'high',
      warnings: [],
    },
    sourceCandidates: [{ confidence: 'high', riskFlags: [] }],
    exactOwnershipClaimed: true,
  };

  assert.deepEqual(evaluateTrustExpectations(fixture, observation), [
    { kind: 'visual-area', status: 'fail', message: 'high confidence or missing warnings: VISUAL_AREA_ONLY' },
    { kind: 'interop-boundary', status: 'fail', message: 'exact ownership claimed or missing warnings: POSSIBLE_VIEW_INTEROP' },
  ]);
});

test('outcomeForFixture separates caveated pass failure deferred and fixture drift', () => {
  assert.equal(outcomeForFixture({
    status: 'pass',
    trustFindings: [{ kind: 'weak-source', status: 'pass', message: 'caveated' }],
  }), 'caveated_pass');
  assert.equal(outcomeForFixture({
    status: 'fail',
    reason: 'fixture_drift: expected path disappeared',
    trustFindings: [],
  }), 'fixture_drift');
  assert.equal(outcomeForFixture({
    status: 'deferred',
    reason: 'Android SDK unavailable',
    trustFindings: [],
  }), 'environment_deferred');
  assert.equal(outcomeForFixture({
    status: 'fail',
    reason: 'high confidence or missing warnings',
    trustFindings: [{ kind: 'visual-area', status: 'fail', message: 'high confidence' }],
  }), 'product_failure');
});
```

Add these imports to the top-level import list from `./external-fixture-matrix.mjs`:

```js
  evaluateTrustExpectations,
  outcomeForFixture,
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
npm run external-fixture:matrix:test
```

Expected: FAIL with missing export errors for `evaluateTrustExpectations` and `outcomeForFixture`.

- [ ] **Step 3: Implement trust classification helpers**

Add these functions to `scripts/external-fixture-matrix.mjs` after `externalMatrixStatusForEnvironment`:

```js
export function confidenceRank(value) {
  if (value === 'high') return 3;
  if (value === 'medium') return 2;
  if (value === 'low') return 1;
  return 0;
}

function observationWarnings(observation) {
  return new Set(observation?.targetReliability?.warnings || []);
}

function observationRiskFlags(observation) {
  return new Set((observation?.sourceCandidates || []).flatMap((candidate) => candidate.riskFlags || []));
}

function observationConfidence(observation) {
  const target = confidenceRank(observation?.targetReliability?.confidence);
  const source = Math.max(0, ...(observation?.sourceCandidates || []).map((candidate) => confidenceRank(candidate.confidence)));
  return Math.max(target, source);
}

function missingValues(required, available) {
  return (required || []).filter((entry) => !available.has(entry));
}

function passFinding(kind, message) {
  return { kind, status: 'pass', message };
}

function failFinding(kind, message) {
  return { kind, status: 'fail', message };
}

export function evaluateTrustExpectations(fixture, observation = {}) {
  const warnings = observationWarnings(observation);
  const risks = observationRiskFlags(observation);
  const confidence = observationConfidence(observation);
  return (fixture.trustExpectations || []).map((expectation) => {
    const missingWarn = missingValues(expectation.mustWarn, warnings);
    const missingRisk = missingValues(expectation.mustRisk, risks);
    const highConfidence = expectation.mustNotHighConfidence === true && confidence >= confidenceRank('high');
    const exactOwnership = expectation.mustNotExactOwnership === true && observation.exactOwnershipClaimed === true;

    if (expectation.kind === 'interop-boundary') {
      if (exactOwnership || missingWarn.length > 0) {
        return failFinding(expectation.kind, `exact ownership claimed or missing warnings: ${missingWarn.join(', ') || 'none'}`);
      }
      return passFinding(expectation.kind, `no exact ownership claim and warnings ${(expectation.mustWarn || []).join(', ')}`);
    }

    if (missingRisk.length > 0) {
      return failFinding(expectation.kind, `missing risk flags: ${missingRisk.join(', ')}`);
    }
    if (highConfidence || missingWarn.length > 0) {
      return failFinding(expectation.kind, `high confidence or missing warnings: ${missingWarn.join(', ') || 'none'}`);
    }
    if ((expectation.mustRisk || []).length > 0) {
      return passFinding(expectation.kind, `confidence ${observation?.targetReliability?.confidence || observation?.sourceCandidates?.[0]?.confidence || 'unknown'} with risk flags ${(expectation.mustRisk || []).join(', ')}`);
    }
    return passFinding(expectation.kind, `confidence ${observation?.targetReliability?.confidence || observation?.sourceCandidates?.[0]?.confidence || 'unknown'} with warnings ${(expectation.mustWarn || []).join(', ')}`);
  });
}

export function outcomeForFixture({ status, reason = null, trustFindings = [] } = {}) {
  if (reason && String(reason).startsWith('fixture_drift:')) return 'fixture_drift';
  if (status === 'deferred') return 'environment_deferred';
  if (status === 'fail') return 'product_failure';
  if (trustFindings.some((finding) => finding.status === 'pass')) return 'caveated_pass';
  return 'pass';
}
```

- [ ] **Step 4: Write failing integration test for trust findings in runExternalMatrix**

Append this test to `scripts/external-fixture-matrix-test.mjs` after `runExternalMatrix executes non-connected commands with injected runner`:

```js
test('runExternalMatrix evaluates setup-only trust observations when provided', () => {
  const manifest = {
    schemaVersion: 2,
    fixtures: [{
      id: 'weak-source-caveated',
      projectShape: 'single-module',
      dsl: 'kotlin',
      appModule: ':app',
      moduleDir: 'app',
      applicationId: 'io.github.beyondwin.fixthis.matrix.weaksource',
      variant: 'debug',
      expectedSetup: 'ready',
      runtimeCapability: 'setup-only',
      trustExpectations: [{ kind: 'weak-source', mustNotHighConfidence: true, mustWarn: ['WEAK_SOURCE_EVIDENCE'] }],
    }],
  };
  const report = runExternalMatrix({
    manifest,
    strict: false,
    workRoot: '/tmp/fixthis-matrix',
    androidEnvironment: { ready: true, reason: null, envPatch: {} },
    root: '/repo',
    runCommandFn: () => ({ status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 }),
    prepareCliDistributionFn: () => ({ name: 'prepare-cli', command: './gradlew :fixthis-cli:installDist --no-daemon', status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 }),
    generateFixtureProjectFn: () => {},
    cleanupFixtureFn: () => {},
    trustObservationFn: () => ({
      targetReliability: { confidence: 'medium', warnings: ['WEAK_SOURCE_EVIDENCE'] },
      sourceCandidates: [{ confidence: 'medium', riskFlags: [] }],
      exactOwnershipClaimed: false,
    }),
  });

  assert.equal(report.fixtures[0].status, 'pass');
  assert.equal(report.fixtures[0].outcome, 'caveated_pass');
  assert.deepEqual(report.fixtures[0].trustFindings, [{
    kind: 'weak-source',
    status: 'pass',
    message: 'confidence medium with warnings WEAK_SOURCE_EVIDENCE',
  }]);
});
```

- [ ] **Step 5: Wire trust observations into runExternalMatrix**

Update `runExternalMatrix` signature in `scripts/external-fixture-matrix.mjs`:

```js
export function runExternalMatrix({
  manifest = loadExternalMatrixManifest(),
  strict = false,
  workRoot = defaultMatrixWorkRoot,
  root = repoRoot,
  androidEnvironment = resolveAndroidEnvironment(),
  runCommandFn = (command, cwd, envPatch) => runCommand(command, cwd, envPatch),
  prepareCliDistributionFn = (activeRoot, envPatch, runner) => prepareCliDistribution(activeRoot, envPatch, runner),
  generateFixtureProjectFn = generateFixtureProject,
  cleanupFixtureFn = cleanupFixture,
  trustObservationFn = () => null,
} = {}) {
```

In the `!androidEnvironment.ready` branch, push this fixture object:

```js
fixtures.push({
  fixtureId: fixture.id,
  status: environment.status,
  outcome: outcomeForFixture({ status: environment.status, reason: environment.reason, trustFindings: [] }),
  runtimeCapability: fixture.runtimeCapability,
  reason: environment.reason,
  trustFindings: [],
  commands: planFixtureCommands(fixture, projectDir, root).map((entry) => ({ ...entry, status: 'deferred', durationMs: 0 })),
});
```

At the end of the ready branch, replace the current `fixtures.push({ fixtureId: fixture.id, status, reason, commands });` with:

```js
const observation = status === 'pass' ? trustObservationFn(fixture) : null;
const trustFindings = observation ? evaluateTrustExpectations(fixture, observation) : [];
if (trustFindings.some((finding) => finding.status === 'fail')) {
  status = 'fail';
  reason = trustFindings.find((finding) => finding.status === 'fail')?.message || 'trust expectation failed';
}
fixtures.push({
  fixtureId: fixture.id,
  status,
  outcome: outcomeForFixture({ status, reason, trustFindings }),
  runtimeCapability: fixture.runtimeCapability,
  reason,
  trustFindings,
  commands,
});
```

Also update the prepare-cli failure fixture object to include:

```js
outcome: 'product_failure',
runtimeCapability: fixture.runtimeCapability,
trustFindings: [],
```

- [ ] **Step 6: Run matrix contract tests**

Run:

```bash
npm run external-fixture:matrix:test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 2**

```bash
git add scripts/external-fixture-matrix.mjs scripts/external-fixture-matrix-test.mjs
git commit -m "feat: classify external trust findings"
```

## Task 3: Runtime Evidence Commands And Matrix v2 Strict Behavior

**Files:**
- Modify: `scripts/external-fixture-matrix.mjs`
- Modify: `scripts/external-fixture-matrix-test.mjs`
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`

**Interfaces:**
- Consumes: `fixture.runtimeCapability === "first-handoff-trust"` from Task 1.
- Produces:
  - `planRuntimeTrustCommands(fixture): Array<{ name, command }>` for runtime-capable fixtures.
  - `normalizeRuntimeTrustResult(entry, result): object`.
  - Evidence profile step name `External trust matrix v2 strict`.

- [ ] **Step 1: Write failing runtime command planning tests**

Append these tests to `scripts/external-fixture-matrix-test.mjs` after `planFixtureCommands uses install-agent doctor and source-index commands`:

```js
test('planRuntimeTrustCommands adds handoff and runtime trust checks for runtime-capable fixtures', () => {
  const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'local-sample-first-handoff');
  const commands = planRuntimeTrustCommands(fixture);

  assert.deepEqual(commands, [
    { name: 'agent-loop-smoke', command: 'npm run agent-loop:smoke -- --strict' },
    { name: 'runtime-trust-strict', command: 'npm run source-matching:fixtures:runtime -- --strict' },
  ]);
});

test('planRuntimeTrustCommands returns no commands for setup-only fixtures', () => {
  const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'single-kotlin-dsl');
  assert.deepEqual(planRuntimeTrustCommands(fixture), []);
});
```

Add `planRuntimeTrustCommands` to the import list from `./external-fixture-matrix.mjs`.

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
npm run external-fixture:matrix:test
```

Expected: FAIL with missing export `planRuntimeTrustCommands`.

- [ ] **Step 3: Implement runtime command planning**

Add this export after `planFixtureCommands` in `scripts/external-fixture-matrix.mjs`:

```js
export function planRuntimeTrustCommands(fixture) {
  if (fixture.runtimeCapability !== 'first-handoff-trust') return [];
  return [
    { name: 'agent-loop-smoke', command: 'npm run agent-loop:smoke -- --strict' },
    { name: 'runtime-trust-strict', command: 'npm run source-matching:fixtures:runtime -- --strict' },
  ];
}
```

- [ ] **Step 4: Write failing strict runtime matrix behavior tests**

Append these tests to `scripts/external-fixture-matrix-test.mjs` after `runExternalMatrix keeps deferred fixtures out of command execution when Android is missing`:

```js
test('runExternalMatrix executes runtime trust commands for runtime-capable fixture', () => {
  const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'local-sample-first-handoff');
  const calls = [];
  const report = runExternalMatrix({
    manifest: { schemaVersion: 2, fixtures: [fixture] },
    strict: true,
    workRoot: '/tmp/fixthis-matrix',
    androidEnvironment: { ready: true, reason: null, envPatch: { ANDROID_HOME: '/sdk' } },
    root: '/repo',
    runCommandFn: (command) => {
      calls.push(command);
      return { status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 };
    },
    prepareCliDistributionFn: () => ({ name: 'prepare-cli', command: './gradlew :fixthis-cli:installDist --no-daemon', status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 }),
    generateFixtureProjectFn: () => {},
    cleanupFixtureFn: () => {},
    trustObservationFn: () => ({
      targetReliability: { confidence: 'medium', warnings: ['VISUAL_AREA_ONLY', 'POSSIBLE_VIEW_INTEROP'] },
      sourceCandidates: [{ confidence: 'medium', riskFlags: ['SHARED_COMPONENT'] }],
      exactOwnershipClaimed: false,
    }),
  });

  assert.ok(calls.includes('npm run agent-loop:smoke -- --strict'));
  assert.ok(calls.includes('npm run source-matching:fixtures:runtime -- --strict'));
  assert.equal(report.fixtures[0].status, 'pass');
});

test('runExternalMatrix marks runtime trust command failure as product failure', () => {
  const fixture = loadExternalMatrixManifest(defaultManifestPath).fixtures.find((entry) => entry.id === 'local-sample-first-handoff');
  const report = runExternalMatrix({
    manifest: { schemaVersion: 2, fixtures: [fixture] },
    strict: true,
    workRoot: '/tmp/fixthis-matrix',
    androidEnvironment: { ready: true, reason: null, envPatch: {} },
    root: '/repo',
    runCommandFn: (command) => {
      if (command === 'npm run source-matching:fixtures:runtime -- --strict') {
        return { status: 'fail', durationMs: 1, stdout: '', stderr: 'missing_warning_observation\n', exitCode: 1 };
      }
      return { status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 };
    },
    prepareCliDistributionFn: () => ({ name: 'prepare-cli', command: './gradlew :fixthis-cli:installDist --no-daemon', status: 'pass', durationMs: 1, stdout: '', stderr: '', exitCode: 0 }),
    generateFixtureProjectFn: () => {},
    cleanupFixtureFn: () => {},
  });

  assert.equal(report.status, 'fail');
  assert.equal(report.fixtures[0].status, 'fail');
  assert.equal(report.fixtures[0].outcome, 'product_failure');
  assert.equal(report.fixtures[0].reason, 'missing_warning_observation');
});
```

- [ ] **Step 5: Execute runtime commands inside runExternalMatrix**

Inside the ready branch of `runExternalMatrix`, after the loop over `planFixtureCommands`, add this block before `cleanupFixtureFn(projectDir)`:

```js
if (status === 'pass') {
  for (const entry of planRuntimeTrustCommands(fixture)) {
    const result = runCommandFn(entry.command, root, androidEnvironment.envPatch);
    commands.push({ ...entry, ...result });
    if (result.status === 'fail') {
      status = 'fail';
      reason = result.stderr?.split('\n').find(Boolean) || result.stdout?.split('\n').find(Boolean) || `${entry.name} failed`;
      break;
    }
  }
}
```

This deliberately runs runtime evidence from the repository root because `agent-loop:smoke` and `source-matching:fixtures:runtime` are repo-level evidence surfaces, not generated fixture Gradle tasks.

- [ ] **Step 6: Write failing evidence profile tests**

In `scripts/evidence-runner-test.mjs`, add this test after the existing test that checks trust profile commands:

```js
test('trust and gate profiles include external trust matrix v2 strict evidence', () => {
  const trustCommands = expandProfile('trust').map((step) => step.command);
  const gateCommands = expandProfile('gate').map((step) => step.command);

  assert.ok(trustCommands.includes('npm run external-fixture:matrix -- --strict'));
  assert.ok(gateCommands.includes('npm run external-fixture:matrix -- --strict'));

  const trustStep = expandProfile('trust').find((step) => step.command === 'npm run external-fixture:matrix -- --strict');
  assert.equal(trustStep.name, 'External trust matrix v2 strict');
  assert.equal(trustStep.deferrable, true);
  assert.equal(trustStep.requiresAndroid, true);
});
```

- [ ] **Step 7: Rename evidence step labels**

In `scripts/evidence-runner.mjs`, replace both instances of:

```js
step("External fixture matrix strict", "npm run external-fixture:matrix -- --strict", {
```

with:

```js
step("External trust matrix v2 strict", "npm run external-fixture:matrix -- --strict", {
```

Keep command, `deferrable`, and `requiresAndroid` unchanged.

- [ ] **Step 8: Update tests that still assert the old step name**

In `scripts/release-gate-test.mjs` and `scripts/evidence-runner-test.mjs`, replace old expected display names:

```js
'External fixture matrix strict'
```

with:

```js
'External trust matrix v2 strict'
```

Do not change the npm command.

- [ ] **Step 9: Run focused tests**

Run:

```bash
npm run external-fixture:matrix:test
npm run evidence:test
npm run release:gate:test
```

Expected: PASS for all three commands.

- [ ] **Step 10: Commit Task 3**

```bash
git add scripts/external-fixture-matrix.mjs scripts/external-fixture-matrix-test.mjs scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
git commit -m "feat: wire external trust runtime evidence"
```

## Task 4: Release Gate Claim And Documentation

**Files:**
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`

**Interfaces:**
- Consumes: Evidence step `External trust matrix v2 strict`.
- Produces: Release claim `{ id: "external-trust-matrix-v2", evidence: ["External trust matrix v2 strict"] }`.

- [ ] **Step 1: Write failing release gate claim test**

Append this test to `scripts/release-gate-test.mjs` after `release gate maps external fixture matrix claim`:

```js
test('release gate maps external trust matrix v2 claim', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-20T00:00:00.000Z',
    steps: [
      {
        name: 'External trust matrix v2 strict',
        command: 'npm run external-fixture:matrix -- --strict',
        status: 'deferred',
        reason: 'Android SDK unavailable',
      },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'external-trust-matrix-v2'), {
    id: 'external-trust-matrix-v2',
    status: 'deferred',
    evidence: ['External trust matrix v2 strict'],
    reason: 'Android SDK unavailable',
  });
});
```

- [ ] **Step 2: Run release gate tests to verify failure**

Run:

```bash
npm run release:gate:test
```

Expected: FAIL because `external-trust-matrix-v2` is not in `releaseClaimDefinitions`.

- [ ] **Step 3: Add release claim definition**

In `scripts/release-gate.mjs`, insert this claim immediately after the existing `external-fixture-matrix` definition:

```js
  { id: 'external-trust-matrix-v2', evidenceNames: ['External trust matrix v2 strict'] },
```

Keep the existing `external-fixture-matrix` claim for compatibility with older reports until a separate cleanup removes it.

- [ ] **Step 4: Update existing full claim snapshot test**

In `scripts/release-gate-test.mjs`, in `release gate report maps evidence steps to unlocked claims`, add this expected claim immediately after the `external-fixture-matrix` claim:

```js
    {
      id: 'external-trust-matrix-v2',
      status: 'fail',
      evidence: [],
      reason: 'missing evidence command',
    },
```

- [ ] **Step 5: Add release-readiness docs**

In `docs/contributing/release-readiness.md`, add this section after `## External Trust Matrix And Release Drift Evidence`:

```markdown
## External App Trust Matrix v2 Evidence

The External App Trust Matrix v2 line may be claimed only when the release
commit has evidence that external project setup, first-handoff runtime proof,
and handoff trust caveats are reported in one matrix.

| Claim | Required evidence |
| --- | --- |
| External project setup and runtime-capable handoff trust are classified as pass, deferred, fail, fixture drift, or caveated pass without overclaiming exact source ownership. | `npm run external-fixture:matrix:test` and `npm run external-fixture:matrix -- --strict`. |
| Release gate consumes the matrix v2 report as the `external-trust-matrix-v2` claim. | `npm run release:gate:test` and `npm run release:gate`. |

When Android SDK, ADB, an unlocked emulator/device, or the launched debug app is
unavailable, non-strict reports must record the exact deferred reason and
strict connected evidence must fail. A caveated pass is acceptable only when
the handoff preserves the required warning or risk signal, such as
`VISUAL_AREA_ONLY`, `POSSIBLE_VIEW_INTEROP`, or `SHARED_COMPONENT`.
```

- [ ] **Step 6: Add release-readiness guard rules**

In `scripts/check-release-readiness.mjs`, add these rules near the existing external fixture matrix checks:

```js
requireRegex(
  'R50.external-trust-matrix-v2-evidence',
  'docs/contributing/release-readiness.md',
  /## External App Trust Matrix v2 Evidence[\s\S]*npm run external-fixture:matrix:test[\s\S]*npm run external-fixture:matrix -- --strict[\s\S]*external-trust-matrix-v2/,
  'external trust matrix v2 evidence section',
);
requireRegex(
  'R51.external-trust-matrix-v2-caveats',
  'docs/contributing/release-readiness.md',
  /VISUAL_AREA_ONLY[\s\S]*POSSIBLE_VIEW_INTEROP[\s\S]*SHARED_COMPONENT/,
  'external trust matrix v2 caveat signals',
);
```

- [ ] **Step 7: Run docs and gate tests**

Run:

```bash
npm run release:gate:test
node scripts/check-release-readiness.mjs
```

Expected: PASS.

- [ ] **Step 8: Commit Task 4**

```bash
git add scripts/release-gate.mjs scripts/release-gate-test.mjs docs/contributing/release-readiness.md scripts/check-release-readiness.mjs
git commit -m "docs: add external trust matrix release evidence"
```

## Task 5: Final Verification And Graph Update

**Files:**
- Modify: generated graph output only if graphify tracks it locally. Do not commit `graphify-out/`.

**Interfaces:**
- Consumes: All prior task commits.
- Produces: Fresh verification evidence for the implementation branch.

- [ ] **Step 1: Run matrix and release focused tests**

Run:

```bash
npm run external-fixture:matrix:test
npm run evidence:test
npm run release:gate:test
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: every command exits 0.

- [ ] **Step 2: Run non-strict dry-run/report smoke**

Run:

```bash
npm run external-fixture:matrix -- --dry-run
```

Expected output includes:

```text
External fixture matrix: pass
JSON: /Users/kws/source/android/FixThis/build/reports/fixthis-external-fixture-matrix/report.json
Markdown: /Users/kws/source/android/FixThis/build/reports/fixthis-external-fixture-matrix/report.md
```

Then inspect the report:

```bash
node -e 'const r=require("./build/reports/fixthis-external-fixture-matrix/report.json"); console.log(r.schemaVersion, r.summary.total, r.fixtures.some(f=>f.runtimeCapability==="first-handoff-trust"))'
```

Expected output:

```text
2.0 8 true
```

- [ ] **Step 3: Run graph update**

Run:

```bash
graphify update .
```

Expected: command exits 0. `graphify-out/` may be dirty or ignored; do not stage it.

- [ ] **Step 4: Inspect final status**

Run:

```bash
git status --short
```

Expected: only intended source, test, and doc files are modified. `graphify-out/` must not appear as staged.

- [ ] **Step 5: Final verification commit**

If Task 5 changed tracked files from this plan, commit only this explicit set:

```bash
git add fixtures/external-project-matrix/manifest.json \
  scripts/external-fixture-matrix.mjs \
  scripts/external-fixture-matrix-test.mjs \
  scripts/evidence-runner.mjs \
  scripts/evidence-runner-test.mjs \
  scripts/release-gate.mjs \
  scripts/release-gate-test.mjs \
  docs/contributing/release-readiness.md \
  scripts/check-release-readiness.mjs
git commit -m "chore: verify external trust matrix v2"
```

If Task 5 changed no tracked files, do not create an empty commit.

## Self-Review

### Spec Coverage

- External project setup shapes: Task 1 updates manifest v2 and validation.
- First handoff runtime evidence: Task 3 adds runtime-capable command planning and strict execution.
- Trust signals and overconfidence checks: Task 2 adds pure trust expectation evaluation.
- Pass/deferred/fail/fixture drift/caveated pass: Tasks 1 and 2 add report summary and outcome classification.
- Release gate consumption: Task 4 adds the `external-trust-matrix-v2` claim.
- No public JSON or bridge field changes: Global constraints and file structure avoid MCP/bridge DTO edits.
- Local-only strict connected evidence: Task 3 keeps Android commands deferrable and Task 5 verifies non-strict behavior.

### Placeholder Scan

The plan contains no placeholder tokens or dangling task references. Each task lists exact files, tests, commands, expected failures, and code snippets for changed interfaces.

### Type Consistency

The plan consistently uses:

- `runtimeCapability` with values `setup-only` and `first-handoff-trust`.
- `trustExpectations` as an array on each fixture.
- `trustFindings` as an array on each report fixture.
- `outcome` values `pass`, `caveated_pass`, `environment_deferred`, `product_failure`, and `fixture_drift`.
- Evidence step name `External trust matrix v2 strict`.
- Release claim id `external-trust-matrix-v2`.
