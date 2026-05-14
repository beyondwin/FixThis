# Console State Machine Expansion — Design Spec

> Status: Draft (2026-05-14)
> Owner: Feedback console
> Companion plan: [`docs/superpowers/plans/2026-05-14-console-state-machine-expansion-implementation.md`](../plans/2026-05-14-console-state-machine-expansion-implementation.md)

## 1. Problem

The 2026-05-14 draft workspace state-machine plan
([`docs/superpowers/plans/2026-05-14-draft-workspace-state-machine.md`](../plans/2026-05-14-draft-workspace-state-machine.md))
addresses **one** of the console's mutable-state islands — the pending-annotation
draft workspace — by introducing a pure reducer (`draftWorkspace.js`), use
cases, ports, and a command queue. The other state islands remain ad-hoc:
top-level `let` declarations spread across `state.js`, `connection.js`,
`devices.js`, and the larger `annotations.js` / `rendering.js` modules.

### 1.1 Inventory (verified)

Counted in `fixthis-mcp/src/main/console/`:

| Module | `let` / `var` count | Notable shared state |
|---|---|---|
| `state.js` | 94 declarations | ~30 are mutable `let` flags + timers; the rest are DOM handles. |
| `annotations.js` | 76 declarations | mostly locals inside ~30 functions, **but** 4 module-level: `let dragStart`, `let dragPreview`, `let suppressNextClick`, `let hoveredAnnotationTarget` are actually owned by `state.js` and only mutated from `annotations.js`. |
| `rendering.js` | 111 declarations | All locals inside render functions; no module-level mutable state. |
| `connection.js` | 25 declarations | 1 module-local: `let lastHeartbeatError = null` (line 232). |
| `devices.js` | 19 declarations | Locals inside functions only; no module-level. |
| `history.js` | 31 declarations | Locals; no module-level mutable state. |
| `preview.js` | 26 declarations | Locals; no module-level mutable state. |
| `sessions-polling.js` | 6 declarations | No module-level mutable state — reads/writes `state.js` globals. |

Across `state.js` lines 64–98, the genuinely cross-module mutable state is:

```js
let livePreviewTimer = null;                  // preview FSM
let heartbeatTimer = null;                    // connection FSM
let heartbeatPolling = false;                 // connection FSM
let previewRequestGeneration = 0;             // preview FSM
let previewRequestContextGeneration = 0;      // preview FSM
let sessionMutationGeneration = 0;            // mutation lock FSM
let previewRequestInFlight = null;            // preview FSM
let previewRequestInFlightContextGeneration = null;  // preview FSM
let addItemsFlow = null;                      // already-migrated draft FSM
let addItemsFlowStarting = false;             // tool-mode FSM
let newHistoryAnnotateModeStarting = false;   // tool-mode FSM
let promptActionInFlight = false;             // prompt FSM
let pendingFeedbackItems = [];                // already-migrated draft FSM (shim)
let focusedPendingItemIndex = null;           // already-migrated draft FSM (shim)
let focusedSavedItemId = null;                // selection FSM
let focusedSavedSessionId = null;             // selection FSM
let currentSelection = null;                  // already-migrated draft FSM (shim)
let toolMode = 'select';                      // tool-mode FSM
let annotationSequence = 1;                   // tool-mode FSM (id generator counter)
let hoveredAnnotationTarget = null;           // tool-mode FSM
let dragStart = null;                         // tool-mode FSM
let dragPreview = null;                       // tool-mode FSM
let suppressNextClick = false;                // tool-mode FSM
let previewZoom = 1;                          // zoom FSM
let historyDrawerOpen = false;                // ui-drawer FSM
let sessionsPollingTimer = null;              // polling FSM
let lastSessionsEtag = null;                  // polling FSM
let lastSessionEtag = null;                   // polling FSM
let pendingMutationCount = 0;                 // mutation lock FSM
let consecutivePollFailures = 0;              // polling FSM
let undoRedoHistory = createHistory();        // already-migrated draft FSM (shim)
let draftWorkspace = createEmptyDraftWorkspace();  // already-migrated draft FSM
let draftCommandQueue = null;                 // already-migrated draft FSM
```

