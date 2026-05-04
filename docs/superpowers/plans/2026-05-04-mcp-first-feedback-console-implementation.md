# MCP-First Feedback Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an MCP-owned desktop feedback console that captures Android Compose screen snapshots, collects multi-screen feedback, and exposes that queue to AI agents through MCP.

**Architecture:** The MCP process owns an in-memory feedback session and serves a localhost web console for humans. The Android sidekick remains a debug-only runtime data source, reached through the existing ADB/local bridge. The CLI `pointpatch console` command delegates to the MCP executable's console mode so non-MCP users can open the same experience.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization, kotlinx.coroutines, Clikt, Java `HttpServer`, existing ADB bridge, Android Compose sidekick, stdio MCP JSON-RPC.

---

## Scope Check

This plan covers one cohesive feature: an MCP-owned feedback queue with a desktop console. It touches MCP, CLI, sidekick bridge, and docs, but each subsystem change serves the same end-to-end workflow:

```text
open console -> capture screen -> create feedback items -> list/read/resolve through MCP
```

Remote app control remains out of scope. The console captures and annotates snapshots; it does not send arbitrary taps, text, or gestures to Android.

## File Structure

Create or modify these files:

- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionModels.kt`: serializable session, screen, target, item, and status models.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt`: thread-safe in-memory session store.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt`: Markdown and JSON export helpers for queues.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`: package resolution, bridge calls, capture coordination, item creation, and resolution.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt`: localhost HTTP server and JSON API.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`: HTML, CSS, and JavaScript strings for the console.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`: add feedback-session tools while preserving existing tools.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/McpServer.kt`: add console mode CLI parsing for `pointpatch-mcp --console`.
- `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServer.kt`: add `captureScreenSnapshot` bridge method and serializable result.
- `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`: add `captureScreenSnapshot` and screen screenshot artifact pull.
- `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/commands/ConsoleCommand.kt`: new CLI command that delegates to `pointpatch-mcp --console`.
- `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/Main.kt`: register `ConsoleCommand`.
- `docs/mcp.md`, `README.md`, `docs/output-schema.md`, `docs/troubleshooting.md`, `docs/privacy.md`: document the new flow.

Testing files:

- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt`
- `pointpatch-compose-sidekick/src/test/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServerTest.kt`
- `pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt`
- `pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/commands/ConsoleCommandTest.kt`

## Task 1: Feedback Session Models

**Files:**
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionModels.kt`
- Test: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`

- [x] **Step 1: Write model serialization tests**

Create `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackSessionStoreTest {
    @Test
    fun feedbackSessionRoundTripsThroughJson() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            screens = listOf(
                CapturedScreen(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 11L,
                    activityName = "MainActivity",
                    displayName = "MainActivity",
                    screenshot = FeedbackScreenshot(desktopFullPath = "/repo/.pointpatch/artifacts/screen-1/full.png"),
                ),
            ),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 12L,
                    updatedAtEpochMillis = 13L,
                    target = FeedbackTarget.Area(PointPatchRect(1f, 2f, 3f, 4f)),
                    comment = "Make this button clearer",
                    status = FeedbackItemStatus.READY,
                ),
            ),
            status = FeedbackSessionStatus.READY_FOR_AGENT,
        )

        val encoded = pointPatchJson.encodeToString(FeedbackSession.serializer(), session)
        val decoded = pointPatchJson.decodeFromString(FeedbackSession.serializer(), encoded)

        assertEquals(session, decoded)
        assertTrue(encoded.contains("ready_for_agent"))
        assertTrue(encoded.contains("visual_area"))
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest.feedbackSessionRoundTripsThroughJson
```

Expected: compilation fails because `FeedbackSession` and related models do not exist.

- [x] **Step 3: Add serializable models**

Create `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionModels.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.SourceCandidate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackSession(
    val schemaVersion: String = "1.0",
    val sessionId: String,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val screens: List<CapturedScreen> = emptyList(),
    val items: List<FeedbackItem> = emptyList(),
    val status: FeedbackSessionStatus = FeedbackSessionStatus.ACTIVE,
)

@Serializable
enum class FeedbackSessionStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("ready_for_agent")
    READY_FOR_AGENT,

    @SerialName("closed")
    CLOSED,
}

@Serializable
data class CapturedScreen(
    val screenId: String,
    val capturedAtEpochMillis: Long,
    val activityName: String? = null,
    val displayName: String,
    val screenshot: FeedbackScreenshot? = null,
    val roots: List<FeedbackScreenRoot> = emptyList(),
    val sourceIndexAvailable: Boolean = false,
    val errors: List<PointPatchError> = emptyList(),
)

@Serializable
data class FeedbackScreenRoot(
    val rootIndex: Int,
    val boundsInWindow: PointPatchRect,
    val mergedNodes: List<PointPatchNode> = emptyList(),
    val unmergedNodes: List<PointPatchNode> = emptyList(),
)

@Serializable
data class FeedbackScreenshot(
    val fullPath: String? = null,
    val cropPath: String? = null,
    val desktopFullPath: String? = null,
    val desktopCropPath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val captureFailedReason: String? = null,
)

@Serializable
data class FeedbackItem(
    val itemId: String,
    val screenId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val target: FeedbackTarget,
    val selectedNode: PointPatchNode? = null,
    val nearbyNodes: List<PointPatchNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val screenshotCrop: FeedbackScreenshot? = null,
    val comment: String,
    val status: FeedbackItemStatus = FeedbackItemStatus.OPEN,
    val agentSummary: String? = null,
)

@Serializable
sealed interface FeedbackTarget {
    @Serializable
    @SerialName("semantics_node")
    data class Node(val nodeUid: String, val boundsInWindow: PointPatchRect) : FeedbackTarget

    @Serializable
    @SerialName("visual_area")
    data class Area(val boundsInWindow: PointPatchRect) : FeedbackTarget
}

@Serializable
enum class FeedbackItemStatus {
    @SerialName("open")
    OPEN,

    @SerialName("ready")
    READY,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("resolved")
    RESOLVED,

    @SerialName("needs_clarification")
    NEEDS_CLARIFICATION,

    @SerialName("wont_fix")
    WONT_FIX,
}
```

- [x] **Step 4: Run model test**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest.feedbackSessionRoundTripsThroughJson
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionModels.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "mcp: add feedback session models"
```

## Task 2: In-Memory Feedback Session Store

