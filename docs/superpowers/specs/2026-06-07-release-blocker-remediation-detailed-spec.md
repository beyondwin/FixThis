# Release Blocker Remediation Detailed Spec

Date: 2026-06-07
Status: Proposed
Scope: Remediate every blocker found while reviewing commits after `v1.1.0`
before cutting the next FixThis release.

## Summary

The current `main` has strong coverage after `v1.1.0`, but it is not ready to
tag. The release review found two hard blockers:

1. `npm run release:check` fails because the detekt baseline ratchet reports
   `config/detekt/baseline-fixthis-compose-core.xml` at `58/57`.
2. `npm run release:gate -- --strict` fails because runtime source-trust
   promotes two shared-component definition cases to HIGH confidence.

Both blockers are solvable without widening release scope. The fix should make
the shipped behavior match the older trust contract: a reused component
definition can expose a ranked `recommendedEditSite`, but the definition
candidate itself must stay below HIGH confidence. That distinction matters for
agent behavior: "check this likely call site first" is useful context; "edit
this shared definition with high confidence" is an overclaim.

## Current Evidence

Commands already run during review:

```bash
node scripts/check-release-readiness.mjs
npm run release:gate:test
npm run source-matching:fixtures:test
./gradlew :fixthis-cli:test :fixthis-compose-core:test :fixthis-gradle-plugin:test :fixthis-mcp:test --no-daemon
npm run release:drift -- --strict
npm run release:reality
bash scripts/check-docs-cli-surface.sh
npm run release:check
npm run release:gate -- --strict
npm run source-matching:fixtures:runtime -- --strict
```

Passing evidence:

- Release readiness rules pass.
- Release drift strict passes.
- Release reality passes.
- Docs CLI surface passes.
- Unit tests for CLI, core, Gradle plugin, and MCP pass.
- Release gate sub-steps pass for release reality, release drift, ADB
  discovery, compose-core source trust, interop boundary contracts, handoff
  evaluation, runtime trust boundary observations, external fixture matrix,
  agent-loop smoke, real Copy Prompt smoke, console reliability, docs
  consistency, and workspace whitespace.

Failing evidence:

- `npm run release:check` stops at `npm run detekt:baseline:check`.
- `npm run release:gate -- --strict` stops because `Runtime trust strict`
  fails.
- `build/reports/fixthis-source-matching/report.md` shows 7 runtime-trust
  cases, 2 failed cases, and no environment downgrades.

The runtime failures are:

- `fixthis-sample-shared-header-medium-cap`
- `fixthis-sample-shared-header-recommended-edit-site`

Both fail with:

- `overconfident`
- `unexpected_high_confidence`
- `weak_evidence_promoted`

## Root Cause

### Runtime source-trust overconfidence

`fixtures/source-matching/manifest.json` explicitly requires the shared
`StudioHeader` definition to remain `low-or-medium` and `mustNotHighConfidence`,
even when a single recommended edit site is present.

Production code currently computes this boolean in `SourceMatcher`:

```kotlin
val confidentCallSite = callSites.count { it.recommendedEditSite } == 1 &&
    (profile.hasSelectedOwnerFunction || profile.hasStrictCompTag || profile.hasSelectedTestTag)
```

That value is passed to `SourceRiskClassifier.applyCaps(...)`. Inside
`SourceRiskClassifier`, shared-component caps are skipped when
`confidentCallSite == true`:

```kotlin
if (profile.hasSharedComponentDefinition) {
    flags.add(SourceCandidateRisk.SHARED_COMPONENT)
    if (!confidentCallSite) {
        confidence = capAt(confidence, SelectionConfidence.MEDIUM)
    }
}
```

This contradicts the shipped MCP reference contract and the runtime fixture
contract. `recommendedEditSite` is verification context. It must not raise the
definition candidate to HIGH.

There is also a naming trap: `SelectionConfidence` is ordered
`HIGH, MEDIUM, LOW, NONE`, so `capAt(current, ceiling)` must be interpreted
carefully. The remediation should replace this helper with explicit confidence
ordering instead of relying on enum ordinal direction.

### Detekt baseline ratchet failure

`config/detekt/baseline-budget.json` budgets compose-core at 57 IDs, while
`config/detekt/baseline-fixthis-compose-core.xml` currently contains 58 IDs.
The growth comes from moved source-signal weights in `SourceIndex.kt`, where
enum constructor literals produce `MagicNumber` baseline entries.

The correct fix is not to bump the budget. This repo uses
`detekt:baseline:check` as a ratchet. The implementation should remove at
least one compose-core baseline entry and keep the budget at 57 or lower.

## Goals

- Make shared-component definition candidates stay at MEDIUM or lower even
  when exactly one call site is marked `recommendedEditSite`.
- Preserve `callSites[].recommendedEditSite` and `mostLikely` as additive
  context.
- Preserve HIGH confidence for non-shared strong evidence and for MCP
  `CALL_SITE` edit-surface candidates where the source candidate itself is
  high and precise.
- Make `npm run source-matching:fixtures:runtime -- --strict` pass on a
  connected Android environment.
- Make `npm run release:gate -- --strict` pass on a connected Android
  environment.
- Make `npm run detekt:baseline:check` pass without increasing the
  compose-core budget.
- Update release notes and roadmap text that currently claim shared-component
  definition candidates can keep HIGH confidence.

## Non-Goals

- No bridge protocol change.
- No persisted MCP JSON field rename.
- No new package channel, registry, or release workflow.
- No removal of `recommendedEditSite`.
- No weakening of runtime-trust fixture expectations.
- No budget increase for compose-core detekt baseline.
- No refactor of the whole source matcher. The change should be local to
  confidence calibration and a small constant extraction.