Plus the structured `state.connection.*` object (also in `state.js:10-19`)
which carries 7 fields and is mutated by ~8 call sites in `connection.js`,
`availability.js`, and `devices.js`.

### 1.2 What hurts

1. **Implicit invariants.** `heartbeatTimer` and `heartbeatPolling` must
   always agree (a non-null timer means polling is true). Today the
   contract is enforced by convention in 3 call sites in `connection.js`.
2. **Cross-module mutation is silent.** Setting `toolMode = 'annotate'`
   from `annotations.js` does not notify `rendering.js`; the next render
   tick eventually reads the new value. The render pipeline polls global
   state on every frame.
3. **Race-prone generation counters.** `previewRequestGeneration` and
   `previewRequestContextGeneration` exist precisely because the
   ad-hoc-mutation model lets stale async responses overwrite fresh
   ones. The counters are correct but require careful incrementing in
   exactly the right order.
4. **Polling failure backoff is brittle.** `consecutivePollFailures`,
   `MaxConsecutivePollFailures` (5), and `sessionsPollingTimer` are
   managed by `sessions-polling.js` reading `state.connection.*` and
   writing `state.js` globals. The tested contract is fragile (see the
   13 polling tests already split out by Item 3).
5. **Recovery and undo do not stack.** Each FSM that needs recovery
   reinvents serialization. `pendingPersistence.js` (draft FSM v1) and
   `draftStorageAdapter.js` (draft FSM v2) handle annotations only;
   nothing persists tool-mode or zoom across reloads, so users routinely
   land in `select` mode at 100% zoom after a refresh.

### 1.3 Out-of-scope reductions

DOM handle `const` declarations (50 in `state.js`) do **not** need a
state machine; they are inert references to DOM elements. The spec
considers them fine as-is.

Per-function `let` declarations inside `rendering.js` (111),
`annotations.js` (~70 of 76), and `history.js` (31) are local loop
counters and accumulators — they are not module state.

## 2. Goals / Non-Goals

### Goals

- Introduce **four** sub-state-machines that own the mutable state islands
  identified in §1.1:

  1. **Connection FSM** — owns `state.connection.*`, `heartbeatTimer`,
     `heartbeatPolling`, `lastHeartbeatError`, and connection
     availability transitions.
  2. **Preview FSM** — owns `livePreviewTimer`, `previewRequestGeneration`,
     `previewRequestContextGeneration`, `previewRequestInFlight`,
     `previewRequestInFlightContextGeneration`, `previewZoom`.
  3. **Tool-mode FSM** — owns `toolMode`, `annotationSequence`,
     `hoveredAnnotationTarget`, `dragStart`, `dragPreview`,
     `suppressNextClick`, `addItemsFlowStarting`,
     `newHistoryAnnotateModeStarting`, `historyDrawerOpen`,
     `focusedSavedItemId`, `focusedSavedSessionId`.
  4. **Polling FSM** — owns `sessionsPollingTimer`, `lastSessionsEtag`,
     `lastSessionEtag`, `pendingMutationCount`, `sessionMutationGeneration`,
     `consecutivePollFailures`, `promptActionInFlight`.

- Each sub-FSM follows the same architectural shape as the existing
  draft workspace FSM:
  - Pure reducer (no DOM, no browser globals).
  - Application use cases over narrow ports.
  - Browser adapter that wires the use cases to real DOM/network/timer
    primitives.
  - Compatibility shim in `state.js` so legacy callers keep working
    during migration.

- Reuse `draftCommandQueue.js` semantics for serializing transitions and
  fencing stale async responses where applicable (Preview FSM, Polling
  FSM).

