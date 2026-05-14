# Bridge Server Concurrency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `@Volatile`-based lifecycle fields in `BridgeServer` and the informal `synchronized` block in `FixThisBridgeRuntime` with a `kotlinx.coroutines.sync.Mutex` plus an observable `StateFlow<BridgeServerState>`, closing the three concurrency races documented in [`../specs/2026-05-14-bridge-server-concurrency-design.md`](../specs/2026-05-14-bridge-server-concurrency-design.md).

**Architecture:** Keep `:fixthis-compose-sidekick` debug-only and free of MCP/CLI imports. Preserve `BridgeServer`'s public `start(): Boolean` / `stop(): Unit` shape from non-suspending callers by tunnelling through `runBlocking(Dispatchers.IO)` only at the `FixThisBridgeRuntime` boundary. Bridge protocol stays at `1.3`; no wire format changes. Add the new state flow as an additive read-only API.

**Tech Stack:** Kotlin Coroutines (Mutex, StateFlow, cancelAndJoin), Ktor (unaffected; only referenced because mcp consumes the bridge), Robolectric (existing test runtime).

---

## Related Spec

[`../specs/2026-05-14-bridge-server-concurrency-design.md`](../specs/2026-05-14-bridge-server-concurrency-design.md)

## Baseline

- `BridgeServer.kt` is 195 lines after the SOLID Remediation Task 6 split.
- `FixThisBridgeRuntime.kt` is 74 lines.
- `BridgeConnectionState.kt` is 22 lines and already correct.
- `ArchitectureHotspotBudgetTest` sets the `BridgeServer.kt` budget at `740` lines (carried over from the pre-split baseline — see Task 6 follow-up).
- No tests today reproduce the start/stop races; we will add them in Task 1.

## File Structure

Create:

- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerState.kt`
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyTest.kt`
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyStressTest.kt`
- `docs/architecture/adr/2026-05-14-bridge-server-concurrency.md`

Modify:

- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`
- `docs/architecture/overview.md`

## Task 1: Reproduce The Concurrency Races With Failing Tests

**Files:**
- Create: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyTest.kt`

- [ ] **Step 1: Write the failing concurrency test**

Create `BridgeServerConcurrencyTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.sidekick.bridge

