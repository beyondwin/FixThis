# Runtime Evidence Autopilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn FixThis's manual runtime-evidence attachment contract into a bounded, redacted, time-correlated Android evidence collection path that runs automatically before Save to MCP exposes new items to agents.

**Architecture:** Keep ADB execution and neutral Android collector DTOs in `:fixthis-cli`; keep policy, redaction, local artifacts, event replay, handoff rendering, MCP, and console behavior in `:fixthis-mcp`. The sidekick bridge and `:fixthis-compose-core` remain unchanged. Manual collection, automatic handoff collection, and connected proof all call one coordinator.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, kotlinx.coroutines, Java `ProcessBuilder`, ADB, local HTTP console JavaScript, Node.js 20 ESM/node:test, Gradle, existing MCP JSON-RPC smoke client.

**Spec:** `docs/superpowers/specs/2026-07-12-runtime-evidence-autopilot-design.md`

## Global Constraints

- Debug builds only; never add production collection.
- Persisted fields `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `targetReliability`, `sourceCandidates`, `runtimeEvidence`, and `runtimeEvidenceIds` must not be renamed.
- `BridgeProtocol.VERSION` remains `1.3`; no sidekick method is added.
- `:fixthis-compose-core` must not depend on CLI, MCP, Android UI, or `.fixthis/` paths.
- Final shipped behavior explicitly creates new sessions with `auto_on_handoff`; sessions missing `runtimeEvidencePolicy` decode as `manual`. Keep new-session creation on `manual` until Task 9 connected product-path proof is green, then flip it in Task 10.
- Copy Prompt never starts automatic collection.
- Save to MCP waits at most 2,500 ms for evidence and still sends items on partial, failed, unsupported, or disabled collection.
- Raw collector output never enters session JSON, MCP responses, or compact handoff text.
- Persist only redacted artifacts under `.fixthis/runtime-evidence/<sessionId>/<captureId>/`; never commit `.fixthis/` or `graphify-out/`.
- Limits: logcat 2,000 lines or 512 KiB, memory 128 KiB, frame 128 KiB, baseline bundle 2 MiB, project 250 MiB.
- Built-in Perfetto/simpleperf, navigation replay, mobile companion, XML/View exact targeting, WebView DOM, Flutter, React Native, iOS, and production builds are out of scope.

---

### Task 1: Add Bounded ADB Execution

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Adb.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/AdbTest.kt`

**Interfaces:**
- Produces: `AdbExecutionLimits`, `AdbExecutionResult`, `AdbCommandRunner.runBounded()`, and `AdbFacade.execute()`.
- Consumed by: Task 2 only; MCP never accepts arbitrary ADB arguments.

- [ ] **Step 1: Write failing timeout, truncation, serial, and cleanup tests**

```kotlin
@Test
fun boundedExecutionScopesSerialAndReportsTruncation() {
    val runner = RecordingBoundedRunner(result(stdout = "1234", stdoutTruncated = true))
    val adb = Adb("adb", runner).forDevice("emulator-5554")
    val actual = adb.execute(
        listOf("shell", "pidof", "io.example"),
        AdbExecutionLimits(250, 4, 4),
    )
    assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "pidof", "io.example"), runner.command)
    assertTrue(actual.stdoutTruncated)
}

@Test
fun processRunnerDestroysTimedOutProcess() {
    val actual = ProcessAdbCommandRunner().runBounded(
        listOf(blockingScript.absolutePath),
        AdbExecutionLimits(50, 64, 64),
    )
    assertTrue(actual.timedOut)
    assertNull(actual.exitCode)
}
```

- [ ] **Step 2: Run the focused tests**

```bash
./gradlew :fixthis-cli:test --tests '*AdbTest*bounded*' --tests '*AdbTest*TimedOut*' --no-daemon
```

Expected: FAIL with unresolved bounded-execution types.

- [ ] **Step 3: Add the bounded contracts**

```kotlin
data class AdbExecutionLimits(
    val timeoutMillis: Long,
    val maxStdoutBytes: Int,
    val maxStderrBytes: Int,
)

data class AdbExecutionResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

fun interface AdbCommandRunner {
    fun run(command: List<String>): AdbResult

    fun runBounded(command: List<String>, limits: AdbExecutionLimits): AdbExecutionResult {
        val result = run(command)
        return AdbExecutionResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = false,
            stdoutTruncated = false,
            stderrTruncated = false,
        )
    }
}
```

Keep `AdbCommandRunner.run(command)` for existing callers and add the default `runBounded(command, limits)` above for existing test fakes. Add `fun execute(arguments: List<String>, limits: AdbExecutionLimits): AdbExecutionResult = error("Bounded ADB execution is unsupported")` to `AdbFacade`; override it in `Adb` to prepend the configured executable and selected `-s` serial.

- [ ] **Step 4: Implement bounded process I/O**

`ProcessAdbCommandRunner.runBounded()` must start stdout/stderr drain threads, retain only the configured byte count while continuing to drain, use `process.waitFor(timeout, TimeUnit.MILLISECONDS)`, destroy then forcibly destroy on timeout, close streams, join readers, and return `exitCode=null` on timeout. Decode retained bytes as UTF-8 with replacement.

- [ ] **Step 5: Run the full ADB and device-scope tests**

```bash
./gradlew :fixthis-cli:test --tests '*AdbTest*' --tests '*BridgeClientDeviceScopeTest*' --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Adb.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/AdbTest.kt
git commit -m "feat(cli): add bounded adb execution"
```

---

