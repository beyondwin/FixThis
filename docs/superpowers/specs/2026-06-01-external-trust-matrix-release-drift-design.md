# External Trust Matrix and Release Drift Guard - Design

**Date:** 2026-06-01
**Status:** Approved (design)
**Topic:** External Android fixture matrix, handoff correctness evaluation, and
release source-of-truth drift detection

## Summary

FixThis already has a credible v1.1 trust loop: published install paths, runtime
source-trust calibration, external agent lifecycle smoke, interop caution
rendering, SSE evidence, and release-gate aggregation. The next useful umbrella
should prove that this trust loop is not limited to the bundled sample app or a
single happy-path fixture.

This design combines three tracks:

- **Track A: External Project Fixture Matrix.**
- **Track B: Handoff Correctness Eval v2.**
- **Track C: Release Drift / Source-of-Truth Guard.**

The common product promise is: FixThis should keep working when installed into
realistic debug Compose apps, should give agents useful and honest edit
starting points, and should fail release checks when public docs drift from the
actual repo and tagged-release state.

## Approved Direction

Build one umbrella spec with three bounded, independently verifiable tracks:

1. Expand external-project fixture coverage from one lifecycle proof into a
   matrix of representative Android project shapes.
2. Evaluate handoff correctness as usefulness and honesty, not just successful
   prompt/session generation.
3. Add release drift checks that compare changelog, unreleased notes, tag
   distance, release-readiness claims, and package/version evidence.

This should extend the current evidence system rather than create a parallel
test platform. Existing runners such as `scripts/evidence-runner.mjs`,
`scripts/release-gate.mjs`, `scripts/source-matching-fixtures.mjs`,
`scripts/agent-loop-smoke.mjs`, `npm run handoff:eval:test`, and
`node scripts/check-release-readiness.mjs` remain the integration points.

## Goals

- Prove `install-agent -> doctor -> source-index -> console handoff ->
  read/claim/resolve` across multiple realistic external Android project
  layouts.
- Keep external fixture setup deterministic, local-first, and safe to run in CI
  without committing generated workspaces, `.fixthis/`, screenshots, or build
  output.
- Score handoff quality with explicit dimensions: correct starting file,
  correct role, confidence calibration, required caution text, and no exact
  ownership overclaim for risky evidence.
- Make handoff eval failures actionable by reporting which signal regressed and
  which command reproduces it.
- Detect release-source drift, including tag-after-commit drift, empty
  unreleased notes after meaningful changes, changelog/unreleased mismatch,
  readiness claim mismatch, and version/package evidence mismatch.
- Feed the new evidence into the release gate as `pass`, `deferred`, or `fail`.
- Preserve persisted MCP/session JSON compatibility contracts.

## Non-Goals

- No release-build sidekick support. FixThis remains debug-only.
- No XML/View exact source targeting.
- No WebView DOM inspection.
- No automatic app-code edits in fixture projects.
- No new package registry channel such as PyPI or Docker.
- No cloud service, external AI API call, or remote telemetry.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots,
  generated reports, local fixture workspaces, or downloaded external repos.
- No bridge-protocol or persisted JSON breaking change.

## Architecture Context

- `:fixthis-cli` owns `install-agent`, `doctor`, package detection, generated
  metadata setup, and agent config writing.
- `:fixthis-gradle-plugin` owns source-index generation and source semantic
  signals for Gradle-backed Android projects.
- `:fixthis-compose-core` owns source matching, target evidence, confidence,
  shared-component risk, and edit-surface classification. It must stay free of
  MCP, CLI, Android UI, `.fixthis/`, and external fixture path dependencies.
- `:fixthis-mcp` owns feedback sessions, MCP tools, compact/full handoff
  rendering, console routes, console assets, and local persistence.
- Node scripts own local evidence orchestration, fixture preparation, release
  checks, and report writing under `build/reports/`.

New evidence must be additive. Persisted fields such as `items`, `screens`,
`itemId`, `screenId`, `targetEvidence`, `targetReliability`, and
`sourceCandidates` remain compatibility contracts.

## Track A: External Project Fixture Matrix

### Problem

The current trust loop has strong internal evidence, but external-user risk is
broader than one sample path. Android projects vary by module structure,
Gradle DSL, flavors, source sets, version catalogs, app module location, and
agent setup state. A release can pass current checks while still failing a
common external layout.

### Design

Introduce a small fixture matrix that models external Android app shapes without
vendoring large third-party projects:

```text
fixture descriptor
-> prepare local temp workspace
-> install-agent
-> doctor --json
-> Gradle source-index generation
-> optional connected console lifecycle smoke
-> report fixture status and deferred reasons
```

The first matrix should include:

- single-module Kotlin DSL Compose app;
- multi-module project with non-root app module;
- product-flavor application IDs;
- version-catalog dependency management;
- project with pre-existing Claude/Codex/Cursor config files;
- project where generated metadata is missing and `doctor` must provide a
  recoverable next action.