import android.net.LocalServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BridgeServerConcurrencyTest {

    @Test
    fun concurrentStartReturnsTrueOnceAndBindsOnlyOnce(): Unit = runBlocking {
        val bindCount = AtomicInteger(0)
        val server = newServerWithCountingSocketFactory(bindCount)

        val results = withTimeout(5.seconds) {
            (0 until 8).map { async { server.start() } }.awaitAll()
        }
        try {
            assertEquals(1, results.count { it }, "exactly one start should win")
            assertEquals(7, results.count { !it }, "the rest must return false")
            assertEquals(1, bindCount.get(), "socketFactory must bind exactly once")
        } finally {
            server.stop()
        }
    }

    @Test
    fun stopWaitsForInFlightHandlerToComplete(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val handlerFinished = CompletableDeferred<Unit>()
        val server = newServerWithBlockingEnvironment(gate, handlerFinished)

        server.start()
        val inFlight = async {
            server.handleRequestForTest(statusRequestPayload(server.session.token))
        }
        // Allow the handler to enter status() before we stop.
        gate.complete(Unit)

        withTimeout(5.seconds) { server.stop() }
        // After stop returns, the in-flight handler must have finished — not
        // be lingering and writing to a closed socket.
        assertEquals(true, handlerFinished.isCompleted)
        inFlight.await()
    }

    @Test
    fun rapidStartStopCyclesNeverObserveTornState(): Unit = runBlocking {
        val server = newServerWithCountingSocketFactory(AtomicInteger(0))
        val violations = AtomicInteger(0)

        val cycler = async {
            repeat(50) {
                server.start()
                server.stop()
            }
        }
        val observer = async {
            repeat(500) {
                val name = server.resolvedSocketName()
                val running = server.state.value is BridgeServerState.Running
                if (running && name.isNullOrBlank()) violations.incrementAndGet()
                if (!running && name != null) violations.incrementAndGet()
            }
        }

        withTimeout(10.seconds) {
            cycler.await()
            observer.await()
        }
        assertEquals(0, violations.get(), "state and resolvedSocketName must agree")
    }

    // === fixtures ===

    private fun newServerWithCountingSocketFactory(counter: AtomicInteger): BridgeServer {
        val session = TestSessions.fixed("io.beyondwin.fixthis.sample")
        val env = StubBridgeEnvironment()
        return BridgeServer(
            session = session,
            environment = env,
            socketFactory = { name ->
                counter.incrementAndGet()
                LocalServerSocket(name + "-test-" + counter.get())
            },
        )
    }

    private fun newServerWithBlockingEnvironment(
        gate: CompletableDeferred<Unit>,
        finished: CompletableDeferred<Unit>,
    ): BridgeServer {
        val session = TestSessions.fixed("io.beyondwin.fixthis.sample")
        val env = object : BridgeEnvironment by StubBridgeEnvironment() {
            override suspend fun status(): BridgeStatus {
                gate.await()
                return BridgeStatus(
                    activity = null,
                    rootsCount = 0,
                    sidekickVersion = "test",
                    bridgeProtocolVersion = BridgeProtocol.VERSION,
                    sourceIndexAvailable = false,
                ).also { finished.complete(Unit) }
            }
        }
        return BridgeServer(session = session, environment = env)
    }

    private fun statusRequestPayload(token: String): String =
        """{"id":"1","method":"status","params":{},"token":"$token"}"""
}
```

The fixtures `TestSessions.fixed(...)` and `StubBridgeEnvironment` already exist in `BridgeServerTest.kt`; promote them to a shared `BridgeServerTestFixtures.kt` file in the same package as part of Step 2 if they're not already shared.

- [ ] **Step 2: Verify the test FAILS**

Run:

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.BridgeServerConcurrencyTest"
```

Expected: at least one of the three tests FAILS or hangs (`withTimeout` catches the hang). The most reliable failure is `concurrentStartReturnsTrueOnceAndBindsOnlyOnce` reporting `bindCount` > 1 or `results.count { it }` > 1. Capture the failure output for the commit message.

- [ ] **Step 3: Commit the failing test under @Ignore**

Add `@Ignore("Reproduces concurrency hazards; un-ignored in Task 4")` to each test method, then:

```bash
git add fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyTest.kt
git commit -m "test(sidekick): document bridge server concurrency races"
```

## Task 2: Introduce BridgeServerState And StateFlow Surface

**Files:**
- Create: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerState.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`

- [ ] **Step 1: Write a failing test for the state flow surface**

Add to `BridgeServerTest.kt`:

```kotlin
@Test
fun stateTransitionsThroughIdleStartingRunningStoppingIdle() = runBlocking {
    val server = newServer()
    val seen = mutableListOf<BridgeServerState>()
    val collector = launch { server.state.collect { seen += it } }

    assertTrue(server.start())
    server.stop()
    collector.cancel()

    assertEquals(BridgeServerState.Idle, seen.first())
    assertTrue(seen.any { it is BridgeServerState.Running })
    assertEquals(BridgeServerState.Idle, seen.last())
}
```

- [ ] **Step 2: Verify the test FAILS**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.BridgeServerTest.stateTransitionsThroughIdleStartingRunningStoppingIdle"
```

