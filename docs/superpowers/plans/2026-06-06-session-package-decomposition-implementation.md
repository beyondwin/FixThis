# FixThis `mcp/session` Package Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat 57-file `fixthis-mcp/.../mcp/session/` package with cohesive responsibility-named sub-packages, enforce the grouping with an intra-`session` boundary test, and lift the MCP-independent edit-surface domain logic into `fixthis-compose-core` — all without changing any runtime behavior, persisted JSON, wire schema, or MCP contract.

**Architecture:** Behavior-preserving package moves (`git mv` + `package` declaration + import fixes only), guarded at every commit by the existing full test matrix plus detekt/spotless. Order runs low-coupling groups first, the lifecycle store/event aggregate last inside the module, and the cross-module `compose-core` lift last of all. A layout-guard test and a dependency-rule test lock in the result.

**Tech Stack:** Kotlin, kotlin.test (JUnit), Gradle (`:fixthis-mcp`, `:fixthis-compose-core`), detekt, spotless. No new dependencies.

Design spec: [`../specs/2026-06-06-session-package-decomposition-detailed-spec.md`](../specs/2026-06-06-session-package-decomposition-detailed-spec.md)

---

## Conventions used in this plan

Path prefixes (referenced by every task):

```bash
MAIN=fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp
SESS=$MAIN/session
TEST=fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp
ARCH=$TEST/architecture
CORE=fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core
```

### Standard Move Procedure (every move task instantiates this)

A Kotlin file move is four mechanical edits. Each task below lists the **exact**
files, package name, and budget/guard edits; this block defines the rote steps so
they are not repeated in prose:

1. **Move:** `git mv $SESS/<File>.kt $SESS/<group>/<File>.kt` for each file.
2. **Package line:** in each moved file change
   `package io.github.beyondwin.fixthis.mcp.session`
   → `package io.github.beyondwin.fixthis.mcp.session.<group>`.
3. **Fix references:** run `./gradlew :fixthis-mcp:compileKotlin -q`; for every
   `Unresolved reference` add the new import
   `import io.github.beyondwin.fixthis.mcp.session.<group>.<MovedSymbol>` to the
   referencing file (production **and** test sources). Repeat until compile is
   clean. Test files stay in their current directory — only their imports change.
4. **Budget keys:** if a moved file appears in
   `ARCH/ArchitectureHotspotBudgetTest.kt`, update its path string to the new
   location in the same commit.

Test files are **not** moved (keeps `ArchitectureHotspotBudgetTest` test-budget
keys valid). Only production files move.

### Per-task verification (the real safety net)

Every task ends by running:

```bash
./gradlew :fixthis-mcp:test --no-daemon -q
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
```

Expected: BUILD SUCCESSFUL, no test failures (behavior preserved), no whitespace
errors. `spotlessApply` fixes import ordering introduced by step 3.

---

## Phase 0 — Guardrails first

## Task 1: ADR-0008 documenting the decomposition

**Files:**
- Create: `docs/architecture/adr/0008-session-package-decomposition.md`
- Modify: `docs/architecture/adr/README.md` (add the index line)

- [ ] **Step 1: Write the ADR**

Create `docs/architecture/adr/0008-session-package-decomposition.md` mirroring the
house ADR format (see `0002-domain-models-live-in-compose-core.md`):

