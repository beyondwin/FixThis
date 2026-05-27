# FixThis Clean Architecture Hardening Design

Date: 2026-05-27
Status: Implemented
Scope: one execution-ready architecture hardening spec covering MCP session
boundaries, source/target trust policy extraction, and module dependency
governance.

Related work:

- [Source Matching Trust Program](2026-05-24-source-matching-trust-program-design.md)
- [Source Matching Runtime Trust Fixtures](2026-05-24-source-matching-runtime-trust-fixtures-design.md)
- [Interop Trust And Push-First Console Cleanup](2026-05-27-interop-trust-push-first-console-cleanup-design.md)
- [Architecture Overview](../../architecture/overview.md)
- [Output Schema](../../reference/output-schema.md)

## Summary

FixThis already has the right high-level module shape: `:fixthis-compose-core`
contains pure Kotlin domain contracts and policies, while the Android sidekick,
CLI, Gradle plugin, MCP server, and browser console sit at the outer product
boundaries. The current structural pressure is concentrated in the MCP session
package, where session orchestration, DTO assembly, persistence, preview
reservation, target evidence, reliability decisions, screenshot handling, and
event-log coordination still meet in a few large services.

This design hardens the architecture without changing FixThis's external
contracts. The implementation should keep MCP tool names and payloads, persisted
session JSON fields, CLI commands, Gradle plugin behavior, and bridge protocol
behavior stable. Internally, it should move toward a clean architecture shape:
external adapters translate requests, application services orchestrate use
cases through explicit ports, and pure domain policies live in
`:fixthis-compose-core`.

The work is intentionally session-first. The first track reduces the highest
current complexity in `fixthis-mcp/src/main/kotlin/.../session`. The second
track extracts source matching and target reliability rules that are currently
harder to test because they are mixed with MCP DTO or bridge concerns. The
third track adds lightweight architecture checks so the improved boundaries do
not drift back.

Implementation note: the first implementation pass extracted preview
fingerprint policy, preview save reservation tracking, core target evidence
assembly, MCP target validation, and architecture drift checks while preserving
external contracts.

## Goals

- Preserve all existing external product contracts while improving internal
  structure.
- Keep `FeedbackSessionService` as a temporary facade so console routes, MCP
  tools, and tests can migrate safely.
- Split MCP session responsibilities into explicit application workflows,
  domain policies, and adapter infrastructure.
- Move pure source matching, target evidence interpretation, target reliability,
  annotation mutation, and session sequencing rules into testable domain or
  application use cases.
- Make bridge, file, source-index, screenshot, event-log, preview-cache, clock,
  and id-generation dependencies explicit ports.
- Reduce long parameter lists by introducing command/result objects at
  application boundaries.
- Add focused tests that prove behavior is preserved while internals move.
- Add architecture regression checks after the first cleanup lands.

## Non-Goals

- No breaking changes to MCP tool names, request shapes, response shapes, or
  resource URIs.
- No rename or removal of persisted session JSON fields such as `items`,
  `screens`, `itemId`, `screenId`, `targetEvidence`, `targetReliability`,
  `sourceCandidates`, or `editSurfaceCandidates`.
- No bridge protocol version change unless a later feature explicitly requires
  an additive bridge capability.
- No CLI command behavior change.
- No Gradle plugin behavior change.
- No release-build support, cloud service behavior, AccessibilityService,
  XML/View exact source targeting, WebView DOM inspection, Flutter, React
  Native, or iOS support.
- No big-bang package rewrite that forces every route, tool, and test to change
  at once.
- No new runtime dependency on Graphify or generated `graphify-out/` artifacts.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots,
  local fixture workspaces, or generated reports.

## Current State

The repository already documents the intended boundary:
`:fixthis-compose-core` should not know about MCP, CLI, Android UI surfaces, or
`.fixthis` file layout. That invariant should remain the north star.

The implementation already contains useful pieces:

- `FeedbackSessionService` is a facade over registry, annotation workflow, and
  evidence coordination, but it still has high fan-in and exposes many workflow
  methods.
- `AnnotationWorkflow` delegates claim and resolve to core use cases, proving
  the repository already supports an application/domain split.
- `FeedbackDraftService` coordinates request validation, preview save
  reservation, fingerprint handling, target evidence building, screenshot
  promotion, and store writes in one class.
- `TargetEvidenceService` builds feedback items, validates targets, reads source
  indexes, derives evidence, refreshes source evidence, and calculates target
  reliability.