**Files:**
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`

- [x] **Step 1: Add store behavior tests**

Append to `FeedbackSessionStoreTest`:

```kotlin
    @Test
    fun storeCreatesSessionAndAddsScreenAndItem() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)

        val session = store.openSession(
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
        )
        val screen = store.addScreen(
            sessionId = session.sessionId,
            screen = CapturedScreen(
                screenId = "ignored",
                capturedAtEpochMillis = -1L,
                displayName = "Checkout",
            ),
        )
        val item = store.addItem(
            sessionId = session.sessionId,
            item = FeedbackItem(
                itemId = "ignored",
                screenId = screen.screenId,
                createdAtEpochMillis = -1L,
                updatedAtEpochMillis = -1L,
                target = FeedbackTarget.Area(PointPatchRect(1f, 1f, 10f, 10f)),
                comment = "Increase contrast",
            ),
        )

        val current = store.getSession(session.sessionId)
        assertEquals("session-1", session.sessionId)
        assertEquals("screen-1", screen.screenId)
        assertEquals("item-1", item.itemId)
        assertEquals(1, current.screens.size)
        assertEquals(1, current.items.size)
        assertEquals(FeedbackItemStatus.OPEN, current.items.single().status)
    }

    @Test
    fun storeResolvesFeedbackItem() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.pointpatch.sample", "/repo")
        val screen = store.addScreen(session.sessionId, CapturedScreen("ignored", -1L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            FeedbackItem(
                itemId = "ignored",
                screenId = screen.screenId,
                createdAtEpochMillis = -1L,
                updatedAtEpochMillis = -1L,
                target = FeedbackTarget.Area(PointPatchRect(1f, 1f, 10f, 10f)),
                comment = "Increase contrast",
            ),
        )

        val resolved = store.updateItemStatus(
            sessionId = session.sessionId,
            itemId = "item-1",
            status = FeedbackItemStatus.RESOLVED,
            agentSummary = "Adjusted color token.",
        )

        assertEquals(FeedbackItemStatus.RESOLVED, resolved.status)
        assertEquals("Adjusted color token.", resolved.agentSummary)
        assertEquals(100L, resolved.updatedAtEpochMillis)
    }

    private class FakeClock(private val value: Long) {
        fun now(): Long = value
    }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        fun next(): String = queue.removeFirst()
    }
```

- [x] **Step 2: Run store tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest
```

Expected: compilation fails because `FeedbackSessionStore` does not exist.

- [x] **Step 3: Implement store**

Create `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import java.util.UUID

class FeedbackSessionStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val sessions = linkedMapOf<String, FeedbackSession>()
    private var currentSessionId: String? = null

    fun openSession(packageName: String, projectRoot: String): FeedbackSession =
        synchronized(lock) {
            val now = clock()
            val session = FeedbackSession(
                sessionId = idGenerator(),
                packageName = packageName,
                projectRoot = projectRoot,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            sessions[session.sessionId] = session
            currentSessionId = session.sessionId
            session
        }

    fun currentSession(): FeedbackSession? =
        synchronized(lock) { currentSessionId?.let { sessions[it] } }

    fun getSession(sessionId: String): FeedbackSession =
        synchronized(lock) {
            sessions[sessionId] ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")
        }

    fun addScreen(sessionId: String, screen: CapturedScreen): CapturedScreen =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            val captured = screen.copy(
                screenId = idGenerator(),
                capturedAtEpochMillis = now,
            )
            sessions[sessionId] = session.copy(
                screens = session.screens + captured,
                updatedAtEpochMillis = now,
            )
            captured
        }

    fun addItem(sessionId: String, item: FeedbackItem): FeedbackItem =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            require(session.screens.any { it.screenId == item.screenId }) {
                "Cannot add feedback for unknown screen: ${item.screenId}"
            }
            val now = clock()
            val created = item.copy(
                itemId = idGenerator(),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            sessions[sessionId] = session.copy(
                items = session.items + created,
                updatedAtEpochMillis = now,
            )
            created
        }

    fun markReadyForAgent(sessionId: String): FeedbackSession =
        synchronized(lock) {
            val session = getSessionLocked(sessionId)
            val now = clock()
            val updated = session.copy(
                status = FeedbackSessionStatus.READY_FOR_AGENT,
                updatedAtEpochMillis = now,
            )
            sessions[sessionId] = updated
            updated
        }

    fun updateItemStatus(
        sessionId: String,
        itemId: String,
        status: FeedbackItemStatus,
        agentSummary: String?,
    ): FeedbackItem =
        synchronized(lock) {
            require(status in setOf(FeedbackItemStatus.RESOLVED, FeedbackItemStatus.NEEDS_CLARIFICATION, FeedbackItemStatus.WONT_FIX)) {
                "Agent resolution status is not allowed: $status"
            }
            val session = getSessionLocked(sessionId)
            val now = clock()
            var updatedItem: FeedbackItem? = null
            val updatedItems = session.items.map { item ->
                if (item.itemId == itemId) {
                    item.copy(
                        status = status,
                        agentSummary = agentSummary,
                        updatedAtEpochMillis = now,
                    ).also { updatedItem = it }
                } else {
                    item
                }
            }
            val item = updatedItem ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
            sessions[sessionId] = session.copy(items = updatedItems, updatedAtEpochMillis = now)
            item
        }

    private fun getSessionLocked(sessionId: String): FeedbackSession =
        sessions[sessionId] ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")
}

class FeedbackSessionException(message: String) : RuntimeException(message)
```

- [x] **Step 4: Run store tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "mcp: store feedback sessions in memory"
```

## Task 3: Queue Formatting

**Files:**
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt`
- Create: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt`

- [x] **Step 1: Write formatter tests**

Create `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlin.test.Test
import kotlin.test.assertTrue

class FeedbackQueueFormatterTest {
    @Test
    fun markdownIncludesScreensItemsAndWarnings() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            screens = listOf(
                CapturedScreen(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Checkout",
                    screenshot = FeedbackScreenshot(desktopFullPath = "/repo/.pointpatch/artifacts/screen-1/full.png"),
                ),
            ),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = FeedbackTarget.Area(PointPatchRect(10f, 20f, 110f, 70f)),
                    comment = "Increase button contrast",
                    status = FeedbackItemStatus.READY,
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session)

        assertTrue(markdown.contains("# PointPatch Feedback Queue"))
        assertTrue(markdown.contains("io.github.pointpatch.sample"))
        assertTrue(markdown.contains("Checkout"))
        assertTrue(markdown.contains("Increase button contrast"))
        assertTrue(markdown.contains("Screenshots are local debug artifacts"))
    }
}
```

- [x] **Step 2: Run formatter test to verify it fails**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest
```

Expected: compilation fails because `FeedbackQueueFormatter` does not exist.

- [x] **Step 3: Implement formatter**

