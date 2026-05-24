# Source Matching Runtime Trust Fixtures Design

Date: 2026-05-24
Status: Ready for user review
Scope: local-only source matching runtime trust fixtures, report semantics,
manifest contract cleanup, and staged quality gates

## Summary

FixThis will add an opt-in runtime fixture tier for source matching trust
calibration. The current source-index fixture lab is useful, but it can only
prove that generated source-index entries and typed signals exist. It cannot
observe runtime `SourceCandidate` confidence, source risk flags, or
`targetReliability` warnings because no app is installed, launched, captured, or
matched against a selected semantics node.

The approved direction is to keep the fast source-index fixture path, add a
separate device-backed runtime trust path, and remove legacy fixture
manifest/report compatibility from this local lab. Public MCP persisted JSON
compatibility remains protected; local fixture schema compatibility does not.

## Goals

- Make fixture reports say exactly what was observed.
- Stop treating runtime trust expectations as source-index-only checks.
- Add `source-matching:fixtures:runtime` for real app install, launch, capture,
  target resolution, source matching, and target reliability validation.
- Keep `source-matching:fixtures` fast and deterministic for source-index
  generation checks.
- Preserve debug-only, Compose-only, local-first constraints.
- Keep runtime fixture automation opt-in and strict when requested.

## Non-Goals

- Do not make runtime fixtures a CI or release gate.
- Do not support release builds, View-based apps, Flutter, React Native, WebView,
  iOS, or cloud execution.
- Do not automate the browser feedback console for fixture evaluation.
- Do not preserve old local fixture manifest or report schemas.
- Do not rename or remove public persisted MCP JSON fields such as `items`,
  `screens`, `targetEvidence`, `targetReliability`, or `sourceCandidates`.
- Do not commit `.fixthis/`, fixture workspaces, build reports, screenshots, or
  `graphify-out/`.

## Current Problem

`scripts/source-matching-fixtures.mjs` currently validates only
`mode: "source-index"` cases. It patches external fixture apps with
`addDebugRuntime.set(false)`, runs the source-index generation task, reads
`fixthis-source-index.json`, and evaluates candidate file paths plus typed
signals.

This path cannot observe:

- source candidate confidence
- source candidate risk flags
- source candidate score margins
- target reliability confidence
- target reliability warnings
- screenshot/fingerprint/runtime capture state

When a source-index-only case includes runtime trust expectations, the runner
uses `trust_observation_not_configured`. That label is a correct safety marker,
but it also exposes a design smell: runtime expectations are in the wrong
fixture mode.

## Design Principles

1. Source-index fixtures validate build-time source-index contracts only.
2. Runtime trust fixtures validate runtime matching and reliability contracts.
3. Local fixture schemas can break when the contract improves.
4. Public MCP/session output contracts remain additive and compatible.
5. Device-backed checks are opt-in because ADB, emulator state, startup UI, and
   sample app behavior can vary locally.
6. Runtime failures must not be masked by source-index downgrade labels.

## Manifest Contract

Move the fixture manifest to a new local schema. No v1 reader or automatic
migration is required.

Fixture-level fields stay close to the existing shape:

- `id`
- `repo`
- `commit`
- `projectDir`
- `modulePath`
- `variant`
- `applicationId`
- `cases`

Case-level `mode` becomes a hard boundary.

### `source-index` Cases

Allowed fields:

- `id`
- `mode: "source-index"`
- `expectedEntryPathContains`
- `expectedTop1PathContains`
- `expectedTop3PathContains`
- `expectedSignal`

Forbidden fields:

- `runtimeTarget`
- `expectedConfidence`
- `expectedSourceConfidence`
- `expectedRiskFlags`
- `mustWarn`
- `mustNotWarn`
- `mustNotHighConfidence`

If a source-index case includes runtime-only fields, validation fails with
`case_contract_invalid`.

### `runtime-trust` Cases

