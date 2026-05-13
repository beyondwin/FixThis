# Architecture Solid Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce FixThis architecture drift and SOLID risk by activating the existing core domain ports, splitting oversized MCP/bridge/source-index components, and adding guardrails that prevent new boundary violations.

**Architecture:** Keep `:fixthis-compose-core` as the pure Kotlin center. Preserve the MCP-console-first product architecture, debug-only sidekick runtime, local-first persistence, and all persisted JSON field names. Execute the work in compatibility-preserving slices: guardrails first, then domain adapters, then store/protocol/tool/scanner splits.

**Tech Stack:** Kotlin/JVM 21, Android Gradle Plugin, kotlinx.serialization, JUnit 4/Kotlin test, vanilla console JavaScript concatenated by `scripts/build-console-assets.mjs`, Node built-in test runner.

---

## Related Spec

Detailed analysis and rationale:
[`../specs/2026-05-13-architecture-solid-remediation-detailed-spec.md`](../specs/2026-05-13-architecture-solid-remediation-detailed-spec.md)

## Baseline

Current hotspots from source line counts:

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`: 993 lines.
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt`: 869 lines.
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`: 698 lines.
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`: 578 lines.
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt`: 531 lines.
- `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`: 442 lines.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`: 4,736 lines.

Current boundary check result:

- `fixthis-compose-core/src/main` has no Android/MCP/CLI/Gradle/sidekick imports.
- `fixthis-compose-sidekick/src/main`, `fixthis-compose-core/src/main`,
  `fixthis-gradle-plugin/src/main`, and `sample/src/main` have no MCP/CLI imports.
- `fixthis-mcp/src/main` imports CLI classes for `BridgeClient`, `AdbDevice`,
  and `fixThisJson`; this is an accepted current dependency but a P2 module
  cleanup target.

## File Structure

Create:

- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationWorkflow.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/domain/McpSessionRepository.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/domain/McpSnapshotRepository.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/domain/McpAnnotationRepository.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/domain/McpDomainRepositoryTest.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionMutation.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionReducer.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionEventJournal.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionReplayEngine.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SessionReducerTest.kt`
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeModels.kt`
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt`
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/AndroidBridgeEnvironment.kt`
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeScreenshotReader.kt`
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeSourceIndexReader.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisToolDispatcher.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisResourceDispatcher.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/BridgeResultCache.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/ConsoleServerManager.kt`
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceScoringPolicy.kt`
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceConfidencePolicy.kt`
- `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt`
- `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/XmlStringResourceScanner.kt`
- `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt`
- `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/ConsoleServerFixtures.kt`

Modify:

- `CONTRIBUTING.md`
- `docs/architecture/overview.md`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt`
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt`
- `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

Delete after replacement:

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationRepository.kt`

## Task 1: Add Architecture Guardrails

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Add module boundary test**

Create `ModuleBoundaryTest.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ModuleBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }

    @Test
    fun composeCoreDoesNotImportOuterModulesOrAndroid() {
        val forbidden = Regex("""^import (android|androidx|io\.beyondwin\.fixthis\.(mcp|cli|gradle|compose\.sidekick))""")
        val offenders = kotlinFiles("fixthis-compose-core/src/main")
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
                }
            }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    @Test
    fun sidekickGradlePluginAndSampleDoNotImportMcpOrCli() {
        val forbidden = Regex("""^import io\.beyondwin\.fixthis\.(mcp|cli)""")
        val offenders = listOf(
            "fixthis-compose-sidekick/src/main",
            "fixthis-gradle-plugin/src/main",
            "sample/src/main",
        ).flatMap(::kotlinFiles)
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
                }
            }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    private fun kotlinFiles(path: String): List<File> = File(root, path)
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()
}
```

- [ ] **Step 2: Add hotspot budget test**

Create `ArchitectureHotspotBudgetTest.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureHotspotBudgetTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }

    @Test
    fun handwrittenKotlinFilesStayUnderBudgetUnlessExplicitlyAllowed() {
        val budgets = mapOf(
            "fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt" to 1_050,
            "fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt" to 920,
            "fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt" to 740,
            "fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt" to 620,
            "fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt" to 560,
            "fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt" to 470,
        )
        val offenders = budgets.mapNotNull { (path, maxLines) ->
            val file = File(root, path)
            val lines = file.readLines().size
            if (lines > maxLines) "$path has $lines lines, budget is $maxLines" else null
        }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }
}
```

- [ ] **Step 3: Document the guardrail**

Add this paragraph under `CONTRIBUTING.md` after `## Required Local Checks`:

```markdown
Architecture guardrails are part of `:fixthis-mcp:test`. They assert that
`:fixthis-compose-core` stays free of Android/MCP/CLI imports and that known
large handwritten files cannot grow while they are being split. If a legitimate
architecture change needs a new dependency direction, record the decision in
`docs/architecture/adr/` before changing the guard.
```

- [ ] **Step 4: Verify**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.architecture.*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  CONTRIBUTING.md \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt
git commit -m "test: add architecture boundary guardrails"
```

## Task 2: Rename MCP Annotation Facade Before Adding Domain Adapters

**Files:**
- Move: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationRepository.kt` to `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationWorkflow.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Modify: tests that refer to `AnnotationRepository`

- [ ] **Step 1: Move the file and rename the class**

Use `git mv`:

```bash
git mv \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationRepository.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationWorkflow.kt
```

In the moved file, change the class declaration and KDoc opening:

```kotlin
/**
 * Owns MCP annotation workflow operations over DTO-backed sessions.
 *
 * This is intentionally not the core `AnnotationRepository` port. It coordinates
 * draft preview saves, target evidence, and status transitions at the MCP
 * boundary.
 */
