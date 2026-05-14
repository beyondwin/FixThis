# Bridge Server Concurrency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `@Volatile`-based lifecycle fields in `BridgeServer` and the informal `synchronized` block in `FixThisBridgeRuntime` with a `kotlinx.coroutines.sync.Mutex` plus an observable `StateFlow<BridgeServerState>`, closing the three concurrency races documented in [`../specs/2026-05-14-bridge-server-concurrency-design.md`](../specs/2026-05-14-bridge-server-concurrency-design.md).

**Architecture:** Keep `:fixthis-compose-sidekick` debug-only and free of MCP/CLI imports. `BridgeServer.start()` / `stop()` become `suspend fun`. Production callers launch on `ProcessLifecycleOwner.lifecycleScope.async(Dispatchers.IO)` to avoid main-thread blocking (see spec §3.6 + §6.1). `runBlocking(Dispatchers.IO)` is permitted **only** in `stopForTest()` and in test code. `BridgeServer` is **single-use** after `stop()` (spec G2a); restart requires a fresh instance. Bridge protocol stays at `1.3`; no wire-format changes. Add the new state flow as an additive read-only API.

**Tech Stack:** Kotlin Coroutines (Mutex, StateFlow, cancelAndJoin, withTimeoutOrNull), AndroidX Lifecycle (`ProcessLifecycleOwner.lifecycleScope` for production start), Robolectric (existing test runtime). Ktor is unaffected; mcp consumes the bridge.

**Wire-protocol invariant:** `BridgeProtocol.VERSION` is mirrored across four sites and validated by `BridgeProtocolVersionSyncTest` (`:fixthis-mcp:test`). This change does not alter the protocol; every task's verify step explicitly re-runs `BridgeProtocolVersionSyncTest`.

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

> **Phase A+B bundle.** This task and Task 2 land in the same commit (spec §4). The tests below reference `BridgeServerState` / `state.value`, which are introduced by Task 2; building Task 1 in isolation would fail to compile. Do **not** commit between Step 3 of Task 1 and Step 6 of Task 2 — defer the single bundle commit to the end of Task 2.

**Files:**
- Create: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyTest.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` (visibility only)

- [ ] **Step 0: Audit and expand fixture visibility**

Tests in this task (and T2 in particular) need to read `BridgeServer.session.token`. Run:

```bash
grep -n "val session" fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt
```

If `session` is `private`, expose it as `@VisibleForTesting internal val session` (NOT public). Add an inline comment:

```kotlin
// @VisibleForTesting: tests construct BridgeServer directly to drive the
// socket path. Production callers go through FixThisBridgeRuntime which
// only consumes resolvedSocketName(). Do not read this outside tests.
@VisibleForTesting
internal val session: SessionToken
```

Verify no production caller depends on the visibility change:

```bash
grep -rn "\.session\b" fixthis-compose-sidekick/src/main/ fixthis-mcp/src/main/ \
  | grep -v "BridgeServer.kt:"