Allowed fields:

- `id`
- `mode: "runtime-trust"`
- `runtimeTarget`
- `navigation`
- `expectedTop1PathContains`
- `expectedTop3PathContains`
- `expectedConfidence`
- `expectedSourceConfidence`
- `expectedRiskFlags`
- `mustWarn`
- `mustNotWarn`
- `mustNotHighConfidence`

`runtimeTarget` is required. It resolves the selected target from the captured
semantics tree each run. It must not pin runtime-generated node UIDs.

Initial selector forms:

- `text`
- `testTag`
- `contentDescription`
- `role`

Coordinate or area selectors are reserved for a later step because they are more
fragile across device density, window size, and sample app layout changes.

`expectedConfidence` means `targetReliability.confidence`. If a case needs to
validate the top source candidate confidence, it uses
`expectedSourceConfidence`. This avoids overloading one field with two trust
domains.

## Commands

Keep the current command as the fast path:

```bash
npm run source-matching:fixtures
```

Add a runtime command:

```bash
npm run source-matching:fixtures:runtime
```

Add strict runtime mode:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

Default runtime mode returns `pass_with_environment_downgrade` when no
usable device or bridge is available. Strict mode exits non-zero for device,
launch, bridge, or capture unavailability.

## Runtime Architecture

The JavaScript fixture runner remains the clone, work-copy, Gradle, and report
orchestrator.

Runtime-specific execution uses an internal `fixthis-mcp` JavaExec runner
instead of driving the browser console. This avoids a CLI/MCP dependency cycle:
`fixthis-mcp` already depends on `fixthis-cli` for bridge access, so the runtime
fixture runner must live at the MCP/session boundary where target evidence is
computed. The runner is for repository-local evaluation and is not documented as
a public install flow. It reuses the same production services that create agent
handoff evidence:

1. Patch the fixture work copy with `addDebugRuntime.set(true)` and source-index
   generation enabled.
2. Build and install the debug variant.
3. Launch the app and wait for the sidekick bridge.
4. Capture the current screen through the bridge.
5. Resolve `runtimeTarget` against captured merged semantics nodes.
6. Build feedback evidence through the MCP/session service boundary.
7. Extract observed source candidates, source confidence, source risk flags,
   target reliability confidence, and target reliability warnings.
8. Return machine-readable observed JSON to the JavaScript runner.

The browser feedback console is intentionally not part of this flow. Console
draft persistence, CSRF token handling, and browser-local state are product UI
concerns, not fixture evaluation requirements.

## Data Flow

```text
manifest
  -> JS runner prepares fixture work copy
  -> source-index case:
       run Gradle source-index task
       read fixthis-source-index.json
       validate schema, path, ranking, and typed signals
  -> runtime-trust case:
       enable debug runtime
       build/install/launch app
       capture screen through bridge
       resolve runtimeTarget
       build target evidence and reliability
       validate confidence, source risk, warnings, and ranking
  -> report.json and report.md
```

## Report Semantics

Top-level status values:

- `pass`
- `fail`
- `pass_with_environment_downgrade`

Summary fields:

- `sourceIndexCases`
- `runtimeTrustCases`
- `failedCases`
- `environmentCases`
- `failureCounts`
- `environmentCounts`

Environment labels:

- `device_unavailable`
- `app_launch_failed`
- `bridge_unavailable`
- `capture_failed`

Default runtime mode classifies these as environment downgrades. Strict mode
treats them as failures.

Failure labels:

- `case_contract_invalid`
- `fixture_build_failed`
- `source_index_missing`
- `app_install_failed`
- `target_not_found`
- `target_ambiguous`
- `missing_confidence_observation`
- `missing_source_confidence_observation`
- `missing_risk_observation`
- `missing_warning_observation`
- `wrong_top1`
- `missing_top3`
- `missing_source_signal`
- `overconfident`
- `underconfident`
- `missing_risk_flag`
- `missing_warning`
- `unexpected_warning`
- `unexpected_high_confidence`
- `weak_evidence_promoted`