@Suppress("TooManyFunctions")
class AnnotationWorkflow(
    private val store: FeedbackSessionStore,
    private val draftService: FeedbackDraftService,
)
```

Keep the existing methods and method bodies unchanged in this task.

- [ ] **Step 2: Update `FeedbackSessionService`**

Change the property construction:

```kotlin
private val annotations = AnnotationWorkflow(
    store = store,
    draftService = feedbackDraftService,
)
```

Also update the KDoc reference near the class header from
`[AnnotationRepository]` to `[AnnotationWorkflow]`.

- [ ] **Step 3: Verify mechanical rename**

Run:

```bash
rg -n "class AnnotationRepository|AnnotationRepository\\(" fixthis-mcp/src/main fixthis-mcp/src/test
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.*"
```

Expected:

- `rg` returns no old MCP class construction.
- Tests pass.

- [ ] **Step 4: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationWorkflow.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt
git commit -m "refactor(mcp): rename annotation workflow facade"
```

## Task 3: Add MCP Adapters For Core Domain Ports

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/domain/McpSessionRepository.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/domain/McpSnapshotRepository.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/domain/McpAnnotationRepository.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/domain/McpDomainRepositoryTest.kt`

- [ ] **Step 1: Add repository adapters**

Create `McpSessionRepository.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session.domain

import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.session.Session
import io.beyondwin.fixthis.compose.core.domain.session.SessionRepository
import io.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.toDomainSession
import io.beyondwin.fixthis.mcp.session.toSessionDto

class McpSessionRepository(
    private val store: FeedbackSessionStore,
) : SessionRepository {
    override suspend fun find(id: SessionId): Session? = try {
        store.getSession(id.value).toDomainSession()
    } catch (_: FeedbackSessionException) {
        null
    }

    override suspend fun save(session: Session): Session {
        store.replaceSessionForDomain(session.toSessionDto())
        return session
    }
}
```

Create `McpSnapshotRepository.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session.domain

import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot
import io.beyondwin.fixthis.compose.core.domain.snapshot.SnapshotRepository
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.toDomainSnapshot
import io.beyondwin.fixthis.mcp.session.toSnapshotDto

class McpSnapshotRepository(
    private val store: FeedbackSessionStore,
    private val sessionIdProvider: () -> String,
) : SnapshotRepository {
    override suspend fun find(id: SnapshotId): Snapshot? = store
        .getSession(sessionIdProvider())
        .screens
        .firstOrNull { it.screenId == id.value }
        ?.toDomainSnapshot()

    override suspend fun save(snapshot: Snapshot): Snapshot {
        store.addOrReplaceScreenForDomain(sessionIdProvider(), snapshot.toSnapshotDto())
        return snapshot
    }
}
```

Create `McpAnnotationRepository.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session.domain

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationRepository
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.toAnnotationDto
import io.beyondwin.fixthis.mcp.session.toDomainAnnotation

class McpAnnotationRepository(
    private val store: FeedbackSessionStore,
) : AnnotationRepository {
    override suspend fun save(annotation: Annotation): Annotation {
        val dto = annotation.toAnnotationDto()
        store.addOrReplaceAnnotationForDomain(annotation.sessionId.value, dto)
        return store
            .getSession(annotation.sessionId.value)
            .items
            .first { it.itemId == annotation.id.value }
            .toDomainAnnotation(annotation.sessionId.value)
    }
}
```

- [ ] **Step 2: Add store methods used only by domain adapters**

In `FeedbackSessionStore.kt`, add these public methods near the other public API
methods:

```kotlin
fun replaceSessionForDomain(session: SessionDto): SessionDto = synchronized(lock) {
    save(session)
    sessions[session.sessionId] = session
    if (session.status != SessionStatusDto.CLOSED) currentSessionId = session.sessionId
    session
}

fun addOrReplaceScreenForDomain(sessionId: String, screen: SnapshotDto): SessionDto = synchronized(lock) {
    val session = getSessionLocked(sessionId)
    val updated = session.copy(
        screens = session.screens.filterNot { it.screenId == screen.screenId } + screen,
        updatedAtEpochMillis = clock(),
    )
    commitSessionMutation(session, updated)
}

fun addOrReplaceAnnotationForDomain(sessionId: String, annotation: AnnotationDto): SessionDto = synchronized(lock) {
    val session = getSessionLocked(sessionId)
    require(session.screens.any { it.screenId == annotation.screenId }) {
        "Cannot save annotation for unknown screen: ${annotation.screenId}"
    }
    val updated = session.copy(
        items = session.items.filterNot { it.itemId == annotation.itemId } + annotation,
        updatedAtEpochMillis = clock(),
    )
    commitSessionMutation(session, updated)
}
```

These methods intentionally preserve DTO field names and reuse the existing
session persistence boundary.

- [ ] **Step 3: Add adapter tests**

Create `McpDomainRepositoryTest.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session.domain

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationTarget
import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class McpDomainRepositoryTest {
    @Test
    fun adaptersRoundTripSessionSnapshotAndAnnotationThroughStore() = runBlocking {
        val store = FeedbackSessionStore(
            clock = { 100L },
            idGenerator = sequenceOf("session-1").iterator()::next,
        )
        store.openSession("io.beyondwin.fixthis.sample", "/repo")

        val sessions = McpSessionRepository(store)
        val snapshots = McpSnapshotRepository(store) { "session-1" }
        val annotations = McpAnnotationRepository(store)

        snapshots.save(
            Snapshot(
                id = SnapshotId("screen-1"),
                capturedAtEpochMillis = 101L,
                displayName = "MainActivity",
            ),
        )
        annotations.save(
            Annotation(
                id = AnnotationId("item-1"),
                sessionId = SessionId("session-1"),
                snapshotId = SnapshotId("screen-1"),
                createdAtEpochMillis = 102L,
                updatedAtEpochMillis = 102L,
                target = AnnotationTarget.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Tighten spacing",
            ),
        )

        val session = sessions.find(SessionId("session-1"))

        assertNotNull(session)
        assertEquals(listOf("screen-1"), session.snapshots.map { it.id.value })
        assertEquals(listOf("item-1"), session.annotations.map { it.id.value })
        assertEquals("Tighten spacing", session.annotations.single().comment)
    }
}
```

- [ ] **Step 4: Verify**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.domain.McpDomainRepositoryTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/domain \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/domain/McpDomainRepositoryTest.kt
git commit -m "refactor(mcp): adapt feedback store to core domain ports"
```

