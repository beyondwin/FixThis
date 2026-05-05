# Feedback Console Live Preview And Batched Items Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the feedback console default to app navigation, enter target selection only while adding feedback, save one evidence snapshot for multiple feedback items on the same frozen screen, and export concise agent handoff content with actionable Compose source hints.

**Architecture:** Split transient live preview from persisted feedback evidence. The console polls a screenshot+semantics preview into a temporary in-memory/cache record that is not part of session history. `Add` freezes the latest preview record for selection only, and `Save` promotes that frozen preview once into one persisted `CapturedScreen` plus all pending feedback items created against it.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, Java HTTP server, Android ADB bridge client, local browser HTML/CSS/JavaScript assets, Compose semantics/source index matching, Gradle tests.

---

## Related Documents

- Detailed design: `docs/superpowers/specs/2026-05-05-feedback-console-live-preview-batched-items-detailed-design.md`
- Current selection handoff design: `docs/superpowers/specs/2026-05-05-feedback-console-selection-handoff-design.md`
- Current selection handoff plan: `docs/superpowers/plans/2026-05-05-feedback-console-selection-handoff-implementation.md`
- Prior V2 design: `docs/superpowers/specs/2026-05-04-feedback-workspace-navigation-v2-design.md`
- Prior V2 plan: `docs/superpowers/plans/2026-05-04-feedback-workspace-navigation-v2-implementation.md`

## Product Decisions

1. The console default mode is navigation. Users should not switch between `Select` and `Navigate` during normal app operation.
2. The `Add` button freezes the latest live preview for the current screen. It must not persist a session screen and should not perform a second stored capture.
3. The `Save` button persists exactly one evidence snapshot by promoting the frozen preview and all pending target/comment pairs from that flow.
4. If the user later starts `Add` again on the same app screen, treat it as a new feedback task and save a new evidence snapshot when they save.
5. Live preview is not session history. It must not append to `session.screens`.
6. The visible `Screens` list should be removed or reduced to an internal evidence detail; users work with sessions and feedback items.
7. Agent handoff Markdown should be optimized for fixing code, not for exposing storage internals.
8. Pending feedback must be reviewable before save. When multiple pending items exist on one frozen preview, the screenshot overlay shows numbered markers/boxes that match the pending item list.
9. Saved feedback must remain reviewable after save. A saved item or saved snapshot group must let the user see the persisted preview screenshot with the saved numbered markers and the comments that were saved from that preview.
10. Agent handoff actions are session-level actions, not comment-composer actions. Keep them in the top bar, away from per-item comment controls.

## Live Preview Cadence

Default live preview behavior:

- Poll every `2_000ms` by default while a device is selected, no add-items flow is active, and the browser tab is visible.
- Let the user choose the preview interval from `Manual`, `1s`, `2s`, and `5s`. Persist this browser-local preference in `localStorage`; do not add it to feedback session persistence.
- Use `1_000ms` as the minimum automatic interval. Do not allow sub-second polling.
- Pause polling while the user is selecting/commenting in the add-items flow so the target image, semantics, and click/drag coordinates do not move under the user.
- Pause polling any time a frozen preview is active, even if no pending item has been queued yet.
- Pause polling when `document.hidden` is true.
- Keep a manual `Refresh` button that fetches once immediately.
- Keep only the latest preview records in transient console state or an overwriteable temp/cache location.
- Do not store live preview responses as `FeedbackSession.screens`.
- Do not copy preview artifacts into `.pointpatch/feedback-sessions/<session>/artifacts/screens/` until `Save`.

This preview does not consume AI tokens by itself. Tokens are only consumed when an AI agent reads exported Markdown/JSON or receives screenshots/semantics as model input.

## Pending And Saved Item Review

During `Add`, the right pane shows a `Pending Items` list for the frozen preview. Each pending row includes:

- number: `#1`, `#2`, `#3`
- first line of the comment
- target label such as `Email address`, `Button`, or `Visual area`
- actions: `Focus` and `Delete`

The frozen screenshot overlay must render the same number for each pending item:

- node targets render the node bounds with a numbered badge at the top-left corner
- area targets render the selected area bounds with a numbered badge
- clicking a pending row focuses/highlights the matching overlay
- deleting a pending row renumbers the remaining pending items so overlay numbers and list numbers always match
- editing pending items is intentionally not supported; if a pending item is wrong, delete only that item and add a new one on the same frozen preview

After `Save`, the saved draft queue should expose the evidence snapshot as a compact group. The group label can be:

```text
MainActivity | 3 items | screenshot attached
```

Expanding the group shows the persisted screenshot with saved numbered overlays and the saved comments below it. This is the user's confirmation that the saved preview and comments match what will be handed to the agent.

## File Map

Create:

- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsolePreviewModels.kt`: request/response models for transient preview snapshots and pending item save requests.

Modify:

- `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`: add a preview-friendly screenshot capture path if needed by server APIs.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`: expose preview and source-index bridge operations needed by the console service.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt`: add an atomic `addScreenWithItems` mutation so one saved snapshot creates multiple items in a single persisted session update.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`: own transient live preview state, promote one frozen preview into persisted evidence on save, compute source candidates, and collect node evidence for node and area targets.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt`: compact agent-facing Markdown around requests, target evidence, and likely source files.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt`: add preview, batched save, and preview image routes.
- `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`: replace persistent Select/Navigate toggles with navigation-default live preview and add-items flow UI.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt`: fake preview payloads with semantics and source-index availability.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`: atomic batched screen/item persistence coverage.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`: transient preview, save-time promotion, source candidates, and area evidence coverage.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt`: compact handoff output coverage.
- `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`: console API and HTML behavior smoke coverage.
- `README.md`
- `docs/mcp.md`
- `docs/output-schema.md`
- `docs/troubleshooting.md`

## Task 0: Baseline Verification

**Files:**

- No source edits.

- [x] **Step 1: Check branch, status, HEAD, and repo-local instructions**

Run:

```bash
git status --short --branch
git branch --show-current
git rev-parse HEAD
rg --files -g 'AGENTS.md' -g 'CLAUDE.md' -g '*agent*guide*' -g '*instructions*'
```

Expected: branch is understood, HEAD is recorded, and only known unrelated untracked docs are left untouched. If instruction files are listed, read them before Task 1.

- [x] **Step 2: Run targeted baseline tests**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Record checkpoint**

Record:

```text
HANDOFF CHECKPOINT
Task 0 Baseline Verification
Completed: branch/status/instructions and targeted baseline tests
Changed files: none
Contracts: no behavior changes yet
Review issues: none
Verification: targeted :pointpatch-mcp:test command PASS
Risks: none
Next action: Task 1 failing persistence tests
Worktree/branch: current workspace and branch
Session-owned processes: list any console process started by this session
```

## Task 1: Atomic Evidence Snapshot With Multiple Items

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt`

- [x] **Step 1: Write failing store test for one screen and multiple items**

Add this test to `FeedbackSessionStoreTest`:

```kotlin
@Test
fun addScreenWithItemsPersistsOneScreenAndMultipleDraftItemsAtomically() {
    val store = FeedbackSessionStore(
        clock = sequenceClock(1_000L, 2_000L),
        idGenerator = sequenceIds("session-1", "screen-1", "item-1", "item-2"),
    )
    val session = store.openSession("io.github.pointpatch.sample", "/repo")
    val screen = CapturedScreen(
        screenId = "pending",
        capturedAtEpochMillis = 0L,
        activityName = "io.github.pointpatch.sample.MainActivity",
        displayName = "MainActivity",
        screenshot = FeedbackScreenshot(width = 720, height = 1600),
    )
    val first = FeedbackItem(
        itemId = "pending",
        screenId = "pending",
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        target = FeedbackTarget.Area(PointPatchRect(10f, 20f, 110f, 80f)),
        comment = "Change headline copy",
    )
    val second = FeedbackItem(
        itemId = "pending",
        screenId = "pending",
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        target = FeedbackTarget.Area(PointPatchRect(120f, 200f, 260f, 280f)),
        comment = "Add more left margin",
    )

    val updated = store.addScreenWithItems(session.sessionId, screen, listOf(first, second))

    assertEquals(1, updated.screens.size)
    assertEquals("screen-1", updated.screens.single().screenId)
    assertEquals(2, updated.items.size)
    assertEquals(listOf("screen-1", "screen-1"), updated.items.map { it.screenId })
    assertEquals(listOf(1, 2), updated.items.map { it.sequenceNumber })
    assertEquals(listOf("item-1", "item-2"), updated.items.map { it.itemId })
}
```

If the test file does not already have `sequenceClock` or `sequenceIds`, add local helpers:

```kotlin
private fun sequenceClock(vararg values: Long): () -> Long {
    val queue = ArrayDeque(values.toList())
    return { queue.removeFirstOrNull() ?: values.last() }
}

private fun sequenceIds(vararg values: String): () -> String {
    val queue = ArrayDeque(values.toList())
    return { queue.removeFirstOrNull() ?: error("No more ids configured") }
}
```

- [x] **Step 2: Run test and verify RED**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest
```

Expected: FAIL because `addScreenWithItems` does not exist.

- [x] **Step 3: Implement atomic store mutation**

Add this method to `FeedbackSessionStore`:

```kotlin
fun addScreenWithItems(sessionId: String, screen: CapturedScreen, items: List<FeedbackItem>): FeedbackSession =
    synchronized(lock) {
        require(items.isNotEmpty()) { "At least one feedback item is required" }
        val session = getSessionLocked(sessionId)
        val now = clock()
        val captured = screen.copy(
            screenId = if (screen.screenId == "pending") idGenerator() else screen.screenId,
            capturedAtEpochMillis = now,
        )
        val firstSequence = nextItemSequenceNumber(session)
        val createdItems = items.mapIndexed { index, item ->
            item.copy(
                itemId = if (item.itemId == "pending") idGenerator() else item.itemId,
                screenId = captured.screenId,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                sequenceNumber = item.sequenceNumber ?: firstSequence + index,
                delivery = FeedbackDelivery.DRAFT,
            )
        }
        val updated = session.copy(
            screens = session.screens + captured,
            items = session.items + createdItems,
            updatedAtEpochMillis = now,
        )
        commitSessionMutation(session, updated)
    }
```

