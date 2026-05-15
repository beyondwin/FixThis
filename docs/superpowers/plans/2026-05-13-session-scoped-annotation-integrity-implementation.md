# Session-Scoped Annotation Integrity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent annotation data from crossing sessions, previews, screens, or device contexts during create/delete/save/session-switch/device-screen-change workflows.

**Architecture:** Make annotation workflows session-scoped at the HTTP boundary first, then carry the same immutable context through console state, artifact URLs, pending recovery, and undo/redo. Saved overlay rendering becomes conservative: live preview shows no saved pins unless a saved annotation is focused, and durable server sequence counters replace array-index fallback numbering.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization, `com.sun.net.httpserver` console routes, vanilla console JS modules bundled by `scripts/build-console-assets.mjs`, Node built-in test runner, Playwright console smoke, JUnit/kotlin.test.

**Related spec:** [`../specs/2026-05-13-session-scoped-annotation-integrity-detailed-spec.md`](../specs/2026-05-13-session-scoped-annotation-integrity-detailed-spec.md)

---

## File Structure

Server request/route scope:

- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsolePreviewModels.kt` to add `sessionId` to `SaveSnapshotRequest` and `AgentHandoffRequest`.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt` to add `sessionId` to `AddAnnotationRequest`.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt` to resolve explicit request session IDs for add, batch-save, clear-draft, and handoff routes.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewRoutes.kt` to read preview screenshots from an explicit `sessionId` query parameter.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt` to read/delete screen artifacts from an explicit `sessionId` query parameter.
- Test in `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`.
- Test in `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleArtifactRoutesSessionScopeTest.kt`.

Console state/context:

- Modify `fixthis-mcp/src/main/console/state.js` for `sessionMutationGeneration` and a context-aware undo history state shape.
- Modify `fixthis-mcp/src/main/console/preview.js` for session-scoped preview/screen image URLs and live/saved preview selection.
- Modify `fixthis-mcp/src/main/console/annotations.js` for `addItemsFlow.context`, session-scoped save/update/delete/handoff calls, response generation guards, and pending reset behavior.
- Modify `fixthis-mcp/src/main/console/history.js` for the shared pending boundary guard before open/new/close/delete.
- Modify `fixthis-mcp/src/main/console/main.js` for recovery restore context and boundary helper integration.
- Modify `fixthis-mcp/src/main/console/prompt.js` for session-scoped handoff/copy prompt calls.
- Modify `fixthis-mcp/src/main/console/rendering.js` for saved overlay scoping and sequence display.
- Modify `fixthis-mcp/src/main/console/undoRedo.js` for context-bearing operations.
- Regenerate `fixthis-mcp/src/main/resources/console/app.js` after every console JS edit by running `node scripts/build-console-assets.mjs`.

Server sequence counter:

- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt` to add `nextItemSequenceNumber`.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` to allocate from the durable counter and migrate legacy sessions on mutation.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionReplayEngine.kt` if replay bypasses the store reducer for sequence allocation.
- Test in `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`.
- Test in `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt`.

JavaScript tests:

- Create `scripts/sessionScopedRequests-test.mjs`.
- Create `scripts/pendingBoundaryGuard-test.mjs`.
- Create `scripts/undoRedoContext-test.mjs`.
- Create `scripts/savedOverlayScope-test.mjs`.
- Modify `scripts/console-browser-smoke.mjs` for one end-to-end session-scope race and one artifact URL assertion.

## Conventions

- Follow TDD for each task: write the failing test, run it and confirm failure, implement the smallest working change, run the focused tests, then commit.
- Do not hand-edit `fixthis-mcp/src/main/resources/console/app.js`; regenerate it from source modules.
- Do not rename persisted public JSON fields: `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `sourceCandidates`.
- Keep legacy current-session fallback in server routes when `sessionId` is absent. The console must always send explicit session IDs.
- Commit messages:
  - Server/session scope: `fix(console): scope annotation routes to request session`
  - Artifact scope: `fix(console): scope preview artifacts to request session`
  - Pending guard: `fix(console): guard pending annotations across session boundaries`
  - Undo/redo context: `fix(console): scope undo history to annotation context`
  - Overlay/sequence: `fix(console): harden saved overlay and sequence numbering`

---

## Task 1: Add Explicit Session IDs to Annotation Mutating Routes

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsolePreviewModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Add failing test for batch save using request `sessionId`**

Append this test to `ConsoleFeedbackItemRoutesTest`:

```kotlin
@Test
fun batchSaveUsesRequestedSessionWhenCurrentSessionChanged() {
    val store = FeedbackSessionStore(
        clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L).next,
        idGenerator = FakeIds("session-a", "preview-screen-a", "item-a", "session-b").next,
    )
    val service = FeedbackSessionService(
        bridge = SequencedFingerprintBridge("fp-a", "fp-a"),
        store = store,
        projectRoot = Files.createTempDirectory("fixthis-session-scope").toString(),
        defaultPackageName = "io.github.beyondwin.fixthis.sample",
    )
    val sessionA = service.openSession(null, newSession = true)
    val preview = runBlocking { service.capturePreview(sessionA.sessionId) }
    service.openSession(null, newSession = true)
    val server = FeedbackConsoleServer(service = service, port = 0)
    server.start()
    try {
        val body = """
            {
              "sessionId": "${sessionA.sessionId}",
              "previewId": "${preview.previewId}",
              "frozenFingerprint": "fp-a",
              "items": [{
                "targetType": "area",
                "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                "comment": "save into session A"
              }]
            }
        """.trimIndent()
        val response = ConsoleHttpTestClient(server.url).postJson("/api/items/batch", body)

        assertEquals(200, response.statusCode)
        assertEquals(1, service.getSession(sessionA.sessionId).items.size)
        assertEquals("save into session A", service.getSession(sessionA.sessionId).items.single().comment)
        assertTrue(service.requireCurrentSession().sessionId != sessionA.sessionId)
        assertTrue(service.requireCurrentSession().items.isEmpty())
    } finally {
        server.stop()
    }
}
```

- [ ] **Step 2: Add failing test for handoff using request `sessionId`**

Append this test to `ConsoleFeedbackItemRoutesTest`:

```kotlin
@Test
fun agentHandoffUsesRequestedSessionWhenCurrentSessionChanged() {
    val store = FeedbackSessionStore(
        clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L).next,
        idGenerator = FakeIds("session-a", "screen-a", "item-a", "session-b", "handoff-a").next,
    )
    val service = FeedbackSessionService(
        bridge = FakeFixThisBridge(),
        store = store,
        projectRoot = "/repo",
        defaultPackageName = "io.github.beyondwin.fixthis.sample",
    )
    val sessionA = service.openSession(null, newSession = true)
    store.addScreen(sessionA.sessionId, SnapshotDto("screen-a", 100L, displayName = "A"))
    val item = store.addItem(
        sessionA.sessionId,
        AnnotationDto(
            itemId = "pending",
            screenId = "screen-a",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "handoff A",
        ),
    )
    service.openSession(null, newSession = true)
    val server = FeedbackConsoleServer(service = service, port = 0)
    server.start()
    try {
        val body = """{"sessionId":"${sessionA.sessionId}","itemIds":["${item.itemId}"]}"""
        val response = ConsoleHttpTestClient(server.url).postJson("/api/agent-handoffs", body)

        assertEquals(200, response.statusCode)
        assertEquals(FeedbackDelivery.SENT, service.getSession(sessionA.sessionId).items.single().delivery)
        assertTrue(service.requireCurrentSession().items.isEmpty())
    } finally {
        server.stop()
    }
}
```

- [ ] **Step 3: Run focused tests and confirm failure**

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchSaveUsesRequestedSessionWhenCurrentSessionChanged" --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.agentHandoffUsesRequestedSessionWhenCurrentSessionChanged"
```

