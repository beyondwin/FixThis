# Project Stability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the remaining stabilization findings into green release gates, real dry-run publishing validation, stronger session-store concurrency, current hotspot budgets, a meaningful multi-tab harness, reproducible required-check observation, and hardened console mutation auth.

**Architecture:** Keep FixThis local-first and debug-only. Land the work in small, independently verifiable slices: first restore the full local CI signal, then make release validation executable, then tighten runtime lock scope and guardrails without changing MCP JSON or bridge protocol contracts. Console runtime changes stay inside the existing vanilla JS module structure and Kotlin server changes stay behind existing route/server boundaries.

**Tech Stack:** Kotlin/JVM 21, Gradle 9.3.x, Android Gradle Plugin 9.1.x, Detekt, JUnit/kotlin.test, vanilla browser JavaScript, Node.js 20 test runner, Playwright harness scripts, Markdown release docs.

**Related implementation details:** [`../specs/2026-05-15-project-stability-hardening-implementation-details.md`](../specs/2026-05-15-project-stability-hardening-implementation-details.md)

---

## File Structure

Likely modified Kotlin files:

- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` - split long fixture setup and line-length offenders.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt` - split long assertion message.
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FormatterExtensions.kt` - replace truncation magic number with a named suffix constant.
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactor.kt` - implement a small compaction interface.
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` - accept the compaction interface provider.
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt` - move compaction outside the store monitor.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt` - keep existing production wiring tests.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt` - add lock-scope regression coverage.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt` - register current source and test hotspots.
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` - delegate mutation auth to a dedicated module.
- Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleRequestAuth.kt` - central auth policy for console mutation requests.
- Test `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` or create `ConsoleRequestAuthTest.kt`.

Likely modified Gradle files:

- `gradle.properties` - add one local package group/version source if artifact dry-run is implemented.
- `build.gradle.kts` - assign root/subproject group and version from properties; optionally add root aggregate validation.
- `fixthis-compose-core/build.gradle.kts` - configure local Maven publication.
- `fixthis-compose-sidekick/build.gradle.kts` - configure debug AAR local Maven publication.
- `fixthis-gradle-plugin/build.gradle.kts` - configure plugin local publication only if the included build lacks it.
- `docs/contributing/release-readiness.md` - update dry-run command wording if the command changes.
- `docs/contributing/release-process.md` - update the exact dry-run command and evidence requirements.

Likely modified JS and script files:

- `fixthis-mcp/src/main/console/main.js` - refresh pending recovery/history on cross-tab storage changes.
- `fixthis-mcp/src/main/resources/console/app.js` and `.map` - regenerate with `node scripts/build-console-assets.mjs`.
- `scripts/console-harness.mjs` - make `multi-tab` assert receiver-page draft recovery.
- `scripts/console-harness.test.mjs` - test helper behavior for multi-tab scenario envelope and receiver targeting.
- Create `scripts/required-checks-observation.mjs` - optional maintainer helper for required-check streaks.
- `docs/contributing/required-checks.md` - document the helper and tracker update flow.

Guardrails:

- Do not commit `.fixthis/`.
- Do not rename persisted MCP fields.
- Do not bump `BridgeProtocol.VERSION`.
- Do not add remote publishing credentials or remote publish repositories.
- Do not suppress detekt findings that can be fixed mechanically.

---

### Task 1: Restore Full Local CI Readiness

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FormatterExtensions.kt`

- [ ] **Step 1: Capture the current detekt failure**

Run:

```bash
./gradlew :fixthis-mcp:detekt --warning-mode all --no-daemon
```

Expected before this task:

```text
CompactHandoffRendererTest.kt:2142:9: LongMethod
FormatterExtensions.kt:24:37: MagicNumber
ConsoleAssetContractTest.kt:693:1: MaxLineLength
CompactHandoffRendererTest.kt:2080:1: MaxLineLength
BUILD FAILED
```

- [ ] **Step 2: Replace the truncation magic number**

In `FormatterExtensions.kt`, change `compactQuotedValue` to use a named suffix:

```kotlin
private const val CompactTruncationSuffix = "..."

internal fun String.compactQuotedValue(maxLength: Int = 80): String {
    val normalized = inlineSafe().replace("\"", "'")
    return if (normalized.length <= maxLength) {
        normalized
    } else {
        normalized.take(maxLength - CompactTruncationSuffix.length) + CompactTruncationSuffix
    }
}
```

- [ ] **Step 3: Split the long target summary string**

In `CompactHandoffRendererTest.renderTargetSummaryForNode`, replace the inline
long string around line 2080 with a local value:

```kotlin
val expectedTarget =
    "  target: tag=\"comp:ReviewScreen:submit\"; text=\"Submit request\"; " +
        "contentDescription=\"Submit handoff request\"; role=Button"

assertTrue(markdown.contains(expectedTarget), markdown)
```

- [ ] **Step 4: Extract the handoff-quality fixture**

Move the inline `SessionDto` literal currently inside
`renderAddsHandoffQualitySummaryForRiskSignals()` into a private helper near
the other test helpers:

```kotlin
private fun handoffQualityRiskSignalSession(): SessionDto = SessionDto(
    sessionId = "session-quality",
    packageName = "io.beyondwin.fixthis.sample",
    projectRoot = "/repo",
    createdAtEpochMillis = 1L,
    updatedAtEpochMillis = 1L,
    screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Review")),
    items = listOf(
        qualityVisualAreaItem(),
        qualityRedactedNodeItem(),
        qualityOverlapNodeItem(),
    ),
)
```

Add three helper methods that contain the existing item literals unchanged:

```kotlin
private fun qualityVisualAreaItem(): AnnotationDto = AnnotationDto(
    itemId = "item-low",
    screenId = "screen-1",
    createdAtEpochMillis = 1L,
    updatedAtEpochMillis = 1L,
    target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 120f, 120f)),
    comment = "Visual area feedback",
    sequenceNumber = 1,
    targetReliability = TargetReliability(
        confidence = TargetConfidence.LOW,
        warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY),
    ),
    sourceCandidates = emptyList(),
)
```

`qualityRedactedNodeItem()` and `qualityOverlapNodeItem()` should preserve the
current field values from the inline fixture. The test body becomes:

```kotlin
@Test
fun renderAddsHandoffQualitySummaryForRiskSignals() {
    val markdown = CompactHandoffRenderer.render(handoffQualityRiskSignalSession())

    assertTrue(markdown.contains("Handoff quality:"), markdown)
    assertTrue(markdown.contains("1 low-confidence target"), markdown)
    assertTrue(markdown.contains("2 warning targets"), markdown)
    assertTrue(markdown.contains("1 overlap group"), markdown)
    assertTrue(markdown.contains("1 visual area"), markdown)
    assertTrue(markdown.contains("1 redacted target"), markdown)
    assertTrue(markdown.contains("1 stale source candidate"), markdown)
    assertTrue(markdown.contains("1 item without source candidates"), markdown)
}
```

- [ ] **Step 5: Split the console asset assertion message**

In `ConsoleAssetContractTest.consoleHtmlRendersSwitchedSessionAnnotationsBeforeFullRefresh`,
replace the long message argument with a local:

```kotlin
val repaintBeforeRefreshMessage =
    "Switching sessions should repaint the annotation list from the opened session " +
        "before slower device/connection refresh work."

assertTrue(
    Regex(
        "setConsoleSession\\(await withMutationLock\\(\\(\\) => requestJson\\('/api/session/open',[\\s\\S]*" +
            "\\)\\)\\);\\s+" +
            "renderCurrentSessionList\\(\\);\\s+" +
            "renderInspectorRegion\\(\\);\\s+" +
            "await refresh\\(\\);",
    ).containsMatchIn(openSession),
    repaintBeforeRefreshMessage,
)
```

- [ ] **Step 6: Verify detekt and focused tests**

Run:

```bash
./gradlew :fixthis-mcp:detekt --warning-mode all --no-daemon
./gradlew :fixthis-mcp:test \
  --tests "*CompactHandoffRendererTest" \
  --tests "*ConsoleAssetContractTest" \
  --no-daemon
```

Expected:

```text
BUILD SUCCESSFUL
```

If the Gradle 10 deprecation warning remains, run:

```bash
./gradlew :fixthis-mcp:detekt --warning-mode all --stacktrace --no-daemon
```

Record whether the deprecated `ReportingExtension.file(String)` call is from
local build logic or the Detekt plugin. If it is third-party, add a note to
`docs/contributing/required-checks.md` with the command, plugin version, and
upgrade owner.

- [ ] **Step 7: Verify full local CI**

Run:

```bash
npm run ci:local
```

Expected: complete success.

- [ ] **Step 8: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FormatterExtensions.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt \
  docs/contributing/required-checks.md