```markdown
# ADR-0008: Session Package Decomposition

- Status: Accepted
- Date: 2026-06-06

## Context

`fixthis-mcp/.../mcp/session/` grew to 57 flat files (6,728 lines) mixing
session persistence/event-sourcing, draft workflow, target evidence,
edit-surface analysis, handoff rendering, preview cache, and host source
freshness. The only structural backstop was the per-file line-count ratchet in
`ArchitectureHotspotBudgetTest`. Module-level boundaries (ADR-0001, ADR-0002)
are healthy and unchanged.

## Decision

Group `session` files into responsibility sub-packages: `lifecycle/{store,event}`,
`draft`, `target`, `editsurface`, `handoff`, `preview`, `source`, `connection`,
`dto` (plus existing `domain`). The `session` root keeps only the
`FeedbackSessionService` facade. Intra-`session` dependency direction is enforced
by `SessionPackageBoundaryTest`. The split uses packages, not Gradle modules,
because module granularity here adds DI/build overhead without payoff. Pure,
MCP-independent edit-surface domain logic is lifted into
`fixthis-compose-core/editsurface/` per ADR-0002.

## Consequences

- New `session` code must declare a sub-package; the layout guard fails a flat
  root dump.
- Cross-group imports are constrained by an explicit rule table.
- `ArchitectureHotspotBudgetTest` path keys track the new locations.
- The edit-surface lift removes a pre-existing ADR-0002 violation.

## Alternatives Considered

- Keep the flat package and rely on line budgets. Rejected: budgets cap size,
  not cohesion, so the flat dump keeps growing.
- Extract sub-packages into separate Gradle modules. Rejected: DI/build overhead
  exceeds the benefit at this size (see multi-module guidance).
```

- [ ] **Step 2: Link it from the ADR index**

In `docs/architecture/adr/README.md`, add a list entry pointing to
`0008-session-package-decomposition.md` next to the existing `0007` entry,
matching the surrounding formatting.

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/adr/0008-session-package-decomposition.md docs/architecture/adr/README.md
git commit -m "docs(adr): ADR-0008 session package decomposition"
```

## Task 2: Layout-guard test (deferred-green until Phase 6)

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageLayoutTest.kt`

- [ ] **Step 1: Write the layout guard, `@Ignore`-d**

It encodes the full target layout (filename → expected sub-package) and lists any
file still at the `session` root that is not the facade. It is `@Ignore`-d so the
suite stays green between phases; Phase 6 removes `@Ignore` and it must pass.

```kotlin
package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

@Ignore // Enabled in Phase 6 once all moves are complete.
class SessionPackageLayoutTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val sessionRoot =
        File(root, "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session")
    private val allowedAtRoot = setOf("FeedbackSessionService.kt")

    @Test
    fun sessionRootHoldsOnlyTheFacade() {
        val strays = sessionRoot.listFiles { f -> f.isFile && f.extension == "kt" }
            .orEmpty()
            .map { it.name }
            .filterNot { it in allowedAtRoot }
            .sorted()
        assertTrue(strays.isEmpty(), "Unexpected files at session/ root:\n${strays.joinToString("\n")}")
    }
}
```

- [ ] **Step 2: Verify it compiles and is ignored**

Run: `./gradlew :fixthis-mcp:test --no-daemon -q --tests '*SessionPackageLayoutTest'`
Expected: PASS (test is `@Ignore`-d, so it does not execute the assertion).

- [ ] **Step 3: Commit**

```bash
git add $ARCH/SessionPackageLayoutTest.kt
git commit -m "test(mcp): add ignored session layout guard for decomposition"
```

---

## Phase 1 — Low-coupling moves

Each task follows the **Standard Move Procedure** and **Per-task verification**.
These four groups have the fewest inbound references, so they move first.

## Task 3: Move `preview/`

**Files (move into `$SESS/preview/`):**
`PreviewCaptureService.kt PreviewSnapshotCache.kt PreviewFingerprintPolicy.kt PreviewCacheRetentionPolicy.kt PreviewSaveReservationTracker.kt ScreenshotArtifactPromoter.kt ScreenFingerprintMismatch.kt`

- [ ] **Step 1: Create dir and move**

```bash
mkdir -p $SESS/preview
git mv $SESS/PreviewCaptureService.kt $SESS/PreviewSnapshotCache.kt \
       $SESS/PreviewFingerprintPolicy.kt $SESS/PreviewCacheRetentionPolicy.kt \
       $SESS/PreviewSaveReservationTracker.kt $SESS/ScreenshotArtifactPromoter.kt \
       $SESS/ScreenFingerprintMismatch.kt $SESS/preview/
```