Create `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson

object FeedbackQueueFormatter {
    fun toJson(session: FeedbackSession): String =
        pointPatchJson.encodeToString(FeedbackSession.serializer(), session)

    fun toMarkdown(session: FeedbackSession): String = buildString {
        appendLine("# PointPatch Feedback Queue")
        appendLine()
        appendLine("- Session: `${session.sessionId}`")
        appendLine("- Package: `${session.packageName}`")
        appendLine("- Status: `${session.status.name.lowercase()}`")
        appendLine()
        appendLine("> Screenshots are local debug artifacts. Review them before sharing exported content.")
        appendLine()
        session.screens.forEach { screen ->
            appendLine("## Screen: ${screen.displayName}")
            appendLine()
            appendLine("- Screen ID: `${screen.screenId}`")
            screen.activityName?.let { appendLine("- Activity: `$it`") }
            screen.screenshot?.desktopFullPath?.let { appendLine("- Screenshot: `$it`") }
            val items = session.items.filter { it.screenId == screen.screenId }
            if (items.isEmpty()) {
                appendLine()
                appendLine("No feedback items for this screen.")
                appendLine()
            } else {
                items.forEachIndexed { index, item ->
                    appendLine()
                    appendLine("### ${index + 1}. ${item.comment.lineSequence().firstOrNull().orEmpty().ifBlank { "(No comment)" }}")
                    appendLine()
                    appendLine("- Item ID: `${item.itemId}`")
                    appendLine("- Status: `${item.status.name.lowercase()}`")
                    appendLine("- Target: `${item.target.describe()}`")
                    item.sourceCandidates.firstOrNull()?.let { candidate ->
                        appendLine("- Source candidate: `${candidate.file}${candidate.line?.let { line -> ":$line" }.orEmpty()}`")
                    }
                    appendLine()
                    appendLine(item.comment.ifBlank { "(No comment)" })
                }
                appendLine()
            }
        }
    }

    private fun FeedbackTarget.describe(): String =
        when (this) {
            is FeedbackTarget.Area -> "area ${boundsInWindow.left},${boundsInWindow.top},${boundsInWindow.right},${boundsInWindow.bottom}"
            is FeedbackTarget.Node -> "node $nodeUid"
        }
}
```

- [x] **Step 4: Run formatter tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt
git commit -m "mcp: format feedback queues"
```

## Task 4: Sidekick Screen Snapshot Bridge Method

**Files:**
- Modify: `pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServer.kt`
- Modify: `pointpatch-compose-sidekick/src/test/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServerTest.kt`

- [x] **Step 1: Add bridge protocol test**

Add to `BridgeServerTest`:

```kotlin
    @Test
    fun captureScreenSnapshotReturnsSnapshotResult() = runBlocking {
        val server = server(
            environment = RecordingBridgeEnvironment(
                screenSnapshot = BridgeScreenSnapshot(
                    activity = "MainActivity",
                    inspection = BridgeScreenInspection(activity = "MainActivity"),
                    screenshot = ScreenshotInfo(fullPath = "/cache/screen.png"),
                    sourceIndexAvailable = true,
                ),
            ),
        )

        val response = server.handleForTest(
            """{"id":"1","token":"token","method":"captureScreenSnapshot","params":{}}""",
        )

        assertTrue(response.contains("MainActivity"))
        assertTrue(response.contains("/cache/screen.png"))
        assertTrue(response.contains("sourceIndexAvailable"))
    }
```

Update the test fake `RecordingBridgeEnvironment` with a constructor parameter:

```kotlin
private val screenSnapshot: BridgeScreenSnapshot = BridgeScreenSnapshot(
    inspection = BridgeScreenInspection(activity = "MainActivity"),
)
```

and implement:

```kotlin
override suspend fun captureScreenSnapshot(): BridgeScreenSnapshot = screenSnapshot
```

- [x] **Step 2: Run sidekick bridge test to verify it fails**

Run:

```bash
./gradlew :pointpatch-compose-sidekick:testDebugUnitTest --tests io.github.pointpatch.compose.sidekick.bridge.BridgeServerTest.captureScreenSnapshotReturnsSnapshotResult
```

Expected: compilation fails because `BridgeScreenSnapshot` and `captureScreenSnapshot` do not exist.

- [x] **Step 3: Add bridge method and data model**

In `BridgeServer.kt`, add a request branch:

```kotlin
"captureScreenSnapshot" -> BridgeProtocol.json.encodeToJsonElement(environment.captureScreenSnapshot())
```

Extend `BridgeEnvironment`:

```kotlin
suspend fun captureScreenSnapshot(): BridgeScreenSnapshot
```

Add the serializable model near `BridgeScreenInspection`:

```kotlin
@Serializable
data class BridgeScreenSnapshot(
    val activity: String? = null,
    val inspection: BridgeScreenInspection,
    val screenshot: ScreenshotInfo? = null,
    val sourceIndexAvailable: Boolean = false,
)
```

Update `AndroidBridgeEnvironment` constructor:

```kotlin
private val screenshotCapturer: ScreenshotCapturer = ScreenshotCapturer(ScreenshotStore(context)),
```

Add the implementation:

```kotlin
override suspend fun captureScreenSnapshot(): BridgeScreenSnapshot =
    withContext(mainDispatcher) {
        val activity = currentActivity?.get()
        if (activity == null) {
            val inspection = BridgeScreenInspection(
                errors = listOf(PointPatchError("NO_ACTIVITY", "No resumed Activity is available")),
            )
            return@withContext BridgeScreenSnapshot(
                inspection = inspection,
                sourceIndexAvailable = context.hasAsset("pointpatch/pointpatch-source-index.json"),
            )
        }

        val inspection = inspectCurrentScreen()
        val screenshot = screenshotCapturer.capture(
            activity = activity,
            annotationId = "screen-${System.currentTimeMillis()}",
            selectedBounds = null,
        )
        BridgeScreenSnapshot(
            activity = activity::class.java.name,
            inspection = inspection,
            screenshot = screenshot,
            sourceIndexAvailable = context.hasAsset("pointpatch/pointpatch-source-index.json"),
        )
    }
```

- [x] **Step 4: Run sidekick bridge tests**

Run:

```bash
./gradlew :pointpatch-compose-sidekick:testDebugUnitTest --tests io.github.pointpatch.compose.sidekick.bridge.BridgeServerTest
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add pointpatch-compose-sidekick/src/main/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServer.kt pointpatch-compose-sidekick/src/test/kotlin/io/github/pointpatch/compose/sidekick/bridge/BridgeServerTest.kt
git commit -m "sidekick: expose screen snapshot capture"
```

## Task 5: CLI Bridge Client Screen Capture

**Files:**
- Modify: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`
- Modify: `pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt`

- [ ] **Step 1: Add BridgeClient test**

Add to `BridgeClientTest`:

```kotlin
    @Test
    fun captureScreenSnapshotPullsFullScreenshotArtifact() = runBlocking {
        val adb = RecordingAdb(
            devices = listOf(AdbDevice("emulator-5554", "device")),
            sessionJson = sessionJson(),
        )
        val socket = RecordingBridgeSocket(
            responses = listOf(
                bridgeSuccess(
                    """{
                      "bridgeProtocolVersion":"1.0",
                      "activity":"MainActivity",
                      "inspection":{"activity":"MainActivity","roots":[],"errors":[]},
                      "screenshot":{"fullPath":"/data/user/0/pkg/cache/pointpatch/full.png"},
                      "sourceIndexAvailable":true
                    }""",
                ),
                bridgeSuccess(
                    """{
                      "bridgeProtocolVersion":"1.0",
                      "path":"/data/user/0/pkg/cache/pointpatch/full.png",
                      "kind":"full",
                      "mimeType":"image/png",
                      "base64":"iVBORw0KGgo="
                    }""",
                ),
            ),
        )
        val root = createTempDir(prefix = "pointpatch-bridge-test")
        val client = BridgeClient(
            adb = adb,
            projectRoot = root,
            portAllocator = { 41001 },
            socketConnector = { socket },
        )

        val result = client.captureScreenSnapshot("io.github.pointpatch.sample")

        val screenshot = result.getValue("screenshot").jsonObject
        assertTrue(screenshot.getValue("desktopFullPath").jsonPrimitive.content.endsWith("-full.png"))
        assertEquals(listOf("captureScreenSnapshot", "readScreenshot"), socket.requestMethods)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :pointpatch-cli:test --tests io.github.pointpatch.cli.BridgeClientTest.captureScreenSnapshotPullsFullScreenshotArtifact
```

Expected: compilation fails because `captureScreenSnapshot` does not exist.

- [ ] **Step 3: Implement client method**

In `BridgeClient.kt`, add:

```kotlin
suspend fun captureScreenSnapshot(packageName: String): JsonObject {
    val result = request(packageName = packageName, method = "captureScreenSnapshot")
    val screenshot = result["screenshot"]?.jsonObject ?: return result
    val screenId = "screen-${System.currentTimeMillis()}".sanitizedPathSegment()
    val artifactDirectory = projectRoot.resolve(".pointpatch/artifacts/$screenId")
    check(artifactDirectory.exists() || artifactDirectory.mkdirs()) {
        "Could not create PointPatch artifact directory: ${artifactDirectory.absolutePath}"
    }
    val fullDesktopPath = readScreenshotArtifact(
        packageName = packageName,
        kind = "full",
        androidPath = screenshot["fullPath"]?.jsonPrimitive?.contentOrNull,
        destination = artifactDirectory.resolve("$screenId-full.png"),
    )
    val rewrittenScreenshot = buildJsonObject {
        screenshot.forEach { (key, value) -> put(key, value) }
        fullDesktopPath?.let { put("desktopFullPath", it) }
    }
    return buildJsonObject {
        result.forEach { (key, value) -> put(key, value) }
        put("screenshot", rewrittenScreenshot)
    }
}
```

- [ ] **Step 4: Run BridgeClient tests**

Run:

```bash
./gradlew :pointpatch-cli:test --tests io.github.pointpatch.cli.BridgeClientTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/BridgeClientTest.kt
git commit -m "cli: pull screen snapshot artifacts"
```

## Task 6: Feedback Session Service

**Files:**
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`
- Create: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`
- Create: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`

- [ ] **Step 1: Extend bridge interface**

In `PointPatchTools.kt`, add to `PointPatchBridge`:

```kotlin
suspend fun captureScreenSnapshot(packageName: String): JsonObject
```

Add to `CliPointPatchBridge`:

```kotlin
override suspend fun captureScreenSnapshot(packageName: String): JsonObject =
    client.captureScreenSnapshot(packageName)
```

- [ ] **Step 2: Write service tests**

Create `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.tools.PointPatchBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackSessionServiceTest {
    @Test
    fun captureScreenAddsScreenToCurrentSession() = runBlocking {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next)
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )

        val session = service.openSession(null)
        val screen = service.captureScreen(session.sessionId)

        assertEquals("screen-1", screen.screenId)
        assertEquals("MainActivity", screen.displayName)
        assertEquals(1, store.getSession(session.sessionId).screens.size)
    }

    @Test
    fun addAreaFeedbackStoresItemForScreen() = runBlocking {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next)
        val service = FeedbackSessionService(FakePointPatchBridge(), store, "/repo", "io.github.pointpatch.sample")
        val session = service.openSession(null)
        val screen = service.captureScreen(session.sessionId)

        val item = service.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = PointPatchRect(1f, 2f, 3f, 4f),
            comment = "Fix spacing",
        )

        assertEquals("item-1", item.itemId)
        assertEquals("Fix spacing", item.comment)
    }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }

}
```

Create `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.mcp.tools.PointPatchBridge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class FakePointPatchBridge : PointPatchBridge {
    override fun resolvePackageName(packageOverride: String?): String =
        packageOverride ?: "io.github.pointpatch.sample"

    override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())
    override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())
    override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())
    override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject = JsonObject(emptyMap())
    override suspend fun captureScreenSnapshot(packageName: String): JsonObject = buildJsonObject {
        put("activity", "MainActivity")
        put("sourceIndexAvailable", true)
        put("inspection", buildJsonObject {
            put("activity", "MainActivity")
            put("roots", JsonArray(emptyList()))
            put("errors", JsonArray(emptyList()))
        })
        put("screenshot", buildJsonObject {
            put("desktopFullPath", "/repo/.pointpatch/artifacts/screen-1/full.png")
        })
    }
}
```

- [ ] **Step 3: Run service tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
```

Expected: compilation fails because `FeedbackSessionService` does not exist.

- [ ] **Step 4: Implement service**

Create `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`:

```kotlin
package io.github.pointpatch.mcp.session

import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.McpProtocol
import io.github.pointpatch.mcp.tools.PointPatchBridge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FeedbackSessionService(
    private val bridge: PointPatchBridge,
    private val store: FeedbackSessionStore = FeedbackSessionStore(),
    private val projectRoot: String,
    private val defaultPackageName: String? = null,
) {
    fun openSession(packageNameOverride: String?): FeedbackSession {
        val packageName = bridge.resolvePackageName(packageNameOverride ?: defaultPackageName)
        return store.currentSession()
            ?.takeIf { it.packageName == packageName && it.projectRoot == projectRoot && it.status != FeedbackSessionStatus.CLOSED }
            ?: store.openSession(packageName = packageName, projectRoot = projectRoot)
    }

    fun currentSession(): FeedbackSession =
        store.currentSession() ?: openSession(null)

    fun getSession(sessionId: String): FeedbackSession = store.getSession(sessionId)

    suspend fun captureScreen(sessionId: String): CapturedScreen {
        val session = store.getSession(sessionId)
        val payload = bridge.captureScreenSnapshot(session.packageName)
        val inspection = payload["inspection"]?.jsonObject
        val screen = CapturedScreen(
            screenId = "pending",
            capturedAtEpochMillis = 0L,
            activityName = payload["activity"]?.jsonPrimitive?.contentOrNull
                ?: inspection?.get("activity")?.jsonPrimitive?.contentOrNull,
            displayName = payload["activity"]?.jsonPrimitive?.contentOrNull
                ?.substringAfterLast('.')
                ?: "Screen ${session.screens.size + 1}",
            screenshot = payload["screenshot"]?.jsonObject?.let {
                McpProtocol.json.decodeFromJsonElement<FeedbackScreenshot>(it)
            },
            roots = inspection?.get("roots")?.jsonArray?.map { element ->
                McpProtocol.json.decodeFromJsonElement<FeedbackScreenRoot>(element)
            }.orEmpty(),
            sourceIndexAvailable = payload["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull ?: false,
            errors = inspection?.get("errors")?.jsonArray?.map { element ->
                McpProtocol.json.decodeFromJsonElement(element)
            }.orEmpty(),
        )
        return store.addScreen(sessionId, screen)
    }

    fun addAreaFeedback(
        sessionId: String,
        screenId: String,
        bounds: PointPatchRect,
        comment: String,
    ): FeedbackItem =
        store.addItem(
            sessionId,
            FeedbackItem(
                itemId = "pending",
                screenId = screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = FeedbackTarget.Area(bounds),
                comment = comment,
                status = if (comment.isBlank()) FeedbackItemStatus.OPEN else FeedbackItemStatus.READY,
            ),
        )

    fun markReadyForAgent(sessionId: String): FeedbackSession = store.markReadyForAgent(sessionId)

    fun resolveFeedback(sessionId: String, itemId: String, status: FeedbackItemStatus, summary: String?): FeedbackItem =
        store.updateItemStatus(sessionId, itemId, status, summary)
}
```