- `FeedbackSessionStoreDelegate` owns in-memory state, snapshot persistence,
  event logging, compaction triggers, session lifecycle, item mutation, handoff,
  and draft cleanup.
- `SourceMatcher` and `TargetReliabilityCalculator` are already pure enough to
  test directly, but adjacent evidence interpretation and MCP assembly still
  mix product policy with DTO concerns.

The design should build on those improvements instead of replacing them with a
large rewrite.

## Design Principles

1. Behavior preservation beats architectural purity.
2. The external contract remains the safety rail; internal classes can move only
   when contract tests stay green.
3. Use ports where concrete IO currently makes orchestration hard to test.
4. Move only pure rules into `:fixthis-compose-core`; keep product-specific MCP
   wording and persisted DTO compatibility in `:fixthis-mcp`.
5. Add abstractions only when they remove real coupling or make a workflow
   independently testable.
6. Source and target trust must remain conservative. Missing, stale, weak, or
   interop-adjacent evidence should downgrade confidence instead of inventing
   certainty.
7. Architecture checks should be lightweight and enforce the boundaries that
   actually matter.

## Architecture

The target architecture has three layers inside the existing module map.

### Adapters

Adapters translate external contracts and own concrete IO:

- MCP tools and resources.
- HTTP console routes and browser DTOs.
- CLI bridge calls and ADB-facing transport.
- Android sidekick bridge DTOs.
- Session JSON, event logs, preview cache, screenshot artifact promotion, and
  source-index reads.

Adapters may know about DTO names, file paths, bridge payloads, and backward
compatibility. They should not contain reusable source confidence, reliability,
or session mutation policy.

### Application Services

Application services orchestrate workflows through explicit ports and
command/result objects:

- Session lifecycle and session lookup.
- Preview capture and frozen-preview save.
- Draft validation, reservation, commit, and cancellation.
- Handoff batch creation and read-feedback refresh.
- Claim, resolve, delete, and draft mutation workflows.
- Source evidence refresh for handoff.
- Connection status and launch recovery.

Application services may know the product workflow and transaction boundaries,
but they should not directly parse bridge JSON, write files, or know browser
route DTO details.

### Core Domain And Policies

Core domain and policies contain pure rules:

- Annotation and session state transitions.
- Sequence assignment and mutation invariants.
- Source matching scoring, confidence, risk precedence, and interpretation.
- Target evidence quality classification when it can be expressed without MCP
  DTOs or IO.
- Target reliability confidence, reasons, warnings, and downgrade policy.
- Selection, nearby-node, identity, occurrence, and redaction policies.

The core can expose typed models and use cases. It must not depend on MCP,
CLI, Android runtime UI, `.fixthis` paths, browser console DTOs, or persisted
session JSON compatibility names.

## Track A: MCP Session Application Boundary

### Objective

Turn the current MCP session package into a set of focused application services
behind the existing facade.

### Components

`FeedbackSessionService` remains public to current callers during the first
implementation plan. It should become a constructor and pass-through facade with
minimal logic. New or refined collaborators should own specific workflows:

- `SessionLifecycleService`: open, resume, list, close, current-session lookup.
- `PreviewCaptureWorkflow`: capture current screen, capture preview, navigate
  with optional capture.
- `PreviewFeedbackSaveWorkflow`: validate draft input, reserve preview save,
  perform live fingerprint recapture when requested, commit or cancel the save.
- `FeedbackMutationWorkflow`: add selected/area feedback, update draft, delete
  draft, clear drafts, claim, resolve.
- `HandoffWorkflow`: refresh source evidence, create handoff batch, mark items
  handed off, render compact and detailed handoff output.
- `ConnectionWorkflow`: devices, selected device, heartbeat, connection status,
  app launch recovery.

This design does not require all names to be exact. The implementation plan
should choose names that fit the local package style, but each workflow should
have one clear purpose.

### Ports

Introduce ports only where they cut concrete IO out of application logic:

- `SessionStorePort`: load, save, mutate, and list sessions.
- `PreviewCachePort`: get, put, and evict preview records.
- `SourceIndexPort`: read a source index for a package and screen.
- `ScreenshotArtifactPort`: promote or resolve screenshot artifacts.
- `BridgeScreenPort`: capture screen, capture preview data, navigate, heartbeat.
- `Clock` and `IdGenerator`: deterministic time and ids for workflow tests.

