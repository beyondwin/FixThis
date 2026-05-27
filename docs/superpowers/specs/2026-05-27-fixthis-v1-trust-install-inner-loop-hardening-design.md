# FixThis V1 Trust, Install, And Inner-Loop Hardening Design

Date: 2026-05-27
Status: Ready for user review
Scope: one umbrella hardening program covering source trust, agent-first
installation evidence, and maintainer verification speed.

Related work:

- [FixThis Trust Sync Release Hardening](2026-05-27-fixthis-trust-sync-release-hardening-design.md)
- [Source Matching Trust Program](2026-05-24-source-matching-trust-program-design.md)
- [Source Matching Runtime Trust Fixtures](2026-05-24-source-matching-runtime-trust-fixtures-design.md)
- [Console Inner Loop Tightening](2026-05-25-console-inner-loop-tightening-design.md)

## Summary

FixThis has recently hardened handoff trust language, SSE-driven console sync,
runtime trust fixtures, and release evidence. The next high-leverage step is
to unify the remaining V1 hardening into one evidence-first program:

1. reduce false confidence in source matching and edit-surface guidance;
2. make agent-first installation and release claims recoverable and
   evidence-backed;
3. make local verification faster to run and easier to diagnose.

The tracks are grouped in one spec because they share the same product
promise: FixThis should give coding agents local, debug-only evidence they can
trust before editing a user's Android app. The implementation should still
split the tracks into independently verifiable tasks so a failure in one area
does not weaken another.

## Goals

- Improve source matching and handoff confidence for interop boundaries,
  visual-area selections, layout renderer patterns, reusable components, and
  copy/data origins.