- [ ] **Step 2: Update package declarations**

In each of the 7 moved files set
`package io.github.beyondwin.fixthis.mcp.session.preview`.

- [ ] **Step 3: Fix references**

Run `./gradlew :fixthis-mcp:compileKotlin -q`; add
`import io.github.beyondwin.fixthis.mcp.session.preview.<Symbol>` for each
unresolved reference (no budgeted file in this group, so no budget edit). Repeat
until clean.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move preview files into session/preview"
```

## Task 4: Move `source/`

**Files (move into `$SESS/source/`):**
`HostSourcePathResolver.kt HostSourceFreshnessProbe.kt SourceCandidateStalenessChecker.kt SourceIndexRegistry.kt`

- [ ] **Step 1: Create dir and move**

```bash
mkdir -p $SESS/source
git mv $SESS/HostSourcePathResolver.kt $SESS/HostSourceFreshnessProbe.kt \
       $SESS/SourceCandidateStalenessChecker.kt $SESS/SourceIndexRegistry.kt $SESS/source/
```

- [ ] **Step 2: Update package declarations** to
`package io.github.beyondwin.fixthis.mcp.session.source` in all 4 files.

- [ ] **Step 3: Fix references** via `compileKotlin`, adding
`import io.github.beyondwin.fixthis.mcp.session.source.<Symbol>`. No budgeted
file in this group.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move host source freshness into session/source"
```

## Task 5: Move `connection/`

**Files (move into `$SESS/connection/`):** `ConsoleConnectionService.kt`

- [ ] **Step 1: Create dir and move**

```bash
mkdir -p $SESS/connection
git mv $SESS/ConsoleConnectionService.kt $SESS/connection/
```

- [ ] **Step 2: Update package declaration** to
`package io.github.beyondwin.fixthis.mcp.session.connection`.

- [ ] **Step 3: Fix references** via `compileKotlin`, adding
`import io.github.beyondwin.fixthis.mcp.session.connection.ConsoleConnectionService`
wherever it resolves unresolved. No budgeted file.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move console connection into session/connection"
```

## Task 6: Move `dto/`

**Files (move into `$SESS/dto/`):**
`SessionDtoModels.kt SessionDomainMappers.kt FeedbackNavigationModels.kt`

- [ ] **Step 1: Create dir and move**

```bash
mkdir -p $SESS/dto
git mv $SESS/SessionDtoModels.kt $SESS/SessionDomainMappers.kt \
       $SESS/FeedbackNavigationModels.kt $SESS/dto/
```

- [ ] **Step 2: Update package declarations** to
`package io.github.beyondwin.fixthis.mcp.session.dto` in all 3 files.

- [ ] **Step 3: Fix references**

`dto` is widely referenced, so expect many unresolved references across
`session`, `tools`, `console`, and tests. Run `compileKotlin`, add
`import io.github.beyondwin.fixthis.mcp.session.dto.<Symbol>` for each. No
budgeted file in this group. Persisted JSON field names live in these DTOs and
**must not change** — only the package moves.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q   # round-trip/persistence tests must stay green
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move session DTOs and mappers into session/dto"
```

---

## Phase 2 — Cohesive sub-domains

## Task 7: Move `target/`

**Files (move into `$SESS/target/`):**
`TargetEvidenceService.kt FeedbackTargetValidator.kt TargetBoundaryContextFormatter.kt TargetBoundaryGuidance.kt TargetSummaryFormatter.kt TargetOwnerResolver.kt`

- [ ] **Step 1: Create dir and move**