git commit -m "test(mcp): restore detekt gate"
```

If `docs/contributing/required-checks.md` was not changed, omit it from
`git add`.

---

### Task 2: Make Artifact Publish Dry-Run Executable

**Files:**
- Modify: `gradle.properties`
- Modify: `build.gradle.kts`
- Modify: `fixthis-compose-core/build.gradle.kts`
- Modify: `fixthis-compose-sidekick/build.gradle.kts`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/contributing/release-process.md`
- Optional modify: `fixthis-gradle-plugin/build.gradle.kts`

- [ ] **Step 1: Verify the current blocker**

Run:

```bash
./gradlew publishToMavenLocal --dry-run --no-daemon
```

Expected before this task:

```text
Task 'publishToMavenLocal' not found in root project 'FixThis' and its subprojects.
```

- [ ] **Step 2: Add one package version source**

Append to `gradle.properties`:

```properties

# Local artifact publication metadata. Public remote publishing is not enabled.
FIXTHIS_GROUP=io.beyondwin.fixthis
FIXTHIS_VERSION=0.2.0-SNAPSHOT
```

- [ ] **Step 3: Wire group and version in the root build**

In `build.gradle.kts`, after plugin declarations and before task configuration,
add:

```kotlin
val fixthisGroup = providers.gradleProperty("FIXTHIS_GROUP").orElse("io.beyondwin.fixthis")
val fixthisVersion = providers.gradleProperty("FIXTHIS_VERSION").orElse("0.2.0-SNAPSHOT")

subprojects {
    group = fixthisGroup.get()
    version = fixthisVersion.get()
}
```

- [ ] **Step 4: Publish the core JVM artifact locally**

In `fixthis-compose-core/build.gradle.kts`, add `maven-publish`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}
```

Then add:

```kotlin
java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "fixthis-compose-core"
        }
    }
}
```

- [ ] **Step 5: Publish the debug sidekick AAR locally**

In `fixthis-compose-sidekick/build.gradle.kts`, add `maven-publish`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}
```

Inside the existing `android` block, add:

```kotlin
publishing {
    singleVariant("debug") {
        withSourcesJar()
    }
}
```

After the `androidComponents` block, add:

```kotlin
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("debug") {
                from(components["debug"])
                artifactId = "fixthis-compose-sidekick"
            }
        }
    }
}
```

The `debug` variant is intentional because `src/debug/AndroidManifest.xml`
contains the AndroidX Startup provider that makes the sidekick debug-only.

- [ ] **Step 6: Validate the included Gradle plugin build**

Run:

```bash
./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run --no-daemon
./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon
```

Expected: both commands pass. If the included build has no
`publishToMavenLocal`, add `maven-publish` or `com.gradle.plugin-publish` in
`fixthis-gradle-plugin/build.gradle.kts` without adding remote credentials.

- [ ] **Step 7: Run root dry-run validation**

Run:

```bash
./gradlew publishToMavenLocal --dry-run --no-daemon
```

Expected: root project command now succeeds and schedules the core and sidekick
local publication tasks. If the included plugin build is not part of the root
task graph, update release docs to include the separate `-p fixthis-gradle-plugin`
command from Step 6.

- [ ] **Step 8: Update release docs**

In `docs/contributing/release-readiness.md` and
`docs/contributing/release-process.md`, keep the "not published" language and
replace the dry-run section with the exact commands that passed in Steps 6 and
7.

- [ ] **Step 9: Verify release docs and Gradle validation**

Run:

```bash
node scripts/check-release-readiness.mjs
node scripts/check-doc-consistency.mjs
./gradlew publishToMavenLocal --dry-run --no-daemon
./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run --no-daemon
./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon
git diff --check
```

Expected: all commands pass.

- [ ] **Step 10: Commit**

```bash
git add \
  gradle.properties \
  build.gradle.kts \
  fixthis-compose-core/build.gradle.kts \
  fixthis-compose-sidekick/build.gradle.kts \
  fixthis-gradle-plugin/build.gradle.kts \
  docs/contributing/release-readiness.md \
  docs/contributing/release-process.md
git commit -m "build: add local artifact publish dry run"
```

If `fixthis-gradle-plugin/build.gradle.kts` was not changed, omit it.

---