- Reduce module-local mutable state in `state.js` from ~30 `let` to ≤ 5
  (only those owned by no sub-FSM — currently none qualify; the goal is
  zero).

- Cover each FSM with Node `node:test` reducer tests in the same style as
  `scripts/draftWorkspace-test.mjs`.

- Preserve existing console behavior: any HTTP request, persisted JSON
  shape, or asset-contract test must continue to pass.

### Non-Goals

- **No umbrella store framework.** No Redux, no Zustand, no MobX. The
  sub-FSMs are plain JS reducers, deliberately small.
- **No state synchronization across tabs.** Each browser tab continues to
  own its own FSM instances.
- **No new HTTP routes.** The MCP server is unchanged.
- **No CSS or HTML structural change.**
- **No migration of the DOM-handle `const`s in `state.js`.** They remain
  inert references; renaming them is a separate cleanup.
- **No work on the existing draft workspace FSM.** Items 1 and 2 of this
  document specifically expand to the *other* state islands.
- **No work on undo/redo coordination across FSMs.** Each FSM may have
  its own undo (or none); cross-FSM undo is left for a later iteration.

## 3. Design

### 3.1 Architectural shape (repeated per FSM)

Each of the four sub-FSMs follows this layered shape, mirroring the
existing draft workspace decomposition:

```
   ┌────────────────────────────────────────────────────────────┐
   │ Pure reducer (no DOM)                                      │
   │   <fsm>Reducer.js  + <fsm>Reducer-test.mjs                 │
   │   - Lifecycle enum                                          │
   │   - createEmpty<Fsm>()                                      │
   │   - reduce<Fsm>(state, action) → state                      │
   │   - selectors (read-only)                                   │
   └─────────────────────┬──────────────────────────────────────┘
                         │
   ┌─────────────────────┴──────────────────────────────────────┐
   │ Use cases over ports (no DOM, no globals)                  │
   │   <fsm>UseCases.js  + <fsm>UseCases-test.mjs               │
   │   - Async workflows: open(), tick(), retry(), ...           │
   │   - Each use case calls reducer + dispatches port effects   │
   └─────────────────────┬──────────────────────────────────────┘
                         │
   ┌─────────────────────┴──────────────────────────────────────┐
   │ Browser adapter (DOM + timers + fetch)                      │
   │   <fsm>BrowserAdapter.js                                   │
   │   - createBrowser<Fsm>Ports() — returns ports               │
   │   - Wires reducer state to render hooks                     │
   └─────────────────────┬──────────────────────────────────────┘
                         │
   ┌─────────────────────┴──────────────────────────────────────┐
   │ Compat shim in state.js                                    │
   │   - Hold the FSM instance                                  │
   │   - Re-export legacy globals as thin getters/setters       │
   │     during migration                                       │
   └────────────────────────────────────────────────────────────┘
```

### 3.2 Connection FSM (Task 2 in the plan)

**Lifecycle states:**

```
DISCONNECTED ─┐
              │ launch                    ┌─── tick OK ────┐
              ▼                          │                ▼
        LAUNCHING ───── ready ────► READY ──── tick fail ─┐
              │                          ▲                │
              │ launch fail              │                │
              ▼                          │                ▼
        UNAVAILABLE  ◄──────── retry ─── │ ── BLOCKED (interaction-blocked) ─┐
                                         │                                  │
                                         └──── unblock ─────────────────────┘
```

**Owned state:**

```js
{
  lifecycle: 'DISCONNECTED' | 'LAUNCHING' | 'READY' | 'BLOCKED' | 'UNAVAILABLE',
  current: { connection details ... } | null,
  hasEverConnected: boolean,
  lastReadyAt: number | null,
  availability: { ... } | null,
  interactionBlockedReason: string | null,
  previousBlockedReason: string | null,
  sessionsPollingPaused: boolean,
  heartbeatGeneration: number,        // replaces ad-hoc heartbeatTimer == null check
  lastHeartbeatError: string | null,
}
```

