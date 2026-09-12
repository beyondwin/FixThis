# ADR: Bridge Server Concurrency Model

- Date: 2026-05-14
- Status: Accepted

## In short

`BridgeServer` start/stop run one at a time. After `stop()`, create a new
instance. The wire protocol is unchanged (`1.3`).

## Context

`BridgeServer` used `@Volatile` fields for lifecycle state. Concurrent
start/stop sequences raced. Details:
[`docs/superpowers/specs/2026-05-14-bridge-server-concurrency-design.md`](../../superpowers/specs/2026-05-14-bridge-server-concurrency-design.md)
§1. That spec is historical; this ADR is the current rule.

## Decision

1. Replace `@Volatile` lifecycle fields with a
   `MutableStateFlow<BridgeServerState>` exposed as a read-only `StateFlow`.
2. Serialize `start()` and `stop()` with a `Mutex`.
3. Make both `suspend fun`. Production `start()` runs on
   `ProcessLifecycleOwner.lifecycleScope.launch(Dispatchers.IO)` so the
   main thread never blocks on a mutex or socket bind.
   `runBlocking(Dispatchers.IO)` is only for `stopForTest()`, off the main
   thread in tests.
4. `stop()` cancels the accept loop and parent `Job` inside
   `withTimeoutOrNull(5.seconds)`. A stuck handler logs and returns
   instead of hanging shutdown.
5. After `stop()`, the instance is single-use. Restart needs a new
   instance. `FixThisBridgeRuntime` always discards the instance on
   `stopForTest()`.
6. `AndroidBridgeEnvironment.currentActivity` is a
   `StateFlow<WeakReference<Activity>?>` with compare-and-clear updates.

## Consequences

- `stop()` can wait up to five seconds while handlers drain.
- Public `start()` / `stop()` are suspending. Production callers treat the
  returned `Boolean` as a hint, not a synchronous result.
- `state: StateFlow<BridgeServerState>` is an additive read-only surface.
- `BridgeProtocol.VERSION` stays `1.3`.
- A real-`LocalSocket` regression test cannot run under Robolectric. That
  coverage is deferred to connected tests. Unit coverage is
  `BridgeServerConcurrencyStressTest`.