### Task 2: Implement Neutral CLI Collectors

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/runtime/RuntimeEvidenceContracts.kt`
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/runtime/RuntimeEvidenceCommandPlanner.kt`
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/runtime/RuntimeEvidenceParsers.kt`
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/runtime/AndroidRuntimeEvidenceCollector.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt`
- Create: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/runtime/RuntimeEvidenceCommandPlannerTest.kt`
- Create: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/runtime/RuntimeEvidenceParsersTest.kt`
- Create: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/runtime/AndroidRuntimeEvidenceCollectorTest.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/BridgeClientDeviceScopeTest.kt`

**Interfaces:**
- Consumes: Task 1 bounded execution.
- Produces: `CliRuntimeEvidenceKind`, `CliRuntimeEvidenceStatus`, `CliRuntimeEvidenceContext`, `CliRuntimeEvidenceResult`, `CliRuntimeEvidenceCapabilities`, and three `BridgeClient` evidence methods.

- [ ] **Step 1: Write failing planner and parser tests**

```kotlin
@Test
fun plannerUsesOnlyAllowlistedArgumentLists() {
    val plan = RuntimeEvidenceCommandPlanner.baseline(
        packageName = "io.example.app",
        pid = 123,
        logcatSince = "07-12 10:14:58.000",
    )
    assertEquals(listOf("logcat", "-d", "-v", "epoch", "--pid", "123", "-T", "07-12 10:14:58.000", "-t", "2000"), plan.appLog.arguments)
    assertEquals(listOf("logcat", "-b", "crash", "-d", "-v", "epoch", "-t", "200"), plan.crashLog.arguments)
    assertEquals(listOf("shell", "dumpsys", "activity", "exit-info", "io.example.app"), plan.exitInfo.arguments)
    assertEquals(listOf("shell", "dumpsys", "meminfo", "io.example.app"), plan.memory.arguments)
    assertEquals(listOf("shell", "dumpsys", "gfxinfo", "io.example.app"), plan.frame.arguments)
    assertTrue(plan.allCommands().none { "sh" in it.arguments || "-c" in it.arguments })
}
```

Also cover blank/multiple PID, missing `lastUpdateTime`, total PSS, janky frames, exception counts, permission denial, unsupported output, timeout, and truncation.

- [ ] **Step 2: Confirm the tests fail**

```bash
./gradlew :fixthis-cli:test --tests '*RuntimeEvidence*' --no-daemon
```

Expected: FAIL because `fixthis.cli.runtime` does not exist.

- [ ] **Step 3: Add neutral contracts**

```kotlin
enum class CliRuntimeEvidenceKind { CONTEXT, LOGCAT_WINDOW, MEMORY_SUMMARY, FRAME_SUMMARY }
enum class CliRuntimeEvidenceStatus { COMPLETE, PARTIAL, FAILED, UNSUPPORTED }

data class CliRuntimeEvidenceContext(
    val deviceSerial: String,
    val packageName: String,
    val packageAvailable: Boolean,
    val pid: Int?,
    val installEpochMillis: Long?,
    val currentActivity: String?,
    val bridgeProtocolVersion: String?,
    val currentScreenFingerprint: String?,
)

data class CliRuntimeEvidenceResult(
    val kind: CliRuntimeEvidenceKind,
    val status: CliRuntimeEvidenceStatus,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val command: List<String>,
    val output: String,
    val warnings: Set<String> = emptySet(),
    val failureCode: String? = null,
)

data class CliRuntimeEvidenceLimits(
    val baselineBytes: Int = 2 * 1024 * 1024,
    val logcatBytes: Int = 512 * 1024,
    val summaryBytes: Int = 128 * 1024,
)

data class CliRuntimeEvidenceCapabilities(
    val baselineAvailable: Boolean,
    val supportedCollectors: Set<CliRuntimeEvidenceKind>,
    val traceAvailable: Boolean = false,
    val limits: CliRuntimeEvidenceLimits = CliRuntimeEvidenceLimits(),
)
```

Keep these DTOs free of MCP imports and serialization annotations.

- [ ] **Step 4: Implement planner, parsers, and collector**

Validate application ids with `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`. Use context limits `(750 ms, 128 KiB)`, logcat `(1,500 ms, 512 KiB)`, and summary `(1,250 ms, 128 KiB)`. Start app logcat two seconds before the frozen-preview timestamp with `-T` when supported; fall back to bounded tail mode and record `timestamp_filter_unsupported`. Merge bounded current-PID logcat, crash buffer, and ActivityManager exit info into one logical `LOGCAT_WINDOW` result without exceeding its byte cap. The collector returns bounded raw output only through the internal Kotlin port and maps timeout/non-zero/unsupported/permission results to the neutral status.

- [ ] **Step 5: Add device-scoped BridgeClient methods**

```kotlin
fun runtimeEvidenceCapabilities(packageName: String): CliRuntimeEvidenceCapabilities
fun runtimeEvidenceContext(packageName: String): CliRuntimeEvidenceContext
fun collectRuntimeEvidence(
    packageName: String,
    kind: CliRuntimeEvidenceKind,
    screenCapturedAtEpochMillis: Long,
): CliRuntimeEvidenceResult
```

Each method must use one existing `requestScope()` so every ADB command and existing bridge `status`/screen-snapshot read stays on the same selected serial. `runtimeEvidenceContext()` may reuse existing bridge operations to fill protocol/fingerprint fields, but must not add a new bridge method or change protocol `1.3`.

- [ ] **Step 6: Run tests and commit**

```bash
./gradlew :fixthis-cli:test --tests '*RuntimeEvidence*' --tests '*BridgeClientDeviceScopeTest*' --no-daemon
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/runtime fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/runtime fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/BridgeClientDeviceScopeTest.kt
git commit -m "feat(cli): collect bounded android runtime evidence"
```

Expected: PASS, then one focused commit.

---

### Task 3: Extend Persisted Models And Handoff Grammar

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStoreDelegate.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/RuntimeEvidenceSerializationTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffEvidenceExtensionsTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`