```

Output should be empty (besides unrelated `.session` substrings such as `session.json`). Commit visibility change separately if non-empty.

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
    fun stopWaitsForInFlightHandlerToCompleteOverRealSocket(): Unit = runBlocking {
        // T2 drives the REAL socket path (not handleRequestForTest) so the
        // §1.2 zombie-handler race is actually reproduced. handleRequestForTest
        // bypasses acceptLoop.launch { handleClient(...) } entirely.
        val handlerEntered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val handlerFinished = CompletableDeferred<Unit>()
        val server = newServerWithBlockingEnvironment(handlerEntered, gate, handlerFinished)

        server.start()
        val socketName = (server.state.value as BridgeServerState.Running).socketName

        val clientJob = async(Dispatchers.IO) {
            val client = LocalSocket()
            client.connect(LocalSocketAddress(socketName))
            client.outputStream.bufferedWriter().also {
                it.write(statusRequestPayload(server.session.token))
                it.newLine()
                it.flush()
            }
            // Block on response; will not return until handler completes.
            client.inputStream.bufferedReader().readLine()
        }

        // Wait until handler is actually inside status(); only then call stop().
        withTimeout(5.seconds) { handlerEntered.await() }
        val stopJob = async { server.stop() }
        // Give stop() a chance to enter Stopping but not return.
        delay(50)
        assertEquals(false, stopJob.isCompleted, "stop() must wait for handler")
        gate.complete(Unit) // release the handler

        withTimeout(5.seconds) { stopJob.await() }
        assertEquals(true, handlerFinished.isCompleted, "handler must finish before stop returns")
        clientJob.await()
    }

    @Test
    fun observedStateIsConsistentAcrossSingleLifecycle(): Unit = runBlocking {
        // T3: BridgeServer is single-use (spec G2a). One server, one lifecycle.
        // We assert that observed emissions form a legal subsequence — StateFlow
        // conflation may collapse Idle→Starting→Running into Idle→Running, which
        // is acceptable. Illegal pairs (Running→Starting, Running→Running with
        // blank name, etc.) are not.
        val server = newServerWithCountingSocketFactory(AtomicInteger(0))
        val seen = mutableListOf<BridgeServerState>()
        val collector = launch { server.state.collect { seen += it } }

        server.start()
        server.stop()
        delay(50)            // allow trailing emission to land
        collector.cancel()

        assertEquals(BridgeServerState.Idle, seen.first(), "must start in Idle")
        assertEquals(BridgeServerState.Idle, seen.last(), "must end in Idle")
        assertTrue(
            seen.any { it is BridgeServerState.Running && it.socketName.isNotBlank() },
            "must observe at least one Running with non-blank socket name"
        )
        // No two adjacent emissions are illegal.
        seen.zipWithNext().forEach { (a, b) ->
            require(a != b) { "duplicate adjacent emission: $a" }
            require(!(a is BridgeServerState.Running && b is BridgeServerState.Starting)) {
                "illegal Running→Starting transition"
            }
        }
        // resolvedSocketName agrees with the latest Running observation.
        val lastRunning = seen.filterIsInstance<BridgeServerState.Running>().lastOrNull()
        if (lastRunning != null) {
            // After stop(), resolvedSocketName must be null.
            assertEquals(null, server.resolvedSocketName())
        }
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
        entered: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
        finished: CompletableDeferred<Unit>,
    ): BridgeServer {
        val session = TestSessions.fixed("io.beyondwin.fixthis.sample")
        val env = object : BridgeEnvironment by StubBridgeEnvironment() {
            override suspend fun status(): BridgeStatus {
                entered.complete(Unit)   // signal observation
                gate.await()              // hold until caller releases
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

- [ ] **Step 2: Verify the test FAILS (transiently, before adding @Ignore)**

> Run this once locally to confirm the test reproduces the race. Do **not** commit the un-ignored failing test — Step 3 immediately adds `@Ignore` before any commit. The Phase A+B bundle commit lands after Task 2 Step 6.

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.BridgeServerConcurrencyTest"
```

Expected: at least one of the three tests FAILS or hangs (`withTimeout` catches the hang). The most reliable failure is `concurrentStartReturnsTrueOnceAndBindsOnlyOnce` reporting `bindCount` > 1 or `results.count { it }` > 1. Capture the failure output for the eventual Phase A+B commit message.

Note: depending on whether Task 2's `BridgeServerState` already exists when you run this, T3 may instead fail to compile. That is acceptable — Step 3 adds `@Ignore` before any commit, and the compile error is resolved once Task 2 Step 3 lands.

- [ ] **Step 3: Apply @Ignore (do NOT commit yet)**

Add `@Ignore("Reproduces concurrency hazards; un-ignored in Task 4 (Phase D)")` to each test method. **Do not commit** — Task 2 will produce a single Phase A+B bundle commit at its Step 6.

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

- [ ] **Step 6: Commit (Phase A+B bundle — includes Task 1 artifacts)**

```bash
git add \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerState.kt \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyTest.kt

./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests "io.beyondwin.fixthis.compose.sidekick.bridge.*"
./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"

git commit -m "feat(sidekick): expose bridge server state as flow and document concurrency races"
```

This is the single commit for Phase A+B. The three concurrency tests are present but under `@Ignore`; existing tests pass; behaviour is unchanged.

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

- [ ] **Step 3: Convert stop() to suspend with mutex, bounded drain, and idempotency**

```kotlin
suspend fun stop() = lifecycleMutex.withLock {
    // Idempotent for Idle/Stopping — re-entrant callers do not wait.
    when (_state.value) {
        is BridgeServerState.Idle, BridgeServerState.Stopping -> return@withLock
        else -> Unit
    }
    _state.value = BridgeServerState.Stopping
    runCatching { serverSocket?.close() }   // unblocks accept()

    // Bounded drain. If a handler hangs (e.g., a deadlocked Accessibility
    // service), do not hold stop() forever — log loudly and leak.
    val drained = withTimeoutOrNull(StopDrainTimeout) {
        acceptJob?.cancelAndJoin()
        scope.coroutineContext[Job]?.cancelAndJoin()
    }
    if (drained == null) {
        Log.w(
            BridgeServerLogTag,
            "stop() drain timed out after $StopDrainTimeout; pending handlers leaked",
        )
    }

    serverSocket = null
    acceptJob = null
    _state.value = BridgeServerState.Idle
    // Single-use: scope is now cancelled. Constructing start() again
    // returns false (idle check passes but the scope is dead).
    // FixThisBridgeRuntime discards the instance; tests do likewise.
}

companion object {
    @VisibleForTesting internal val StopDrainTimeout = 5.seconds
}
```