- [ ] **Step 5: Run service tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt
git commit -m "mcp: coordinate feedback sessions"
```

## Task 7: MCP Feedback Tools

**Files:**
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Add MCP protocol tests for new tools**

Add tests to `McpProtocolTest`:

```kotlin
    @Test
    fun toolsListIncludesFeedbackQueueTools() = runBlocking {
        val response = handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")

        assertTrue(response.contains("pointpatch_open_feedback_console"))
        assertTrue(response.contains("pointpatch_capture_screen"))
        assertTrue(response.contains("pointpatch_list_feedback"))
        assertTrue(response.contains("pointpatch_read_feedback"))
        assertTrue(response.contains("pointpatch_resolve_feedback"))
    }

    @Test
    fun listFeedbackReturnsCurrentSessionQueue() = runBlocking {
        val tools = PointPatchTools(
            bridge = FakePointPatchBridge(),
            defaultPackageName = "io.github.pointpatch.sample",
            projectRoot = File("/repo"),
        )
        val protocol = McpProtocol(tools)
        protocol.handleLine("""{"jsonrpc":"2.0","id":"open","method":"tools/call","params":{"name":"pointpatch_open_feedback_console","arguments":{}}}""")

        val response = protocol.handleLine(
            """{"jsonrpc":"2.0","id":"list","method":"tools/call","params":{"name":"pointpatch_list_feedback","arguments":{}}}""",
        ).orEmpty()

        assertTrue(response.contains("sessionId"))
        assertTrue(response.contains("io.github.pointpatch.sample"))
    }
```

- [ ] **Step 2: Run MCP tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.McpProtocolTest
```

Expected: FAIL because new tools are not registered.

- [ ] **Step 3: Wire service into PointPatchTools**

Change the `PointPatchTools` constructor:

```kotlin
class PointPatchTools(
    private val bridge: PointPatchBridge = CliPointPatchBridge(BridgeClient()),
    private val defaultPackageName: String? = null,
    private val projectRoot: File = File(".").canonicalFile,
    private val feedbackService: FeedbackSessionService = FeedbackSessionService(
        bridge = bridge,
        projectRoot = projectRoot.absolutePath,
        defaultPackageName = defaultPackageName,
    ),
)
```

Add cases in `call`:

```kotlin
"pointpatch_open_feedback_console" -> bridgeToolResult {
    val session = feedbackService.openSession(arguments.stringParam("packageName"))
    jsonToolResult(buildJsonObject {
        put("sessionId", session.sessionId)
        put("packageName", session.packageName)
        put("projectRoot", session.projectRoot)
        put("consoleUrl", "not-started")
        put("session", McpProtocol.json.encodeToJsonElement(FeedbackSession.serializer(), session))
    })
}
"pointpatch_capture_screen" -> bridgeToolResult {
    val session = feedbackService.currentSession()
    val screen = feedbackService.captureScreen(session.sessionId)
    jsonToolResult(buildJsonObject {
        put("sessionId", session.sessionId)
        put("screen", McpProtocol.json.encodeToJsonElement(CapturedScreen.serializer(), screen))
    })
}
"pointpatch_list_feedback" -> bridgeToolResult {
    val session = feedbackService.currentSession()
    jsonToolResult(buildJsonObject {
        put("sessionId", session.sessionId)
        put("packageName", session.packageName)
        put("status", session.status.name.lowercase())
        put("screensCount", session.screens.size)
        put("itemsCount", session.items.size)
        put("items", buildJsonArray {
            session.items.forEach { item ->
                add(buildJsonObject {
                    put("itemId", item.itemId)
                    put("screenId", item.screenId)
                    put("status", item.status.name.lowercase())
                    put("comment", item.comment)
                })
            }
        })
    })
}
"pointpatch_read_feedback" -> bridgeToolResult {
    val session = feedbackService.currentSession()
    toolResult(
        content = listOf(
            textContent(FeedbackQueueFormatter.toJson(session), "application/json"),
            textContent(FeedbackQueueFormatter.toMarkdown(session), "text/markdown"),
        ),
    )
}
"pointpatch_resolve_feedback" -> bridgeToolResult {
    val session = feedbackService.currentSession()
    val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
        ?: throw PointPatchToolException("pointpatch_resolve_feedback requires itemId")
    val status = arguments.stringParam("status")?.toFeedbackItemStatus()
        ?: throw PointPatchToolException("pointpatch_resolve_feedback requires status")
    val summary = arguments.stringParam("summary")
    val item = feedbackService.resolveFeedback(session.sessionId, itemId, status, summary)
    jsonToolResult(McpProtocol.json.encodeToJsonElement(FeedbackItem.serializer(), item).jsonObject)
}
```

Add:

```kotlin
private fun String.toFeedbackItemStatus(): FeedbackItemStatus =
    when (this) {
        "resolved" -> FeedbackItemStatus.RESOLVED
        "needs_clarification" -> FeedbackItemStatus.NEEDS_CLARIFICATION
        "wont_fix" -> FeedbackItemStatus.WONT_FIX
        else -> throw PointPatchToolException("Unsupported feedback resolution status: $this")
    }
```

- [ ] **Step 4: Add tool definitions**

Add these `ToolDefinition` entries:

```kotlin
ToolDefinition(
    name = "pointpatch_open_feedback_console",
    description = "Open or return the local PointPatch feedback console for the current MCP session.",
    inputSchema = objectSchema(
        "packageName" to stringProperty("Android application id. If omitted, .pointpatch/project.json or server --package is used."),
    ),
)
ToolDefinition(
    name = "pointpatch_capture_screen",
    description = "Capture the current Android screen into the active PointPatch feedback session.",
    inputSchema = objectSchema(
        "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
    ),
)
ToolDefinition(
    name = "pointpatch_list_feedback",
    description = "List feedback queue summaries for the active PointPatch feedback session.",
    inputSchema = objectSchema(
        "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
    ),
)
ToolDefinition(
    name = "pointpatch_read_feedback",
    description = "Read the feedback queue as annotation JSON and Markdown.",
    inputSchema = objectSchema(
        "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        "itemId" to stringProperty("Optional feedback item id to focus the returned payload."),
    ),
)
ToolDefinition(
    name = "pointpatch_resolve_feedback",
    description = "Mark a feedback item as resolved, needing clarification, or not fixed.",
    inputSchema = objectSchema(
        "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        "itemId" to stringProperty("Feedback item id to update."),
        "status" to stringProperty("One of resolved, needs_clarification, or wont_fix."),
        "summary" to stringProperty("Agent summary shown in the console."),
        required = listOf("itemId", "status"),
    ),
)
```

- [ ] **Step 5: Run MCP tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.McpProtocolTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt
git commit -m "mcp: expose feedback queue tools"
```

## Task 8: Local Feedback Console Server

**Files:**
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt`
- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Create: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`

- [ ] **Step 1: Write server tests**

Create `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`:

```kotlin
package io.github.pointpatch.mcp.console

