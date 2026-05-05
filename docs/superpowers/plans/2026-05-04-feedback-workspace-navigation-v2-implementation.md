# Feedback Workspace And Limited Navigation V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build resumable PointPatch feedback workspaces and limited single-step app navigation through MCP and the local console.

**Architecture:** The MCP process keeps owning feedback session state, but `FeedbackSessionStore` gains project-local persistence under `.pointpatch/feedback-sessions/`. The sidekick bridge gains one constrained navigation method that can dispatch `back`, `tap`, or `swipe` inside the debug app process, and MCP/console callers may capture the resulting screen into the active session.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, Java HTTP server, Android LocalSocket bridge, ADB bridge client, Gradle tests.

---

## Related Documents

- Design: `docs/superpowers/specs/2026-05-04-feedback-workspace-navigation-v2-design.md`
- Implementation details: `docs/superpowers/specs/2026-05-04-feedback-workspace-navigation-v2-implementation-details.md`

## Scope

This plan intentionally implements both V2A and V2B in one sequence:

1. persistent feedback workspaces
2. session listing and resume
3. session-owned screenshot artifacts
4. limited navigation actions
5. navigation plus capture workflow

The plan excludes arbitrary automation, text input, scripted exploration,
networked Android services, cloud sync, and broad console redesign.

## Required Baseline

Before Task 1, workers must verify the repository is on a clean worktree or an
isolated integration worktree:

```bash
git status --short --branch
git branch --show-current
```

Repo-local instruction files must be checked before edits:

```bash
rg --files -g 'AGENTS.md' -g 'CLAUDE.md' -g '*agent*guide*' -g '*instructions*'
```

If such files exist, read them and follow them before this plan.

## File Map

Create:

- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPaths.kt`: canonical storage paths and safe id/path helpers.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPersistence.kt`: disk read/write/list logic for sessions and index data.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionSummary.kt`: serializable summary/index models.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackNavigationModels.kt`: MCP/session-level navigation models.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPersistenceTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackNavigationModelsTest.kt`
- `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/NavigationPerformer.kt`
- `pointpatch-compose-sidekick/src/test/kotlin/io/github/pointpatch/compose/sidekick/bridge/NavigationPerformerTest.kt`

Modify:

- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionModels.kt`
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt`
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt`
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt`
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`
- `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServer.kt`
- `pointpatch-compose-sidekick/src/test/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServerTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt`
- `pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt`
- `README.md`
- `docs/mcp.md`
- `docs/output-schema.md`
- `docs/privacy.md`
- `docs/troubleshooting.md`

## Task 1: Baseline Verification

**Files:**

- No source edits.

- [x] **Step 1: Check branch and instructions**

Run:

```bash
git status --short --branch
git branch --show-current
rg --files -g 'AGENTS.md' -g 'CLAUDE.md' -g '*agent*guide*' -g '*instructions*'
```

Expected: branch and worktree state are understood. If instruction files are
listed, read them before Task 2.

- [x] **Step 2: Run baseline tests**

Run:

```bash
./gradlew :pointpatch-compose-core:test :pointpatch-cli:test :pointpatch-mcp:test
```

Expected: PASS.

- [x] **Step 3: Commit checkpoint only if local process requires it**

If no files changed, do not commit. Record baseline output in the task
checkpoint.

## Task 2: Session Path And Summary Models

**Files:**

- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPaths.kt`
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionSummary.kt`
- Test: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPersistenceTest.kt`

- [x] **Step 1: Write failing path and summary tests**

Create `FeedbackSessionPersistenceTest.kt` with these tests:

```kotlin
package io.github.pointpatch.mcp.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeedbackSessionPersistenceTest {
    @Test
    fun pathsStayUnderProjectFeedbackSessionsDirectory() {
        val root = createTempDir(prefix = "pointpatch-v2-paths-")
        val paths = FeedbackSessionPaths(root)

        val sessionDir = paths.sessionDirectory("session-1")
        val screenDir = paths.screenArtifactDirectory("session-1", "screen-1")

        assertEquals(File(root, ".pointpatch/feedback-sessions/session-1").canonicalFile, sessionDir)
        assertEquals(File(root, ".pointpatch/feedback-sessions/session-1/artifacts/screens/screen-1").canonicalFile, screenDir)
        assertTrue(screenDir.toPath().startsWith(paths.rootDirectory.toPath()))
    }

    @Test
    fun pathHelpersRejectUnsafeIds() {
        val paths = FeedbackSessionPaths(createTempDir(prefix = "pointpatch-v2-unsafe-"))

        assertFailsWith<IllegalArgumentException> {
            paths.sessionDirectory("../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            paths.screenArtifactDirectory("session-1", "screen/1")
        }
    }

    @Test
    fun sessionSummaryCountsUnresolvedItems() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            screens = listOf(CapturedScreen(screenId = "screen-1", capturedAtEpochMillis = 2L, displayName = "Main")),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = FeedbackTarget.Area(PointPatchRectForTest.bounds),
                    comment = "Fix spacing",
                    status = FeedbackItemStatus.READY,
                ),
                FeedbackItem(
                    itemId = "item-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 2L,
                    updatedAtEpochMillis = 2L,
                    target = FeedbackTarget.Area(PointPatchRectForTest.bounds),
                    comment = "Done",
                    status = FeedbackItemStatus.RESOLVED,
                ),
            ),
        )

        val summary = FeedbackSessionSummary.from(session)

        assertEquals("session-1", summary.sessionId)
        assertEquals(1, summary.screensCount)
        assertEquals(2, summary.itemsCount)
        assertEquals(1, summary.unresolvedItemsCount)
    }
}
```

Add this helper at the bottom of the file:

```kotlin
private object PointPatchRectForTest {
    val bounds = io.github.pointpatch.compose.core.model.PointPatchRect(1f, 2f, 3f, 4f)
}
```

- [x] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionPersistenceTest
```

Expected: compilation fails because `FeedbackSessionPaths` and
`FeedbackSessionSummary` do not exist.

- [x] **Step 3: Add path helper**

Create `FeedbackSessionPaths.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import java.io.File

class FeedbackSessionPaths(projectRoot: File) {
    val projectRoot: File = projectRoot.canonicalFile
    val rootDirectory: File = File(this.projectRoot, ".pointpatch/feedback-sessions").canonicalFile
    val indexFile: File = File(rootDirectory, "index.json").canonicalFile

    fun sessionDirectory(sessionId: String): File =
        child(rootDirectory, safeId(sessionId))

    fun sessionFile(sessionId: String): File =
        child(sessionDirectory(sessionId), "session.json")

    fun screenArtifactDirectory(sessionId: String, screenId: String): File =
        child(child(child(sessionDirectory(sessionId), "artifacts"), "screens"), safeId(screenId))

    fun fullScreenshotFile(sessionId: String, screenId: String): File =
        child(screenArtifactDirectory(sessionId, screenId), "${safeId(screenId)}-full.png")

    fun isUnderFeedbackStorage(file: File): Boolean =
        file.canonicalFile.toPath().startsWith(rootDirectory.toPath())

    private fun child(parent: File, segment: String): File {
        val child = File(parent, segment).canonicalFile
        require(child.toPath().startsWith(parent.canonicalFile.toPath())) {
            "Path escapes PointPatch feedback storage: $segment"
        }
        return child
    }

    private fun safeId(value: String): String {
        require(value.isNotBlank()) { "PointPatch id must not be blank" }
        require(value.matches(Regex("[A-Za-z0-9._-]+"))) { "Unsafe PointPatch id: $value" }
        require(value != "." && value != "..") { "Unsafe PointPatch id: $value" }
        return value
    }
}
```