## Architecture

### Track A - Source confidence calibration

Replace the current shared-component cap relaxation with an invariant:

> Any candidate carrying `SourceCandidateRisk.SHARED_COMPONENT` for a reused
> definition remains capped at MEDIUM or below.

Recommended call-site information remains in `SourceCandidate.callSites`. It is
used by the handoff and console as a navigation hint, not as a confidence
promotion reason.

Implementation shape:

- Add a local confidence ordering helper in `SourceRiskClassifier`, independent
  of enum ordinal order.
- Rename or replace `capAt` with a true `ceiling(...)` helper.
- Remove `confidentCallSite` from `SourceRiskClassifier.applyCaps(...)`.
- Keep `SourceMatcher` computing ranked call sites, but do not pass that flag
  into confidence capping.
- Update tests that expected HIGH for a shared definition to expect MEDIUM while
  still asserting `recommendedEditSite == true`.

### Track B - Documentation truth

Some post-`v1.1.0` docs and release notes now contradict the fixture contract.
The release claim should read:

- `CALL_SITE` edit surfaces can reach HIGH under strong, unambiguous evidence.
- Shared-component definitions stay capped at MEDIUM.
- A confident single call-site is surfaced as `recommendedEditSite`, but that is
  verification context and does not make the shared definition high confidence.

Update only current user-facing and planning docs that would mislead a release
reader:

- `CHANGELOG.md`
- `docs/releases/unreleased.md`
- `docs/product/roadmap.md`
- `docs/superpowers/specs/2026-06-04-source-matching-refinement-design.md`

Do not rewrite old tagged release notes.

### Track C - Detekt ratchet repair

Move source-signal weight literals out of enum entries into named constants so
detekt no longer needs the new `MagicNumber` baseline entries for
`SourceIndex.kt`.

Implementation shape:

- Add an internal object in `SourceIndex.kt`, for example
  `SourceSignalWeights`.
- Replace literal enum constructor values with named constants.
- Run detekt and baseline budget checks.
- If the baseline file still contains stale `MagicNumber:SourceIndex.kt`
  entries after detekt, regenerate or prune only those stale entries.
- Keep `config/detekt/baseline-budget.json` at 57 or lower.

### Track D - Release gate closure

After source calibration and detekt ratchet fixes, run the same release commands
that failed during review. The finish line is not "unit tests pass"; it is both
release gates passing:

```bash
npm run release:check
npm run release:gate -- --strict
```

Strict gate assumes an Android SDK and a ready emulator/device. If the
environment becomes unavailable during implementation, the implementation must
still pass non-connected commands and record the exact connected blocker. On
this review machine, strict runtime did reach the AVD and failed by case
classification, so the expected remediation result is a strict pass, not a
deferred report.

## Data Contracts

- `SourceCandidate.callSites` remains additive and compatible.
- `mostLikely` and `recommendedEditSite` remain optional booleans.
- No field name changes to `items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, `targetReliability`, or `sourceCandidates`.
- `SourceCandidate.confidence` changes only by becoming less overconfident for
  shared reused definitions.
- `EditSurfaceCandidateDto.confidence` behavior for `CALL_SITE` remains
  unchanged except where the underlying source candidate was incorrectly HIGH.

## Error Handling

- If runtime source-trust still fails, inspect
  `build/reports/fixthis-source-matching/report.json` and fix the failed case.
  Do not weaken `mustNotHighConfidence`.
- If `detekt:baseline:check` reports `improved`, lower the matching budget only
  when the baseline count has actually dropped.
- If `detekt:baseline:check` reports `over`, remove baseline debt or stale IDs
  before changing any budget.
- If docs consistency fails because of stale release claims, prefer changing the
  claim text over changing the checker.

## Testing Strategy

Focused tests:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceRiskClassifierTest" --no-daemon
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherSharedComponentTest" --no-daemon
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
npm run source-matching:fixtures:test
npm run source-matching:fixtures:runtime -- --strict
npm run detekt:baseline:check
./gradlew :fixthis-compose-core:detekt --no-daemon
node scripts/check-doc-consistency.mjs
node scripts/check-release-readiness.mjs
```

Final release checks:

```bash
npm run release:check
npm run release:gate -- --strict
git diff --check
```

## Acceptance Criteria

- `fixthis-sample-shared-header-medium-cap` passes.
- `fixthis-sample-shared-header-recommended-edit-site` passes.
- Runtime report has `Failed cases: 0`.
- Shared header candidates still include `SHARED_COMPONENT`.
- The Diagnostics case still reports exactly one `recommendedEditSite` pointing
  at `screens/DiagnosticsScreen.kt`.
- No shared component definition candidate is HIGH in runtime-trust output.
- `npm run detekt:baseline:check` passes with compose-core budget not increased.
- Current release notes no longer claim shared-component definitions keep HIGH
  confidence.
- `npm run release:check` passes.
- `npm run release:gate -- --strict` passes.

## Rollback Plan

- Source confidence fix can be reverted by restoring the previous
  `applyCaps(...)` signature and tests, but doing so reopens the runtime gate
  blocker.
- Detekt constant extraction can be reverted independently if it introduces
  unexpected serialization issues; enum names and wire values are unchanged.
- Docs updates can be reverted independently, but release text must not claim
  behavior that gates reject.

## Spec Self-Review

- Placeholder scan: no placeholders or future-only decisions remain.
- Scope check: the spec covers two blockers and their required doc truth sync.
- Compatibility check: no persisted JSON field rename or bridge protocol change.
- Runtime honesty check: strict connected evidence must pass on this machine.
- Release honesty check: release notes must match runtime-trust fixtures.