import io.github.pointpatch.mcp.session.FeedbackSessionService
import io.github.pointpatch.mcp.session.FeedbackSessionStore
import io.github.pointpatch.mcp.session.FakePointPatchBridge
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackConsoleServerTest {
    @Test
    fun servesIndexAndSessionJson() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val index = URL(server.url).readText()
            assertTrue(index.contains("PointPatch Feedback Console"))

            val session = URL("${server.url}/api/session").readText()
            assertTrue(session.contains("io.github.pointpatch.sample"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectsUnsupportedMethods() {
        val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            assertEquals(405, connection.responseCode)
        } finally {
            server.stop()
        }
    }
}
```

- [ ] **Step 2: Run server tests to verify they fail**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: compilation fails because console server files do not exist.

- [ ] **Step 3: Implement assets**

Create `FeedbackConsoleAssets.kt`:

```kotlin
package io.github.pointpatch.mcp.console

internal object FeedbackConsoleAssets {
    val indexHtml: String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>PointPatch Feedback Console</title>
          <style>
            body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: #1f2328; background: #f6f8fa; }
            header { height: 48px; display: flex; align-items: center; justify-content: space-between; padding: 0 16px; background: #ffffff; border-bottom: 1px solid #d0d7de; }
            main { display: grid; grid-template-columns: 260px 1fr 340px; gap: 12px; padding: 12px; height: calc(100vh - 73px); box-sizing: border-box; }
            section { background: #ffffff; border: 1px solid #d0d7de; border-radius: 8px; overflow: auto; }
            h2 { margin: 12px; font-size: 14px; }
            button { min-height: 34px; border: 1px solid #8c959f; border-radius: 6px; background: #ffffff; cursor: pointer; }
            button.primary { background: #0969da; color: #ffffff; border-color: #0969da; }
            .toolbar { display: flex; gap: 8px; padding: 12px; }
            .list { display: flex; flex-direction: column; gap: 8px; padding: 12px; }
            .item { border: 1px solid #d0d7de; border-radius: 6px; padding: 8px; background: #f6f8fa; }
            .snapshot { margin: 12px; min-height: 420px; border: 1px dashed #8c959f; border-radius: 8px; display: flex; align-items: center; justify-content: center; color: #57606a; }
            textarea { width: calc(100% - 24px); min-height: 100px; margin: 12px; box-sizing: border-box; }
          </style>
        </head>
        <body>
          <header>
            <strong>PointPatch Feedback Console</strong>
            <span id="status">Connecting...</span>
          </header>
          <main>
            <section>
              <h2>Screens</h2>
              <div class="toolbar"><button id="capture" class="primary">Capture current screen</button></div>
              <div id="screens" class="list"></div>
            </section>
            <section>
              <h2>Snapshot</h2>
              <div id="snapshot" class="snapshot">Capture a screen to begin</div>
            </section>
            <section>
              <h2>Queue</h2>
              <div id="items" class="list"></div>
              <textarea id="comment" aria-label="Feedback comment"></textarea>
              <div class="toolbar">
                <button id="add">Add area feedback</button>
                <button id="copy">Copy Markdown</button>
              </div>
            </section>
          </main>
          <script>
            async function refresh() {
              const response = await fetch('/api/session');
              const session = await response.json();
              document.getElementById('status').textContent = session.packageName + ' · ' + session.status;
              document.getElementById('screens').innerHTML = session.screens.map(s => '<div class="item">' + s.displayName + '<br>' + s.screenId + '</div>').join('');
              document.getElementById('items').innerHTML = session.items.map(i => '<div class="item">' + i.status + '<br>' + i.comment + '</div>').join('');
              const last = session.screens[session.screens.length - 1];
              document.getElementById('snapshot').textContent = last ? last.displayName : 'Capture a screen to begin';
            }
            document.getElementById('capture').onclick = async () => { await fetch('/api/capture', { method: 'POST' }); await refresh(); };
            document.getElementById('add').onclick = async () => {
              const comment = document.getElementById('comment').value;
              await fetch('/api/items', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ comment, bounds: { left: 10, top: 10, right: 100, bottom: 60 } }) });
              await refresh();
            };
            document.getElementById('copy').onclick = async () => {
              const response = await fetch('/api/export/markdown');
              await navigator.clipboard.writeText(await response.text());
            };
            refresh();
          </script>
        </body>
        </html>
    """.trimIndent()
}
```

- [ ] **Step 4: Implement server**

Create `FeedbackConsoleServer.kt`:

```kotlin
package io.github.pointpatch.mcp.console

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.session.FeedbackQueueFormatter
import io.github.pointpatch.mcp.session.FeedbackSession
import io.github.pointpatch.mcp.session.FeedbackSessionService
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class FeedbackConsoleServer(
    private val service: FeedbackSessionService,
    private val host: String = "127.0.0.1",
    private val port: Int = 0,
) {
    private var server: HttpServer? = null
    val url: String
        get() {
            val actual = server ?: error("Feedback console server is not running")
            return "http://localhost:${actual.address.port}"
        }

    fun start(): String {
        if (server != null) return url
        val created = HttpServer.create(InetSocketAddress(host, port), 0)
        created.createContext("/") { exchange ->
            when (exchange.requestURI.path) {
                "/" -> exchange.respondText(FeedbackConsoleAssets.indexHtml, "text/html")
                "/api/session" -> exchange.requireMethod("GET") {
                    exchange.respondJson(service.currentSession())
                }
                "/api/capture" -> exchange.requireMethod("POST") {
                    val session = service.currentSession()
                    val screen = runBlocking { service.captureScreen(session.sessionId) }
                    exchange.respondJson(screen)
                }
                "/api/items" -> exchange.requireMethod("POST") {
                    val request = Json.decodeFromString(AddItemRequest.serializer(), exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                    val session = service.currentSession()
                    val screenId = session.screens.lastOrNull()?.screenId
                        ?: error("Capture a screen before adding feedback")
                    val item = service.addAreaFeedback(
                        sessionId = session.sessionId,
                        screenId = screenId,
                        bounds = PointPatchRect(request.bounds.left, request.bounds.top, request.bounds.right, request.bounds.bottom),
                        comment = request.comment,
                    )
                    exchange.respondJson(item)
                }
                "/api/export/markdown" -> exchange.requireMethod("GET") {
                    exchange.respondText(FeedbackQueueFormatter.toMarkdown(service.currentSession()), "text/markdown")
                }
                else -> exchange.respondText("Not found", "text/plain", status = 404)
            }
        }
        created.start()
        server = created
        service.openSession(null)
        return url
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun HttpExchange.requireMethod(method: String, block: () -> Unit) {
        if (requestMethod != method) {
            respondText("Method not allowed", "text/plain", status = 405)
            return
        }
        block()
    }

    private fun HttpExchange.respondJson(value: FeedbackSession) =
        respondText(pointPatchJson.encodeToString(FeedbackSession.serializer(), value), "application/json")

    private inline fun <reified T> HttpExchange.respondJson(value: T) =
        respondText(pointPatchJson.encodeToString(value), "application/json")

    private fun HttpExchange.respondText(text: String, contentType: String, status: Int = 200) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        responseHeaders.add("content-type", "$contentType; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

@Serializable
private data class AddItemRequest(val comment: String, val bounds: BoundsRequest)

@Serializable
private data class BoundsRequest(val left: Float, val top: Float, val right: Float, val bottom: Float)
```

- [ ] **Step 5: Run console server tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: PASS.

- [ ] **Step 6: Wire `pointpatch_open_feedback_console` to return URL**

Update `PointPatchTools` to own a lazy console server:

```kotlin
private var consoleServer: FeedbackConsoleServer? = null

private fun openConsoleUrl(): String {
    val server = consoleServer ?: FeedbackConsoleServer(feedbackService).also { consoleServer = it }
    return server.start()
}
```

Change `pointpatch_open_feedback_console` to:

```kotlin
val url = openConsoleUrl()
```

and return `consoleUrl = url`.

- [ ] **Step 7: Run MCP and console tests**

Run:

```bash
./gradlew :pointpatch-mcp:test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt
git commit -m "mcp: serve feedback console"
```

## Task 9: MCP Console Mode And CLI Command

**Files:**
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/McpServer.kt`
- Create: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/commands/ConsoleCommand.kt`
- Modify: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/Main.kt`
- Create: `pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/commands/ConsoleCommandTest.kt`

- [ ] **Step 1: Add command tests**

Create `ConsoleCommandTest.kt`:

```kotlin
package io.github.pointpatch.cli.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleCommandTest {
    @Test
    fun buildsMcpConsoleCommand() {
        val executable = java.io.File("/tmp/pointpatch-mcp")
        val command = buildConsoleProcessCommand(
            executable = executable,
            packageName = "io.github.pointpatch.sample",
            projectDir = "/repo",
        )

        assertEquals(
            listOf(
                "/tmp/pointpatch-mcp",
                "--console",
                "--package",
                "io.github.pointpatch.sample",
                "--project-dir",
                "/repo",
            ),
            command,
        )
    }

    @Test
    fun buildsMcpConsoleCommandWithoutPackageOverride() {
        val command = buildConsoleProcessCommand(
            executable = java.io.File("/tmp/pointpatch-mcp"),
            packageName = null,
            projectDir = "/repo",
        )

        assertTrue(command.contains("--console"))
        assertTrue(command.contains("--project-dir"))
    }
}
```

- [ ] **Step 2: Run CLI command test to verify it fails**

Run:

```bash
./gradlew :pointpatch-cli:test --tests io.github.pointpatch.cli.commands.ConsoleCommandTest
```

Expected: compilation fails because `ConsoleCommand` helpers do not exist.

- [ ] **Step 3: Add MCP console mode**

In `McpServer.kt`, extend options:

```kotlin
private data class McpOptions(
    val packageName: String?,
    val projectDir: File,
    val consoleMode: Boolean,
)
```

Parse `--console`:

```kotlin
"--console" -> {
    consoleMode = true
    index += 1
}
```

In `main`, branch before stdio server:

```kotlin
if (options.consoleMode) {
    val bridge = CliPointPatchBridge(BridgeClient(projectRoot = options.projectDir))
    val tools = PointPatchTools(
        bridge = bridge,
        defaultPackageName = options.packageName,
        projectRoot = options.projectDir,
    )
    val result = runBlocking {
        tools.call("pointpatch_open_feedback_console", kotlinx.serialization.json.JsonObject(emptyMap()))
    }
    val text = result["content"]?.jsonArray
        ?.firstOrNull()?.jsonObject
        ?.get("text")?.jsonPrimitive?.content
        ?: error("Console tool did not return JSON content")
    System.out.println(text)
    Thread.currentThread().join()
    return
}
```

Import `jsonArray`, `jsonObject`, and `content`.

- [ ] **Step 4: Add CLI console command**

Create `ConsoleCommand.kt`:

```kotlin
package io.github.pointpatch.cli.commands

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import kotlin.system.exitProcess

class ConsoleCommand : CoreCliktCommand(name = "console") {
    private val packageName by option("--package", help = "Android application id")
    private val projectDir by option("--project-dir", help = "Project root containing .pointpatch/project.json").default(".")

    override fun run() {
        val executable = McpExecutableLocator.find()
            ?: throw com.github.ajalt.clikt.core.CliktError(
                "Could not find pointpatch-mcp executable. Run :pointpatch-mcp:installDist or add pointpatch-mcp to PATH.",
            )
        val command = buildConsoleProcessCommand(
            executable = executable,
            packageName = packageName,
            projectDir = File(projectDir).canonicalPath,
        )
        val exitCode = ProcessBuilder(command)
            .inheritIO()
            .start()
            .waitFor()
        exitProcess(exitCode)
    }
}

internal fun buildConsoleProcessCommand(
    executable: File,
    packageName: String?,
    projectDir: String,
): List<String> = buildList {
    add(executable.absolutePath)
    add("--console")
    packageName?.let {
        add("--package")
        add(it)
    }
    add("--project-dir")
    add(projectDir)
}
```

Register it in `Main.kt`:

```kotlin
ConsoleCommand(),
```

- [ ] **Step 5: Run CLI and MCP tests**

Run:

```bash
./gradlew :pointpatch-cli:test :pointpatch-mcp:test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/McpServer.kt pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/commands/ConsoleCommand.kt pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/Main.kt pointpatch-cli/src/test/kotlin/io/github/pointpatch/cli/commands/ConsoleCommandTest.kt
git commit -m "cli: add feedback console command"
```

## Task 10: Compatibility Wrapper For Single Feedback

**Files:**
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Add compatibility behavior test**

Add to `McpProtocolTest`:

```kotlin
    @Test
    fun oldGetUiFeedbackStillReturnsAnnotationAndMarkdown() = runBlocking {
        val response = handle(
            """{"jsonrpc":"2.0","id":"feedback","method":"tools/call","params":{"name":"pointpatch_get_ui_feedback","arguments":{"timeoutMs":1500}}}""",
        )

        assertTrue(response.contains("application/json"))
        assertTrue(response.contains("text/markdown"))
    }
```

- [ ] **Step 2: Run compatibility test**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.McpProtocolTest.oldGetUiFeedbackStillReturnsAnnotationAndMarkdown
```

Expected: PASS with the existing path before refactoring.

- [ ] **Step 3: Add deprecation note in tool description**

Update the existing `pointpatch_get_ui_feedback` description:

```kotlin
description = "Compatibility wrapper for single-item PointPatch feedback capture. Prefer pointpatch_open_feedback_console plus feedback queue tools for new workflows.",
```

Keep the existing `startFeedbackCapture` implementation intact. Do not rewrite it to depend on the new web console in this task. This preserves current MCP client behavior while the new queue tools ship.

- [ ] **Step 4: Run MCP tests**

Run:

```bash
./gradlew :pointpatch-mcp:test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/McpProtocolTest.kt
git commit -m "mcp: keep single feedback compatibility"
```

## Task 11: Documentation Updates

**Files:**
- Modify: `README.md`
- Modify: `docs/mcp.md`
- Modify: `docs/output-schema.md`
- Modify: `docs/troubleshooting.md`
- Modify: `docs/privacy.md`

- [ ] **Step 1: Update README**

In `README.md`, replace the MCP summary paragraph with:

```markdown
MCP is the primary agent workflow for the feedback console. `pointpatch mcp` runs as a stdio JSON-RPC server and can open a local web console where you review Android screen snapshots, add feedback with a desktop keyboard, and let the agent read the queue. `pointpatch console` opens the same console without requiring an MCP client.
```

Add usage:

```markdown
```bash
pointpatch setup --package <applicationId>
pointpatch console --package <applicationId>
```
```

- [ ] **Step 2: Update MCP docs**

In `docs/mcp.md`, add a "Feedback Console" section:

```markdown
## Feedback Console

The feedback console is an MCP-owned local web UI. The MCP server owns the session queue and exposes it to agents through queue tools. The browser UI is the human review surface.

Typical flow:

1. Call `pointpatch_open_feedback_console`.
2. Capture one or more screens in the console.
3. Add feedback items on desktop snapshots.
4. Call `pointpatch_list_feedback`.
5. Call `pointpatch_read_feedback`.
6. Make code changes and call `pointpatch_resolve_feedback`.

The CLI command `pointpatch console --package <applicationId>` opens the same local console for copy/export workflows.
```

Add the five new tools to the tools list with one-sentence descriptions.

- [ ] **Step 3: Update output schema**

In `docs/output-schema.md`, add top-level sections for:

```markdown
## Feedback Session Schema
## Captured Screen Schema
## Feedback Item Schema
```

Use the field names from `FeedbackSessionModels.kt`: `sessionId`, `screens`, `items`, `target`, `status`, `agentSummary`.

- [ ] **Step 4: Update troubleshooting**

In `docs/troubleshooting.md`, add entries for:

```markdown
### NO_DEVICE
Connect a device or start an emulator, then run `adb devices`.

### SIDEKICK_UNREACHABLE
Install and launch a debuggable build with PointPatch sidekick enabled, then retry `pointpatch status`.

### MCP_SESSION_CLOSED
Reopen the feedback console from the agent or run `pointpatch console --package <applicationId>`.

### SCREEN_CAPTURE_FAILED
The console may still show semantics without a screenshot. Retry Capture current screen after the app finishes drawing.
```

- [ ] **Step 5: Update privacy docs**

In `docs/privacy.md`, add:

```markdown
The feedback console is served from localhost by the desktop MCP process. The Android app does not host the console and does not need network permissions. Console screenshots are local debug artifacts under `.pointpatch/artifacts/`.
```

- [ ] **Step 6: Run docs grep check**

Run:

```bash
rg -n "pointpatch_get_ui_feedback|pointpatch console|pointpatch_open_feedback_console|feedback queue" README.md docs
```

Expected: output includes the new console flow and still mentions `pointpatch_get_ui_feedback` only as a compatibility or legacy single-feedback tool.

- [ ] **Step 7: Commit**

```bash
git add README.md docs/mcp.md docs/output-schema.md docs/troubleshooting.md docs/privacy.md
git commit -m "docs: document feedback console workflow"
```

## Task 12: End-To-End Verification

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
./gradlew :pointpatch-compose-sidekick:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Build distributions**

Run:

```bash
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
```

Expected: PASS and install scripts exist under:

```text
pointpatch-cli/build/install/pointpatch/bin/pointpatch
pointpatch-mcp/build/install/pointpatch-mcp/bin/pointpatch-mcp
```

- [ ] **Step 4: Run no-device console smoke**

Run:

```bash
pointpatch-cli/build/install/pointpatch/bin/pointpatch console --package io.github.pointpatch.sample
```

Expected without a connected device: command starts the console server and the console shows a clear no-device or sidekick-unreachable state when capture is attempted. Stop the process with Ctrl-C after confirming the URL prints.

- [ ] **Step 5: Run connected manual smoke when a device or emulator is available**

Run:

```bash
./gradlew :app:installDebug
pointpatch-cli/build/install/pointpatch/bin/pointpatch run --package io.github.pointpatch.sample
pointpatch-cli/build/install/pointpatch/bin/pointpatch console --package io.github.pointpatch.sample
```

Expected:

- browser opens or prints a localhost URL
- Capture current screen adds the first screen
- adding feedback creates a queue item
- navigating the app manually and capturing again adds a second screen
- `pointpatch_list_feedback` through MCP returns the same queue

- [ ] **Step 6: Commit verification-only notes if docs needed adjustment**

If verification required a docs correction, commit it:

```bash
git add README.md docs/mcp.md docs/troubleshooting.md docs/privacy.md docs/output-schema.md
git commit -m "docs: clarify feedback console verification"
```

If no files changed, do not create an empty commit.

## Self-Review Checklist

- Spec coverage: this plan covers MCP-first ownership, desktop web console, multi-screen queue, copy/export, MCP list/read/resolve, CLI `pointpatch console`, sidekick capture, privacy, error states, tests, and docs.
- Wording scan: the plan avoids incomplete-marker terms, unbounded error-handling instructions, and references to undefined future work.
- Type consistency: model names use `FeedbackSession`, `CapturedScreen`, `FeedbackItem`, `FeedbackTarget`, `FeedbackItemStatus`, and `FeedbackSessionStatus` consistently across tasks.
- Scope control: remote control and arbitrary Android input are excluded from tasks.