- [x] **Step 4: Run store tests and verify GREEN**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionStoreTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 5: Commit Task 1**

Run:

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStore.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "feat: persist batched feedback items with one snapshot"
```

## Task 2: Transient Preview Promotion Service

**Files:**

- Create: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsolePreviewModels.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`

- [x] **Step 1: Add preview model file**

Create `FeedbackConsolePreviewModels.kt`:

```kotlin
package io.github.pointpatch.mcp.console

import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.mcp.session.CapturedScreen
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackPreviewSnapshot(
    val previewId: String,
    val screen: CapturedScreen,
)

@Serializable
data class SavePreviewFeedbackItemsRequest(
    val previewId: String,
    val items: List<PendingDraftFeedbackItem>,
)

@Serializable
data class PendingDraftFeedbackItem(
    val targetType: FeedbackTargetType,
    val bounds: PointPatchRect,
    val nodeUid: String? = null,
    val comment: String,
)
```

- [x] **Step 2: Write failing service test for preview promotion**

Add this test to `FeedbackSessionServiceTest`:

```kotlin
@Test
fun savingFrozenPreviewPersistsOneScreenForMultipleItems() = runBlocking {
    val bridge = FakePointPatchBridge.withScreen(
        selectedNode = pointPatchNode(
            uid = "email-label",
            text = listOf("Email address"),
            bounds = PointPatchRect(28f, 77f, 692f, 186f),
        ),
    )
    val store = FeedbackSessionStore(
        clock = sequenceClock(1_000L, 2_000L),
        idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1", "item-2"),
    )
    val service = FeedbackSessionService(bridge = bridge, store = store, projectRoot = "/repo")
    val session = service.openSession("io.github.pointpatch.sample", newSession = true)

    val preview = service.capturePreview(session.sessionId)
    assertTrue(store.getSession(session.sessionId).screens.isEmpty())

    val updated = service.savePreviewFeedbackItems(
        sessionId = session.sessionId,
        previewId = preview.previewId,
        items = listOf(
            PendingDraftFeedbackItem(
                targetType = FeedbackTargetType.NODE,
                nodeUid = "email-label",
                bounds = PointPatchRect(28f, 77f, 692f, 186f),
                comment = "Rename this label",
            ),
            PendingDraftFeedbackItem(
                targetType = FeedbackTargetType.AREA,
                bounds = PointPatchRect(112f, 426f, 351f, 588f),
                comment = "Change this visual area",
            ),
        ),
    )

    assertEquals(1, updated.screens.size)
    assertEquals(2, updated.items.size)
    assertEquals(listOf("screen-1", "screen-1"), updated.items.map { it.screenId })
    assertTrue(updated.items.first().selectedNode?.text.orEmpty().contains("Email address"))
    assertTrue(updated.items[1].nearbyNodes.isNotEmpty())
}
```

- [x] **Step 3: Run service test and verify RED**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
```

Expected: FAIL because preview promotion APIs do not exist.

- [x] **Step 4: Implement transient preview state**

Add a preview map and APIs to `FeedbackSessionService`:

```kotlin
private val previewSnapshots = linkedMapOf<String, FeedbackPreviewSnapshot>()

suspend fun capturePreview(sessionId: String): FeedbackPreviewSnapshot {
    val session = store.getSession(sessionId)
    val previewId = store.nextId()
    val screenId = store.nextId()
    val artifactDirectory = File(session.projectRoot, ".pointpatch/preview-cache/${session.sessionId}/$previewId")
    val payload = bridge.captureScreenSnapshot(
        packageName = session.packageName,
        sessionId = session.sessionId,
        screenId = screenId,
        destinationDirectory = artifactDirectory,
    )
    val screen = payload.toCapturedScreen(
        screenId = screenId,
        fallbackDisplayName = "Draft screen",
    )
    val preview = FeedbackPreviewSnapshot(previewId = previewId, screen = screen)
    synchronized(sessionLock) {
        previewSnapshots[previewId] = preview
        while (previewSnapshots.size > 3) {
            previewSnapshots.remove(previewSnapshots.keys.first())
        }
    }
    return preview
}
```

Extract the existing `captureScreen` payload mapping into a private `JsonObject.toCapturedScreen(screenId, fallbackDisplayName)` helper and reuse it from both `captureScreen` and `capturePreview`.

- [x] **Step 5: Implement batched save by promoting a frozen preview**

Add:

```kotlin
fun savePreviewFeedbackItems(
    sessionId: String,
    previewId: String,
    items: List<PendingDraftFeedbackItem>,
): FeedbackSession {
    require(items.isNotEmpty()) { "At least one feedback item is required" }
    val preview = synchronized(sessionLock) {
        previewSnapshots.remove(previewId)
    } ?: throw FeedbackSessionException("PREVIEW_NOT_FOUND: Unknown preview: $previewId")
    val persistedScreen = promotePreviewArtifacts(sessionId, preview.screen)
    val feedbackItems = items.map { pending ->
        require(pending.comment.isNotBlank()) { "Feedback comment must not be blank" }
        buildFeedbackItemForDraft(persistedScreen, pending)
    }
    return store.addScreenWithItems(sessionId, persistedScreen, feedbackItems)
}
```

`promotePreviewArtifacts(sessionId, screen)` copies or moves the frozen preview screenshot from `.pointpatch/preview-cache/...` into `.pointpatch/feedback-sessions/<session>/artifacts/screens/<screenId>/` exactly once. It must not issue a new ADB capture during save; the saved evidence must match the frozen image the user selected.

Implement `buildFeedbackItemForDraft(screen, pending)` so:

- `NODE` requires `nodeUid` and stores `selectedNode`.
- `AREA` stores `FeedbackTarget.Area`.
- `AREA` also stores meaningful overlapping nodes first, then nearby nodes.
- both paths compute `sourceCandidates` from selected/nearby evidence when a source index is available.

- [x] **Step 6: Run service tests and verify GREEN**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 7: Commit Task 2**

Run:

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsolePreviewModels.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FakePointPatchBridge.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt
git commit -m "feat: promote frozen previews into batched feedback"
```