**Actions:** `LAUNCH_REQUESTED`, `LAUNCH_SUCCEEDED`, `LAUNCH_FAILED`,
`HEARTBEAT_OK`, `HEARTBEAT_FAILED`, `INTERACTION_BLOCKED`,
`INTERACTION_UNBLOCKED`, `AVAILABILITY_UPDATED`.

**Replaces (in `state.js`):** the entire `state.connection.*` object plus
`heartbeatTimer`, `heartbeatPolling` (and `lastHeartbeatError` in
`connection.js`). The heartbeat timer is owned by the browser adapter,
not the reducer.

### 3.3 Preview FSM (Task 3)

**Lifecycle states:**

```
IDLE ── request ──► REQUESTING ── ok ──► READY ── stale ──► STALE
  ▲                       │                                    │
  │                       │ fail                               │
  │                       ▼                                    │
  └─────── reset ───── ERROR  ◄──── retry ─────────────────────┘
```

**Owned state:**

```js
{
  lifecycle: 'IDLE' | 'REQUESTING' | 'READY' | 'STALE' | 'ERROR',
  contextGeneration: number,    // replaces previewRequestContextGeneration
  requestGeneration: number,    // replaces previewRequestGeneration
  inFlight: {
    generation: number,
    contextGeneration: number,
  } | null,
  current: { previewId, screenshotUrl, ... } | null,
  zoom: number,                 // replaces previewZoom
  pollIntervalMs: number,       // mirrors PreviewIntervalStorageKey
  error: string | null,
}
```

The generation counters survive verbatim; they are the existing
race-fence mechanism. The reducer formalizes when they advance.

**Replaces:** `livePreviewTimer`, `previewRequestGeneration`,
`previewRequestContextGeneration`, `previewRequestInFlight`,
`previewRequestInFlightContextGeneration`, `previewZoom`.
(`livePreviewTimer` itself is held by the browser adapter, not the reducer.)

### 3.4 Tool-mode FSM (Task 4)

**Lifecycle states:**

```
SELECT ── enter_annotate ──► ANNOTATE_IDLE
                                 │
                                 │ start_drag
                                 ▼
                            ANNOTATE_DRAGGING
                                 │
                  ┌──────────────┼──────────────┐
                  │              │              │
              cancel         drop_commit    drop_discard
                  │              │              │
                  ▼              ▼              ▼
            ANNOTATE_IDLE      SELECT          SELECT
```

**Owned state:**

```js
{
  mode: 'SELECT' | 'ANNOTATE_IDLE' | 'ANNOTATE_DRAGGING',
  annotationSequence: number,
  hoveredTarget: { ... } | null,
  drag: { start: {x,y}, preview: { rect } } | null,
  suppressNextClick: boolean,
  focusedSavedItemId: string | null,
  focusedSavedSessionId: string | null,
  historyDrawerOpen: boolean,
  addItemsFlowStarting: boolean,
  newHistoryAnnotateModeStarting: boolean,
}
```

**Replaces:** `toolMode`, `annotationSequence`, `hoveredAnnotationTarget`,
`dragStart`, `dragPreview`, `suppressNextClick`, `addItemsFlowStarting`,
`newHistoryAnnotateModeStarting`, `historyDrawerOpen`,
`focusedSavedItemId`, `focusedSavedSessionId`.

### 3.5 Polling FSM (Task 5)

**Lifecycle states:**

```
STOPPED ── start ──► POLLING_ACTIVE ── visibility_hidden ──► POLLING_PAUSED
   ▲                       │                                       │
   │                       │ failure × N                           │
   │                       ▼                                       │
   │                  POLLING_BACKOFF ◄─── visibility_visible ─────┘
   │                       │
   │                       │ success
   │                       ▼
   │                  POLLING_ACTIVE
   │                       │
   └─ all_done ────────────┘
```

**Owned state:**