## Task 4: Introduce A Pure Session Reducer Before Splitting The Store

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionMutation.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionReducer.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SessionReducerTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`

- [ ] **Step 1: Add mutation model and reducer**

Create `SessionMutation.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

sealed interface SessionMutation {
    data class AddScreen(val screen: SnapshotDto, val now: Long) : SessionMutation
    data class AddScreenWithItems(
        val screen: SnapshotDto,
        val items: List<AnnotationDto>,
        val now: Long,
    ) : SessionMutation
    data class ReplaceItems(val items: List<AnnotationDto>, val now: Long) : SessionMutation
    data class DeleteScreen(val screenId: String, val now: Long) : SessionMutation
    data class DeleteItem(val itemId: String, val now: Long) : SessionMutation
    data class AddHandoff(
        val batch: FeedbackHandoffBatch,
        val items: List<AnnotationDto>,
        val now: Long,
    ) : SessionMutation
    data class Close(val now: Long) : SessionMutation
    data class MarkReadyForAgent(val now: Long) : SessionMutation
}
```

Create `SessionReducer.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

object SessionReducer {
    fun reduce(session: SessionDto, mutation: SessionMutation): SessionDto = when (mutation) {
        is SessionMutation.AddScreen -> session.copy(
            screens = session.screens + mutation.screen,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.AddScreenWithItems -> session.copy(
            screens = session.screens + mutation.screen,
            items = session.items + mutation.items,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.ReplaceItems -> session.copy(
            items = mutation.items,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.DeleteScreen -> deleteScreen(session, mutation.screenId, mutation.now)
        is SessionMutation.DeleteItem -> deleteItem(session, mutation.itemId, mutation.now)
        is SessionMutation.AddHandoff -> session.copy(
            items = mutation.items,
            handoffBatches = session.handoffBatches + mutation.batch,
            status = SessionStatusDto.READY_FOR_AGENT,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.Close -> session.copy(
            status = SessionStatusDto.CLOSED,
            updatedAtEpochMillis = mutation.now,
        )
        is SessionMutation.MarkReadyForAgent -> session.copy(
            status = SessionStatusDto.READY_FOR_AGENT,
            updatedAtEpochMillis = mutation.now,
        )
    }

    private fun deleteScreen(session: SessionDto, screenId: String, now: Long): SessionDto {
        val removedItemIds = session.items
            .filter { it.screenId == screenId }
            .map { it.itemId }
            .toSet()
        return session.copy(
            screens = session.screens.filterNot { it.screenId == screenId },
            items = session.items.filterNot { it.screenId == screenId },
            handoffBatches = session.handoffBatches
                .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it in removedItemIds }) }
                .filter { it.itemIds.isNotEmpty() },
            updatedAtEpochMillis = now,
        )
    }

    private fun deleteItem(session: SessionDto, itemId: String, now: Long): SessionDto = session.copy(
        items = session.items.filterNot { it.itemId == itemId },
        handoffBatches = session.handoffBatches
            .map { batch -> batch.copy(itemIds = batch.itemIds.filterNot { it == itemId }) }
            .filter { it.itemIds.isNotEmpty() },
        updatedAtEpochMillis = now,
    )
}
```

- [ ] **Step 2: Add reducer tests**

Create `SessionReducerTest.kt` with tests for screen deletion, item deletion,
handoff addition, and close mutation:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionReducerTest {
    @Test
    fun deleteScreenRemovesItsItemsAndPrunesEmptyBatches() {
        val session = baseSession().copy(
            screens = listOf(screen("screen-1"), screen("screen-2")),
            items = listOf(item("item-1", "screen-1"), item("item-2", "screen-2")),
            handoffBatches = listOf(batch("batch-1", listOf("item-1"))),
        )

        val updated = SessionReducer.reduce(
            session,
            SessionMutation.DeleteScreen(screenId = "screen-1", now = 200L),
        )

        assertEquals(listOf("screen-2"), updated.screens.map { it.screenId })
        assertEquals(listOf("item-2"), updated.items.map { it.itemId })
        assertEquals(emptyList(), updated.handoffBatches)
        assertEquals(200L, updated.updatedAtEpochMillis)
    }

    @Test
    fun addHandoffSetsReadyForAgentAndStoresBatch() {
        val sent = item("item-1", "screen-1").copy(delivery = FeedbackDelivery.SENT)
        val handoff = batch("batch-1", listOf("item-1"))

        val updated = SessionReducer.reduce(
            baseSession().copy(items = listOf(sent)),
            SessionMutation.AddHandoff(batch = handoff, items = listOf(sent), now = 300L),
        )

        assertEquals(SessionStatusDto.READY_FOR_AGENT, updated.status)
        assertEquals(listOf("batch-1"), updated.handoffBatches.map { it.batchId })
        assertEquals(300L, updated.updatedAtEpochMillis)
    }

    private fun baseSession(): SessionDto = SessionDto(
        sessionId = "session-1",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )

    private fun screen(id: String): SnapshotDto = SnapshotDto(
        screenId = id,
        capturedAtEpochMillis = 100L,
        displayName = "MainActivity",
    )

    private fun item(id: String, screenId: String): AnnotationDto = AnnotationDto(
        itemId = id,
        screenId = screenId,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
        target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
        comment = "Fix spacing",
    )

    private fun batch(id: String, itemIds: List<String>): FeedbackHandoffBatch = FeedbackHandoffBatch(
        batchId = id,
        sequenceNumber = 1,
        createdAtEpochMillis = 100L,
        itemIds = itemIds,
        markdownSnapshot = null,
    )
}
```

- [ ] **Step 3: Use reducer in one low-risk store path**

In `FeedbackSessionStore.closeSession`, replace the manual copy with:

```kotlin
val closed = SessionReducer.reduce(session, SessionMutation.Close(now))
```

In `FeedbackSessionStore.markReadyForAgent`, replace the manual copy with:

```kotlin
val updated = SessionReducer.reduce(session, SessionMutation.MarkReadyForAgent(now))
```

- [ ] **Step 4: Verify**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.SessionReducerTest"
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.FeedbackSessionStoreTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionMutation.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionReducer.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SessionReducerTest.kt
git commit -m "refactor(mcp): introduce pure session reducer"
```

## Task 5: Split Event Journal And Replay Engine Out Of `FeedbackSessionStore`

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionEventJournal.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionReplayEngine.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- Test: existing event-log tests

- [ ] **Step 1: Extract event append sequencing**

Create `SessionEventJournal.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.mcp.session.eventlog.EventLogReader
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import io.beyondwin.fixthis.mcp.session.eventlog.SessionEvent
import kotlinx.serialization.json.JsonObject

class SessionEventJournal(
    private val clock: () -> Long,
    private val idGenerator: () -> String,
    private val writerProvider: ((sessionId: String) -> EventLogWriter)?,
    private val readerProvider: ((sessionId: String) -> EventLogReader)?,
) {
    private val lastReplayedSeq = mutableMapOf<String, Long>()
    private val nextSeqMap = mutableMapOf<String, Long>()

    val hasReader: Boolean get() = readerProvider != null

    fun reader(sessionId: String): EventLogReader? = readerProvider?.invoke(sessionId)

    fun recordReplaySequence(sessionId: String, sequenceNumber: Long) {
        lastReplayedSeq[sessionId] = sequenceNumber
        nextSeqMap[sessionId] = sequenceNumber + 1L
    }

    fun seedFromActiveLog(sessionId: String, reader: EventLogReader) {
        val maxActiveSeq = reader.maxActiveSequenceNumberOrNull() ?: return
        val maxSeq = maxOf(lastReplayedSeq.getOrDefault(sessionId, -1L), maxActiveSeq)
        recordReplaySequence(sessionId, maxSeq)
    }

    fun append(sessionId: String, type: String, payload: JsonObject) {
        val writer = writerProvider?.invoke(sessionId) ?: return
        writer.append(
            SessionEvent(
                eventId = idGenerator(),
                sequenceNumber = nextEventSeq(sessionId),
                epochMillis = clock(),
                actor = "mcp",
                type = type,
                payload = payload,
            ),
        )
    }

    private fun nextEventSeq(sessionId: String): Long {
        val current = nextSeqMap.getOrDefault(
            sessionId,
            lastReplayedSeq.getOrDefault(sessionId, -1L) + 1L,
        )
        nextSeqMap[sessionId] = current + 1L
        return current
    }
}
```

- [ ] **Step 2: Extract replay logic**

Create `SessionReplayEngine.kt` by moving the current checkpoint/read/replay
functions from `FeedbackSessionStore`. Keep the same event type strings and
payload decoding. The public API should be:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.mcp.session.eventlog.EventLogReader

class SessionReplayEngine(
    private val journal: SessionEventJournal,
    private val persistence: FeedbackSessionPersistence?,
) {
    fun replay(
        sessionId: String,
        shell: SessionDto,
        reader: EventLogReader,
        recordSkipped: (sessionId: String, path: String, message: String) -> Unit,
    ): SessionDto
}
```

Implementation detail: keep the exact current semantics:

- Invalid checkpoint records a skipped session and seeds the next sequence from
  active log.
- No checkpoint means mutable lists start empty.
- Checkpoint means replay starts from the persisted shell and ignores events at
  or below `compactedThroughSequenceNumber`.
- Unknown or malformed event payload is skipped.

- [ ] **Step 3: Shrink `FeedbackSessionStore`**

In `FeedbackSessionStore`, replace:

- `lastReplayedSeq`
- `nextSeqMap`
- `readReplayCheckpoint`
- `replayEventsFrom`
- `applyEvent`
- all `apply*` replay helpers

with:

```kotlin
private val journal = SessionEventJournal(
    clock = clock,
    idGenerator = idGenerator,
    writerProvider = eventLogWriterProvider,
    readerProvider = eventLogReaderProvider,
)
private val replayEngine = SessionReplayEngine(journal, persistence)
```

Change `appendEventThenMutate` to call:

```kotlin
journal.append(sessionId = sessionId, type = type, payload = payload)
mutate()
```

Change boot replay loop to:

```kotlin
val reader = journal.reader(sid) ?: return@forEach
val shell = sessions[sid] ?: return@forEach
val replayed = replayEngine.replay(
    sessionId = sid,
    shell = shell,
    reader = reader,
    recordSkipped = ::recordReplaySkippedSession,
)
sessions[sid] = replayed
```

- [ ] **Step 4: Verify**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.FeedbackSessionStoreEventLogTest"
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.SigkillReplayTest"
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.session.eventlog.EventLogCompactorTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionEventJournal.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionReplayEngine.kt
git commit -m "refactor(mcp): split session event replay from store"
```

## Task 6: Split Sidekick Bridge File Without Changing Protocol

**Files:**
- Create: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeModels.kt`
- Create: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRuntime.kt`
- Create: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/AndroidBridgeEnvironment.kt`
- Create: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeScreenshotReader.kt`
- Create: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeSourceIndexReader.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`

- [ ] **Step 1: Move serializable DTOs to `BridgeModels.kt`**

Move these declarations unchanged:

- `BridgeStatus`
- `BridgeCapabilities`
- `BridgeScreenInspection`
- `BridgeScreenSnapshot`
- `BridgeSourceIndexResult`
- `BridgeInspectedRoot`
- `BridgeUiVerificationResult`
- `BridgeScreenshotReadResult`
- `BridgeHeartbeatResult`
- `mapOrientation`
- `mapWindowMode`
- `inferWindowMode`

Keep package:

```kotlin
package io.beyondwin.fixthis.compose.sidekick.bridge
```

- [ ] **Step 2: Move runtime singleton to `BridgeRuntime.kt`**

Move `FixThisBridgeRuntime` unchanged, including its lock, `connectionState`,
and `start/onActivityResumed/onActivityDestroyed/stopForTest` methods.

- [ ] **Step 3: Move Android environment to `AndroidBridgeEnvironment.kt`**

Move `AndroidBridgeEnvironment`, `Application.isDebuggable`, and Android-only
source-index helpers out of `BridgeServer.kt`. Keep constructor defaults and
method bodies unchanged.

- [ ] **Step 4: Move screenshot read helper**

Create `BridgeScreenshotReader.kt` with:

```kotlin
package io.beyondwin.fixthis.compose.sidekick.bridge

import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

internal class BridgeScreenshotReader(
    private val environment: BridgeEnvironment,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun read(params: JsonObject): BridgeScreenshotReadResult {
        require(params.stringParam("path") == null) {
            "Explicit screenshot paths are not supported; use kind=full or kind=crop for the latest screen snapshot"
        }
        val kind = params.stringParam("kind") ?: "full"
        require(kind == "full" || kind == "crop") { "Unsupported screenshot kind: $kind" }
        val source = params.stringParam("source") ?: "screenSnapshot"
        require(source == "screenSnapshot") { "Unsupported screenshot source: $source" }
        val screenshot = environment.getLastScreenSnapshot()?.screenshot
        val path = if (kind == "crop") screenshot?.cropPath else screenshot?.fullPath
        require(!path.isNullOrBlank()) { "No screenshot path is available" }
        return withContext(ioDispatcher) {
            val file = File(path).canonicalFile
            val cacheDirectory = environment.screenshotCacheDirectory().canonicalFile
            require(PathSafety.isUnder(file, cacheDirectory)) {
                "Screenshot path is outside the FixThis screenshot cache"
            }
            require(file.extension.equals("png", ignoreCase = true)) { "Screenshot must be a PNG file" }
            require(file.exists() && file.isFile) { "Screenshot does not exist: $path" }
            require(file.length() <= MaxScreenshotReadBytes) { "Screenshot is too large to read" }
            require(file.hasPngHeader()) { "Screenshot file is not PNG data" }
            BridgeScreenshotReadResult(
                path = file.absolutePath,
                kind = kind,
                mimeType = "image/png",
                base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP),
            )
        }
    }

    private companion object {
        const val MaxScreenshotReadBytes = 16L * 1024L * 1024L
    }
}
```

Also move `File.hasPngHeader` and `BridgePngHeader` into this file.

- [ ] **Step 5: Keep `BridgeServer.kt` as server and request router only**

`BridgeServer.kt` should retain:

- `BridgeServer`
- `BridgeEnvironment`
- `handleRequest`
- `verifyUiChange`
- socket accept/client loops
- `JsonObject.stringParam`

Instantiate:

```kotlin
private val screenshotReader = BridgeScreenshotReader(environment, ioDispatcher)
```

Then route `"readScreenshot"` to:

```kotlin
"readScreenshot" -> BridgeProtocol.json.encodeToJsonElement(screenshotReader.read(request.params))
```

- [ ] **Step 6: Verify**

Run:

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests "io.beyondwin.fixthis.compose.sidekick.bridge.*"
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.BridgeProtocolVersionSyncTest"
```

Expected: PASS. `BridgeProtocol.VERSION` stays `1.3`.

- [ ] **Step 7: Commit**

```bash
git add fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge
git commit -m "refactor(sidekick): split bridge runtime files"
```

## Task 7: Split MCP Tool Dispatch From Registry And Cache

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/BridgeResultCache.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/ConsoleServerManager.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisResourceDispatcher.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisToolDispatcher.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt`

- [ ] **Step 1: Move tool and resource definitions**

Create `McpToolRegistry.kt` and move `ToolDefinition`, `ResourceDefinition`,
`ToolDefinitions`, `ResourceDefinitions`, `objectSchema`, and property schema
helpers unchanged.

Expose:

```kotlin
internal object McpToolRegistry {
    fun listTools(): JsonArray = buildJsonArray {
        ToolDefinitions.forEach { add(it.toJson()) }
    }

    fun listResources(): JsonArray = buildJsonArray {
        ResourceDefinitions.forEach { resource ->
            add(resource.toJson())
        }
    }
}
```

- [ ] **Step 2: Extract cache**

Create `BridgeResultCache.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.tools

import io.beyondwin.fixthis.mcp.McpProtocol
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

internal class BridgeResultCache(
    private val maxRecentOverridePackages: Int = 8,
    private val defaultPackageName: String?,
) {
    private val lock = Any()
    private val latestScreens = mutableMapOf<String, JsonObject>()
    private val latestStatuses = mutableMapOf<String, JsonObject>()
    private val cachedPackageOrder = linkedSetOf<String>()
    private var defaultCachePackage: String? = defaultPackageName?.takeIf { it.isNotBlank() }

    fun cacheScreen(packageName: String, screen: JsonObject) = synchronized(lock) {
        latestScreens[packageName] = screen
        rememberCachedPackage(packageName)
    }

    fun cacheSnapshot(packageName: String, screen: SnapshotDto) {
        cacheScreen(packageName, McpProtocol.json.encodeToJsonElement(SnapshotDto.serializer(), screen).jsonObject)
    }

    fun cacheStatus(packageName: String, status: JsonObject) = synchronized(lock) {
        latestStatuses[packageName] = status
        rememberCachedPackage(packageName)
    }

    fun latestScreen(packageName: String): JsonObject? = synchronized(lock) { latestScreens[packageName] }

    fun latestStatus(packageName: String): JsonObject? = synchronized(lock) { latestStatuses[packageName] }

    fun rememberDefaultPackage(packageName: String) = synchronized(lock) {
        defaultCachePackage = packageName
        rememberCachedPackage(packageName)
    }

    private fun rememberCachedPackage(packageName: String) {
        cachedPackageOrder.remove(packageName)
        cachedPackageOrder.add(packageName)
        while (cachedPackageOrder.count { it != defaultCachePackage } > maxRecentOverridePackages) {
            val evictedPackage = cachedPackageOrder.firstOrNull { it != defaultCachePackage } ?: return
            cachedPackageOrder.remove(evictedPackage)
            latestScreens.remove(evictedPackage)
            latestStatuses.remove(evictedPackage)
        }
    }
}
```

- [ ] **Step 3: Extract console lifecycle**

Create `ConsoleServerManager.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.tools

import io.beyondwin.fixthis.mcp.console.FeedbackConsoleServer
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.SessionDto
import java.io.File

internal class ConsoleServerManager(
    private val service: FeedbackSessionService,
    private val consoleAssetsDir: File?,
    private val consolePort: Int,
) {
    private val lock = Any()
    private var consoleServer: FeedbackConsoleServer? = null

    fun open(session: SessionDto): OpenFeedbackConsoleResult = synchronized(lock) {
        val server = consoleServer ?: FeedbackConsoleServer(
            service = service,
            consoleAssetsDir = consoleAssetsDir,
            port = consolePort,
        ).also { consoleServer = it }
        OpenFeedbackConsoleResult(session = session, consoleUrl = server.start(), resumed = false)
    }

    fun stop() = synchronized(lock) {
        consoleServer?.stop()
        consoleServer = null
    }
}
```

Move `OpenFeedbackConsoleResult` to this file and keep it `internal`.

- [ ] **Step 4: Extract dispatcher classes**

Move the `call` `when` body into `FixThisToolDispatcher`. Move `readResource`
into `FixThisResourceDispatcher`. Each dispatcher receives:

- `bridge`
- `feedbackService`
- `cache`
- package resolver function
- freshness probe where needed
- console manager where needed

Keep `FixThisTools.call` as:

```kotlin
suspend fun call(name: String, arguments: JsonObject): JsonObject = toolDispatcher.call(name, arguments)
```

Keep `FixThisTools.readResource` as:

```kotlin
suspend fun readResource(uri: String): JsonObject = resourceDispatcher.read(uri)
```

- [ ] **Step 5: Verify**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.McpProtocolTest"
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.tools.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools
git commit -m "refactor(mcp): split tool registry and dispatch"
```

## Task 8: Split Source Matching Policy Without Changing Output Strings

**Files:**
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceScoringPolicy.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceConfidencePolicy.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt`

- [ ] **Step 1: Add typed reason labels**

Create `SourceMatchReason.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

internal enum class SourceMatchReason(val wireLabel: String) {
    SELECTED_TEXT("selected text"),
    SELECTED_CONTENT_DESCRIPTION("selected contentDescription"),
    SELECTED_TEST_TAG("selected testTag"),
    SELECTED_TEST_TAG_CONVENTION_COMPOSABLE("selected testTag convention composable"),
    SELECTED_ROLE("selected role"),
    SELECTED_STRING_RESOURCE("selected stringResource"),
    NEARBY_TEXT("nearby text"),
    NEARBY_CONTENT_DESCRIPTION("nearby contentDescription"),
    NEARBY_TEST_TAG("nearby testTag"),
    NEARBY_ROLE("nearby role"),
    ACTIVITY("activity"),
    ARBITRARY_LITERAL("arbitrary literal"),
    LEGACY_FALLBACK("legacy fallback"),
}
```

- [ ] **Step 2: Extract scoring constants**

Create `SourceScoringPolicy.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

internal object SourceScoringPolicy {
    const val maxCandidates: Int = 5
    const val highConfidenceScore: Double = 100.0
    const val minPartialMatchLength: Int = 3

    fun bucketScore(reason: SourceMatchReason): Double = when (reason) {
        SourceMatchReason.SELECTED_TEXT -> 45.0
        SourceMatchReason.SELECTED_CONTENT_DESCRIPTION -> 40.0
        SourceMatchReason.SELECTED_TEST_TAG -> 55.0
        SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE -> 65.0
        SourceMatchReason.SELECTED_ROLE -> 25.0
        SourceMatchReason.NEARBY_TEXT -> 24.0
        SourceMatchReason.NEARBY_CONTENT_DESCRIPTION -> 22.0
        SourceMatchReason.NEARBY_TEST_TAG -> 18.0
        SourceMatchReason.NEARBY_ROLE -> 8.0
        SourceMatchReason.ACTIVITY -> 15.0
        SourceMatchReason.SELECTED_STRING_RESOURCE,
        SourceMatchReason.ARBITRARY_LITERAL,
        SourceMatchReason.LEGACY_FALLBACK,
        -> 0.0
    }
}
```

- [ ] **Step 3: Extract shared caution text**

Create `SourceConfidencePolicy.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk

internal object SourceConfidencePolicy {
    fun cautionFor(
        confidence: SelectionConfidence,
        flags: List<SourceCandidateRisk>,
    ): String? {
        val highest = SourceCandidateRiskPrecedence.highest(flags)
        if (highest != null) {
            return when (highest) {
                SourceCandidateRisk.AMBIGUOUS ->
                    "Verify this source candidate before editing; top candidates are close."
                SourceCandidateRisk.AREA_SELECTION ->
                    "Visual-area selection; use screenshot and bounds before editing."
                SourceCandidateRisk.TEXT_ONLY ->
                    "Text-only match; confirm against screenshot and code."
                SourceCandidateRisk.NEARBY_ONLY ->
                    "Nearby-only match; confirm against screenshot and code."
                SourceCandidateRisk.ARBITRARY_LITERAL ->
                    "Match relied on a generic string literal; confirm against screenshot and code."
                SourceCandidateRisk.ACTIVITY_ONLY ->
                    "Activity-only match; confirm against screenshot and code."
                SourceCandidateRisk.LEGACY_FALLBACK ->
                    "Legacy-fallback match; confirm against screenshot and code."
            }
        }
        return when (confidence) {
            SelectionConfidence.LOW -> "Top source candidate has low confidence; verify before editing."
            SelectionConfidence.NONE -> "No source candidate was available from current evidence."
            else -> null
        }
    }
}
```

- [ ] **Step 4: Convert `SourceMatcher` to reason enum internally**

Replace local string reason additions with `SourceMatchReason` and emit strings
only at the `SourceCandidate` boundary:

```kotlin
matchReasons.add(SourceMatchReason.SELECTED_TEXT)
...
matchReasons = matchReasons.map { it.wireLabel }
```

Use `SourceScoringPolicy.bucketScore(reason)` instead of numeric literals.

- [ ] **Step 5: Use shared caution policy**

In `SourceMatcher.cautionFor`, delegate to:

```kotlin
SourceConfidencePolicy.cautionFor(confidence, flags)
```

In `SourceInterpretationFactory.defaultCaution`, delegate to:

```kotlin
SourceConfidencePolicy.cautionFor(top.confidence, top.riskFlags)
```

- [ ] **Step 6: Verify output compatibility**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "io.beyondwin.fixthis.compose.core.source.SourceMatcherTest"
./gradlew :fixthis-compose-core:test --tests "io.beyondwin.fixthis.compose.core.source.SourceInterpretationFactoryTest"
```

Expected: PASS with no changed `matchReasons` strings.

- [ ] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source
git commit -m "refactor(core): split source matching policy"
```

## Task 9: Extract Gradle Source Scanners From The Task

**Files:**
- Create: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexAssets.kt`
- Create: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt`
- Create: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/XmlStringResourceScanner.kt`
- Create: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`
- Modify: `fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt`

- [ ] **Step 1: Move asset DTOs**

Create `SourceIndexAssets.kt` under `io.beyondwin.fixthis.gradle.source` and
move these declarations from the task file:

- `SourceIndexAsset`
- `SourceIndexEntryAsset`
- `SourceSignalAsset`
- `SourceSignalKindAsset`
- `FixThisBuildInfoAsset`
- `SourceIndexEntryBuilder`

Make `SourceIndexEntryAsset`, `SourceSignalAsset`, `SourceSignalKindAsset`,
`SourceIndexAsset`, and `FixThisBuildInfoAsset` `internal`.

- [ ] **Step 2: Extract Kotlin scanner**

Create `KotlinSourceScanner.kt` with this shape:

```kotlin
package io.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import java.io.File

internal class KotlinSourceScanner(
    private val projectDirectory: File,
    private val json: Json,
) {
    fun scan(file: File): List<SourceIndexEntryAsset> = scanKotlinFile(file)

    private fun scanKotlinFile(file: File): List<SourceIndexEntryAsset> {
        val source = file.readText()
        val lines = source.lineSequence().toList()
        val lineStartOffsets = source.lineStartOffsets()
        val packageName = packageRegex.find(source)?.groupValues?.get(1)
        val classDeclarations = classRegex.findAll(source)
            .map { match -> match.range.first to match.groupValues[2] }
            .toList()
        val recognizedStringRanges = recognizedUiStringRanges(source)
        val entriesByLine = linkedMapOf<Int, SourceIndexEntryBuilder>()
        var pendingComposable = false

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            if (line.contains("@Composable")) pendingComposable = true
            val functionMatch = functionRegex.find(line)
            if (functionMatch != null) {
                if (pendingComposable || line.contains("@Composable")) {
                    val symbol = functionMatch.groupValues[1]
                    entriesByLine.entryFor(file, lineNumber, line, packageName, classNameAt(lineStartOffsets.getOrElse(index) { 0 }, classDeclarations))
                        .apply {
                            symbols += symbol
                            addSignal(SourceSignalKindAsset.COMPOSABLE_SYMBOL, symbol)
                        }
                }
                pendingComposable = false
            }
        }

        collectRecognizedStringSignals(file, lines, recognizedStringRanges, entriesByLine)
        collectTextCallSignals(file, source, lineStartOffsets, entriesByLine)
        collectStringResourceSignals(file, source, lineStartOffsets, entriesByLine)
        collectModifierSignals(file, source, lineStartOffsets, entriesByLine)

        return entriesByLine.values.map { it.toAsset() }
    }
}
```

Move the existing helper functions from the task into this class with their
current regexes and return values intact. The helper names above are the target
shape for the four existing scan loops:

- `collectRecognizedStringSignals` contains the loop that skips recognized UI
  string ranges while still indexing arbitrary literals.
- `collectTextCallSignals` contains the current `Text(...)` argument extraction.
- `collectStringResourceSignals` contains the current `stringResource(...)`
  extraction.
- `collectModifierSignals` contains the current `testTag(...)` and
  `contentDescription` extraction.

Replace every former `file.relativePath()` call with:

```kotlin
file.relativeToOrSelf(projectDirectory).invariantSeparatorsPath
```

Do not change regexes, emitted JSON field names, signal enum names, or line
number semantics in this slice.

- [ ] **Step 3: Extract XML scanner**

Create `XmlStringResourceScanner.kt` and move `scanXmlStringResources` plus
`newDocumentBuilderFactory`. Constructor:

```kotlin
internal class XmlStringResourceScanner(
    private val projectDirectory: File,
)
```

- [ ] **Step 4: Extract generator coordinator**

Create `SourceIndexGenerator.kt`:

```kotlin
package io.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import java.io.File

internal class SourceIndexGenerator(
    private val projectDirectory: File,
    private val json: Json,
) {
    private val kotlinScanner = KotlinSourceScanner(projectDirectory, json)
    private val xmlScanner = XmlStringResourceScanner(projectDirectory)

    fun generate(kotlinFiles: List<File>, xmlFiles: List<File>): SourceIndexAsset = SourceIndexAsset(
        entries = buildList {
            kotlinFiles.sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                .forEach { addAll(kotlinScanner.scan(it)) }
            xmlFiles.sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                .forEach { addAll(xmlScanner.scan(it)) }
        },
    )
}
```

- [ ] **Step 5: Shrink Gradle task**

In `GenerateFixThisSourceIndexTask.generate`, replace inline scanning with:

```kotlin
val generator = SourceIndexGenerator(projectDirectory.get().asFile, json)
val entries = generator.generate(
    kotlinFiles = kotlinSourceFiles.files.flatMap { it.kotlinFiles() },
    xmlFiles = resourceXmlFiles.files.flatMap { it.xmlFiles() },
)
assetRoot.resolve("fixthis-source-index.json").writeText(json.encodeToString(entries))
```

Keep Gradle properties and output paths unchanged.

- [ ] **Step 6: Verify**

Run:

```bash
./gradlew :fixthis-gradle-plugin:test --tests "io.beyondwin.fixthis.gradle.GenerateFixThisSourceIndexTaskTest"
./gradlew :fixthis-gradle-plugin:functionalTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add \
  fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt \
  fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/source \
  fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt
git commit -m "refactor(gradle): extract source index scanners"
```

## Task 10: Split `FeedbackConsoleServerTest` Fixtures And Route Tests

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/ConsoleServerFixtures.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetRoutesTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionRoutesTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsolePreviewRoutesTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Extract shared fixtures**

Move these nested helpers from `FeedbackConsoleServerTest.kt` into
`ConsoleServerFixtures.kt`:

- `FakeIds`
- `FakeLongs`
- `ConsoleHttpTestClient`
- `ConsoleHttpResponse`
- `DeviceListBridge`
- `SessionScreenshotBridge`
- `SequencedSessionScreenshotBridge`
- `SequencedFingerprintBridge`
- `NullableSequencedFingerprintBridge`
- `SecondCaptureIllegalArgumentBridge`
- `BlockingCaptureBridge`
- `LegacyScreenshotBridge`

Use package:

```kotlin
package io.beyondwin.fixthis.mcp.fixtures
```

Keep behavior unchanged and make classes `internal`.

- [ ] **Step 2: Move asset route tests**

Move these tests into `ConsoleAssetRoutesTest.kt`:

- `configuredConsoleAssetsReceiveConsoleToken`
- `consoleRequestJsonSendsTokenForMutatingRequestsAndPreservesHeaders`
- `servesFaviconWithoutBrowserVisible404`
- `consoleAssetsAreLoadedFromClasspathResources`
- `generatedConsoleAppMatchesConsoleSourceModules`
- `consoleBundleEmbedsBuildEpochAndGitSha`
- `stalenessModuleExposesCheckAndRender`
- `stalenessCheckHandlesMissingEndpoint`
- `servesConsoleAssetsFromConfiguredDirectoryWithoutCaching`
- `consoleAssetsRejectTraversalPaths`
- `consoleAssetsRejectAbsolutePaths`

- [ ] **Step 3: Move connection/device tests**

Move connection and device tests into `ConsoleConnectionRoutesTest.kt`, including
the tests whose names contain `connection`, `heartbeat`, `device`, `launchApp`,
`availability`, `blocked`, or `wifi`.

- [ ] **Step 4: Move preview/save tests**

Move preview route tests into `ConsolePreviewRoutesTest.kt` and batch item save
tests into `ConsoleFeedbackItemRoutesTest.kt`.

- [ ] **Step 5: Leave only top-level smoke tests in original file**

After moves, keep `FeedbackConsoleServerTest.kt` limited to route table smoke,
server index smoke, CSRF smoke, and unsupported method behavior.

- [ ] **Step 6: Verify**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/ConsoleServerFixtures.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console
git commit -m "test(mcp): split console server route tests"
```

## Task 11: Final Documentation And Full Verification

**Files:**
- Modify: `docs/architecture/overview.md`
- Modify: `CONTRIBUTING.md`
- Modify: `config/detekt/baseline-fixthis-mcp.xml`
- Modify: other detekt baselines only if refactors remove findings

- [ ] **Step 1: Update architecture overview**

In `docs/architecture/overview.md`, update the `:fixthis-mcp`,
`:fixthis-compose-sidekick`, `:fixthis-compose-core`, and
`:fixthis-gradle-plugin` sections to mention the new split classes.

- [ ] **Step 2: Regenerate detekt baselines only after findings disappear**

Run:

```bash
./gradlew detekt --no-daemon
```

If detekt passes without baseline changes, do not touch baseline XML. If
refactors remove baseline entries, regenerate only the affected module baseline
using the project's existing detekt baseline task and review the XML diff before
committing.

- [ ] **Step 3: Full local verification**

Run:

```bash
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

Expected: all commands PASS.

- [ ] **Step 4: Commit**

```bash
git add docs/architecture/overview.md CONTRIBUTING.md config/detekt
git commit -m "docs: update architecture after solid remediation"
```

## Self-Review Checklist

- [ ] The plan keeps `compose-core` pure.
- [ ] Public MCP tool names are unchanged.
- [ ] Persisted JSON fields `items`, `screens`, `itemId`, `screenId`,
      `targetEvidence`, and `sourceCandidates` are unchanged.
- [ ] Bridge protocol version is unchanged unless a task explicitly changes
      wire-visible behavior.
- [ ] Each task has a focused verification command.
- [ ] Refactor tasks are sequenced so behavior-preserving moves land before
      behavior-routing changes.