### Task 3: Move Event-Log Compaction Outside Store Lock

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactor.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt`

- [ ] **Step 1: Add an injectable compaction interface**

At the top of `EventLogCompactor.kt`, before `class EventLogCompactor`, add:

```kotlin
fun interface EventLogCompactionTask {
    fun runOnce(threshold: Int = 1000)
}
```

Change the class declaration:

```kotlin
class EventLogCompactor(
    private val directory: File,
    private val snapshotProvider: () -> SessionDto,
    private val snapshotWriter: (SessionDto) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : EventLogCompactionTask
```

Then add `override` to the existing `runOnce` function declaration:

```kotlin
override fun runOnce(threshold: Int) {
    val files = directory.listFiles { f -> f.isFile && f.extension == "jsonl" }
        ?.map { file -> EventLogFile(file, readEvent(file)) }
        ?.sortedBy { it.event.sequenceNumber }
        .orEmpty()
    if (files.size <= threshold) return
    val toArchive = files.dropLast(threshold)
    val compactedThroughSequenceNumber = toArchive.last().event.sequenceNumber
    val snapshot = snapshotProvider()
    snapshotWriter(snapshot)
    EventLogWriter(directory).writeCheckpoint(
        EventLogCheckpoint(
            sessionId = snapshot.sessionId,
            compactedThroughSequenceNumber = compactedThroughSequenceNumber,
            snapshotUpdatedAtEpochMillis = snapshot.updatedAtEpochMillis,
            createdAtEpochMillis = clock(),
        ),
    )
    val archive = File(directory, "archive").apply { mkdirs() }
    toArchive.forEach { entry ->
        entry.file.renameTo(File(archive, entry.file.name))
    }
}
```

- [ ] **Step 2: Change store providers to the interface**

In `FeedbackSessionStore.kt` and `FeedbackSessionStoreDelegate.kt`, change:

```kotlin
eventLogCompactorProvider: ((sessionId: String) -> EventLogCompactor)? = null
```

to:

```kotlin
eventLogCompactorProvider: ((sessionId: String) -> EventLogCompactionTask)? = null
```

Import `EventLogCompactionTask`.

- [ ] **Step 3: Add the lock-scope regression test**

Add a test to `FeedbackSessionStoreEventLogTest.kt`:

```kotlin
@Test
fun storeReadsAreNotBlockedBySlowPostMutationCompaction() {
    val root = Files.createTempDirectory("store-compaction-lock").toFile()
    try {
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths)
        val compactionStarted = CountDownLatch(1)
        val releaseCompaction = CountDownLatch(1)
        val ids = idGenerator()
        lateinit var store: FeedbackSessionStore
        store = FeedbackSessionStore(
            clock = { 100L },
            idGenerator = ids,
            persistence = persistence,
            eventLogWriterProvider = fastEventWriterFor(paths),
            eventLogReaderProvider = eventReaderFor(paths),
            eventLogCompactorProvider = {
                EventLogCompactionTask {
                    compactionStarted.countDown()
                    assertTrue(releaseCompaction.await(5, TimeUnit.SECONDS))
                }
            },
            eventLogCompactionThreshold = 0,
        )
        val session = store.openSession("com.test", root.absolutePath)
        val screen = makeScreen()

        val mutation = thread(start = true) {
            store.addScreen(session.sessionId, screen)
        }
        assertTrue(compactionStarted.await(5, TimeUnit.SECONDS))

        val read = AtomicReference<SessionDto>()
        val reader = thread(start = true) {
            read.set(store.getSession(session.sessionId))
        }
        reader.join(1_000)
        assertEquals(session.sessionId, read.get()?.sessionId)

        releaseCompaction.countDown()
        mutation.join(5_000)
    } finally {
        root.deleteRecursively()
    }
}
```

Add imports for `CountDownLatch`, `TimeUnit`, `AtomicReference`, `thread`, and
`assertEquals` if they are not already present.

- [ ] **Step 4: Refactor event-backed mutations**

Replace the current pattern where public methods are expression-bodied
`synchronized(lock)` blocks around `appendEventThenMutate` with a helper that
locks only append plus mutation:

```kotlin
private inline fun <T> withEventBackedMutation(
    sessionId: String,
    type: String,
    payload: JsonObject,
    mutate: () -> T,
): T {
    val result = synchronized(lock) {
        journal.append(sessionId = sessionId, type = type, payload = payload)
        mutate()
    }
    compactEventLogAfterMutation(sessionId)
    return result
}
```

Then convert each spec-backed mutation to return its value from `mutate`.
For example, `addScreen` should become structurally:

```kotlin
fun addScreen(sessionId: String, screen: SnapshotDto): SnapshotDto =
    withEventBackedMutation(
        sessionId = sessionId,
        type = "addScreen",
        payload = buildJsonObject {
            put("sessionId", sessionId)
            put("screen", eventLogJson.encodeToJsonElement(SnapshotDto.serializer(), screen))
        },
    ) {
        val session = getSessionLocked(sessionId)
        val (updated, captured) = mutations.addScreen(session, screen)
        save(updated)
        sessions[sessionId] = updated
        captured
    }