Initial adapters can wrap existing classes such as `FeedbackSessionStore`,
`PreviewSnapshotCache`, `TargetEvidenceService` source-index access, and
`ScreenshotArtifactPromoter`. The first goal is explicit dependency direction,
not new storage behavior.

### Data Flow

The save-feedback flow should become:

1. Console route or MCP tool receives the existing DTO.
2. Adapter mapper builds a `SavePreviewFeedbackCommand`.
3. Application workflow validates the command and reserves the preview save.
4. If live fingerprint checking is needed, the workflow asks a bridge port for
   the current screen and records mismatch metadata.
5. Domain or policy services build target evidence, source candidates,
   reliability, and item mutation data.
6. Store adapter persists the snapshot and items with existing JSON fields.
7. Adapter mapper returns the existing response DTO.

The same pattern should apply to handoff, claim, resolve, and capture flows:
adapters translate, application orchestrates, core policies decide, adapters
persist or call IO.

### Error Handling

External error codes and prefixes remain stable, including existing messages
such as `PREVIEW_NOT_FOUND`, `PREVIEW_SAVE_IN_PROGRESS`, `SCREEN_NOT_FOUND`,
and `SESSION_CLOSED`.

Inside the application layer, distinguish:

- request validation failure
- reservation conflict
- missing preview
- missing screen
- stale source evidence
- fingerprint unavailable
- fingerprint mismatch
- bridge IO failure
- persistence failure

Preview save reservation must be released on every failure before commit.
Commit should be the only step that mutates persisted session state.

### Acceptance Criteria

- `FeedbackSessionService` remains source-compatible for existing route and MCP
  callers.
- Preview save behavior is covered through a smaller workflow test without
  constructing the full facade.
- Failure paths release preview reservations.
- Existing console/MCP session route tests still pass.
- No persisted JSON field names change.

## Track B: Source And Target Domain Policy Extraction

### Objective

Make source matching, target evidence interpretation, and target reliability
more testable and SOLID without reducing the conservative trust behavior built
by the source matching trust program.

### Components

`TargetEvidenceService` should split along these responsibilities:

- target validation against a captured screen
- target context extraction from selected and nearby nodes
- source-index read coordination
- source candidate refresh
- target evidence assembly
- target reliability calculation
- feedback item assembly for MCP DTOs

Pure calculations should move to `:fixthis-compose-core` or to small pure
classes that depend only on core models. MCP-specific DTO assembly should remain
in `:fixthis-mcp`.

`SourceMatcher`, `SourceConfidencePolicy`, `SourceRiskClassifier`,
`SourceInterpretationFactory`, and `TargetReliabilityCalculator` should remain
or become the main places for reusable trust policy. If new policy is needed,
prefer extending these focused classes over adding more conditional logic to
MCP services.

### Data Flow

For a selected target, the desired flow is:

1. Application workflow receives a typed target command.
2. Target validation returns a validated target and evidence nodes.
3. Source-index port returns the best available source index or a structured
   unavailable result.
4. Core matching policy returns source candidates, confidence, risk, and
   interpretation data.
5. Core reliability policy returns confidence, reasons, and warnings.
6. MCP mapper assembles persisted `AnnotationDto` fields without renaming
   existing JSON.

### Error And Trust Handling

Weak evidence should be represented explicitly:

- visual-area-only targets stay low or unknown confidence unless stronger
  evidence exists
- possible interop remains verification-first
- stale source indexes add warning metadata
- low margin source candidates stay cautious
- sensitive redaction prevents overclaiming
- missing source index does not invent source candidates

The implementation should prefer confidence caps and warning tokens over
optimistic fallbacks.

### Acceptance Criteria

- Target evidence and reliability policy can be tested without bridge or file
  IO for common cases.
- Source candidates are still emitted in existing JSON shape.
- Existing source matching fixture tests remain green.
- New tests cover at least one extraction where policy moves out of
  `TargetEvidenceService`.
- No core code imports MCP, CLI, browser console, or `.fixthis` path concerns.

## Track C: Module Governance And Drift Prevention

### Objective

Add lightweight enforcement for the architecture rules after the first
refactors make those rules realistic.

### Rules

The first architecture checks should enforce:

- `:fixthis-compose-core` does not import `io.github.beyondwin.fixthis.mcp`,
  `io.github.beyondwin.fixthis.cli`, sidekick bridge/runtime packages, or local
  `.fixthis` path helpers.
- MCP application workflow code does not directly parse bridge JSON or write
  event-log files when a port exists.
