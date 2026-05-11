# Plan — Code Hardening

Status: Draft
Spec: [`../specs/code-hardening.md`](../specs/code-hardening.md)

Each task is independently mergeable. Order is by risk-to-reward: Task 0 is
the smallest and most observable; Task 3 is the largest and last.

## Tasks

### Task 0: CH-2 — `Thread.sleep` → `delay`

**Files:**
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/RunCommand.kt`
- `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/RunCommandTest.kt` (new or extend)

**Steps**
1. Replace `Thread.sleep(500)` inside `waitForStatus` with
   `delay(backoff.next())` where `backoff` is a small inline helper
   (`200 → 400 → 800 → 1500`, capped).
2. Pass the retry deadline (`deadline - now()`) into `delay` so cancellation
   propagates; if remaining time is less than the next backoff, sleep only
   the remaining time.
3. Confirm `waitForStatus` is called from a coroutine scope; if any non-suspend
   caller remains, wrap with `runBlocking` at the boundary and document why.
4. Add unit test asserting that cancelling the outer scope while `waitForStatus`
   is mid-backoff returns within 50 ms.

#### Acceptance Criteria
```bash
./gradlew :fixthis-cli:test
test -z "$(git grep -nE 'Thread\.sleep' fixthis-cli/src/main)"
```

### Task 1: CH-1 — Eliminate `!!` in `:fixthis-mcp`

**Files:**
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/InstanceGroupingHelper.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt` (single site at line 101: `fallbackScreen!!`)

**Steps**
1. `git grep -n '!!' fixthis-mcp/src/main` → produce the inventory.
2. For each hit:
   - If the value can be null along any code path → switch to
     `?: return` / `?: error("contract: ...")` with the contract spelled out.
   - If non-nullness comes from a discriminator type → introduce a sealed
     hierarchy (`sealed interface ResolvedTarget { object Bounds; data class
     Node(val node: SemanticsSnapshot)... }`) and switch the dereference to a
     `when`.
3. Keep a single annotated `!!` only where the surrounding code already
   pattern-matched the variant in the same statement (e.g., immediately after
   a `require(target is Node)`).

#### Acceptance Criteria
```bash
./gradlew :fixthis-mcp:test
# Any remaining !! must be on the same line as a require/check that proves non-null;
# count of unannotated !! occurrences must be zero
git grep -nE '!!' fixthis-mcp/src/main | grep -vE '(require|check|//\s*ok:)' | wc -l | grep -qE '^\s*0\s*$'
```

### Task 2: CH-3 — `InFlightRegistry` in `McpServer`

**Files:**
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/McpServer.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/InFlightRegistry.kt` (new)
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/InFlightRegistryTest.kt` (new)

**Steps**
1. Add `InFlightRegistry` with a private `Mutex` and the API:
   `suspend fun register(key, request)`, `suspend fun remove(key)`,
   `suspend fun consumeAll(): List<InFlightRequest>`,
   `suspend fun cancel(key, reason): InFlightRequest?`.
2. In `McpServer`, replace every `synchronized(inFlight)` block with the
   corresponding suspend call. Call `cancelAndJoin` outside the critical
   section.
3. Move the existing `cancelInFlightRequests` body into
   `registry.consumeAll().forEach { it.job.cancelAndJoin() }`.
4. Add a new unit test fanning out 32 cancellable requests on a test
   dispatcher, cancelling every other one via `notifications/cancelled`, and
   asserting `registry.size() == 0` after `cancelInFlightRequests` and no
   `Job` is left active.

#### Acceptance Criteria
```bash
./gradlew :fixthis-mcp:test
test -z "$(git grep -nE 'synchronized\(inFlight' fixthis-mcp/src/main)"
```

### Task 3: CH-4 — Split `FeedbackSessionService`

**Files:**
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionRegistry.kt` (new)
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationRepository.kt` (new)
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EvidenceCoordinator.kt` (new)
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionRegistryTest.kt` (new)
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationRepositoryTest.kt` (new)
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EvidenceCoordinatorTest.kt` (new)

**Steps**
1. Extract method clusters in three commits:
   - lifecycle (`open*`, `close*`, `list*`, persistence I/O)
     → `FeedbackSessionRegistry`;
   - CRUD on annotations (`add*`, `update*`, `delete*`, `claim*`, `resolve*`)
     → `AnnotationRepository`;
   - evidence binding (`attachScreenshot*`, `attachEvidence*`,
     `recomputeTargetEvidence*`) → `EvidenceCoordinator`.
2. Each extracted class takes its collaborators as constructor args (no
   service-locator lookups).
3. `FeedbackSessionService` either disappears or remains as a thin façade
   (≤ 60 lines) for backwards-compatible call sites.
4. New focused unit tests per class — each ≤ 200 lines, no HTTP fixtures.

#### Acceptance Criteria
```bash
./gradlew :fixthis-mcp:test
# Each new class file is under 200 lines
for f in fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionRegistry.kt \
         fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationRepository.kt \
         fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EvidenceCoordinator.kt; do
  test -f "$f" && [ "$(wc -l < "$f")" -le 200 ]
done
```

## Rollout

- One PR per task (Task 0 → Task 3 in that order).
- No flags; each PR is a pure refactor with behaviour-preserving tests.
- Squash-merge; CHANGELOG entry under "Internal / refactor".
