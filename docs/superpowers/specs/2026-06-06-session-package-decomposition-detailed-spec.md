# FixThis `mcp/session` Package Decomposition Detailed Spec

Date: 2026-06-06
Status: Draft for review
Scope: `fixthis-mcp` `session` package internal structure, intra-module boundary
enforcement, and a follow-up lift of MCP-independent edit-surface logic into
`fixthis-compose-core`.
Related implementation plan:
[`../plans/2026-06-06-session-package-decomposition-implementation.md`](../plans/2026-06-06-session-package-decomposition-implementation.md)

## Summary

`fixthis-mcp/.../mcp/session/` has grown to **57 Kotlin files (6,728 lines) in a
single flat package**, plus two already-extracted sub-packages (`domain/`,
`eventlog/`). The flat package now mixes at least seven distinct concerns —
session persistence/event-sourcing, draft workflow, target evidence, edit-surface
analysis, agent-facing handoff rendering, transient preview cache, and host
source freshness — in one namespace. No automated rule keeps these concerns
apart, so each new feature adds another file to the same flat directory and the
only backstop is the per-file line-count ratchet in
`ArchitectureHotspotBudgetTest`.

This is **not a behavior change and not a product rewrite**. The dominant work is
a set of behavior-preserving package moves that group files by responsibility,
followed by an intra-`session` import rule that keeps the groups apart, and
finally a real architecture improvement: lifting the edit-surface domain logic
that is already MCP-independent up into `fixthis-compose-core`, paying down an
existing ADR-0002 violation.

The module-level boundaries are already healthy and stay unchanged:

- `:fixthis-compose-core` has **zero** `android`/`androidx`/MCP/CLI/sidekick
  imports (verified by import scan and by `ModuleBoundaryTest`).
- ADR-0001 (clean-architecture layering) and ADR-0002 (domain models live in
  `compose-core`) remain the correct constraints. This spec extends, not
  rewrites, them.

## Analysis Method

Commands and checks used (2026-06-06, branch `main`):

```bash
# Module map + dependency direction
cat settings.gradle.kts
cat fixthis-compose-core/build.gradle.kts          # pure kotlin-jvm, no android plugin
grep -rl "import android\." fixthis-compose-core/src/main   # -> no output (clean)

# session package inventory
wc -l fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/*.kt | sort -rn

# coupling probes for the editsurface lift
grep "^import" fixthis-mcp/.../session/TargetEvidenceService.kt | grep -v kotlin
grep "^import" fixthis-mcp/.../session/EditSurfaceCandidateService.kt | grep -v kotlin
grep "^import" fixthis-mcp/.../session/EditIntentLexicon.kt | grep -v kotlin

# existing guardrails
find . -path '*/test/*' -name '*.kt' | grep -iE 'arch|boundary'
cat fixthis-mcp/.../mcp/architecture/ModuleBoundaryTest.kt
cat fixthis-mcp/.../mcp/architecture/ArchitectureHotspotBudgetTest.kt
```

## Current Architecture Baseline

### Module boundaries (unchanged by this work)

```text
:fixthis-compose-core      pure Kotlin domain contracts, use cases, models, selection, formatting, source matching
:fixthis-compose-sidekick  debug Android runtime + app-local bridge
fixthis-gradle-plugin/     debug DI + source-index asset generation
:fixthis-cli               desktop CLI + ADB bridge client
:fixthis-mcp               MCP stdio server, feedback session store, local console
:app (sample/)             validation sample app
```

### Existing guardrails (must keep passing, must be updated, not bypassed)

1. `mcp/architecture/ModuleBoundaryTest.kt` — directory-walking, regex
   `^import` scan. Enforces: `compose-core` imports no `android*`/outer module;
   `compose-core/target` imports no `mcp.session`; `compose-core/domain` imports
   no `compose-core.model`; sidekick/gradle-plugin/sample import no `mcp`/`cli`.
   It scans by directory, so **moving files inside `mcp/session` does not break
   it**.
2. `mcp/architecture/ArchitectureHotspotBudgetTest.kt` — per-file line-count
   ratchet keyed by **exact path string**. It references, among others,
   `${mcpMain}session/FeedbackSessionStoreDelegate.kt` (budget 700, current
   680), `session/SessionReplayEngine.kt`, `session/FeedbackDraftService.kt`,
   `session/TargetEvidenceService.kt`. **Every move of a budgeted file must
   update its path key in this test in the same commit**, or the test fails on a
   missing file. This is the single biggest mechanical gotcha in the migration.