Contract tests should validate fixture descriptors without Android SDK or ADB.
Strict connected validation can run only when an Android SDK and unlocked device
or emulator are available. Non-strict reports must record concrete deferral
reasons instead of implying success.

### Components

- New or extended fixture manifest under `fixtures/` for external project
  shapes. Descriptors should be small and generated into temp workspaces.
- A new script such as `scripts/external-fixture-matrix.mjs`, or a focused mode
  in an existing fixture runner if the existing shape is a clean fit.
- Contract tests for descriptor validation, command planning, deferral
  taxonomy, report rendering, and cleanup.
- `scripts/evidence-runner.mjs` profile entry for matrix contract checks and
  optional strict connected checks.
- `scripts/release-gate.mjs` claim mapping for external fixture matrix status.
- Docs updates in release-readiness and contributor verification surfaces.

### Error Handling

- Missing Android SDK, unavailable ADB, locked emulator, or missing connected
  device is `deferred` in non-strict mode and `fail` in strict mode.
- Fixture descriptor errors always fail.
- `install-agent` mutation failures fail and include the target fixture id.
- `doctor --json` returning a recoverable setup action can pass only when the
  expected next action is explicit in the fixture contract.
- Generated workspace cleanup failures fail in CI-facing modes, because leaked
  fixture state can corrupt later runs.

### Acceptance Criteria

- At least five fixture shapes are covered by descriptor contract tests.
- At least three fixture shapes run the full non-connected setup path:
  `install-agent -> doctor --json -> source-index or explicit next action`.
- Strict connected mode can run the lifecycle smoke against at least one matrix
  fixture when Android runtime prerequisites are available.
- Matrix reports identify each fixture, command, status, duration, and deferral
  reason.
- No generated fixture workspace or local setup artifact is committed.

## Track B: Handoff Correctness Eval v2

### Problem

FixThis can already produce compact handoffs, source candidates, edit-surface
roles, confidence, and caution text. The next question is quality: did the
handoff give an agent a useful and honest starting point for the requested UI
change?

Simple pass/fail prompt generation is not enough. A handoff can be syntactically
valid but still point at the wrong owner, hide shared-component blast radius, or
make weak evidence look too precise.

### Design

Extend the handoff eval corpus so each case has expected correctness dimensions:

```text
runtime/source fixture case
-> selected target evidence
-> source candidates and edit-surface candidates
-> compact/full handoff output
-> per-dimension score and regression reason
```

The first scoring dimensions should be:

- **owner:** expected file or owner composable appears in the recommended set;
- **role:** edit-surface role matches the intended action type;
- **confidence:** confidence does not exceed the allowed cap for the evidence;
- **caution:** required risk/caution tokens are rendered for shared component,
  visual area, interop, weak source, or ambiguous ownership;
- **ranking:** recommended candidate is ranked before less useful candidates
  when the fixture has enough evidence to decide;
- **prompt usability:** compact handoff includes item id, session id, protocol
  footer, and source/context lines needed by a chat-style or MCP-backed agent.

The eval should report both aggregate score and hard failures. Hard failures are
reserved for trust-breaking behavior such as overclaiming exact ownership,
missing required caution, or omitting protocol fields. Lower usefulness scores
can be recorded without blocking unless the case is marked as release-critical.

### Components

- Extend existing handoff eval fixtures and corpus metadata rather than create
  a separate corpus format.
- `:fixthis-mcp` tests for compact/full handoff rendering and edit-surface
  confidence basis.
- `:fixthis-compose-core` tests for source matching and ranking regressions.
- Node report generation for eval summaries under `build/reports/`.
- Release gate mapping for release-critical handoff correctness cases.

### Error Handling

- Missing expected owner in a release-critical case fails.
- Confidence above the case cap fails.
- Missing required caution text fails.
- Multiple plausible candidates are allowed only when the fixture marks the case
  as ambiguous and expects caveated language.
- Report parsing errors fail, because unreadable eval evidence is not useful
  release evidence.

### Acceptance Criteria

- Handoff eval v2 defines the scoring dimensions in code and docs.
- At least one case covers each risky category: shared component, interop-risk,
  visual area, weak/ambiguous source, lazy-list owner, and navigation
  destination owner.
- Release-critical cases fail on overconfident or uncaveated evidence.
- The report includes aggregate score, per-case dimensions, hard failures, and
  reproduction commands.
- Existing `npm run handoff:eval:test` remains the canonical quick check, or a
  clearly named successor command is wired into evidence profiles.

## Track C: Release Drift / Source-of-Truth Guard

### Problem

FixThis has many release documents and evidence surfaces. That is useful, but
it creates drift risk. A source tree can be ahead of the latest tag while
`CHANGELOG.md` or `docs/releases/unreleased.md` still says no changes landed.
Release-readiness claims can also name evidence commands that no longer exist,
or release notes can imply package availability that current observable checks
do not support.

### Design