**Interfaces:**
- Produces additive persisted enums/fields consumed by Tasks 5–9.
- Default decode policy is `MANUAL`; explicit Auto/Manual/Off round-trip now, while the new-session default stays Manual until the Task 10 rollout flip.

- [ ] **Step 1: Write failing compatibility and rendering tests**

```kotlin
@Test
fun legacySessionWithoutPolicyDecodesAsManual() {
    val session = fixThisJson.decodeFromString(SessionDto.serializer(), legacySessionJson)
    assertEquals(RuntimeEvidencePolicy.MANUAL, session.runtimeEvidencePolicy)
}

@Test
fun policyUpdateCanPersistAutoOnHandoff() {
    val session = store.openSession("io.example", "/repo")
    val updated = store.updateRuntimeEvidencePolicy(session.sessionId, RuntimeEvidencePolicy.AUTO_ON_HANDOFF)
    assertEquals(RuntimeEvidencePolicy.AUTO_ON_HANDOFF, updated.runtimeEvidencePolicy)
}
```

Add a renderer assertion for `logcat_window status=complete proximity=near`, a warning line, bounded summary, relative artifact, and absence of a raw secret.

- [ ] **Step 2: Confirm the tests fail**

```bash
./gradlew :fixthis-mcp:test --tests '*RuntimeEvidenceSerializationTest*' --tests '*CompactHandoffEvidenceExtensionsTest*' --no-daemon
```

Expected: FAIL with unresolved policy/status/trigger/proximity fields.

- [ ] **Step 3: Add exact enums and fields**

Add these serial-name enums; do not persist Kotlin enum names as wire values:

```kotlin
@Serializable
enum class RuntimeEvidencePolicy {
    @SerialName("auto_on_handoff") AUTO_ON_HANDOFF,
    @SerialName("manual") MANUAL,
    @SerialName("off") OFF,
}

@Serializable
enum class RuntimeEvidenceStatus {
    @SerialName("complete") COMPLETE,
    @SerialName("partial") PARTIAL,
    @SerialName("failed") FAILED,
    @SerialName("unsupported") UNSUPPORTED,
}

@Serializable
enum class RuntimeEvidenceTrigger {
    @SerialName("handoff_auto") HANDOFF_AUTO,
    @SerialName("console_manual") CONSOLE_MANUAL,
    @SerialName("mcp_manual") MCP_MANUAL,
    @SerialName("manual_attachment") MANUAL_ATTACHMENT,
}

@Serializable
enum class RuntimeEvidenceProximity {
    @SerialName("near") NEAR,
    @SerialName("delayed") DELAYED,
    @SerialName("stale") STALE,
}

@Serializable
enum class RuntimeEvidenceFailureReason {
    @SerialName("device_unavailable") DEVICE_UNAVAILABLE,
    @SerialName("device_changed") DEVICE_CHANGED,
    @SerialName("package_unavailable") PACKAGE_UNAVAILABLE,
    @SerialName("process_not_running") PROCESS_NOT_RUNNING,
    @SerialName("collector_unsupported") COLLECTOR_UNSUPPORTED,
    @SerialName("permission_denied") PERMISSION_DENIED,
    @SerialName("capture_timeout") CAPTURE_TIMEOUT,
    @SerialName("context_changed") CONTEXT_CHANGED,
    @SerialName("artifact_write_failed") ARTIFACT_WRITE_FAILED,
    @SerialName("quota_exceeded") QUOTA_EXCEEDED,
    @SerialName("artifact_missing") ARTIFACT_MISSING,
}
```

Extend `RuntimeEvidenceWarning` with serial names `output_truncated`, `redaction_applied`, `process_restarted`, `context_changed`, `stale_window`, `cumulative_not_windowed`, `timestamp_filter_unsupported`, and `pid_filter_unsupported`; retain all existing values.

Extend attachments additively:

```kotlin
val captureId: String? = null,
val status: RuntimeEvidenceStatus = RuntimeEvidenceStatus.COMPLETE,
val trigger: RuntimeEvidenceTrigger = RuntimeEvidenceTrigger.MANUAL_ATTACHMENT,
val screenCapturedAtEpochMillis: Long? = null,
val captureStartedAtEpochMillis: Long? = null,
val captureCompletedAtEpochMillis: Long? = null,
val proximity: RuntimeEvidenceProximity? = null,
val failureReason: RuntimeEvidenceFailureReason? = null,
```

Add `runtimeEvidencePolicy: RuntimeEvidencePolicy = MANUAL` to `SessionDto`. Do not change `openSession()` to Auto in this task; Task 10 performs that single rollout flip only after Task 9's strict connected proof passes.

- [ ] **Step 4: Update compact rendering**

Render newest attachments first within the existing cap:

```text
  runtimeEvidence:
    - logcat_window status=complete proximity=near
      summary: <bounded summary>
      artifact: <relative path>
      warning: redaction_applied
```

Keep `inlineSafe()` and the existing summary character cap.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :fixthis-mcp:test --tests '*RuntimeEvidence*' --tests '*CompactHandoffEvidenceExtensionsTest*' --tests '*HandoffEvaluationCorpusTest*' --no-daemon
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceModels.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStoreDelegate.kt fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session
git commit -m "feat(mcp): extend runtime evidence contracts"
```

Expected: PASS with legacy decode coverage.

---

### Task 4: Add Redacted Atomic Artifact Bundles

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceRedactor.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceArtifactStore.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceRedactorTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceArtifactStoreTest.kt`