```js
{
  lifecycle: 'STOPPED' | 'POLLING_ACTIVE' | 'POLLING_BACKOFF' | 'POLLING_PAUSED',
  lastSessionsEtag: string | null,
  lastSessionEtag: string | null,
  pendingMutationCount: number,
  mutationGeneration: number,
  consecutiveFailures: number,
  promptActionInFlight: boolean,
}
```

Constants kept (referenced by `ConsoleSessionsPollingContractTest`):

- `MaxConsecutivePollFailures = 5`
- `MinLivePreviewIntervalMs = 1000`
- `DefaultLivePreviewIntervalMs = 1000`
- `PreviewIntervalStorageKey = 'fixthis.previewIntervalMs.v2'`

These remain top-level `const` in their existing modules and are *not*
moved into FSM state.

**Replaces:** `sessionsPollingTimer`, `lastSessionsEtag`, `lastSessionEtag`,
`pendingMutationCount`, `sessionMutationGeneration`,
`consecutivePollFailures`, `promptActionInFlight`.

### 3.6 Cross-FSM coordination

The four sub-FSMs do not share a single store. Coordination happens at
the *use-case* layer:

- Connection FSM `BLOCKED` action calls `pollingUseCases.pause()`.
- Polling FSM `mutationStart()` increments `pendingMutationCount`;
  when the count drops to zero AND the connection FSM is `READY`,
  the use case auto-resumes polling.
- Preview FSM `request()` reads the connection FSM lifecycle via a
  selector; it returns early if not `READY`.

Each cross-FSM call is a one-line use-case-to-use-case invocation in
`main.js`, with the dependency injected at boot. There is no global
event bus.

### 3.7 ASCII diagram of the post-migration layout

```
fixthis-mcp/src/main/console/
    state.js                          ─── owns DOM handles only (~50 const)
                                          + holds FSM instances
    connection.js                     ─── presentation only
    devices.js                        ─── presentation only
    annotations.js                    ─── presentation only
    rendering.js                      ─── selectors → DOM
    sessions-polling.js               ─── thin wrapper over pollingUseCases
    preview.js                        ─── thin wrapper over previewUseCases

  + connectionFsm.js                  ─── NEW: pure reducer
  + connectionUseCases.js             ─── NEW: workflows
  + connectionBrowserAdapter.js       ─── NEW: timers + heartbeat HTTP

  + previewFsm.js                     ─── NEW
  + previewUseCases.js                ─── NEW
  + previewBrowserAdapter.js          ─── NEW

  + toolModeFsm.js                    ─── NEW
  + toolModeUseCases.js               ─── NEW

  + pollingFsm.js                     ─── NEW
  + pollingUseCases.js                ─── NEW
  + pollingBrowserAdapter.js          ─── NEW
```

12 new files (4 FSMs × 3 layers; tool-mode FSM has no async I/O, so it
needs no browser adapter — 11 files in practice). Each carries a
`// @requires` header for Item 2's bundler.

## 4. Migration Strategy

The migration sequence respects the dependency graph (later FSMs may
read selectors from earlier ones):

| Phase | FSM | Why this order |
|---|---|---|
| 1 | Connection | Most cross-cutting; all other FSMs read its lifecycle. |
| 2 | Preview | Depends on Connection. |
| 3 | Polling | Depends on Connection + Preview (pause on mutation). |
| 4 | Tool-mode | Depends on nothing; can land last with zero blockers. |

### Phase 0 — Establish the umbrella spec test

A single test file `scripts/consoleFsmIsolation-test.mjs` asserts that
the four sub-FSM modules expose pure reducers (no DOM access, no
`document`, no `window`, no `setTimeout` in the reducer file). This is
the umbrella invariant; it lands red and turns green as each sub-FSM is
extracted.

### Phase 1–4 — Per-FSM extraction (Tasks 2–5)

For each FSM:

1. Write the pure reducer + its `node:test`.
2. Write the use cases + their tests with fake ports.
3. Write the browser adapter (where async I/O is needed).
4. Add a compat shim in `state.js`: legacy globals become getter/setter
   pairs delegating to the FSM. Legacy callers do not change yet.
5. Migrate the call sites in `annotations.js`, `connection.js`, etc., to
   the use cases. Remove the compat shim.
6. Delete the legacy `let` declarations from `state.js`.
7. Re-run the Gradle matrix + Node tests + browser smoke.

### Phase 5 — Final cleanup (Task 6)

- `state.js` retains only DOM handles plus FSM holders.
- Assert that `state.js` has ≤ 5 module-level mutable declarations.
- Update `docs/reference/feedback-console-contract.md`.

### Backwards compatibility

- Persisted MCP JSON: unchanged. No FSM here is server-persisted; they
  are all client-side.
- HTTP routes: unchanged.
- Asset contract tests: unchanged (because Item 2 already migrates them
  to unbundled source reads; if Item 2 has not landed, this plan re-uses
  the same approach in its own helpers).
- localStorage: each FSM that wants recovery (none in this iteration —
  Tool-mode/Preview/Polling are session-scoped, not reload-scoped)
  defines its own schema; the existing
  `localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]` is
  untouched.

## 5. Test Strategy

### 5.1 Reducer tests (4 files)

Each FSM gets a `scripts/<fsm>Fsm-test.mjs` file in the same shape as
`scripts/draftWorkspace-test.mjs`:

- Pure tests; no DOM, no `globalThis` mocks.
- Each action verified for: (a) lifecycle transition, (b) state delta,
  (c) idempotency where appropriate.
- Invariants asserted explicitly (e.g., `heartbeatGeneration` strictly
  monotonic; `consecutiveFailures` resets to 0 on any success).

### 5.2 Use-case tests (4 files)

Each FSM gets `scripts/<fsm>UseCases-test.mjs` with fake ports
(test-only `setTimeout`, fake HTTP, fake clock).

- Race tests: stale async response after lifecycle changed must be
  fenced (generation counter test).
- Mutation-lock test: polling pauses while a mutation is in flight,
  resumes on completion.

### 5.3 Cross-FSM contract tests

A single `scripts/consoleFsmContract-test.mjs` asserts:

- Each reducer file does not contain `document.` or `window.` references.
- Each use-cases file does not contain `setTimeout` (must come via port).
- The umbrella `state.js` post-migration has at most 5 module-local
  `let` declarations (grep-based).

### 5.4 Asset contract tests

The 33 asset contract tests in `ConsoleAssetContractTest.kt` (post Item 3
split) grep for symbol names like `withMutationLock`,
`MaxConsecutivePollFailures`, `DefaultLivePreviewIntervalMs`. The new
FSMs preserve these names as top-level identifiers (the constants live
in their original modules; only the `let`s move into FSMs). After
each task, the asset contract suite runs.

### 5.5 Gradle matrix

`./gradlew :fixthis-mcp:test` is the final gate after every task — the
~200 Kotlin tests that exercise the browser-served bundle must pass.

### 5.6 Browser smoke

`scripts/console-browser-smoke.mjs` runs after every FSM lands.

## 6. Open Risks

### R1 — FSM-to-FSM coupling becomes a hidden global

**Risk:** Each FSM injects the others' use cases through `main.js` at
boot. If `main.js` accumulates 12+ such bindings, it becomes its own
implicit god-object.

**Mitigation:**
- A single `consoleApp.js` factory (Task 6) wires all FSMs and returns
  a structured `{connection, preview, polling, toolMode}` handle.
- `main.js` calls `createConsoleApp()` and accesses the structured
  handle, not 12 loose use cases.

### R2 — Concurrent landing with Items 2 and 3

**Risk:** Item 2 introduces `// @requires` headers and minification;
Item 3 splits tests. New FSM source files need their own
`// @requires` headers and may produce new contract symbols.

**Mitigation:**
- Each new FSM module gets its `// @requires` header in the same
  commit that creates the file.
