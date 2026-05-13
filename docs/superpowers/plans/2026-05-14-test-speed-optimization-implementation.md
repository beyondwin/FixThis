# FixThis Test Speed Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the required local and CI test loops faster by removing unnecessary event-log durability work from unit tests, stabilizing MCP build metadata during verification, and improving CI failure locality.

**Architecture:** Keep production behavior unchanged by default and add test-only fast paths through explicit constructor parameters. Stabilize generated MCP build metadata only for verification tasks so repeated test runs can be up-to-date without weakening distribution staleness checks. Preserve the existing `Baseline verification` CI check name by making it an aggregate over more focused jobs.

**Tech Stack:** Gradle 9.3.1, Android Gradle Plugin 9.1.1, Kotlin 2.2.21, JDK 21, JUnit 4 / kotlin.test, GitHub Actions, Node built-in test runner.

---

## File Structure

**Create:**
- `docs/superpowers/specs/2026-05-14-test-speed-optimization-detailed-spec.md` - design and rationale for this work.
- `docs/superpowers/plans/2026-05-14-test-speed-optimization-implementation.md` - this execution plan.

**Modify:**
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriter.kt` - add explicit durable vs fast write mode.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriterTest.kt` - cover fast mode and default durable mode.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt` - use fast writers and smaller thresholds.
- `fixthis-mcp/build.gradle.kts` - use stable build epoch for verification tasks.
- `.github/workflows/ci.yml` - split fast console checks from Gradle verification while preserving the aggregate baseline check name.
- `CONTRIBUTING.md` - document focused test-speed loops.

---

## Task 1: Capture the Current Timing Baseline

**Files:**
- No source changes in this task.

- [ ] **Step 1: Run the required JVM/local unit test group with a Gradle profile**

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

Expected: `BUILD SUCCESSFUL`. Save the profile path printed by Gradle.

- [ ] **Step 2: Print slowest test classes from XML results**

```bash
perl -nE 'if(/<testsuite[^>]*name="([^"]+)"[^>]*tests="([^"]+)".*time="([^"]+)"/) { say "$3\t$2\t$1" }' \
  $(find fixthis-cli fixthis-compose-core fixthis-compose-sidekick fixthis-gradle-plugin fixthis-mcp -path '*/build/test-results/*/TEST-*.xml' -print) \
  | sort -nr \
  | sed -n '1,30p'
```

Expected: `io.beyondwin.fixthis.mcp.session.eventlog.EventLogCompactorTest`
appears near the top before this plan is implemented.

- [ ] **Step 3: Print module totals**

```bash
perl -nE 'if(/<testsuite[^>]*name="([^"]+)"[^>]*tests="([^"]+)".*time="([^"]+)"/) { my $n=$2; my $t=$3; my $mod=$ARGV; $mod =~ s#/build/test-results/.*##; $tests{$mod}+=$n; $time{$mod}+=$t; $classes{$mod}++ } END { for $m (sort { $time{$b} <=> $time{$a} } keys %time) { printf "%.3f\t%d\t%d\t%s\n", $time{$m}, $tests{$m}, $classes{$m}, $m } }' \
  $(find fixthis-cli fixthis-compose-core fixthis-compose-sidekick fixthis-gradle-plugin fixthis-mcp -path '*/build/test-results/*/TEST-*.xml' -print)
```

Expected: `fixthis-mcp` is the slowest module before this plan is implemented.

- [ ] **Step 4: Record baseline in the PR description**

Add the baseline command, total time, slowest class, and profile path to the PR
description. The exact numbers are machine-specific; the before/after shape is
what matters.

---

## Task 2: Add Explicit Event Log Durability Modes

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriter.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriterTest.kt`

- [ ] **Step 1: Add failing tests for fast mode and default durable mode**

Append these tests to `EventLogWriterTest` above `private fun makeEvent`:

```kotlin
@Test
fun fastDurabilityWritesReadableEvents() {
    val dir = Files.createTempDirectory("evtlog-fast").toFile()
    try {
        val eventsDir = File(dir, "events")
        val touched = mutableListOf<String>()
        val writer = EventLogWriter(
            directory = eventsDir,
            onWriteHook = { touched += it.fileName.toString() },
            durability = EventLogDurability.Fast,
        )

        writer.append(makeEvent(1L, "fast"))

        val replayed = EventLogReader(eventsDir).readAll()
        assertEquals(listOf(1L), replayed.map { it.sequenceNumber })
        assertEquals(listOf("1715500000001-0000000001.jsonl.tmp"), touched)
        assertEquals(EventLogDurability.Fast, writer.durability)
    } finally {
        dir.deleteRecursively()
    }
}

@Test
fun defaultConstructorUsesDurableWrites() {
    val dir = Files.createTempDirectory("evtlog-default-durable").toFile()
    try {
        val writer = EventLogWriter(directory = File(dir, "events"))

        assertEquals(EventLogDurability.Durable, writer.durability)
    } finally {
        dir.deleteRecursively()
    }
}
```

- [ ] **Step 2: Run the focused tests and verify they fail**

```bash
./gradlew :fixthis-mcp:test --tests '*EventLogWriterTest' --no-daemon
```

Expected: FAIL because `EventLogDurability` and the `durability` constructor
parameter do not exist yet.

- [ ] **Step 3: Add the durability enum and constructor parameter**

In `EventLogWriter.kt`, add this enum above `class EventLogWriter`:

```kotlin
enum class EventLogDurability {
    Durable,
    Fast,
}
```

Update the `EventLogWriter` constructor to:

```kotlin
class EventLogWriter(
    private val directory: File,
    private val onWriteHook: (java.nio.file.Path) -> Unit = {},
    internal val durability: EventLogDurability = EventLogDurability.Durable,
) {
```

- [ ] **Step 4: Split event file writing by durability mode**

Replace the `RandomAccessFile` block inside `append` with this structure:

```kotlin
val line = eventLogJson.encodeToString(SessionEvent.serializer(), event) + "\n"
writeEventLine(tmp, line)
```

Add this private function inside `EventLogWriter` below `append`:

```kotlin
private fun writeEventLine(tmp: File, line: String) {
    when (durability) {
        EventLogDurability.Durable -> {
            RandomAccessFile(tmp, "rwd").use { raf ->
                onWriteHook(tmp.toPath())
                raf.write(line.toByteArray(Charsets.UTF_8))
                raf.channel.force(true)
            }
        }
        EventLogDurability.Fast -> {
            onWriteHook(tmp.toPath())
            tmp.writeText(line, Charsets.UTF_8)
        }
    }
}
```

Keep the existing catch blocks and final rename logic unchanged.

- [ ] **Step 5: Run event-log writer and failure-mode tests**

```bash
./gradlew :fixthis-mcp:test \
  --tests '*EventLogWriterTest' \
  --tests '*EventLogFailureModeTest' \
  --no-daemon
```

Expected: PASS. `EventLogFailureModeTest` must still use durable writes through
the default constructor.

- [ ] **Step 6: Commit Task 2**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriter.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriterTest.kt
git commit -m "test: add fast event log writer mode"
```

---

## Task 3: Shrink EventLogCompactorTest Fixtures

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt`

- [ ] **Step 1: Add a fast writer helper**

In `EventLogCompactorTest.kt`, add this helper below `eventWriterFor`:

```kotlin
private fun fastEventWriterFor(paths: FeedbackSessionPaths): (String) -> EventLogWriter = { sessionId ->
    EventLogWriter(
        directory = paths.eventLogDirectory(sessionId),
        durability = EventLogDurability.Fast,
    )
}
```

- [ ] **Step 2: Use the fast writer in the replay test**

In `compactedSessionReplaysFromSnapshotPlusActiveEvents`, replace:

```kotlin
eventLogWriterProvider = eventWriterFor(paths),
```

with:

```kotlin
eventLogWriterProvider = fastEventWriterFor(paths),
```

Do this for the initial `FeedbackSessionStore`. Leave the replayed store using
`eventLogReaderProvider = eventReaderFor(paths)`.

- [ ] **Step 3: Reduce the replay test event count and threshold**

