# Console Session Preview Sync Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining session/preview/annotation sync gaps in the FixThis feedback console so stale async events, explicit-session CRUD, partially commented drafts, and deleted-session local recovery cannot drift from the visible UI.

**Architecture:** Keep the existing draft workspace and preview fingerprint architecture. Add narrow session fences at SSE emit/apply boundaries, correct one legacy route emission source, align prompt persistence with existing written-comment-only draft save behavior, and add browser-local recovery cleanup for deleted sessions.

**Tech Stack:** Kotlin/JVM 21, `com.sun.net.httpserver.HttpServer`, kotlinx.serialization JSON, vanilla browser JavaScript, Node 20 `node:test`, Gradle/kotlin.test.

**Related spec:** [`../specs/2026-05-15-console-session-preview-sync-hardening-detailed-spec.md`](../specs/2026-05-15-console-session-preview-sync-hardening-detailed-spec.md)

---

## File Structure

- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEventEmitters.kt` to include top-level `sessionId` in session events and add `emitPreviewReady`.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/PreviewRoutes.kt` to emit session-scoped preview events.
- Modify `fixthis-mcp/src/main/console/events.js` to ignore session and preview events that do not match the active session.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt` so legacy `POST /api/items` emits the mutated explicit session.
- Modify `fixthis-mcp/src/main/console/prompt.js` so written-comment-only prompt actions do not reject residual pin-only items.
- Modify `fixthis-mcp/src/main/console/draftStorageAdapter.js` to delete all stored draft workspaces for a session.
- Modify `fixthis-mcp/src/main/console/history.js` to delete local recovery for closed/deleted sessions.
- Modify Node tests under `scripts/` for event scoping, partial prompt persistence, and deleted-session storage cleanup.
- Modify Kotlin route tests under `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/` for server-side event payload and explicit-session emission.

## Task 1: Session-Scope SSE Session And Preview Events

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEventEmitters.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/PreviewRoutes.kt`
- Modify: `fixthis-mcp/src/main/console/events.js`
- Test: `scripts/consoleEvents-test.mjs`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt`

- [ ] **Step 1: Add browser contract tests for session-scoped event handling**

Update `scripts/consoleEvents-test.mjs` with source-level checks that fail on
the current unscoped handlers:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const eventsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/events.js'), 'utf8');

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

test('session-updated event is fenced to active session before replacing detail state', () => {
  const start = body(eventsSource, 'function startConsoleEvents()');
  assert.match(start, /data\.sessionId/);
  assert.match(start, /state\.session\?\.sessionId/);
  assert.match(start, /data\.sessionId === state\.session\?\.sessionId/);
  assert.doesNotMatch(start, /on\('session-updated'[\s\S]*?setConsoleSession\(data\.session\);[\s\S]*?loadPendingRecoveryForCurrentSession\(\);/);
});

test('preview-ready event is ignored when its session does not match active session', () => {
  const start = body(eventsSource, 'function startConsoleEvents()');
  assert.match(start, /on\('preview-ready'/);
  assert.match(start, /data\.sessionId !== state\.session\?\.sessionId/);
  assert.match(start, /return;/);
});
```

- [ ] **Step 2: Run the browser event contract test and verify it fails**

Run:

```bash
node --test scripts/consoleEvents-test.mjs
```

Expected: FAIL because `events.js` currently applies `session-updated` and
`preview-ready` without a session fence.

- [ ] **Step 3: Add Kotlin route/event tests for top-level session IDs**

Extend `ConsoleEventsRoutesTest.kt` with a test that subscribes to the event
bus, captures a preview, and verifies the emitted `preview-ready` event carries
`sessionId`.

```kotlin
@Test
fun previewReadyEventIncludesSessionId() {
    val fixture = newConsoleSessionFixtureWithTempRoot(
        idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
    )
    val bus = ConsoleEventBus(clock = { 1L })
    val seen = LinkedBlockingQueue<ConsoleEvent>()
    bus.subscribe { event -> seen += event }
    val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
    try {
        fixture.service.openSession(null, newSession = true)
        server.start()

        get(server.url, "/api/preview")

        val previewEvent = generateSequence { seen.poll(1, TimeUnit.SECONDS) }
            .first { it.name == "preview-ready" }
        assertEquals("session-1", previewEvent.data.getValue("sessionId").jsonPrimitive.content)
    } finally {
        server.stop()
        fixture.close()
    }
}
```