**Interfaces:**
- Produces: `RuntimeEvidenceRedactor.redact()`, `RuntimeEvidenceArtifactStore.commit()`, `cleanupIncomplete()`, `deleteSession()`, and `CommittedRuntimeEvidenceBundle`.

- [ ] **Step 1: Write failing redaction, path, quota, and atomicity tests**

```kotlin
@Test
fun redactorRemovesSecretsButKeepsOrdinaryEmail() {
    val actual = RuntimeEvidenceRedactor().redact(
        "Authorization: Bearer abc.def.ghi user=dev@example.com password=hunter2",
    )
    assertEquals("Authorization: [REDACTED] user=dev@example.com password=[REDACTED]", actual.text)
    assertTrue(actual.redacted)
}
```

Also test cookies, JWTs, secret key/value pairs, sensitive query keys, FixThis tokens, additional regexes, unsafe ids, traversal, manifest-last visibility, write failure, 2 MiB bundle, 250 MiB project quota, and temp cleanup.

- [ ] **Step 2: Confirm tests fail**

```bash
./gradlew :fixthis-mcp:test --tests '*RuntimeEvidenceRedactorTest*' --tests '*RuntimeEvidenceArtifactStoreTest*' --no-daemon
```

Expected: FAIL because both classes are absent.

- [ ] **Step 3: Implement redaction and artifact contracts**

```kotlin
data class RuntimeEvidenceRedactionResult(val text: String, val redacted: Boolean)
data class RuntimeEvidenceArtifactInput(val type: RuntimeEvidenceType, val fileName: String, val redactedText: String)
data class CommittedRuntimeEvidenceBundle(
    val captureId: String,
    val relativeDirectory: String,
    val relativeFiles: Map<RuntimeEvidenceType, String>,
)

interface RuntimeEvidenceArtifactStore {
    fun commit(
        sessionId: String,
        captureId: String,
        inputs: List<RuntimeEvidenceArtifactInput>,
    ): CommittedRuntimeEvidenceBundle

    fun deleteBundle(sessionId: String, captureId: String)
    fun cleanupIncomplete(): Int
    fun cleanupOrphans(referencedCaptureIdsBySession: Map<String, Set<String>>): Int
    fun deleteSession(sessionId: String)
}
```

Implement it as `FileRuntimeEvidenceArtifactStore(projectRoot: File, redactor: RuntimeEvidenceRedactor)`. Redact before writing and re-redact summaries. `commit()` validates `[A-Za-z0-9._-]+` ids, rejects symlinks and canonical paths outside the project `.fixthis/runtime-evidence` root, computes quota, creates `<captureId>.tmp-<nonce>`, writes bounded files, writes `manifest.json` last, and atomically renames without a non-atomic success fallback. Delete the temp directory on every failure; never return a partial path. Limit project-configured redaction rules to 32 patterns of at most 256 characters, reject invalid patterns and nested quantifiers/backreferences/lookbehind, and test catastrophic-pattern rejection.

Run `cleanupIncomplete()` at startup. Run `cleanupOrphans()` only after session event replay has completed, using replayed attachment capture ids as the reference set: a committed bundle without an event is deleted, while a bundle whose event was written before a snapshot crash survives replay. Never run orphan deletion before replay.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :fixthis-mcp:test --tests '*RuntimeEvidenceRedactorTest*' --tests '*RuntimeEvidenceArtifactStoreTest*' --tests '*SessionArtifactJanitorTest*' --no-daemon
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime
git commit -m "feat(mcp): persist redacted evidence bundles"
```

Expected: PASS; test cleanup leaves no repository `.fixthis/` artifact.

---

### Task 5: Make Evidence And Policy Event-Backed

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionMutation.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionReducer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionEventPayloadFactory.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionBootReplayer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionReplayEngine.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStoreDelegate.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/SessionArtifactJanitor.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionEventPayloadFactoryTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/event/SessionBootReplayerTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionArtifactJanitorTest.kt`

**Interfaces:**
- Produces: `FeedbackSessionStore.attachRuntimeEvidence()` and `updateRuntimeEvidencePolicy()`.
- Invariant: append event before session snapshot; attachments and all item links change together.

- [ ] **Step 1: Write failing shared-link and replay tests**

```kotlin
@Test
fun runtimeEvidenceReplaysAttachmentAndTwoItemLinksTogether() {
    store.attachRuntimeEvidence(
        sessionId = "s1",
        expectedScreenId = "screen-1",
        itemIds = listOf("i1", "i2"),
        attachments = listOf(attachment("e1")),
        aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
    )
    val replayed = reopenStore().getSession("s1")
    assertEquals(listOf("e1"), replayed.runtimeEvidence.map { it.evidenceId })
    assertTrue(replayed.items.all { it.runtimeEvidenceIds == listOf("e1") })
}
```

Cover duplicate replay, policy replay, snapshot-save interruption, closed session, deleted item, wrong screen, and manual attachment replay.

