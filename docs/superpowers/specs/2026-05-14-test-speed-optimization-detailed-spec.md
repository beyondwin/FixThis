# FixThis Test Speed Optimization - Detailed Spec

**Date:** 2026-05-14
**Status:** Ready for implementation planning
**Owners:** build / CI / test-infrastructure
**Related:**
- `docs/superpowers/plans/2026-05-14-test-speed-optimization-implementation.md` - task-by-task implementation plan
- `docs/superpowers/specs/2026-05-14-build-optimization-detailed-spec.md` - adjacent Gradle and CI build optimization work
- `CONTRIBUTING.md` - canonical local and CI verification commands
- `.github/workflows/ci.yml` - current PR baseline workflow

---

## Purpose

The current PR verification loop is dominated by one JVM test module and one
event-log compaction test class. The test suite is already broad and valuable,
so the goal is not to remove coverage. The goal is to preserve the same
behavioral guarantees while reducing unnecessary durable filesystem work,
preventing test tasks from being invalidated by time-based generated sources,
and making CI report independent failures faster.

This spec focuses on test execution speed. It complements the broader build
optimization spec, which covers local Gradle build cache defaults, custom task
cacheability, sidekick build-info generation, compatibility matrix plumbing,
and CI shape.

## Audit Findings

### Required Local Test Command

The primary JVM and Android local unit test command from the PR checklist is:

```bash
./gradlew \
  :fixthis-compose-core:test \
  :fixthis-cli:test \
  :fixthis-mcp:test \
  :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test \
  --no-daemon \
  --profile
```

On this machine on 2026-05-14, the command completed successfully in roughly
28 seconds. The Gradle profile showed that the slow path was concentrated in
`:fixthis-mcp:test`.

### Module-Level Test Timing

The Gradle XML test results from the same run reported:

| Module | Test classes | Test cases | XML test time |
| --- | ---: | ---: | ---: |
| `fixthis-mcp` | 58 | 539 | 20.797s |
| `fixthis-compose-sidekick` | 19 | 102 | 6.740s |
| `fixthis-cli` | 10 | 61 | 2.732s |
| `fixthis-gradle-plugin` | 2 | 7 | 1.201s |
| `fixthis-compose-core` | 19 | 109 | 0.218s |

The important signal is not the exact number, which varies by machine. The
important signal is the shape: `fixthis-mcp` dominates the local test loop.

### Slowest Test Classes

The slowest class was:

| Class | Test cases | XML test time |
| --- | ---: | ---: |
| `io.github.beyondwin.fixthis.mcp.session.eventlog.EventLogCompactorTest` | 4 | 18.091s |

The two slowest methods were:

| Test method | XML test time |
| --- | ---: |
| `compactedSessionReplaysFromSnapshotPlusActiveEvents` | 12.275s |
| `compactionMovesOldestFilesWhenAboveThreshold` | 5.609s |

Both tests generate many event log files. The event log writer currently uses
`RandomAccessFile(..., "rwd")` and `channel.force(true)` for every event. That
is the correct production durability posture, but it is excessive for unit
tests that are only verifying compaction and replay semantics.

### MCP BuildInfo Invalidates Tests

`fixthis-mcp/build.gradle.kts` generates `BuildInfo.kt` with:

```kotlin
val resolvedBuildEpochMs: Long = (System.currentTimeMillis() / 60_000L) * 60_000L
```

The generated source is part of the main Kotlin source set. When a test run
crosses a minute boundary, Gradle sees a changed input, regenerates
`BuildInfo.kt`, recompiles `:fixthis-mcp`, recompiles test code, and reruns
`:fixthis-mcp:test`.

This is useful for runtime stale-binary detection in distribution builds, but
it is counterproductive for local test loops. Tests should be up-to-date when
source files and resources are unchanged.

### Console JavaScript Checks Are Already Fast

The current console checks are not a meaningful bottleneck:

```bash
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs
```

The 68 Node tests completed in under 0.2 seconds of wall-clock time locally.
These checks should still move earlier or into a separate CI job because they
give fast, precise failure signals, but they are not where most time will be
saved.

## Goals

1. Reduce `:fixthis-mcp:test` wall-clock time by removing unnecessary durable
   filesystem sync from unit-test setup paths.
2. Keep production event-log durability unchanged by default.
3. Make `:fixthis-mcp:test` up-to-date on repeated local runs when source and
   resource inputs are unchanged, even after the wall clock crosses a minute
   boundary.
4. Preserve current event-log compaction, checkpoint, and replay coverage with
   smaller fixtures.
5. Preserve all persisted MCP JSON compatibility contracts.
6. Improve CI feedback locality while keeping the existing required
   `Baseline verification` check name available for branch protection.
7. Document focused test loops so contributors do not run the whole PR matrix
   while iterating on a narrow area.

## Non-Goals