## Task 3: Preview And Batched Save Console APIs

**Files:**

- Modify: `pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Write failing API tests**

Add tests to `FeedbackConsoleServerTest`:

```kotlin
@Test
fun previewRouteDoesNotAppendSessionScreens() {
    server.use { console ->
        val before = URL("${console.url}/api/session").readText()

        val preview = URL("${console.url}/api/preview").readText()
        val after = URL("${console.url}/api/session").readText()

        assertTrue(preview.contains(""""screen""""))
        assertTrue(before.contains(""""screens":[]"""))
        assertTrue(after.contains(""""screens":[]"""))
    }
}

@Test
fun savingDraftItemsAppendsOneScreenAndTwoItems() {
    server.use { console ->
        val preview = URL("${console.url}/api/preview").readText()
        val previewId = json.parseToJsonElement(preview).jsonObject.getValue("previewId").jsonPrimitive.content

        val session = postJson(
            "${console.url}/api/items/batch",
            """
            {
              "previewId": "$previewId",
              "items": [
                {
                  "targetType": "area",
                  "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                  "comment": "Change headline"
                },
                {
                  "targetType": "area",
                  "bounds": {"left":120.0,"top":200.0,"right":260.0,"bottom":280.0},
                  "comment": "Add margin"
                }
              ]
            }
            """.trimIndent(),
        )

        assertTrue(session.contains(""""screens":[""").not())
        assertTrue(session.contains("Change headline"))
        assertTrue(session.contains("Add margin"))
        assertEquals(1, Regex(""""screenId"""").findAll(session).count())
    }
}
```

- [x] **Step 2: Run API tests and verify RED**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: FAIL because preview and batched save routes do not exist.

- [x] **Step 3: Add routes**

Add server routes:

```kotlin
"/api/preview" -> exchange.requireMethod("GET") {
    val session = service.currentSession()
    exchange.sendJson(200, runBlocking { service.capturePreview(session.sessionId) })
}
"/api/items/batch" -> exchange.requireMethod("POST") {
    val request = exchange.decodeSavePreviewFeedbackItemsBody()
    exchange.sendJson(
        200,
        service.savePreviewFeedbackItems(
            sessionId = service.currentSession().sessionId,
            previewId = request.previewId,
            items = request.items,
        ),
    )
}
```

Add `decodeSavePreviewFeedbackItemsBody()` using `pointPatchJson.decodeFromString(SavePreviewFeedbackItemsRequest.serializer(), body)`.

- [x] **Step 4: Add screenshot serving for preview**

Serve preview screenshots through a console-owned route:

```text
GET /api/preview/screenshot/full
```

Only serve PNG files under PointPatch-owned preview cache or persisted artifact directories.

- [x] **Step 5: Run API tests and verify GREEN**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 6: Commit Task 3**

Run:

```bash
git add pointpatch-cli/src/main/kotlin/io/github/pointpatch/cli/BridgeClient.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/tools/PointPatchTools.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServer.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat: add live preview and batched save console APIs"
```

## Task 4: Console UX Redesign

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Write failing HTML behavior smoke tests**

Add assertions to `FeedbackConsoleServerTest`:

```kotlin
@Test
fun consoleUsesNavigationDefaultAddItemsFlowAndSimpleLabels() {
    val html = FeedbackConsoleAssets.indexHtml

    assertTrue(html.contains(">Add<"))
    assertTrue(html.contains(">Save<"))
    assertTrue(html.contains(">Refresh<"))
    assertTrue(html.contains(">Copy<"))
    assertTrue(html.contains(">Send<"))
    assertTrue(html.contains("setInterval"))
    assertTrue(html.contains("document.hidden"))
    assertTrue(html.contains("previewIntervalSelect"))
    assertTrue(html.contains("PreviewIntervalStorageKey"))
    assertTrue(html.contains("Math.max(1000"))
    assertTrue(html.contains("Pending Items"))
    assertTrue(html.contains("renderNumberedFeedbackOverlay"))
    assertTrue(html.contains("focusPendingFeedbackItem"))
    assertTrue(html.contains("deletePendingFeedbackItem"))
    assertTrue(html.contains("renderSavedEvidenceGroups"))
    assertFalse(html.contains("modeSelect"))
    assertFalse(html.contains("modeNavigate"))
    assertFalse(html.contains("Clear Comment"))
}
```

Also add JS helper assertions:

```kotlin
assertTrue(html.contains("const DefaultLivePreviewIntervalMs = 2000"))
assertTrue(html.contains("const MinLivePreviewIntervalMs = 1000"))
assertTrue(html.contains("startAddItemsFlow"))
assertTrue(html.contains("queuePendingFeedbackItem"))
assertTrue(html.contains("savePendingFeedbackItems"))
```

- [x] **Step 2: Run console asset tests and verify RED**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: FAIL because the old mode UI is still present.

- [x] **Step 3: Replace interaction model in HTML**

Change the visible controls:

```text
Top bar:
  Device picker | Preview interval | Refresh | Add | Save | Copy | Send | New | Close

Center:
  Live preview when idle
  Frozen feedback snapshot while adding items
  Back/Swipe controls remain available only while idle

Right pane:
  Pending item composer while adding
  Pending items for this frozen snapshot
  Saved evidence groups with screenshot/comment detail
  Draft feedback already saved in session
  No agent handoff buttons in the comment/composer pane
```

Remove persistent `Select` and `Navigate` buttons. The default screenshot click behavior is navigate/tap while idle. During add-items flow, click selects a node and drag selects an area.

Use short top-bar button labels:

- `Refresh`: refreshes live preview once.
- `Add`: starts add-items flow.
- `Save`: persists pending items from the frozen preview.
- `Copy`: copies agent context Markdown.
- `Send`: creates the persisted handoff batch for the agent.
- `New`: starts a new feedback session.
- `Close`: closes the active feedback session.

Place `Copy` and `Send` next to each other in the top bar. Do not place `Send` inside the comment composer because sending is a session/draft action.

- [x] **Step 4: Implement live preview polling**

Add JS constants and lifecycle:

```javascript
const DefaultLivePreviewIntervalMs = 2000;
const MinLivePreviewIntervalMs = 1000;
const PreviewIntervalStorageKey = 'pointpatch.previewIntervalMs';
let livePreviewTimer = null;
let addItemsFlow = null;

function configuredPreviewIntervalMs() {
  const rawValue = previewIntervalSelect.value;
  if (rawValue === 'manual') return null;
  const parsed = Number(rawValue || localStorage.getItem(PreviewIntervalStorageKey) || DefaultLivePreviewIntervalMs);
  return Math.max(MinLivePreviewIntervalMs, parsed);
}

function shouldPollPreview() {
  return !document.hidden && !addItemsFlow && Boolean(state.selectedDeviceSerial);
}

function startLivePreviewPolling() {
  stopLivePreviewPolling();
  const intervalMs = configuredPreviewIntervalMs();
  if (!intervalMs) return;
  livePreviewTimer = setInterval(() => {
    if (shouldPollPreview()) refreshPreview().catch(showError);
  }, intervalMs);
}

function stopLivePreviewPolling() {
  if (livePreviewTimer) clearInterval(livePreviewTimer);
  livePreviewTimer = null;
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && shouldPollPreview()) refreshPreview().catch(showError);
});

previewIntervalSelect.addEventListener('change', () => {
  localStorage.setItem(PreviewIntervalStorageKey, previewIntervalSelect.value);
  startLivePreviewPolling();
});
```

- [x] **Step 5: Implement add-items flow**

Add JS functions:

```javascript
async function startAddItemsFlow() {
  error.textContent = '';
  if (!state.preview) {
    state.preview = await requestJson('/api/preview');
  }
  addItemsFlow = {
    previewId: state.preview.previewId,
    screen: state.preview.screen,
  };
  pendingFeedbackItems = [];
  currentSelection = null;
  stopLivePreviewPolling();
  render();
}

function queuePendingFeedbackItem() {
  const feedbackComment = comment.value.trim();
  if (!addItemsFlow) throw new Error('Click Add before selecting feedback.');
  if (!currentSelection) throw new Error('Select a component or area first.');
  if (!feedbackComment) throw new Error('Enter a comment before adding it to the pending list.');
  pendingFeedbackItems.push({
    targetType: currentSelection.targetType,
    nodeUid: currentSelection.nodeUid,
    bounds: currentSelection.bounds,
    comment: feedbackComment,
  });
  currentSelection = null;
  comment.value = '';
  renderSelectionOverlay();
  render();
}

function deletePendingFeedbackItem(index) {
  pendingFeedbackItems.splice(index, 1);
  render();
  renderSelectionOverlay();
}

function focusPendingFeedbackItem(index) {
  focusedPendingItemIndex = index;
  const item = pendingFeedbackItems[index];
  currentSelection = item ? {
    targetType: item.targetType,
    nodeUid: item.nodeUid,
    bounds: item.bounds,
    label: item.targetType === 'node' ? 'Selected component' : 'Custom area',
  } : null;
  renderSelectionOverlay();
}

async function savePendingFeedbackItems() {
  if (!addItemsFlow) return;
  if (!pendingFeedbackItems.length) throw new Error('Add at least one pending feedback item.');
  state.session = await requestJson('/api/items/batch', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      previewId: addItemsFlow.previewId,
      items: pendingFeedbackItems,
    }),
  });
  addItemsFlow = null;
  pendingFeedbackItems = [];
  currentSelection = null;
  comment.value = '';
  await refresh();
  startLivePreviewPolling();
}
```

Use button labels:

- `Add`: freezes the latest live preview without adding a session screen.
- `Add to Pending`: queues a target/comment on the frozen snapshot.
- `Save`: promotes that frozen preview into one persisted snapshot and all pending items.
- `Cancel`: discards the frozen preview reference and pending list without changing session history.

- [x] **Step 6: Add pending list and numbered overlay**

Render pending items while the add-items flow is active:

```javascript
function renderPendingItems() {
  pendingItems.innerHTML = pendingFeedbackItems.map((item, index) => `
    <div class="row pending-item-row ${'$'}{index === focusedPendingItemIndex ? 'active' : ''}">
      <strong>#${'$'}{index + 1} ${'$'}{escapeHtml(firstLine(item.comment))}</strong>
      <span>${'$'}{escapeHtml(pendingTargetLabel(item))}</span>
      <button type="button" data-focus-pending="${'$'}{index}">Focus</button>
      <button type="button" data-delete-pending="${'$'}{index}">Delete</button>
    </div>
  `).join('') || '<div class="row"><span>No pending feedback items.</span></div>';
}
```

Do not implement pending item editing. To correct a pending item, the user clicks `Delete` on that row and adds the corrected target/comment again. This keeps the temporary state model simple and prevents accidental edits to the wrong numbered marker.

Render numbered boxes on the frozen screenshot:

```javascript
function renderNumberedFeedbackOverlay(overlay, image) {
  pendingFeedbackItems.forEach((item, index) => {
    renderOverlayBox(overlay, image, item.bounds, `#${'$'}{index + 1}`, index === focusedPendingItemIndex);
  });
}
```

Call `renderNumberedFeedbackOverlay` from `renderSelectionOverlay` before rendering the current transient selection. This keeps saved pending targets visible while the user selects the next target.

- [x] **Step 7: Add saved evidence group detail**

Group saved draft items by `screenId` in the visible draft area:

```javascript
function savedEvidenceGroups() {
  const groups = new Map();
  (state.session?.items || [])
    .filter(item => item.delivery !== 'sent')
    .forEach(item => {
      const key = item.screenId;
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(item);
    });
  return Array.from(groups.entries()).map(([screenId, items]) => ({ screenId, items }));
}