- [ ] **Step 2: Confirm event tests fail**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreEventLogTest*runtimeEvidence*' --tests '*SessionBootReplayerTest*runtimeEvidence*' --no-daemon
```

Expected: FAIL because the events/store methods are absent.

- [ ] **Step 3: Add mutations, payloads, reducer, and replay**

Add `AttachRuntimeEvidence(attachments, itemIds, expectedScreenId, now)` and `UpdateRuntimeEvidencePolicy(policy, now)`. Use event names `runtimeEvidenceCaptured` and `runtimeEvidencePolicyUpdated`. Before appending evidence, require the session is open and every item exists on `expectedScreenId`; otherwise throw `RUNTIME_EVIDENCE_CONTEXT_CHANGED` and append nothing. Deduplicate evidence ids during reduce and replay.

After `SessionBootReplayer.replayAll()` has rebuilt every session, pass the replayed `(sessionId -> captureIds)` reference map to `SessionArtifactJanitor.cleanupRuntimeEvidence()`. Test both crash boundaries: final bundle without an appended event is removed, while event-without-snapshot replays first and preserves its bundle.

Expose the exact store operations:

```kotlin
fun attachRuntimeEvidence(
    sessionId: String,
    expectedScreenId: String,
    itemIds: List<String>,
    attachments: List<RuntimeEvidenceAttachment>,
    aggregateStatus: RuntimeEvidenceStatus,
): SessionDto

fun updateRuntimeEvidencePolicy(
    sessionId: String,
    policy: RuntimeEvidencePolicy,
): SessionDto
```

- [ ] **Step 4: Route the legacy manual tool through the event-backed store**

`RuntimeEvidenceService.attachManualSummary()` creates a default complete/manual attachment, then calls `store.attachRuntimeEvidence()`. Remove its `replaceSessionForDomain()` write.

- [ ] **Step 5: Run tests and commit**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreEventLogTest*' --tests '*SessionBootReplayerTest*' --tests '*RuntimeEvidenceServiceTest*' --no-daemon
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceService.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session
git commit -m "feat(mcp): replay runtime evidence mutations"
```

Expected: PASS, including write-ahead recovery.

---

### Task 6: Add Coordinator, CLI Adapter, And MCP Tool

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/RuntimeEvidenceBridge.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceSummarizer.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceAvailabilityService.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceCaptureCoordinator.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FeedbackToolOperations.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/handlers/DefaultMcpToolHandlers.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceCaptureCoordinatorTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceSummarizerTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceAvailabilityServiceTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/tools/RuntimeEvidenceToolRegistryTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`

**Interfaces:**
- Produces: `RuntimeEvidenceBridge`, `RuntimeEvidenceCaptureCoordinator.collect()`, `RuntimeEvidenceCaptureResult`, and `fixthis_collect_runtime_evidence`.

- [ ] **Step 1: Write failing coordinator tests**

Cover process-wide collector concurrency ≤2 across simultaneous coordinator calls, 2,500 ms timeout, two-item shared capture, in-flight dedupe, serial/install drift failure, PID restart partial, proximity boundaries, stale screen, closed/deleted item, unsupported, permission denial, truncation, redaction warning, quota/write failure, a referenced artifact disappearing after replay, and a single retry only for transient failures.

```kotlin
@Test
fun serialChangeFailsWithoutLinking() = runTest {
    fakeBridge.endContext = fakeBridge.startContext.copy(deviceSerial = "other")
    val actual = coordinator.collect(request())
    assertEquals(RuntimeEvidenceStatus.FAILED, actual.status)
    assertEquals(RuntimeEvidenceFailureReason.CONTEXT_CHANGED, actual.failureReason)
    assertTrue(store.getSession("s1").runtimeEvidence.isEmpty())
}
```

- [ ] **Step 2: Confirm tests fail**

```bash
./gradlew :fixthis-mcp:test --tests '*RuntimeEvidenceCaptureCoordinatorTest*' --tests '*RuntimeEvidenceToolRegistryTest*' --no-daemon
```

Expected: FAIL because coordinator, port, and tool are absent.

- [ ] **Step 3: Implement the narrow bridge and coordinator**

```kotlin
interface RuntimeEvidenceBridge {
    fun capabilities(packageName: String): CliRuntimeEvidenceCapabilities
    suspend fun context(packageName: String): CliRuntimeEvidenceContext
    suspend fun collect(packageName: String, kind: CliRuntimeEvidenceKind, screenCapturedAtEpochMillis: Long): CliRuntimeEvidenceResult
}

@Serializable
enum class RuntimeEvidencePreset {
    @SerialName("baseline") BASELINE,
    @SerialName("logs") LOGS,
    @SerialName("memory") MEMORY,
    @SerialName("performance") PERFORMANCE,
}

data class RuntimeEvidenceCaptureRequest(
    val sessionId: String,
    val itemIds: List<String>,
    val screenId: String,
    val preset: RuntimeEvidencePreset,
    val trigger: RuntimeEvidenceTrigger,
)

