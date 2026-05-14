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
([`fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt))
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
([`BridgeRuntime.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt))
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
  server's scope to terminate before returning. `stop(); start()` is safe
  with no zombie handler windows.
- **G3.** Lifecycle state is observable via a typed `StateFlow` for tests
  and future console/UI consumers, without exposing internal sockets or
  scopes.
- **G4.** `BridgeRuntime`'s informal Java `synchronized` lock is replaced
  by a coroutine-friendly `Mutex` so that `start()` (which performs a
  potentially-blocking `LocalServerSocket` bind) does not pin a thread that
  cannot suspend.
- **G5.** A new test class `BridgeServerConcurrencyTest` reproduces the
  three races in §1 and demonstrates they no longer occur after the fix.
- **G6.** Public API of `BridgeServer` and `FixThisBridgeRuntime` is
  preserved for existing callers — adding `state(): StateFlow<...>` is
  additive only.

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
│    ├─ if (_state.value == Idle) return     // idempotent
│    │
│    ├─ _state.value = Stopping
│    │
│    ├─ runCatching { serverSocket?.close() }   // unblocks accept()
│    │
│    ├─ acceptJob?.cancelAndJoin()              // waits for loop
│    │
│    ├─ // Drain in-flight handlers: cancel the scope and join its job
│    ├─ scope.coroutineContext[Job]?.cancelAndJoin()
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
├─ suspend fun start(...) = mutex.withLock { ... }
├─ suspend fun stopForTest() = mutex.withLock { ... }
│
├─ // currentActivity is now a MutableStateFlow, not a WeakReference field
├─ private val _currentActivity = MutableStateFlow<WeakReference<Activity>?>(null)
├─ fun onActivityResumed(activity: Activity)  // assigns into the flow
├─ fun onActivityDestroyed(activity: Activity) // CAS on flow value
```

The `Application.isDebuggable() && synchronized(lock) { ... }` short-circuit
becomes:

```kotlin
fun start(app, callbacks): Boolean {
    if (!app.isDebuggable()) return false
    return runBlocking(Dispatchers.IO) { startSuspending(app, callbacks) }
}
```

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

The change is staged to keep every commit shippable:

1. **Phase A (test infrastructure).** Add `BridgeServerConcurrencyTest`
   that reproduces the three races. These tests will FAIL on `main`. They
   are committed under `@Ignore` initially so CI stays green; an
   accompanying `BridgeServerConcurrencyContractTest` documents the
   *intended* contract assertions and runs from day one.

2. **Phase B (state surface).** Introduce the sealed
   `BridgeServerState` and a `MutableStateFlow` backing it. Keep the
   existing `@Volatile` fields wired up so behaviour is unchanged.
   `resolvedSocketName()` now reads from the state flow. All existing
   tests must still pass.

3. **Phase C (suspending lifecycle).** Convert `start()` and `stop()` to
   `suspend fun`. Update `BridgeRuntime` to wrap them in
   `runBlocking(Dispatchers.IO)` at the singleton boundary. Add the
   `Mutex` and use `withLock`. Remove `@Volatile` from `serverSocket`.

4. **Phase D (handler drain).** Add `scope.coroutineContext[Job]
   ?.cancelAndJoin()` to `stop()`. Un-ignore the concurrency tests; they
   should now PASS. Tighten the architecture hotspot budget for
   `BridgeServer.kt` from `740` down to `260`.

5. **Phase E (Runtime currentActivity flow).** Replace the
   `WeakReference` field in `FixThisBridgeRuntime.environment` with a
   `MutableStateFlow<WeakReference<Activity>?>`. Update the activity
   lifecycle callbacks to assign atomically.

6. **Phase F (ADR + docs).** Write
   `docs/architecture/adr/2026-05-14-bridge-server-concurrency.md`
   capturing the decision (Mutex over synchronized; StateFlow over
   Volatile; suspend boundary at BridgeRuntime).

Each phase is one commit. Phase A can land independently; B–E are a
single behavioural slice.

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

- **T2 — stop awaits in-flight handlers.** Inject a `BridgeEnvironment`
  whose `status()` suspends on a `CompletableDeferred`. Send a request,
  observe it enter `status()`, then call `stop()`. Assert that `stop()`
  does not return until the deferred is completed and the request
  handler finishes. Use a `withTimeoutOrNull(2.seconds)` guard so a
  regression manifests as a test timeout, not a hang.

- **T3 — start-stop-start re-binds cleanly.** Run 100 iterations of
  `start() ; stop() ; start() ; stop()` from a single coroutine.
  Concurrently, a second coroutine reads `state.value` continually.
  Assert no observed state is illegal (e.g., `Running` with an empty
  socket name; `Idle` followed by `Running` without a `Starting`
  in between when subscribed via `state.toList()`).

- **T4 — resolvedSocketName is monotonic within a generation.**
  Between any two `Running(a)` and `Running(b)` observations, there
  must be at least one non-`Running` state. Asserted by collecting
  the state flow into a list and walking adjacent pairs.

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

### 6.1. `runBlocking` at the runtime boundary

`FixThisBridgeRuntime.start()` is called from `Application.onCreate`
indirectly via the lifecycle callbacks. `runBlocking(Dispatchers.IO)`
on the main thread for the duration of a `LocalServerSocket.bind()`
plus up to two retry attempts is **at most ~50ms** in practice (Linux
abstract-namespace bind is sub-millisecond when the name is free), but
the retries with a stale binding can take longer if the kernel's
internal cleanup is slow.

Mitigation: the runtime start is gated by `isDebuggable()`. Production
APKs never hit this path. If the wait becomes a problem, we can promote
the entire runtime start to be invoked from a background coroutine
launched off the application's `ProcessLifecycleOwner` scope.

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
log of every transition). Tests that walk transitions must
use `MutableStateFlow.collect` with a fast collector, or collect via
`state.take(N)` driven by signal coroutines.

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
without changes.

### 6.6. Test flakiness from real time

T2 uses a `CompletableDeferred` to control timing rather than `sleep`.
T3/T4 use Job join, not wall-clock. We avoid all `Thread.sleep` and
`delay(N.milliseconds)` waits. Each test must complete within
`withTimeout(5.seconds)`; longer is a regression.

## 7. References

- [`BridgeServer.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt) — current implementation (195 lines, post-split)
- [`BridgeRuntime.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt) — `FixThisBridgeRuntime` singleton (74 lines)
- [`BridgeConnectionState.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeConnectionState.kt) — heartbeat freshness tracker (22 lines)
- [`BridgeSocketNameNegotiator.kt`](../../../fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeSocketNameNegotiator.kt) — bind retry policy (30 lines)
- [`BridgeServerTest.kt`](../../../fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt) — request-handling coverage
- [`BridgeServerStartupTest.kt`](../../../fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerStartupTest.kt) — bind-retry coverage
- [`2026-05-13-architecture-solid-remediation-detailed-spec.md`](2026-05-13-architecture-solid-remediation-detailed-spec.md) §F3 — the structural split that preceded this concurrency fix
- [`2026-05-13-architecture-solid-remediation-implementation.md`](../plans/2026-05-13-architecture-solid-remediation-implementation.md) Task 6 — landed via commit `c6c0524`, merged in `a6abe8a`
- [`docs/reference/bridge-protocol.md`](../../reference/bridge-protocol.md) — bridge wire contract (unchanged by this design)
- Kotlin Coroutines guide — [Mutex](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html#mutex), [StateFlow](https://kotlinlang.org/docs/flow.html#stateflow)