- The `CONTRACT_SYMBOLS` list in Item 2's build script gets one new
  entry per top-level FSM-exposed function in the same commit
  (e.g., `MaxConsecutivePollFailures`, `connectionLifecycle`,
  `previewLifecycle`).

### R3 — Compat shim period leaks state

**Risk:** During the multi-commit migration of one FSM, both the old
`let` and the new FSM instance hold state. Mutations to one do not
propagate to the other unless the shim is strict.

**Mitigation:**
- The shim uses a **getter/setter** pair, never a stored value. The
  FSM is the single source of truth from the moment of its creation:

  ```js
  // legacy: let toolMode = 'select';
  // new:    (no let; getters)
  Object.defineProperty(globalThis, 'toolMode', {
    get: () => toolModeFsm.state.mode.toLowerCase(),
    set: (value) => toolModeUseCases.setMode(value.toUpperCase()),
  });
  ```

- Each task ends with a `grep -n "let <state>" state.js` showing zero
  remaining legacy declarations for that FSM's territory.

### R4 — Animation-frame timing regression

**Risk:** The existing `rendering.js` reads global state on every
animation frame. After FSM extraction, those reads go through
selectors (`toolModeFsm.state.mode`). Selector overhead can add
microseconds × 60 fps; on slow Android WebViews, this matters.

**Mitigation:**
- Each FSM exposes selectors that return primitives or frozen objects;
  no per-call allocation.
- A microbenchmark in `scripts/console-responsive-stress.mjs` records
  the frame-budget impact before/after.

### R5 — Asset contract symbol drift

**Risk:** Renaming `heartbeatTimer` to a private field inside
`connectionBrowserAdapter` causes `ConsoleSessionsPollingContractTest`
to fail its grep for `heartbeatTimer`.

**Mitigation:**
- Audit the contract tests at the start of each task. For each
  symbol the test greps that the migration is about to rename,
  either:
  - Keep the symbol as a top-level alias (preferred), or
  - Update the test in the same commit that renames the symbol.

### R6 — Recovery scope creep

**Risk:** Once we have an FSM for tool-mode, contributors may ask
"why not persist tool-mode across reloads?" — driving scope creep.

**Mitigation:**
- §2 Non-Goals explicitly excludes cross-reload recovery for the new
  FSMs. The spec calls it out.
- Each new FSM is session-scoped; recovery is a follow-up plan.

## 7. References

### Specs / plans

- Companion plan:
  `docs/superpowers/plans/2026-05-14-console-state-machine-expansion-implementation.md`
- Predecessor (draft workspace FSM):
  `docs/superpowers/plans/2026-05-14-draft-workspace-state-machine.md`
- Sibling Item 2 (bundle optimization, share the `// @requires` annotation surface):
  `docs/superpowers/specs/2026-05-14-console-bundle-optimization-design.md`
- Sibling Item 3 (test decomposition, source of the asset contract test
  cluster):
  `docs/superpowers/specs/2026-05-14-console-routes-test-decomposition-design.md`

### Code locations

- Mutable state inventory source: `fixthis-mcp/src/main/console/state.js:64-98`
- Connection-shape struct: `fixthis-mcp/src/main/console/state.js:10-19`
- Heartbeat ad-hoc state: `fixthis-mcp/src/main/console/connection.js:232`
- Polling globals: `fixthis-mcp/src/main/console/state.js:89-94` and
  `fixthis-mcp/src/main/console/sessions-polling.js`
- Tool-mode globals: `fixthis-mcp/src/main/console/state.js:81-88`
- Existing draft workspace reducer (style template):
  `fixthis-mcp/src/main/console/draftWorkspace.js`
- Existing reducer test (style template):
  `scripts/draftWorkspace-test.mjs`
- Asset contract tests that grep for FSM symbols:
  `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
  (will be `ConsoleSessionsPollingContractTest.kt` after Item 3 lands).