```bash
mkdir -p $SESS/target
git mv $SESS/TargetEvidenceService.kt $SESS/FeedbackTargetValidator.kt \
       $SESS/TargetBoundaryContextFormatter.kt $SESS/TargetBoundaryGuidance.kt \
       $SESS/TargetSummaryFormatter.kt $SESS/TargetOwnerResolver.kt $SESS/target/
```

- [ ] **Step 2: Update package declarations** to
`package io.github.beyondwin.fixthis.mcp.session.target` in all 6 files.

- [ ] **Step 3: Fix references** via `compileKotlin`, adding
`import io.github.beyondwin.fixthis.mcp.session.target.<Symbol>`.

- [ ] **Step 4: Update the budget key**

In `ARCH/ArchitectureHotspotBudgetTest.kt`, change
`"${mcpMain}session/TargetEvidenceService.kt" to 320` to
`"${mcpMain}session/target/TargetEvidenceService.kt" to 320`.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q   # ArchitectureHotspotBudgetTest must pass
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move target evidence into session/target"
```

## Task 8: Move `editsurface/` (within mcp)

**Files (move into `$SESS/editsurface/`):**
`EditSurfaceCandidateService.kt EditSurfaceRoleClassifier.kt EditSurfaceConfidencePolicy.kt EditSurfaceEvidence.kt EditIntentAnalyzer.kt EditIntentClassifier.kt EditIntentLexicon.kt`

This is the intra-module move only; the cross-module lift to `compose-core` is
Phase 5.

- [ ] **Step 1: Create dir and move**

```bash
mkdir -p $SESS/editsurface
git mv $SESS/EditSurfaceCandidateService.kt $SESS/EditSurfaceRoleClassifier.kt \
       $SESS/EditSurfaceConfidencePolicy.kt $SESS/EditSurfaceEvidence.kt \
       $SESS/EditIntentAnalyzer.kt $SESS/EditIntentClassifier.kt \
       $SESS/EditIntentLexicon.kt $SESS/editsurface/
```

- [ ] **Step 2: Update package declarations** to
`package io.github.beyondwin.fixthis.mcp.session.editsurface` in all 7 files.

- [ ] **Step 3: Fix references** via `compileKotlin`, adding
`import io.github.beyondwin.fixthis.mcp.session.editsurface.<Symbol>`. No
budgeted production file in this group. (The per-role confidence work in the
2026-06-06 calibration plan references `EditSurfaceEvidence`/`EditSurfaceConfidencePolicy`
tests — their imports update here.)

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move edit-surface analysis into session/editsurface"
```

## Task 9: Move `handoff/`

**Files (move into `$SESS/handoff/`):**
`CompactHandoffRenderer.kt FeedbackQueueFormatter.kt FormatterExtensions.kt HandoffQualitySummary.kt InstanceGroupingHelper.kt AnnotationOverlapDetector.kt DuplicateMarkerDetector.kt FeedbackHandoffModels.kt`

- [ ] **Step 1: Create dir and move**

```bash
mkdir -p $SESS/handoff
git mv $SESS/CompactHandoffRenderer.kt $SESS/FeedbackQueueFormatter.kt \
       $SESS/FormatterExtensions.kt $SESS/HandoffQualitySummary.kt \
       $SESS/InstanceGroupingHelper.kt $SESS/AnnotationOverlapDetector.kt \
       $SESS/DuplicateMarkerDetector.kt $SESS/FeedbackHandoffModels.kt $SESS/handoff/
```

- [ ] **Step 2: Update package declarations** to
`package io.github.beyondwin.fixthis.mcp.session.handoff` in all 8 files.

- [ ] **Step 3: Fix references** via `compileKotlin`, adding
`import io.github.beyondwin.fixthis.mcp.session.handoff.<Symbol>`.

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q   # CompactHandoffRendererTest (test budget unchanged) must pass
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move handoff rendering into session/handoff"
```

## Task 10: Move `draft/`

**Files (move into `$SESS/draft/`):**
`FeedbackDraftService.kt DraftSaveService.kt AnnotationWorkflow.kt EvidenceCoordinator.kt`

- [ ] **Step 1: Create dir and move**

```bash
mkdir -p $SESS/draft
git mv $SESS/FeedbackDraftService.kt $SESS/DraftSaveService.kt \
       $SESS/AnnotationWorkflow.kt $SESS/EvidenceCoordinator.kt $SESS/draft/