@Serializable
data class RuntimeEvidenceCaptureResult(
    val attempted: Boolean,
    val captureId: String? = null,
    val status: RuntimeEvidenceStatus? = null,
    val attachmentIds: List<String> = emptyList(),
    val linkedItemIds: List<String> = emptyList(),
    val artifactDirectory: String? = null,
    val warnings: List<RuntimeEvidenceWarning> = emptyList(),
    val failureReason: RuntimeEvidenceFailureReason? = null,
    val skippedReason: String? = null,
) {
    companion object {
        fun skipped(reason: String) = RuntimeEvidenceCaptureResult(
            attempted = false,
            skippedReason = reason,
        )
    }
}
```

`CliRuntimeEvidenceBridge` delegates to Task 2 `BridgeClient` methods. The coordinator owns one field-level `Semaphore(2)` shared by every request plus a `ConcurrentHashMap<CaptureKey, Deferred<RuntimeEvidenceCaptureResult>>` for in-flight automatic dedupe; remove entries in `finally`. It uses `withTimeoutOrNull(2_500)`, start/end context, proximity thresholds 3,000/15,000 ms, redaction before artifact commit, summary re-redaction, and event linkage only after commit. Delete an unlinked bundle on final context failure. A committed bundle left by a crash before event append is handled by Task 4's post-replay orphan reconciliation.

- [ ] **Step 4: Add deterministic summaries**

Report counts, exception class/tag, PSS/frame values, time distance, and limitations. Say “no matching error pattern in the selected window,” never “no error.” Add `cumulative_not_windowed` for gfxinfo.

`RuntimeEvidenceAvailabilityService.materialize(session)` must canonicalize every referenced artifact under the project runtime-evidence root without following symlinks. For a missing or unsafe path, return a read-time copy with `artifact_missing`, downgrade `COMPLETE` to `PARTIAL`, and keep the original capture/event metadata intact. Use the materialized view in compact handoff rendering and outward MCP/console responses; do not silently rewrite the journal or claim that the original capture failed.

- [ ] **Step 5: Register the new tool**

Schema: optional `sessionId`, required `itemId` and `preset`; preset enum exactly `baseline`, `logs`, `memory`, `performance`; `additionalProperties=false`. Keep `fixthis_capture_runtime_evidence` unchanged. Return only serialized `RuntimeEvidenceCaptureResult`, not raw collector output.

- [ ] **Step 6: Run tests and commit**

```bash
./gradlew :fixthis-mcp:test --tests '*RuntimeEvidence*' --tests '*McpProtocolTest*' --tests '*architecture*' --no-daemon
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp
git commit -m "feat(mcp): collect runtime evidence through mcp"
```

Expected: PASS; both evidence tools are listed and callable.

---

### Task 7: Add Manual Console Collection And Policy UI

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/SessionRoutes.kt`
- Create: `fixthis-mcp/src/main/console/runtimeEvidence.js`
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Modify: `scripts/console-tests.json`
- Create: `scripts/runtimeEvidenceConsole-test.mjs`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleSessionRoutesTest.kt`

**Interfaces:**
- Produces: `POST /api/items/{itemId}/runtime-evidence/collect` and `POST /api/sessions/{sessionId}/runtime-evidence-policy`.

- [ ] **Step 1: Write failing route and browser tests**

Test baseline request, terminal status rendering, unknown preset 400, missing item 404, closed session 409, policy persistence, and late-result fencing after active session changes.

```javascript
test('late result from prior session is dropped', async () => {
  const fixture = deferredApi();
  const controller = m.createRuntimeEvidenceController(fixture.ports);
  const pending = controller.collect({ sessionId: 's1', itemId: 'i1', preset: 'baseline' });
  fixture.setActiveSession('s2');
  fixture.resolve({ status: 'complete' });
  await pending;
  assert.equal(controller.stateFor('i1'), null);
});
```

- [ ] **Step 2: Confirm tests fail**

```bash
./gradlew :fixthis-mcp:test --tests '*ConsoleFeedbackItemRoutesTest*runtimeEvidence*' --tests '*ConsoleSessionRoutesTest*runtimeEvidence*' --no-daemon
node scripts/runtimeEvidenceConsole-test.mjs
```

Expected: FAIL because endpoints/module are absent.

- [ ] **Step 3: Add request models and routes**

```kotlin
@Serializable
data class CollectRuntimeEvidenceRequest(
    val sessionId: String? = null,
    val preset: RuntimeEvidencePreset = RuntimeEvidencePreset.BASELINE,
)

@Serializable
data class UpdateRuntimeEvidencePolicyRequest(val policy: RuntimeEvidencePolicy)
```

The collection route resolves a missing `sessionId` using the existing active-session rule, calls the coordinator with `CONSOLE_MANUAL` in `runBlocking`, and returns `RuntimeEvidenceCaptureResult`. The policy route calls the event-backed store and returns `SessionDto`. Both emit `session-updated` with the resolved session id.

- [ ] **Step 4: Add the browser module and UI**

Start `runtimeEvidence.js` with `// @requires api.js, state.js`. Provide `runtimeEvidenceStatusModel`, `collectRuntimeEvidenceForItem`, `updateRuntimeEvidencePolicy`, and `renderRuntimeEvidenceRows`. Replace the saved-item `Log` button with `Capture diagnostics`, terminal state, rows, and `Capture again`. Add top-bar Auto/Manual/Off bound to the session field, not `localStorage`.

- [ ] **Step 5: Build, test, and commit**

```bash
node scripts/build-console-assets.mjs
node scripts/runtimeEvidenceConsole-test.mjs
npm run console:test:fast
./gradlew :fixthis-mcp:test --tests '*ConsoleFeedbackItemRoutesTest*' --tests '*ConsoleSessionRoutesTest*' --tests '*ConsoleAssetContractTest*' --no-daemon
npm run console:responsive:stress
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console fixthis-mcp/src/main/console fixthis-mcp/src/main/resources/console scripts/console-tests.json scripts/runtimeEvidenceConsole-test.mjs fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console
git commit -m "feat(console): add runtime diagnostics controls"
```

Expected: PASS and one regenerated console bundle commit.

---

### Task 8: Collect Before Items Become Agent-Visible

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceHandoffService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsolePreviewModels.kt`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceHandoffServiceTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHandoffRoutesTest.kt`
- Create: `scripts/runtimeEvidenceHandoff-test.mjs`
- Modify: `scripts/console-tests.json`

**Interfaces:**
- Produces: `sendDraftToAgentWithRuntimeEvidence()` and evidence state in `AgentHandoffResponse`.
- Preserves: the existing lower-level `sendDraftToAgent()` for focused tests and Copy Prompt behavior.

- [ ] **Step 1: Write failing ordering and fallback tests**

