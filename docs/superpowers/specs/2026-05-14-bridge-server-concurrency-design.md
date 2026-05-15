# Bridge Server Concurrency Design

> **Status:** Proposal (2026-05-14). Targets the post-split `BridgeServer.kt`
> (195 lines) that landed via commit `c6c0524 refactor(sidekick): split bridge
> runtime files`, merged into `main` as `a6abe8a` (SOLID Remediation Phase 3 /
> Task 6 of
> [`docs/superpowers/plans/2026-05-13-architecture-solid-remediation-implementation.md`](../plans/2026-05-13-architecture-solid-remediation-implementation.md)).
> The 698-line baseline cited in the SOLID plan's Baseline section was the
> *pre-split* file; the concurrency hazards survived the split because the
> split was structural (file boundaries), not behavioural (locking model).

## 1. Problem

`BridgeServer` is the sole entry point for the sidekick's LAN-of-one
LocalSocket bridge. It is constructed once per `Application` instance by the
`FixThisBridgeRuntime` singleton and exposes three lifecycle methods plus the
JSON-RPC request handler:

```
BridgeServer.start()  -> Boolean
BridgeServer.stop()   -> Unit
BridgeServer.handleRequestForTest(payload: String) -> String  (internal)
```

The current implementation
([`fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt))
relies on two `@Volatile` fields for shared state:

```kotlin
@Volatile private var serverSocket: LocalServerSocket? = null
@Volatile private var resolvedName: String? = null
```

`@Volatile` guarantees that each *individual* read sees the latest write,
but does not make compound sequences atomic. Three concrete races exist
today:

### 1.1. start() TOCTOU on `serverSocket`

```kotlin
fun start(): Boolean {
    if (serverSocket != null) return false   // (a) check
    ...
    for (attempt in 0 until BridgeSocketNameNegotiator.MaxAttempts) {
        ...
        serverSocket = socket                  // (b) assign
        resolvedName = candidate
        scope.launch { acceptLoop(socket) }
        return true
    }
}
```

If two threads call `start()` simultaneously (e.g., a test harness racing
the `Application` debuggable check), both can observe `serverSocket == null`
at (a) and both proceed to (b). The second assignment overwrites the first
`LocalServerSocket` reference, leaks the first socket binding, and launches
a second `acceptLoop` reading from a discarded socket. The `BridgeRuntime`
singleton wraps `start()` in `synchronized(lock)` today, which masks this in
production; tests that construct `BridgeServer` directly do not, and the
contract of the class permits multiple owners.

### 1.2. stop() races with in-flight acceptLoop

```kotlin
fun stop() {
    runCatching { serverSocket?.close() }
    serverSocket = null
    resolvedName = null
    scope.cancel()
}
```

`stop()` reads `serverSocket` (now null), closes it, then cancels the scope.
A concurrent `acceptLoop` running on the scope may be mid-`socket.accept()`
when `stop()` closes the socket. The `accept()` throws, the catch swallows
it, and the loop exits — which is fine. But a request handler coroutine
launched by the previous `acceptLoop.launch { handleClient(client) }` keeps
running because `scope.cancel()` only cancels its *Job*, not the IO it has
already started. If `stop()` returns and a caller immediately calls
`start()` again, the new accept loop binds to the same abstract name; the
zombie handler from the previous generation can still write to its
already-closed client socket. The write fails silently, but the resolved
`socketName` reported on the wire can briefly reflect the old generation if
the zombie handler completes a `"status"` call after the new `start()`
re-publishes `resolvedName`.

### 1.3. resolvedSocketName() observes torn state

`resolvedSocketName()` returns `resolvedName` directly. During a
`start() -> stop() -> start()` sequence with a different negotiated
candidate (e.g. base name, then `-1` suffix), a caller can observe the
sequence `base -> null -> base-1`, but also — under the TOCTOU in 1.1 — the
sequence `base -> base-1 -> base` if two concurrent start attempts race on
which candidate wins. There is no way for an external observer to know
which generation a returned name belongs to.

### 1.4. BridgeRuntime synchronization is informal

`FixThisBridgeRuntime`
([`BridgeRuntime.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt))
uses `synchronized(lock)` to guard `start` and `stopForTest`, but:

- `onActivityResumed` / `onActivityDestroyed` are *not* synchronized; they
  mutate `environment?.currentActivity` from the main thread while
  `start()`'s setup block may be writing `environment` from a different
  caller.
- The lock guards the *runtime* singleton, not `BridgeServer` itself, so
  `BridgeServer.start()` cannot rely on any caller-supplied locking.
- `BridgeRuntime` is documented as the only owner today, but the
  `BridgeServer` constructor is `class` (not `internal class`); any
  consumer of `:fixthis-compose-sidekick` can instantiate it directly.

### 1.5. What is *not* broken

- `BridgeConnectionState.lastAuthorizedRequestAtMillis` is `@Volatile` and
  read exactly once per `isConnected()` call. The read-and-compare on a
  single `Long` is safe; no fix needed there.
- The request-handling coroutines themselves (`handleClient`, `handleRequest`)
  do not share mutable state across requests; each request is independent.
- `BridgeProtocol.readFrame` / `writeFrame` are stateless.

So this design is scoped to **lifecycle state** (start/stop/observable
status) and **environment binding** (current activity) — not the per-request
data path.

## 2. Goals / Non-Goals

### Goals

- **G1.** `start()` is safe to call from any thread; concurrent invocations
  produce exactly one bound socket and at most one running `acceptLoop`.
- **G2.** `stop()` waits for in-flight client coroutines launched on the
  server's scope to terminate before returning, with no zombie handler
  writes to closed sockets after `stop()` returns.
- **G2a.** **`BridgeServer` is single-use.** After `stop()` returns, the
  instance cannot be restarted; the internal `scope` is cancelled. To
  restart, the owner (`FixThisBridgeRuntime`) constructs a fresh
  `BridgeServer`. This matches production reality (the runtime never
  restarts the same instance) and removes an entire class of "restart
  race" failure modes. See §3.4 for the rationale.
- **G3.** Lifecycle state is observable via a typed `StateFlow` for tests
  and future console/UI consumers, without exposing internal sockets or
  scopes.
- **G4.** `BridgeRuntime`'s informal Java `synchronized` lock is replaced
  by a coroutine-friendly `Mutex` so that `start()` (which performs a
  potentially-blocking `LocalServerSocket` bind) does not pin a thread that
  cannot suspend.
- **G5.** A new test class `BridgeServerConcurrencyTest` reproduces the
  three races in §1 and demonstrates they no longer occur after the fix.
  Each race-reproduction test exercises **the actual code path it claims
  to cover** (T2 must drive the socket path, not the test-only
  `handleRequestForTest` shortcut).
- **G6.** Public API of `BridgeServer` and `FixThisBridgeRuntime` is
  preserved for existing callers — adding `state(): StateFlow<...>` is
  additive only. `start()` / `stop()` become `suspend fun` but
  `FixThisBridgeRuntime` keeps a non-suspending facade.
- **G7.** No new ANR risk. `runBlocking(Dispatchers.IO)` is **not**
  invoked from the main thread. `FixThisBridgeRuntime.start(...)` is
  invoked off the main thread (see §3.6).

### Non-Goals

- **NG1.** Multi-client fairness. The sidekick is "LAN of one"; we don't
  add request queues or backpressure.
- **NG2.** Bridge protocol changes. `BridgeProtocol.VERSION` stays `1.3`.
- **NG3.** Persisted session-token format changes. `session.json` keeps
  the same fields.
- **NG4.** Replacing `LocalServerSocket` with anything else (network
  sockets, gRPC, etc.).
- **NG5.** Cross-process concurrency. The negotiator in
  `BridgeSocketNameNegotiator` already handles stale prior binds from a
  dead sibling process; we are only fixing in-process concurrency.

## 3. Design

### 3.1. State model

