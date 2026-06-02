# Plan — Annotation & Session Residual Risk Closure

Status: Proposed.
Spec: [`../specs/annotation-session-residual-risks.md`](../specs/annotation-session-residual-risks.md)

Each task is independently mergeable and ordered by risk-to-reward: Task 0 is
the smallest and most observable; Task 3 has the largest blast radius and is
last. Every task is TDD — write the failing test first, then implement.

Common verification appended to every task:

```bash
./gradlew :fixthis-mcp:test :fixthis-mcp:detekt :fixthis-mcp:spotlessCheck
git diff --check
```

## Task 0: R-3 — Throttled warning on compaction failure

**Files:**
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`
  (`compactEventLogAfterMutation`)
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt`

**Steps**
1. RED: extend `FeedbackSessionStoreEventLogTest` with a test that injects an
   `EventLogCompactionTask { error("compaction boom") }`, threshold 0, drives
   M = 5 mutations, and asserts: every mutation committed, `replaySkippedSessions`
   stays empty, and a warning sink received **fewer than M** entries (throttle).
   To observe the warning, route the WARN through an injectable sink — add an
   optional constructor param `compactionFailureSink: (sessionId, cause) -> Unit`
   defaulting to a throttled logger; the test passes a counting sink.
2. Implement a throttle keyed by `sessionId`: track consecutive-failure count
   and emit only on the 1st failure and then every Nth (e.g., N = 50), or
   time-window (≥ 60 s since last emit). Reset the counter on the next
   successful compaction.
3. Keep the existing P3 behavior: do **not** write `replaySkippedSessions`; never
   rethrow.
4. Default sink: `WARN` log line `"event-log compaction failed for session
   {sessionId}: {cause}"` via the module's existing logging facility (match
   whatever `FeedbackSessionStoreDelegate` siblings use; if none, use the
   project logger pattern already present in `:fixthis-mcp`).

#### Acceptance Criteria
```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackSessionStoreEventLogTest"
# counting sink in the test observes < 5 emissions for 5 failing mutations
```

## Task 1: R-1 — Single-source the `SESSION_CLOSED` message

**Files:**
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`
  (`requireOpenSessionForMutation` + any other `SESSION_CLOSED` literal)
- `docs/reference/` session-store reference (the doc describing store error codes;
  create a short section if absent)
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/ResolveClaimReplayTest.kt`
  or a new `SessionMutationGuardTest.kt`

**Steps**
1. RED: add a test asserting that `resolveFeedback` and `claimFeedback` against a
   **closed** session each throw `FeedbackSessionException` whose message
   `startsWith("SESSION_CLOSED:")`. Assert on the prefix only (codifies the
   contract that the suffix is not stable).
2. `git grep -n '"SESSION_CLOSED' fixthis-mcp/src/main` → inventory every
   construction site.
3. Collapse them to one private `companion`/top-level helper, e.g.
   `private fun sessionClosed(type: String) = FeedbackSessionException(
   "SESSION_CLOSED: Cannot run $type on a closed feedback session.")`. All
   entry points call it.
4. Add a test asserting claiming a `RESOLVED` item throws a message
   `startsWith("ITEM_ALREADY_RESOLVED:")` (documents the P1-era behavioral add).
5. Document the error-code prefixes (`SESSION_CLOSED:`, `ITEM_ALREADY_RESOLVED:`,
   `SESSION_NOT_FOUND:`, …) as the stable contract in the reference doc, with an
   explicit "suffix text is not contractual" note.

#### Acceptance Criteria
```bash
./gradlew :fixthis-mcp:test --tests "*SessionMutationGuard*" --tests "*ResolveClaimReplay*"
# exactly one SESSION_CLOSED construction site:
test "$(git grep -c '\"SESSION_CLOSED:' fixthis-mcp/src/main | awk -F: '{s+=$2} END{print s}')" = "1"
```

## Task 2: R-2 — `index.json` as a validated, rebuildable derived cache

**Files:**
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionPersistence.kt`
  (`indexJson`, new `rebuildIndex`)
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionPersistenceTest.kt`
- `docs/architecture/overview.md` or the persistence reference (state `index.json`
  is derived)

**Steps**
1. RED (validation): write a test that seeds `index.json` with a summary for a
   session whose directory does not exist, then calls `save(realSession)` and
   asserts the resulting index contains the real session(s) but **not** the
   phantom one.
2. In `indexJson`, when reusing existing summaries, filter to those whose backing
   `paths.sessionFile(it.sessionId)` `isFile`. Use only `isFile` stats — do not
   re-parse `session.json` (preserves the P2 performance win).
3. RED (rebuild): write a test that damages `index.json`, calls a new public
   `rebuildIndex()`, and asserts the written index equals a fresh
   `loadAll()`-derived, `updatedAt`-sorted summary list.
4. Implement `rebuildIndex()`: full `loadAll()` scan → summaries → atomic write
   through the same `replaceFile` path used by `save`.
5. Document `index.json` as a non-authoritative cache rebuilt from `session.json`,
   and reference `rebuildIndex()` as the recovery path.

#### Acceptance Criteria
```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackSessionPersistenceTest"
# fast path must not re-parse session.json during incremental build:
#   asserted via the phantom-entry test (no FeedbackSessionException on a
#   directory that has no readable session.json)
```

## Task 3: R-4 — Remove the prod-dead resolve/claim domain path

**Files (pending audit):**
- `fixthis-compose-core/.../usecase/feedback/ResolveAnnotationUseCase.kt`
- `fixthis-compose-core/.../usecase/feedback/ClaimAnnotationUseCase.kt`
- `fixthis-mcp/.../session/domain/McpSessionRepository.kt`
- Associated tests:
  `fixthis-mcp/.../session/domain/McpDomainRepositoryTest.kt` and any
  `:fixthis-compose-core` use-case tests

**Steps**
1. Audit:
   ```bash
   git grep -nE "ResolveAnnotationUseCase|ClaimAnnotationUseCase|McpSessionRepository" -- '*.kt'
   ```
   Build a table of every reference and classify production vs test. Confirm zero
   production references (current finding). Check whether
   `McpSnapshotRepository` / `McpAnnotationRepository` depend on
   `McpSessionRepository`; if so, that keeps it alive — record and stop at that
   boundary.
2. RED-by-retention: before deleting, ensure a store-level test asserts the
   resolution allow-list is still enforced —
   `store.updateItemStatus(session, item, OPEN, …)` throws
   `IllegalArgumentException("Agent resolution status is not allowed: OPEN")`.
   Add it if missing (this is the rule the use-case used to own).
3. Delete only the symbols with zero production references and whose sole purpose
   was the unwired path. Remove tests that existed only to cover them; migrate
   any still-relevant assertions to the store path.
4. For anything kept (still referenced elsewhere), add a one-line comment / spec
   audit-table note explaining the surviving reference.
5. Re-run the full matrix across both modules.

#### Acceptance Criteria
```bash
./gradlew :fixthis-mcp:test :fixthis-compose-core:test
# zero production refs to any removed symbol:
git grep -nE "ResolveAnnotationUseCase|ClaimAnnotationUseCase" -- '*/src/main/*' | wc -l | grep -qE '^[[:space:]]*0[[:space:]]*$'
```

## Sequencing notes

- Tasks 0–2 are non-destructive and can land in any order; the spec's
  recommended order (0 → 1 → 2 → 3) front-loads observability and defers the
  only deletion to last.
- Task 3 must not start until Task 1's store-path guard test exists, so the
  resolution allow-list is provably enforced before the use-case is removed.