3. `mcp/architecture/DispatchArchitectureTest.kt` — tool/resource dispatch shape.
   Not affected by session moves unless a moved file is referenced; verify on
   each phase.
4. The full module test matrix in
   [`CONTRIBUTING.md`](../../../CONTRIBUTING.md#required-local-checks) plus
   `detekt` and `spotlessCheck`.

### The problem, quantified

Flat `session/` root files by responsibility (line counts from `wc -l`):

| Concern (target sub-package) | Files | Largest file |
| --- | ---: | --- |
| `lifecycle` (store + event-sourcing) | 17 | `FeedbackSessionStoreDelegate.kt` (680) |
| `draft` | 4 | `FeedbackDraftService.kt` (394) |
| `target` | 6 | `TargetEvidenceService.kt` (290) |
| `editsurface` | 7 | `EditSurfaceCandidateService.kt` (156) |
| `handoff` | 8 | `CompactHandoffRenderer.kt` (355) |
| `preview` | 7 | `PreviewCaptureService.kt` (178) |
| `source` | 4 | `HostSourcePathResolver.kt` (119) |
| `connection` | 1 | `ConsoleConnectionService.kt` (241) |
| `dto` | 3 | `SessionDomainMappers.kt` (248) |
| `domain` (already extracted) | 2 | `McpSnapshotRepository.kt` |
| `eventlog` (already extracted) | 4 | `EventLogCompactor.kt` |

## Goals

1. Replace the flat `session/` namespace with cohesive responsibility-named
   sub-packages so that "files that change together live together."
2. Make the grouping **enforced**, not merely documented: an intra-`session`
   import rule and a layout-guard test prevent regression to a flat dump.
3. Pay down the ADR-0002 violation: edit-surface domain logic that has no MCP
   dependency belongs in `compose-core`, not `mcp/session`.
4. Preserve **all** runtime behavior, persisted JSON field names, wire schema,
   and public MCP tool/resource contracts. Zero functional change.
5. Set up the structural boundary that relieves `FeedbackSessionStoreDelegate.kt`
   line-ratchet pressure (680/700): the `lifecycle/{store,event}` split gives the
   eventual physical decomposition a home. The physical file split itself is
   **deferred** to a follow-up plan — it needs its own responsibility analysis and
   characterization tests and must not ride along on a behavior-preserving move
   sweep. The 700 budget is carried unchanged here, not raised (see Acceptance
   Criterion 3).

## Non-Goals

- No change to module-level Gradle structure. Per the multi-module lecture
  guidance (Hong, unit 320770), splitting these small units into separate Gradle
  modules adds DI/build overhead without payoff; **package separation is the
  correct granularity**. The only cross-module move is the `editsurface` lift
  into the existing `compose-core` module (Phase 5).
- No CQRS read/write rename. The store already separates the authoritative
  `session.json` write path from the derived `index.json` read cache; renaming is
  deferred to a possible later spec.
- No behavior, schema, persistence-path, or MCP-contract change.
- No edit to the generated console bundle `resources/console/app.js`.

## Target Package Layout

Apply the hexagonal package-structure principle (Toby, unit 301373): "separate
top-level packages per layer/aggregate, avoid nesting/mixing concerns, keep
dependency direction explicit." Each sub-package is one responsibility
(aggregate). Files keep their class names; only the `package` declaration and
their physical directory change.

```text
mcp/session/
├─ FeedbackSessionService.kt(295)   # application-service facade — the only file at session/ root
├─ lifecycle/                 # Session aggregate: write path, persistence, event sourcing
│   ├─ store/
│   │   FeedbackSessionStore.kt(109) FeedbackSessionStoreDelegate.kt(680)
│   │   FeedbackSessionStoreDraftDeduplication.kt(69) FeedbackSessionPersistence.kt(222)
│   │   FeedbackSessionPaths.kt(38) FeedbackSessionRegistry.kt(83)
│   │   FeedbackSessionSummary.kt(45) SessionStateCache.kt(36) SessionArtifactJanitor.kt(11)
│   └─ event/
│       SessionReducer.kt(61) SessionReplayEngine.kt(306) SessionEventJournal.kt(56)
│       SessionEventPayloads.kt(47) SessionMutation.kt(20) SessionMutationService.kt(116)
│       FeedbackSessionHandoffMutation.kt(65)
│       (existing eventlog/* moves under here: EventLogCheckpoint, EventLogCompactor,
│        EventLogWriter, SessionEvent)
├─ draft/
│   FeedbackDraftService.kt(394) DraftSaveService.kt(73) AnnotationWorkflow.kt(166)
│   EvidenceCoordinator.kt(88)
├─ target/
│   TargetEvidenceService.kt(290) FeedbackTargetValidator.kt(172)
│   TargetBoundaryContextFormatter.kt(114) TargetBoundaryGuidance.kt(40)
│   TargetSummaryFormatter.kt(63) TargetOwnerResolver.kt(49)
├─ editsurface/               # Phase 5 lift candidates -> compose-core
│   EditSurfaceCandidateService.kt(156) EditSurfaceRoleClassifier.kt(86)
│   EditSurfaceConfidencePolicy.kt(84) EditSurfaceEvidence.kt(49)
│   EditIntentAnalyzer.kt(90) EditIntentClassifier.kt(10) EditIntentLexicon.kt(75)
├─ handoff/
│   CompactHandoffRenderer.kt(355) FeedbackQueueFormatter.kt(294)
│   FormatterExtensions.kt(78) HandoffQualitySummary.kt(57) InstanceGroupingHelper.kt(48)
│   AnnotationOverlapDetector.kt(79) DuplicateMarkerDetector.kt(43) FeedbackHandoffModels.kt(22)
├─ preview/
│   PreviewCaptureService.kt(178) PreviewSnapshotCache.kt(45) PreviewFingerprintPolicy.kt(51)
│   PreviewCacheRetentionPolicy.kt(34) PreviewSaveReservationTracker.kt(42)
│   ScreenshotArtifactPromoter.kt(43) ScreenFingerprintMismatch.kt(18)
├─ source/
│   HostSourcePathResolver.kt(119) HostSourceFreshnessProbe.kt(87)
│   SourceCandidateStalenessChecker.kt(93) SourceIndexRegistry.kt(24)
├─ connection/
│   ConsoleConnectionService.kt(241)
├─ dto/
│   SessionDtoModels.kt(206) SessionDomainMappers.kt(248) FeedbackNavigationModels.kt(65)
└─ domain/                    # already extracted, unchanged
    McpSessionRepository.kt McpAnnotationRepository.kt McpSnapshotRepository.kt
```

Notes:

- Package names use the existing convention
  `io.github.beyondwin.fixthis.mcp.session.<group>`.
- `lifecycle/` is the only group deep enough to warrant a second nesting level
  (`store/`, `event/`). All others are single-level. This matches Toby's "avoid
  unnecessary nesting" guidance — nest only where the aggregate is large.

## Intra-`session` Dependency Rules (to be enforced)

After the moves, add these rules to a new `SessionPackageBoundaryTest` (or extend
`ModuleBoundaryTest`). Direction points from presentation/adapter toward the
session core; the lifecycle store is a sink, not a source.

| Package | May depend on | May NOT depend on |
| --- | --- | --- |
| `handoff` | `dto`, `target`, core models | `lifecycle.store`, `preview`, `connection` |
| `preview` | `dto`, core models | `lifecycle.store`, `handoff`, `target` |
| `target` | `dto`, core (`compose-core.target/source/model`) | `lifecycle.store`, `handoff`, `preview`, `connection` |
| `editsurface` | core only | everything in `mcp.*` (proves it is liftable) |
| `source` | core models | `lifecycle.store`, `handoff`, `preview`, `target` |
| `lifecycle.event` | `dto` | `handoff`, `preview`, `connection` |
| `lifecycle.store` | `lifecycle.event`, `dto`, `preview` | `handoff`, `connection`, `target` |
| `draft` | `lifecycle.*`, `target`, `dto` | `connection` |
| `connection` | (bridge/tools) | `handoff`, `preview`, `target` |

The rules are an enforceable encoding of the same idea the lecture states
("dependency direction must be explicit; layers must not mix"). The exact
forbidden-import regexes are spelled out in the implementation plan, Phase 4.
Any rule that turns out to contradict a real, justified dependency is recorded
and relaxed in ADR-0008 rather than worked around silently.

## Edit-Surface Lift Into `compose-core` (ADR-0002 debt)

Probed coupling (2026-06-06):

| File | Non-stdlib imports | Verdict |
| --- | --- | --- |
| `EditIntentLexicon.kt` | none | Pure data/policy → lift confirmed |
| `EditSurfaceCandidateService.kt` | `compose-core.identity`, `compose-core.model` only | Strong lift candidate |
| `EditSurfaceRoleClassifier.kt` | (audit in Phase 5) | Likely lift |
| `EditSurfaceConfidencePolicy.kt` | (audit in Phase 5) | Likely lift |
| `EditSurfaceEvidence.kt` | (audit in Phase 5) | Likely lift |
| `EditIntentAnalyzer.kt` | (audit in Phase 5) | Likely lift |
| `EditIntentClassifier.kt` | (audit in Phase 5) | Likely lift |
| `TargetEvidenceService.kt` | core (many) **+ `mcp.McpProtocol`, `mcp.console.FeedbackTargetType`, `mcp.tools.FixThisBridge`** | Legit adapter — **stays in mcp** |

ADR-0002 says domain models and domain rules live in `compose-core`; outer
modules only adapt. Edit-surface intent classification and confidence policy are
pure domain rules that happen to sit in `mcp/session`. The fix is to move the
MCP-independent subset to `compose-core/editsurface/` and leave any MCP-coupled
coordinator (e.g. anything touching `FixThisBridge`/console DTOs) behind in
`mcp/session/editsurface/` as a thin adapter.

This phase is the only one that crosses a module boundary and the only one that
is a genuine architecture change rather than a move. It is sequenced **last** so
the intra-`session` boundary is already firm before code leaves the module.

## ADR-0008

Add `docs/architecture/adr/0008-session-package-decomposition.md` recording: the
responsibility-named sub-package layout, the intra-`session` dependency rules,
the decision to use packages (not Gradle modules) for the split, and the
edit-surface lift into `compose-core`. This satisfies Toby's "commit the
guideline to git so reviews and automated checks can reference it" point and
keeps the new structure from eroding.

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| `ArchitectureHotspotBudgetTest` path keys break on move | Update the path key in the same commit as each file move; Phase tasks make this explicit. |
| Import churn across the repo (tests, tools, console routes import these classes) | After each move, run a repo-wide import rewrite for the moved FQNs and run the full module test matrix before committing. |
| `docs/architecture/overview.md` references old paths | Update overview.md path references in a docs step per phase. |
| A "pure" editsurface file turns out MCP-coupled | Phase 5 starts with a per-file import audit; only verified-clean files lift, the rest stay as adapters. |
| Cyclic dependency surfaced between new packages | The dependency-rule table is the contract; if a real cycle exists, record it and adjust the rule in ADR-0008 instead of forcing the move. |
| Behavior regression from an accidental edit during a move | Moves are `git mv` + package-line + imports only; no logic edits. Full suite + detekt + spotless gate every commit. |

## Acceptance Criteria

1. `mcp/session/` root contains **only** the `FeedbackSessionService` facade (and
   sub-package directories); all other former root files live in a
   responsibility sub-package.
2. The full local check matrix passes unchanged:
   `:fixthis-compose-core:test`, `:fixthis-cli:test`, `:fixthis-mcp:test`,
   `:fixthis-compose-sidekick:testDebugUnitTest`,
   `:fixthis-gradle-plugin:test`, `detekt`, `spotlessCheck`, console JS checks,
   and `git diff --check`.
3. `ArchitectureHotspotBudgetTest` passes with updated path keys; no budget is
   raised to accommodate a move (a split may lower budgets).
4. A new boundary test enforces the intra-`session` dependency-rule table and
   the "root is facade-only" layout guard; both fail if a file is added back to
   the flat root or violates a rule.
5. `compose-core` still has zero `android`/`androidx`/MCP/CLI/sidekick imports
   after the editsurface lift, and `ModuleBoundaryTest` proves the lifted
   `editsurface` package imports nothing from `mcp.*`.
6. No persisted JSON field name, wire schema, `BridgeProtocol.VERSION`, or MCP
   tool/resource name changes. Persisted-session round-trip tests are unchanged
   and green.
7. ADR-0008 is committed and linked from `docs/architecture/adr/README.md`.

## Out-of-Scope Follow-ups (separate specs)

- CQRS naming (`...WriteStore` / `...ReadProjection`).
- Migrating `ModuleBoundaryTest`/`SessionPackageBoundaryTest` from hand-rolled
  regex to a library (Konsist) — viable once the rule set stabilizes.
- Splitting `FeedbackDraftService.kt` (394) further if it keeps growing.