function renderSavedEvidenceGroups() {
  draftItems.innerHTML = savedEvidenceGroups().map(group => {
    const screen = findScreen(group.screenId);
    return `
      <details class="evidence-group">
        <summary>${'$'}{escapeHtml(screen?.displayName || 'Saved evidence')} | ${'$'}{group.items.length} item${'$'}{group.items.length === 1 ? '' : 's'} | screenshot attached</summary>
        <div class="saved-evidence-preview" data-screen-id="${'$'}{escapeHtml(group.screenId)}"></div>
        ${'$'}{group.items.map((item, index) => `
          <div class="row">
            <strong>#${'$'}{index + 1} ${'$'}{escapeHtml(firstLine(item.comment))}</strong>
            <span>${'$'}{escapeHtml(targetLabel(item))} | ${'$'}{escapeHtml(sourceHintLabel(item))}</span>
          </div>
        `).join('')}
      </details>
    `;
  }).join('') || '<div class="row"><span>No draft feedback items.</span></div>';
}
```

The expanded group must show the persisted screenshot and numbered overlays using the saved item order within that screen group. It should not show screen id, item id, screenshot dimensions, or captured time in the default view.

- [x] **Step 8: Simplify visible session and item metadata**

Session rows should show only:

```text
Active | 2 draft | 1 sent | updated 06:52 PM
```

Do not show package name, session id, screen count, or batch id in ordinary rows. Keep package in the header only.

Draft item rows should show only:

```text
#2 Add left margin
Email address | Source hint available
```

Do not show item id, screen id, delivery, status, screenshot size, or captured time in ordinary rows. If source hint is missing, show `No source hint`.

- [x] **Step 9: Hide screen history**

Remove the left-pane `Screens` list from the primary UI. If evidence details are needed, show them inside an item detail panel as:

```text
Evidence: screenshot attached
Target: Email address
Source: sample/src/main/java/.../FormScreen.kt:37
```

- [x] **Step 10: Run console asset/API tests and verify GREEN**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.console.FeedbackConsoleServerTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 11: Commit Task 4**

Run:

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleAssets.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat: simplify console add-items workflow"
```