- [x] **Step 4: Add summary models**

Create `FeedbackSessionSummary.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import kotlinx.serialization.Serializable

@Serializable
data class FeedbackSessionIndex(
    val schemaVersion: String = "2.0",
    val updatedAtEpochMillis: Long,
    val sessions: List<FeedbackSessionSummary> = emptyList(),
)

@Serializable
data class FeedbackSessionSummary(
    val sessionId: String,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val status: FeedbackSessionStatus,
    val screensCount: Int,
    val itemsCount: Int,
    val unresolvedItemsCount: Int,
) {
    companion object {
        fun from(session: FeedbackSession): FeedbackSessionSummary =
            FeedbackSessionSummary(
                sessionId = session.sessionId,
                packageName = session.packageName,
                projectRoot = session.projectRoot,
                createdAtEpochMillis = session.createdAtEpochMillis,
                updatedAtEpochMillis = session.updatedAtEpochMillis,
                status = session.status,
                screensCount = session.screens.size,
                itemsCount = session.items.size,
                unresolvedItemsCount = session.items.count { item ->
                    item.status !in setOf(FeedbackItemStatus.RESOLVED, FeedbackItemStatus.WONT_FIX)
                },
            )
    }
}
```

- [x] **Step 5: Run tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionPersistenceTest
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPaths.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionSummary.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPersistenceTest.kt
git commit -m "mcp: add feedback workspace path models"
```

## Task 3: Disk Persistence

**Files:**

- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPersistence.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPersistenceTest.kt`

- [x] **Step 1: Add failing persistence tests**

Append tests:

```kotlin
@Test
fun persistenceSavesSessionAndIndex() {
    val root = createTempDir(prefix = "pointpatch-v2-persist-")
    val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 200L })
    val session = FeedbackSession(
        sessionId = "session-1",
        packageName = "io.github.pointpatch.sample",
        projectRoot = root.absolutePath,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
    )

    persistence.save(session)

    assertTrue(File(root, ".pointpatch/feedback-sessions/session-1/session.json").isFile)
    assertTrue(File(root, ".pointpatch/feedback-sessions/index.json").isFile)
    assertEquals(session, persistence.load("session-1"))
    assertEquals(listOf("session-1"), persistence.list().sessions.map { it.sessionId })
}

@Test
fun persistenceSkipsCorruptSessionFilesDuringList() {
    val root = createTempDir(prefix = "pointpatch-v2-corrupt-")
    val paths = FeedbackSessionPaths(root)
    val persistence = FeedbackSessionPersistence(paths, clock = { 200L })
    paths.sessionDirectory("session-bad").mkdirs()
    paths.sessionFile("session-bad").writeText("{not json")

    val listed = persistence.list()

    assertEquals(emptyList(), listed.sessions)
    assertEquals(listOf(paths.sessionFile("session-bad").absolutePath), listed.skippedSessions.map { it.path })
}
```

- [x] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionPersistenceTest
```

Expected: compilation fails because `FeedbackSessionPersistence` does not exist.

- [x] **Step 3: Implement persistence**

Create `FeedbackSessionPersistence.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson
import java.io.File
import kotlinx.serialization.Serializable

class FeedbackSessionPersistence(
    private val paths: FeedbackSessionPaths,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun save(session: FeedbackSession) {
        val sessionDirectory = paths.sessionDirectory(session.sessionId)
        check(sessionDirectory.exists() || sessionDirectory.mkdirs()) {
            "Could not create feedback session directory: ${sessionDirectory.absolutePath}"
        }
        val sessionFile = paths.sessionFile(session.sessionId)
        sessionFile.writeText(pointPatchJson.encodeToString(FeedbackSession.serializer(), session))
        writeIndex()
    }

    fun load(sessionId: String): FeedbackSession {
        val sessionFile = paths.sessionFile(sessionId)
        require(sessionFile.isFile) { "Feedback session does not exist: $sessionId" }
        return pointPatchJson.decodeFromString(FeedbackSession.serializer(), sessionFile.readText())
    }

    fun list(packageName: String? = null, includeClosed: Boolean = false): FeedbackSessionList {
        val loaded = loadAll()
        val sessions = loaded.sessions
            .filter { packageName == null || it.packageName == packageName }
            .filter { includeClosed || it.status != FeedbackSessionStatus.CLOSED }
            .map(FeedbackSessionSummary.Companion::from)
            .sortedByDescending { it.updatedAtEpochMillis }
        return FeedbackSessionList(sessions = sessions, skippedSessions = loaded.skipped)
    }

    fun artifactPaths(): FeedbackSessionPaths = paths

    private fun writeIndex() {
        val listed = list(includeClosed = true)
        check(paths.rootDirectory.exists() || paths.rootDirectory.mkdirs()) {
            "Could not create feedback session root: ${paths.rootDirectory.absolutePath}"
        }
        paths.indexFile.writeText(
            pointPatchJson.encodeToString(
                FeedbackSessionIndex.serializer(),
                FeedbackSessionIndex(updatedAtEpochMillis = clock(), sessions = listed.sessions),
            ),
        )
    }

    private fun loadAll(): LoadedSessions {
        if (!paths.rootDirectory.isDirectory) return LoadedSessions(emptyList(), emptyList())
        val sessions = mutableListOf<FeedbackSession>()
        val skipped = mutableListOf<SkippedFeedbackSession>()
        paths.rootDirectory.listFiles().orEmpty()
            .filter(File::isDirectory)
            .forEach { directory ->
                val file = File(directory, "session.json")
                if (file.isFile) {
                    runCatching {
                        pointPatchJson.decodeFromString(FeedbackSession.serializer(), file.readText())
                    }.onSuccess { session ->
                        sessions += session
                    }.onFailure { error ->
                        skipped += SkippedFeedbackSession(path = file.absolutePath, message = error.message ?: error::class.java.simpleName)
                    }
                }
            }
        return LoadedSessions(sessions, skipped)
    }

    private data class LoadedSessions(
        val sessions: List<FeedbackSession>,
        val skipped: List<SkippedFeedbackSession>,
    )
}

@Serializable
data class FeedbackSessionList(
    val sessions: List<FeedbackSessionSummary> = emptyList(),
    val skippedSessions: List<SkippedFeedbackSession> = emptyList(),
)

@Serializable
data class SkippedFeedbackSession(
    val path: String,
    val message: String,
)
```

- [x] **Step 4: Run persistence tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionPersistenceTest
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPersistence.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionPersistenceTest.kt
git commit -m "mcp: persist feedback workspaces"
```

