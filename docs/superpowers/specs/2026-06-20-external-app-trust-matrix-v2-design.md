# External App Trust Matrix v2 Design

## Summary

External App Trust Matrix v2 extends the current external-app setup and
first-handoff evidence line. The goal is to prove that an external Compose app
can move from `fixthis install-agent` and `fixthis doctor --json` into one
agent-readable handoff whose source and target evidence stays useful without
overclaiming exact edit ownership.

The work will connect existing verification surfaces instead of replacing
them:

- `external-fixture:matrix` for external project setup shapes.
- `agent-loop:smoke` for the first feedback handoff lifecycle.
- `source-matching:fixtures` and runtime trust observations for source and
  target confidence behavior.
- `release:gate` and the evidence runner for pass, deferred, and fail
  aggregation.

## Product Decision

Prioritize the external trust loop over new package channels or broader UI
stack support. FixThis already has public install paths and a documented
agent-first setup flow. The next high-value improvement is stronger evidence
that those public paths produce a trustworthy handoff when used against real
external project shapes.

This keeps V1 focused on local, debug-only Jetpack Compose apps. It does not
add XML/View exact source targeting, WebView DOM inspection, cloud review
behavior, or automatic code edits.

## Goals

- Validate more external project shapes through install, doctor, and generated
  metadata checks.
- For runtime-capable fixtures, continue into a first handoff and assert that
  one agent-readable feedback item is produced.
- Check handoff trust signals: `sourceCandidates`, `targetReliability`,
  `editSurface`, target warnings, source risk flags, shared-component call-site
  guidance, interop boundary context, and visual-area caveats.
- Classify outcomes as `pass`, `deferred`, `fail`, or fixture drift without
  mixing environmental blockers with product regressions.
- Make release-gate reporting consume the new matrix as one explicit claim.

## Non-Goals

- No public CLI, MCP, bridge, or persisted feedback JSON field rename.
- No new package channel such as PyPI or Docker.
- No release-build support.
- No exact XML/View or WebView source ownership claim.
- No CI-required connected Android gate. Strict connected runs remain local
  release evidence.

## Scope

The feature has two evidence layers.

### Setup Layer

The setup layer expands the fixture matrix around external project shapes:

- Single app module.
- Multi-module app with source-index aggregation through app dependencies.
- Flavor or `applicationIdSuffix` shape where package resolution matters.
- Compose-only baseline with generated FixThis metadata.
- Weak source-evidence fixture where setup succeeds but source trust remains
  caveated.

Each fixture declares whether it is setup-only or runtime-capable. Setup-only
fixtures stop at `doctor --json` readiness. Runtime-capable fixtures must be
installable, launchable, capturable, annotatable, handed off, and inspectable.

### Runtime Trust Layer

Runtime-capable fixtures continue through the first handoff lifecycle:

1. Launch or recover the debug app.
2. Capture a screen through the console or MCP path.
3. Create one written feedback item.
4. Persist the item through Copy Prompt or Save to MCP semantics.
5. Read the queue or prompt payload.
6. Assert handoff trust expectations.

The runtime layer does not need to prove every UI workflow. It proves that the
first external handoff contains honest enough evidence for an agent to start
work without guessing from a screenshot alone.

## Architecture

### Fixture Catalog

Add a small catalog model for external trust matrix cases. Each case must
describe:

- Fixture id and source.
- Project directory or fixture generator.
- Expected `applicationId`.
- Setup expectations.
- Runtime capability.
- Runtime target selector for runtime-capable cases.
- Trust expectations for runtime-capable cases.

The catalog must avoid embedding release-specific paths in code. Generated
fixture workspaces remain under local ignored directories such as `.fixthis/`
or `build/`.

### Setup And Readiness Runner

Extend the existing external fixture runner instead of adding an unrelated
script. For each case, it runs in a fixture-local home/config scope:

```bash
fixthis install-agent --project-dir <fixture> --target all --package <id>
fixthis doctor --project-dir <fixture> --package <id> --json
```

The runner records generated metadata, MCP config template availability,
doctor checks, top-level `readiness`, `nextAction`, and recovery text. Android
runtime blockers are not treated as setup regressions unless the fixture is
running in strict mode.

### Runtime Trust Probe

Runtime-capable cases reuse the existing first-handoff and source-trust
surfaces. The probe produces observations rather than raw logs:

- Feedback item exists and is agent-readable.
- Top source candidate confidence and risk flags.
- Target confidence and warnings.
- Edit-surface roles and confidence basis.
- Boundary context for AndroidView/WebView-risk targets.
- Recommended edit site for shared components only when unambiguous.