```

Keep the exact event payload shape for every mutation. Do not move
`journal.append()` after `save()`.

- [ ] **Step 5: Keep compaction failure diagnostic behavior**

Keep `compactEventLogAfterMutation` best-effort:

```kotlin
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun compactEventLogAfterMutation(sessionId: String) {
    val compactor = eventLogCompactorProvider?.invoke(sessionId) ?: return
    try {
        compactor.runOnce(eventLogCompactionThreshold)
    } catch (error: Exception) {
        replaySkippedSessions[sessionId] = SkippedFeedbackSession(
            path = "event-log-compaction",
            message = "Event log compaction failed: ${error.message ?: error::class.java.simpleName}",
        )
    }
}
```

If concurrent compactions can overlap in tests, add a per-session guard:

```kotlin
private val compactionLocks = mutableMapOf<String, Any>()

private fun compactionLock(sessionId: String): Any = synchronized(lock) {
    compactionLocks.getOrPut(sessionId) { Any() }
}
```

Use `synchronized(compactionLock(sessionId)) { compactor.runOnce(eventLogCompactionThreshold) }`
inside `compactEventLogAfterMutation`.

- [ ] **Step 6: Verify store and event-log tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests "*FeedbackSessionStoreEventLogTest" \
  --tests "*EventLogCompactorTest" \
  --tests "*SigkillReplayTest" \
  --no-daemon
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactor.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt
git commit -m "fix(mcp): run event compaction outside store lock"
```

---

### Task 4: Register Current Architecture Hotspots

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`

- [ ] **Step 1: Update budget maps**

Replace the current `legacyBudgets` / `remediationBudgets` shape with three
explicit maps:

```kotlin
val productionKotlinBudgets = mapOf(
    "${mcpMain}session/FeedbackSessionStoreDelegate.kt" to 760,
    "${mcpMain}tools/FixThisToolDispatcher.kt" to 520,
    "${mcpMain}session/FeedbackDraftService.kt" to 500,
    "${mcpMain}session/TargetEvidenceService.kt" to 440,
    "${mcpMain}session/SessionReplayEngine.kt" to 340,
    "${mcpMain}tools/McpToolRegistry.kt" to 290,
    "${sidekickBridge}BridgeServer.kt" to 180,
    "${sidekickBridge}BridgeModels.kt" to 220,
    "${sidekickBridge}AndroidBridgeEnvironment.kt" to 180,
    "fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt" to 540,
    "fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt" to 260,
    "${gradlePlugin}task/GenerateFixThisSourceIndexTask.kt" to 130,
    "${gradlePlugin}source/KotlinSourceScanner.kt" to 330,
)
val consoleJsBudgets = mapOf(
    "fixthis-mcp/src/main/console/annotations.js" to 670,
    "fixthis-mcp/src/main/console/history.js" to 530,
    "fixthis-mcp/src/main/console/presentation/annotationDetailView.js" to 520,
    "fixthis-mcp/src/main/console/main.js" to 440,
    "fixthis-mcp/src/main/console/state.js" to 440,
    "fixthis-mcp/src/main/console/domain/consoleReducer.js" to 410,
)
val testBudgets = mapOf(
    "fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt" to 2_400,
    "fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt" to 1_750,
    "fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt" to 1_680,
    "fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt" to 1_300,
    "${mcpConsoleTest}ConsoleAssetContractTest.kt" to 1_080,
    "${mcpConsoleTest}ConsoleFeedbackItemRoutesTest.kt" to 900,
)
```

Combine them with the existing strict remediation map. Preserve the
`FIXTHIS_STRICT_ARCH_BUDGETS` behavior for budgets that are meant to fail only
after a follow-up split lands.

- [ ] **Step 2: Add a ratchet comment**

Add this comment above the maps:

```kotlin
// These are ratchets. When a file shrinks, lower its budget in the same commit.
// Separate maps keep production source growth from being hidden by large tests.
```

- [ ] **Step 3: Verify budget test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ArchitectureHotspotBudgetTest" --no-daemon
```

Expected: pass.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt
git commit -m "test(architecture): register current hotspots"
```

---

### Task 5: Make Multi-Tab Harness Assert Cross-Tab Draft Recovery

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `scripts/console-harness.mjs`
- Modify: `scripts/console-harness.test.mjs`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js.map`

- [ ] **Step 1: Add a multi-tab recovery helper in the console runtime**

In `main.js`, near `loadPendingRecoveryForCurrentSession`, add:

```js
function refreshPendingRecoveryFromExternalStorageChange() {
  if (!state.session?.sessionId) return;
  if (draftFlow() || draftItemList().length) return;
  loadPendingRecoveryForCurrentSession();
  renderCurrentSessionList();
  renderInspectorRegion();
}
```

After the function is defined and before debug exports, add:

```js
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (event) => {
    if (!event.key || !event.key.startsWith(DraftWorkspaceKeyPrefix)) return;
    refreshPendingRecoveryFromExternalStorageChange();
  });
}
```

- [ ] **Step 2: Add a schema v2 draft envelope helper in the harness**

In `scripts/console-harness.mjs`, add:

```js
function multiTabDraftEnvelope() {
  const now = Date.now();
  return {
    schemaVersion: 2,
    sessionId: 'session-1',
    workspaceId: 'multi-tab-workspace',
    revision: 1,
    lifecycle: 'editing',
    context: {
      sessionId: 'session-1',
      previewId: 'preview-1',
      screenId: 'screen-1',
      screenFingerprint: 'fake-fingerprint',
      deviceSerial: 'fake-device',
      frozenAtEpochMillis: now,
      activityName: 'FakeActivity',
    },
    screen: {
      screenId: 'screen-1',
      capturedAtEpochMillis: now,
      displayName: 'Fake screen',
      roots: [],
      sourceIndexAvailable: false,
    },
    screenshotUrl: '/api/preview/preview-1/screenshot/full',
    items: [{
      draftItemId: 'draft-multi-tab-1',
      itemId: 'draft-multi-tab-1',
      screenId: 'screen-1',
      createdAtEpochMillis: now,
      updatedAtEpochMillis: now,
      target: {
        type: 'visual_area',
        boundsInWindow: { left: 10, top: 10, right: 80, bottom: 80 },
      },
      comment: 'Cross-tab draft',
      sequenceNumber: 1,
      status: 'open',
    }],
    history: { undoStack: [], redoStack: [] },
    updatedAtEpochMillis: now,
  };
}
```

Export it for tests if `console-harness.test.mjs` imports helpers directly:

```js
export { multiTabDraftEnvelope };
```

- [ ] **Step 3: Replace the multi-tab placeholder**

In the `multi-tab` branch of `runCell`, replace the placeholder with:

```js
const second = await context.newPage();
await second.goto(fixture.url, { waitUntil: 'domcontentloaded' });
await second.getByTestId('connection-card').waitFor({ state: 'visible', timeout: 8000 });
await page.evaluate((envelope) => {
  localStorage.setItem('fixthis.workspace.index.session-1', JSON.stringify([envelope.workspaceId]));
  localStorage.setItem(
    `fixthis.workspace.session-1.${envelope.workspaceId}`,
    JSON.stringify(envelope),
  );
}, multiTabDraftEnvelope());
await second.getByTestId('pending-recovery-banner').waitFor({ state: 'visible', timeout: 8000 });
const recoveryText = await second.getByTestId('pending-recovery-banner').textContent();
if (!/Cross-tab draft|draft comment/i.test(recoveryText || '')) {
  throw new Error(`receiver tab did not render cross-tab draft recovery: ${recoveryText}`);
}
await second.close();
```

- [ ] **Step 4: Add harness unit coverage**

In `scripts/console-harness.test.mjs`, add:

```js
test('multiTabDraftEnvelope writes a schema v2 recoverable workspace', () => {
  const envelope = multiTabDraftEnvelope();
  assert.equal(envelope.schemaVersion, 2);
  assert.equal(envelope.sessionId, 'session-1');
  assert.equal(envelope.context.sessionId, 'session-1');
  assert.equal(envelope.items.length, 1);
  assert.equal(envelope.items[0].comment, 'Cross-tab draft');
});
```

Update the import:

```js
import { parseArgs, selectScenarios, emitJunit, multiTabDraftEnvelope } from './console-harness.mjs';
```

- [ ] **Step 5: Rebuild console assets**

Run:

```bash
node scripts/build-console-assets.mjs
```

Expected: `app.js` and `app.js.map` update.

- [ ] **Step 6: Verify harness**

Run:

```bash
npm run console:harness:test
node scripts/console-harness.mjs --matrix multi-tab --viewport desktop-1280
npm run console:test:all
node scripts/build-console-assets.mjs --check
```

Expected: all commands pass.

- [ ] **Step 7: Commit**

```bash
git add \
  fixthis-mcp/src/main/console/main.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  scripts/console-harness.mjs \
  scripts/console-harness.test.mjs
git commit -m "test(console): assert multi-tab draft recovery"
```

---

### Task 6: Add Required-Check Observation Helper