## Task 4: Persistent Store Resume Semantics

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`

- [x] **Step 1: Add failing store resume tests**

Append to `FeedbackSessionStoreTest`:

```kotlin
@Test
fun storePersistsMutationsAndCanResumeLatestSession() {
    val root = createTempDir(prefix = "pointpatch-v2-store-")
    val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
    val ids = ArrayDeque(listOf("session-1", "screen-1", "item-1"))
    val store = FeedbackSessionStore(
        clock = { 100L },
        idGenerator = { ids.removeFirst() },
        persistence = persistence,
    )

    val session = store.openSession("io.github.pointpatch.sample", root.absolutePath)
    val screen = store.addScreen(session.sessionId, CapturedScreen(screenId = "pending", capturedAtEpochMillis = 0L, displayName = "Main"))
    store.addItem(
        session.sessionId,
        FeedbackItem(
            itemId = "pending",
            screenId = screen.screenId,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
            comment = "Fix it",
        ),
    )

    val resumed = FeedbackSessionStore(clock = { 200L }, persistence = persistence)

    assertEquals("session-1", resumed.currentSession()?.sessionId)
    assertEquals(1, resumed.currentSession()?.screens?.size)
    assertEquals(1, resumed.currentSession()?.items?.size)
}

@Test
fun storeCanOpenExactPersistedSession() {
    val root = createTempDir(prefix = "pointpatch-v2-exact-")
    val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
    val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence)
    val created = store.openSession("io.github.pointpatch.sample", root.absolutePath)

    val fresh = FeedbackSessionStore(clock = { 200L }, persistence = persistence)
    val opened = fresh.openExistingSession(created.sessionId)

    assertEquals(created.sessionId, opened.sessionId)
    assertEquals(created.sessionId, fresh.currentSession()?.sessionId)
}
```

- [x] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest
```

Expected: compilation fails because `FeedbackSessionStore` has no
`persistence` constructor parameter or `openExistingSession`.

- [x] **Step 3: Update store constructor and load persisted sessions**

Modify `FeedbackSessionStore` constructor:

```kotlin
class FeedbackSessionStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val persistence: FeedbackSessionPersistence? = null,
) {
    private val lock = Any()
    private val sessions = linkedMapOf<String, FeedbackSession>()
    private var currentSessionId: String? = null

    init {
        persistence?.list(includeClosed = true)?.sessions
            ?.sortedBy { it.updatedAtEpochMillis }
            ?.forEach { summary ->
                runCatching { persistence.load(summary.sessionId) }
                    .getOrNull()
                    ?.let { session ->
                        sessions[session.sessionId] = session
                        if (session.status != FeedbackSessionStatus.CLOSED) currentSessionId = session.sessionId
                    }
            }
    }
```

- [x] **Step 4: Add exact open, list, close, and save hooks**

Add methods:

```kotlin
fun listSessions(packageName: String? = null, includeClosed: Boolean = false): FeedbackSessionList =
    synchronized(lock) {
        persistence?.list(packageName, includeClosed)
            ?: FeedbackSessionList(
                sessions = sessions.values
                    .filter { packageName == null || it.packageName == packageName }
                    .filter { includeClosed || it.status != FeedbackSessionStatus.CLOSED }
                    .map(FeedbackSessionSummary.Companion::from)
                    .sortedByDescending { it.updatedAtEpochMillis },
            )
    }

fun openExistingSession(sessionId: String): FeedbackSession =
    synchronized(lock) {
        val session = sessions[sessionId] ?: persistence?.load(sessionId)?.also { sessions[it.sessionId] = it }
            ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")
        currentSessionId = session.sessionId
        session
    }

fun closeSession(sessionId: String): FeedbackSession =
    synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        val closed = session.copy(status = FeedbackSessionStatus.CLOSED, updatedAtEpochMillis = now)
        sessions[sessionId] = closed
        if (currentSessionId == sessionId) currentSessionId = null
        save(closed)
        closed
    }

private fun save(session: FeedbackSession) {
    persistence?.save(session)
}
```

Call `save(updated)` after every mutation in `openSession`, `addScreen`,
`addItem`, `markReadyForAgent`, and `updateItemStatus`.

- [x] **Step 5: Run store tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "mcp: resume persisted feedback sessions"
```

## Task 5: Service Session Listing And Exact Open

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt`

- [x] **Step 1: Add failing service tests**

Append to `FeedbackSessionServiceTest`:

```kotlin
@Test
fun serviceOpensExactPersistedSession() {
    val root = createTempDir(prefix = "pointpatch-v2-service-")
    val store = FeedbackSessionStore(
        clock = { 100L },
        idGenerator = { "session-1" },
        persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L }),
    )
    val service = FeedbackSessionService(
        bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample"),
        store = store,
        projectRoot = root.absolutePath,
        defaultPackageName = "io.github.pointpatch.sample",
    )
    val created = service.openSession(packageNameOverride = null, newSession = true)

    val reopened = service.openSession(packageNameOverride = null, sessionId = created.sessionId)

    assertEquals(created.sessionId, reopened.sessionId)
}

@Test
fun serviceListsSessionsForPackage() {
    val root = createTempDir(prefix = "pointpatch-v2-list-")
    val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" })
    val service = FeedbackSessionService(
        bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample"),
        store = store,
        projectRoot = root.absolutePath,
        defaultPackageName = "io.github.pointpatch.sample",
    )
    service.openSession(packageNameOverride = null, newSession = true)

    val sessions = service.listSessions(packageNameOverride = "io.github.pointpatch.sample")

    assertEquals(listOf("session-1"), sessions.sessions.map { it.sessionId })
}
```

- [x] **Step 2: Run service tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
```

Expected: compilation fails because service methods do not accept `sessionId`
or `newSession`, and `listSessions` does not exist.

- [x] **Step 3: Update service open/list/close methods**

Change `openSession` signature and body:

```kotlin
fun openSession(
    packageNameOverride: String?,
    sessionId: String? = null,
    newSession: Boolean = false,
): FeedbackSession =
    synchronized(sessionLock) {
        sessionId?.takeIf { it.isNotBlank() }?.let { return@synchronized store.openExistingSession(it) }
        val packageName = bridge.resolvePackageName(
            packageNameOverride?.takeIf { it.isNotBlank() } ?: defaultPackageName,
        )
        if (!newSession) {
            store.currentSession()
                ?.takeIf { it.packageName == packageName && it.projectRoot == projectRoot && it.status != FeedbackSessionStatus.CLOSED }
                ?.let { return@synchronized it }
            store.listSessions(packageName = packageName)
                .sessions
                .firstOrNull { it.projectRoot == projectRoot }
                ?.let { return@synchronized store.openExistingSession(it.sessionId) }
        }
        store.openSession(packageName = packageName, projectRoot = projectRoot)
    }
```

Add:

```kotlin
fun listSessions(packageNameOverride: String? = null, includeClosed: Boolean = false): FeedbackSessionList {
    val packageName = packageNameOverride?.takeIf { it.isNotBlank() }
        ?.let { bridge.resolvePackageName(it) }
    return store.listSessions(packageName = packageName, includeClosed = includeClosed)
}

fun closeSession(sessionId: String): FeedbackSession = store.closeSession(sessionId)
```

- [x] **Step 4: Run service tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt
git commit -m "mcp: list and reopen feedback workspaces"
```

## Task 6: Session-Owned Screen Artifacts

**Files:**