- DTO mappers stay at adapter boundaries.
- Persisted session JSON field compatibility stays documented and tested.
- Graphify output remains an agent navigation aid, not a runtime dependency.

### Enforcement Shape

Use the lightest local mechanism that fits the repository:

- focused unit tests that scan imports and package names
- existing detekt configuration if a rule is easy to express there
- small script checks only if Kotlin tests would be awkward

Do not introduce a large architecture testing dependency unless the first
implementation plan shows a clear need.

### Acceptance Criteria

- At least one automated check proves `:fixthis-compose-core` stays independent
  from MCP/CLI/sidekick runtime concerns.
- The check is cheap enough to run in the normal local verification path.
- The rule is narrow enough that future legitimate refactors can update it
  without fighting a broad static-analysis framework.

## Implementation Sequencing

The implementation should be incremental:

1. Add characterization tests around the first workflow to be split.
2. Introduce command/result models for preview feedback save.
3. Extract preview save reservation and commit workflow from
   `FeedbackDraftService`.
4. Extract target validation and evidence policy seams from
   `TargetEvidenceService`.
5. Move pure reliability or source interpretation rules into core only after
   tests pin current behavior.
6. Split store mutation/replay responsibilities only where the workflow split
   needs a clearer port.
7. Add architecture drift checks once the first application boundary is real.
8. Remove dead pass-throughs from the facade only after callers have migrated or
   the facade method is no longer useful.

Each step should be independently reviewable and should avoid broad formatting
or unrelated test rewrites.

## Testing Strategy

The verification strategy has three layers.

### Contract Tests

Keep existing MCP, console route, session JSON, and output schema behavior
pinned while internals move:

- MCP protocol and tool tests.
- Console feedback item, preview, handoff, events, and connection route tests.
- Persisted session compatibility tests.
- Output schema documentation checks where present.

### Domain And Workflow Tests

Add smaller tests for extracted rules:

- preview save validation, reservation, cancellation, and commit
- fingerprint mismatch and unavailable-fingerprint decisions
- target reliability reasons and warnings
- source confidence and risk precedence
- source evidence refresh
- session mutation and sequence assignment

Tests should prefer pure core/application collaborators over full facade
construction when the behavior no longer needs route wiring.

### Local Verification Ladder

The expected local ladder for this architecture work is:

1. Focused Gradle tests for touched Kotlin classes.
2. Focused console/MCP route tests for affected workflows.
3. `npm run source-matching:fixtures:test` when source matching or reliability
   behavior changes.
4. `git diff --check`.
5. `graphify update .` after code changes so the repository graph stays current.

Runtime strict fixture verification should be added only when the change touches
runtime trust capture, Android bridge behavior, or emulator-backed evidence.

## Documentation

Update documentation only when behavior or architecture contracts change:

- `docs/architecture/overview.md` for durable module and boundary changes.
- `docs/reference/output-schema.md` only for additive schema documentation.
- `docs/reference/mcp-tools.md` only if externally visible tool behavior
  changes, which this design does not intend.
- `CONTRIBUTING.md` only if the local verification ladder changes.

Do not update `AGENTS.md` unless the canonical docs it points to change in a
way agents must notice immediately.

## Risks

- Over-abstracting early could make the code harder to follow. Mitigation:
  introduce ports only around real IO or hard-to-test orchestration.
- Moving policy into core could accidentally pull MCP DTO compatibility into
  core. Mitigation: core uses typed domain models; MCP owns DTO mapping.
- Contract tests may be too broad and slow for every small refactor. Mitigation:
  use focused tests during implementation and keep the full ladder for final
  verification.
- Store and event-log behavior is sensitive. Mitigation: split mutation ports
  only after characterization tests pin snapshot and event-log behavior.

## Final Acceptance Criteria

- External MCP, console, CLI, Gradle plugin, bridge, and persisted JSON
  contracts remain stable.
- `FeedbackSessionService` is thinner and delegates meaningful workflows to
  focused collaborators.
- At least one large session workflow can be tested without building the full
  console/MCP facade.
- Target evidence or reliability policy has a clearer pure-testable seam.
- `:fixthis-compose-core` remains free of MCP, CLI, sidekick runtime, and
  `.fixthis` concerns.
- Architecture drift checks protect the most important dependency rule.
- Source matching trust fixtures remain green when trust policy is touched.
- The implementation lands in small commits with focused verification evidence.