Add imports:

```kotlin
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
```

**Decision: single-use after stop().** Cancelling the parent `Job` of
`scope` makes the scope unusable. Tests that need separate lifecycles
construct a fresh `BridgeServer` each time. This matches
`FixThisBridgeRuntime`'s production behaviour (it never restarts the
same instance — `stopForTest` discards the server). Task 1's T3 already
asserts only single-lifecycle invariants after the spec/plan
reconciliation; no further test changes are needed in this step.

- [ ] **Step 4: Update test fixtures and BridgeRuntime callers**

All test callers wrap suspending calls in `runBlocking { ... }`:

```kotlin
runBlocking { server.start() }
runBlocking { server.stop() }
```

In `BridgeRuntime.kt`, **production `start()` must NOT run on the main thread** (spec §3.6 + §6.1). Replace `synchronized(lock) { ... }` with a `ProcessLifecycleOwner.lifecycleScope.launch(Dispatchers.IO)` for production, and reserve `runBlocking` for `stopForTest()` only:

```kotlin
internal object FixThisBridgeRuntime {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var startInFlight: kotlinx.coroutines.Job? = null
    ...

    // Suspending core — used by both production start (via launch) and tests.
    private suspend fun startSuspending(
        application: Application,
        lifecycleCallbacks: FixThisActivityLifecycleCallbacks,
    ): Boolean = mutex.withLock {
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

    // Production entry point — fire-and-forget on a background coroutine so
    // Application.onCreate / Activity lifecycle callbacks never block the
    // main thread on a Mutex or socket bind. The Boolean return is a hint
    // ("we attempted") not a guarantee ("server is ready").
    fun start(
        application: Application,
        lifecycleCallbacks: FixThisActivityLifecycleCallbacks,
    ): Boolean {
        if (!application.isDebuggable()) return false
        startInFlight = androidx.lifecycle.ProcessLifecycleOwner.get()
            .lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { startSuspending(application, lifecycleCallbacks) }
                    .onFailure { Log.w(BridgeRuntimeLogTag, "start failed", it) }
            }
        return true
    }

    // @VisibleForTesting — invoked only from test code that already runs
    // off the main thread. runBlocking here is intentional and bounded.
    @androidx.annotation.VisibleForTesting
    fun stopForTest() {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            mutex.withLock {
                server?.stop()
                server = null
                environment = null
                startInFlight?.cancelAndJoin()
                startInFlight = null
            }
        }
    }
}
```

Note: `androidx.lifecycle:lifecycle-process` is already on the
`:fixthis-compose-sidekick` classpath via the Compose dependency
graph. Verify with:

```bash
./gradlew :fixthis-compose-sidekick:dependencies --configuration debugRuntimeClasspath \
  | grep "lifecycle-process"
```

If the artifact is missing, add an explicit `implementation` entry to
`fixthis-compose-sidekick/build.gradle.kts` before this step.

- [ ] **Step 5: Verify**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.*"
./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"
grep -rn "runBlocking" fixthis-compose-sidekick/src/main/ | grep -v stopForTest
# expected: empty (runBlocking only in stopForTest or in test sources)
```

Expected: gradle tests PASS, `grep` returns empty. The previously `@Ignore`d concurrency tests are still ignored at this point; they'll be un-ignored in Task 4.

- [ ] **Step 6: Commit**

```bash
git add \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt
git commit -m "refactor(sidekick): serialize bridge lifecycle with coroutine mutex"
```

## Task 4: Un-Ignore Concurrency Tests, Tighten Budget, And Confirm Green (Phase D)

**Files:**
- Modify: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`

> **Phase D — merged commit.** Spec §4 requires that the un-ignore and the hotspot budget tightening land in **one commit** so no intermediate `main` state exists where the file has been refactored but the budget is stale (which would break `git bisect` across the budget step). Former Task 7 is folded in here.

- [ ] **Step 1: Remove `@Ignore` annotations**

Delete each `@Ignore("Reproduces concurrency hazards; un-ignored in Task 4")`.

The three tests are now self-contained per the reconciled spec §5.2:

- `concurrentStartReturnsTrueOnceAndBindsOnlyOnce` — multi-coroutine start race on one server.
- `stopWaitsForInFlightHandlerToCompleteOverRealSocket` — drives the socket path, reproduces §1.2.
- `observedStateIsConsistentAcrossSingleLifecycle` — single-lifecycle invariants only (single-use contract).

No additional rewrites are required.

- [ ] **Step 2: Tighten architecture hotspot budget**

In `ArchitectureHotspotBudgetTest.kt`, change:

```kotlin
"fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt" to 740,
```

to:

```kotlin
"fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt" to 260,
```

The post-concurrency file is ~210 lines; 260 gives a small headroom for future doc comments.