- Modify: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`
- Modify: `pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt`

- [x] **Step 1: Add failing artifact ownership tests**

In `FeedbackSessionServiceTest`, add:

```kotlin
@Test
fun captureUsesSessionOwnedArtifactPath() = runBlocking {
    val root = createTempDir(prefix = "pointpatch-v2-artifacts-")
    val bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample")
    val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" })
    val service = FeedbackSessionService(
        bridge = bridge,
        store = store,
        projectRoot = root.absolutePath,
        defaultPackageName = "io.github.pointpatch.sample",
    )
    val session = service.openSession(null, newSession = true)

    service.captureScreen(session.sessionId)

    assertEquals("session-1", bridge.lastCaptureSessionId)
    assertEquals("screen-1", bridge.lastCaptureScreenId)
    assertTrue(bridge.lastCaptureDestination!!.contains(".pointpatch/feedback-sessions/session-1/artifacts/screens/screen-1"))
}
```

Update `FakePointPatchBridge` to record those fields after the interface is
extended.

- [x] **Step 2: Run service test to verify it fails**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest.captureUsesSessionOwnedArtifactPath
```

Expected: compilation fails because capture methods do not accept session-owned
artifact arguments.

- [x] **Step 3: Extend bridge interfaces**

In `PointPatchBridge`, change:

```kotlin
suspend fun captureScreenSnapshot(
    packageName: String,
    sessionId: String? = null,
    screenId: String? = null,
    destinationDirectory: File? = null,
): JsonObject
```

In `CliPointPatchBridge`, forward to `BridgeClient.captureScreenSnapshot`.

- [x] **Step 4: Update BridgeClient capture destination**

Change `BridgeClient.captureScreenSnapshot` signature:

```kotlin
suspend fun captureScreenSnapshot(
    packageName: String,
    sessionId: String? = null,
    screenId: String? = null,
    destinationDirectory: File? = null,
): JsonObject
```

Use:

```kotlin
val artifactId = (screenId ?: "screen-${System.currentTimeMillis()}").sanitizedPathSegment()
val artifactDirectory = destinationDirectory ?: projectRoot.resolve(".pointpatch/artifacts/$artifactId")
```

Write the full screenshot to:

```kotlin
destination = artifactDirectory.resolve("$artifactId-full.png")
```

- [x] **Step 5: Reserve screen IDs before bridge capture**

Add to `FeedbackSessionStore`:

```kotlin
fun nextId(): String = synchronized(lock) { idGenerator() }
```

Change `addScreen` to preserve an already assigned `screenId` when it is not
`"pending"`:

```kotlin
val captured = screen.copy(
    screenId = if (screen.screenId == "pending") idGenerator() else screen.screenId,
    capturedAtEpochMillis = now,
)
```

In `FeedbackSessionService.captureScreen`, before calling the bridge:

```kotlin
val screenId = store.nextId()
val artifactDirectory = FeedbackSessionPaths(File(session.projectRoot)).screenArtifactDirectory(session.sessionId, screenId)
val payload = bridge.captureScreenSnapshot(
    packageName = session.packageName,
    sessionId = session.sessionId,
    screenId = screenId,
    destinationDirectory = artifactDirectory,
)
```

Create `CapturedScreen(screenId = screenId, ...)`.

- [x] **Step 6: Run artifact tests**

Run:

```bash
./gradlew :pointpatch-cli:test :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest.captureUsesSessionOwnedArtifactPath
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt
git commit -m "mcp: store screen artifacts in feedback workspaces"
```

## Task 7: MCP Session Tools

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt`

- [x] **Step 1: Add failing MCP protocol tests**

Add tests:

```kotlin
@Test
fun listFeedbackSessionsReturnsPersistedSummaries() = runBlocking {
    val tools = PointPatchTools(
        bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample"),
        defaultPackageName = "io.github.pointpatch.sample",
        projectRoot = createTempDir(prefix = "pointpatch-v2-mcp-sessions-"),
    )
    tools.call("pointpatch_open_feedback_console", jsonObject("newSession" to true))

    val result = tools.call("pointpatch_list_feedback_sessions", JsonObject(emptyMap()))
    val payload = result.firstJsonContent()

    assertEquals(1, payload["sessions"]!!.jsonArray.size)
}

@Test
fun openFeedbackConsoleCanOpenExactSession() = runBlocking {
    val tools = PointPatchTools(
        bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample"),
        defaultPackageName = "io.github.pointpatch.sample",
        projectRoot = createTempDir(prefix = "pointpatch-v2-mcp-open-"),
    )
    val opened = tools.call("pointpatch_open_feedback_console", jsonObject("newSession" to true)).firstJsonContent()
    val sessionId = opened["sessionId"]!!.jsonPrimitive.content

    val reopened = tools.call("pointpatch_open_feedback_console", jsonObject("sessionId" to sessionId)).firstJsonContent()

    assertEquals(sessionId, reopened["sessionId"]!!.jsonPrimitive.content)
    assertEquals(true, reopened["resumed"]!!.jsonPrimitive.boolean)
}
```

Use the existing test helpers for JSON content. If the helper is not present,
add one local helper that decodes the first `content[].text` as `JsonObject`.

- [x] **Step 2: Run MCP tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.McpProtocolTest
```

Expected: unknown tool or missing arguments behavior fails.

- [x] **Step 3: Wire persistent store by default**

In `PointPatchTools` constructor default, create the service with persistence:

```kotlin
private val feedbackService: FeedbackSessionService = FeedbackSessionService(
    bridge = bridge,
    store = FeedbackSessionStore(
        persistence = FeedbackSessionPersistence(FeedbackSessionPaths(projectRoot)),
    ),
    projectRoot = projectRoot.absolutePath,
    defaultPackageName = defaultPackageName,
)
```

- [x] **Step 4: Extend open console arguments**

Change `openConsole` to accept `sessionId` and `newSession`:

```kotlin
private fun openConsole(packageName: String?, sessionId: String?, newSession: Boolean): Pair<FeedbackSession, String> {
    synchronized(consoleLock) {
        val before = feedbackService.currentSessionOrNull()
        val session = feedbackService.openSession(packageNameOverride = packageName, sessionId = sessionId, newSession = newSession)
        val server = consoleServer ?: FeedbackConsoleServer(feedbackService).also { consoleServer = it }
        return session to server.start()
    }
}
```

If `currentSessionOrNull` does not exist, add it to `FeedbackSessionService` as:

```kotlin
fun currentSessionOrNull(): FeedbackSession? = store.currentSession()
```

Return `resumed = sessionId != null || !newSession`.

- [x] **Step 5: Add tool definition and call branch**

Add `pointpatch_list_feedback_sessions` to tool definitions. Add call branch:

```kotlin
"pointpatch_list_feedback_sessions" -> bridgeToolResult {
    val sessions = feedbackService.listSessions(
        packageNameOverride = arguments.stringParam("packageName"),
        includeClosed = arguments.booleanParam("includeClosed") == true,
    )
    jsonToolResult(buildJsonObject {
        put("projectRoot", projectRoot.absolutePath)
        put("sessions", McpProtocol.json.encodeToJsonElement(FeedbackSessionList.serializer(), sessions).jsonObject["sessions"]!!)
        put("skippedSessions", McpProtocol.json.encodeToJsonElement(FeedbackSessionList.serializer(), sessions).jsonObject["skippedSessions"]!!)
    })
}
```