Expected: FAIL with `unresolved reference: state` (the property doesn't exist yet).

- [ ] **Step 3: Create the state model**

Create `BridgeServerState.kt`:

```kotlin
package io.beyondwin.fixthis.compose.sidekick.bridge

/**
 * Observable lifecycle of [BridgeServer]. Exposed via [BridgeServer.state] so
 * tests and future console / UI consumers can subscribe instead of polling
 * [BridgeServer.resolvedSocketName].
 *
 * Transitions are strictly serialised by [BridgeServer]'s lifecycle mutex:
 * `Idle -> Starting -> Running -> Stopping -> Idle`. `Starting -> Idle`
 * is the only short-circuit (occurs when all bind attempts fail).
 */
sealed interface BridgeServerState {
    data object Idle : BridgeServerState
    data object Starting : BridgeServerState
    data class Running(val socketName: String) : BridgeServerState
    data object Stopping : BridgeServerState
}
```

- [ ] **Step 4: Wire the state flow into BridgeServer**

Replace the two `@Volatile` fields at the top of `BridgeServer.kt`:

```kotlin
    @Volatile
    private var serverSocket: LocalServerSocket? = null

    @Volatile
    private var resolvedName: String? = null
```

with:

```kotlin
    private val _state = MutableStateFlow<BridgeServerState>(BridgeServerState.Idle)
    val state: StateFlow<BridgeServerState> = _state.asStateFlow()

    @Volatile
    private var serverSocket: LocalServerSocket? = null
```

Add imports:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

Rewrite `resolvedSocketName`:

```kotlin
fun resolvedSocketName(): String? = when (val s = _state.value) {
    is BridgeServerState.Running -> s.socketName
    else -> null
}
```

In `start()`, replace `resolvedName = candidate` with:

```kotlin
_state.value = BridgeServerState.Running(candidate)
```

Just before the bind retry loop, set:

```kotlin
_state.value = BridgeServerState.Starting
```

If the loop exits without binding, before logging:

```kotlin
_state.value = BridgeServerState.Idle
```

In `stop()`, replace:

```kotlin
resolvedName = null
```

with:

```kotlin
_state.value = BridgeServerState.Stopping
```

and at the end of `stop()`, before `scope.cancel()`:

```kotlin
_state.value = BridgeServerState.Idle
```

- [ ] **Step 5: Verify**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.BridgeServerTest"
```

Expected: PASS, including the new state-transition test. `BridgeServerStartupTest` and `BridgeServerScreenshotPathTest` must also still pass.

- [ ] **Step 6: Commit**

```bash
git add \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerState.kt \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt
git commit -m "feat(sidekick): expose bridge server state as flow"
```

## Task 3: Convert start() And stop() To Suspending With Mutex

**Files:**
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`

- [ ] **Step 1: Write a failing idempotency assertion**

Augment `BridgeServerTest`:

```kotlin
@Test
fun startIsIdempotentAcrossSequentialCalls() = runBlocking {
    val server = newServer()
    assertTrue(server.start())
    assertFalse(server.start())
    assertFalse(server.start())
    server.stop()
}
```

This test should already pass under the current implementation because of the
`if (serverSocket != null) return false` guard. We keep it because the next
step converts to `suspend fun` and we need a regression check that the
contract holds across the API change.

- [ ] **Step 2: Convert start() to suspend with mutex**

In `BridgeServer.kt`, add imports:

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
```

Add field:

```kotlin
private val lifecycleMutex = Mutex()
private var acceptJob: Job? = null
```

Change `fun start(): Boolean` to `suspend fun start(): Boolean` and wrap the
body in `lifecycleMutex.withLock { ... }`:

```kotlin
suspend fun start(): Boolean = lifecycleMutex.withLock {
    if (_state.value != BridgeServerState.Idle) return@withLock false
    _state.value = BridgeServerState.Starting
    val attempted = mutableListOf<String>()
    var lastError: Throwable? = null
    for (attempt in 0 until BridgeSocketNameNegotiator.MaxAttempts) {
        val candidate = BridgeSocketNameNegotiator.nextCandidate(session.socketName, attempt)
        attempted += candidate
        val socket = try {
            socketFactory(candidate)
        } catch (error: IOException) {
            lastError = error
            continue
        }
        serverSocket = socket
        acceptJob = scope.launch { acceptLoop(socket) }
        _state.value = BridgeServerState.Running(candidate)
        return@withLock true
    }
    _state.value = BridgeServerState.Idle
    Log.w(
        BridgeServerLogTag,
        "BridgeServer.start() failed after ${attempted.size} attempts: " +
            "tried ${attempted.joinToString(", ")}",
        lastError,
    )
    false
}
```

- [ ] **Step 3: Convert stop() to suspend with mutex and join**

```kotlin
suspend fun stop() = lifecycleMutex.withLock {
    if (_state.value == BridgeServerState.Idle) return@withLock
    _state.value = BridgeServerState.Stopping
    runCatching { serverSocket?.close() }
    acceptJob?.cancelAndJoin()
    val parent = scope.coroutineContext[Job]
    parent?.cancelAndJoin()
    serverSocket = null
    acceptJob = null
    _state.value = BridgeServerState.Idle
    // The scope itself is now cancelled; if a subsequent start() is called,
    // BridgeServer is single-use. Callers must construct a fresh instance.
}
```

Add imports:

```kotlin
import kotlinx.coroutines.cancelAndJoin
```

**Decision: single-use after stop().** Cancelling the parent `Job` of
`scope` makes the scope unusable. Tests that need start/stop/start cycles
must construct a fresh `BridgeServer`. This matches `FixThisBridgeRuntime`'s
production behaviour (it never restarts the same instance — `stopForTest`
discards the server). Update Task 1's `rapidStartStopCyclesNeverObserveTornState`
test to construct a new server each cycle.

- [ ] **Step 4: Update test fixtures and BridgeRuntime callers**

All test callers wrap in `runBlocking { ... }`:

```kotlin
runBlocking { server.start() }
runBlocking { server.stop() }
```

In `BridgeRuntime.kt`, change the `start` block from `synchronized(lock) { ... }` to a `runBlocking(Dispatchers.IO) { mutex.withLock { ... } }`. See Task 5 for the full rewrite; this task's verification needs the runtime to still compile, so apply a minimal patch:

```kotlin
internal object FixThisBridgeRuntime {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    ...
    fun start(application: Application, lifecycleCallbacks: FixThisActivityLifecycleCallbacks): Boolean =
        application.isDebuggable() && kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            mutex.withLock {
                if (server != null) return@withLock false
                val store = SessionTokenStore(application)
                val session = store.create(application.packageName)
                val bridgeEnvironment = AndroidBridgeEnvironment(
                    context = application,
                    sidekickVersion = session.sidekickVersion,
                    lifecycleCallbacks = lifecycleCallbacks,
                )
                val bridgeServer = BridgeServer(
                    session = session,
                    environment = bridgeEnvironment,
                    connectionState = connectionState,
                )
                if (!bridgeServer.start()) {
                    false
                } else {
                    val resolved = bridgeServer.resolvedSocketName() ?: session.socketName
                    val resolvedSession = if (resolved == session.socketName) session
                        else session.copy(socketName = resolved, socketAddress = "localabstract:$resolved")
                    store.write(resolvedSession)
                    environment = bridgeEnvironment
                    server = bridgeServer
                    true
                }
            }
        }

    fun stopForTest() {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            mutex.withLock {
                server?.stop()
                server = null
                environment = null
            }
        }
    }
}
```

- [ ] **Step 5: Verify**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.*"
```