```kotlin
@Test
fun autoCollectionFinishesBeforeRenderAndMarkSent() = runTest {
    val result = handoff.send("s1", listOf("i1", "i2"))
    assertEquals(listOf("collect", "render", "markSent"), calls)
    assertTrue(result.prompt.contains("runtimeEvidence:"))
    assertTrue(result.session.items.all { it.delivery == FeedbackDelivery.SENT })
}
```

Cover explicit Auto/Manual/Off, timeout, failed collection still sent, `fixthis_list_feedback`/`fixthis_read_feedback` excluding the draft batch until mark-sent, two concurrent Save requests producing one capture and one handoff batch, and Copy Prompt never invoking the coordinator.

- [ ] **Step 2: Confirm tests fail**

```bash
./gradlew :fixthis-mcp:test --tests '*RuntimeEvidenceHandoffServiceTest*' --tests '*ConsoleHandoffRoutesTest*runtimeEvidence*' --no-daemon
node scripts/runtimeEvidenceHandoff-test.mjs
```

Expected: FAIL because the service and response fields are absent.

- [ ] **Step 3: Implement exact handoff ordering**

The service reads the draft session, collects only for `AUTO_ON_HANDOFF`, re-reads the session, renders compact Markdown with final evidence, then calls the existing mark-sent path. Convert typed evidence failures to a failed result; do not catch annotation persistence or mark-sent failures.

```kotlin
val capture = when (before.runtimeEvidencePolicy) {
    AUTO_ON_HANDOFF -> coordinator.collect(autoRequest(before, itemIds))
    MANUAL -> RuntimeEvidenceCaptureResult.skipped("manual")
    OFF -> RuntimeEvidenceCaptureResult.skipped("off")
}
```

Use these exact return boundaries:

```kotlin
data class SendDraftToAgentWithRuntimeEvidenceResult(
    val session: SessionDto,
    val prompt: String,
    val runtimeEvidence: RuntimeEvidenceCaptureResult,
)

suspend fun sendDraftToAgentWithRuntimeEvidence(
    sessionId: String,
    itemIds: List<String>,
): SendDraftToAgentWithRuntimeEvidenceResult

@Serializable
data class AgentHandoffResponse(
    val session: SessionDto,
    val prompt: String,
    val runtimeEvidence: RuntimeEvidenceCaptureResult? = null,
)
```

- [ ] **Step 4: Route Save to MCP only**

Call the suspend method from `/api/agent-handoffs` via `runBlocking`; extend the response with defaulted evidence state. Show `Collecting diagnostics…` during the same request and terminal detail afterward. Do not modify `copyPrompt()`, `/handoff-preview`, or mark-handed-off.

- [ ] **Step 5: Test and commit**

```bash
./gradlew :fixthis-mcp:test --tests '*RuntimeEvidenceHandoffServiceTest*' --tests '*ConsoleHandoffRoutesTest*' --tests '*McpProtocolTest*' --no-daemon
node scripts/runtimeEvidenceHandoff-test.mjs
npm run console:browser:reliability
npm run handoff:eval:test
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console fixthis-mcp/src/main/console/prompt.js fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp scripts/runtimeEvidenceHandoff-test.mjs scripts/console-tests.json
git commit -m "feat(mcp): collect evidence before agent handoff"
```

Expected: PASS; healthy SSE adds no redundant polling.

---

### Task 9: Replace Direct Logcat Smoke With Product-Path Proof

**Files:**
- Modify: `scripts/runtime-evidence-smoke.mjs`
- Modify: `scripts/runtime-evidence-smoke-test.mjs`
- Modify: `scripts/android-proof-runner.mjs`
- Modify: `scripts/android-proof-runner-test.mjs`
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`
- Reuse: `scripts/mcp-json-rpc-client.mjs`

**Interfaces:**
- Produces strict report fields for MCP collection, linkage, artifact existence, bounded handoff, and replay.
- Removes direct generic `adb logcat -d -t 80` as a success condition.

- [ ] **Step 1: Write the new smoke contract first**

```javascript
assert.deepEqual(report.productPath, {
  tool: 'fixthis_collect_runtime_evidence',
  sessionId: 'session-1',
  itemIds: ['item-1'],
  captureStatus: 'complete',
  attachmentCount: 3,
  artifactVerified: true,
  compactHandoffBounded: true,
  replayVerified: true,
});
```

Add failures for missing attachment, outside artifact path, raw secret in session/handoff, replay mismatch, and missing collection tool. Remove acceptance of a fabricated generic row.

- [ ] **Step 2: Confirm old smoke fails**

```bash
npm run runtime-evidence:smoke:test
```

Expected: FAIL because the old report contains only direct-capture rows.

- [ ] **Step 3: Call the real MCP product path**

Reuse `createMcpJsonRpcClient`, create/obtain a sample feedback item through existing fixture helpers, explicitly update that session to `auto_on_handoff`, call `fixthis_collect_runtime_evidence` with `baseline`, exercise Save to MCP, read the session/handoff back, verify artifact paths remain inside `.fixthis/runtime-evidence`, scan JSON/Markdown for a known fake secret, restart MCP, and verify the same evidence ids replay. Non-strict missing Android is deferred; strict missing Android or any assertion fails. The smoke must not depend on the global new-session default, so it proves the Auto path before Task 10 enables that default.

- [ ] **Step 4: Wire connected Android proof**

Add after Agent loop smoke:

```javascript
{
  name: 'Runtime evidence product path',
  command: 'npm run runtime-evidence:smoke -- --strict',
  failureCode: 'runtime_evidence_failed',
  reportPath: 'build/reports/fixthis-runtime-evidence/report.json',
}
```

Add the failure catalog entry, runner test, and evidence-profile assertion.

- [ ] **Step 5: Run contracts, connected proof, and commit**

```bash
npm run runtime-evidence:smoke:test
npm run android:proof:test
npm run evidence:test
npm run runtime-evidence:smoke -- --strict
npm run android:proof -- --strict
git add scripts/runtime-evidence-smoke.mjs scripts/runtime-evidence-smoke-test.mjs scripts/android-proof-runner.mjs scripts/android-proof-runner-test.mjs scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs
git commit -m "test(runtime-evidence): prove connected product path"
```

Expected: unit contracts pass; connected commands pass on a ready device. If the environment is unavailable, report the strict blocker and do not change product code to mask it.

---

### Task 10: Update Maintained Contracts And Run Acceptance

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStoreDelegate.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`
- Modify: `docs/reference/mcp-tools.md`
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/reference/privacy.md`
- Modify: `docs/reference/threat-model.md`
- Modify: `docs/guides/troubleshooting.md`
- Modify: `docs/guides/project-map.md`
- Modify: `CONTRIBUTING.md`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/product/roadmap.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/releases/README.md`
- Modify: `docs/releases/unreleased.md`