```

- [ ] **Step 2: Update package declarations** to
`package io.github.beyondwin.fixthis.mcp.session.draft` in all 4 files.

- [ ] **Step 3: Fix references** via `compileKotlin`, adding
`import io.github.beyondwin.fixthis.mcp.session.draft.<Symbol>`.

- [ ] **Step 4: Update the budget key**

In `ARCH/ArchitectureHotspotBudgetTest.kt`, change
`"${mcpMain}session/FeedbackDraftService.kt" to 430` to
`"${mcpMain}session/draft/FeedbackDraftService.kt" to 430`.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move draft workflow into session/draft"
```

---

## Phase 3 — Lifecycle aggregate (store + event)

## Task 11: Move `lifecycle/store/`

**Files (move into `$SESS/lifecycle/store/`):**
`FeedbackSessionStore.kt FeedbackSessionStoreDelegate.kt FeedbackSessionStoreDraftDeduplication.kt FeedbackSessionPersistence.kt FeedbackSessionPaths.kt FeedbackSessionRegistry.kt FeedbackSessionSummary.kt SessionStateCache.kt SessionArtifactJanitor.kt`

- [ ] **Step 1: Create dir and move**

```bash
mkdir -p $SESS/lifecycle/store
git mv $SESS/FeedbackSessionStore.kt $SESS/FeedbackSessionStoreDelegate.kt \
       $SESS/FeedbackSessionStoreDraftDeduplication.kt $SESS/FeedbackSessionPersistence.kt \
       $SESS/FeedbackSessionPaths.kt $SESS/FeedbackSessionRegistry.kt \
       $SESS/FeedbackSessionSummary.kt $SESS/SessionStateCache.kt \
       $SESS/SessionArtifactJanitor.kt $SESS/lifecycle/store/
```

- [ ] **Step 2: Update package declarations** to
`package io.github.beyondwin.fixthis.mcp.session.lifecycle.store` in all 9 files.

- [ ] **Step 3: Fix references** via `compileKotlin`, adding
`import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.<Symbol>`.

- [ ] **Step 4: Update budget keys**

In `ARCH/ArchitectureHotspotBudgetTest.kt`:
- `"${mcpMain}session/FeedbackSessionStoreDelegate.kt" to 700`
  → `"${mcpMain}session/lifecycle/store/FeedbackSessionStoreDelegate.kt" to 700`
- In `remediationBudgets`: `"${mcpMain}session/FeedbackSessionStore.kt" to 250`
  → `"${mcpMain}session/lifecycle/store/FeedbackSessionStore.kt" to 250`

- [ ] **Step 5: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q   # FeedbackSessionStoreTest (test budget unchanged) must pass
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move session store into session/lifecycle/store"
```

## Task 12: Move `lifecycle/event/` (+ existing `eventlog/`)

**Files (move into `$SESS/lifecycle/event/`):**
`SessionReducer.kt SessionReplayEngine.kt SessionEventJournal.kt SessionEventPayloads.kt SessionMutation.kt SessionMutationService.kt FeedbackSessionHandoffMutation.kt`
plus the existing `eventlog/` package moves under `lifecycle/event/eventlog/`:
`EventLogCheckpoint.kt EventLogCompactor.kt EventLogWriter.kt SessionEvent.kt`

- [ ] **Step 1: Create dirs and move**

```bash
mkdir -p $SESS/lifecycle/event
git mv $SESS/SessionReducer.kt $SESS/SessionReplayEngine.kt $SESS/SessionEventJournal.kt \
       $SESS/SessionEventPayloads.kt $SESS/SessionMutation.kt $SESS/SessionMutationService.kt \
       $SESS/FeedbackSessionHandoffMutation.kt $SESS/lifecycle/event/
