# Spec — Code Hardening

Status: Draft
Scope: `:fixthis-cli`, `:fixthis-mcp`
Related plan: [`../plans/code-hardening.md`](../plans/code-hardening.md)

## Background

Several runtime hazards exist in the long-running JVM processes (`:fixthis-cli`,
`:fixthis-mcp`). They are tolerable today because the tool is local-only and
single-user, but they are easy correctness pitfalls (NPE, deadlock, thread
starvation) and will become more visible as concurrency in the MCP server
grows.

## Goals

- Remove non-null assertion (`!!`) uses on values that can legitimately be null
  along happy paths.
- Replace blocking primitives (`Thread.sleep`) inside coroutines with
  cancellation-aware suspending equivalents.
- Pick one synchronization model per shared state and use it consistently;
  do not mix `synchronized` blocks with `Mutex` on the same data.
- Split the largest session-layer class so each unit is independently testable.

## Non-Goals

- Rewriting the protocol or session model.
- Refactoring any module beyond `:fixthis-mcp` and `:fixthis-cli`.
- Performance tuning.

## Items

### CH-1 — Replace `!!` on validated session targets

**Sites:** `fixthis-mcp/.../session/TargetEvidenceService.kt:118`,
`fixthis-mcp/.../session/InstanceGroupingHelper.kt` (similar pattern).

The validation step that establishes "type == NODE" lives apart from the
dereference. A reviewer cannot prove non-null locally.

**Contract:** any access to a target sub-field is gated by a sealed
discrimination (`when (target) { is NodeTarget -> ... }`) or by smart-casting
through an explicit local `val node = target.selectedNode ?: return` early
return. `!!` is only acceptable when the compiler cannot see an invariant the
type system already enforces, with a comment.

**Acceptance:** zero `!!` operators in `:fixthis-mcp` `main/` sources except
where annotated with a single-line justification.

### CH-2 — Replace `Thread.sleep` in coroutines with `delay`

**Site:** `fixthis-cli/.../commands/RunCommand.kt:58` — `waitForStatus` is a
`suspend fun` but calls `Thread.sleep(500)` inside its retry loop.

**Contract:** every suspend function and every block under `runBlocking`,
`launch`, or `async` uses `delay(...)` instead of `Thread.sleep(...)`. Retries
back off (e.g., 200 ms → 400 ms → 800 ms, capped at 2 s) and respect the
caller's deadline.

**Acceptance:**
1. `git grep -nE 'Thread\.sleep\b' fixthis-cli fixthis-mcp` returns zero hits
   in `main/` (test sources may keep it).
2. Cancelling the parent coroutine while `waitForStatus` is mid-retry returns
   within one tick (≤ 50 ms), not up to 500 ms.

### CH-3 — One lock per state in `McpServer`

**Site:** `fixthis-mcp/.../McpServer.kt` lines 64, 70, 96, 102 — the
`inFlight: MutableMap<String, InFlightRequest>` is guarded by raw
`synchronized` blocks, while the protocol layer uses a `Mutex`. Mixing the two
on adjacent state makes reasoning about cancellation ordering harder than it
needs to be.

**Contract:** `inFlight` is owned by a single guard. Pick one of:
- (preferred) wrap it in a small `InFlightRegistry` class that exposes
  `register / remove / cancelAll` as suspend functions and uses a `Mutex`
  internally; or
- keep `synchronized` but document that the registry is only touched from the
  dispatcher thread.

The cancellation path (`cancelInFlightRequests`) reads + clears in one
critical section, then calls `cancelAndJoin` outside the lock.

**Acceptance:**
1. New `InFlightRegistry` (or equivalent) used at every `inFlight` call site.
2. Unit test that fires N concurrent `tools/call` requests, cancels half
   mid-flight, and asserts the registry is empty and no job leaks.

### CH-4 — Split `FeedbackSessionService`

**Site:** `fixthis-mcp/.../session/FeedbackSessionService.kt` (~304 lines)
mixes (a) session lifecycle (open/close/list), (b) annotation CRUD, and
(c) evidence-attach orchestration.

**Contract:** three responsibilities → three classes:
- `FeedbackSessionRegistry` — open / list / resolve / persist.
- `AnnotationRepository` — create / update / delete annotation rows.
- `EvidenceCoordinator` — bind screenshots + target evidence to annotations.

Public MCP routes call into these via narrow interfaces. The existing service
remains as a thin façade only if needed for callers we cannot touch in this
pass.

**Acceptance:**
1. No new public method on the façade; behaviour preserved.
2. Each new class has unit tests independent of HTTP/MCP plumbing.
3. The largest of the three is ≤ 150 lines.

## Out-of-scope follow-ups

- Replacing the JSON-RPC bookkeeping with `kotlinx.rpc` or an off-the-shelf
  MCP server library.
- Migrating `:fixthis-cli` from blocking `ProcessBuilder` to a coroutine
  process wrapper.
