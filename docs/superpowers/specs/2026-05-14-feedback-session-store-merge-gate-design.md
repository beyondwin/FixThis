# FeedbackSessionStore — Post-Merge Audit & Followup Tracker (SOLID Remediation Tasks 4–5)

**Status:** Merged on 2026-05-13 (merge commit `a6abe8a`); this doc tracks post-merge verification and followups.

> **Scope.** This is a **post-merge audit + follow-up registry**, not a fresh
> specification. The SOLID Remediation plan
> ([`../plans/2026-05-13-architecture-solid-remediation-implementation.md`](../plans/2026-05-13-architecture-solid-remediation-implementation.md))
> covering the `FeedbackSessionStore` responsibility split landed on `main`
> via merge commit `a6abe8a` (2026-05-13). This document records what
> shipped, what risks remain, and the verification followups before declaring
> the `FeedbackSessionStore` portion of that initiative fully closed.

## 1. Current Plan Reference

Source plan:
[`2026-05-13-architecture-solid-remediation-implementation.md`](../plans/2026-05-13-architecture-solid-remediation-implementation.md)
(1,692 lines, 11 tasks).

Source spec:
[`2026-05-13-architecture-solid-remediation-detailed-spec.md`](2026-05-13-architecture-solid-remediation-detailed-spec.md)
(622 lines), specifically Finding F2 ("`FeedbackSessionStore` owns too many
persistence and domain concerns", lines 142–179) and Phase 2 of the
Prioritized Remediation Roadmap (lines 462–478).

### Task-to-merge mapping

| Plan task                                              | Commit(s) on main                                 | Files of record                                                       |
|--------------------------------------------------------|---------------------------------------------------|-----------------------------------------------------------------------|
| Task 2 — rename `AnnotationRepository` → `AnnotationWorkflow` | `93c26af refactor(mcp): rename annotation workflow facade` | `fixthis-mcp/.../session/AnnotationWorkflow.kt`                       |
| Task 3 — MCP adapters for core domain ports            | `00b0320 refactor(mcp): adapt feedback store to core domain ports` | `fixthis-mcp/.../session/domain/Mcp{Session,Snapshot,Annotation}Repository.kt` |
| Task 4 — pure `SessionReducer` + `SessionMutation`     | `a1278dc refactor(mcp): introduce pure session reducer` | `fixthis-mcp/.../session/{SessionMutation,SessionReducer}.kt`        |
| Task 5 — `SessionEventJournal` + `SessionReplayEngine` | `6d54545 refactor(mcp): split session event replay from store` | `fixthis-mcp/.../session/{SessionEventJournal,SessionReplayEngine}.kt` |
| Task 1 — architecture guardrails                       | `c7bdbca test: add architecture boundary guardrails` then tightened by `1756e27 test: tighten architecture hotspot budgets` | `fixthis-mcp/.../architecture/{ModuleBoundaryTest,ArchitectureHotspotBudgetTest}.kt` |
| Task 11 — final docs & verification                    | `ffd50de docs: update architecture after solid remediation`, `a6abe8a` merge | `docs/architecture/overview.md`                                       |

All commits land in main via the merge commit `a6abe8a` from
`codex/architecture-solid-remediation`.

## 2. Completion Audit

### Task 2 — `AnnotationWorkflow` rename
- **Status:** COMPLETE.
- **Evidence:**
  - File `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationWorkflow.kt` exists.
  - The pre-rename file `AnnotationRepository.kt` no longer exists.
  - `FeedbackSessionService` constructs `AnnotationWorkflow(store = ..., draftService = ...)`.
- **Caveat:** None.

### Task 3 — MCP domain repository adapters
- **Status:** COMPLETE.
- **Evidence:**
  - Three adapter classes exist under `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/domain/`:
    `McpSessionRepository.kt`, `McpSnapshotRepository.kt`, `McpAnnotationRepository.kt`.
  - `FeedbackSessionStore` exposes the new public methods
    `replaceSessionForDomain`, `addOrReplaceScreenForDomain`,
    `addOrReplaceAnnotationForDomain` (visible at lines 112–145 of the
    current 748-line file).
- **Caveat:** The adapters are constructed at the test layer in
  `McpDomainRepositoryTest`. Production wiring (DI / composition root)
  does not yet route session reads through `SessionRepository`. The
  adapters are dormant until a caller picks them up.

### Task 4 — `SessionReducer` + `SessionMutation`
- **Status:** PARTIALLY ADOPTED.
- **Evidence:**
  - `SessionMutation.kt` and `SessionReducer.kt` exist.
  - `SessionReducerTest.kt` is present and runs in `:fixthis-mcp:test`.
  - `FeedbackSessionStore.closeSession()` (line 169 of current file) uses
    `SessionReducer.reduce(session, SessionMutation.Close(now))`.
  - `FeedbackSessionStore.markReadyForAgent()` similarly delegates.
- **Caveat:** The remaining `SessionMutation` variants
  (`AddScreen`, `AddScreenWithItems`, `ReplaceItems`, `DeleteScreen`,
  `DeleteItem`, `AddHandoff`) are *defined* but the corresponding store
  paths (`addScreen` at line 183, `addScreenWithItems` at line 208,
  etc.) still build `session.copy(...)` inline rather than delegating
  through the reducer. The plan's Task 4 Step 3 explicitly limited the
  adoption to two "low-risk" methods. Broader adoption is a follow-up.

### Task 5 — Event journal and replay engine split
- **Status:** COMPLETE.
- **Evidence:**
  - `SessionEventJournal.kt` and `SessionReplayEngine.kt` exist.
  - `FeedbackSessionStore` constructs them (lines 47–53 of the current
    file) and routes the boot replay loop through
    `replayEngine.replay(...)`.
  - Tests `FeedbackSessionStoreEventLogTest`, `SigkillReplayTest`, and
    `eventlog.EventLogCompactorTest` are still in the source tree and
    run green per the SOLID merge.
- **Caveat:** `FeedbackSessionStore.kt` is now 748 lines (down from the
  993-line baseline) but still carries `@Suppress("LargeClass")` at the
  class declaration (line 35). The suppression hints at further
  extraction work, but the file is below the hotspot budget (`1_050`
  from `ArchitectureHotspotBudgetTest`).

### Task 1 — guardrails
- **Status:** COMPLETE and tightened.
- **Evidence:**
  - `ModuleBoundaryTest.kt` and `ArchitectureHotspotBudgetTest.kt`
    exist under `fixthis-mcp/src/test/kotlin/.../architecture/`.
  - The `FeedbackSessionStore.kt` budget in the hotspot test is `1_050`
    (room remains; current size is 748).

### Task 11 — documentation
- **Status:** COMPLETE.
- **Evidence:** `docs/architecture/overview.md` mentions the new
  session-side files (commit `ffd50de`). `docs: sync post-v0.1.0
  documentation` (`3135f88`, current `main` HEAD) sweeps related docs.

## 3. Additional Risks Discovered

Risks the auditor found that the original plan did not surface:

### R1. The reducer is not the single source of mutation truth.
Only `closeSession` and `markReadyForAgent` route through `SessionReducer`.
Every other public mutation (`addScreen`, `addScreenWithItems`,
`replaceItems`, `deleteScreen`, `deleteItem`, `addOrReplaceScreenForDomain`,
`addOrReplaceAnnotationForDomain`, and the handoff path) constructs the
new `SessionDto` inline. This means:

- **Drift risk.** A future change to "how an `addScreen` mutation looks"
  has to be applied in two places — once in the inline copy and once if
  someone later wires the reducer. The event-log replay code (which now
  lives in `SessionReplayEngine`) does *not* go through the reducer for
  these events either, so the divergence is latent.
- **Test gap.** `SessionReducerTest` proves the reducer's algebra is
  correct, but no integration test asserts that the store's inline
  `session.copy(...)` is **equivalent** to the reducer's behaviour for
  the same input. The plan declared this acceptable for the slice but
  did not list it as a follow-up.

### R2. `@Suppress("LargeClass")` is still on the store.
The annotation at line 35 says:

```kotlin
// LargeClass suppressed: split into smaller responsibilities once the
// event-log API stabilises — see #ALH-followup
@Suppress("LargeClass")
class FeedbackSessionStore(...)
```

The "ALH-followup" reference points to the Annotation Lifecycle Hardening
plan, which has merged. The suppression is now stale-context — either:

- The store should be split further (the obvious candidates being
  `SessionPersistenceCoordinator` for the `save`/`load` orchestration
  and `SessionMutationApplier` for the public mutation API), and the
  suppression removed; or
- The suppression should be re-annotated with a current rationale
  ("Hotspot budget allows up to 1,050 lines; current size 748 is below
  the threshold and warrants no further split").

Leaving the stale comment in place is a low-grade signal of unfinished
business.

### R3. Domain adapters are dormant.
`McpSessionRepository`, `McpSnapshotRepository`, and
`McpAnnotationRepository` exist but are never injected into the
production composition root. They are only constructed by
`McpDomainRepositoryTest`. This was explicitly acceptable per the plan
("Phase 1 — Domain ports become active runtime dependencies" was scoped
to *add adapters*, not *adopt them*), but the merge gate should record:

- A follow-up plan is required to retire the direct `FeedbackSessionStore`
  call sites in `FixThisToolDispatcher` / `FeedbackSessionService` in
  favour of the new ports.
- Until that follow-up lands, the `:fixthis-compose-core` domain ports
  remain decorative for production code.

### R4. `nextItemSequenceNumber` migration is store-internal.
The new public methods `replaceSessionForDomain` /
`addOrReplaceAnnotationForDomain` call `withMigratedItemSequenceCounter()`
to backfill `nextItemSequenceNumber` for pre-migration session JSON
(commits `86071ea fix(session): persist monotonic annotation sequence
counter`). This migration is **not** invoked from `SessionReducer`. If
the reducer is later adopted for `addOrReplaceAnnotationForDomain`, the
migration must move with it or be applied at a higher layer. The risk is
that a future "use the reducer everywhere" refactor silently drops the
migration for replayed sessions.

### R5. Event-log append still happens inside the store's mutex.
`appendEventThenMutate` (called from every spec'd mutation) runs the
write-ahead `EventLogWriter.append(...)` while holding `synchronized
(lock)`. On a slow filesystem (Android emulator with throttled storage)
this can serialise concurrent reads. The SOLID plan did not address this
because it was a structural refactor, not a performance one. Recorded
here so the merge gate doesn't paper over it.

## 4. Post-Merge Verification Checklist

Pre-merge verification for the *original* SOLID work has already been
satisfied (the merge has happened). The checklist below is the **post-merge
followup tracker for declaring the FeedbackSessionStore portion of the SOLID
initiative "fully closed"** — i.e., the point at which we can take down
the `@Suppress("LargeClass")` and stop treating this as in-flight work.

### Invariant Re-Verification

Grep-able commands that must remain green for this slice not to regress structural invariants:

- [x] `./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"` — Bridge protocol 4-site sync. Evidence: SOLID remediation reshapes session internals only; bridge protocol pin sites are untouched.
- [x] `./gradlew :fixthis-mcp:test --tests "*ModuleBoundaryTest"` — module boundary invariants. Evidence: domain adapters live under `fixthis-mcp/.../session/domain/` and depend on `:fixthis-compose-core` ports as designed; `ModuleBoundaryTest` was tightened in `1756e27` and currently passes.
- [x] `./gradlew :fixthis-mcp:test --tests "*ArchitectureHotspotBudgetTest"` — hotspot budgets. Evidence: `FeedbackSessionStore.kt` budget is `1_050`; current size is 748 lines.
- [ ] Persisted JSON schema compatibility — `SessionDto`, `ScreenDto`, `AnnotationDto`, and `SessionEvent*` JSON shapes must continue to round-trip historical session files. Evidence pending: no dedicated schema fixture test exists in the suite today; the migration helper `withMigratedItemSequenceCounter()` covers one field but not the broader schema. Manual verification today is "load a session JSON from before merge `a6abe8a`, save it again, diff."
  - Owner: TBD
  - Rollback if regressed: revert the SOLID merge `a6abe8a` (last resort);
    more narrowly, restore the previous shape of `FeedbackSessionStore.save*`
    paths by reverting `a1278dc` and `6d54545`.

### Code-state gates

- [x] `AnnotationWorkflow.kt` exists; `AnnotationRepository.kt` does not.
- [x] `SessionMutation.kt` and `SessionReducer.kt` exist.
- [x] `SessionEventJournal.kt` and `SessionReplayEngine.kt` exist.
- [x] `McpSessionRepository`, `McpSnapshotRepository`,
      `McpAnnotationRepository` exist.
- [x] `FeedbackSessionStore.kt` is below 1,050 lines.
- [ ] `FeedbackSessionStore.kt` is below 800 lines **and**
      `@Suppress("LargeClass")` is removed *or* re-annotated with a
      current rationale (see R2).
  - Owner: TBD
  - Rollback if regressed: revert the split commit (TODO sha) and restore
    the prior `@Suppress("LargeClass")` rationale comment.
- [ ] At least one production call site (composition root in
      `:fixthis-mcp` main) uses the `McpSessionRepository` /
      `SnapshotRepository` / `AnnotationRepository` ports rather than
      calling the store directly (R3).
  - Owner: TBD
  - Rollback if regressed: revert the wiring commit (TODO sha); the
    dormant adapters remain in tree and production keeps calling
    `FeedbackSessionStore` directly.

### Test gates

- [x] `SessionReducerTest` passes (`./gradlew :fixthis-mcp:test --tests
      "io.beyondwin.fixthis.mcp.session.SessionReducerTest"`).
- [x] `McpDomainRepositoryTest` passes.
- [x] `FeedbackSessionStoreEventLogTest`, `SigkillReplayTest`, and
      `EventLogCompactorTest` pass.
- [x] `ModuleBoundaryTest` and `ArchitectureHotspotBudgetTest` pass.
- [ ] A new equivalence test
      (`SessionReducerEquivalenceTest` or similar) asserts that for
      each public mutation method on `FeedbackSessionStore`, applying
      the corresponding `SessionMutation` through `SessionReducer`
      yields a `SessionDto` with the same `screens`, `items`,
      `handoffBatches`, `status`, and `updatedAtEpochMillis` as the
      store's inline implementation (R1).
  - Owner: TBD
  - Rollback if regressed: delete the new test file; no production
    behavior depends on it.
- [ ] Replay round-trip: feed a known sequence of events to a fresh
      store, capture the resulting `SessionDto`, then feed the same
      events through `SessionReducer` directly; assert equality. This
      proves replay and reducer agree even though they don't share a
      code path today.
  - Owner: TBD
  - Rollback if regressed: delete the new test file; no production
    behavior depends on it.

### Documentation gates

- [x] `docs/architecture/overview.md` references the new files.
- [ ] An ADR or follow-up note records the residual `@Suppress
      ("LargeClass")` decision (R2) — either "removed" or
      "keeping; here's the budget".
  - Owner: TBD
  - Rollback if regressed: delete the ADR or note; no code change to revert.
- [ ] The next-step plan for adopting `McpSessionRepository` in
      production exists (filename, even if the body is `TBD`).
  - Owner: TBD
  - Rollback if regressed: delete the plan file; the dormant adapters
    remain undisturbed.

### Verification commands

Run before flipping the unchecked boxes above:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.*"
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.architecture.*"
./gradlew detekt --no-daemon
git diff --check
```

Each command must complete without error before the unchecked items are
marked done.

## 5. Outstanding Followups

Items deferred past the SOLID merge, sequenced by dependency.

### Outstanding Followups Checklist

- [ ] Domain adapters (`McpSessionRepository`, `McpSnapshotRepository`,
      `McpAnnotationRepository`) are dormant in production — only test code
      constructs them today (R3 / Followup-B). Until at least one
      production caller routes through the ports, the
      `:fixthis-compose-core` domain interfaces are decorative for the
      shipped binary.
  - Owner: TBD
  - Rollback if regressed: when production wiring lands, if it breaks
    behaviour, revert the wiring commit (TODO sha) and keep the adapters
    dormant.

### Followup-A. Adopt the reducer in all store mutations (R1)

Goal: every `synchronized(lock) { ... }` block in
`FeedbackSessionStore` that mutates `SessionDto.screens`,
`SessionDto.items`, `SessionDto.handoffBatches`, or
`SessionDto.status` is replaced by a single call to
`SessionReducer.reduce(...)`.

Sketch:
- Add `SessionMutation.AddOrReplaceScreen`, `AddOrReplaceAnnotation`
  variants (currently they exist only as the domain-adapter paths).
- Refactor `addScreen`, `addScreenWithItems`, `replaceItems`,
  `deleteScreen`, `deleteItem`, `addHandoffBatch`,
  `addOrReplaceScreenForDomain`, `addOrReplaceAnnotationForDomain` to
  build a `SessionMutation` and call `reducer.reduce(...)`.
- Migrate `withMigratedItemSequenceCounter` into the reducer path or
  into a dedicated `SessionMigrations` step that runs before
  `reducer.reduce` (R4).

Acceptance: `SessionReducerEquivalenceTest` passes; line count of
`FeedbackSessionStore.kt` drops below 600.

### Followup-B. Production wiring for the domain adapters (R3)

Goal: `FixThisToolDispatcher` and `FeedbackSessionService` accept
`SessionRepository` / `SnapshotRepository` / `AnnotationRepository`
as constructor parameters instead of an `FeedbackSessionStore`.

Sketch:
- `FixThisTools` constructs the three adapters at startup and passes
  them down.
- The MCP HTTP routes that currently call store methods directly are
  routed through the repository ports.
- `FeedbackSessionStore` remains internal to the wiring and is no
  longer part of the dispatcher's API surface.

Acceptance: a `ProductionWiringTest` asserts that
`FixThisToolDispatcher` can be constructed against in-memory
`SessionRepository` fakes (no `FeedbackSessionStore` needed).

### Followup-C. Remove or re-annotate `@Suppress("LargeClass")` (R2)

Trigger: after Followup-A lands. If the store drops below 600 lines,
remove the annotation. Otherwise, replace its comment with a current
rationale referencing the hotspot budget and any open splits.

### Followup-D. Move event-log append off the store mutex (R5)

Goal: `EventLogWriter.append` runs outside `synchronized(lock)` so
slow-disk environments don't serialise reads.

Sketch: serialise the *commit* (memory + persistence) under the
mutex, but stage the event-log write on a single-thread dispatcher
keyed by `sessionId`. The journal becomes append-only; readers
already tolerate "event happened, persistence hasn't caught up" by
re-reading from the event log on boot.

This is a behavioural change (failure semantics shift slightly) so
it needs its own spec + plan; not in scope for this merge gate.

### Followup-E. Reducer-driven replay (R4)

Once Followup-A is in, route `SessionReplayEngine.replay(...)`
through the same `SessionReducer.reduce(...)` calls instead of its
current per-event `apply*` helpers. This collapses two divergent
mutation paths into one. Listed as the final follow-up because it
depends on the reducer being complete.

## References

- Merged plan: [`2026-05-13-architecture-solid-remediation-implementation.md`](../plans/2026-05-13-architecture-solid-remediation-implementation.md)
- Merged spec: [`2026-05-13-architecture-solid-remediation-detailed-spec.md`](2026-05-13-architecture-solid-remediation-detailed-spec.md)
- Current store: [`fixthis-mcp/.../session/FeedbackSessionStore.kt`](../../../fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt) (748 lines)
- Reducer: [`fixthis-mcp/.../session/SessionReducer.kt`](../../../fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionReducer.kt)
- Journal: [`fixthis-mcp/.../session/SessionEventJournal.kt`](../../../fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionEventJournal.kt)
- Replay engine: [`fixthis-mcp/.../session/SessionReplayEngine.kt`](../../../fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionReplayEngine.kt)
- Domain adapters: [`fixthis-mcp/.../session/domain/`](../../../fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/domain/)
- Merge commit: `a6abe8a Merge branch 'codex/architecture-solid-remediation'`