git mv $SESS/eventlog $SESS/lifecycle/event/eventlog
```

- [ ] **Step 2: Update package declarations**

- 7 top-level files → `package io.github.beyondwin.fixthis.mcp.session.lifecycle.event`
- 4 eventlog files → `package io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog`

- [ ] **Step 3: Fix references** via `compileKotlin`, adding the matching
`import io.github.beyondwin.fixthis.mcp.session.lifecycle.event[.eventlog].<Symbol>`.
References from `lifecycle/store` to these symbols are expected and allowed.

- [ ] **Step 4: Update budget key**

In `ARCH/ArchitectureHotspotBudgetTest.kt`, change
`"${mcpMain}session/SessionReplayEngine.kt" to 340` to
`"${mcpMain}session/lifecycle/event/SessionReplayEngine.kt" to 340`.

- [ ] **Step 5: Verify and commit**

```bash
./gradlew :fixthis-mcp:test --no-daemon -q   # event-log replay/compaction tests must pass
./gradlew :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(mcp): move event sourcing into session/lifecycle/event"
```

At this point `session/` root holds only `FeedbackSessionService.kt` plus the
sub-package directories.

---

## Phase 4 — Enforce intra-`session` boundaries

## Task 13: `SessionPackageBoundaryTest` (the dependency-rule table)

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt`

- [ ] **Step 1: Write a failing rule first**

Add a single rule and confirm the test mechanism works by asserting `editsurface`
imports nothing from `mcp.*` (this is also the precondition for Phase 5).

```kotlin
package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SessionPackageBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val session = "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session"

    private fun offenders(group: String, forbidden: Regex): List<String> =
        File(root, "$session/$group").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    if (forbidden.containsMatchIn(line)) "${f.relativeTo(root)}:${i + 1}: $line" else null
                }
            }.toList()

    @Test
    fun editsurfaceImportsNothingFromMcp() {
        val bad = offenders("editsurface", Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\."""))
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :fixthis-mcp:test --no-daemon -q --tests '*SessionPackageBoundaryTest'`
Expected: PASS if Task 8 left `editsurface` MCP-clean; if it FAILS, the named
imports are the exact files Phase 5 must keep behind as adapters — record them.

- [ ] **Step 3: Add the remaining rules from the spec table**

Append the remaining rows of the spec's dependency-rule table — one `@Test` each.
The complete set (every spec row except `editsurface`, which Step 1 already
covers) is the eight methods below; e.g. `handoff` must not import
`lifecycle.store`, `preview`, `connection`:

```kotlin
    @Test
    fun handoffDoesNotImportStorePreviewOrConnection() {
        val bad = offenders(
            "handoff",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(lifecycle\.store|preview|connection)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun previewDoesNotImportStoreHandoffOrTarget() {
        val bad = offenders(
            "preview",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(lifecycle\.store|handoff|target)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun targetDoesNotImportStoreHandoffPreviewOrConnection() {
        val bad = offenders(
            "target",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(lifecycle\.store|handoff|preview|connection)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun sourceDoesNotImportStoreHandoffPreviewOrTarget() {
        val bad = offenders(
            "source",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(lifecycle\.store|handoff|preview|target)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun lifecycleEventDoesNotImportHandoffPreviewOrConnection() {
        val bad = offenders(
            "lifecycle/event",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(handoff|preview|connection)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun lifecycleStoreDoesNotImportHandoffConnectionOrTarget() {
        // store MAY depend on lifecycle.event, dto, preview (spec table); those are not forbidden here.
        val bad = offenders(
            "lifecycle/store",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(handoff|connection|target)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun draftDoesNotImportConnection() {
        val bad = offenders(
            "draft",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.connection\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun connectionDoesNotImportHandoffPreviewOrTarget() {
        val bad = offenders(
            "connection",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(handoff|preview|target)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
```