Introduce a single sealed lifecycle type:

```
sealed interface BridgeServerState {
    data object Idle      : BridgeServerState
    data object Starting  : BridgeServerState
    data class  Running(val socketName: String) : BridgeServerState
    data object Stopping  : BridgeServerState
}
```

Transitions:

```
              start() success
   Idle ─────────────────────────► Starting ──bind ok──► Running(name)
    ▲                                  │                     │
    │                                  │ bind fail            │
    │ ◄──────────────────────── back to Idle                  │
    │                                                         │
    │                                          stop()         │
    └─────── Idle ◄────── Stopping ◄───────────────────────────
                         (acceptLoop joins,
                          handlers drained)
```

Exposed by `BridgeServer` as `val state: StateFlow<BridgeServerState>`.
Tests subscribe via `state.first { it is Running }` instead of polling
`resolvedSocketName()`.

### 3.2. Synchronization primitive

Replace `@Volatile` fields with a `kotlinx.coroutines.sync.Mutex` plus a
private `MutableStateFlow<BridgeServerState>`:

```kotlin
private val lifecycleMutex = Mutex()
private val _state = MutableStateFlow<BridgeServerState>(BridgeServerState.Idle)
val state: StateFlow<BridgeServerState> = _state.asStateFlow()

private var serverSocket: LocalServerSocket? = null    // guarded by lifecycleMutex
private var acceptJob:    Job?               = null    // guarded by lifecycleMutex
```

The mutex is held only across lifecycle transitions (start/stop). Per-request
handlers do not touch the mutex.

### 3.3. start() flow

```
BridgeServer.start()  (suspend)
│
├─ lifecycleMutex.withLock {
│    │
│    ├─ if (_state.value != Idle) return false   // idempotent
│    │
│    ├─ _state.value = Starting
│    │
│    ├─ for attempt in 0..MaxAttempts-1:
│    │     candidate = negotiator.nextCandidate(...)
│    │     try { socket = socketFactory(candidate); break }
│    │     catch (IOException) { lastError = e; continue }
│    │
│    ├─ if (socket == null) {
│    │     _state.value = Idle
│    │     return false
│    │ }
│    │
│    ├─ serverSocket = socket
│    ├─ acceptJob = scope.launch { acceptLoop(socket) }
│    ├─ _state.value = Running(candidate)
│    └─ return true
│ }
```

Key change: `start()` becomes `suspend fun` so it can acquire the suspending
`Mutex`. `FixThisBridgeRuntime.start()` is invoked from the activity
lifecycle callback which is already main-thread; we wrap the suspending
call in `runBlocking(Dispatchers.IO)` *only* at the runtime boundary so
the rest of the codebase stays suspend-friendly. (The boundary
`runBlocking` is acceptable because runtime start happens exactly once at
debug application launch.)

### 3.4. stop() flow

```
BridgeServer.stop()  (suspend)
│
├─ lifecycleMutex.withLock {
│    │
│    ├─ if (_state.value is Stopping or Idle) return  // idempotent
│    │   // Note: if a re-entrant caller observes Stopping, it returns
│    │   // immediately rather than waiting. Callers that need to await
│    │   // shutdown subscribe to state.first { it is Idle }.
│    │
│    ├─ _state.value = Stopping
│    │
│    ├─ runCatching { serverSocket?.close() }   // unblocks accept()
│    │
│    ├─ withTimeoutOrNull(5.seconds) {
│    │     acceptJob?.cancelAndJoin()           // waits for loop
│    │     scope.coroutineContext[Job]?.cancelAndJoin()  // drains handlers
│    │ } ?: Log.w(tag, "stop() drain timed out; leaking pending handlers")
│    │
│    ├─ serverSocket = null
│    ├─ acceptJob = null
│    │
│    └─ _state.value = Idle
│ }
```

`cancelAndJoin` on the scope's parent `Job` is the suspend-safe equivalent
of "wait for every launched coroutine to finish unwinding." This closes the
zombie-handler window from §1.2.

