# Spec — Annotation & Session Residual Risk Closure

Status: Proposed.
Scope: `:fixthis-mcp` (`session/` package), `:fixthis-compose-core`
(`usecase/feedback`, audit only).
Related plan: [`../plans/annotation-session-residual-risks.md`](../plans/annotation-session-residual-risks.md)

## Background

A review of the annotation and session save / delete / edit paths produced four
fixes that have since landed locally:

- **P1** — agent resolve/claim now route through the event-backed store
  (`store.updateItemStatus` / `store.claimFeedback`) so status survives an MCP
  restart replay, replacing a domain use-case path that mutated DTO state
  without appending a `SessionEvent`.
- **P2** — `replaceSessionForDomain` no longer hijacks the current-session
  pointer on every domain save.
- **P2** — `FeedbackSessionPersistence.indexJson` builds the index incrementally
  from the existing `index.json` summaries instead of re-parsing every
  `session.json` on each save.
- **P3** — a failed best-effort event-log compaction no longer marks the session
  as replay-skipped (that signal means "data could not be loaded").

Those fixes are correct but left four residual risks. This spec defines the
contract for closing each. None of them is a data-loss bug today; they are
correctness-of-signal, observability, and maintainability gaps that become more
visible as the MCP server gains concurrency and more clients.

## Goals

- One canonical, machine-stable representation for the `SESSION_CLOSED`
  rejection across every mutation entry point.
- `index.json` explicitly documented and treated as a derived, non-authoritative
  cache, with a deterministic rebuild path and build-time validation so it
  cannot silently diverge from the `session.json` set of truth.
- A throttled, visible signal when event-log compaction fails repeatedly,
  without reusing the skipped-session channel.
- Removal (or explicit re-justification) of the now prod-dead domain
  resolve/claim use-cases and MCP session repository, after a usage audit.

## Non-Goals

- Re-introducing the domain use-case path for resolve/claim (P1 intentionally
  bypasses it; this spec does not reverse that).
- Changing the persisted `session.json` schema or any wire/bridge protocol.
- Adding a public "delete entire session" API (none exists today; out of scope).
- Performance tuning beyond the already-landed incremental index build.

## Items

### R-1 — Unify the `SESSION_CLOSED` rejection message

**Problem.** The agent resolve/claim path previously emitted
`"SESSION_CLOSED: Reopen the session or create a new active session before
changing feedback."` After P1 it flows through
`requireOpenSessionForMutation`, which emits
`"SESSION_CLOSED: Cannot run <type> on a closed feedback session."` The
machine-readable prefix (`SESSION_CLOSED:`) is unchanged, but the human text now
differs by entry point. A client that string-matches the full sentence breaks;
worse, the two messages can drift further over time.

Relatedly, `claimFeedback` now enforces `ITEM_ALREADY_RESOLVED` (claiming a
`RESOLVED`/`WONT_FIX` item throws). This is desirable, but it is an undocumented
behavioral addition versus the old path.

**Contract.**
- The `SESSION_CLOSED:` prefix is the stable, machine-readable contract. The
  human suffix is explicitly *not* part of the contract and must not be
  asserted on by clients.
- All `SESSION_CLOSED` rejections are produced by a single source (a private
  helper / message constant in the delegate), so wording cannot diverge by
  entry point.
- `ITEM_ALREADY_RESOLVED` (prefix-stable) on claim of a resolved item is
  documented as intended behavior in the session-store reference.

**Acceptance.**
- Exactly one literal `"SESSION_CLOSED:"` construction site in
  `:fixthis-mcp` `main/`.
- A test asserts both resolve and claim against a closed session throw a
  `FeedbackSessionException` whose message `startsWith("SESSION_CLOSED:")` — and
  asserts on the prefix only.
- A test asserts claiming a `RESOLVED` item throws `ITEM_ALREADY_RESOLVED:`.
- Reference doc updated.

### R-2 — Treat `index.json` as a derived cache with rebuild + validation

**Problem.** `index.json` is written on every save but read only by the
incremental index builder; `list()` / `loadAll()` always scan `session.json`
directly. After P2, the builder trusts the existing `index.json` for all
non-candidate summaries. If `index.json` is ever corrupted out-of-band, stale,
or hand-edited, the divergence persists until a parse failure forces a full
rescan. There is no command or code path that deterministically rebuilds it from
truth, and nothing validates that an indexed summary still has a backing
`session.json`.

(Note: there is no "delete whole session" API today — sessions are only closed —
so deletion-driven drift cannot occur via the current surface. This item closes
the *general* drift risk, not a specific reachable bug.)

**Contract.**
- `index.json` is documented as a non-authoritative, derived cache of
  `session.json` files. `session.json` is the sole source of truth.
- The incremental builder drops any existing summary whose backing session
  directory / `session.json` no longer exists (cheap `isFile` check, not a
  re-parse), so the index converges toward truth on each save.