## Task 5: Source Hints And Compact Agent Handoff

**Files:**

- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt`
- Modify: `pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt`
- Modify: `pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt`

- [x] **Step 1: Write failing formatter test for compact source-hinted handoff**

Add:

```kotlin
@Test
fun markdownFocusesOnRequestTargetEvidenceAndLikelySource() {
    val selectedNode = PointPatchNode(
        uid = "email-label",
        composeNodeId = 42,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = PointPatchRect(28f, 77f, 692f, 186f),
        text = listOf("Email address"),
        testTag = "emailField",
    )
    val session = FeedbackSession(
        sessionId = "session-1",
        packageName = "io.github.pointpatch.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        screens = listOf(CapturedScreen("screen-1", 2L, activityName = "io.github.pointpatch.sample.MainActivity", displayName = "MainActivity")),
        items = listOf(
            FeedbackItem(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 2L,
                updatedAtEpochMillis = 2L,
                target = FeedbackTarget.Node("email-label", selectedNode.boundsInWindow),
                selectedNode = selectedNode,
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/io/github/pointpatch/sample/screens/FormScreen.kt",
                        line = 37,
                        score = 0.95,
                        matchedTerms = listOf("Email address", "emailField"),
                        matchReasons = listOf("selected text", "selected testTag"),
                        confidence = SelectionConfidence.HIGH,
                    ),
                ),
                comment = "Give this field 20 more px of left margin",
                sequenceNumber = 1,
            ),
        ),
    )

    val markdown = FeedbackQueueFormatter.toMarkdown(session)

    assertTrue(markdown.contains("# PointPatch Feedback Handoff"))
    assertTrue(markdown.contains("Request:"))
    assertTrue(markdown.contains("Give this field 20 more px of left margin"))
    assertTrue(markdown.contains("Likely Source:"))
    assertTrue(markdown.contains("FormScreen.kt:37"))
    assertTrue(markdown.contains("Text: `Email address`"))
    assertTrue(markdown.contains("Test Tag: `emailField`"))
    assertFalse(markdown.contains("Delivery:"))
    assertFalse(markdown.contains("Status:"))
    assertFalse(markdown.contains("Captured At:"))
    assertFalse(markdown.contains("Screenshot Size:"))
}
```

- [x] **Step 2: Run formatter test and verify RED**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest
```