**Single-use rationale.** Cancelling the scope's parent `Job` makes
`scope` permanently unusable. We deliberately accept this because:

1. In production, `FixThisBridgeRuntime` owns exactly one `BridgeServer`
   per `Application` instance. Restart is never invoked.
2. `stopForTest()` already discards the instance — there is no caller
   that depends on `stop(); start()` working on the same instance.
3. The alternative (rebuild `scope` inside `start()`) would require
   `start()` to be re-entrancy-safe across scopes, introducing a second
   class of races we now avoid.

Tests that previously implied restart capability (T3 from earlier
drafts) are restructured in §5.2 to subscribe to state across a single
lifecycle rather than cycle the same instance.

**Re-entrant cancellation note.** If the caller of `stop()` is itself
cancelled mid-drain, `withTimeoutOrNull` re-throws the cancellation; the
mutex is released by `withLock`, but `_state` is left at `Stopping`. A
subsequent caller observing `Stopping` returns immediately per the
idempotency rule above. This is acceptable for the single-use contract;
the instance is shutting down and will not be restarted.

### 3.5. resolvedSocketName() compatibility

Existing callers (`FixThisBridgeRuntime.start` writes the resolved name to
`session.json`) read `resolvedSocketName()`. We keep it but route through
the state machine:

```kotlin
fun resolvedSocketName(): String? = when (val s = _state.value) {
    is BridgeServerState.Running -> s.socketName
    else -> null
}
```

This is consistent with the contract (`null` before `start()` succeeds or
after `stop()`), and removes the second `@Volatile` field entirely.

### 3.6. FixThisBridgeRuntime adjustments

```
FixThisBridgeRuntime  (was: synchronized(lock) for start)
│
├─ private val mutex = Mutex()
├─ suspend fun startSuspending(...) = mutex.withLock { ... }
├─ suspend fun stopSuspending() = mutex.withLock { ... }
│
├─ // currentActivity is now a MutableStateFlow, not a WeakReference field
├─ private val _currentActivity = MutableStateFlow<WeakReference<Activity>?>(null)
├─ fun onActivityResumed(activity: Activity)  // assigns into the flow
├─ fun onActivityDestroyed(activity: Activity) // CAS on flow value
```

The `Application.isDebuggable() && synchronized(lock) { ... }` short-circuit
becomes a **background-launched** start to avoid blocking the main thread:

```kotlin
fun start(app, callbacks): Boolean {
    if (!app.isDebuggable()) return false
    // Run off the main thread. We use the application's process scope so
    // the start completes even if the launching Activity is destroyed.
    val deferred = ProcessLifecycleOwner.get().lifecycleScope.async(Dispatchers.IO) {
        startSuspending(app, callbacks)
    }
    // Callers that need the result immediately (tests) must use
    // startSuspending(...) directly; production callers fire-and-forget.
    return true.also { /* deferred result is logged by startSuspending */ }
}
```

Production callers of `FixThisBridgeRuntime.start(...)` are
`Application.onCreate` paths (debug only). They do not consume the
returned `Boolean` for control flow — they treat it as a hint for
logging. Tests use the `suspend` variant directly inside `runBlocking`.

**ANR mitigation rationale.** The original sketch wrapped the entire
suspending start in `runBlocking(Dispatchers.IO)` on the **calling**
thread. For tests that is fine (the calling thread is the JUnit runner).
For production, the caller is the main thread inside an Activity
lifecycle callback. `LocalServerSocket.bind()` is typically
sub-millisecond, but a stale-binding retry can take longer; and worse,
if a future change makes `startSuspending` await any other suspending
work (e.g., a `Mutex` already held by `stopSuspending`), the main
thread would block indefinitely → ANR. Launching on a process-scoped
coroutine eliminates this risk while keeping the synchronous Java
signature for callers that only need fire-and-forget semantics.

`stopForTest()` likewise routes through a suspending core:

```kotlin
fun stopForTest() = runBlocking(Dispatchers.IO) { stopSuspending() }
```