Add these imports:

```kotlin
import io.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import kotlinx.serialization.json.content
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 4: Run the focused Kotlin event test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleEventsRoutesTest.previewReadyEventIncludesSessionId" --no-daemon
```

Expected: FAIL because `preview-ready` currently has no top-level
`sessionId`.

- [ ] **Step 5: Implement session-scoped emitters**

Update `ConsoleEventEmitters.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.session.SessionDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun ConsoleEventBus.emitSessionUpdated(session: SessionDto) {
    emit(
        "session-updated",
        buildJsonObject {
            put("sessionId", session.sessionId)
            put("session", fixThisJson.encodeToJsonElement(SessionDto.serializer(), session).jsonObject)
        },
    )
    emit(
        "sessions-updated",
        buildJsonObject {
            put("sessionId", session.sessionId)
        },
    )
}

internal fun ConsoleEventBus.emitPreviewReady(
    sessionId: String,
    preview: FeedbackPreviewSnapshot,
) {
    emit(
        "preview-ready",
        buildJsonObject {
            put("sessionId", sessionId)
            put(
                "preview",
                fixThisJson.encodeToJsonElement(
                    FeedbackPreviewSnapshot.serializer(),
                    preview,
                ).jsonObject,
            )
        },
    )
}
```

Update `PreviewRoutes.kt` in `handlePreviewCapture()`:

```kotlin
private fun HttpExchange.handlePreviewCapture() = requireMethod("GET") {
    val session = service.requireCurrentSession()
    val preview = runBlocking { service.capturePreview(session.sessionId) }
    eventBus.emitPreviewReady(session.sessionId, preview)
    sendJson(200, preview)
}
```

- [ ] **Step 6: Implement browser-side event fences**

Update `events.js` inside `startConsoleEvents()`:

```js
const activeSessionId = () => state.session?.sessionId || null;
const matchesActiveSession = (data) => Boolean(data?.sessionId && data.sessionId === activeSessionId());

on('session-updated', (data) => {
  if (!data.session) return;
  if (activeSessionId() && !matchesActiveSession(data)) {
    refreshSessions().catch(showError);
    return;
  }
  setConsoleSession(data.session);
  loadPendingRecoveryForCurrentSession();
  render();
});

on('preview-ready', (data) => {
  if (!data.preview || draftFlow()) return;
  if (!matchesActiveSession(data)) return;
  setConsolePreview({
    ...data.preview,
    activity: state.connection?.availability?.activity ?? null,
    frozenAtEpochMillis: Date.now(),
    stale: false,
  });
  renderPreviewOnly();
});
```

Keep the `snapshot` handler unchanged except for any formatting needed by the
file.

- [ ] **Step 7: Verify Task 1**

Run:

```bash
node --test scripts/consoleEvents-test.mjs
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleEventsRoutesTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 8: Commit Task 1**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEventEmitters.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/PreviewRoutes.kt \
  fixthis-mcp/src/main/console/events.js \
  scripts/consoleEvents-test.mjs \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt
git commit -m "fix(console): fence SSE updates by active session"
```

## Task 2: Emit The Mutated Session From Legacy Item Creation

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Add a failing explicit-session event emission test**

Add this test to `ConsoleFeedbackItemRoutesTest.kt`. Reuse existing helpers in
that file for creating screens/items. The core assertion is that the emitted
session is the request session, not the current session.

