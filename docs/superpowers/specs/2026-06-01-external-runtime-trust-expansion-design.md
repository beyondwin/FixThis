# External Runtime Trust Expansion Umbrella - Design

Date: 2026-06-01
Status: Ready for user review
Scope: external Android fixture matrix expansion, risky target runtime trust,
and release-gate aggregation for the next v1.x hardening line.

Related work:

- [External Trust Matrix and Release Drift Guard](2026-06-01-external-trust-matrix-release-drift-design.md)
- [v1.1 Trust Loop Umbrella](2026-05-31-v11-trust-loop-umbrella-design.md)
- [Release Gate, Interop, and SSE Umbrella](2026-05-31-release-gate-interop-sse-umbrella-design.md)
- [FixThis V1 Trust, Install, and Inner-Loop Hardening](2026-05-27-fixthis-v1-trust-install-inner-loop-hardening-design.md)
- [Source Matching Runtime Trust Fixtures](2026-05-24-source-matching-runtime-trust-fixtures-design.md)

## Summary

FixThis now has strong internal evidence for release reality, external agent
lifecycle smoke, source matching, interop caution rendering, and release-gate
aggregation. The next high-leverage hardening line should prove that this trust
loop stays honest outside the bundled sample and across high-risk target types.

This umbrella combines two approved improvement areas:

1. expand the external Android project evidence matrix; and
2. deepen runtime evidence for `interop-risk`, `visual-area`, and weak source
   candidate cases.

The shared release claim is:

> FixThis can verify its agent handoff loop against realistic external Android
> project shapes while keeping interop-risk and visual-area selections honest
> through runtime evidence instead of exact source ownership claims.

The work is evidence-first. It should extend the existing matrix, runtime
fixture, handoff, evidence-runner, and release-gate systems rather than create a
parallel validation platform.

## Goals

- Verify `install-agent -> doctor --json -> source-index or explicit next
  action -> console handoff` across representative external Android project
  shapes.
- Keep external fixture reports deterministic, local-first, and explicit about
  pass, deferred, and fail outcomes.
- Add or strengthen runtime trust cases for `interop-risk`, `visual-area`, and
  weak source candidate evidence.
- Ensure risky targets remain useful context without being rendered as exact
  source ownership or a direct "edit here" instruction.
- Aggregate the new evidence into release decision reports so future v1.x
  claims are backed by commands and deferred reasons.
- Preserve persisted MCP/session JSON compatibility contracts.

## Non-Goals

- No release-build sidekick behavior. FixThis remains debug-only.
- No XML/View exact source targeting.
- No WebView DOM inspection.
- No Flutter, React Native, iOS, or cloud workflow expansion.
- No automatic app-code edits inside external fixture projects.
- No new package channel such as PyPI or Docker.
- No bridge-protocol or persisted JSON breaking change.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots,
  generated reports, downloaded repos, or local fixture workspaces.

## Architecture

The design keeps current module boundaries:

- `scripts/external-fixture-matrix.mjs` owns external project shape planning,
  setup execution, readiness expectations, strict connected execution, and
  matrix report rendering.
- `fixtures/source-matching/manifest.json` and
  `scripts/source-matching-fixtures.mjs` own source-index and runtime trust
  fixture declarations.
- `:fixthis-compose-core` owns target reliability, source confidence,
  confidence caps, risk flags, caution policy, and edit-surface classification.
  It must not gain dependencies on MCP, CLI, Android UI, `.fixthis/`, report
  paths, or fixture workspaces.
- `:fixthis-mcp` owns MCP/session persistence, compact/full handoff rendering,
  console reflection, and agent-facing wording.
- `scripts/evidence-runner.mjs` and `scripts/release-gate.mjs` aggregate focused
  evidence into local release-decision reports.

New evidence must be additive. Persisted fields such as `items`, `screens`,
`itemId`, `screenId`, `targetEvidence`, `targetReliability`,
`sourceCandidates`, and `editSurfaceCandidates` remain compatibility contracts.
Any new field must be optional, documented, and tolerated when absent.

## Track A: External Fixture Matrix Expansion

### Problem

The current external evidence proves important lifecycle behavior, but it does
not yet cover enough Android project shapes to defend a broader install claim.
External users vary by Gradle layout, app module location, source-set shape,
published artifact path, generated metadata state, and agent configuration.

A release can pass the bundled sample and still fail in a realistic external
repo. The matrix should make those failures visible before release notes imply
the path is generally reliable.

