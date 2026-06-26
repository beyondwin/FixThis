# Android Release Evidence Consolidation Design

Date: 2026-06-27

## Goal

Make connected Android release evidence run once, fail once, and explain itself
from one report.

FixThis now has `npm run android:proof -- --strict`, which classifies Android
readiness and runs the sample smoke, real Copy Prompt smoke, agent-loop smoke,
and external fixture matrix. The remaining release-gate gap is that the gate
profile still runs several of those connected checks again as separate evidence
steps. This design promotes the connected Android proof runner from a useful
aggregator to the single connected Android release-decision surface.

## Current Context

The current gate profile in `scripts/evidence-runner.mjs` includes both the new
integrated proof and older Android-connected commands:

- `Connected Android proof` runs
  `npm run android:proof -- --strict --continue`.
- `Runtime trust strict` runs
  `npm run source-matching:fixtures:runtime -- --strict`.
- `External trust matrix v2 strict` runs
  `npm run external-fixture:matrix -- --strict`.
- `Agent loop smoke` runs `npm run agent-loop:smoke -- --strict`.
- `Real copy prompt smoke` runs `npm run real-copy-prompt:smoke -- --strict`.

The integrated proof already runs the sample, Copy Prompt, agent-loop, and
external fixture checks internally and writes a JSON/Markdown report under
`build/reports/fixthis-android-proof/`. The gate should use that proof as the
connected runtime authority instead of re-running its child steps.

## Scope

In scope:

- remove duplicate connected Android strict commands from the release-gate
  profile when they are already covered by `Connected Android proof`;
- keep host-only, contract-only, and source-trust checks that are not covered by
  the connected proof;
- update release-claim mapping so connected Android claims consume
  `Connected Android proof`;
- preserve existing release claim ids and top-level report fields;
- add optional release-gate detail fields for the proof runner's child failures;
- update release and contributor docs to describe `android:proof` as the
  release-decision command and individual smokes as focused debugging commands.

Out of scope:

- changing the proof runner's child smoke behavior;
- changing MCP queue semantics or persisted feedback JSON fields;
- changing bridge protocol or source-matching compatibility fields;
- removing focused smoke commands from package scripts;
- changing non-gate profiles such as `trust` unless a specific duplicate is
  release-gate-only.

## Release Gate Profile

The gate profile should keep one Android-connected runtime step:

```text
Connected Android proof
  npm run android:proof -- --strict --continue
```

The gate profile should no longer run these as separate gate steps:

```text
npm run real-copy-prompt:smoke -- --strict
npm run agent-loop:smoke -- --strict
npm run external-fixture:matrix -- --strict
npm run source-matching:fixtures:runtime -- --strict
```

The host fixture contract command `npm run source-matching:fixtures:test` stays
because it covers source-trust fixture behavior without needing a connected
Android runtime. The connected runtime confidence for release-gate claims comes
from `Connected Android proof`.

Focused commands remain available for debugging and for non-gate evidence
profiles. This consolidation is about release-decision orchestration, not about
removing low-level proof tools.

## Claim Mapping

Release claim ids remain stable. The evidence behind connected Android claims
changes to use the integrated proof.

Proposed mapping:

| Claim id | Required evidence after consolidation |
| --- | --- |
| `connected-android-proof` | `Connected Android proof` |
| `external-agent-loop` | `Agent loop smoke contracts`, `Connected Android proof` |
| `external-first-handoff-recovery` | `Agent loop smoke contracts`, `Connected Android proof` |
| `first-handoff-autopilot` | `First handoff autopilot CLI contract`, `Agent loop smoke contracts`, `Connected Android proof` |
| `external-fixture-matrix` | `External fixture matrix contracts`, `Connected Android proof` |
| `external-trust-matrix-v2` | `External fixture matrix contracts`, `Connected Android proof` |
| `runtime-source-trust` | `Runtime trust boundary observations`, `Connected Android proof` |

This keeps contract evidence distinct from runtime proof. For example,
`external-fixture-matrix` still needs the contract test that validates fixture
planning and report semantics, while the connected runtime execution comes from
the integrated proof.

## Detail Propagation

The release gate should not lose information by replacing several rows with one
row. When `Connected Android proof` has a report at
`build/reports/fixthis-android-proof/report.json`, release-gate normalization
should read it best-effort and expose child details additively.

Suggested additive fields on normalized evidence steps:

```json
{
  "name": "Connected Android proof",
  "status": "fail",
  "reportPath": "build/reports/fixthis-android-proof/report.json",
  "detailReportPath": "build/reports/fixthis-android-proof/report.json",
  "childFailures": [
    {
      "scope": "step",
      "step": "Agent loop smoke",
      "failureCode": "agent_loop_failed",
      "reason": "Save to MCP through MCP failed.",
      "nextAction": "Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`."
    }
  ]
}
```

Existing fields stay unchanged. Consumers that only read `claims[]` and
`steps[]` continue to work. New consumers can use `childFailures` to find the
specific failing smoke without scraping Markdown.

Claim reasons should prefer the first child failure when present:

```text
agent_loop_failed: Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.
```

If the proof report cannot be read, release-gate should keep the top-level
evidence status and reason from the command result. Missing detail reports are
not a separate failure unless the command itself passed while claiming a report
path that does not exist.

## Strict And Deferred Semantics

`android:proof` remains the only component that classifies Android runtime
availability for connected release evidence.

Rules:

- `android:proof` `pass` means connected Android evidence can pass.
- `android:proof` `deferred` means non-strict `release:gate` may produce
  `pass_with_deferred`, but strict `release:gate -- --strict` fails.
- `android:proof` `fail` always fails connected Android claims.
- `pass_with_deferred` is reserved for future optional proof steps after
  preflight passes; current required proof steps should pass, fail, or defer the
  whole proof because runtime prerequisites are missing.

The release gate should not reclassify `device_missing`, `device_offline`,
`boot_incomplete`, or smoke failure codes. It should surface the proof runner's
classification and next action.

## Documentation Changes

Update `CONTRIBUTING.md`:

- describe `npm run android:proof -- --strict` as the connected Android
  release-decision command;
- keep individual smokes as focused debugging commands;
- describe `release:gate` as consuming the integrated connected proof instead
  of running each connected smoke separately.

Update `docs/contributing/release-readiness.md`:

- change connected Android claim evidence to `android:proof` plus relevant
  contract tests;
- remove required-evidence rows that still require direct connected smoke
  commands for release-gate claims already covered by `android:proof`;
- keep the deferred/strict policy explicit.

Update `docs/releases/unreleased.md`:

- state that connected Android evidence is recorded through the integrated proof
  report;
- keep the note that local connected evidence may be deferred only in non-strict
  reports with a concrete reason.

## Testing

Add or update tests in `scripts/release-gate-test.mjs`:

- `releaseGateSteps()` includes `Connected Android proof`.
- `releaseGateSteps()` does not include duplicated connected commands covered by
  the integrated proof.
- connected Android claim ids are unchanged.
- connected Android claims use `Connected Android proof` as runtime evidence.
- missing contract evidence still fails claims that require contract evidence.
- a proof runner child failure is propagated into claim reason and
  `childFailures`.
- strict release gate fails deferred connected proof.
- non-strict release gate reports deferred connected proof as
  `pass_with_deferred`.

Add or update tests in `scripts/evidence-runner-test.mjs`:

- the gate profile dry run includes one integrated connected proof step;
- the gate profile no longer lists duplicate connected strict smoke commands.

Add or update tests in `scripts/android-proof-runner-test.mjs` only if the proof
runner report shape needs small additions for release-gate detail propagation.
The proof runner should remain the owner of child step names, failure codes, and
next actions.

## Acceptance Criteria

- `npm run release:gate:test` passes.
- `npm run evidence:test` passes.
- `npm run android:proof:test` passes.
- `npm run release:gate` produces one connected Android runtime evidence step,
  not repeated child smoke rows.
- When connected proof fails, release-gate Markdown and JSON point to the proof
  report and include the child failure code or next action.
- Existing release claim ids remain present.
- No MCP tool names, persisted feedback fields, bridge protocol fields, or
  source-matching compatibility fields are renamed.

## Risks

The main risk is hiding a meaningful product regression behind one aggregate
row. The mitigation is child failure propagation: release gate should summarize
the integrated proof but still expose the failing child step and report path.

The second risk is making older claim names misleading. The mitigation is to
preserve claim ids while redefining their runtime evidence source in
release-readiness docs. Contract evidence remains separate where the claim needs
it.

The third risk is over-consolidating non-gate workflows. The mitigation is to
leave focused smoke scripts and non-gate profiles available for targeted
debugging and development evidence.