Add a source-of-truth guard that compares release metadata and repo state:

```text
git tag and commits
-> changelog unreleased section
-> docs/releases/unreleased.md
-> release-readiness claim manifests
-> package/version evidence commands
-> drift findings
```

The guard should classify drift:

- **tag-distance drift:** commits exist after the latest version tag, but
  unreleased notes claim no changes;
- **changelog drift:** `CHANGELOG.md` unreleased section and
  `docs/releases/unreleased.md` disagree;
- **claim drift:** release-readiness claims refer to missing commands, missing
  docs, or evidence commands not present in `package.json`;
- **version drift:** documented package versions disagree with project version
  metadata or release version checks;
- **evidence drift:** release gate maps a claim to a command that is absent or
  not covered by tests.

Not every tag-distance drift should block ordinary development. Blocking policy
should be configurable by command profile:

- fast/local profile reports warnings;
- release profile fails on unresolved release drift;
- strict release gate fails on any drift that can mislead external users.

### Components

- Extend `scripts/check-release-readiness.mjs` when the rule is purely
  readiness-related.
- Add a helper module or new script if git/tag/changelog comparison would make
  the readiness script too broad.
- Add contract tests for each drift class.
- Update `scripts/release-gate.mjs` to include drift guard status in the
  aggregate report.
- Update `docs/contributing/release-readiness.md`,
  `docs/releases/unreleased.md`, and `CONTRIBUTING.md` with the new guard and
  expected maintainer response.

### Error Handling

- Missing latest semver tag is a deferred or warning condition outside release
  mode, but fails strict release mode.
- Missing `docs/releases/unreleased.md` fails.
- Missing `CHANGELOG.md` fails.
- A missing evidence command referenced by release-readiness fails.
- Network-backed package evidence remains handled by the existing release
  reality checks; this guard should not add new network requirements.

### Acceptance Criteria

- A test fixture proves tag-distance drift is detected when commits exist after
  the latest tag and unreleased notes still say no changes landed.
- Changelog/unreleased disagreement is detected with a clear file and section
  reference.
- Release-readiness claim tables are checked for missing package scripts or
  missing command documentation.
- `release:gate` includes drift guard status and propagates fail/deferred
  correctly.
- The guard produces actionable messages, not only generic "release docs stale"
  errors.

## Data Flow

```text
Track A fixture reports
Track B handoff eval reports
Track C drift findings
        |
        v
evidence-runner profiles
        |
        v
release-gate normalization
        |
        v
JSON + Markdown reports under build/reports/
        |
        v
release-readiness docs and maintainer release issue evidence
```

All reports are local artifacts. They are useful for release issues and review,
but they are not committed.

## Testing Strategy

- Node contract tests for fixture descriptor validation, command planning,
  deferral classification, report rendering, drift detection, and release-gate
  claim mapping.
- Gradle/Kotlin tests for source matching, semantic signal extraction,
  edit-surface confidence, and compact handoff rendering.
- Existing connected smoke commands remain opt-in strict runtime evidence:
  `npm run agent-loop:smoke -- --strict`,
  `npm run source-matching:fixtures:runtime -- --strict`, and real console
  smoke commands where relevant.
- `node scripts/check-release-readiness.mjs` and `npm run release:gate:test`
  must cover the new release drift guard.
- `graphify update .` should run after code changes in the implementation phase.

## Rollout

1. Land Track C first if release drift is actively present or blocking release
   hygiene. It is the smallest safety net and protects the rest of the work.
2. Land Track A contract-only matrix next, then add strict connected matrix
   support once the non-connected path is stable.
3. Land Track B eval v2 after Track A has enough external shapes to produce
   useful quality cases.
4. Wire all three tracks into `release:gate` only after their focused tests pass.
5. Update release-readiness and unreleased docs last so docs describe shipped
   behavior, not planned behavior.

## Risks And Mitigations

- **Fixture matrix becomes too slow.** Keep descriptor tests fast and make
  connected runtime paths strict/opt-in unless release mode explicitly requires
  them.
- **Fixture projects become brittle.** Generate minimal local projects instead
  of cloning or vendoring large external repos.
- **Eval scores become arbitrary.** Separate hard trust failures from usefulness
  scores, and keep release-blocking policy limited to trust-breaking regressions.
- **Drift guard blocks normal development.** Use profile-specific severity:
  warnings in fast/local mode, failures in release mode.
- **Release gate becomes unreadable.** Keep aggregate report concise and link to
  focused reports for detailed per-fixture and per-case evidence.

## Open Decisions For Plan Phase

- Whether Track A should be a new `external-fixture-matrix` script or a mode in
  the existing source-matching fixture runner.
- Which three fixture shapes should be connected-runtime candidates first.
- Whether Handoff Correctness Eval v2 should reuse `handoff:eval:test` or add a
  separate `handoff:eval:v2:test` during migration.
- Exact release-mode severity for tag-distance drift on development branches.