- Prevent weak evidence from being rendered as exact source ownership.
- Keep persisted MCP/session JSON additive and compatible with existing
  `items`, `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, `sourceCandidates`, and `editSurfaceCandidates`
  contracts.
- Keep `:fixthis-compose-core` free of MCP, CLI, Android UI, and `.fixthis/`
  path concerns.
- Make `fixthis install-agent -> fixthis doctor --json -> console -> Save to
  MCP` easier for agents to recover in external Android repositories.
- Keep current public install channels honest: GitHub Releases, Homebrew,
  npm, MCP Registry, Maven Central, and the Gradle Plugin Portal.
- Add a profile-based local evidence runner that reuses existing commands and
  reports exact failed commands and log/report paths.

## Non-Goals

- No release-build support. FixThis remains debug-only.
- No XML/View exact source targeting and no WebView DOM inspection.
- No Flutter, React Native, iOS, or cloud review behavior.
- No automatic code edits inside FixThis itself.
- No new package channel such as PyPI or Docker in this spec.
- No new browser transport. Existing HTTP and SSE paths remain the console
  transport model.
- No committed `.fixthis/`, `graphify-out/`, Android build output, local
  fixture workspaces, screenshots, generated reports, or Graphify output.

## Design Principles

1. Trust language must not outrun evidence. Weak target evidence should lower
   confidence and guide verification, not imply a precise edit target.
2. Public output contracts are stricter than local fixture schemas. Additive
   fields are allowed only when existing fields cannot express the evidence.
3. Agent install recovery should be machine-readable first and documentation
   should render the same contract.
4. Release claims must have local validation commands. If evidence is missing,
   narrow the claim or mark the evidence deferred.
5. Verification speed should come from orchestration and clearer reporting,
   not from weakening checks.

## Track A: Trust And Source Matching

### Current Problem

FixThis now distinguishes strong Compose targets from visual-area and interop
risk cases, but the remaining source-matching frontier is calibration. Agents
need richer hints for layout renderer patterns, reusable component call sites,
copy/data origins, and AndroidView/WebView boundaries without being told that a
candidate source file definitely owns the rendered pixels.

False confidence is more dangerous than a missing hint. If FixThis ranks a
nearby file too strongly, the downstream agent may edit the wrong component
with confidence. The matcher and handoff renderers should keep useful context
while making uncertainty visible.

### Architecture

`:fixthis-compose-core` remains the policy boundary for target reliability,
source candidate confidence, warning tokens, and role classification. The
Gradle plugin scanner continues to produce source-index signals. `:fixthis-mcp`
owns agent-facing rendering and must not move product policy into Markdown
formatters.

The implementation should prefer existing fields:

- `targetReliability.warnings`
- `targetEvidence.warnings`
- `sourceCandidates[].riskFlags`
- `sourceCandidates[].caution`
- `sourceCandidates[].evidenceStrength`
- `sourceCandidates[].scoreMargin`
- `editSurfaceCandidates[].role`
- `editSurfaceCandidates[].note`

New persisted fields are allowed only if the evidence cannot be represented
clearly with these contracts. Any new field must be optional, additive, and
documented before use.

### Components

- `TargetReliabilityCalculator`: cap confidence for visual-area,
  no-meaningful-target, stale-source, and interop-risk cases.
- Kotlin source scanner: expand and protect signals for `Layout(...)`,
  `SubcomposeLayout(...)`, reusable component declarations, data/copy
  providers, and owner functions.
- Source matcher: keep top1, top3, expected-entry presence, confidence band,
  signal, warning, and risk-flag contracts independent.
- `EditSurfaceRoleClassifier`: distinguish `CALL_SITE`,
  `COMPONENT_DEFINITION`, `COPY_OR_DATA`, `LAYOUT_OR_STYLE`, `VISUAL_AREA`,
  and `INTEROP_RISK`.
- Handoff renderers: separate likely context from safe edit guidance in
  PRECISE, FULL, and COMPACT output.
- Fixture lab: add manifest cases for source-index-only and runtime-trust
  scenarios that prove false-confidence reductions.

### Data Flow

1. Capture and selection produce a semantics node, visual area, or fallback
   target.
2. Core reliability policy assigns confidence, reasons, and warnings.
3. Source matching ranks candidates and records evidence strength, score
   margins, risk flags, and caution text.
4. Edit-surface classification maps source candidates to likely edit roles.
5. MCP renderers produce agent-facing guidance that keeps weak candidates as
   context instead of direct edit instructions.
6. Fixture and runtime evaluators verify ranking, confidence, warning, and
   handoff contracts independently.

### Error Handling

- If target evidence is weak, confidence stays low or unknown.
- If source-index and runtime evidence disagree, render a warning and require
  verification rather than promoting the candidate.
- If runtime trust is unavailable, report a downgrade such as
  `trust_observation_not_configured` instead of silently passing.
- If a visual-area selection has no source candidate, do not invent one from
  nearby labels.
- If scanner evidence is ambiguous, preserve context but cap confidence.

### Acceptance Criteria

- Layout renderer and reusable component cases have regression coverage for
  confidence bands and candidate ranking.
- Visual-area and interop-risk handoffs do not render exact-source claims.
- Copy/data origin hints are distinguishable from visual/layout edit surfaces.
- Source fixture tests enforce top1, top3, expected entry, expected signal,
  warning, risk flag, and confidence band separately.
- Runtime-trust cases remain local-only and strict runs fail only for real
  trust regressions, not unavailable environment setup.

## Track B: Agent Install And Release Evidence

### Current Problem

FixThis now has multiple public entry points: GitHub Releases, Homebrew, npm,
MCP Registry, Maven Central, Gradle Plugin Portal, and source checkout. The
risk is drift between CLI output, generated setup artifacts, docs, snippets,
release notes, and actual install behavior.

An agent in an external Android repo should not need project-specific memory to
recover setup failures. `install-agent` and `doctor --json` should explain the
state, the next action, and whether the user or agent can safely proceed.

### Architecture

The CLI owns machine-readable setup and readiness contracts. Documentation and
release checks render or validate that same contract. Public channel claims
remain evidence-backed and current-channel-only; this spec does not add PyPI,
Docker, or other new registries.

`install-agent`, `init`, `setup`, and `doctor` may share planner logic, but the
implementation should avoid widening release or runtime behavior. `.fixthis/`
artifacts remain local handoff files and must not be committed.

### Components

- `install-agent` JSON report: align `applied`, `skipped`, `errors`, `next`,
  `nextAction`, readiness codes, target status, and restart guidance.
- `doctor --json`: make setup, Gradle metadata, MCP config, source-index, ADB,
  and package readiness actionable for agents.
- `.fixthis/agent-setup.json` and Markdown rendering: keep generated setup
  state consistent with CLI JSON.
- Docs snippets: keep README, `agent-install-snippet.md`,
  `connect-your-agent.md`, CLI reference, and troubleshooting aligned.
- Release readiness checks: validate channel claims and evidence command
  mappings.
- Package-channel evidence: keep Homebrew, npm, MCP Registry, GitHub Release,
  Maven Central, and Gradle Plugin Portal claims tied to known checks.

### Data Flow

1. Agent runs `fixthis install-agent --project-dir . --target all`.
2. CLI detects the Android app module, writes Gradle setup and local setup
   artifacts, and reports exact changes.
3. Agent runs `fixthis doctor --project-dir . --json`.
4. Doctor reports whether the project is ready, recoverable, or blocked.
5. Agent starts the console, saves feedback to MCP, and reads the queue.
6. Release readiness checks verify docs and release notes claim only paths with
   supporting evidence.

### Error Handling

- Partial setup must be transactional where practical. If a target is skipped,
  JSON must explain the reason and next command.
- If an Android project or package cannot be detected uniquely, fail with a
  recoverable readiness code and an explicit `--package` or project-dir action.
- If global config cannot be written safely, continue with project-local
  artifacts where possible and report the blocked target.
- If a public package channel lacks current clean-install evidence, docs and
  release notes must avoid claiming that channel as freshly verified.

### Acceptance Criteria

- `install-agent --json` and `doctor --json` agree on readiness language and
  recovery commands.
- Agent setup artifacts render the same next action contract as CLI JSON.
- README and agent snippets keep the primary flow as `install-agent` then
  `doctor --json`.
- Release readiness checks fail when docs claim unsupported or unverified
  package channels.
- Existing current channels remain documented without expanding into new
  package-manager work.

## Track C: Local Evidence Runner

### Current Problem

FixThis has many useful local checks, but choosing the right subset is slow and
failures can be noisy. Maintainers need a faster way to answer different
questions:

- Did the source trust changes regress?
- Did console reliability regress?
- Are release docs and package metadata aligned?
- Is the full local candidate ready to ship?

The project already has strong individual commands. The missing layer is a
profile-based evidence runner with concise reporting and explicit deferred
handling for environment-dependent checks.

### Architecture

Add a repo-local evidence runner that orchestrates existing commands instead
of replacing them. The runner should support profiles, dry-run output, and a
machine-readable report written under ignored build/report paths. It should
not become a new source of truth for release claims; release readiness docs
should consume or mirror its command matrix.

The runner is local-first. It may call Graphify update when requested by a
profile, but generated `graphify-out/` output remains untracked.

### Components

- Evidence profile matrix:
  - `fast`: cheapest checks for ordinary local edits.
  - `trust`: source matching, handoff, and runtime-trust capable checks.
  - `console`: console JS, SSE, and browser reliability checks.
  - `release`: docs, version, package-channel, changelog, and readiness checks.
  - `full`: broad local integration candidate.
- Runner script: execute commands sequentially, preserve exit codes, and
  report failed command, duration, and log/report path.
- Report writer: write JSON and Markdown under ignored report directories.
- Dry-run mode: print selected commands without executing them.
- Environment probe: detect Android SDK, ADB device, Node dependencies, and
  optional Graphify availability.
- Docs integration: reference profiles from release readiness and contributor
  docs without hiding the canonical underlying commands.

### Data Flow

1. Maintainer selects a profile.
2. Runner resolves profile commands and environment requirements.
3. Runner executes existing commands in deterministic order.
4. Each step records status as passed, failed, skipped, or deferred.
5. JSON and Markdown reports capture command, duration, status, and relevant
   log/report path.
6. The terminal summary highlights the first failure and the next command to
   rerun directly.

### Error Handling

- Missing Android SDK or no unlocked emulator becomes `deferred` for runtime
  checks unless the selected profile explicitly requires strict runtime.
- Missing Node dependencies fails with the setup command needed to continue.
- Graphify missing or failing does not block non-Graphify profiles.
- A failed command stops the profile by default, with an optional continue mode
  reserved for report-gathering workflows.
- Reports are written only under ignored build/report paths.

### Acceptance Criteria

- Dry-run mode prints the profile command matrix without side effects.
- A fast profile completes using only cheap local commands.
- Trust, console, release, and full profiles reuse existing project commands.
- Runtime-only checks are reported as deferred when environment prerequisites
  are missing.
- Failed reports include the exact command to rerun.
- Docs continue to list canonical commands while referencing the runner as a
  convenience layer.

## Test And Evidence Matrix

| Area | Required evidence |
| --- | --- |
| Source trust unit behavior | `./gradlew :fixthis-compose-core:test --no-daemon` |
| Gradle scanner behavior | `./gradlew :fixthis-gradle-plugin:test --no-daemon` |
| Handoff rendering and corpus | `npm run handoff:eval:test` and targeted `:fixthis-mcp:test` renderer tests |
| Source fixture calibration | `npm run source-matching:fixtures:test` |
| Runtime trust, when Android SDK/device is available | `npm run source-matching:fixtures:runtime -- --strict` |
| Agent setup JSON and doctor | targeted `:fixthis-cli:test` install-agent and doctor tests |
| Docs and CLI surface | `npm run docs:agent-bootstrap:test` and `bash scripts/check-docs-cli-surface.sh` |
| Release readiness | `node scripts/check-release-readiness.mjs` and `npm run release:version:check` |
| Console reliability profile | `node --test scripts/studioReliabilityContract-test.mjs` and `npm run console:browser:reliability` |
| Evidence runner contracts | runner dry-run and report contract tests |
| Final local candidate | `npm run ci:local` or the new full profile after it is introduced |

## Implementation Boundaries

- Track A may touch `:fixthis-compose-core`, source scanner/matcher code,
  handoff renderers, fixtures, fixture scripts, and source-matching docs.
- Track B may touch `:fixthis-cli`, setup artifact renderers, README/docs,
  release readiness checks, changelog, and release notes.
- Track C may add runner scripts, runner tests, report docs, and command
  matrix references.
- Track C must not hide failing checks by default. It can classify runtime
  environment gaps as deferred only when the selected profile allows that.
- All tracks must keep local artifacts ignored.

## Rollout Order

1. Implement Track A first because source trust is the highest product-risk
   surface for downstream agent edits.
2. Implement Track B second so public setup and release claims reflect the
   strengthened trust behavior.
3. Implement Track C last so the runner matrix can include the exact checks
   proven by Track A and Track B.
4. Run focused checks after each track, then the broad local candidate before
   shipping.

## Open Decisions Resolved In This Spec

- All three improvement directions are included in one umbrella spec.
- The implementation remains track-based and independently verifiable.
- The spec does not add new package channels.
- The evidence runner is an orchestration layer over existing commands, not a
  replacement for canonical checks.
- Runtime-trust checks stay local-only and may be deferred when the local
  environment lacks Android SDK or a usable emulator.