- A single `rebuildIndex()` operation exists that regenerates `index.json` from
  a full `loadAll()` scan, usable for recovery.

**Acceptance.**
- A test corrupts/stales `index.json` (e.g., injects a summary for a
  non-existent session), performs a save, and asserts the phantom entry is gone
  while real sessions remain.
- A test calls `rebuildIndex()` after manual `index.json` damage and asserts the
  result equals a fresh full scan.
- The incremental fast path still avoids re-parsing existing `session.json`
  contents (only `isFile` stats are permitted for validation).
- Architecture/reference docs state `index.json` is derived and how to rebuild.

### R-3 — Surface repeated compaction failure (throttled)

**Problem.** After P3 the compaction catch block is silent — no log, no metric.
A persistently failing compaction leaves a valid but unbounded-growth event log
with zero operator-visible signal.

**Contract.**
- Compaction remains best-effort and must never fail or roll back the committed
  mutation.
- A compaction failure emits a `WARN` log with the session id and the cause.
- The log is throttled (e.g., at most once per session per N consecutive
  failures, or time-windowed) so a hot mutation loop cannot spam it.
- The skipped-session channel is *not* used (preserves the P3 fix).

**Acceptance.**
- A test injects a compactor that always throws, drives M mutations, asserts:
  (a) every mutation still commits, (b) `replaySkippedSessions` stays empty,
  (c) the warning is emitted but throttled (count ≪ M, per the chosen policy).
- No behavior change when compaction succeeds (no spurious warnings).

### R-4 — Remove or re-justify the prod-dead resolve/claim domain path

**Problem.** After P1, `ResolveAnnotationUseCase`, `ClaimAnnotationUseCase`, and
`McpSessionRepository` have no production callers (a repo-wide grep finds only
their own definitions and tests). They previously enforced the agent
resolution-status rules; that enforcement now lives in
`SessionMutationService.updateItemStatus` and `FeedbackSessionStoreDelegate
.claimFeedback`. Dead code that *looks* load-bearing invites a future
regression where someone re-wires through it and silently reopens the P1 replay
bug.

**Contract.**
- Run a usage audit first. Only symbols with zero production references AND
  whose sole remaining purpose was the unwired resolve/claim path are removed.
- Anything still referenced by another module (compose-core consumers,
  sidekick, snapshot/annotation repositories) is kept; if kept, a one-line note
  records why it survives the audit.
- Tests that exist only to exercise the removed code are deleted with it; tests
  that assert still-relevant invariants are migrated to the store-backed path.
- The resolution-status allow-list (`RESOLVED`, `NEEDS_CLARIFICATION`,
  `WONT_FIX`) remains enforced at the surviving (store) path with a regression
  test, so removing the use-case does not remove the rule.

**Acceptance.**
- `git grep` shows zero production references to any removed symbol.
- `./gradlew :fixthis-mcp:test :fixthis-compose-core:test` green.
- A test asserts a disallowed agent status (`OPEN`/`READY`/`IN_PROGRESS`) still
  throws `IllegalArgumentException("Agent resolution status is not allowed: …")`
  via `store.updateItemStatus`.
- If any symbol is *kept*, the spec's audit table records the surviving
  reference.

**Audit result (implemented).** Removed: `ResolveAnnotationUseCase`,
`ClaimAnnotationUseCase` (and their `ResolveAnnotationCommand` /
`ClaimAnnotationCommand` models), `McpSessionRepository`, plus the tests that
existed only to cover them. Kept symbols and why:

| Symbol | Survives because |
|--------|------------------|
| `FeedbackSessionStoreDelegate.replaceSessionForDomain` | Domain session-save seam for the `SessionRepository`/domain-mapping layer; not specific to resolve/claim. Now exercised by `McpDomainRepositoryTest` (which validates the P2 current-session-pointer behavior directly against this seam). |
| `McpSnapshotRepository`, `McpAnnotationRepository` | Separate domain-mapping concern (snapshot/annotation), not the resolve/claim path. Out of R-4 scope; left intact. |
| `SessionRepository` interface, `toDomainSession`/`toSessionDto` | Still referenced by the surviving compose-core use-cases and the domain repos/tests. |

The resolution allow-list (`RESOLVED`/`NEEDS_CLARIFICATION`/`WONT_FIX`) that the
removed use-cases enforced is retained on the store path
(`SessionMutationService.updateItemStatus`) with a message-asserting regression
test (`FeedbackSessionStoreTest.updateItemStatusRejectsDisallowedAgentStatusWithMessage`).

## Rollout

Each item is independently mergeable and behind no flag (local-only tool). Order
by risk-to-reward: R-3 (smallest, observability) → R-1 (message unification) →
R-2 (cache contract) → R-4 (deletion, largest blast radius, last). Full Gradle
matrix + `:fixthis-mcp:detekt` + `:fixthis-mcp:spotlessCheck` per item.