- Do not remove event-log compaction tests.
- Do not weaken production write-ahead event-log durability.
- Do not rename persisted MCP JSON fields: `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, and `sourceCandidates` remain compatibility
  contracts.
- Do not change the bridge protocol.
- Do not move connected Android tests into the required PR baseline.
- Do not introduce a remote Gradle build cache in this pass.
- Do not enable global Gradle configuration cache as part of this work. That is
  covered by the build optimization track and remains blocked on Spotless cache
  reuse behavior.

## Design

### 1. Add a Fast Event Log Durability Mode for Unit Tests

Introduce a small `EventLogDurability` enum in
`fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/eventlog/EventLogWriter.kt`:

```kotlin
enum class EventLogDurability {
    Durable,
    Fast,
}
```

`EventLogWriter` keeps `Durable` as the default. In durable mode it continues
to use `RandomAccessFile(tmp, "rwd")`, writes the encoded JSON line, and calls
`channel.force(true)`.

In fast mode it writes the same JSON line to the same temp file path, calls the
same `onWriteHook`, keeps the same temp-file cleanup behavior, and performs the
same final rename. The only difference is that it avoids per-event durable
sync. This keeps unit tests focused on event ordering, compaction, checkpoint
writing, and replay behavior instead of storage-device durability.

### 2. Shrink Compactor Fixtures Without Reducing Behavior Coverage

`EventLogCompactorTest` does not need 1100 or 1200 files to prove compaction.
The important behavior is:

- no compaction occurs when active event count is at or below the threshold
- active events above the threshold are split into archived and retained sets
- the checkpoint records the highest archived sequence number
- replay starts from the persisted snapshot when a checkpoint exists
- active events after the checkpoint remain replayable

Those behaviors can be tested with thresholds like 2 and 10. The large fixture
sizes should be replaced with smaller counts and fast event writers.

### 3. Stabilize MCP BuildInfo During Verification Tasks

Runtime distribution builds may keep using a rounded wall-clock epoch so the
console/server staleness signal remains fresh. Verification tasks should use a
stable epoch derived from the current git commit. A verification task is a task
named `test`, ending in `Test`, ending in `UnitTest`, `check`, or `build`.

For verification tasks:

- `BuildInfo.BUILD_EPOCH_MS` uses `git log -1 --format=%ct` multiplied by 1000
- `BuildInfo.GIT_SHA` still uses the current short SHA
- `processResources` filters `console/app.js` with the same epoch and SHA
- `ConsoleBundleStalenessConsistencyTest` continues to prove that the bundled
  console and server build info match within the same test artifact

For distribution tasks such as `:fixthis-mcp:installDist`, the build continues
to use the current rounded minute epoch unless the broader build optimization
track changes that behavior later.

### 4. Keep CI Compatibility with Existing Required Check Names

The current workflow has a single job named `Baseline verification`. Branch
protection may already depend on that display name. CI can add parallel jobs,
but the required check should remain stable by turning `Baseline verification`
into an aggregate job that depends on the concrete jobs.

Initial CI shape:

- `console-js`: checkout only, then run console bundle, syntax, and Node tests
- `gradle-verification`: Java 21, Gradle, Android SDK, Gradle verification,
  whitespace check
- `baseline`: aggregate job named `Baseline verification`; fails if either
  upstream job failed

This change primarily improves failure locality. Larger CI wall-clock gains can
come from splitting `gradle-verification` into static analysis and unit jobs
after the test-level optimizations land.

### 5. Document Focused Test Loops

Add a short section to `CONTRIBUTING.md` with the fastest commands for common
edit areas:

```bash
./gradlew :fixthis-mcp:test --tests '*EventLogCompactorTest' --no-daemon
./gradlew :fixthis-mcp:test --tests '*eventlog*' --no-daemon
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --no-daemon
node --test scripts/console-availability-test.mjs scripts/previewStaleness-test.mjs
```

The full PR checklist remains canonical before opening or updating a pull
request.

## Acceptance Criteria

The implementation is complete when these checks pass:

```bash
./gradlew :fixthis-mcp:test --tests '*EventLogWriterTest' --no-daemon
./gradlew :fixthis-mcp:test --tests '*EventLogCompactorTest' --no-daemon
./gradlew :fixthis-mcp:test --no-daemon
sleep 70
./gradlew :fixthis-mcp:test --no-daemon
./gradlew \
  :fixthis-compose-core:test \
  :fixthis-cli:test \
  :fixthis-mcp:test \
  :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test \
  --no-daemon \
  --profile
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs
git diff --check
```

The second `:fixthis-mcp:test` after `sleep 70` should be `UP-TO-DATE` when no
source or resource files changed. The focused `EventLogCompactorTest` run
should no longer dominate the test suite; on a local SSD it should target one
to two seconds instead of roughly eighteen seconds.

## Risks and Mitigations

### Risk: Fast Event Log Mode Leaks Into Production

Mitigation: `EventLogWriter` defaults to `EventLogDurability.Durable`. Only test
helpers pass `EventLogDurability.Fast`. Existing failure-mode tests continue to
use the default constructor.

### Risk: Smaller Compactor Fixtures Miss Scale Bugs

Mitigation: The compactor algorithm is file-count based and does not contain
batch-size specific branching beyond threshold comparison. The tests should
assert exact archived and retained counts for small thresholds. If maintainers
want a scale smoke test, add one nightly-only test command rather than keeping
the PR loop slow.

### Risk: Stable Test BuildInfo Masks Staleness Issues

Mitigation: The existing `ConsoleBundleStalenessConsistencyTest` and
`ServerVersionEndpointTest` continue to verify that server and console build
metadata match within the test artifact. Distribution builds keep the current
rounded-minute behavior.

### Risk: CI Split Breaks Required Checks

Mitigation: Preserve the `Baseline verification` display name as an aggregate
job. Do not remove or rename it without coordinating branch-protection settings.

## Decision Log

| Date | Decision | Rationale |
| --- | --- | --- |
| 2026-05-14 | Optimize `EventLogCompactorTest` first | One test class accounts for most `fixthis-mcp` test time. |
| 2026-05-14 | Keep event-log durable writes as the production default | FixThis feedback persistence should remain crash-resistant by default. |
| 2026-05-14 | Stabilize MCP BuildInfo for verification tasks only | Test loops should be cacheable; distribution stale-binary checks still need fresh metadata. |
| 2026-05-14 | Preserve the `Baseline verification` CI check name | Avoid branch-protection churn while improving failure locality. |