Expected before implementation: FAIL because `SaveSnapshotRequest` and `AgentHandoffRequest` do not decode `sessionId`, or the route ignores it.

- [ ] **Step 4: Add `sessionId` to request models**

In `FeedbackConsolePreviewModels.kt`, replace `SaveSnapshotRequest` and `AgentHandoffRequest` with:

```kotlin
@Serializable
data class SaveSnapshotRequest(
    val sessionId: String? = null,
    val previewId: String,
    val items: List<AnnotationDraftDto>,
    val screen: SnapshotDto? = null,
    val frozenFingerprint: String? = null,
    val forceMismatchOverride: Boolean = false,
)

@Serializable
data class AgentHandoffRequest(
    val sessionId: String? = null,
    val itemIds: List<String> = emptyList(),
)
```

In `AnnotationRequestModels.kt`, replace `AddAnnotationRequest` with:

```kotlin
@Serializable
data class AddAnnotationRequest(
    val sessionId: String? = null,
    val screenId: String,
    val comment: String,
    val targetType: FeedbackTargetType = FeedbackTargetType.AREA,
    val bounds: FixThisRect,
    val nodeUid: String? = null,
)
```

- [ ] **Step 5: Add request-session resolver in `FeedbackItemRoutes.kt`**

Inside `FeedbackItemRoutes`, add:

```kotlin
private fun requestSessionId(explicit: String?): String =
    explicit?.takeIf { it.isNotBlank() } ?: service.requireCurrentSession().sessionId
```

Change route bodies:

```kotlin
// POST /api/items
val sessionId = requestSessionId(request.sessionId)
val item = runBlocking {
    service.addFeedbackItem(
        sessionId = sessionId,
        screenId = request.screenId,
        targetType = request.targetType,
        bounds = request.bounds,
        nodeUid = request.nodeUid,
        comment = request.comment,
    )
}

// POST /api/items/batch
val sessionId = requestSessionId(request.sessionId)
runBlocking {
    service.savePreviewFeedbackItemsWithLiveFingerprintMetadata(
        PreviewFeedbackLiveSaveRequest(
            sessionId = sessionId,
            previewId = request.previewId,
            items = request.items,
            fallbackScreen = request.screen,
            fingerprintCheck = PreviewFeedbackFingerprintCheck(
                frozenFingerprint = request.frozenFingerprint,
                forceMismatchOverride = request.forceMismatchOverride,
            ),
        ),
    )
}

// DELETE /api/items/draft
val sessionId = exchange.queryParameter("sessionId")?.takeIf { it.isNotBlank() }
    ?: service.requireCurrentSession().sessionId
exchange.sendJson(200, service.clearDraftItems(sessionId))

// POST /api/agent-handoffs
val sessionId = requestSessionId(request.sessionId)
val result = service.sendDraftToAgent(sessionId, request.itemIds)
```

Update `allowedAddFeedbackItemRequestKeys` to include `"sessionId"` if that set exists in this file.

- [ ] **Step 6: Run focused tests**

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchSaveUsesRequestedSessionWhenCurrentSessionChanged" --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.agentHandoffUsesRequestedSessionWhenCurrentSessionChanged"
```

Expected after implementation: PASS.

- [ ] **Step 7: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsolePreviewModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "fix(console): scope annotation routes to request session"
```