- [ ] **Step 3: Verify (full Phase D gate)**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest \
  --tests "io.beyondwin.fixthis.compose.sidekick.bridge.BridgeServerConcurrencyTest"
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.architecture.ArchitectureHotspotBudgetTest"
./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"
```

Expected: all PASS. Each concurrency test completes in well under 5 seconds. `BridgeProtocolVersionSyncTest` confirms the wire-protocol invariant is intact.

- [ ] **Step 4: Commit (single commit; this is the Phase D bundle)**

```bash
git add \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerConcurrencyTest.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt
git commit -m "test(sidekick): confirm bridge server is race-free and tighten hotspot budget"
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

## Task 7: ADR And Documentation Sync (Phase F)

> **Note.** The hotspot budget tightening previously listed as Task 7 has been merged into Task 4 (Phase D) per the reconciled spec §4. The numbering of subsequent tasks shifts accordingly.


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
3. Convert both to `suspend fun`. `FixThisBridgeRuntime` launches the
   production `start()` on
   `ProcessLifecycleOwner.lifecycleScope.launch(Dispatchers.IO)` so the
   main thread is never blocked on a `Mutex` or socket bind.
   `runBlocking(Dispatchers.IO)` is reserved for `stopForTest()` which
   only runs off the main thread inside test code.
4. `cancelAndJoin` the accept loop and the scope's parent `Job` inside
   `stop()`, wrapped in `withTimeoutOrNull(5.seconds)` so a deadlocked
   handler does not hang shutdown — instead a `Log.w` records the
   leak and `stop()` returns.
5. `BridgeServer` becomes **single-use** after `stop()`. The internal
   `scope` is cancelled; restart requires a fresh instance.
   `FixThisBridgeRuntime` always discards the instance on
   `stopForTest()`, which matches existing behaviour and removes an
   entire class of "restart race" failure modes.

## Consequences

- `stop()` can briefly block while handlers drain; the 5-second
  `withTimeoutOrNull` is the upper bound.
- Public `start(): Boolean` / `stop(): Unit` become `suspend fun`.
  Non-suspend production callers (`FixThisBridgeRuntime`) use
  `ProcessLifecycleOwner.lifecycleScope.launch` and treat the returned
  `Boolean` as a hint, not a synchronous result. Test callers use
  `runBlocking`.
- The new `state: StateFlow<BridgeServerState>` is an additive read-only
  surface; future console / UI code can subscribe instead of polling.
- The wire protocol is unchanged: `BridgeProtocol.VERSION` stays `1.3`
  and is enforced by `BridgeProtocolVersionSyncTest`.
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
- [ ] `BridgeProtocolVersionSyncTest` (`:fixthis-mcp:test`) passes after every phase.
- [ ] `BridgeServer.start()` / `BridgeServer.stop()` are `suspend fun`.
- [ ] Production `FixThisBridgeRuntime.start(...)` invokes `startSuspending` via
      `ProcessLifecycleOwner.lifecycleScope.launch(Dispatchers.IO)`. **No
      `runBlocking` on the main thread.**
- [ ] `runBlocking(Dispatchers.IO)` appears only in `stopForTest()` and inside
      `:fixthis-compose-sidekick:test`/`:fixthis-mcp:test` sources.
      Verify with:
      ```bash
      grep -rn "runBlocking" fixthis-compose-sidekick/src/main/ | grep -v stopForTest
      # expected: empty
      ```
- [ ] `BridgeServer.resolvedSocketName()` is preserved as a non-suspending
      read derived from the state flow.
- [ ] `BridgeServerState.Running` carries the negotiated socket name so
      external observers don't need to peek at `resolvedSocketName()`.
- [ ] `BridgeServer.stop()` uses `withTimeoutOrNull(StopDrainTimeout)` and
      logs (does not throw) on drain timeout.
- [ ] `BridgeServer` is single-use: after `stop()`, the scope is cancelled;
      a fresh instance is required for a new lifecycle.
- [ ] `BridgeServerConcurrencyTest` is committed without `@Ignore` and
      passes within 5 seconds per test. T2 (`stopWaitsForInFlightHandlerToCompleteOverRealSocket`)
      drives a real `LocalSocket` client, not `handleRequestForTest`.
- [ ] `ArchitectureHotspotBudgetTest` budget for `BridgeServer.kt` is at
      `260` lines (down from `740`) — landed **in the same commit** as the
      un-ignore (Task 4 / Phase D).
- [ ] `docs/architecture/adr/2026-05-14-bridge-server-concurrency.md`
      exists and is linked from `docs/architecture/overview.md`.
- [ ] No new modules depend on each other; `:fixthis-compose-sidekick`
      still has no MCP / CLI imports. Verify with the existing
      `ModuleBoundaryTest`.
- [ ] Phase merge order matches spec §4: A independent, B–D bundled,
      E independent, F co-merges with D.