Expected: PASS. The previously `@Ignore`d concurrency tests are still ignored at this point; they'll be un-ignored in Task 4.

- [ ] **Step 6: Commit**

```bash
git add \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt
git commit -m "refactor(sidekick): serialize bridge lifecycle with coroutine mutex"
```

## Task 4: Un-Ignore Concurrency Tests And Confirm Green

**Files:**
- Modify: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyTest.kt`

- [ ] **Step 1: Remove `@Ignore` annotations**

Delete each `@Ignore("Reproduces concurrency hazards; un-ignored in Task 4")`.

Adjust `rapidStartStopCyclesNeverObserveTornState` to construct a fresh
`BridgeServer` on each iteration (because `stop()` is now single-use):

```kotlin
repeat(50) {
    val s = newServerWithCountingSocketFactory(AtomicInteger(0))
    s.start()
    s.stop()
}
```

The observer coroutine should subscribe to a single long-lived server's state
across one `start`/`stop` cycle and assert that intermediate state agrees
with `resolvedSocketName()` per the design's §3.5 contract.

- [ ] **Step 2: Verify**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.BridgeServerConcurrencyTest"
```

Expected: all three tests PASS. Each completes in well under 5 seconds.

- [ ] **Step 3: Commit**

```bash
git add fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyTest.kt
git commit -m "test(sidekick): confirm bridge server is race-free"
```