### Design

Extend the external fixture matrix around small generated or pinned project
shapes:

```text
fixture descriptor
-> prepare isolated workspace
-> install local or published FixThis path
-> fixthis install-agent
-> fixthis doctor --json
-> source-index generation or explicit expected nextAction
-> optional runtime launch and console handoff
-> matrix report: pass / deferred / fail
```

The first expanded matrix should cover at least these project shapes:

- simple single-module Compose app;
- multi-module app with a non-root application module;
- published-install path that exercises Gradle Plugin Portal and Maven
  coordinates instead of a source checkout;
- project with pre-existing agent config files;
- project where generated metadata is missing and `doctor --json` must return a
  recoverable next action.

At least three shapes should run the non-connected setup path. Strict connected
execution can initially target one or more shapes when an Android SDK and
unlocked device or emulator are available.

### Error Handling

- Missing Android SDK, unavailable ADB, locked emulator, or missing connected
  device is `deferred` in non-strict mode and `fail` in strict mode.
- Fixture descriptor or manifest errors always fail.
- `install-agent` write failures fail and identify the fixture id and target
  file.
- `doctor --json` can pass with a recoverable state only when the expected
  readiness code and `nextAction` are declared by the fixture.
- Workspace cleanup failures fail in CI-facing modes because leaked state can
  corrupt later fixture runs.

### Acceptance

- At least five fixture shapes have descriptor/contract coverage.
- At least three fixture shapes execute
  `install-agent -> doctor --json -> source-index or explicit nextAction`.
- Strict connected mode can run against at least one matrix fixture when runtime
  prerequisites are available.
- Matrix reports include fixture id, shape, command list, status, duration, and
  deferred reason.
- No generated fixture workspace or local setup artifact is committed.

## Track B: Risky Target Runtime Evidence

### Problem

`interop-risk`, `visual-area`, and weak source candidate cases are high-value
because they tell agents where to inspect. They are also high-risk because the
wrong wording can make context look like exact source ownership.

Existing handoff and console rendering already caution these cases. The next
step is to verify that caution with runtime evidence, not only formatter tests
or source-index fixtures.

### Design

Strengthen runtime trust fixtures for risky targets:

```text
debug app runtime fixture
-> install and launch app
-> capture real screen through MCP/session path
-> select node target or visual area
-> observe targetReliability and sourceCandidates
-> render JSON + compact/full handoff
-> assert useful context without exact ownership overclaim
```

The first runtime set should include:

- an `interop-risk` selection with nearby Compose boundary/context evidence;
- a `visual-area` selection that does not invent a fake likely source when
  source evidence is absent or weak;
- a weak source candidate case where confidence, risk flags, and caution are
  consistent across JSON and Markdown;
- a shared-component or reusable-call-site case only if it helps calibrate the
  risky-target language without duplicating existing shared-component ranking
  coverage.

The runtime observations should focus on trust essentials:

- target reliability warnings;
- source candidate confidence and risk flags;
- edit-surface role and confidence basis;
- required caution/action wording;
- absence of exact ownership language for interop and visual-area targets.

### Error Handling

- Missing runtime trust observation is a failure in strict runtime mode.
- Missing Android runtime prerequisites are deferred only through non-strict
  evidence profiles and must include the exact reason.
- A source candidate may be absent for visual-area targets; absence should be
  preserved rather than filled with nearby-label guesses.
- Interop boundary context may be present, but it must remain context and must
  not raise confidence into an exact edit target.
- Older persisted sessions without newer optional fields continue to render
  without crashing or changing compatibility contracts.

### Acceptance

- Runtime trust fixtures include at least one `interop-risk` case and one
  `visual-area` case.
- Strict runtime output fails when required trust observations are missing.
- Compact and full handoffs preserve trust-essential tokens for risky targets.
- Interop-risk handoffs expose boundary/context evidence without exact source
  ownership.
- Visual-area handoffs do not invent a likely source when evidence is weak or
  absent.
- Weak candidate cases keep confidence caps, risk flags, and caution text
  aligned between JSON and Markdown.

## Track C: Release Gate Aggregation

### Problem

External matrix results and risky-target runtime evidence are only useful for a
release claim if they flow into the release decision surface. Otherwise a
maintainer can update release notes while skipping the exact evidence that
backs the claim.

### Design

Wire Track A and Track B into the existing evidence and release-gate layers:

```text
external fixture matrix report
risky target runtime trust report
handoff eval / focused MCP tests
        |
        v
evidence-runner trust or release profile
        |
        v
release-gate normalization
        |
        v
JSON + Markdown report: pass / pass_with_deferred / fail
```

The release readiness docs should state the claim and list the required
commands. Strict release mode fails on missing connected evidence. Non-strict
profiles may pass with deferred evidence only when the report records the exact
environment reason.

### Error Handling

- Missing commands referenced by release-readiness fail readiness checks.
- A failed risky-target trust case fails the aggregate gate.
- A deferred connected command fails strict mode and becomes
  `pass_with_deferred` only in non-strict mode.
- Release notes must not claim external runtime coverage unless the gate maps
  that claim to Track A and Track B evidence.

### Acceptance

- `release:gate` or an equivalent release profile includes external matrix and
  risky runtime trust evidence.
- `release:gate:test` or focused contract tests pin pass/deferred/fail
  normalization for the new evidence.
- `docs/contributing/release-readiness.md` includes a manifest row for the
  external runtime trust expansion claim.
- `npm run evidence:trust -- --dry-run` shows the new evidence commands without
  hiding the canonical focused commands.

## Data Flow

```text
external fixture project
-> install local/published FixThis path
-> fixthis install-agent / doctor --json
-> optional runtime launch and console handoff
-> matrix report

risky runtime fixture
-> install debug app
-> capture real screen through MCP/session
-> select interop-risk or visual-area target
-> read sourceCandidates / targetReliability / handoff markdown
-> trust report

release gate
-> collect matrix + runtime trust + handoff evidence
-> strict mode fails missing connected evidence
-> non-strict mode records exact deferred reason
```

Reports are local artifacts under `build/reports/`. They are useful for release
issues and review but are not committed.

## Testing Strategy

- **Contract tests:** descriptor validation, command planning, readiness and
  `nextAction` expectations, strict/non-strict environment classification,
  report rendering, and release-gate normalization.
- **Runtime connected tests:** strict execution for external matrix and runtime
  trust only when an Android SDK and unlocked device/emulator are available.
  Both `ANDROID_HOME` and `ANDROID_SDK_ROOT` should be normalized to the
  detected SDK before connected commands run.
- **MCP / handoff tests:** compact/full Markdown and JSON payloads preserve the
  same trust essentials for interop-risk, visual-area, and weak candidate
  cases.
- **Release aggregation tests:** release-gate and evidence-runner tests prove
  that missing, deferred, and failed evidence are reported distinctly.

Focused verification commands for the implementation plan:

```bash
npm run external-fixture:matrix:test
npm run source-matching:fixtures:test
npm run release:gate:test
npm run evidence:trust -- --dry-run
```

Strict runtime commands when prerequisites are available:

```bash
npm run external-fixture:matrix -- --strict
npm run source-matching:fixtures:runtime -- --strict
```

## Rollout

1. Extend matrix contract coverage first so project-shape failures can be
   diagnosed without Android runtime dependencies.
2. Add risky-target runtime trust cases with strict/non-strict environment
   semantics.
3. Wire the focused evidence into `evidence:trust` and `release:gate`.
4. Update release-readiness and roadmap docs after the commands and reports
   exist.
5. Run `graphify update .` after implementation code changes and leave
   `graphify-out/` uncommitted.

## Risks And Mitigations

- **Scope creep:** keep this umbrella to five matrix descriptor shapes and the
  first interop-risk / visual-area / weak-candidate runtime cases.
- **Connected evidence flakiness:** separate contract tests from strict runtime
  checks and require exact deferred reasons in non-strict profiles.
- **False precision:** do not raise risky-target confidence or wording from
  context to exact source ownership.
- **Release claim drift:** require release-readiness rows and release-gate
  evidence mappings before user-facing release notes claim the new line.
- **Local artifact pollution:** keep `.fixthis/`, fixture workspaces, reports,
  screenshots, Android build output, and `graphify-out/` out of commits.

## Open Decisions For Plan Phase

- Which five external fixture shapes should be the first descriptor set if the
  existing matrix already covers some of them.
- Whether the published-install fixture should use only observable public
  package coordinates or a locally built distribution in strict local mode.
- The exact risky-target runtime fixture app/screens used for interop-risk and
  visual-area selection.
- Whether release aggregation lands under `evidence:trust`, `evidence:release`,
  or both.