**Interfaces:**
- Final rollout flips only newly created sessions to Auto after strict proof; maintained docs become authoritative after delivery, while the spec and plan remain historical context.

- [ ] **Step 1: Gate and flip the new-session default**

Confirm Task 9 produced a fresh passing strict report, then add this failing store test:

```kotlin
@Test
fun newSessionUsesAutoOnHandoffAfterConnectedProof() {
    val session = store.openSession("io.example", "/repo")
    assertEquals(RuntimeEvidencePolicy.AUTO_ON_HANDOFF, session.runtimeEvidencePolicy)
}
```

Run it, change only `FeedbackSessionStoreDelegate.openSession()` to pass `runtimeEvidencePolicy = AUTO_ON_HANDOFF`, and rerun the focused store/serialization tests. If the strict report is absent or failed, stop here and leave new sessions on Manual.

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest*newSessionUsesAutoOnHandoff*' --tests '*RuntimeEvidenceSerializationTest*' --no-daemon
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStoreDelegate.kt fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "feat(mcp): enable automatic runtime evidence"
```

- [ ] **Step 2: Update exact reference contracts**

Document both MCP tools, additive wire fields, legacy/new policy defaults, Auto/Manual/Off, Copy Prompt exclusion, paths, limits, redaction, quota, warning taxonomy, proximity thresholds, context drift, and host capability versus bridge version.

- [ ] **Step 3: Update operations and release docs**

Document focused `runtime-evidence:smoke -- --strict`, aggregate `android:proof -- --strict`, and exact recovery for `device_changed`, `permission_denied`, `capture_timeout`, `quota_exceeded`, and `artifact_missing`. Mark the feature delivered only after connected proof passes.

- [ ] **Step 4: Run focused and documentation gates**

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test --no-daemon
npm run runtime-evidence:smoke:test
npm run console:test:fast
npm run handoff:eval:test
./gradlew spotlessCheck detekt --no-daemon
node scripts/check-doc-consistency.mjs
bash scripts/check-docs-cli-surface.sh
npm run release:package:test
git diff --check
```

Expected: PASS with zero failures.

- [ ] **Step 5: Run connected, external, and full release gates**

```bash
npm run runtime-evidence:smoke -- --strict
npm run android:proof -- --strict
npm run external-fixture:matrix -- --strict
npm run release:check
```

Expected: PASS on a ready selected device; never report deferred evidence as connected proof.

- [ ] **Step 6: Refresh Graphify and verify hygiene**

```bash
graphify update .
git status --short
git diff --check
```

Expected: no `.fixthis/`, `graphify-out/`, build output, screenshots, or local fixtures are staged.

- [ ] **Step 7: Commit documentation**

Stage only the maintained files changed in this task:

```bash
git add docs/reference/mcp-tools.md docs/reference/output-schema.md docs/reference/feedback-console-contract.md docs/reference/privacy.md docs/reference/threat-model.md docs/guides/troubleshooting.md docs/guides/project-map.md CONTRIBUTING.md docs/contributing/release-readiness.md docs/product/roadmap.md CHANGELOG.md docs/releases/README.md docs/releases/unreleased.md
git diff --cached --check
git commit -m "docs: document runtime evidence autopilot"
```

---

## Final Completion Audit

- [ ] New sessions persist Auto; legacy sessions without the field decode as Manual.
- [ ] Manual and Off survive event replay.
- [ ] Copy Prompt never calls collection.
- [ ] Save keeps items draft until the evidence decision ends, then sends them even on evidence failure.
- [ ] Deadline is 2,500 ms and concurrency is at most two.
- [ ] Serial/install drift blocks linkage; PID drift yields partial evidence.
- [ ] Artifacts are redacted/bounded/local and raw bodies stay out of JSON, MCP, and Markdown.
- [ ] One handoff batch shares one capture id.
- [ ] Evidence/policy replay correctly; late closed/deleted/replaced-screen results do not link.
- [ ] Legacy `fixthis_capture_runtime_evidence` remains compatible.
- [ ] New `fixthis_collect_runtime_evidence` exposes only four allowlisted presets.
- [ ] Host capabilities do not change bridge version or core dependencies.
- [ ] Strict proof calls MCP, verifies artifacts and replay, and cannot pass from generic direct logcat alone.
- [ ] Focused, console, connected, external, release, docs, formatting, architecture, and Graphify checks have fresh passing output.