- [ ] **Step 4: Run; reconcile any real violation**

Run: `./gradlew :fixthis-mcp:test --no-daemon -q --tests '*SessionPackageBoundaryTest'`
Expected: PASS. If a rule fails on a genuine, justified dependency, do **not**
weaken the move — relax that one rule and record the exception in ADR-0008
(amend the file, append a `## Exceptions` note).

- [ ] **Step 5: Commit**

```bash
git add $ARCH/SessionPackageBoundaryTest.kt
git commit -m "test(mcp): enforce intra-session package dependency direction"
```

---

## Phase 5 — Lift edit-surface domain into `compose-core` (ADR-0002 debt)

## Task 14: Per-file coupling audit

**Files:** (read-only analysis; output recorded in the commit message of Task 15)

- [ ] **Step 1: Classify each editsurface file**

```bash
for f in $SESS/editsurface/*.kt; do
  echo "== $f =="
  grep '^import' "$f" | grep -v '^import kotlin' | grep -v '^import kotlinx'
done
```

- [ ] **Step 2: Partition**

A file is a **lift** candidate iff its only non-stdlib imports are
`io.github.beyondwin.fixthis.compose.core.*` (or none). Any file importing
`mcp.*` (e.g. `FixThisBridge`, `McpProtocol`, console DTOs) is an **adapter** and
stays in `mcp/session/editsurface/`. Confirmed liftable from the spec probe:
`EditIntentLexicon.kt`, `EditSurfaceCandidateService.kt`. Record the full
partition before moving.

## Task 15: Move liftable files to `compose-core/editsurface`

**Files:**
- Create: `$CORE/editsurface/` (one file per liftable class from Task 14)
- Modify: `ARCH/ModuleBoundaryTest.kt` (add a guard for the new core package)
- Modify: any `mcp` caller of a lifted symbol (import path changes module)

- [ ] **Step 1: Move the verified-pure files**

For each liftable file (example shown for the two confirmed ones; extend with the
Task 14 partition):

```bash
mkdir -p $CORE/editsurface
git mv $SESS/editsurface/EditIntentLexicon.kt $CORE/editsurface/
git mv $SESS/editsurface/EditSurfaceCandidateService.kt $CORE/editsurface/
```

- [ ] **Step 2: Update package declarations**

Set `package io.github.beyondwin.fixthis.compose.core.editsurface` in each moved
file.

- [ ] **Step 3: Fix references across modules**

```bash
./gradlew :fixthis-compose-core:compileKotlin :fixthis-mcp:compileKotlin -q
```

For every unresolved reference in `mcp`, replace the old import
`io.github.beyondwin.fixthis.mcp.session.editsurface.<Symbol>` with
`io.github.beyondwin.fixthis.compose.core.editsurface.<Symbol>`. Repeat until
both modules compile.

- [ ] **Step 4: Move the matching unit tests to `compose-core`**

Pure domain tests follow their code. `git mv` each lifted class's test from
`fixthis-mcp/src/test/.../session/<Name>Test.kt` to
`fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/editsurface/`,
update its `package` and imports. (If a test also exercises MCP wiring, split the
pure assertions into the core test and leave the MCP assertions in the mcp test.)

- [ ] **Step 5: Update budget-test keys if a lifted file was budgeted**

None of the confirmed-liftable files are in `ArchitectureHotspotBudgetTest`;
verify against the Task 14 partition and update any path key that did move into
`compose-core`.

- [ ] **Step 6: Add a core boundary guard**

In `ARCH/ModuleBoundaryTest.kt`, add a test asserting the new core package stays
pure (it is already covered by `composeCoreDoesNotImportOuterModulesOrAndroid`,
which walks all of `fixthis-compose-core/src/main`; add a focused assertion so
failures point at editsurface directly):

