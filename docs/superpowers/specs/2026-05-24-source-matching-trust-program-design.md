# Source Matching Trust Program Design

Date: 2026-05-24
Status: Ready for user review
Scope: local source matching evaluation, agent handoff trust rendering,
confidence policy, and focused Compose matcher pattern expansion

## Summary

FixThis should improve source matching in the order that best protects its core
promise: useful source hints without false precision.

The approved direction is a three-phase Source Matching Trust Program:

1. Strengthen the local fixture lab into a trust evaluation and report loop.
2. Improve agent handoff quality for `targetReliability`, `targetEvidence`,
   `sourceCandidates`, and `editSurfaceCandidates`.
3. Expand matcher pattern coverage only after the evaluation and handoff layers
   can explain confidence and warnings clearly.

This is intentionally not a broad feature expansion. The work keeps FixThis V1
inside debug-only Jetpack Compose, local-only ADB/MCP workflows, and additive
MCP/session output compatibility.

## Product Goal

Give agents better evidence about when a source candidate is safe to inspect
first and when it is only a weak hint.

The program should make FixThis better at answering:

- Did this source-index or matcher change improve useful source hints?
- Did it avoid increasing high-confidence claims on ambiguous targets?
- Does the agent handoff explain why a target is high, medium, low, or unknown
  confidence?
- Are newly recognized Compose patterns covered by evaluation cases before
  confidence is allowed to rise?

## Non-Goals

- Do not support release builds.
- Do not add View, WebView, Flutter, React Native, or iOS source targeting.
- Do not make the fixture lab a CI, release, or public install gate in this
  iteration.
- Do not vendor external sample repositories, fixture working copies, reports,
  screenshots, `.fixthis/`, or `graphify-out/`.
