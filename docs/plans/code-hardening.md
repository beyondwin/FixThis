# Plan — Code Hardening

Status: Draft
Spec: [`../specs/code-hardening.md`](../specs/code-hardening.md)

Each item is independently mergeable. Order is by risk-to-reward: CH-2 is the
smallest and most observable; CH-4 is the largest and last.

## CH-2 — `Thread.sleep` → `delay`

**Files**
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/RunCommand.kt`

**Steps**
1. Replace `Thread.sleep(500)` inside `waitForStatus` with
   `delay(backoff.next())` where `backoff` is a small inline helper
   (`200 → 400 → 800 → 1500`, capped).
2. Pass the retry deadline (`deadline - now()`) into `delay` so cancellation
   propagates; if remaining time is less than the next backoff, sleep only
   the remaining time.
3. Confirm `waitForStatus` is called from a coroutine scope; if any non-suspend
   caller remains, wrap with `runBlocking` at the boundary and document why.

**Validation**
- `./gradlew :fixthis-cli:test`
- New unit test in `fixthis-cli/src/test/.../RunCommandTest.kt` that asserts
  cancelling the outer scope while `waitForStatus` is mid-backoff returns
  within 50 ms.
- `git grep -nE 'Thread\.sleep' fixthis-cli/src/main` → empty.

## CH-1 — Eliminate `!!` in `:fixthis-mcp`

**Files**
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/InstanceGroupingHelper.kt`

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

**Validation**
- `./gradlew :fixthis-mcp:test`
- `git grep -nE '\\!\\!' fixthis-mcp/src/main` returns only annotated
  exceptions.

## CH-3 — `InFlightRegistry` in `McpServer`

**Files**
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/McpServer.kt`
- New: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/InFlightRegistry.kt`
- New: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/InFlightRegistryTest.kt`

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

**Validation**
- New unit test: fan out 32 cancellable requests on a test dispatcher, cancel
  every other one via `notifications/cancelled`, assert
  `registry.size() == 0` after `cancelInFlightRequests` and no `Job` is left
  active.
- Existing `:fixthis-mcp:test` suite must remain green.

## CH-4 — Split `FeedbackSessionService`

**Files**
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- New: `…/session/FeedbackSessionRegistry.kt`
- New: `…/session/AnnotationRepository.kt`
- New: `…/session/EvidenceCoordinator.kt`

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

**Validation**
- All existing tests pass without modification (callers go through the façade
  or are updated in-commit).
- New focused unit tests per class — each ≤ 200 lines, no HTTP fixtures.

## Rollout

- One PR per item (CH-2, CH-1, CH-3, CH-4 in that order).
- No flags; each PR is a pure refactor with behaviour-preserving tests.
- Squash-merge; CHANGELOG entry under "Internal / refactor".