In `compactedSessionReplaysFromSnapshotPlusActiveEvents`, replace:

```kotlin
repeat(1200) { index ->
    store.addItem(session.sessionId, makeDraftItem(screen.screenId, index + 1))
}
```

with:

```kotlin
repeat(24) { index ->
    store.addItem(session.sessionId, makeDraftItem(screen.screenId, index + 1))
}
```

Replace:

```kotlin
).runOnce(threshold = 1000)
```

with:

```kotlin
).runOnce(threshold = 10)
```

- [ ] **Step 4: Reduce the primary compaction fixture**

In `compactionMovesOldestFilesWhenAboveThreshold`, construct the writer with
fast durability:

```kotlin
val writer = EventLogWriter(eventsDir, durability = EventLogDurability.Fast)
```

Replace:

```kotlin
repeat(1100) { i -> writer.append(makeEvent((i + 1).toLong())) }
```

with:

```kotlin
repeat(12) { i -> writer.append(makeEvent((i + 1).toLong())) }
```

Replace the compactor run with:

```kotlin
).runOnce(threshold = 10)
```

Update the archive assertion to:

```kotlin
assertEquals(2, archiveFiles.size, "archive/ should contain the 2 oldest files")
```

Update the remaining-files assertion to:

```kotlin
assertEquals(10, remainingFiles.size, "events/ should retain the newest 10 files")
```

Update the checkpoint expectation to:

```kotlin
EventLogCheckpoint(
    sessionId = snapshot.sessionId,
    compactedThroughSequenceNumber = 2L,
    snapshotUpdatedAtEpochMillis = snapshot.updatedAtEpochMillis,
    createdAtEpochMillis = 1_715_500_002_000L,
)
```

- [ ] **Step 5: Reduce the no-op fixture**

In `noOpWhenBelowThreshold`, construct the writer with fast durability:

```kotlin
val writer = EventLogWriter(eventsDir, durability = EventLogDurability.Fast)
```

Replace:

```kotlin
repeat(50) { i -> writer.append(makeEvent((i + 1).toLong())) }
```

with:

```kotlin
repeat(5) { i -> writer.append(makeEvent((i + 1).toLong())) }
```

Replace the compactor run with:

```kotlin
).runOnce(threshold = 10)
```

Update the final assertion to:

```kotlin
assertEquals(5, remaining.size, "All 5 original files should remain")
```

- [ ] **Step 6: Use fast durability in the order test**

In `oldestFilesArchivedAndNewestRetained`, construct the writer with fast
durability:

```kotlin
val writer = EventLogWriter(eventsDir, durability = EventLogDurability.Fast)
```

Keep the existing five-event fixture and `threshold = 2` because this test is
already small and directly checks ordering.

- [ ] **Step 7: Run the focused compactor test**

```bash
./gradlew :fixthis-mcp:test --tests '*EventLogCompactorTest' --no-daemon
```

Expected: PASS. The XML time for `EventLogCompactorTest` should be much lower
than the baseline. On a local SSD, target one to two seconds.

- [ ] **Step 8: Run the full MCP test suite**

```bash
./gradlew :fixthis-mcp:test --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit Task 3**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt
git commit -m "test: shrink event log compactor fixtures"
```

---

## Task 4: Stabilize MCP BuildInfo for Verification Tasks

**Files:**
- Modify: `fixthis-mcp/build.gradle.kts`

- [ ] **Step 1: Add helper functions for verification task detection**

In `fixthis-mcp/build.gradle.kts`, above the existing `resolvedGitSha`
declaration, add:

```kotlin
fun requestedVerificationTask(taskName: String): Boolean {
    val task = taskName.substringAfterLast(":")
    return task == "test" ||
        task == "check" ||
        task == "build" ||
        task.endsWith("Test") ||
        task.endsWith("UnitTest")
}

val requestedStableBuildInfo = gradle.startParameter.taskNames.any(::requestedVerificationTask)
```

- [ ] **Step 2: Add a git commit epoch provider**

Below `resolvedGitSha`, add:

```kotlin
val resolvedGitCommitEpochMs: Long =
    providers
        .exec {
            commandLine("git", "log", "-1", "--format=%ct")
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull
        ?.trim()
        ?.toLongOrNull()
        ?.times(1000L)
        ?: 1L
```

- [ ] **Step 3: Replace the MCP build epoch calculation**

Replace:

```kotlin
val resolvedBuildEpochMs: Long = (System.currentTimeMillis() / 60_000L) * 60_000L
```

with:

```kotlin
val resolvedBuildEpochMs: Long =
    if (requestedStableBuildInfo) {
        resolvedGitCommitEpochMs
    } else {
        (System.currentTimeMillis() / 60_000L) * 60_000L
    }
```

Add this comment immediately above the new `resolvedBuildEpochMs`:

```kotlin
// Test and verification tasks need stable generated sources so repeated local
// runs can be UP-TO-DATE. Distribution tasks keep a rounded wall-clock epoch so
// runtime stale-binary checks remain fresh.
```

- [ ] **Step 4: Run MCP build-info and console consistency tests**

```bash
./gradlew :fixthis-mcp:test \
  --tests '*BuildInfoTest' \
  --tests '*ConsoleBundleStalenessConsistencyTest' \
  --tests '*ServerVersionEndpointTest' \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Verify repeated test runs stay up-to-date across a minute boundary**

```bash
./gradlew :fixthis-mcp:test --no-daemon
sleep 70
./gradlew :fixthis-mcp:test --no-daemon
```

Expected: the second Gradle run reports `:fixthis-mcp:test UP-TO-DATE` when no
source or resource files changed.

- [ ] **Step 6: Verify distribution build metadata still works**

```bash
./gradlew :fixthis-mcp:installDist --no-daemon
```

Expected: PASS. `ConsoleBundleStalenessConsistencyTest` is not part of this
command, but the generated `BuildInfo.kt` and filtered console resource should
still share the same epoch within the distribution artifact.

- [ ] **Step 7: Commit Task 4**

```bash
git add fixthis-mcp/build.gradle.kts
git commit -m "build: stabilize mcp build info for tests"
```

---

## Task 5: Split Fast Console CI From Gradle Verification

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Replace the single baseline job with concrete jobs plus an aggregate**

Restructure `.github/workflows/ci.yml` so it has three jobs:

```yaml
jobs:
  console-js:
    name: Console JavaScript
    runs-on: ubuntu-latest
    timeout-minutes: 10

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Check console asset bundle
        run: node scripts/build-console-assets.mjs --check

      - name: Check console JavaScript syntax
        run: node --check fixthis-mcp/src/main/resources/console/app.js

      - name: Run console JavaScript tests
        run: |
          node --test \
            scripts/console-availability-test.mjs \
            scripts/pendingItemRecovery-test.mjs \
            scripts/beforeunloadGuard-test.mjs \
            scripts/undoRedo-test.mjs \
            scripts/undoKeymatch-test.mjs \
            scripts/activityDrift-test.mjs \
            scripts/previewStaleness-test.mjs

  gradle-verification:
    name: Gradle verification
    runs-on: ubuntu-latest
    timeout-minutes: 45

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Make Gradle executable
        run: chmod +x ./gradlew

      - name: Run Gradle verification
        run: |
          ./gradlew \
            spotlessCheck \
            detekt \
            :fixthis-compose-core:test \
            :fixthis-cli:test \
            :fixthis-mcp:test \
            :fixthis-compose-sidekick:testDebugUnitTest \
            :fixthis-gradle-plugin:test \
            :app:assembleDebug \
            :fixthis-cli:installDist \
            :fixthis-mcp:installDist \
            --no-daemon

      - name: Check whitespace
        shell: bash
        env:
          EVENT_NAME: ${{ github.event_name }}
          BASE_REF: ${{ github.base_ref }}
          BEFORE: ${{ github.event.before }}
          SHA: ${{ github.sha }}
        run: |
          set -euo pipefail

          zero_sha="0000000000000000000000000000000000000000"

          if [[ "${EVENT_NAME}" == "pull_request" ]]; then
            git diff --check "origin/${BASE_REF}...HEAD"
          elif [[ -n "${BEFORE}" && "${BEFORE}" != "${zero_sha}" ]] && git cat-file -e "${BEFORE}^{commit}" 2>/dev/null; then
            git diff --check "${BEFORE}..${SHA}"
          elif git rev-parse --verify "${SHA}^" >/dev/null 2>&1; then
            git diff --check "${SHA}^..${SHA}"
          else
            git diff-tree --check --root -r "${SHA}"
          fi

  baseline:
    name: Baseline verification
    runs-on: ubuntu-latest
    needs:
      - console-js
      - gradle-verification
    if: always()

    steps:
      - name: Check required jobs
        shell: bash
        run: |
          if [[ "${{ needs.console-js.result }}" != "success" ]]; then
            echo "console-js failed with result: ${{ needs.console-js.result }}"
            exit 1
          fi
          if [[ "${{ needs.gradle-verification.result }}" != "success" ]]; then
            echo "gradle-verification failed with result: ${{ needs.gradle-verification.result }}"
            exit 1
          fi