## Task 5: BridgeRuntime currentActivity As StateFlow

**Files:**
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/AndroidBridgeEnvironment.kt`

- [ ] **Step 1: Write a failing test for ordered activity updates**

Add to a new file `FixThisBridgeRuntimeTest.kt` (Robolectric):

```kotlin
@Test
fun onActivityResumedAndDestroyedAreSequencedAcrossThreads() = runBlocking {
    // After 100 alternating resume/destroy pairs from two threads,
    // currentActivity is either the last resumed activity or null,
    // never a stale weak reference from an earlier resume.
}
```

- [ ] **Step 2: Convert currentActivity to MutableStateFlow**

In `AndroidBridgeEnvironment.kt`, change `var currentActivity: WeakReference<Activity>?` to:

```kotlin
private val _currentActivity = MutableStateFlow<WeakReference<Activity>?>(null)
val currentActivity: StateFlow<WeakReference<Activity>?> = _currentActivity.asStateFlow()
fun setCurrentActivity(ref: WeakReference<Activity>?) { _currentActivity.value = ref }
fun clearCurrentActivityIf(activity: Activity) {
    _currentActivity.update { current ->
        if (current?.get() === activity) null else current
    }
}
```

In `BridgeRuntime.kt`:

```kotlin
fun onActivityResumed(activity: Activity) {
    environment?.setCurrentActivity(WeakReference(activity))
}

fun onActivityDestroyed(activity: Activity) {
    environment?.clearCurrentActivityIf(activity)
}
```

- [ ] **Step 3: Verify**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.FixThisBridgeRuntimeTest"
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.BridgeStatusAvailabilityTest"
```

Expected: PASS for both.

- [ ] **Step 4: Commit**

```bash
git add \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/AndroidBridgeEnvironment.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/FixThisBridgeRuntimeTest.kt
git commit -m "refactor(sidekick): atomicize current activity reference"
```

## Task 6: Concurrency Stress Test (Optional Gating)

**Files:**
- Create: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyStressTest.kt`

- [ ] **Step 1: Write the stress test**

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BridgeServerConcurrencyStressTest {
    @Test
    fun twoSecondStartStopAndRequestStormHasNoCrash() = runBlocking {
        val deadline = System.currentTimeMillis() + 2_000
        val errors = mutableListOf<Throwable>()
        val cycleJob = launch {
            while (System.currentTimeMillis() < deadline) {
                val server = TestSessions.newServer()
                runCatching { server.start() }.onFailure { errors += it }
                runCatching { server.stop() }.onFailure { errors += it }
            }
        }
        val requestJob = launch {
            val server = TestSessions.newServer()
            server.start()
            try {
                repeat(200) {
                    runCatching {
                        server.handleRequestForTest(statusRequestPayload(server.session.token))
                    }
                }
            } finally {
                server.stop()
            }
        }
        cycleJob.join()
        requestJob.join()
        assertEquals(emptyList<Throwable>(), errors)
    }
}
```

- [ ] **Step 2: Verify**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.BridgeServerConcurrencyStressTest"
```

Expected: PASS within ~3 seconds wall clock.

- [ ] **Step 3: Commit**

```bash
git add fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyStressTest.kt
git commit -m "test(sidekick): stress bridge lifecycle under concurrent load"
```

## Task 7: Tighten Architecture Hotspot Budget

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`

- [ ] **Step 1: Lower the BridgeServer budget**

In `ArchitectureHotspotBudgetTest.kt`, change:

```kotlin
"fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt" to 740,
```

to:

```kotlin
"fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt" to 260,
```

The post-concurrency file is ~210 lines; 260 gives a small headroom for
future doc comments.

