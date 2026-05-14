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
6. `AndroidBridgeEnvironment.currentActivity` is atomicized as
   `StateFlow<WeakReference<Activity>?>` with `setCurrentActivity` and
   compare-and-clear `clearCurrentActivityIf` (using `update { ... }`)
   to close the informal `synchronized(lock)` race in §1.4.

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
- T2's real-`LocalSocket` regression test (§5.2) cannot run under
  Robolectric and is deferred to `:fixthis-compose-sidekick:connectedAndroidTest`
  in a follow-up. Phase D coverage of the §1.2 zombie-handler invariant
  is provided by the bounded-drain implementation plus
  `BridgeServerConcurrencyStressTest` (§5.3).
