# FixThis `mcp/session` Package Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat 57-file `fixthis-mcp/.../mcp/session/` package with cohesive responsibility-named sub-packages and enforce the grouping with an intra-`session` boundary test — all without changing runtime behavior, persisted JSON, wire schema, or MCP contract.

**Architecture:** Behavior-preserving package moves (`git mv` + `package` declaration + import fixes only), guarded at every commit by the existing full test matrix plus detekt/spotless. Order runs low-coupling groups first and the lifecycle store/event aggregate last inside the module. A layout-guard test and a dependency-rule test lock in the result. The earlier proposed `compose-core` edit-surface lift is deferred because actual code review found same-package MCP DTO references that make a direct move unsafe.

**Tech Stack:** Kotlin, kotlin.test (JUnit), Gradle (`:fixthis-mcp` plus final repo checks), detekt, spotless. No new dependencies.

Design spec: [`../specs/2026-06-06-session-package-decomposition-detailed-spec.md`](../specs/2026-06-06-session-package-decomposition-detailed-spec.md)

---

## Conventions used in this plan

Path prefixes (referenced by every task):

```bash
MAIN=fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp
SESS=$MAIN/session
TEST=fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp
ARCH=$TEST/architecture
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
git diff --check
```

Expected: BUILD SUCCESSFUL, no test failures (behavior preserved), no whitespace
errors. `spotlessApply` fixes import ordering introduced by step 3; `spotlessCheck`
confirms the formatter left the tree compliant.

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
because module granularity here adds DI/build overhead without payoff. The
edit-surface core-domain lift is deferred because the current implementation
depends on MCP DTOs from `SessionDtoModels.kt`.

## Consequences

- New `session` code must declare a sub-package; the layout guard fails a flat
  root dump.
- Cross-group imports are constrained by an explicit rule table.
- `ArchitectureHotspotBudgetTest` path keys track the new locations.
- The edit-surface package is isolated enough for a later ADR-0002 cleanup, but
  this ADR does not move MCP DTOs into `compose-core`.

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

## Task 2: Layout-guard test (deferred-green until Task 15)

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageLayoutTest.kt`

- [ ] **Step 1: Write the layout guard, `@Ignore`-d**

It encodes the full target layout (filename → expected sub-package) and lists any
file still at the `session` root that is not the facade. It is `@Ignore`-d so the
suite stays green between phases; Task 15 removes `@Ignore` and it must pass.

```kotlin
package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

@Ignore // Enabled in Task 15 once all moves are complete.
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
git diff --check
git add -A && git commit -m "refactor(mcp): move target evidence into session/target"
```

## Task 8: Move `editsurface/` (within mcp)

**Files (move into `$SESS/editsurface/`):**
`EditSurfaceCandidateService.kt EditSurfaceRoleClassifier.kt EditSurfaceConfidencePolicy.kt EditSurfaceEvidence.kt EditIntentAnalyzer.kt EditIntentClassifier.kt EditIntentLexicon.kt`

This is an intra-module move only. Do not lift these files to `compose-core` in
this plan: actual code review shows direct references to `AnnotationDto`,
`SnapshotDto`, `EditSurfaceCandidateDto`, `EditSurfaceRoleDto`,
`EditSurfaceKindDto`, `EditSurfaceReasonDto`, and `AnnotationTargetDto` from
`SessionDtoModels.kt`.

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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
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
./gradlew :fixthis-mcp:detekt spotlessApply spotlessCheck -q
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

- [ ] **Step 1: Write the first boundary rule**

Add a single rule and confirm the test mechanism works by asserting
`editsurface` does not reach into lifecycle store, handoff rendering, preview, or
connection. Do **not** assert that `editsurface` imports nothing from `mcp.*`:
the actual files are still MCP DTO-coupled and stay in `mcp/session/editsurface`
for this plan.

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
    fun editsurfaceDoesNotImportStoreHandoffPreviewOrConnection() {
        val bad = offenders(
            "editsurface",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(lifecycle\.store|handoff|preview|connection)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew :fixthis-mcp:test --no-daemon -q --tests '*SessionPackageBoundaryTest'`
Expected: PASS. If it fails, inspect the named dependency and either move the
shared helper to `dto`/`target` or record a narrow exception in ADR-0008.

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

## Phase 5 — Close out

## Task 14: Record edit-surface lift follow-up

**Files:**
- Modify: `docs/architecture/overview.md`

- [ ] **Step 1: Capture the concrete blocker**

```bash
for f in $SESS/editsurface/*.kt; do
  echo "== $f =="
  grep -nE "AnnotationDto|SnapshotDto|EditSurface.*Dto|AnnotationTargetDto|TargetOwnerResolver" "$f" || true
done
```

Expected: this prints the DTO/domain-model references that block a direct
`compose-core` move. Keep this output in the Task 14 commit message or PR notes.

- [ ] **Step 2: Update architecture overview**

In `docs/architecture/overview.md`, update the `:fixthis-mcp` bullet list so the
file references reflect the new sub-packages. For edit-surface, point to
`session/editsurface/*` and add one sentence: "The edit-surface classification
logic remains MCP-side in this pass because it consumes persisted session DTOs;
lifting it to `compose-core` requires a separate model/mapping design."

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/overview.md
git commit -m "docs(architecture): record session package decomposition follow-up"
```

## Task 15: Enable the layout guard

**Files:**
- Modify: `ARCH/SessionPackageLayoutTest.kt` (remove `@Ignore`)

- [ ] **Step 1: Enable the layout guard**

Delete the `@Ignore` line (and its import) from `SessionPackageLayoutTest.kt`.

- [ ] **Step 2: Run it**

Run: `./gradlew :fixthis-mcp:test --no-daemon -q --tests '*SessionPackageLayoutTest'`
Expected: PASS — only `FeedbackSessionService.kt` remains at `session/` root.

- [ ] **Step 3: Commit**

```bash
git add $ARCH/SessionPackageLayoutTest.kt
git commit -m "test(mcp): enable session layout guard"
```

## Task 16: Final full-matrix verification

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
node scripts/check-doc-consistency.mjs
git diff --check
graphify update .
```

Expected: BUILD SUCCESSFUL across all modules; doc consistency is clean;
`ModuleBoundaryTest`,
`SessionPackageBoundaryTest`, `SessionPackageLayoutTest`, and
`ArchitectureHotspotBudgetTest` all green; no spotless or whitespace failures.
`graphify update .` may change ignored `graphify-out/` files; do not commit
them.

- [ ] **Step 2: Confirm zero contract drift**

```bash
git diff main --stat        # only moves + test/doc edits; no DTO field-name churn
grep -rn "BridgeProtocol.VERSION" fixthis-mcp fixthis-cli fixthis-compose-sidekick   # unchanged
```

Expected: the diff is moves plus the guard/budget/test edits and docs; no
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
- **Edit-surface core lift:** introduce core edit-surface domain models and
  DTO/domain mappers before moving any edit-surface classifier/policy code into
  `fixthis-compose-core`.
- **Konsist migration** of the hand-rolled boundary tests, once the rule set is
  stable.