```kotlin
    @Test
    fun composeCoreEditSurfaceImportsNoOuterModule() {
        val forbidden = Regex(
            """^import (android|androidx|io\.github\.beyondwin\.fixthis\.(mcp|cli|gradle|compose\.sidekick))""",
        )
        val offenders = kotlinFiles(
            "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/editsurface",
        ).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
            }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }
```

- [ ] **Step 7: Full verify and commit**

```bash
./gradlew :fixthis-compose-core:test :fixthis-mcp:test --no-daemon -q
./gradlew :fixthis-compose-core:detekt :fixthis-mcp:detekt spotlessApply -q
git diff --check
git add -A && git commit -m "refactor(core): lift MCP-independent edit-surface domain into compose-core"
```

---

## Phase 6 — Close out

## Task 16: Enable the layout guard and refresh docs

**Files:**
- Modify: `ARCH/SessionPackageLayoutTest.kt` (remove `@Ignore`)
- Modify: `docs/architecture/overview.md` (path references under `:fixthis-mcp`)

- [ ] **Step 1: Enable the layout guard**

Delete the `@Ignore` line (and its import) from `SessionPackageLayoutTest.kt`.

- [ ] **Step 2: Run it**

Run: `./gradlew :fixthis-mcp:test --no-daemon -q --tests '*SessionPackageLayoutTest'`
Expected: PASS — only `FeedbackSessionService.kt` remains at `session/` root.

- [ ] **Step 3: Update `overview.md`**

In `docs/architecture/overview.md`, update the `:fixthis-mcp` bullet list so the
file references reflect the new sub-packages (e.g.
`session/FeedbackSessionStore.kt` → `session/lifecycle/store/FeedbackSessionStore.kt`,
`session/EditSurfaceCandidateService.kt` → `compose-core/editsurface/...`). Add a
one-line note that `session` is now decomposed per ADR-0008.

- [ ] **Step 4: Commit**

```bash
git add $ARCH/SessionPackageLayoutTest.kt docs/architecture/overview.md
git commit -m "test(mcp): enable session layout guard; docs: refresh module map"
```

## Task 17: Final full-matrix verification

**Files:** none (verification only)

- [ ] **Step 1: Run the complete required check matrix**

```bash
./gradlew \
  :fixthis-compose-core:test \
  :fixthis-cli:test \
  :fixthis-mcp:test \
  :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test \
  --no-daemon
./gradlew detekt spotlessCheck --no-daemon
git diff --check
```

Expected: BUILD SUCCESSFUL across all modules; `ModuleBoundaryTest`,
`SessionPackageBoundaryTest`, `SessionPackageLayoutTest`, and
`ArchitectureHotspotBudgetTest` all green; no spotless or whitespace failures.

- [ ] **Step 2: Confirm zero contract drift**

```bash
git diff main --stat        # only moves + test/doc edits; no DTO field-name churn
grep -rn "BridgeProtocol.VERSION" fixthis-mcp fixthis-cli fixthis-compose-sidekick   # unchanged
```

Expected: the diff is moves plus the four guard/budget/test edits and docs; no
changes to persisted JSON field names, MCP tool/resource names, or
`BridgeProtocol.VERSION`.

- [ ] **Step 3: Final commit (if any doc/cleanup remains)**

```bash
git add -A && git commit -m "chore(mcp): finalize session decomposition verification"
```

---

## Deferred (separate plans, not in scope here)

- **Split `FeedbackSessionStoreDelegate.kt` (680/700):** the `lifecycle/{store,event}`
  package boundary sets this up, but the physical file split needs its own
  responsibility analysis and characterization tests — do it as a follow-up so it
  is not bundled into this behavior-preserving move sweep.
- **CQRS rename** (`...WriteStore` / `...ReadProjection`).
- **Konsist migration** of the hand-rolled boundary tests, once the rule set is
  stable.