Expected: FAIL because the current Markdown still includes repeated metadata.

- [x] **Step 3: Format handoff around actionable fields**

Change `FeedbackQueueFormatter.toMarkdown` to output:

```markdown
# PointPatch Feedback Handoff

- Package: `...`
- Feedback Items: `N`
- Screenshots: local debug artifacts available through PointPatch tooling

## Item 1

Request:
...

Target:
- Type: Compose semantics node
- Text: `...`
- Test Tag: `...`
- Bounds: `...`

Likely Source:
1. `path/File.kt:37` high confidence
   - matched: `Email address`, `emailField`
   - reasons: selected text, selected testTag
```

For visual area items, output:

```markdown
Target:
- Type: Visual area
- Bounds: `...`
- Nearby UI: `...`
- Note: area selection only; verify screenshot and source candidates.
```

Do not include raw session, screen, item, batch, screenshot file path, delivery, status, captured time, or screenshot dimensions in Markdown.

- [x] **Step 4: Compute source candidates for node and area targets**

In `FeedbackSessionService`, source evidence rules are:

- Node selection:
  - selected node is the anchor
  - nearby nodes come from the same root
  - `SourceMatcher.match(sourceIndex, selectedNode, nearbyNodes, activityName)` provides candidates
- Area selection:
  - overlapping meaningful nodes are primary evidence
  - if no node overlaps, use nearest meaningful nodes around the area center
  - source candidates use the best overlapping/nearest node as selected evidence and the remaining nodes as nearby evidence
  - mark area output as lower confidence in Markdown by including the area-selection note

- [x] **Step 5: Run service and formatter tests and verify GREEN**

Run:

```bash
./gradlew :pointpatch-mcp:test --tests io.github.pointpatch.mcp.session.FeedbackSessionServiceTest --tests io.github.pointpatch.mcp.session.FeedbackQueueFormatterTest
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 6: Commit Task 5**

Run:

```bash
git add pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionService.kt pointpatch-mcp/src/main/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatter.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackSessionServiceTest.kt pointpatch-mcp/src/test/kotlin/io/github/pointpatch/mcp/session/FeedbackQueueFormatterTest.kt
git commit -m "feat: export compact source-hinted feedback handoff"
```

## Task 6: Documentation Updates

**Files:**

- Modify: `README.md`
- Modify: `docs/mcp.md`
- Modify: `docs/output-schema.md`
- Modify: `docs/troubleshooting.md`

- [ ] **Step 1: Use `$kws-doc-prompt-review` before editing docs**

Read `/Users/kws/source/persnal/Archive/ai/skills/kws-skills/package/kws-doc-prompt-review/SKILL.md` and apply it narrowly to changed behavior:

- live preview does not persist session screens
- add-items flow saves one evidence snapshot for multiple items
- pending items are visible before save and numbered on the frozen preview
- saved evidence groups show the persisted screenshot and saved comments
- JSON remains complete; Markdown is compact and agent-facing
- default preview cadence is 2 seconds
- polling pauses while adding items and when the tab is hidden

- [ ] **Step 2: Update README usage**

Document the user flow:

```text
1. Select device.
2. Use the app normally from the console preview.
3. Click Add when ready to leave feedback on the current screen.
4. Select one or more UI targets or visual areas and add comments.
5. Review the numbered pending markers and pending comments.
6. Click Save once to store one evidence snapshot and all pending items.
7. Expand the saved evidence group to review the persisted screenshot and comments.
8. Click Send when ready.
```

- [ ] **Step 3: Update MCP docs and schema docs**

Clarify:

- `FeedbackSession.screens` contains persisted evidence snapshots, not every preview frame.
- `FeedbackItem.screenId` points to the evidence snapshot saved with the item batch.
- Multiple items can share one `screenId` when they were saved together from one frozen preview.
- Markdown handoff intentionally omits internal IDs and repeated storage metadata.
- JSON output preserves IDs and paths for tool contracts.

- [ ] **Step 4: Run docs verification**

Run:

```bash
git diff --check -- README.md docs/mcp.md docs/output-schema.md docs/troubleshooting.md
```

Expected: no output.

- [ ] **Step 5: Commit Task 6**

Run:

```bash
git add README.md docs/mcp.md docs/output-schema.md docs/troubleshooting.md
git commit -m "docs: explain live preview feedback workflow"
```

## Task 7: Final Verification And Connected Smoke

**Files:**

- No planned source edits unless verification finds a defect.

- [ ] **Step 1: Run full relevant test suite**

Run:

```bash
./gradlew :pointpatch-mcp:test :pointpatch-cli:test :pointpatch-compose-core:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build install distributions**

Run:

```bash
./gradlew :pointpatch-cli:installDist :pointpatch-mcp:installDist
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Check connected device state**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk PATH=/Users/kws/Library/Android/sdk/platform-tools:$PATH /Users/kws/Library/Android/sdk/platform-tools/adb devices -l
```

Expected: if `SM_G986N` is connected in `device` state, continue to Step 4. If no usable device is connected, record the raw output and skip only the connected-device smoke.

- [ ] **Step 4: Start console and smoke the workflow**

Run:

```bash
ANDROID_HOME=/Users/kws/Library/Android/sdk PATH=/Users/kws/Library/Android/sdk/platform-tools:$PATH pointpatch-cli/build/install/pointpatch/bin/pointpatch console --package io.github.pointpatch.sample
```

Record the console URL. Do not kill unrelated processes.

Smoke expectations:

- preview appears without adding rows to `session.screens`
- `Add` freezes the current screen
- pending items are visible before save and their overlay numbers match the pending list
- selecting two targets and saving once creates one persisted screen and two feedback items
- expanding the saved evidence group shows the persisted screenshot and saved comments
- same visible screen used in a later `Add` flow creates a separate persisted evidence snapshot
- exported Markdown is concise and includes likely source hints when available

- [ ] **Step 5: Run static diff check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 6: Final review**

Dispatch a final code quality review subagent over the full diff. Fix any actionable issue, rerun the relevant verification, and close the agent before finishing.

## Implementation Notes

- Do not broaden navigation beyond one-step `back`, `tap`, and `swipe`.
- Do not add arbitrary typing, scripted automation, cloud sync, network inspection, mocking, or external AI API calls.
- Do not show storage IDs in normal console rows or Markdown handoff.
- Do keep JSON/API contracts complete enough for tools to reference exact sessions, screens, items, batches, and local screenshot artifacts.
- Do not persist live preview frames as `CapturedScreen`.
- Do not let pending overlay numbers drift from pending item list order after delete.
- Do not hide saved feedback so deeply that users cannot confirm what screenshot/comments were persisted.
- Do not delete or rewrite unrelated untracked docs.

## Self-Review

- Spec coverage: this plan covers navigation-default UX, add-items-only selection, numbered pending markers, saved evidence review, one snapshot for multiple items, separate later snapshots, compact session/item rows, compact source-hinted handoff, docs, and connected smoke.
- Placeholder scan: no `TBD`, `TODO`, or undefined follow-up steps are present.
- Type consistency: preview APIs consistently use `FeedbackPreviewSnapshot`, `SavePreviewFeedbackItemsRequest`, and `PendingDraftFeedbackItem`; persistence uses `addScreenWithItems`; UI uses `Add`, `Add to Pending`, and `Save`.