`stopForTest()` is invoked **only from test code** which already runs
off the main thread; `runBlocking` is acceptable here.

### 3.7. ASCII concurrency diagram (steady-state)

```
            ┌──────────────────┐
            │  caller thread   │
            └────────┬─────────┘
                     │ start() (suspend)
                     ▼
   ┌────────────────────────────────────────┐
   │      lifecycleMutex.withLock { }       │
   │                                        │
   │   _state: Idle ─► Starting ─► Running  │
   │   serverSocket = bound socket          │
   │   acceptJob   = scope.launch { ... }   │
   └────────────────────────────────────────┘
                     │
                     ▼  (returns; mutex released)
            ┌──────────────────┐
            │   scope (IO)     │
            │                  │   acceptLoop:
            │ ┌──────────────┐ │     while (active) {
            │ │  acceptLoop  │─┼──►    client = socket.accept()
            │ └──────────────┘ │     launch { handleClient(client) }
            │      │           │     }
            │      ▼           │
            │ ┌──────────────┐ │
            │ │ handleClient │ │ N concurrent, no shared mutable state
            │ │ handleClient │ │
            │ │ handleClient │ │
            │ └──────────────┘ │
            └──────────────────┘
                     ▲
                     │ stop() (suspend)
   ┌─────────────────┴──────────────────────┐
   │      lifecycleMutex.withLock { }       │
   │   _state: Running ─► Stopping          │
   │   socket.close()  ─► accept() throws   │
   │   acceptJob.cancelAndJoin()            │
   │   scope.cancelAndJoin()                │
   │   _state: Stopping ─► Idle             │
   └────────────────────────────────────────┘
```

## 4. Migration Strategy

The change is staged for review clarity. **Phases A+B through D form a
single behavioural slice that must merge in order.** Phase E
(`currentActivity` flow) is independent and can land before or after the
bundle. Phase F (docs) lands with Phase D.

1. **Phase A+B (state surface + ignored tests) — bundled commit.** The
   tests in Phase A reference `BridgeServerState` / `state` symbols, so
   they cannot compile without Phase B's scaffold. We therefore land
   them together: introduce the sealed `BridgeServerState`, the
   `MutableStateFlow` backing it, and rewrite `resolvedSocketName()` to
   read from it (Phase B); and add `BridgeServerConcurrencyTest` with
   all three tests under `@Ignore` referencing Phase D for un-ignore
   (Phase A). Existing `@Volatile` fields stay in place so behaviour is
   unchanged and all existing tests still pass. Required visibility
   changes (e.g., `@VisibleForTesting internal val session` on
   `BridgeServer`) also land here with a `grep`-based verify step to
   confirm no production caller depends on the visibility relaxation.

2. **Phase C (suspending lifecycle).** Convert `start()` and `stop()` to
   `suspend fun`. Update `BridgeRuntime` to use
   `ProcessLifecycleOwner.lifecycleScope.launch(Dispatchers.IO)` for
   production start and `runBlocking(Dispatchers.IO)` for
   `stopForTest()` (see §3.6). Add the `Mutex` and use `withLock`.
   Remove `@Volatile` from `serverSocket`.

3. **Phase D (handler drain + budget + un-ignore) — bundled commit.**
   Add `withTimeoutOrNull(5.seconds) { ...cancelAndJoin... }` to
   `stop()`. Un-ignore the concurrency tests; they should now PASS.
   Tighten the architecture hotspot budget for `BridgeServer.kt` from
   `740` down to `260` **in the same commit** so no intermediate state
   where the file has been refactored but the budget is stale exists
   on `main`.

4. **Phase E (Runtime currentActivity flow) — independently mergeable.**
   Replace the `WeakReference` field in
   `FixThisBridgeRuntime.environment` with a
   `MutableStateFlow<WeakReference<Activity>?>`. Update the activity
   lifecycle callbacks to assign atomically. This phase has no
   dependency on A+B–D and can merge before or after the bundle.