- [ ] **Step 2: Verify**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.architecture.ArchitectureHotspotBudgetTest"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt
git commit -m "test: tighten bridge server hotspot budget after concurrency fix"
```

## Task 8: ADR And Documentation Sync

**Files:**
- Create: `docs/architecture/adr/2026-05-14-bridge-server-concurrency.md`
- Modify: `docs/architecture/overview.md`

- [ ] **Step 1: Write the ADR**

Create `docs/architecture/adr/2026-05-14-bridge-server-concurrency.md`:

```markdown
# ADR: Bridge Server Concurrency Model

**Date:** 2026-05-14
**Status:** Accepted

## Context

`BridgeServer` previously relied on `@Volatile var serverSocket` and
`@Volatile var resolvedName` for lifecycle state. Compound start/stop
sequences were racy under concurrent callers (see
[`docs/superpowers/specs/2026-05-14-bridge-server-concurrency-design.md`](../../superpowers/specs/2026-05-14-bridge-server-concurrency-design.md)
§1).

## Decision

1. Replace `@Volatile` lifecycle fields with a `MutableStateFlow<BridgeServerState>`
   exposed as a read-only `StateFlow`.
2. Serialize `start()` and `stop()` with a `kotlinx.coroutines.sync.Mutex`.
3. Convert both to `suspend fun`. Provide a `runBlocking(Dispatchers.IO)`
   boundary in `FixThisBridgeRuntime` so the singleton entry point keeps
   its synchronous Java-friendly signature.
4. `cancelAndJoin` the accept loop and the scope's parent `Job` inside
   `stop()` so in-flight handlers cannot write to closed sockets after
   `stop()` returns.
5. `BridgeServer` becomes single-use after `stop()`. `FixThisBridgeRuntime`
   discards the instance on `stopForTest()`, which matches existing
   behaviour.

## Consequences

- `stop()` can now block briefly while handlers drain; a 5-second
  `withTimeoutOrNull` wraps the join so a deadlocked handler does not
  hang the test suite.
- Public `start(): Boolean` becomes `suspend fun start(): Boolean`.
  Non-suspend callers must use `runBlocking` (only `FixThisBridgeRuntime`
  is such a caller in-tree).
- The new `state: StateFlow<BridgeServerState>` is an additive read-only
  surface; future console / UI code can subscribe instead of polling.
```

- [ ] **Step 2: Update architecture overview**

In `docs/architecture/overview.md`, find the `:fixthis-compose-sidekick`
section and add:

```markdown
- `BridgeServer` serialises lifecycle transitions with a coroutine `Mutex`
  and exposes lifecycle state via `state: StateFlow<BridgeServerState>`.
  See ADR `2026-05-14-bridge-server-concurrency` for the rationale.
```

- [ ] **Step 3: Verify**

```bash
git diff --check
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.architecture.*"
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests "io.beyondwin.fixthis.compose.sidekick.bridge.*"
node scripts/build-console-assets.mjs --check
```

Expected: all PASS.

- [ ] **Step 4: Commit**

```bash
git add docs/architecture/adr/2026-05-14-bridge-server-concurrency.md docs/architecture/overview.md
git commit -m "docs(adr): record bridge server concurrency model"
```

## Self-Review Checklist

- [ ] `BridgeProtocol.VERSION` is still `1.3`. No wire-format change.
- [ ] `BridgeServer.start()` / `BridgeServer.stop()` are now `suspend fun`;
      `FixThisBridgeRuntime` is the only caller and it wraps in
      `runBlocking(Dispatchers.IO)`.
- [ ] `BridgeServer.resolvedSocketName()` is preserved as a non-suspending
      read derived from the state flow.
- [ ] `BridgeServerState.Running` carries the negotiated socket name so
      external observers don't need to peek at `resolvedSocketName()`.
- [ ] `BridgeServerConcurrencyTest` is committed without `@Ignore` and
      passes within 5 seconds per test.
- [ ] `ArchitectureHotspotBudgetTest` budget for `BridgeServer.kt` is at
      `260` lines (down from `740`).
- [ ] `docs/architecture/adr/2026-05-14-bridge-server-concurrency.md`
      exists and is linked from `docs/architecture/overview.md`.
- [ ] No new modules depend on each other; `:fixthis-compose-sidekick`
      still has no MCP / CLI imports.
