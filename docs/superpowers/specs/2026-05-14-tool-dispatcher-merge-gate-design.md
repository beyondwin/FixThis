# FixThisToolDispatcher Merge Gate (SOLID Remediation Task 7)

> **Scope.** This is a **post-merge audit + follow-up registry**, not a fresh
> specification. The split of MCP tool dispatch from registry, bridge result
> cache, and console lifecycle landed on `main` via merge commit `a6abe8a`
> (2026-05-13). This document records what shipped, the residual risks the
> auditor found in the post-merge `FixThisToolDispatcher`, and the
> verification gate before declaring the tool-dispatcher portion of the
> SOLID initiative fully closed.

## 1. Current Plan Reference

Source plan:
[`2026-05-13-architecture-solid-remediation-implementation.md`](../plans/2026-05-13-architecture-solid-remediation-implementation.md)
Task 7 ("Split MCP Tool Dispatch From Registry And Cache", lines 1029–1197).

Source spec:
[`2026-05-13-architecture-solid-remediation-detailed-spec.md`](2026-05-13-architecture-solid-remediation-detailed-spec.md)
Finding F4 ("`FixThisTools` is a tool registry, dispatcher, resource handler,
bridge cache, and console owner", lines 216–255) and Phase 4 (lines 496–512).

### Task-to-merge mapping

| Plan Task 7 step                              | Commit on main                                      | Files of record                                          |
|-----------------------------------------------|-----------------------------------------------------|----------------------------------------------------------|
| Step 1 — move tool & resource definitions     | part of `0d63398 refactor(mcp): split tool registry and dispatch` | `fixthis-mcp/.../tools/McpToolRegistry.kt`              |
| Step 2 — extract `BridgeResultCache`          | same                                                | `fixthis-mcp/.../tools/BridgeResultCache.kt`             |
| Step 3 — extract `ConsoleServerManager`       | same                                                | `fixthis-mcp/.../tools/ConsoleServerManager.kt`          |
| Step 4 — split into `FixThisToolDispatcher` + `FixThisResourceDispatcher` | same | `fixthis-mcp/.../tools/{FixThisToolDispatcher,FixThisResourceDispatcher}.kt` |
| Step 5–6 — verify, commit                     | included in `0d63398`                               | —                                                        |
| Task 11 — docs                                 | `ffd50de docs: update architecture after solid remediation` | `docs/architecture/overview.md`                          |

All commits reach main via the same merge commit `a6abe8a`.

## 2. Completion Audit

### Task 7 Step 1 — `McpToolRegistry`
- **Status:** COMPLETE.
- **Evidence:**
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt`
    exists.
  - `FixThisTools.kt` is now 206 lines (down from the 869-line baseline)
    and references the registry for `listTools()` / `listResources()`.
- **Caveat:** None.

### Task 7 Step 2 — `BridgeResultCache`
- **Status:** COMPLETE.
- **Evidence:**
  - `BridgeResultCache.kt` exists with the documented public API
    (`cacheScreen`, `cacheSnapshot`, `cacheStatus`, `latestScreen`,
    `latestStatus`, `rememberDefaultPackage`).
  - Uses internal `synchronized(lock)` for all mutating methods.
- **Caveat:** The cache is constructed once per `FixThisTools`
  instantiation and not exposed via a port. Production callers receive
  it through `FixThisToolDispatcherServices` (line 56 of the dispatcher).
  This is the intended shape per the plan.

### Task 7 Step 3 — `ConsoleServerManager`
- **Status:** COMPLETE.
- **Evidence:**
  - `ConsoleServerManager.kt` exists; owns the `FeedbackConsoleServer`
    lifecycle behind a `synchronized(lock)` block.
  - `FixThisToolDispatcher.openFeedbackConsole` delegates here (the
    method appears in the handler map at line 71).
- **Caveat:** The manager keeps its own lock independent of the
  dispatcher's `openConsoleLock` (line 66 of the dispatcher). Two
  layers of locking for the same lifecycle is redundant; see R3.

### Task 7 Step 4 — Dispatcher split
- **Status:** COMPLETE.
- **Evidence:**
  - `FixThisToolDispatcher.kt` exists at 494 lines.
  - `FixThisResourceDispatcher.kt` exists.
  - `FixThisTools.call(...)` delegates to
    `toolDispatcher.call(name, arguments)`.
  - `FixThisTools.readResource(...)` delegates to
    `resourceDispatcher.read(uri)`.
- **Caveat:** The dispatcher is 494 lines — within the hotspot budget but
  still the largest single file in `tools/`. The 11-tool handler map
  (lines 67–79) is fine, but several handlers are 40+ line `suspend fun`
  methods that mix bridge calls, cache writes, and DTO mapping. The plan
  did not split these further. See R1.

### Task 1 — guardrails
- **Status:** COMPLETE.
- **Evidence:** `ArchitectureHotspotBudgetTest` carries a budget for
  `FixThisTools.kt` (`920`). It does **not** carry a budget for
  `FixThisToolDispatcher.kt` — the new file slipped in below the radar
  of the test, which only watches files that were hotspots at the time
  the test was authored. See R2.

## 3. Additional Risks Discovered

### R1. The dispatcher's handler methods are still wide

Sampled handler in `FixThisToolDispatcher.captureScreen` /
`navigateApp` / `listFeedback` / `readFeedback` reach across:

- `ports.screenBridge` (raw bridge call),
- `services.cache` (cache write),
- `services.feedbackService` (session lookup / mutation),
- `services.freshnessProbe` (host source freshness),
- `SnapshotDto` ↔ JSON encoding via `McpProtocol.json`.

Each handler is a small ad-hoc workflow. The plan did not introduce a
"workflow per tool" abstraction; the dispatcher is the workflow layer.
This is acceptable, but it means that adding a 12th tool will continue
to grow the dispatcher linearly. The current 494 lines / 11 tools
ratio is ~45 lines/tool; a 20-tool surface would push the file to
~900 lines — over the architectural intent of "thin dispatcher."

Mitigation: when the next two tools are added, extract a
`ToolWorkflow` interface and move each handler to its own file under
`fixthis-mcp/.../tools/workflows/`. The handler map then becomes a
registry of `ToolWorkflow` instances.

### R2. `FixThisToolDispatcher.kt` is not in the hotspot budget

The `ArchitectureHotspotBudgetTest` budget map enumerates pre-split
files only. The post-split `FixThisToolDispatcher.kt` is not listed,
so the file is free to grow without triggering the guardrail.

Mitigation: add `FixThisToolDispatcher.kt` to the budget at `560`
lines (current 494 + small headroom). Track in Followup-A.

### R3. Double locking around console lifecycle

`FixThisToolDispatcher` carries `private val openConsoleLock = Any()`
(line 66) **and** `ConsoleServerManager` carries its own `lock` (per
the plan's Step 3 sketch). The dispatcher's lock pre-dates the
extraction and now wraps a call into a class that has its own
serialisation. The outer lock is redundant.

Mitigation: remove `openConsoleLock` from the dispatcher; let
`ConsoleServerManager.open(...)` and `stop()` provide all the mutual
exclusion. Track in Followup-B.

### R4. `freshnessProbe` is passed even to tools that don't need it

`FixThisToolDispatcherServices` (line 52) carries a
`freshnessProbe: HostSourceFreshnessProbe`. Only two handlers
(`listFeedback`, `readFeedback` by inspection) actually consume it.
The other nine handlers carry an unused dependency by reference, and
the test fixtures must always supply a probe even for handlers that
ignore it.

Mitigation: split `FixThisToolDispatcherServices` into core services
(used by every handler) and per-handler services (passed only to
constructors of the workflows that need them). Defer to Followup-A
where the workflow split happens.

### R5. `FixThisResourceDispatcher` was not audited

This document focuses on the tool dispatcher per the user's brief, but
the parallel resource dispatcher likely has analogous risks. A quick
audit step ("are R1–R4 also true of the resource dispatcher?") is
listed in the merge gate.

### R6. Cache eviction policy is unverified by the merge

`BridgeResultCache.rememberCachedPackage` evicts entries beyond
`maxRecentOverridePackages = 8`. The plan did not add an explicit test
for the LRU-style eviction; it relies on the prior `FixThisToolsTest`
suite. A regression in eviction would not be caught by the existing
tests. Listed as a low-priority follow-up.

## 4. Merge Gate Checklist

### Code-state gates

- [x] `McpToolRegistry.kt`, `BridgeResultCache.kt`,
      `ConsoleServerManager.kt` exist under
      `fixthis-mcp/.../tools/`.
- [x] `FixThisToolDispatcher.kt` and `FixThisResourceDispatcher.kt`
      exist; `FixThisTools.kt` delegates to them.
- [x] `FixThisTools.kt` is below `920` lines (currently 206).
- [x] `FixThisToolDispatcher.kt` is below `560` lines (currently 494).
- [ ] `ArchitectureHotspotBudgetTest` lists `FixThisToolDispatcher.kt`
      with a budget of `560` (R2).
- [ ] `FixThisResourceDispatcher.kt` is similarly audited and budgeted
      (R5).
- [ ] `FixThisToolDispatcher.openConsoleLock` is removed (R3).

### Test gates

- [x] `:fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.tools.*"`
      passes.
- [x] `:fixthis-mcp:test --tests
      "io.beyondwin.fixthis.mcp.McpProtocolTest"` passes.
- [ ] A `BridgeResultCacheEvictionTest` asserts that the LRU eviction
      pops the oldest non-default package once
      `maxRecentOverridePackages` is exceeded (R6).
- [ ] A `ConsoleServerManagerLifecycleTest` asserts that
      `open()` is idempotent across concurrent callers (independent
      of the dispatcher's lock, so that R3 can be removed safely).

### Documentation gates

- [x] `docs/architecture/overview.md` references the new tool files.
- [ ] An ADR or `docs/reference/` note documents the
      dispatcher-registry-cache split so a future contributor adding a
      new MCP tool knows where each concern lives.

### Verification commands

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.tools.*"
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.architecture.*"
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.McpProtocolTest"
./gradlew detekt --no-daemon
git diff --check
```

All must pass before flipping any unchecked box.

## 5. Follow-up Work

### Followup-A. Workflow-per-tool extraction (R1, R4)

Trigger: when the next MCP tool is added, or `FixThisToolDispatcher.kt`
crosses 500 lines, whichever comes first.

Sketch:

- Introduce `interface ToolWorkflow { suspend fun execute(args: JsonObject):
  JsonObject; val name: String }`.
- Move each existing handler into a single-class file under
  `fixthis-mcp/.../tools/workflows/`. Each workflow declares only the
  services it actually needs.
- `FixThisToolDispatcher` becomes a thin registry: `private val workflows:
  Map<String, ToolWorkflow>`. `call(name, args)` is `workflows[name]
  ?.execute(args) ?: errorUnknown(name)`.

Acceptance: `FixThisToolDispatcher.kt` is under 200 lines; each
workflow file is under 120 lines; the architecture hotspot budget for
the dispatcher drops to `220`.

### Followup-B. Drop the dispatcher's `openConsoleLock` (R3)

Trigger: after `ConsoleServerManagerLifecycleTest` proves the manager
serialises concurrent `open()` calls correctly.

Sketch: remove the field and the `synchronized(openConsoleLock)` block
from `FixThisToolDispatcher.openFeedbackConsole`. Trust the manager's
internal lock.

Acceptance: existing console-open tests pass; the
`ConsoleServerManagerLifecycleTest` from the merge gate above passes.

### Followup-C. Hotspot budget for the dispatcher and resource dispatcher (R2, R5)

Add to `ArchitectureHotspotBudgetTest`:

```kotlin
"fixthis-mcp/.../tools/FixThisToolDispatcher.kt" to 560,
"fixthis-mcp/.../tools/FixThisResourceDispatcher.kt" to <measured + 40>,
```

Run the test once to establish the current line counts, then commit the
budgets at "current + small headroom."

### Followup-D. Cache eviction regression test (R6)

Add `BridgeResultCacheTest.lruEvictsOldestNonDefaultPackage`:
construct the cache with `maxRecentOverridePackages = 2`, write three
distinct package entries, assert that the first non-default package is
evicted. This is purely a regression net; no behaviour change.

### Followup-E. Workflow doc

`docs/reference/mcp-tools.md` (new) or a section under
`docs/architecture/overview.md` documenting:

- "How is an MCP tool dispatched?" (the call path through
  `FixThisTools → FixThisToolDispatcher → handler → bridge/cache/service`).
- "Where do new tools go?" (the workflow-per-file convention once
  Followup-A lands).

## References

- Merged plan: [`2026-05-13-architecture-solid-remediation-implementation.md`](../plans/2026-05-13-architecture-solid-remediation-implementation.md), Task 7
- Merged spec: [`2026-05-13-architecture-solid-remediation-detailed-spec.md`](2026-05-13-architecture-solid-remediation-detailed-spec.md), Finding F4
- Current dispatcher: [`fixthis-mcp/.../tools/FixThisToolDispatcher.kt`](../../../fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisToolDispatcher.kt) (494 lines)
- Current registry: [`fixthis-mcp/.../tools/McpToolRegistry.kt`](../../../fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt)
- Cache: [`fixthis-mcp/.../tools/BridgeResultCache.kt`](../../../fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/BridgeResultCache.kt)
- Console manager: [`fixthis-mcp/.../tools/ConsoleServerManager.kt`](../../../fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/ConsoleServerManager.kt)
- Companion merge gate: [`2026-05-14-feedback-session-store-merge-gate-design.md`](2026-05-14-feedback-session-store-merge-gate-design.md)
- Merge commit: `a6abe8a Merge branch 'codex/architecture-solid-remediation'`