5. **Phase F (ADR + docs).** Write
   `docs/architecture/adr/2026-05-14-bridge-server-concurrency.md`
   capturing the decision (Mutex over synchronized; StateFlow over
   Volatile; suspend boundary at BridgeRuntime; single-use after
   `stop()`). Co-merge with Phase D.

**Bisectability.** Phase A+B is purely additive — the new state flow
shadows the retained `@Volatile` fields so behaviour is unchanged and
all existing tests pass; the new concurrency tests are under `@Ignore`
so they compile but do not run. Phase C changes signatures but
preserves call sites via `runBlocking`/`launch` shims. Only Phase D
introduces the race-closing semantics; the concurrency tests move from
`@Ignore` → green in the same commit. `git bisect` between phases
works because each phase keeps the full test suite green.

**Rollback.** If Phase D regresses, revert *only* Phase D; A+B and C
remain valid (the tests fall back under `@Ignore`). If A+B through D
collectively must revert, revert in reverse order. Phase E reverts in
isolation. The merge gate for the bundle is: all phases pass
`./gradlew check` and
`./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"`.

## 5. Test Strategy

### 5.1. Existing tests must continue to pass

- `BridgeServerTest`
- `BridgeServerStartupTest`
- `BridgeServerScreenshotPathTest`
- `BridgeStatusAvailabilityTest`
- `BridgeStatusInstallEpochTest`
- `BridgeConnectionStateTest`

None of these exercise concurrent start/stop today. They should be
re-runnable verbatim against the new API because `start()` returning
`Boolean` and `stop()` returning `Unit` is preserved (the new `suspend`
modifier is source-compatible from `runBlocking` test callers).

### 5.2. New concurrency tests

`BridgeServerConcurrencyTest` covers:

- **T1 — concurrent start is idempotent.** Launch 8 `start()` callers
  concurrently via `async`. Exactly one returns `true`; seven return
  `false`. `state.first { it is Running }` resolves to a single
  `Running(name)` value. `socketFactory` was invoked at most
  `MaxAttempts` times.

- **T2 — stop awaits in-flight handlers (socket path).** Drive a
  request **through a real `LocalSocket` client**, not via
  `handleRequestForTest`. Inject a `BridgeEnvironment` whose `status()`
  suspends on a `CompletableDeferred`. Open a client socket, write a
  status request, observe (via the deferred awaiter) that the handler
  has entered `status()`, then call `stop()`. Assert that `stop()` does
  not return until the deferred is completed *and* the handler's
  response write has finished. Use `withTimeout(5.seconds)` so a
  regression manifests as a test timeout, not a hang.

  *Why not `handleRequestForTest`?* That entry point bypasses the
  socket and `acceptLoop.launch { handleClient(...) }`. The §1.2
  zombie-handler race lives in the *socket* path; a `handleRequestForTest`
  drive would prove only that suspending handlers join their parent
  scope, which the Kotlin coroutines runtime already guarantees.

  **Test location — Robolectric vs androidTest.** Under Robolectric
  the test harness used by `:fixthis-compose-sidekick:testDebugUnitTest`,
  `android.net.LocalServerSocket` cannot be bound natively (its native
  `bind()` fails; the existing `BridgeServerStartupTest` works around
  this by allocating an empty `LocalServerSocket` via
  `Unsafe.allocateInstance`, which cannot serve real client
  connections). Consequently T2's real-socket drive is **not viable
  under Robolectric** and must run as an instrumented test under
  `:fixthis-compose-sidekick:connectedAndroidTest` against an
  emulator/device. The mutex + bounded-drain implementation introduced
  by Task 3 is invariant-correct independent of the test harness;
  T2's Robolectric placeholder is kept under `@Ignore("Reproduces
  §1.2 zombie-handler race; LocalSocket cannot bind under Robolectric.
  Move to androidTest/ in a follow-up — covered by Task 6 stress harness
  in the meantime.")` so the test body documents the design intent in
  the same file as T1/T3. §1.2 invariant coverage in Phase D = the
  bounded-drain log path plus Task 6's stress harness exercising
  start/stop cycles concurrent with request issuance via the
  injectable `socketFactory`.