The probe marks overconfidence as a failure. A low or medium confidence
result with the correct warning can be a pass.

### Evidence Aggregation

The matrix writes machine-readable JSON and a compact Markdown report under a
build report directory. The release gate consumes that report as an explicit
claim such as `external-trust-matrix-v2`.

The report must preserve the distinction between:

- Product failure.
- Environment deferred.
- Fixture drift.
- Caveated pass.

This distinction matters because connected Android evidence is local-only and
some maintainers will run non-strict evidence without an emulator.

## Data Flow

1. Read the fixture catalog.
2. Prepare each fixture workspace.
3. Run `install-agent` with fixture-local agent config and home directories.
4. Run `doctor --json`.
5. Normalize setup readiness into a case result.
6. For runtime-capable cases, launch or recover the app.
7. Capture, annotate, and hand off one feedback item.
8. Extract source, target, edit-surface, and warning observations.
9. Compare observations with fixture expectations.
10. Write report JSON and Markdown.
11. Let release gate map report status into a release claim.

## Failure Classification

### Deferred

Use `deferred` when local runtime prerequisites are absent in non-strict mode:

- Android SDK missing.
- ADB missing.
- No ready emulator or device.
- Device locked or non-interactive.
- App not launched and no valid launch recovery was attempted.

Deferred results must include the exact next action. Strict mode converts these
blockers into failures.

### Product Failure

Use `fail` for product regressions:

- `install-agent` cannot patch a supported Gradle project shape.
- `doctor --json` emits contradictory readiness or missing `nextAction`.
- Generated metadata is missing after a successful install path.
- First handoff fails despite available runtime prerequisites.
- Handoff source confidence is high where the case requires caveats.
- Interop, visual-area, or shared-component warnings are missing.
- A shared-component definition is promoted as an exact single edit target
  without a strong call-site basis.

### Fixture Drift

Use `fixture_drift` when a pinned fixture no longer matches the case contract:

- Expected source path is gone.
- Upstream module dependency graph no longer exposes the evaluated source.
- Launch state changes in a way unrelated to FixThis.

Fixture drift must not count as product pass. It must also avoid implying a
FixThis regression until the fixture is re-pinned or corrected.

### Caveated Pass

Use caveated pass when FixThis cannot identify an exact edit site but says so
honestly. Examples:

- Visual-area target reports `VISUAL_AREA_ONLY` and low or medium confidence.
- AndroidView/WebView-risk target reports boundary context and avoids exact
  ownership.
- Shared component reports definition risk and either no recommended edit site
  or one clearly ranked call site while keeping confidence capped.

## Testing

The implementation plan must include focused tests at each boundary:

- Node contract tests for fixture catalog parsing and matrix report rendering.
- Existing CLI tests for `doctor --json`, `install-agent --json`, and generated
  agent setup files when JSON shape or readiness mapping changes.
- Source matcher and handoff eval tests for shared-component, copy/data,
  layout/style, visual-area, and interop expectations.
- Release gate tests that map the new report into pass, deferred, and fail
  claim statuses.
- Strict runtime smoke remains a local connected command, not a required CI
  check.

## Compatibility And Contracts

The design preserves existing compatibility contracts:

- Persisted feedback fields such as `items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, `targetReliability`, and `sourceCandidates` remain stable.
- MCP tool names and current public CLI commands remain stable.
- Bridge protocol changes are out of scope.
- `:fixthis-compose-core` remains independent of MCP, CLI, Android UI, and
  `.fixthis/` paths.

New report fields are internal evidence-runner contracts unless explicitly
documented in `docs/reference`.

## Rollout

1. Add the matrix v2 fixture catalog and setup-only report path.
2. Add runtime trust observations for one controlled runtime-capable fixture.
3. Add overconfidence and missing-warning checks.
4. Wire report consumption into `release:gate`.
5. Update release readiness docs only after the evidence command and claim id
   exist.

The first implementation must prefer a small matrix with strong contracts
over a broad matrix with fragile runtime selectors.

## Acceptance Criteria

- A non-strict run produces a JSON and Markdown report with setup, runtime,
  deferred, fixture drift, and trust finding sections.
- Strict mode fails when Android runtime prerequisites are missing for a
  runtime-capable case.
- At least one runtime-capable case reaches an agent-readable handoff item in
  a connected environment.
- Interop, visual-area, and shared-component cases do not produce high
  confidence exact-ownership claims unless the case explicitly allows it.
- `release:gate:test` covers the new claim mapping.
- Existing public setup docs remain accurate.