```kotlin
@Test
fun addItemEmitsExplicitRequestSessionWhenCurrentSessionDiffers() {
    val fixture = newConsoleSessionFixtureWithTempRoot(
        idGenerator = FakeIds("session-a", "screen-a", "session-b", "item-a").next,
    )
    val bus = ConsoleEventBus(clock = { 1L })
    val seen = LinkedBlockingQueue<ConsoleEvent>()
    bus.subscribe { event -> seen += event }
    val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
    try {
        val sessionA = fixture.service.openSession(null, newSession = true)
        val screenA = runBlocking { fixture.service.captureScreen(sessionA.sessionId) }
        fixture.service.openSession(null, newSession = true)
        server.start()

        val response = ConsoleHttpTestClient(server.url).postJson(
            "/api/items",
            """
            {
              "sessionId": "${sessionA.sessionId}",
              "screenId": "${screenA.screenId}",
              "targetType": "area",
              "bounds": { "left": 1, "top": 2, "right": 30, "bottom": 40 },
              "comment": "Explicit session item"
            }
            """.trimIndent(),
        )
        assertEquals(200, response.statusCode, response.body)

        val event = generateSequence { seen.poll(1, TimeUnit.SECONDS) }
            .first { it.name == "session-updated" }
        assertEquals(sessionA.sessionId, event.data.getValue("sessionId").jsonPrimitive.content)
    } finally {
        server.stop()
        fixture.close()
    }
}
```

Required imports:

```kotlin
import io.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import io.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixtureWithTempRoot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.content
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
```

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.addItemEmitsExplicitRequestSessionWhenCurrentSessionDiffers" --no-daemon
```

Expected: FAIL because `POST /api/items` emits `requireCurrentSession()`.

- [ ] **Step 3: Emit `service.getSession(sessionId)` after legacy item creation**

Change the `/api/items` branch in `FeedbackItemRoutes.kt`:

```kotlin
val item = try {
    runBlocking {
        service.addFeedbackItem(
            sessionId = sessionId,
            screenId = request.screenId,
            targetType = request.targetType,
            bounds = request.bounds,
            nodeUid = request.nodeUid,
            comment = request.comment,
        )
    }
} catch (error: IllegalArgumentException) {
    throw FeedbackConsoleHttpException(400, error.message ?: "Invalid feedback item request")
}
eventBus.emitSessionUpdated(service.getSession(sessionId))
exchange.sendJson(200, item)
```

- [ ] **Step 4: Verify Task 2**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.addItemEmitsExplicitRequestSessionWhenCurrentSessionDiffers" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "fix(console): emit explicit session after legacy item create"
```

## Task 3: Align Prompt Actions With Partially Commented Drafts

**Files:**
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Test: `scripts/pendingItemRecovery-test.mjs`

- [ ] **Step 1: Add a failing contract test for partial draft prompt persistence**

Append this test to `scripts/pendingItemRecovery-test.mjs`:

```js
test('prompt persistence allows residual pin-only items when written comments exist', () => {
  const promptSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/prompt.js'), 'utf8');
  const persistCollectBody = extractFunctionBody(promptSource, 'async function persistAndCollectItemIds(options = {})');
  assert.doesNotMatch(
    persistCollectBody,
    /draftItemList\(\)\.some\(item => !hasWrittenAnnotationComment\(item\)\)[\s\S]*?throw new Error\('Add a comment to every annotation before saving\.'\);/,
  );
  assert.match(
    persistCollectBody,
    /const writtenDraftItems = commentedDraftItems\(draftItemList\(\)\);[\s\S]*?if \(writtenDraftItems\.length === 0\)/,
  );
  assert.match(
    persistCollectBody,
    /persistPendingFeedbackItems\(\{[\s\S]*?onlyWrittenComments: true[\s\S]*?keepResidualDraftActive: options\.keepResidualDraftActive !== false[\s\S]*?\}\);/,
  );
});
```

- [ ] **Step 2: Run the test and verify failure**

Run:

```bash
node --test scripts/pendingItemRecovery-test.mjs
```

Expected: FAIL because `persistAndCollectItemIds()` currently rejects when any
draft item lacks a comment.

- [ ] **Step 3: Remove the contradictory all-comments preflight**

In `prompt.js`, change the draft block inside `persistAndCollectItemIds()` from
this shape:

```js
if (draftFlow()) {
    flushFocusedPendingComment();
    const writtenDraftItems = commentedDraftItems(draftItemList());
    if (draftItemList().some(item => !hasWrittenAnnotationComment(item))) {
        throw new Error('Add a comment to every annotation before saving.');
    }
    if (writtenDraftItems.length === 0) {
        throw new Error('Add a comment to at least one annotation before sending.');
    }
    await persistPendingFeedbackItems({
        onlyWrittenComments: true,
        keepResidualDraftActive: options.keepResidualDraftActive !== false,
    });
}
```

to:

```js
if (draftFlow()) {
    flushFocusedPendingComment();
    const writtenDraftItems = commentedDraftItems(draftItemList());
    if (writtenDraftItems.length === 0) {
        throw new Error('Add a comment to at least one annotation before sending.');
    }
    await persistPendingFeedbackItems({
        onlyWrittenComments: true,
        keepResidualDraftActive: options.keepResidualDraftActive !== false,
    });
}
```

- [ ] **Step 4: Verify Task 3**

Run:

```bash
node --test scripts/pendingItemRecovery-test.mjs
npm run console:pending:test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add fixthis-mcp/src/main/console/prompt.js scripts/pendingItemRecovery-test.mjs
git commit -m "fix(console): allow prompt handoff for commented draft subset"
```

## Task 4: Clear Local Draft Recovery When A Session Is Deleted

**Files:**
- Modify: `fixthis-mcp/src/main/console/draftStorageAdapter.js`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Test: `scripts/draftStorageAdapter-test.mjs`
- Test: `scripts/pendingBoundaryGuard-test.mjs`

- [ ] **Step 1: Add storage adapter test for deleting all workspaces in a session**

Append this test to `scripts/draftStorageAdapter-test.mjs`:

```js
test('deleteWorkspacesForSession removes every indexed workspace and the index', () => {
  const storage = new Map();
  const localStorageLike = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: (key) => storage.delete(key),
  };
  const adapter = m.createDraftStorageAdapter(localStorageLike);
  adapter.saveWorkspace({
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'workspace-1',
    context: { sessionId: 'session-a', previewId: 'preview-a' },
    items: [{ draftItemId: 'draft-1', comment: 'one' }],
  });
  adapter.saveWorkspace({
    schemaVersion: 2,
    sessionId: 'session-a',
    workspaceId: 'workspace-2',
    context: { sessionId: 'session-a', previewId: 'preview-b' },
    items: [{ draftItemId: 'draft-2', comment: 'two' }],
  });

  adapter.deleteWorkspacesForSession('session-a');

  assert.equal(storage.get('fixthis.workspace.session-a.workspace-1'), undefined);
  assert.equal(storage.get('fixthis.workspace.session-a.workspace-2'), undefined);
  assert.equal(storage.get('fixthis.workspace.index.session-a'), undefined);
});
```

- [ ] **Step 2: Add history contract test for cleanup call**

Append this test to `scripts/pendingBoundaryGuard-test.mjs`:

```js
test('deleteHistorySession clears local recovery for the deleted session', () => {
  const deleteBody = body(historySource, 'async function deleteHistorySession(sessionId)');
  assert.match(deleteBody, /deleteWorkspacesForSession\(sessionId\)/);
  assert.match(deleteBody, /clearPendingMirror\(sessionId\)/);
  assert.match(deleteBody, /activePendingMirrorSessions\.delete\(sessionId\)/);
});
```

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```bash
node --test scripts/draftStorageAdapter-test.mjs scripts/pendingBoundaryGuard-test.mjs
```

Expected: FAIL because `deleteWorkspacesForSession` does not exist and
`deleteHistorySession` does not call it.

- [ ] **Step 4: Implement `deleteWorkspacesForSession`**

Update `draftStorageAdapter.js`:

```js
function deleteWorkspacesForSession(sessionId) {
  if (!sessionId) return;
  readIndex(sessionId).forEach((workspaceId) => {
    localStorageLike.removeItem(draftWorkspaceKey(sessionId, workspaceId));
  });
  localStorageLike.removeItem(draftWorkspaceIndexKey(sessionId));
}
```