`trust_observation_not_configured` is not part of the new schema. Runtime trust
expectations in `source-index` cases are invalid, and missing observations in
`runtime-trust` cases are failures.

## Stepwise Implementation

### Step 1: Contract Cleanup

- Introduce the new manifest schema.
- Make runtime-only fields invalid for `source-index` cases.
- Require `runtimeTarget` for `runtime-trust` cases.
- Remove old manifest/report compatibility paths.

Quality gate:

- Fast script tests prove invalid case contracts fail clearly.

### Step 2: Report Cleanup

- Split source-index and runtime-trust case summaries.
- Separate failures from environment downgrades.
- Ensure reports cannot look green when runtime expectations were not observed.

Quality gate:

- Report tests cover `pass`, `fail`, and `pass_with_environment_downgrade`.

### Step 3: No-Device Runtime Contract Tests

- Add focused JVM tests for `SourceMatcher` confidence and risk caps.
- Add `TargetReliabilityCalculator` defensive cases for risky source
  candidates, area targets, stale candidates, and fingerprint warnings.
- Add `TargetEvidenceService` synthetic snapshot tests that exercise the same
  path runtime fixtures will use.

Quality gate:

- These tests catch false-confidence regressions without ADB or emulator
  dependency.

### Step 4: Runtime Helper

- Add the internal runtime helper used by the JavaScript runner.
- Reuse existing bridge/session/evidence services.
- Return observed JSON rather than persisted fixture artifacts.

Quality gate:

- Unit tests cover target selector resolution and observed JSON mapping.

### Step 5: Runtime Command

- Add `source-matching:fixtures:runtime`.
- Enable debug runtime only for runtime fixture runs.
- Support `--strict`.
- Start with one stable first-screen runtime case.

Quality gate:

- No device: default mode reports environment downgrade.
- No device with `--strict`: command exits non-zero.
- Device available: initial runtime case validates observed trust fields.

### Step 6: Documentation

- Update the fixture lab guide.
- Explain which command validates which trust layer.
- Document strict mode and environment downgrade semantics.

Quality gate:

- The guide makes it clear that source-index fixtures do not validate runtime
  trust expectations.

## Testing Strategy

JavaScript tests:

- manifest schema validation
- source-index/runtime field separation
- runtime target requirement
- strict/default report status differences
- report summary split

Kotlin tests:

- source candidate confidence and risk caps
- source evidence profile classification
- target reliability confidence and warnings
- target evidence integration from synthetic snapshots
- runtime target selector resolution

Optional local runtime verification:

```bash
npm run source-matching:fixtures:runtime
npm run source-matching:fixtures:runtime -- --strict
```

The runtime command is intentionally local-only and not a release gate.

## Risks

- ADB and emulator state can be flaky. Mitigation: runtime command stays opt-in
  and strict mode is explicit.
- External sample startup UI can drift. Mitigation: use pinned commits and
  stable first-screen selectors.
- Target selectors can become brittle. Mitigation: prefer semantics selectors
  over coordinates.
- Runtime helper can accidentally duplicate production logic. Mitigation: reuse
  existing bridge, session, source matching, and target reliability services.
- Report semantics can become confusing. Mitigation: make unobserved runtime
  expectations invalid or failed, not silently passed.

## Acceptance Criteria

- `source-matching:fixtures` remains a fast source-index-only check.
- `source-matching:fixtures:runtime` exists and is opt-in.
- Source-index cases cannot contain runtime trust expectations.
- Runtime-trust cases validate observed confidence, source risk, warnings, and
  ranking from a real captured screen.
- Runtime unavailability is visibly reported and strict mode fails it.
- Public MCP/session JSON compatibility is preserved.
- Local fixture manifest/report legacy compatibility is not preserved.