- Do not rename persisted MCP JSON fields such as `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, `targetReliability`, `sourceCandidates`, or
  `editSurfaceCandidates`.
- Do not make `:fixthis-compose-core` depend on MCP, CLI, Android UI, local
  artifact paths, or `.fixthis/`.
- Do not treat Graphify as a FixThis runtime dependency.

## Current State

FixThis already has a local source matching fixture lab under
`fixtures/source-matching/` and `scripts/source-matching-fixtures.mjs`. It can
prepare pinned Google Compose sample repos, generate source indexes, and write
JSON/Markdown reports under `build/reports/fixthis-source-matching/`.

The current source matcher uses typed source-index signals, evidence profiles,
confidence caps, margin-based ambiguity handling, stale-source warnings, and
owner-composable hints. The target reliability layer can mark visual-area-only,
possible interop, stale source, low margin, missing fingerprint, forced
fingerprint mismatch, and sensitive-text cases.

The remaining gap is program-level calibration. A future matcher improvement
should not be judged only by whether it finds more candidate paths. It should
also prove that confidence and warnings remain honest.

## Architecture

The design has three layers.

### Trust Evaluation Layer

Extend the fixture lab from source-index presence checks into a local trust
evaluation loop.

Fixture cases should be able to express:

- expected top-1 or top-3 source path substrings
- expected signal kind and value
- expected confidence band
- required warning or risk-flag tokens
- warning or risk-flag tokens that must not appear
- cases that must not become high confidence even if a plausible candidate
  exists

The report should distinguish product regressions from environment or fixture
drift. A fixture build failure is not the same as an overconfident matcher
result.

### Handoff Trust Layer

Use existing core outputs to make agent-facing handoffs more explicit.

The MCP formatter and edit-surface rendering should separate:

- source-origin evidence: where the visible text or semantic signal appears
- edit-surface evidence: where a style, layout, typography, spacing, or
  component-renderer change is likely to belong
- target reliability: how much the selected target should be trusted before
  editing
- warnings: why the agent should verify before editing

The JSON compatibility contract stays additive. Older sessions with missing
optional trust fields must still render valid handoffs.

### Matcher Expansion Layer

Add new Compose pattern recognition only after the first two layers are in
place.

Initial pattern families:

- `Layout` and `SubcomposeLayout` owner or renderer signals
- shared reusable component renderer hints
- string/resource indirection beyond direct `Text(...)` and direct
  `stringResource(...)` cases
- container/style call-site evidence for spacing, color, typography, and
  repeated-list item work

Each new pattern needs a fixture case or focused unit test that defines the
expected source signal, expected confidence behavior, and warning behavior.

## Components

### Fixture Trust Manifest

`fixtures/source-matching/manifest.json` remains the source of committed
fixture contracts.

The manifest should stay small and explicit. New case fields should be added
only when the evaluator can enforce them and a real case needs them. Pinned
commits remain full SHAs.

Recommended new case contract fields:

- `expectedTop1PathContains`
- `expectedTop3PathContains`
- `expectedConfidence`
- `expectedSignal`
- `expectedRiskFlags`
- `mustWarn`
- `mustNotWarn`
- `mustNotHighConfidence`

The manifest is not a user-facing product API.

### Trust Report Evaluator

`scripts/source-matching-fixtures.mjs` owns local evaluation and report output.

It should classify failures with stable labels:

- `missing_top3`
- `wrong_top1`
- `missing_source_signal`
- `overconfident`
- `underconfident`
- `missing_warning`
- `unexpected_warning`
- `unexpected_high_confidence`
- `weak_evidence_promoted`
- `fixture_build_failed`
- `source_index_missing`
- `fixture_drift`
- `case_contract_invalid`

`report.json` is machine-readable evidence. `report.md` is a concise local
review summary. Neither file is committed.

### Core Trust Policy

Core trust decisions stay in `:fixthis-compose-core`.

Relevant implementation surfaces:

- `SourceMatcher`
- `SourceConfidencePolicy`
- `EvidenceProfile`
- `SourceRiskClassifier`
- `TargetReliabilityCalculator`

The policy should continue to prefer confidence caps over optimistic claims.
Untyped fallback, arbitrary literal, nearby-only, activity-only, low margin,
visual-area-only, stale source, and possible interop evidence should restrict
confidence or add warnings.

### Agent Handoff Renderer

MCP session formatting owns the agent-facing wording.

The handoff should avoid long educational prose. It should say what the agent
needs to do next:

- high confidence: inspect the source candidate first
- medium confidence: inspect the candidate and corroborate with screenshot or
  surrounding code
- low confidence: treat source paths as hints only
- unknown confidence: verify manually before editing
- possible interop or visual-area-only: use screenshot/bounds first, then check
  whether Compose source explains the pixels

`sourceCandidates` and `editSurfaceCandidates` must not be conflated. A data
text source can be the origin of visible copy while a component renderer is the
better edit surface for style or layout changes.

### Matcher Pattern Extensions

Gradle source-index scanning can add typed signals for the approved pattern
families. The scanner should keep legacy fields for compatibility, but matcher
confidence should prefer typed signals and explicit evidence profiles.

New signal kinds should be added only when they have a clear matcher meaning.
When an existing signal kind is enough, prefer reusing it rather than expanding
the source-index schema.

## Data Flow

1. The Gradle plugin scans Kotlin and XML sources and generates
   `fixthis-source-index.json`.
2. The sidekick captures Compose semantics, bounds, screenshot metadata, and
   source-index availability from the debug app.
3. Core source matching ranks `SourceCandidate` values and applies confidence
   caps based on evidence strength, margins, and risk flags.
4. Core target reliability calculates item-level confidence, reasons, and
   warnings from the selected target, nearby semantics, source candidates,
   screen fingerprint state, and selection kind.
5. `Save to MCP` or `Copy Prompt` persists or renders `targetEvidence`,
   `targetReliability`, `sourceCandidates`, and `editSurfaceCandidates`.
6. The fixture lab prepares external sample apps, generates source indexes,
   evaluates committed case contracts, and writes local reports.
7. Matcher changes use fixture and unit evidence to prove that new candidates
   improve usefulness without increasing false confidence.

## Error Handling

The program should expose uncertainty instead of hiding it.

Fixture failures:

- External clone, build, and Gradle failures are environment or fixture errors
  unless the source index was generated and the evaluated case itself failed.
- Missing expected paths after an upstream re-pin should be classified as
  fixture drift, not a matcher regression.
- Invalid manifest fields should fail fast as case contract errors.

Weak source evidence:

- Untyped fallback, arbitrary literals, nearby-only matches, and activity-only
  matches should not raise confidence by themselves.
- Low top-candidate margin should prevent high confidence.
- Stale source line evidence should keep the "do not edit by file:line without
  verification" caution.

Missing handoff metadata:

- If an older session has no `targetReliability`, the renderer should fall back
  to unknown or verify-first wording.
- Missing `editSurfaceCandidates` should not prevent source candidate
  rendering.
- Optional/additive fields should decode safely when absent.

Visual and interop targets:

- Visual-area-only and possible AndroidView/WebView targets should persist as
  warnings, not blocking save errors.
- Source candidates can still be useful hints, but the handoff must not claim
  that Compose source definitely explains the selected pixels.

## Testing

### Fixture And Report Tests

Extend `scripts/source-matching-fixtures-test.mjs` with fast offline tests for:

- manifest contract validation
- report classification labels
- confidence calibration
- warning and risk-flag classification
- environment downgrade versus product failure
- invalid case contracts

These tests must not clone external repositories and must not require Android
SDK, ADB, or a device.

### Core Matcher Tests

Extend focused tests in `:fixthis-compose-core` for:

- high confidence only with strong evidence and clear margins
- ambiguity downgrade
- arbitrary literal and untyped fallback caps
- nearby-only and activity-only caps
- stale source and visual/interop warning propagation
- `targetReliability` confidence behavior when source candidates are missing,
  stale, ambiguous, or low confidence

### Gradle Scanner Tests

Extend Gradle plugin scanner tests for:

- `Layout` owner or renderer signals
- `SubcomposeLayout` owner or renderer signals
- shared component renderer patterns
- indirect string/resource patterns
- style/layout call-site signals when the pattern is precise enough to be
  useful

Scanner tests should verify emitted typed signals. Core matcher tests should
verify how those signals affect ranking and confidence.

### MCP Handoff Tests

Extend MCP session and prompt-rendering tests for:

- target confidence lines
- verify-before-editing warning lines
- source candidate versus edit-surface separation
- visual-area and possible-interop wording
- old-session rendering when optional trust fields are absent

### Validation Commands

Fast validation:

```bash
npm run source-matching:fixtures:test
```

Targeted Gradle tests should cover core matcher, target reliability, Gradle
scanner, and MCP handoff formatting changes.

Manual local evidence:

```bash
npm run source-matching:fixtures
npm run source-matching:fixtures:report
```

The manual fixture run remains local-only and is not promoted to a release gate
in this design.

## Implementation Sequence

### Phase 1: Trust Evaluation

Expand the fixture manifest and evaluator first. The deliverable is a clearer
local report that can identify confidence and warning regressions without any
new matcher behavior.

Phase 1 is complete when local offline tests prove the report classifier can
differentiate missing candidates, source signal gaps, overconfidence,
underconfidence, warning mismatches, environment downgrade, and fixture drift.

### Phase 2: Handoff Trust

Improve MCP handoff rendering and edit-surface separation using existing core
trust data.

Phase 2 is complete when agent-facing Markdown makes source confidence,
target reliability, edit-surface role, and warning guidance clear without
renaming persisted JSON fields.

### Phase 3: Matcher Pattern Expansion

Add focused scanner and matcher improvements for the approved Compose pattern
families.

Phase 3 is complete when each new pattern has scanner tests, matcher tests,
handoff tests when relevant, and at least one fixture or fixture-like case that
checks confidence and warnings.

## Success Criteria

- Fixture reports explain why a case failed, not only that it failed.
- Overconfident source candidates are easier to detect locally.
- Handoffs tell agents when to trust, inspect, or treat source candidates as
  hints only.
- `sourceCandidates` and `editSurfaceCandidates` have clearer roles in
  Markdown output.
- New matcher patterns do not bypass confidence caps.
- Existing persisted session JSON remains compatible.
- No local fixture artifacts, screenshots, reports, `.fixthis/`, or
  `graphify-out/` files are committed.