## Task 2: Scope Preview and Screen Artifact Routes by Request Session

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleArtifactRoutesSessionScopeTest.kt`

- [ ] **Step 1: Create failing artifact route tests**

Create `ConsoleArtifactRoutesSessionScopeTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.fixtures.FakeLongs
import io.github.beyondwin.fixthis.mcp.fixtures.SessionScreenshotBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import java.net.URLEncoder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleArtifactRoutesSessionScopeTest {
    @Test
    fun previewScreenshotUsesExplicitSessionIdWhenCurrentSessionChanged() {
        val root = Files.createTempDirectory("fixthis-artifact-scope").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "preview-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val preview = kotlinx.coroutines.runBlocking { service.capturePreview(sessionA.sessionId) }
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val path = "/api/preview/${encode(preview.previewId)}/screenshot/full?sessionId=${encode(sessionA.sessionId)}"
            val response = ConsoleHttpTestClient(server.url).getResponse(path)

            assertEquals(200, response.statusCode)
            assertTrue(response.contentTypeStartsWith("image/png"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun previewScreenshotRejectsMismatchedExplicitSessionId() {
        val root = Files.createTempDirectory("fixthis-artifact-mismatch").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "preview-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val preview = kotlinx.coroutines.runBlocking { service.capturePreview(sessionA.sessionId) }
        val sessionB = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val path = "/api/preview/${encode(preview.previewId)}/screenshot/full?sessionId=${encode(sessionB.sessionId)}"
            val response = ConsoleHttpTestClient(server.url).getResponse(path)

            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
        }
        assertTrue(sessionA.sessionId != sessionB.sessionId)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
```

- [ ] **Step 2: Run focused tests and confirm failure**

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleArtifactRoutesSessionScopeTest"
```

Expected before implementation: FAIL because preview/screen routes use `requireCurrentSession()`.

- [ ] **Step 3: Scope `PreviewRoutes.sendPreviewScreenshot(previewId)`**

In `PreviewRoutes.kt`, change the preview screenshot path handler to pass the exchange:

```kotlin
exchange.sendPreviewScreenshot(exchange.requestURI.path.previewIdFromScreenshotPath())
```

Replace `sendPreviewScreenshot(previewId)` with:

```kotlin
private fun HttpExchange.sendPreviewScreenshot(previewId: String) {
    val explicitSessionId = queryParameter("sessionId")?.takeIf { it.isNotBlank() }
    val session = explicitSessionId?.let { service.getSession(it) } ?: service.requireCurrentSession()
    val screenshotFile = try {
        service.previewScreenshotFile(session.sessionId, previewId)
    } catch (error: RuntimeException) {
        throw FeedbackConsoleHttpException(404, "Screenshot not found", error)
    }
    sendBytes(200, screenshotFile.readBytes(), "image/png")
}
```

- [ ] **Step 4: Scope `ArtifactRoutes` screen screenshot and delete**

In `ArtifactRoutes.kt`, add:

```kotlin
private fun HttpExchange.requestedSession() =
    queryParameter("sessionId")?.takeIf { it.isNotBlank() }?.let { service.getSession(it) }
        ?: service.requireCurrentSession()
```

Use it in both paths:

```kotlin
service.deleteScreen(
    requestedSession().sessionId,
    exchange.requestURI.path.screenIdFromScreenPath(),
)
```

And:

```kotlin
private fun HttpExchange.sendScreenshot(screenId: String) {
    val session = requestedSession()
    val screen = session.screens.firstOrNull { it.screenId == screenId }
        ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")
    ...
}
```

- [ ] **Step 5: Run artifact route tests**

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleArtifactRoutesSessionScopeTest"
```

Expected after implementation: PASS.

- [ ] **Step 6: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewRoutes.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleArtifactRoutesSessionScopeTest.kt
git commit -m "fix(console): scope preview artifacts to request session"
```

## Task 3: Make Console Requests Carry Captured Annotation Context

**Files:**

- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `fixthis-mcp/src/main/console/pendingPersistence.js`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`
- Create: `scripts/sessionScopedRequests-test.mjs`

- [ ] **Step 1: Create failing JavaScript source test**

Create `scripts/sessionScopedRequests-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
const previewSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');
const promptSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/prompt.js'), 'utf8');
const persistenceSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/pendingPersistence.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('annotate flow captures immutable session preview context', () => {
  const start = body(annotationsSource, 'async function startAddItemsFlow()');
  assert.match(start, /context:\s*\{/);
  assert.match(start, /sessionId:\s*state\.session\?\.sessionId\s*\|\|\s*null/);
  assert.match(start, /previewId:\s*state\.preview\.previewId/);
  assert.match(start, /screenId:\s*state\.preview\.screen\?\.screenId\s*\|\|\s*null/);
  assert.match(start, /screenFingerprint:\s*state\.preview\.screen\?\.fingerprint\s*\?\?\s*null/);
});

test('batch save sends captured sessionId and fingerprint from addItemsFlow context', () => {
  const persist = body(annotationsSource, 'async function persistPendingFeedbackItems(options = {})');
  assert.match(persist, /sessionId:\s*addItemsFlow\.context\.sessionId/);
  assert.match(persist, /previewId:\s*addItemsFlow\.context\.previewId/);
  assert.match(persist, /frozenFingerprint:\s*addItemsFlow\.context\.screenFingerprint/);
});

test('preview and screen URLs include explicit sessionId query', () => {
  assert.match(previewSource, /function previewScreenshotUrl\(previewId,\s*sessionId/);
  assert.match(previewSource, /sessionId=\s*encodeURIComponent\(sessionId\)/);
  assert.match(previewSource, /function screenScreenshotUrl\(screenId,\s*sessionId/);
});

test('pending persistence envelope stores captured context', () => {
  assert.match(persistenceSource, /context:\s*value\?\.context\s*\?\?\s*null/);
});

test('agent handoff includes sessionId in request body', () => {
  assert.match(promptSource, /sessionId:\s*state\.session\?\.sessionId\s*\|\|\s*null/);
});
```

- [ ] **Step 2: Run the test and confirm failure**

```bash
node scripts/sessionScopedRequests-test.mjs
```

Expected before implementation: FAIL because `addItemsFlow.context` and session-scoped URL helpers do not exist.

- [ ] **Step 3: Add session-scoped URL helpers in `preview.js`**

Replace `previewScreenshotUrl` and add `screenScreenshotUrl`:

```js
function scopedQuery(sessionId) {
  return sessionId ? '?sessionId=' + encodeURIComponent(sessionId) : '';
}

function previewScreenshotUrl(previewId, sessionId = state.session?.sessionId || null) {
  return '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full' + scopedQuery(sessionId);
}

function screenScreenshotUrl(screenId, sessionId = state.session?.sessionId || null) {
  return '/api/screens/' + encodeURIComponent(screenId) + '/screenshot/full' + scopedQuery(sessionId);
}
```

Update `screenImageUrl(screen)` so saved screen URLs use `screenScreenshotUrl(screen.screenId, state.session?.sessionId || focusedSavedSessionId || null)`.

- [ ] **Step 4: Capture context in `startAddItemsFlow()`**

In `annotations.js`, inside the `addItemsFlow = { ... }` assignment, include:

```js
context: {
  sessionId: state.session?.sessionId || null,
  previewId: state.preview.previewId,
  screenId: state.preview.screen?.screenId || null,
  screenFingerprint: state.preview.screen?.fingerprint ?? null,
  deviceSerial: state.selectedDeviceSerial || null,
  frozenAtEpochMillis: state.preview.frozenAtEpochMillis ?? Date.now(),
  activityName: state.preview.activity ?? state.connection?.availability?.activity ?? null
},
```

Change `screenshotUrl` to:

```js
screenshotUrl: previewScreenshotUrl(state.preview.previewId, state.session?.sessionId || null),
```

- [ ] **Step 5: Persist context in pending recovery envelope**

In `annotations.js`, update `currentPendingStateEnvelope()`:

```js
function currentPendingStateEnvelope(items = pendingFeedbackItems) {
  return {
    context: addItemsFlow?.context ?? null,
    previewId: addItemsFlow?.previewId ?? addItemsFlow?.context?.previewId ?? null,
    screen: addItemsFlow?.screen ?? null,
    screenshotUrl: addItemsFlow?.screenshotUrl ?? null,
    frozenAtEpochMillis: addItemsFlow?.frozenAtEpochMillis ?? addItemsFlow?.context?.frozenAtEpochMillis ?? null,
    items: items,
  };
}
```

In `pendingPersistence.js`, add `context` to the schema v1 envelope and restore path:

```js
context: value?.context ?? null,
```

and:

```js
context: parsed.context ?? null,
```

- [ ] **Step 6: Send context in save and handoff requests**

In `persistPendingFeedbackItems()`, replace the request body fields:

```js
sessionId: addItemsFlow.context.sessionId,
previewId: addItemsFlow.context.previewId,
screen: addItemsFlow.screen,
items: payloadItems,
frozenFingerprint: addItemsFlow.context.screenFingerprint,
forceMismatchOverride: Boolean(overrideMismatch)
```

In `prompt.js`, when posting `/api/agent-handoffs`, include:

```js
const sessionId = state.session?.sessionId || null;
const result = await requestJson('/api/agent-handoffs', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ sessionId, itemIds }),
});
```

- [ ] **Step 7: Regenerate bundle and run JS test**

```bash
node scripts/build-console-assets.mjs
node scripts/sessionScopedRequests-test.mjs
node scripts/build-console-assets.mjs --check
```

Expected after implementation: all commands PASS.

- [ ] **Step 8: Commit**

```bash
git add \
  fixthis-mcp/src/main/console/preview.js \
  fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/console/prompt.js \
  fixthis-mcp/src/main/console/pendingPersistence.js \
  fixthis-mcp/src/main/resources/console/app.js \
  scripts/sessionScopedRequests-test.mjs
git commit -m "fix(console): send captured session context with annotation requests"
```

## Task 4: Add Pending Boundary Guard for Session Close/Delete/Switch

**Files:**

- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`
- Create: `scripts/pendingBoundaryGuard-test.mjs`

- [ ] **Step 1: Create failing boundary guard source test**

Create `scripts/pendingBoundaryGuard-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const mainSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/main.js'), 'utf8');
const historySource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('shared pending boundary resolver exists', () => {
  assert.match(mainSource, /async function resolvePendingBeforeBoundary\(action,\s*sessionId = null\)/);
  assert.match(mainSource, /Save draft/);
  assert.match(mainSource, /Keep editing/);
  assert.match(mainSource, /Discard/);
});

test('closeSession uses boundary resolver before reset', () => {
  const closeBody = body(historySource, 'async function closeSession()');
  assert.match(closeBody, /await resolvePendingBeforeBoundary\('close-session'/);
  assert.doesNotMatch(closeBody, /resetAnnotationComposerState\(\);[\s\S]*requestJson\('\/api\/session\/close'/);
});

test('deleteHistorySession uses boundary resolver before reset', () => {
  const deleteBody = body(historySource, 'async function deleteHistorySession(sessionId)');
  assert.match(deleteBody, /await resolvePendingBeforeBoundary\('delete-session',\s*sessionId\)/);
  assert.doesNotMatch(deleteBody, /if \(isDisplayedSession\(\)\) \{\s*resetAnnotationComposerState\(\);/);
});
```

- [ ] **Step 2: Run the test and confirm failure**

```bash
node scripts/pendingBoundaryGuard-test.mjs
```

Expected before implementation: FAIL because `resolvePendingBeforeBoundary()` does not exist and close/delete still reset immediately.

- [ ] **Step 3: Add resolver in `main.js`**

Add this function near pending recovery helpers:

```js
async function resolvePendingBeforeBoundary(action, sessionId = null) {
  const hasActivePending = Boolean(addItemsFlow && pendingFeedbackItems.length);
  if (!hasActivePending && !hasPendingRecoveryItems()) return 'continue';
  if (hasPendingRecoveryItems() && !hasActivePending) {
    renderPendingRecoveryBanner();
    showError('Recover, recapture, or discard unsaved annotations before changing sessions.');
    return 'cancel';
  }
  const pendingSessionId = addItemsFlow?.context?.sessionId || state.session?.sessionId || null;
  if (sessionId && pendingSessionId && sessionId !== pendingSessionId) return 'continue';
  const choice = await promptPendingBoundaryChoice(action, pendingFeedbackItems.length);
  if (choice === 'save') {
    await persistPendingFeedbackItems({ allowBlankComments: true });
    return 'continue';
  }
  if (choice === 'discard') {
    resetAnnotationComposerState();
    return 'continue';
  }
  return 'cancel';
}

function promptPendingBoundaryChoice(action, count) {
  if (typeof window !== 'undefined' && typeof window.fixThisPromptPendingBoundary === 'function') {
    return Promise.resolve(window.fixThisPromptPendingBoundary({ action, count }));
  }
  if (typeof window === 'undefined' || typeof window.confirm !== 'function') return Promise.resolve('cancel');
  const save = window.confirm('Save draft before changing sessions?\\n확인 = Save draft\\n취소 = Keep editing or discard');
  if (save) return Promise.resolve('save');
  const discard = window.confirm('Discard unsaved annotations?\\n확인 = Discard\\n취소 = Keep editing');
  return Promise.resolve(discard ? 'discard' : 'cancel');
}
```

- [ ] **Step 4: Replace session boundary calls in `history.js`**

At the start of `openSession(sessionId)` after `error.textContent = ''`, use:

```js
if (await resolvePendingBeforeBoundary('open-session', sessionId) !== 'continue') return;
```

At the start of `newSession()`:

```js
if (await resolvePendingBeforeBoundary('new-session') !== 'continue') return;
```

At the start of `closeSession()` after confirming `state.session` exists:

```js
if (await resolvePendingBeforeBoundary('close-session', state.session.sessionId) !== 'continue') return;
```

At the start of `deleteHistorySession(sessionId)`:

```js
if (await resolvePendingBeforeBoundary('delete-session', sessionId) !== 'continue') return;
```

Remove the immediate `flushPendingAnnotationsBeforeSessionChange()` calls in open/new. The resolver now owns the decision.

- [ ] **Step 5: Regenerate and run tests**

```bash
node scripts/build-console-assets.mjs
node scripts/pendingBoundaryGuard-test.mjs
node scripts/pendingItemRecovery-test.mjs
node scripts/build-console-assets.mjs --check
```

Expected after implementation: all commands PASS.

- [ ] **Step 6: Commit**

```bash
git add \
  fixthis-mcp/src/main/console/main.js \
  fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/resources/console/app.js \
  scripts/pendingBoundaryGuard-test.mjs
git commit -m "fix(console): guard pending annotations across session boundaries"
```

## Task 5: Add Generation Fencing for Async Session Mutations

**Files:**

- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/devices.js`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `scripts/sessionScopedRequests-test.mjs`

- [ ] **Step 1: Extend source test for mutation generation**

Append to `scripts/sessionScopedRequests-test.mjs`:

```js
test('session mutation generation fences stale async responses', () => {
  const stateSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/state.js'), 'utf8');
  assert.match(stateSource, /let sessionMutationGeneration = 0;/);
  assert.match(annotationsSource, /const requestGeneration = sessionMutationGeneration;/);
  assert.match(annotationsSource, /if \(requestGeneration !== sessionMutationGeneration\)/);
});
```

- [ ] **Step 2: Run the test and confirm failure**

```bash
node scripts/sessionScopedRequests-test.mjs
```

Expected before implementation: FAIL because generation state is not present.

- [ ] **Step 3: Add generation state and helper**

In `state.js`, add near preview request generation variables:

```js
let sessionMutationGeneration = 0;

function bumpSessionMutationGeneration() {
  sessionMutationGeneration += 1;
  return sessionMutationGeneration;
}
```

- [ ] **Step 4: Bump generation before session/device boundaries**

In `history.js`, before any request that changes current session, call:

```js
bumpSessionMutationGeneration();
```

Apply it in `openSession`, `newSession`, `closeSession`, and `deleteHistorySession` after the pending boundary resolver returns `"continue"` and before `requestJson(...)`.

In `devices.js`, call `bumpSessionMutationGeneration()` before changing `state.selectedDeviceSerial` or posting device selection.

- [ ] **Step 5: Fence save response application**

In `persistPendingFeedbackItems()`, before `sendBatch` is first called:

```js
const requestGeneration = sessionMutationGeneration;
const expectedSessionId = addItemsFlow.context.sessionId;
```

After a successful result:

```js
if (requestGeneration !== sessionMutationGeneration) {
  await refreshSessions();
  return null;
}
if (result.session?.sessionId !== expectedSessionId) {
  await refreshSessions();
  throw new Error('Save returned a different feedback session. Refresh and try again.');
}
```

Only assign `state.session = result.session` after those checks.

- [ ] **Step 6: Regenerate and run tests**

```bash
node scripts/build-console-assets.mjs
node scripts/sessionScopedRequests-test.mjs
node scripts/build-console-assets.mjs --check
```

Expected after implementation: PASS.

- [ ] **Step 7: Commit**

```bash
git add \
  fixthis-mcp/src/main/console/state.js \
  fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/console/devices.js \
  fixthis-mcp/src/main/resources/console/app.js \
  scripts/sessionScopedRequests-test.mjs
git commit -m "fix(console): fence stale session mutation responses"
```

## Task 6: Scope Undo/Redo History to Annotation Context

**Files:**

- Modify: `fixthis-mcp/src/main/console/undoRedo.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/main.js`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`
- Create: `scripts/undoRedoContext-test.mjs`
- Modify: `scripts/undoRedo-test.mjs`

- [ ] **Step 1: Create failing context-aware undo test**

Create `scripts/undoRedoContext-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const undoRedoSrc = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/undoRedo.js'), 'utf8');
const factory = new Function(`${undoRedoSrc}; return { createHistory, recordAdd, recordDelete, undo, redo };`);
const m = factory();

const contextA = { sessionId: 'a', previewId: 'p1', screenId: 's1', screenFingerprint: 'fp1', deviceSerial: 'd1' };
const contextB = { sessionId: 'b', previewId: 'p2', screenId: 's2', screenFingerprint: 'fp2', deviceSerial: 'd1' };

test('undo rejects operations from a different context', () => {
  const state = { pendingFeedbackItems: [{ annotationId: 'local-1', comment: 'a' }] };
  const h = m.createHistory(contextA);
  m.recordDelete(h, state.pendingFeedbackItems[0], 0, contextA);
  state.pendingFeedbackItems = [];
  const result = m.undo(h, state, contextB);
  assert.deepEqual(result, { applied: false, reason: 'context_mismatch' });
  assert.equal(state.pendingFeedbackItems.length, 0);
  assert.equal(h.undoStack.length, 0);
});

test('undo applies operations from the same context', () => {
  const state = { pendingFeedbackItems: [{ annotationId: 'local-1', comment: 'a' }] };
  const h = m.createHistory(contextA);
  m.recordDelete(h, state.pendingFeedbackItems[0], 0, contextA);
  state.pendingFeedbackItems = [];
  const result = m.undo(h, state, contextA);
  assert.deepEqual(result, { applied: true });
  assert.equal(state.pendingFeedbackItems[0].annotationId, 'local-1');
});
```

- [ ] **Step 2: Run the test and confirm failure**

```bash
node scripts/undoRedoContext-test.mjs
```

Expected before implementation: FAIL because `createHistory(context)` and context-aware return values do not exist.

- [ ] **Step 3: Update `undoRedo.js` context helpers**

Replace `createHistory` and add context comparison helpers:

```js
function createHistory(context = null) {
  return { context: cloneHistoryContext(context), undoStack: [], redoStack: [] };
}

function cloneHistoryContext(context) {
  if (!context) return null;
  return {
    sessionId: context.sessionId || null,
    previewId: context.previewId || null,
    screenId: context.screenId || null,
    screenFingerprint: context.screenFingerprint || null,
    deviceSerial: context.deviceSerial || null
  };
}

function sameHistoryContext(left, right) {
  const a = cloneHistoryContext(left);
  const b = cloneHistoryContext(right);
  return Boolean(a && b) &&
    a.sessionId === b.sessionId &&
    a.previewId === b.previewId &&
    a.screenId === b.screenId &&
    a.screenFingerprint === b.screenFingerprint &&
    a.deviceSerial === b.deviceSerial;
}

function clearHistory(history) {
  history.undoStack.length = 0;
  history.redoStack.length = 0;
}
```

- [ ] **Step 4: Update record/apply signatures**

Change records:

```js
function recordAdd(history, item, context = history.context) {
  pushOp(history.undoStack, { kind: 'add', after: { ...item }, context: cloneHistoryContext(context), createdAtEpochMillis: Date.now() });
  history.redoStack.length = 0;
}

function recordDelete(history, before, index = null, context = history.context) {
  if (!before) return;
  pushOp(history.undoStack, {
    kind: 'delete',
    before: { ...before },
    index: Number.isInteger(index) ? index : null,
    context: cloneHistoryContext(context),
    createdAtEpochMillis: Date.now()
  });
  history.redoStack.length = 0;
}

function recordUpdate(history, before, after, context = history.context) {
  if (!before || !after) return;
  pushOp(history.undoStack, { kind: 'update', before: { ...before }, after: { ...after }, context: cloneHistoryContext(context), createdAtEpochMillis: Date.now() });
  history.redoStack.length = 0;
}
```

Change `undo` and `redo`:

```js
function undo(history, state, context = history.context) {
  const op = history.undoStack.pop();
  if (!op) return { applied: false, reason: 'empty' };
  if (!sameHistoryContext(op.context, context)) {
    clearHistory(history);
    return { applied: false, reason: 'context_mismatch' };
  }
  applyInverse(op, state);
  pushOp(history.redoStack, op);
  return { applied: true };
}

function redo(history, state, context = history.context) {
  const op = history.redoStack.pop();
  if (!op) return { applied: false, reason: 'empty' };
  if (!sameHistoryContext(op.context, context)) {
    clearHistory(history);
    return { applied: false, reason: 'context_mismatch' };
  }
  applyForward(op, state);
  pushOp(history.undoStack, op);
  return { applied: true };
}
```

- [ ] **Step 5: Keep legacy boolean tests passing**

Update `scripts/undoRedo-test.mjs` assertions:

```js
assert.equal(m.undo(h, state).applied, false);
```

and for positive cases:

```js
assert.equal(m.undo(h, state).applied, true);
```

- [ ] **Step 6: Wire context in annotation calls**

In `startAddItemsFlow()` after assigning `addItemsFlow`, reset:

```js
undoRedoHistory = createHistory(addItemsFlow.context);
```

Update record calls:

```js
recordAdd(undoRedoHistory, annotation, addItemsFlow.context);
recordDelete(undoRedoHistory, removed, index, addItemsFlow?.context ?? null);
```

Update shortcut handlers in `main.js` if they inspect the boolean return:

```js
const result = undo(undoRedoHistory, { pendingFeedbackItems }, addItemsFlow?.context ?? null);
if (result.reason === 'context_mismatch') showError('Undo history was cleared because the annotation session changed.');
if (result.applied) {
  persistCurrentPendingState();
  render();
}
```

- [ ] **Step 7: Run tests**

```bash
node scripts/build-console-assets.mjs
node scripts/undoRedoContext-test.mjs
node scripts/undoRedo-test.mjs
node scripts/build-console-assets.mjs --check
```

Expected after implementation: PASS.

- [ ] **Step 8: Commit**

```bash
git add \
  fixthis-mcp/src/main/console/undoRedo.js \
  fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/console/main.js \
  fixthis-mcp/src/main/resources/console/app.js \
  scripts/undoRedoContext-test.mjs \
  scripts/undoRedo-test.mjs
git commit -m "fix(console): scope undo history to annotation context"
```

## Task 7: Stop Rendering Saved Overlays on Live Preview

**Files:**

- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`
- Create: `scripts/savedOverlayScope-test.mjs`

- [ ] **Step 1: Replace the existing Kotlin expectation**

In `ConsoleFeedbackItemRoutesTest`, rename `consoleHtmlRendersSavedAnnotationPinsForVisibleScreenWithoutFocus` to:

```kotlin
@Test
fun consoleHtmlDoesNotRenderSavedAnnotationPinsOnLivePreviewWithoutFocus() {
    val html = FeedbackConsoleAssets.indexHtml
    val renderSelectionOverlay = javascriptFunctionBody(html, "renderSelectionOverlay")

    assertTrue(
        renderSelectionOverlay.contains("if (!addItemsFlow && focusedSavedItemId)"),
        "saved overlays should be gated by focusedSavedItemId",
    )
    assertFalse(
        renderSelectionOverlay.contains("if (nodeUid) return visibleUids.has(nodeUid);"),
        "saved overlays must not infer screen identity from nodeUid on live preview",
    )
    assertTrue(
        renderSelectionOverlay.contains("item.screenId === focusedItem.screenId"),
        "focused saved overlay should include only items from the focused screen",
    )
}
```

- [ ] **Step 2: Create failing JS overlay source test**

Create `scripts/savedOverlayScope-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const renderingSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/rendering.js'), 'utf8');
const previewSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/preview.js'), 'utf8');

function body(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${signature} not found`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let i = bodyStart; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(bodyStart + 1, i);
    }
  }
  assert.fail(`${signature} body did not close`);
}

test('saved overlays require focused saved item', () => {
  const render = body(renderingSource, 'function renderSelectionOverlay()');
  assert.match(render, /if \(!addItemsFlow && focusedSavedItemId\)/);
  assert.doesNotMatch(render, /visibleScreenNodeUids\(visibleScreen\)/);
  assert.doesNotMatch(render, /visibleUids\.has\(nodeUid\)/);
});

test('latestScreen uses focused saved screenshot before persisted fallback', () => {
  const latest = body(previewSource, 'function latestScreen()');
  assert.match(latest, /if \(focusedSavedItemId\)/);
  assert.match(latest, /focusedItem\.screenId/);
  assert.match(latest, /return state\.preview\?\.screen \|\| latestPersistedScreen\(\);/);
});
```

- [ ] **Step 3: Run tests and confirm failure**

```bash
node scripts/savedOverlayScope-test.mjs
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.consoleHtmlDoesNotRenderSavedAnnotationPinsOnLivePreviewWithoutFocus"
```

Expected before implementation: FAIL because rendering currently uses `visibleScreenNodeUids`.

- [ ] **Step 4: Update `renderSelectionOverlay()`**

In `rendering.js`, replace the saved overlay block with:

```js
if (!addItemsFlow && focusedSavedItemId) {
  const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
  if (focusedItem) {
    const sameScreenItems = savedEvidenceItems().filter(item => item.screenId === focusedItem.screenId);
    if (sameScreenItems.length) renderSavedEvidenceOverlay(overlay, image, sameScreenItems);
  }
}
```

Remove the `visibleScreenNodeUids()` call from this function. Keep `visibleScreenNodeUids()` itself only if other code still uses it; otherwise remove the helper in a separate cleanup commit after tests pass.

- [ ] **Step 5: Verify `latestScreen()` still focuses saved screenshot**

Ensure `preview.js` keeps this behavior:

```js
function latestScreen() {
  if (addItemsFlow) return addItemsFlow.screen;
  if (focusedSavedItemId) {
    const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
    if (focusedItem) {
      const focusedScreen = (state.session?.screens || []).find(s => s.screenId === focusedItem.screenId);
      if (focusedScreen) return focusedScreen;
    }
  }
  return state.preview?.screen || latestPersistedScreen();
}
```

- [ ] **Step 6: Regenerate and run tests**

```bash
node scripts/build-console-assets.mjs
node scripts/savedOverlayScope-test.mjs
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.consoleHtmlDoesNotRenderSavedAnnotationPinsOnLivePreviewWithoutFocus"
node scripts/build-console-assets.mjs --check
```

Expected after implementation: PASS.

- [ ] **Step 7: Commit**

```bash
git add \
  fixthis-mcp/src/main/console/rendering.js \
  fixthis-mcp/src/main/console/preview.js \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt \
  scripts/savedOverlayScope-test.mjs
git commit -m "fix(console): avoid saved overlay leakage on live preview"
```

## Task 8: Add Durable Item Sequence Counter

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt`

- [ ] **Step 1: Add failing monotonic sequence test**

Append to `FeedbackSessionStoreTest`:

```kotlin
@Test
fun itemSequenceCounterDoesNotReuseNumbersAfterDelete() {
    val store = FeedbackSessionStore(
        clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L).next,
        idGenerator = FakeIds("session-1", "screen-1", "item-1", "item-2", "item-3", "item-4").next,
    )
    val session = store.openSession(packageName = "io.github.beyondwin.fixthis.sample", projectRoot = "/repo")
    val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
    fun draft(comment: String) = AnnotationDto(
        itemId = "pending",
        screenId = screen.screenId,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
        comment = comment,
    )

    val first = store.addItem(session.sessionId, draft("first"))
    val second = store.addItem(session.sessionId, draft("second"))
    val third = store.addItem(session.sessionId, draft("third"))

    store.deleteDraftFeedback(session.sessionId, second.itemId)
    val fourth = store.addItem(session.sessionId, draft("fourth"))

    assertEquals(listOf(1, 3, 4), store.getSession(session.sessionId).items.map { it.sequenceNumber })
    assertEquals(5, store.getSession(session.sessionId).nextItemSequenceNumber)
    assertEquals(listOf(1, 2, 3, 4), listOf(first, second, third, fourth).map { it.sequenceNumber })
}
```

- [ ] **Step 2: Run test and confirm failure**

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStoreTest.itemSequenceCounterDoesNotReuseNumbersAfterDelete"
```

Expected before implementation: FAIL because `SessionDto.nextItemSequenceNumber` does not exist.

- [ ] **Step 3: Add `nextItemSequenceNumber` to `SessionDto`**

In `SessionDtoModels.kt`:

```kotlin
@Serializable
data class SessionDto(
    val schemaVersion: String = "1.0",
    val sessionId: String,
    val packageName: String,
    val projectRoot: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val screens: List<SnapshotDto> = emptyList(),
    val items: List<AnnotationDto> = emptyList(),
    val handoffBatches: List<FeedbackHandoffBatch> = emptyList(),
    val status: SessionStatusDto = SessionStatusDto.ACTIVE,
    val nextItemSequenceNumber: Int = 1,
)
```

- [ ] **Step 4: Allocate from durable counter in `FeedbackSessionStore`**

Add helpers:

```kotlin
private fun migratedSequenceCounter(session: SessionDto): Int {
    val existingNext = session.items.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1) ?: 1
    return maxOf(session.nextItemSequenceNumber, existingNext)
}

private fun sessionWithMigratedSequenceCounter(session: SessionDto): SessionDto {
    val migrated = migratedSequenceCounter(session)
    return if (session.nextItemSequenceNumber == migrated) session else session.copy(nextItemSequenceNumber = migrated)
}
```

In `getSessionLocked`, normalize the loaded/current session before returning:

```kotlin
private fun getSessionLocked(sessionId: String): SessionDto {
    val session = loadPersistedSessionIfAvailable(sessionId)
        ?: sessions[sessionId]
        ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")
    val migrated = sessionWithMigratedSequenceCounter(session)
    sessions[sessionId] = migrated
    return migrated
}
```

Replace `nextItemSequenceNumber(session)` usage in `addItem()`:

```kotlin
val sequence = migratedSequenceCounter(session)
val created = item.copy(
    itemId = idGenerator(),
    createdAtEpochMillis = now,
    updatedAtEpochMillis = now,
    sequenceNumber = item.sequenceNumber ?: sequence,
    delivery = item.delivery,
)
val updated = session.copy(
    items = session.items + created,
    nextItemSequenceNumber = maxOf(sequence + 1, created.sequenceNumber?.plus(1) ?: sequence + 1),
    updatedAtEpochMillis = now,
)
```

Replace `addScreenWithItems()` allocation:

```kotlin
val firstSequence = migratedSequenceCounter(session)
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
val nextSequence = createdItems.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1) ?: firstSequence
val updated = session.copy(
    screens = session.screens + captured,
    items = session.items + createdItems,
    nextItemSequenceNumber = maxOf(session.nextItemSequenceNumber, nextSequence),
    updatedAtEpochMillis = now,
)
```

Keep `nextItemSequenceNumber(session)` only if other code needs it; otherwise delete it.

- [ ] **Step 5: Ensure event replay preserves counter**

Run the existing event replay tests:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStoreEventLogTest"
```

If replay creates sessions with the default counter after applying item events, update replay completion to call `sessionWithMigratedSequenceCounter(replayed)` before storing the replayed session.

- [ ] **Step 6: Run sequence tests**

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStoreTest.itemSequenceCounterDoesNotReuseNumbersAfterDelete"
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStoreEventLogTest"
```

Expected after implementation: PASS.

- [ ] **Step 7: Commit**

```bash
git add \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt
git commit -m "fix(session): persist monotonic annotation sequence counter"
```

## Task 9: Use Server Sequence Numbers in Console Rendering

**Files:**

- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Regenerate: `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `scripts/savedOverlayScope-test.mjs`

- [ ] **Step 1: Extend JS test for sequence rendering**

Append to `scripts/savedOverlayScope-test.mjs`:

```js
test('saved rows and overlays prefer server sequenceNumber', () => {
  assert.match(renderingSource, /function annotationDisplayNumber\(item,\s*index\)/);
  assert.match(renderingSource, /item\?\.sequenceNumber\s*\?\?\s*\(index \+ 1\)/);
  assert.doesNotMatch(renderingSource, /'#' \+ \(index \+ 1\)/);
  assert.doesNotMatch(renderingSource, /String\(index \+ 1\), false,[\s\S]*focusedSavedItemId/);
});
```

- [ ] **Step 2: Run test and confirm failure**

```bash
node scripts/savedOverlayScope-test.mjs
```

Expected before implementation: FAIL if rendering still uses direct `index + 1` fallback for saved rows/overlays.

- [ ] **Step 3: Add display helper in `rendering.js`**

Add near other annotation helpers:

```js
function annotationDisplayNumber(item, index) {
  return item?.sequenceNumber ?? (index + 1);
}
```

Use it in saved row rendering:

```js
const displayNumber = annotationDisplayNumber(item, index);
```

Replace saved row number markup:

```js
'<span class="ann-row-num">' + escapeHtml(String(displayNumber)) + '</span>'
```

Use it in saved overlay rendering:

```js
const displayNumber = annotationDisplayNumber(item, index);
renderOverlayBox(overlay, image, boundsForTarget(item.target), String(displayNumber), false, item.itemId === focusedSavedItemId, index, '', severityColor(annotationSeverity(item)));
```

Keep pending local overlays using `index + 1` until the item has a server sequence number.

- [ ] **Step 4: Update `history.js` fallback**

In `formatAnnotationSummary` or equivalent helper that computes row numbers, keep:

```js
const number = item.sequenceNumber ?? (index + 1);
```

Do not use a truthy check like `item.sequenceNumber ? item.sequenceNumber : index + 1`, because sequence `0` is invalid but this convention makes nullability ambiguous. Use `??`.

- [ ] **Step 5: Regenerate and run tests**

```bash
node scripts/build-console-assets.mjs
node scripts/savedOverlayScope-test.mjs
node scripts/build-console-assets.mjs --check
```

Expected after implementation: PASS.

- [ ] **Step 6: Commit**

```bash
git add \
  fixthis-mcp/src/main/console/rendering.js \
  fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/resources/console/app.js \
  scripts/savedOverlayScope-test.mjs
git commit -m "fix(console): render stable annotation sequence numbers"
```

## Task 10: Extend Browser Smoke for Session Scope Regression

**Files:**

- Modify: `scripts/console-browser-smoke.mjs`

- [ ] **Step 1: Add fake API assertions for scoped screenshot URLs**

In the fake HTTP handler inside `scripts/console-browser-smoke.mjs`, add a request log array:

```js
const fake = {
  ...
  requestedPaths: [],
};
```

At the top of `handleApi`, push:

```js
fake.requestedPaths.push(url.pathname + url.search);
```

- [ ] **Step 2: Add browser assertion after focusing saved annotation**

After the smoke clicks a saved annotation row, add:

```js
await page.waitForFunction(() => {
  const image = document.getElementById('snapshotImage');
  return image?.src?.includes('/api/screens/') && image.src.includes('sessionId=');
});
```

Then expose requested paths from the fake server if the harness already has an evaluation channel. If not, assert in browser only; the `sessionScopedRequests-test.mjs` source test covers code-level URL construction.

- [ ] **Step 3: Add delete-session pending guard smoke step**

After creating one pending annotation and before saving it, click the active
history row delete button. Stub the prompt:

```js
await page.evaluate(() => {
  window.fixThisPromptPendingBoundary = () => 'cancel';
});
```

Assert the pending item remains:

```js
await page.click('[data-delete-session-id]');
await waitForPendingPins(page, 1, 'Pending annotation should remain after Keep editing');
```

- [ ] **Step 4: Run smoke**

```bash
npm run console:smoke
```

Expected after implementation:

```text
Console browser smoke passed.
```

- [ ] **Step 5: Commit**

```bash
git add scripts/console-browser-smoke.mjs
git commit -m "test(console): cover session-scoped annotation smoke path"
```

## Task 11: Run Focused and Full Verification

**Files:**

- No source edits unless verification exposes a defect.

- [ ] **Step 1: Run focused JS verification**

```bash
node scripts/sessionScopedRequests-test.mjs
node scripts/pendingBoundaryGuard-test.mjs
node scripts/undoRedoContext-test.mjs
node scripts/undoRedo-test.mjs
node scripts/pendingItemRecovery-test.mjs
node scripts/savedOverlayScope-test.mjs
node scripts/build-console-assets.mjs --check
```

Expected: every command exits 0.

- [ ] **Step 2: Run focused Kotlin verification**

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest"
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.ConsoleArtifactRoutesSessionScopeTest"
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStoreTest"
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStoreEventLogTest"
```

Expected: every command exits 0.

- [ ] **Step 3: Run browser smoke**

```bash
npm run console:smoke
```

Expected:

```text
Console browser smoke passed.
```

- [ ] **Step 4: Run package-level verification**

```bash
./gradlew :fixthis-mcp:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Inspect final diff**

```bash
git status --short
git diff --stat
git diff --check
```

Expected:

- `git diff --check` exits 0.
- Only files from this plan are modified.
- `fixthis-mcp/src/main/resources/console/app.js` is changed only as the generated bundle of console source edits.

- [ ] **Step 6: Finish verification**

If Step 1-5 made no edits, do not create a verification-only commit. If a defect
was found, return to the task that owns the file, apply the fix there, rerun that
task's focused verification, and use that task's commit command.

## Execution Notes

- Tasks 1 and 2 can run in parallel only if workers avoid editing the same route files. Task 3 depends on Task 1 and Task 2.
- Task 4 depends on Task 3 because boundary save must use captured `sessionId`.
- Task 6 depends on Task 3 because undo context comes from `addItemsFlow.context`.
- Task 7 can run after Task 3. It intentionally reverses the existing test expectation that saved pins render on live preview by `nodeUid`.
- Task 8 can run independently of console JS work, but Task 9 depends on Task 8 if browser tests assert saved server sequence values.
- Keep commits in task order when landing to make regression bisects useful.