- **T3 — observed state is consistent across a single lifecycle.**
  Construct one `BridgeServer`. Launch a collector that records every
  `state` emission into a list. From the main coroutine, run
  `start() ; stop()`. After both complete, assert:
  - First emission is `Idle`.
  - The sequence contains at least one `Running(name)` with non-blank
    `name`.
  - Last emission is `Idle`.
  - No `Running` emission has a blank socket name.
  - No two adjacent emissions are illegal: `Idle→Idle` and
    `Running→Running` are forbidden; `Running→Starting` is forbidden.

  This is a single-lifecycle assertion only — `BridgeServer` is
  single-use (G2a), so cross-lifecycle observations are tested by
  constructing a *new* server per lifecycle and aggregating the
  per-lifecycle assertions.

  **Conflation note.** `StateFlow` is conflated; if a producer
  transitions `Idle→Starting→Running` faster than the collector
  consumes, the collector may observe only `Idle→Running`. The
  assertion above tolerates this — it requires that **observed**
  emissions are consistent, not that every transition is observed.
  Tests asserting *every* transition fires must use a `Channel` or
  emit transitions to a debug sink in test builds; that is out of
  scope here.

- **T4 — `resolvedSocketName` agrees with `state`.** For every
  observed emission, assert
  `resolvedSocketName() == (state as? Running)?.socketName`. This is
  the same idea as the prior "monotonic" assertion but stated as a
  point-in-time invariant which is robust to conflation.

- **T5 — BridgeRuntime double-start is idempotent.** Two threads
  call `FixThisBridgeRuntime.start(application, callbacks)`
  simultaneously. Exactly one returns `true`. The persisted
  `session.json` is written exactly once.

### 5.3. Stress harness

`BridgeServerConcurrencyStressTest` (only on `:fixthis-compose-sidekick:
testDebugUnitTest`, no Robolectric required because we use the
injectable `socketFactory`) runs `start/stop` in a tight loop for two
seconds while a second coroutine fires 200 status requests. Asserts that
either every request succeeds with a `Running` response, or fails with
`UNAUTHORIZED` / `BAD_REQUEST` (because the socket closed mid-write).
No `IllegalStateException`, no `NullPointerException`, no zombie writes.

### 5.4. What we explicitly do not test

- Real Android emulator concurrent device connections (out of scope;
  the negotiator handles cross-process collisions and is already
  covered by `BridgeServerStartupTest`).
- `kotlinx.coroutines` internals (Mutex fairness, StateFlow conflation
  guarantees). We rely on the documented contract.

## 6. Open Risks

### 6.1. ANR risk from `runBlocking` (CLOSED)

The original draft of this spec proposed `runBlocking(Dispatchers.IO)`
on the main thread. That risk is now closed by §3.6: production
`FixThisBridgeRuntime.start(...)` uses
`ProcessLifecycleOwner.lifecycleScope.async(Dispatchers.IO)` instead.
`runBlocking` is reserved for test-only paths (`stopForTest`) which
never execute on the main thread.

If a future change ever reintroduces `runBlocking` on the main thread,
the lint/style review must reject it; we add an
`ArchitectureNoMainThreadBlockingTest` to the verify checklist that
greps for `runBlocking(Dispatchers.` outside `*Test.kt` /
`*ForTest.kt` files in `:fixthis-compose-sidekick`.

### 6.2. `scope.cancelAndJoin()` blocks indefinitely if a handler hangs

If a `BridgeEnvironment.inspectCurrentScreen()` implementation hangs
(e.g., the AccessibilityService is deadlocked), `stop()` will wait
forever. Today, `stop()` returns immediately and lets the hang play out
in the background. The new behaviour is more correct (no zombie writes
to closed sockets) but trades that for "stop() can hang."

Mitigation: wrap `cancelAndJoin` in `withTimeoutOrNull(5.seconds)`. If
it times out, log loudly and let the handler leak — same outcome as
today, but with diagnostic visibility.