Return the method:

```js
return {
  saveWorkspace,
  loadWorkspacesForSession,
  deleteWorkspace,
  deleteWorkspacesForSession,
  migrateLegacyPending,
};
```

- [ ] **Step 5: Call cleanup after successful session deletion**

Update `deleteHistorySession(sessionId)` in `history.js` after the server
mutation succeeds:

```js
await withMutationLock(() => requestJson('/api/session/close', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ sessionId: sessionId })
}));
createBrowserDraftPorts().storage.deleteWorkspacesForSession(sessionId);
clearPendingMirror(sessionId);
activePendingMirrorSessions.delete(sessionId);
if (wasDisplayedSession) {
  resetComposer();
  clearPreview();
  setConsoleSession(null);
}
```

- [ ] **Step 6: Verify Task 4**

Run:

```bash
node --test scripts/draftStorageAdapter-test.mjs scripts/pendingBoundaryGuard-test.mjs
npm run console:session:test
npm run console:draft:test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 4**

```bash
git add fixthis-mcp/src/main/console/draftStorageAdapter.js \
  fixthis-mcp/src/main/console/history.js \
  scripts/draftStorageAdapter-test.mjs \
  scripts/pendingBoundaryGuard-test.mjs
git commit -m "fix(console): clear local drafts when deleting sessions"
```

## Task 5: End-To-End Verification And Documentation Contract

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`

- [ ] **Step 1: Document the new sync contract**

Add these bullets under the storage/session consistency section of
`docs/reference/feedback-console-contract.md`:

```markdown
- Server-sent `session-updated` and `preview-ready` events carry top-level
  `sessionId`. The browser applies them to detail/preview state only when that
  session is currently active, except for the initial `snapshot` event.
- Deleting a feedback session clears browser-local draft recovery for that
  session, including schema-v2 workspace entries and the legacy
  `fixthis.pending.<sessionId>` mirror.
- Copy Prompt / Save to MCP can persist the subset of pending annotations that
  have written comments; pin-only residual annotations remain local only when
  the initiating action keeps residual drafts active.
```

- [ ] **Step 2: Ensure new Node tests are included in console suites**

If Task 1 or Task 4 added a new standalone script, update
`scripts/console-tests.json`. In the expected implementation above, existing
scripts are modified, so no suite map change is required.

- [ ] **Step 3: Run the full focused verification set**

Run:

```bash
npm run console:session:test
npm run console:pending:test
npm run console:draft:test
npm run console:fsm:test
./gradlew :fixthis-mcp:test --no-daemon
```

Expected: all tests PASS.

- [ ] **Step 4: Rebuild console assets check**

Run:

```bash
node scripts/build-console-assets.mjs --check
```

Expected: PASS with no generated asset drift.

If the check reports generated asset drift, run:

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

Expected: second command PASS.

- [ ] **Step 5: Commit Task 5**

```bash
git add docs/reference/feedback-console-contract.md \
  fixthis-mcp/src/main/resources/console/app.js \
  fixthis-mcp/src/main/resources/console/app.js.map \
  fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "docs(console): record session preview sync contract"
```

Only include generated console resources in this commit if Step 4 required a
rebuild and `git diff -- fixthis-mcp/src/main/resources/console` shows real
asset changes from the source edits.

## Final Review Checklist

- [ ] `events.js` never calls `setConsoleSession(data.session)` for a
  non-active session event.
- [ ] `events.js` never calls `setConsolePreview(...)` for a non-active session
  preview event.
- [ ] `PreviewRoutes.kt` emits `preview-ready` with top-level `sessionId`.
- [ ] `FeedbackItemRoutes.kt` emits the explicitly mutated session for
  `POST /api/items`.
- [ ] `prompt.js` allows a commented subset to persist through
  `onlyWrittenComments`.
- [ ] `draftStorageAdapter.js` can delete all workspaces for a session.
- [ ] `history.js` clears schema-v2 and legacy local draft recovery after
  deleting a session.
- [ ] `feedback-console-contract.md` documents the new behavior.
- [ ] Full focused verification passes.