**Files:**
- Create: `scripts/required-checks-observation.mjs`
- Modify: `docs/contributing/required-checks.md`
- Optional modify: `package.json`

- [ ] **Step 1: Create the observation script**

Create `scripts/required-checks-observation.mjs`:

```js
#!/usr/bin/env node
import { execFileSync } from 'node:child_process';

const workflows = [
  '.github/workflows/ci.yml',
  '.github/workflows/codeql.yml',
  '.github/workflows/connected-tests.yml',
  '.github/workflows/nightly-compat.yml',
];

function ghJson(args) {
  try {
    return JSON.parse(execFileSync('gh', args, { encoding: 'utf8' }));
  } catch (error) {
    console.error('error: failed to query GitHub Actions with gh.');
    console.error('Run `gh auth status` and retry from a checkout with repository access.');
    process.exitCode = 2;
    return null;
  }
}

function consecutiveSuccesses(runs) {
  let count = 0;
  for (const run of runs) {
    if (run.status !== 'completed') continue;
    if (run.conclusion === 'success') count += 1;
    else break;
  }
  return count;
}

for (const workflow of workflows) {
  const runs = ghJson([
    'run',
    'list',
    '--workflow',
    workflow,
    '--branch',
    'main',
    '--limit',
    '20',
    '--json',
    'conclusion,createdAt,databaseId,status,url',
  ]);
  if (!runs) continue;
  const completedSuccesses = runs.filter((run) => run.status === 'completed' && run.conclusion === 'success');
  const firstGreen = completedSuccesses.at(-1)?.createdAt || 'none in last 20 runs';
  const streak = consecutiveSuccesses(runs);
  console.log(`${workflow}`);
  console.log(`  first green in sample: ${firstGreen}`);
  console.log(`  consecutive green completed runs from latest: ${streak}`);
  console.log(`  latest: ${runs[0]?.url || 'no runs'}`);
}
```

- [ ] **Step 2: Add an npm helper**

In `package.json`, add:

```json
"checks:observation": "node scripts/required-checks-observation.mjs"
```

- [ ] **Step 3: Document the operator flow**

In `docs/contributing/required-checks.md`, add a "How to update this table"
section:

````markdown
## How to Update This Table

Run:

```bash
npm run checks:observation
```

The command uses `gh run list`, so it requires GitHub CLI authentication and
repository access. Copy the computed first-green and consecutive-green counts
into the table. Do not promote scheduled workflows to PR-required checks until
their longer observation windows are complete.
````

- [ ] **Step 4: Verify the script failure mode**

Run:

```bash
node --check scripts/required-checks-observation.mjs
npm run checks:observation
```

Expected with GitHub auth: prints workflow streaks.

Expected without GitHub auth: prints the clear auth error and exits with code
2. Do not wire this command into CI.

- [ ] **Step 5: Commit**

```bash
git add scripts/required-checks-observation.mjs package.json docs/contributing/required-checks.md
git commit -m "chore(ci): document required-check observation flow"
```

---

### Task 7: Harden Console Mutation Auth

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleRequestAuth.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`
- Modify: `docs/reference/threat-model.md`
- Optional modify: `docs/reference/feedback-console-contract.md`

- [ ] **Step 1: Add request auth module**

Create `ConsoleRequestAuth.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val ConsoleTokenHeader = "X-FixThis-Console-Token"

internal data class ConsoleRequestAuthConfig(
    val token: String,
    val host: String,
    val port: Int,
)

private val ConsoleMutatingMethods = setOf("POST", "PUT", "PATCH", "DELETE")

internal fun HttpExchange.requiresConsoleMutationGuard(): Boolean =
    requestURI.path.startsWith("/api/") && requestMethod.uppercase() in ConsoleMutatingMethods

internal fun HttpExchange.requireConsoleMutationAllowed(config: ConsoleRequestAuthConfig) {
    val origin = requestHeaders.getFirst("Origin")
    if (origin != null && !origin.isAllowedConsoleOrigin(config)) {
        throw FeedbackConsoleHttpException(403, "Forbidden origin")
    }
    val host = requestHeaders.getFirst("Host")
    if (host != null && !host.isAllowedConsoleHost(config)) {
        throw FeedbackConsoleHttpException(403, "Forbidden host")
    }
    if (!constantTimeEquals(config.token, requestHeaders.getFirst(ConsoleTokenHeader))) {
        throw FeedbackConsoleHttpException(403, "Missing console token")
    }
}