### 6.3. StateFlow back-pressure from external subscribers

Adding a public `state: StateFlow<BridgeServerState>` lets future
console/UI code subscribe. `StateFlow` is conflated, so slow consumers
miss intermediate `Starting`/`Stopping` ticks. That is the documented
behaviour and matches our intent (consumers see "current" state, not a
log of every transition).

This conflation interacts with §5.2 T3/T4. The test design explicitly
tolerates conflation: assertions check that **observed** emissions form
a legal subsequence, not that every transition is observed. Tests
asserting every transition fires (none currently planned) would need
a separate debug `SharedFlow` sink — out of scope here.

### 6.4. Interaction with `BridgeSocketNameNegotiator.MaxAttempts`

The negotiator caps retries at 3. Under the new lock, two concurrent
start callers no longer "double-attempt"; the second observes `Starting`
or `Running` and returns false. This *reduces* total bind attempts in
the racing case, which is correct but means a very narrow window where
the original code might have raced into a successful retry is now
gone. Acceptable because the retry was incidental, not designed.

### 6.5. Breaking the `internal fun handleRequestForTest`

The internal test entry point currently sidesteps the socket entirely.
It does not interact with lifecycle state, so it continues to work
without changes. **It is NOT used to reproduce the §1.2 zombie-handler
race** — T2 explicitly drives the socket path (see §5.2).

### 6.6. Test fixture visibility

T1's `bindCount` counting fixture and T2's socket-driven test both
need to construct `BridgeServer` directly and inspect
`server.session.token`. The current `BridgeServer.session` field is
`private`. Phase A adds `@VisibleForTesting internal val session` (or
exposes a `session.token` accessor) **with a justification comment**
and a `grep`-based verify step to ensure no production code outside
the package reads `session` directly.

### 6.7. `BridgeProtocol.VERSION` sync invariant

Per CLAUDE.md, `BridgeProtocol.VERSION` is mirrored across four sites
and enforced by `BridgeProtocolVersionSyncTest` in `:fixthis-mcp:test`.
This concurrency change **does not** modify the wire protocol (see
NG2). Every phase's verify checklist therefore includes:

```bash
./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"
```

to guard against accidental drift introduced by adjacent edits.

### 6.6. Test flakiness from real time

T2 uses a `CompletableDeferred` to control timing rather than `sleep`.
T3/T4 use Job join, not wall-clock. We avoid all `Thread.sleep` and
`delay(N.milliseconds)` waits. Each test must complete within
`withTimeout(5.seconds)`; longer is a regression.

## 7. References

- [`BridgeServer.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt) — current implementation (195 lines, post-split)
- [`BridgeRuntime.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt) — `FixThisBridgeRuntime` singleton (74 lines)
- [`BridgeConnectionState.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeConnectionState.kt) — heartbeat freshness tracker (22 lines)
- [`BridgeSocketNameNegotiator.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeSocketNameNegotiator.kt) — bind retry policy (30 lines)
- [`BridgeServerTest.kt`](../../../fixthis-compose-sidekick/src/test/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt) — request-handling coverage
- [`BridgeServerStartupTest.kt`](../../../fixthis-compose-sidekick/src/test/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerStartupTest.kt) — bind-retry coverage
- [`2026-05-13-architecture-solid-remediation-detailed-spec.md`](2026-05-13-architecture-solid-remediation-detailed-spec.md) §F3 — the structural split that preceded this concurrency fix
- [`2026-05-13-architecture-solid-remediation-implementation.md`](../plans/2026-05-13-architecture-solid-remediation-implementation.md) Task 6 — landed via commit `c6c0524`, merged in `a6abe8a`
- [`docs/reference/bridge-protocol.md`](../../reference/bridge-protocol.md) — bridge wire contract (unchanged by this design)
- Kotlin Coroutines guide — [Mutex](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html#mutex), [StateFlow](https://kotlinlang.org/docs/flow.html#stateflow)