- [x] **Step 6: Run MCP tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.McpProtocolTest
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt
git commit -m "mcp: expose feedback workspace sessions"
```

## Task 8: Console Session APIs

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add failing console API tests**

Add tests:

```kotlin
@Test
fun sessionsApiListsWorkspaces() {
    val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
    service.openSession(null, newSession = true)
    val server = FeedbackConsoleServer(service = service, port = 0)
    try {
        server.start()
        val sessions = URL("${server.url}/api/sessions").readText()
        assertTrue(sessions.contains("session"))
    } finally {
        server.stop()
    }
}

@Test
fun openSessionApiSwitchesCurrentSession() {
    val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(idGenerator = SequenceIds("session-1", "session-2").next), "/repo", "io.github.pointpatch.sample")
    val first = service.openSession(null, newSession = true)
    service.openSession(null, newSession = true)
    val server = FeedbackConsoleServer(service = service, port = 0)
    try {
        server.start()
        val connection = URL("${server.url}/api/session/open").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write("""{"sessionId":"${first.sessionId}"}""".toByteArray()) }

        assertEquals(200, connection.responseCode)
        assertTrue(connection.inputStream.bufferedReader().readText().contains(first.sessionId))
    } finally {
        server.stop()
    }
}
```

- [x] **Step 2: Run console tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: new routes return 404.

- [x] **Step 3: Add request models and routes**

In `FeedbackConsoleServer`, add routes:

```kotlin
"/api/sessions" -> exchange.requireMethod("GET") {
    exchange.sendJson(200, service.listSessions(includeClosed = queryBoolean(exchange, "includeClosed")))
}
"/api/session/open" -> exchange.requireMethod("POST") {
    val request = exchange.decodeOpenSessionBody()
    exchange.sendJson(200, service.openSession(request.packageName, request.sessionId, request.newSession))
}
"/api/session/close" -> exchange.requireMethod("POST") {
    val request = exchange.decodeOpenSessionBody()
    val sessionId = request.sessionId ?: service.currentSession().sessionId
    exchange.sendJson(200, service.closeSession(sessionId))
}
```

Add:

```kotlin
@Serializable
private data class OpenSessionRequest(
    val packageName: String? = null,
    val sessionId: String? = null,
    val newSession: Boolean = false,
)
```

Add a `sendJson` overload for `FeedbackSessionList`.

- [x] **Step 4: Run console tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "mcp: add feedback workspace console APIs"
```

## Task 9: Console Session Picker UI

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add asset smoke test**

Add:

```kotlin
@Test
fun consoleHtmlIncludesSessionPickerControls() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains("id=\"sessions\""))
    assertTrue(html.contains("id=\"newSessionButton\""))
    assertTrue(html.contains("/api/session/open"))
}
```

- [x] **Step 2: Run asset test to verify it fails**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.consoleHtmlIncludesSessionPickerControls
```

Expected: assertion fails because session picker elements are absent.

- [x] **Step 3: Update HTML structure**

Add to the left pane above screens:

```html
<div class="toolbar">
  <button id="newSessionButton">New Session</button>
  <button id="closeSessionButton">Close</button>
</div>
<h2>Sessions</h2>
<div id="sessions" class="list"></div>
<h2>Screens</h2>
```

- [x] **Step 4: Add session picker JavaScript**

Add functions:

```javascript
async function refreshSessions() {
  const response = await requestJson('/api/sessions');
  const activeId = state.session?.sessionId;
  document.getElementById('sessions').innerHTML = (response.sessions || []).map(session => `
    <button class="row session-row ${session.sessionId === activeId ? 'active' : ''}" data-session-id="${escapeHtml(session.sessionId)}">
      <strong>${escapeHtml(session.packageName)}</strong>
      <span>${escapeHtml(session.sessionId)} | ${session.itemsCount} item(s)</span>
    </button>
  `).join('') || '<div class="row"><span>No saved sessions.</span></div>';
  document.querySelectorAll('.session-row').forEach(row => {
    row.addEventListener('click', () => openSession(row.dataset.sessionId).catch(showError));
  });
}

async function openSession(sessionId) {
  state.session = await requestJson('/api/session/open', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId })
  });
  await refresh();
}

async function newSession() {
  state.session = await requestJson('/api/session/open', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newSession: true })
  });
  await refresh();
}
```

Call `await refreshSessions()` inside `refresh()` after session JSON is loaded.

- [x] **Step 5: Run console tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "mcp: show feedback workspace picker"
```

## Task 10: Navigation Protocol Models

**Files:**

- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackNavigationModels.kt`
- Create: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackNavigationModelsTest.kt`
- Modify: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServer.kt`

- [ ] **Step 1: Write failing model tests**

Create `FeedbackNavigationModelsTest.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString

class FeedbackNavigationModelsTest {
    @Test
    fun tapNavigationRoundTrips() {
        val request = FeedbackNavigationRequest(action = FeedbackNavigationAction.TAP, x = 10f, y = 20f)

        val decoded = pointPatchJson.decodeFromString(
            FeedbackNavigationRequest.serializer(),
            pointPatchJson.encodeToString(request),
        )

        assertEquals(request, decoded)
    }