private fun String.isAllowedConsoleOrigin(config: ConsoleRequestAuthConfig): Boolean =
    this == "http://127.0.0.1:${config.port}" ||
        this == "http://localhost:${config.port}" ||
        this == "http://[::1]:${config.port}" ||
        this == "http://${config.host.toUrlHost()}:${config.port}"

private fun String.isAllowedConsoleHost(config: ConsoleRequestAuthConfig): Boolean =
    this == "127.0.0.1:${config.port}" ||
        this == "localhost:${config.port}" ||
        this == "[::1]:${config.port}" ||
        this == "${config.host.toUrlHost()}:${config.port}"

private fun constantTimeEquals(expected: String, supplied: String?): Boolean {
    if (supplied == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        supplied.toByteArray(StandardCharsets.UTF_8),
    )
}
```

Move `toUrlHost()` from `FeedbackConsoleServer.kt` into this file or keep it
internal in one place.

- [ ] **Step 2: Delegate from `FeedbackConsoleServer`**

Remove the local `ConsoleTokenHeader`, `ConsoleMutatingMethods`,
`requiresConsoleMutationGuard`, `requireConsoleMutationAllowed`, and
`isLocalConsoleOrigin` definitions from `FeedbackConsoleServer.kt`.

In `dispatch`, use the running port:

```kotlin
if (exchange.requiresConsoleMutationGuard()) {
    exchange.requireConsoleMutationAllowed(
        ConsoleRequestAuthConfig(
            token = consoleToken,
            host = host,
            port = runningServer().address.port,
        ),
    )
}
```

- [ ] **Step 3: Add auth tests**

Add these tests to `FeedbackConsoleServerTest.kt` or a new
`ConsoleRequestAuthTest.kt`:

- `mutatingRequestRejectsForeignHost`
- `mutatingRequestRejectsForeignOrigin`
- `mutatingRequestRejectsMissingToken`
- `mutatingRequestAllowsLocalHostOriginAndToken`
- `getRequestDoesNotRequireConsoleToken`

Use the existing console test client helpers. If those helpers cannot set
`Host`, create a small `HttpURLConnection` helper in the test that sets
`Origin`, `Host`, and `X-FixThis-Console-Token` explicitly.

- [ ] **Step 4: Document SEC-4 as closed**

In `docs/reference/threat-model.md`, move SEC-4 out of the deferred follow-up
section and describe:

- local origin allow-list;
- host-header pinning to the running loopback port;
- mutation token header;
- constant-time token comparison;
- GET routes not requiring the token.

- [ ] **Step 5: Verify auth tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests "*FeedbackConsoleServerTest" \
  --tests "*ConsoleRequestAuthTest" \
  --no-daemon
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: all commands pass.

- [ ] **Step 6: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleRequestAuth.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleRequestAuthTest.kt \
  docs/reference/threat-model.md \
  docs/reference/feedback-console-contract.md
git commit -m "fix(console): pin mutation auth to local host"
```

Omit optional files that were not changed.

---

### Task 8: Final Stabilization Verification

**Files:**
- Verify only unless a previous task reveals a small typo.

- [ ] **Step 1: Run full local CI**

```bash
npm run ci:local
```

Expected: complete success.

- [ ] **Step 2: Run release dry-run commands**

```bash
./gradlew publishToMavenLocal --dry-run --no-daemon
./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run --no-daemon
./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon
```

Expected: all pass.

- [ ] **Step 3: Run focused stabilization checks**

```bash
./gradlew :fixthis-mcp:detekt --warning-mode all --no-daemon
./gradlew :fixthis-mcp:test --tests "*ArchitectureHotspotBudgetTest" --no-daemon
node scripts/console-harness.mjs --matrix multi-tab --viewport desktop-1280
npm run console:test:all
npm audit --audit-level=moderate
node scripts/check-doc-consistency.mjs
node scripts/check-release-readiness.mjs
git diff --check
```

Expected: all pass. If the Detekt plugin still emits the Gradle 10 deprecation
after a compatible plugin spike, the final notes must name the plugin version,
stacktrace evidence, and upgrade owner.

- [ ] **Step 4: Record stabilization evidence**

Add a short entry to `docs/releases/unreleased.md` under the existing
hardening/reliability section:

```markdown
- Stabilized the release gate by restoring detekt, adding local artifact
  publish dry-run validation, tightening session-store compaction lock scope,
  registering current architecture hotspots, asserting multi-tab draft
  recovery, and closing console mutation auth hardening.
```

- [ ] **Step 5: Final commit**

```bash
git add docs/releases/unreleased.md
git commit -m "docs: record project stability hardening"
```

Skip this commit if a previous task already updated release notes with the same
information.