```

Keep the top-level workflow name, triggers, and concurrency block unchanged.

- [ ] **Step 2: Validate workflow YAML locally**

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/ci.yml"); puts "ok"'
```

Expected: prints `ok`.

- [ ] **Step 3: Commit Task 5**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: split console checks from gradle verification"
```

---

## Task 6: Document Focused Test Loops

**Files:**
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Add a focused test loops section**

In `CONTRIBUTING.md`, under `## Required Local Checks` and before the full
command block, add:

```markdown
### Focused Test Loops

Use focused loops while iterating, then run the full local checklist before
opening or updating a pull request.

```bash
# MCP event-log changes
./gradlew :fixthis-mcp:test --tests '*eventlog*' --no-daemon

# MCP console/server route changes
./gradlew :fixthis-mcp:test --tests '*console*' --no-daemon

# Sidekick Android unit changes
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --no-daemon

# Pure console JavaScript changes
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs
```
```

- [ ] **Step 2: Run Markdown whitespace check**

```bash
git diff --check CONTRIBUTING.md
```

Expected: no output.

- [ ] **Step 3: Commit Task 6**

```bash
git add CONTRIBUTING.md
git commit -m "docs: document focused test loops"
```

---

## Task 7: Final Verification

**Files:**
- All files changed by Tasks 2 through 6.

- [ ] **Step 1: Run focused event-log tests**

```bash
./gradlew :fixthis-mcp:test --tests '*eventlog*' --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run full MCP tests**

```bash
./gradlew :fixthis-mcp:test --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Verify MCP test up-to-date behavior across a minute boundary**

```bash
sleep 70
./gradlew :fixthis-mcp:test --no-daemon
```

Expected: `:fixthis-mcp:test UP-TO-DATE` when no source or resource files
changed after Step 2.

- [ ] **Step 4: Run the required JVM/local unit test group with a profile**

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

Expected: PASS. Compare total time, module totals, and slowest class list with
the Task 1 baseline.

- [ ] **Step 5: Run console checks**

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

Expected: PASS.

- [ ] **Step 6: Run whitespace check**

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 7: Commit final verification notes if documentation changed**

If verification results were added to project documentation, commit them:

```bash
git add CONTRIBUTING.md docs/superpowers/specs/2026-05-14-test-speed-optimization-detailed-spec.md docs/superpowers/plans/2026-05-14-test-speed-optimization-implementation.md
git commit -m "docs: add test speed optimization plan"
```

If verification results are only in the PR description, no extra commit is
needed for this step.

---

## Self-Review Checklist

- Spec coverage: every goal in
  `docs/superpowers/specs/2026-05-14-test-speed-optimization-detailed-spec.md`
  maps to at least one task in this plan.
- Placeholder scan: this plan contains no open-ended implementation placeholders.
- Type consistency: `EventLogDurability`, `EventLogWriter.durability`,
  `requestedVerificationTask`, and `requestedStableBuildInfo` are named
  consistently across tasks.
- Compatibility: production event-log durability defaults to
  `EventLogDurability.Durable`; persisted MCP JSON field names are unchanged.