    @Test
    fun requestValidationRejectsMissingTapCoordinates() {
        val request = FeedbackNavigationRequest(action = FeedbackNavigationAction.TAP)

        assertFailsWith<IllegalArgumentException> {
            request.validate()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackNavigationModelsTest
```

Expected: compilation fails because navigation models do not exist.

- [ ] **Step 3: Add navigation models**

Create `FeedbackNavigationModels.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackNavigationRequest(
    val action: FeedbackNavigationAction,
    val x: Float? = null,
    val y: Float? = null,
    val direction: FeedbackSwipeDirection? = null,
    val distance: Float? = null,
    val captureAfter: Boolean = true,
) {
    fun validate() {
        when (action) {
            FeedbackNavigationAction.BACK -> Unit
            FeedbackNavigationAction.TAP -> {
                require(x != null && y != null) { "Tap navigation requires x and y" }
                require(x.isFinite() && y.isFinite()) { "Tap coordinates must be finite" }
            }
            FeedbackNavigationAction.SWIPE -> {
                require(direction != null) { "Swipe navigation requires direction" }
                distance?.let { require(it.isFinite() && it > 0f) { "Swipe distance must be greater than 0" } }
            }
        }
    }
}

@Serializable
enum class FeedbackNavigationAction {
    @SerialName("back")
    BACK,

    @SerialName("tap")
    TAP,

    @SerialName("swipe")
    SWIPE,
}

@Serializable
enum class FeedbackSwipeDirection {
    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,

    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT,
}

@Serializable
data class FeedbackNavigationResult(
    val performed: Boolean,
    val action: FeedbackNavigationAction,
    val activityName: String? = null,
    val message: String? = null,
    val screen: CapturedScreen? = null,
    val captureError: String? = null,
)
```

- [ ] **Step 4: Run model tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackNavigationModelsTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackNavigationModels.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackNavigationModelsTest.kt
git commit -m "mcp: add feedback navigation models"
```

## Task 11: Sidekick Navigation Performer

**Files:**

- Create: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/NavigationPerformer.kt`
- Create: `pointpatch-compose-sidekick/src/test/kotlin/io/github/pointpatch/compose/sidekick/bridge/NavigationPerformerTest.kt`

- [ ] **Step 1: Write performer validation tests**

Create `NavigationPerformerTest.kt`:

```kotlin
package io.github.pointpatch.compose.sidekick.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NavigationPerformerTest {
    @Test
    fun tapRequiresCoordinatesInsideBounds() {
        val performer = FakeNavigationPerformer(width = 100, height = 200)

        val result = performer.perform(BridgeNavigationRequest(action = BridgeNavigationAction.TAP, x = 10f, y = 20f))

        assertEquals(true, result.performed)
        assertEquals(BridgeNavigationAction.TAP, performer.actions.single().action)
    }

    @Test
    fun tapRejectsCoordinatesOutsideBounds() {
        val performer = FakeNavigationPerformer(width = 100, height = 200)

        assertFailsWith<IllegalArgumentException> {
            performer.perform(BridgeNavigationRequest(action = BridgeNavigationAction.TAP, x = 200f, y = 20f))
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :pointpatch-compose-sidekick:testDebugUnitTest --tests io.github.pointpatch.compose.sidekick.bridge.NavigationPerformerTest
```

Expected: compilation fails because performer models do not exist.

- [ ] **Step 3: Add performer models and interface**

Create `NavigationPerformer.kt`:

```kotlin
package io.github.pointpatch.compose.sidekick.bridge

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BridgeNavigationRequest(
    val action: BridgeNavigationAction,
    val x: Float? = null,
    val y: Float? = null,
    val direction: BridgeSwipeDirection? = null,
    val distance: Float? = null,
)

@Serializable
enum class BridgeNavigationAction {
    @SerialName("back")
    BACK,
    @SerialName("tap")
    TAP,
    @SerialName("swipe")
    SWIPE,
}

@Serializable
enum class BridgeSwipeDirection {
    @SerialName("up")
    UP,
    @SerialName("down")
    DOWN,
    @SerialName("left")
    LEFT,
    @SerialName("right")
    RIGHT,
}

@Serializable
data class BridgeNavigationResult(
    val performed: Boolean,
    val action: BridgeNavigationAction,
    val activity: String? = null,
    val message: String? = null,
)

interface NavigationPerformer {
    suspend fun perform(request: BridgeNavigationRequest): BridgeNavigationResult
}
```

- [ ] **Step 4: Add Android performer**

In the same file, add:

```kotlin
class AndroidNavigationPerformer(
    private val activityProvider: () -> Activity?,
) : NavigationPerformer {
    override suspend fun perform(request: BridgeNavigationRequest): BridgeNavigationResult =
        withContext(Dispatchers.Main.immediate) {
            val activity = activityProvider() ?: error("No resumed Activity is available for navigation")
            val view = activity.window.decorView
            val width = view.width
            val height = view.height
            when (request.action) {
                BridgeNavigationAction.BACK -> {
                    val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
                    val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
                    view.dispatchKeyEvent(down)
                    view.dispatchKeyEvent(up)
                }
                BridgeNavigationAction.TAP -> {
                    val x = request.x ?: error("Tap navigation requires x")
                    val y = request.y ?: error("Tap navigation requires y")
                    require(x.isFinite() && y.isFinite() && x >= 0f && y >= 0f && x <= width && y <= height) {
                        "Tap coordinates are outside the current window"
                    }
                    dispatchTap(view, x, y)
                }
                BridgeNavigationAction.SWIPE -> {
                    val direction = request.direction ?: error("Swipe navigation requires direction")
                    val distance = request.distance ?: (minOf(width, height) * 0.6f)
                    require(distance.isFinite() && distance > 0f) { "Swipe distance must be greater than 0" }
                    dispatchSwipe(view, width / 2f, height / 2f, direction, distance)
                }
            }
            BridgeNavigationResult(true, request.action, activity::class.java.name)
        }

    private fun dispatchTap(view: android.view.View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun dispatchSwipe(view: android.view.View, startX: Float, startY: Float, direction: BridgeSwipeDirection, distance: Float) {
        val end = when (direction) {
            BridgeSwipeDirection.UP -> startX to startY - distance
            BridgeSwipeDirection.DOWN -> startX to startY + distance
            BridgeSwipeDirection.LEFT -> startX - distance to startY
            BridgeSwipeDirection.RIGHT -> startX + distance to startY
        }
        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, startY, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_MOVE, end.first, end.second, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 160, MotionEvent.ACTION_UP, end.first, end.second, 0))
    }
}
```

- [ ] **Step 5: Add fake performer for tests**

In `NavigationPerformerTest.kt`, add:

```kotlin
private class FakeNavigationPerformer(
    private val width: Int,
    private val height: Int,
) {
    val actions = mutableListOf<BridgeNavigationRequest>()

    fun perform(request: BridgeNavigationRequest): BridgeNavigationResult {
        if (request.action == BridgeNavigationAction.TAP) {
            val x = request.x ?: error("Tap navigation requires x")
            val y = request.y ?: error("Tap navigation requires y")
            require(x >= 0f && y >= 0f && x <= width && y <= height) {
                "Tap coordinates are outside the current window"
            }
        }
        actions += request
        return BridgeNavigationResult(true, request.action, "FakeActivity")
    }
}
```

- [ ] **Step 6: Run performer tests**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :pointpatch-compose-sidekick:testDebugUnitTest --tests io.github.pointpatch.compose.sidekick.bridge.NavigationPerformerTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/NavigationPerformer.kt pointpatch-compose-sidekick/src/test/kotlin/io/github/pointpatch/compose/sidekick/bridge/NavigationPerformerTest.kt
git commit -m "sidekick: add limited navigation performer"
```

## Task 12: Bridge Navigation Method

**Files:**

- Modify: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServer.kt`
- Modify: `pointpatch-compose-sidekick/src/test/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServerTest.kt`

- [ ] **Step 1: Add bridge protocol test**

Add to `BridgeServerTest`:

```kotlin
@Test
fun performNavigationRoutesToEnvironment() = runBlocking {
    val environment = FakeBridgeEnvironment()
    val server = BridgeServer(
        session = BridgeTestFixtures.session,
        environment = environment,
        socketFactory = BridgeTestFixtures.socketFactory(),
    )
    val request = BridgeRequest(
        id = "nav-1",
        token = BridgeTestFixtures.session.token,
        method = "performNavigation",
        params = BridgeProtocol.json.encodeToJsonElement(
            BridgeNavigationRequest.serializer(),
            BridgeNavigationRequest(action = BridgeNavigationAction.BACK),
        ).jsonObject,
    )

    val response = server.handleRequestForTest(BridgeProtocol.json.encodeToString(BridgeRequest.serializer(), request))

    assertTrue(response.contains(""""performed": true"""))
    assertEquals(BridgeNavigationAction.BACK, environment.navigationRequests.single().action)
}
```

- [ ] **Step 2: Run bridge test to verify it fails**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :pointpatch-compose-sidekick:testDebugUnitTest --tests io.github.pointpatch.compose.sidekick.bridge.BridgeServerTest.performNavigationRoutesToEnvironment
```

Expected: compilation fails because `BridgeEnvironment.performNavigation` does
not exist.

- [ ] **Step 3: Extend bridge environment and request dispatch**

Add to `BridgeEnvironment`:

```kotlin
suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult
```

Add to `BridgeServer.handleRequest`:

```kotlin
"performNavigation" -> BridgeProtocol.json.encodeToJsonElement(
    environment.performNavigation(
        BridgeProtocol.json.decodeFromJsonElement(BridgeNavigationRequest.serializer(), request.params),
    ),
)
```

- [ ] **Step 4: Implement Android environment method**

In `AndroidBridgeEnvironment`, add a performer:

```kotlin
private val navigationPerformer = AndroidNavigationPerformer { currentActivity?.get() }
```

Add:

```kotlin
override suspend fun performNavigation(request: BridgeNavigationRequest): BridgeNavigationResult =
    navigationPerformer.perform(request)
```

Update fake bridge environments in tests to record navigation requests.

- [ ] **Step 5: Run bridge tests**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :pointpatch-compose-sidekick:testDebugUnitTest --tests io.github.pointpatch.compose.sidekick.bridge.BridgeServerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServer.kt pointpatch-compose-sidekick/src/test/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServerTest.kt
git commit -m "sidekick: expose limited navigation bridge"
```

## Task 13: CLI And MCP Navigation Wiring

**Files:**

- Modify: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`
- Modify: `pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Add failing MCP navigation test**

Add:

```kotlin
@Test
fun navigateAppPerformsBackAndCapturesResult() = runBlocking {
    val bridge = FakePointPatchBridge(packageName = "io.github.pointpatch.sample")
    val tools = PointPatchTools(
        bridge = bridge,
        defaultPackageName = "io.github.pointpatch.sample",
        projectRoot = createTempDir(prefix = "pointpatch-v2-nav-mcp-"),
    )
    val opened = tools.call("pointpatch_open_feedback_console", jsonObject("newSession" to true)).firstJsonContent()
    val sessionId = opened["sessionId"]!!.jsonPrimitive.content

    val result = tools.call(
        "pointpatch_navigate_app",
        jsonObject("sessionId" to sessionId, "action" to "back", "captureAfter" to true),
    ).firstJsonContent()

    assertEquals(true, result["performed"]!!.jsonPrimitive.boolean)
    assertEquals("back", result["action"]!!.jsonPrimitive.content)
    assertEquals(1, bridge.navigationRequests.size)
    assertTrue(result["screen"] != null)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.McpProtocolTest.navigateAppPerformsBackAndCapturesResult
```

Expected: unknown tool failure.

- [ ] **Step 3: Add BridgeClient navigation method**

In `BridgeClient`:

```kotlin
suspend fun performNavigation(packageName: String, request: JsonObject): JsonObject =
    request(packageName = packageName, method = "performNavigation", params = request)
```

- [ ] **Step 4: Extend MCP bridge interface**

In `PointPatchBridge`:

```kotlin
suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject
```

In `CliPointPatchBridge`:

```kotlin
override suspend fun performNavigation(packageName: String, request: FeedbackNavigationRequest): JsonObject =
    client.performNavigation(
        packageName,
        McpProtocol.json.encodeToJsonElement(FeedbackNavigationRequest.serializer(), request).jsonObject,
    )
```

- [ ] **Step 5: Add service navigation method**

In `FeedbackSessionService`:

```kotlin
suspend fun navigate(sessionId: String, request: FeedbackNavigationRequest): FeedbackNavigationResult {
    request.validate()
    val session = store.getSession(sessionId)
    val bridgeResult = bridge.performNavigation(session.packageName, request)
    val performed = bridgeResult["performed"]?.jsonPrimitive?.booleanOrNull ?: false
    val action = request.action
    val activity = bridgeResult["activity"]?.jsonPrimitive?.contentOrNull
    val message = bridgeResult["message"]?.jsonPrimitive?.contentOrNull
    if (!request.captureAfter || !performed) {
        return FeedbackNavigationResult(performed = performed, action = action, activityName = activity, message = message)
    }
    return runCatching {
        val screen = captureScreen(sessionId)
        FeedbackNavigationResult(performed = performed, action = action, activityName = activity, message = message, screen = screen)
    }.getOrElse { error ->
        FeedbackNavigationResult(performed = performed, action = action, activityName = activity, message = message, captureError = error.message ?: error::class.java.simpleName)
    }
}
```

- [ ] **Step 6: Add MCP tool definition and parser**

Add `pointpatch_navigate_app` definition. Parse:

```kotlin
private fun JsonObject.navigationRequest(): FeedbackNavigationRequest =
    FeedbackNavigationRequest(
        action = stringParam("action")?.toNavigationAction()
            ?: throw PointPatchToolException("pointpatch_navigate_app requires action"),
        x = floatParam("x"),
        y = floatParam("y"),
        direction = stringParam("direction")?.toSwipeDirection(),
        distance = floatParam("distance"),
        captureAfter = booleanParam("captureAfter") != false,
    ).also { it.validate() }
```

Add string converters for `back`, `tap`, `swipe`, `up`, `down`, `left`,
`right`. Add `floatParam` using `jsonPrimitive.floatOrNull`.

- [ ] **Step 7: Add call branch**

```kotlin
"pointpatch_navigate_app" -> bridgeToolResult {
    val session = requestedSession(arguments)
    val result = feedbackService.navigate(session.sessionId, arguments.navigationRequest())
    jsonToolResult(McpProtocol.json.encodeToJsonElement(FeedbackNavigationResult.serializer(), result).jsonObject)
}
```

- [ ] **Step 8: Run MCP navigation tests**

Run:

```bash
./gradlew :pointpatch-cli:test :pointpatch-mcp:test --tests io.github.pointpatch.mcp.McpProtocolTest.navigateAppPerformsBackAndCapturesResult
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt
git commit -m "mcp: navigate app from feedback sessions"
```

## Task 14: Console Navigation API And Controls

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Add failing console navigation API test**

Add:

```kotlin
@Test
fun navigationApiPerformsAction() {
    val bridge = FakePointPatchBridge()
    val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
    val server = FeedbackConsoleServer(service = service, port = 0)
    try {
        server.start()
        val connection = URL("${server.url}/api/navigation").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write("""{"action":"back","captureAfter":false}""".toByteArray()) }

        assertEquals(200, connection.responseCode)
        assertTrue(connection.inputStream.bufferedReader().readText().contains(""""performed": true"""))
    } finally {
        server.stop()
    }
}
```

- [ ] **Step 2: Run console test to verify it fails**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest.navigationApiPerformsAction
```

Expected: route returns 404.

- [ ] **Step 3: Add server route**

In `FeedbackConsoleServer.handle`:

```kotlin
"/api/navigation" -> exchange.requireMethod("POST") {
    val request = exchange.decodeNavigationBody()
    val session = service.currentSession()
    val result = runBlocking { service.navigate(session.sessionId, request) }
    exchange.sendJson(200, result)
}
```

Add `decodeNavigationBody` using `FeedbackNavigationRequest.serializer()`. Add a
`sendJson` overload for `FeedbackNavigationResult`.

- [ ] **Step 4: Add console controls**

Add toolbar in snapshot section:

```html
<div class="toolbar">
  <button id="backButton">Back</button>
  <button id="swipeUpButton">Swipe Up</button>
  <button id="swipeDownButton">Swipe Down</button>
  <button id="swipeLeftButton">Swipe Left</button>
  <button id="swipeRightButton">Swipe Right</button>
  <label><input id="captureAfterNavigation" type="checkbox" checked> Capture after navigation</label>
</div>
```

Add JavaScript:

```javascript
async function navigate(action, extras = {}) {
  error.textContent = '';
  const captureAfter = document.getElementById('captureAfterNavigation').checked;
  await requestJson('/api/navigation', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, captureAfter, ...extras })
  });
  await refresh();
}

document.getElementById('backButton').addEventListener('click', () => navigate('back').catch(showError));
document.getElementById('swipeUpButton').addEventListener('click', () => navigate('swipe', { direction: 'up' }).catch(showError));
document.getElementById('swipeDownButton').addEventListener('click', () => navigate('swipe', { direction: 'down' }).catch(showError));
document.getElementById('swipeLeftButton').addEventListener('click', () => navigate('swipe', { direction: 'left' }).catch(showError));
document.getElementById('swipeRightButton').addEventListener('click', () => navigate('swipe', { direction: 'right' }).catch(showError));
```

For snapshot tap, add click handling to the image:

```javascript
function attachSnapshotTapHandler() {
  const image = snapshot.querySelector('img');
  if (!image) return;
  image.addEventListener('click', event => {
    const rect = image.getBoundingClientRect();
    const scaleX = image.naturalWidth / rect.width;
    const scaleY = image.naturalHeight / rect.height;
    navigate('tap', {
      x: (event.clientX - rect.left) * scaleX,
      y: (event.clientY - rect.top) * scaleY
    }).catch(showError);
  });
}
```

Call `attachSnapshotTapHandler()` at the end of `render()`.

- [ ] **Step 5: Run console tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "mcp: add console navigation controls"
```

## Task 15: Docs And Schema Updates

**Files:**

- Modify: `README.md`
- Modify: `docs/mcp.md`
- Modify: `docs/output-schema.md`
- Modify: `docs/privacy.md`
- Modify: `docs/troubleshooting.md`

- [ ] **Step 1: Update README**

Add a feedback workspace note near the MCP feedback console section:

```markdown
Feedback console sessions are resumable. PointPatch saves feedback workspace
metadata and screenshot artifacts under `.pointpatch/feedback-sessions/`, so an
MCP or console restart does not discard queued feedback.
```

- [ ] **Step 2: Update MCP docs**

Add tools:

```markdown
- `pointpatch_list_feedback_sessions`: list resumable feedback workspaces for the project.
- `pointpatch_navigate_app`: perform one debug-only `back`, `tap`, or `swipe` action and optionally capture the resulting screen.
```

Document `pointpatch_open_feedback_console` arguments:

```markdown
Arguments:
- `packageName`: optional package override.
- `sessionId`: optional persisted feedback session to reopen.
- `newSession`: optional boolean. When true, create a new session instead of resuming the latest active one.
```

- [ ] **Step 3: Update output schema**

Add sections:

```markdown
## Feedback Session Summary

Fields: `sessionId`, `packageName`, `projectRoot`, `createdAtEpochMillis`,
`updatedAtEpochMillis`, `status`, `screensCount`, `itemsCount`,
`unresolvedItemsCount`.

## Feedback Navigation Result

Fields: `performed`, `action`, `activityName`, `message`, `screen`,
`captureError`.
```

- [ ] **Step 4: Update privacy docs**

Add:

```markdown
Feedback workspace files are local project artifacts under
`.pointpatch/feedback-sessions/`. Navigation actions are debug-only touch or key
events dispatched inside the app process through the existing sidekick bridge;
the Android app does not open a network service.
```

- [ ] **Step 5: Update troubleshooting**

Add entries:

```markdown
### I reopened the console and do not see my previous feedback

Run `pointpatch_list_feedback_sessions` or reopen the console with the exact
`sessionId`. Verify `.pointpatch/feedback-sessions/` exists under the same
project root used by the MCP server.

### Navigation worked but no new screen appeared

The navigation action can succeed while follow-up capture fails. Check the
`captureError` field or click Capture manually after the app finishes drawing.
```

- [ ] **Step 6: Run docs grep check**

Run:

```bash
rg -n "feedback-sessions|pointpatch_list_feedback_sessions|pointpatch_navigate_app|sessionId|captureAfter" README.md docs
```

Expected: output includes README and MCP/schema/troubleshooting/privacy docs.

- [ ] **Step 7: Commit**

```bash
git add README.md docs/mcp.md docs/output-schema.md docs/privacy.md docs/troubleshooting.md
git commit -m "docs: document feedback workspace navigation"
```

## Task 16: Final Verification

**Files:**

- No source edits unless verification exposes a defect.

- [ ] **Step 1: Run JVM tests**

Run:

```bash
./gradlew :pointpatch-compose-core:test :pointpatch-cli:test :pointpatch-mcp:test
```

Expected: PASS.

- [ ] **Step 2: Run sidekick unit tests**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk ./gradlew :pointpatch-compose-sidekick:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Build distributions**

Run:

```bash
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
```

Expected: PASS. Binaries exist:

```text
pointpatch-cli/build/install/pointpatch/bin/pointpatch
pointpatch-mcp/build/install/pointpatch-mcp/bin/pointpatch-mcp
```

- [ ] **Step 4: Run no-device persistence smoke**

Run:

```bash
pointpatch-cli/build/install/pointpatch/bin/pointpatch console --package io.github.pointpatch.sample
```

Expected: console prints a localhost URL. Opening and refreshing the browser
does not crash. Stop the process with Ctrl-C after confirming the URL prints.

- [ ] **Step 5: Run connected emulator smoke when available**

Run:

```bash
adb devices
./gradlew :app:installDebug
pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.github.pointpatch.sample
pointpatch-cli/build/install/pointpatch/bin/pointpatch console --package io.github.pointpatch.sample
```

Expected:

- capture adds a screen
- adding feedback creates an item
- stopping and restarting console preserves the session
- reopening by `sessionId` shows the same screens and items
- Back, Tap, and Swipe each return a navigation result
- navigation with capture enabled adds a new screen
- `pointpatch_list_feedback_sessions` returns the session
- `pointpatch_navigate_app` through MCP can perform one action
- `pointpatch_read_feedback` returns the updated queue

- [ ] **Step 6: Commit verification notes only if docs changed**

If verification required a docs correction, commit:

```bash
git add README.md docs/mcp.md docs/output-schema.md docs/privacy.md docs/troubleshooting.md
git commit -m "docs: clarify feedback workspace verification"
```

## Plan Self-Review

- Spec coverage: this plan covers persistent sessions, session listing, exact
  resume, session-owned artifacts, console session picker, sidekick navigation,
  MCP navigation, console navigation, docs, and verification.
- Placeholder scan: this plan intentionally avoids incomplete marker terms and
  unbounded implementation instructions.
- Type consistency: session types use `FeedbackSession`, `FeedbackSessionList`,
  `FeedbackSessionSummary`, `FeedbackNavigationRequest`, and
  `FeedbackNavigationResult`; bridge types use `BridgeNavigationRequest`,
  `BridgeNavigationAction`, `BridgeSwipeDirection`, and
  `BridgeNavigationResult`.
- Scope control: arbitrary text input, scripted exploration, cloud sync, and
  general automation are excluded from the tasks.
