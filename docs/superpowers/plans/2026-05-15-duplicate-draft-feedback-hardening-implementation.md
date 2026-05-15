# Duplicate Draft Feedback Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining duplicate draft feedback gaps after client draft id persistence: partial duplicate screen splitting, no-op event-log growth, and overly broad browser semantic dedupe.

**Architecture:** Keep the primary idempotency key as `clientWorkspaceId + clientDraftItemId`. Reuse an existing persisted screen only when an incoming batch contains a duplicate item from that screen, suppress full-duplicate no-op event writes, and restrict browser semantic dedupe to legacy local recovery payloads.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization, `com.sun.net.httpserver.HttpServer`, JUnit/kotlin.test, vanilla browser JavaScript, Node 20+ test runner, esbuild console bundle.

**Related implementation details:** [`../specs/2026-05-15-duplicate-draft-feedback-hardening-implementation-details.md`](../specs/2026-05-15-duplicate-draft-feedback-hardening-implementation-details.md)

---

## File Structure

- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt` for reusable screen selection, partial duplicate item creation, and narrow legacy semantic matching helpers.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt` to reuse existing screens on partial duplicates and skip event-log writes for full duplicate no-ops.
- Modify `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt` for HTTP-level regression tests.
- Modify `fixthis-mcp/src/main/console/historyPendingDedupe.js` to gate semantic dedupe to legacy recovery only.
- Modify `fixthis-mcp/src/main/console/history.js` to dedupe each recovery candidate with its own workspace id before selecting the newest non-empty recovery.
- Modify `scripts/historyPendingDedupe-test.mjs` for browser dedupe regression tests.
- Regenerate `fixthis-mcp/src/main/resources/console/app.js` and `app.js.map` with `FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs`.

## Task 1: Lock Partial Duplicate Screen Reuse With A Failing Test

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Add the failing partial duplicate test**

Insert this test immediately after `batchItemsApiDoesNotDuplicateSameWorkspaceDraftItemWhenPreviewFallsBackToScreen`:

```kotlin
@Test
fun batchItemsApiReusesExistingScreenForPartialWorkspaceDuplicate() {
    withTempProject("fixthis-console-partial-idempotent-batch") { projectRoot ->
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L).next,
                idGenerator = FakeIds(
                    "session-1",
                    "preview-1",
                    "preview-screen-1",
                    "item-1",
                    "item-2",
                    "item-3",
                    "screen-should-not-be-used",
                ).next,
            ),
            projectRoot = projectRoot.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val preview = runBlocking { service.capturePreview("session-1") }
        val screenJson = fixThisJson.encodeToString(preview.screen)

        withConsoleServer(service) { server ->
            val firstBody = """
                {
                  "workspaceId": "workspace-a",
                  "previewId": "${preview.previewId}",
                  "screen": $screenJson,
                  "items": [
                    {
                      "draftItemId": "draft-1",
                      "targetType": "area",
                      "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                      "comment": "first"
                    },
                    {
                      "draftItemId": "draft-2",
                      "targetType": "area",
                      "bounds": {"left":40.0,"top":50.0,"right":90.0,"bottom":100.0},
                      "comment": "second"
                    }
                  ]
                }
            """.trimIndent()
            val secondBody = """
                {
                  "workspaceId": "workspace-a",
                  "previewId": "${preview.previewId}",
                  "screen": $screenJson,
                  "items": [
                    {
                      "draftItemId": "draft-1",
                      "targetType": "area",
                      "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                      "comment": "first"
                    },
                    {
                      "draftItemId": "draft-2",
                      "targetType": "area",
                      "bounds": {"left":40.0,"top":50.0,"right":90.0,"bottom":100.0},
                      "comment": "second"
                    },
                    {
                      "draftItemId": "draft-3",
                      "targetType": "area",
                      "bounds": {"left":100.0,"top":110.0,"right":150.0,"bottom":160.0},
                      "comment": "third"
                    }
                  ]
                }
            """.trimIndent()

            assertEquals(200, ConsoleHttpTestClient(server.url).postJson("/api/items/batch", firstBody).statusCode)
            assertEquals(200, ConsoleHttpTestClient(server.url).postJson("/api/items/batch", secondBody).statusCode)

            val stored = service.getSession("session-1")
            assertEquals(1, stored.screens.size)
            assertEquals(3, stored.items.size)
            assertEquals(setOf(stored.screens.single().screenId), stored.items.map { it.screenId }.toSet())
            assertEquals(listOf("draft-1", "draft-2", "draft-3"), stored.items.map { it.clientDraftItemId })
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchItemsApiReusesExistingScreenForPartialWorkspaceDuplicate"
```

Expected: FAIL because the stored session has two screens or because `draft-3.screenId` differs from the first persisted screen.

## Task 2: Reuse Existing Screen For Partial Duplicate Batches

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`

- [ ] **Step 1: Add screen reuse helpers**

In `FeedbackSessionStoreDraftDeduplication.kt`, add these helpers below `duplicateScreenFor`:

```kotlin
internal fun screenForIncomingBatch(
    session: SessionDto,
    duplicateItems: List<AnnotationDto>,
    requestedScreen: SnapshotDto,
    idGenerator: () -> String,
    now: Long,
): SnapshotDto {
    val duplicateScreen = duplicateScreenFor(session, duplicateItems, requestedScreen)
    if (duplicateItems.isNotEmpty()) return duplicateScreen
    return requestedScreen.copy(
        screenId = if (requestedScreen.screenId == "pending") idGenerator() else requestedScreen.screenId,
        capturedAtEpochMillis = now,
    )
}

internal fun appendScreenIfMissing(session: SessionDto, screen: SnapshotDto): List<SnapshotDto> =
    if (session.screens.any { it.screenId == screen.screenId }) {
        session.screens
    } else {
        session.screens + screen
    }
```

- [ ] **Step 2: Use the helper in `addScreenWithItems`**

Replace the middle of `FeedbackSessionStoreDelegate.addScreenWithItems` from `val now = clock()` through `val screens = ...` with:

```kotlin
val duplicateItems = matchingClientDraftItems(session, items)
val now = clock()
val captured = screenForIncomingBatch(
    session = session,
    duplicateItems = duplicateItems,
    requestedScreen = screen,
    idGenerator = idGenerator,
    now = now,
)
val createdItems = createScreenItems(session, captured, newItems, now, idGenerator)
val firstSequence = session.migratedNextItemSequenceNumber()
val nextSequence = createdItems.mapNotNull { it.sequenceNumber }.maxOrNull()?.plus(1) ?: firstSequence
val screens = appendScreenIfMissing(session, captured)
```

Keep the `newItems.isEmpty()` branch for now. Task 3 changes its event logging behavior.

- [ ] **Step 3: Run the focused test and verify it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchItemsApiReusesExistingScreenForPartialWorkspaceDuplicate"
```

Expected: PASS.

- [ ] **Step 4: Run existing duplicate test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchItemsApiDoesNotDuplicateSameWorkspaceDraftItemWhenPreviewFallsBackToScreen"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt \
        fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "Fix partial duplicate draft screen reuse"
```

## Task 3: Stop Writing Event-Log Entries For Full Duplicate No-Ops

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Add the failing event-log no-op test**

Add this test after the full duplicate test:

```kotlin
@Test
fun batchItemsApiDoesNotAppendEventForFullWorkspaceDuplicate() {
    withTempProject("fixthis-console-noop-event-log") { projectRoot ->
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L).next,
                idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1", "item-2").next,
                projectRoot = projectRoot.toPath(),
            ),
            projectRoot = projectRoot.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val preview = runBlocking { service.capturePreview("session-1") }
        val screenJson = fixThisJson.encodeToString(preview.screen)
        val body = """
            {
              "workspaceId": "workspace-a",
              "previewId": "${preview.previewId}",
              "screen": $screenJson,
              "items": [{
                "draftItemId": "draft-a",
                "targetType": "area",
                "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                "comment": "save once"
              }]
            }
        """.trimIndent()

        withConsoleServer(service) { server ->
            val client = ConsoleHttpTestClient(server.url)
            assertEquals(200, client.postJson("/api/items/batch", body).statusCode)
            val eventLogBytesAfterFirstSave = Files.walk(projectRoot.toPath())
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().contains("event", ignoreCase = true) }
                .mapToLong { Files.size(it) }
                .sum()

            assertEquals(200, client.postJson("/api/items/batch", body).statusCode)
            val eventLogBytesAfterDuplicateSave = Files.walk(projectRoot.toPath())
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().contains("event", ignoreCase = true) }
                .mapToLong { Files.size(it) }
                .sum()

            assertEquals(eventLogBytesAfterFirstSave, eventLogBytesAfterDuplicateSave)
        }
    }
}
```

If `FeedbackSessionStore` does not accept `projectRoot` in this exact constructor in the current checkout, use the existing event-log fixture from `FeedbackSessionStoreEventLogTest` and keep the same assertion: duplicate POST must not increase event-log bytes.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchItemsApiDoesNotAppendEventForFullWorkspaceDuplicate"
```

Expected: FAIL because the duplicate request appends an `addScreenWithItems` event.

- [ ] **Step 3: Add nullable event helper**

In `FeedbackSessionStoreDelegate.kt`, add near the event-log helper section:

```kotlin
private data class EventBackedMutation<T>(
    val payload: JsonObject,
    val mutate: () -> T,
)

private fun <T> withOptionalEventBackedMutation(
    sessionId: String,
    type: String,
    prepare: () -> EventBackedMutation<T>?,
    noop: () -> T,
): T {
    val result = synchronized(lock) {
        val prepared = prepare() ?: return@synchronized noop()
        journal.append(sessionId = sessionId, type = type, payload = prepared.payload)
        prepared.mutate()
    }
    compactEventLogAfterMutation(sessionId)
    return result
}
```

- [ ] **Step 4: Route `addScreenWithItems` through the nullable helper**

Change the function signature line from:

```kotlin
): SessionDto = withEventBackedMutation(sessionId, "addScreenWithItems") {
```

to:

```kotlin
): SessionDto = withOptionalEventBackedMutation(
    sessionId = sessionId,
    type = "addScreenWithItems",
    noop = { getSessionLocked(sessionId) },
) {
```

In the `newItems.isEmpty()` branch, replace the current payload return with:

```kotlin
return@withOptionalEventBackedMutation null
```

For the non-no-op return, replace:

```kotlin
addScreenWithItemsPayload(...) to {
    commitSessionMutation(session, updated)
}
```

with:

```kotlin
EventBackedMutation(
    payload = addScreenWithItemsPayload(
        sessionId = sessionId,
        eventMetadata = eventMetadata,
        screen = captured,
        items = createdItems,
    ),
    mutate = { commitSessionMutation(session, updated) },
)
```

- [ ] **Step 5: Run the no-op event test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchItemsApiDoesNotAppendEventForFullWorkspaceDuplicate"
```

Expected: PASS.

- [ ] **Step 6: Run route regression tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchItemsApi*Workspace*"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "Skip event log writes for duplicate draft retries"
```

## Task 4: Restrict Browser Semantic Dedupe To Legacy Recovery

**Files:**
- Modify: `scripts/historyPendingDedupe-test.mjs`
- Modify: `fixthis-mcp/src/main/console/historyPendingDedupe.js`

- [ ] **Step 1: Add failing JavaScript tests**

Append these tests to `scripts/historyPendingDedupe-test.mjs`:

```js
test('history pending items keep new draft with same semantic target when client key differs', () => {
  m.setState({
    session: {
      sessionId: 'session-a',
      items: [{
        clientWorkspaceId: 'ws-a',
        clientDraftItemId: 'draft-1',
        target: { type: 'visual_area', boundsInWindow: bounds },
        comment: 'Fix label',
      }],
    },
  });

  const pendingItem = { draftItemId: 'draft-2', targetType: 'area', bounds, comment: 'Fix label' };
  const pending = m.dedupePendingHistoryItemsForSession({ sessionId: 'session-a' }, [pendingItem], 'ws-a');

  assert.deepEqual(pending, [pendingItem]);
});

test('history pending items keep legacy pin-only item even when bounds match', () => {
  m.setState({
    session: {
      sessionId: 'session-a',
      items: [{
        target: { type: 'visual_area', boundsInWindow: bounds },
        comment: '',
      }],
    },
  });

  const pendingItem = { targetType: 'area', bounds, comment: '' };
  const pending = m.dedupePendingHistoryItemsForSession({ sessionId: 'session-a' }, [pendingItem], null);

  assert.deepEqual(pending, [pendingItem]);
});
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```bash
node --test scripts/historyPendingDedupe-test.mjs
```

Expected: FAIL because current semantic fallback hides the same-target/same-comment client-key mismatch.

- [ ] **Step 3: Gate semantic fallback**

Modify `dedupePendingHistoryItemsForSession` in `historyPendingDedupe.js`:

```js
function hasLegacySemanticDedupeKey(item, workspaceId) {
  if (pendingClientDraftKey(item, workspaceId)) return false;
  return Boolean(String(item?.comment || '').trim());
}

function dedupePendingHistoryItemsForSession(session, pendingItems, workspaceId) {
  const persisted = persistedItemsForHistoryDedupe(session);
  if (!persisted.length) return pendingItems || [];
  const persistedClientKeys = new Set(persisted.map(persistedClientDraftKey).filter(Boolean));
  const persistedSemanticKeys = new Set(
    persisted
      .filter(item => String(item?.comment || '').trim())
      .map(historyItemSemanticKey)
      .filter(Boolean)
  );
  return (pendingItems || []).filter((item) => {
    const clientKey = pendingClientDraftKey(item, workspaceId);
    if (clientKey) return !persistedClientKeys.has(clientKey);
    if (!hasLegacySemanticDedupeKey(item, workspaceId)) return true;
    return !persistedSemanticKeys.has(historyItemSemanticKey(item));
  });
}
```

Update `normalizedHistoryBounds` to integer rounding:

```js
function normalizedHistoryBounds(bounds) {
  if (!bounds) return '';
  return ['left', 'top', 'right', 'bottom']
    .map((key) => String(Math.round(Number(bounds[key] || 0))))
    .join(',');
}
```

- [ ] **Step 4: Run JavaScript tests**

Run:

```bash
node --test scripts/historyPendingDedupe-test.mjs
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/historyPendingDedupe.js scripts/historyPendingDedupe-test.mjs
git commit -m "Limit history semantic dedupe to legacy recovery"
```

## Task 5: Dedupe Recovery Candidates With Their Own Workspace IDs

**Files:**
- Modify: `scripts/historyPendingDedupe-test.mjs`
- Modify: `fixthis-mcp/src/main/console/historyPendingDedupe.js`
- Modify: `fixthis-mcp/src/main/console/history.js`

- [ ] **Step 1: Add a pure helper test**

Extend the `factory` return in `scripts/historyPendingDedupe-test.mjs`:

```js
return {
  setState(next) { state = next; },
  dedupePendingHistoryItemsForSession,
  newestDedupedHistoryRecoveryItems
};
```

Add this test:

```js
test('history recovery chooses newest non-empty candidate after per-workspace dedupe', () => {
  m.setState({
    session: {
      sessionId: 'session-a',
      items: [{
        clientWorkspaceId: 'ws-new',
        clientDraftItemId: 'draft-saved',
        target: { type: 'visual_area', boundsInWindow: bounds },
        comment: 'saved',
      }],
    },
  });

  const olderUnsaved = {
    workspaceId: 'ws-old',
    updatedAtEpochMillis: 100,
    items: [{ draftItemId: 'draft-unsaved', targetType: 'area', bounds, comment: '' }],
  };
  const newerDuplicate = {
    workspaceId: 'ws-new',
    updatedAtEpochMillis: 200,
    items: [{ draftItemId: 'draft-saved', targetType: 'area', bounds, comment: 'saved' }],
  };

  const items = m.newestDedupedHistoryRecoveryItems(
    { sessionId: 'session-a' },
    [olderUnsaved, newerDuplicate],
  );

  assert.deepEqual(items, olderUnsaved.items);
});
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
node --test scripts/historyPendingDedupe-test.mjs
```

Expected: FAIL because `newestDedupedHistoryRecoveryItems` does not exist.

- [ ] **Step 3: Implement the helper**

Add to `historyPendingDedupe.js`:

```js
function newestDedupedHistoryRecoveryItems(session, recoveryCandidates) {
  return [...(recoveryCandidates || [])]
    .map((workspace) => ({
      workspace,
      items: dedupePendingHistoryItemsForSession(
        session,
        Array.isArray(workspace?.items) ? workspace.items : [],
        workspace?.workspaceId || null
      ),
    }))
    .filter((candidate) => candidate.items.length)
    .sort((left, right) =>
      (left.workspace?.updatedAtEpochMillis || 0) - (right.workspace?.updatedAtEpochMillis || 0)
    )
    .at(-1)?.items || [];
}
```

- [ ] **Step 4: Use it from history recovery**

In `history.js`, replace:

```js
const recovery = newestHistoryRecovery(stored.concat(legacy ? [legacy] : []));
return dedupePendingHistoryItemsForSession(session, draftItemsFromValue(recovery), recovery?.workspaceId || null);
```

with:

```js
return newestDedupedHistoryRecoveryItems(session, stored.concat(legacy ? [legacy] : []));
```

If `newestHistoryRecovery` becomes unused, remove it from `history.js`.

- [ ] **Step 5: Run tests**

Run:

```bash
node --test scripts/historyPendingDedupe-test.mjs
npm run console:draft:test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/historyPendingDedupe.js \
        fixthis-mcp/src/main/console/history.js \
        scripts/historyPendingDedupe-test.mjs
git commit -m "Dedupe history recovery by workspace"
```

## Task 6: Add Narrow Legacy Server Dedupe

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Add failing legacy server dedupe test**

Add this test after the partial duplicate test:

```kotlin
@Test
fun batchItemsApiDedupeLegacyServerItemWithSameTargetAndComment() {
    withTempProject("fixthis-console-legacy-idempotent-batch") { projectRoot ->
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L).next,
                idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "legacy-item", "new-item").next,
            ),
            projectRoot = projectRoot.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val preview = runBlocking { service.capturePreview("session-1") }
        val legacy = AnnotationDto(
            itemId = "pending",
            screenId = preview.screen.screenId,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 30f, 40f)),
            comment = "legacy saved comment",
        )
        service.savePreviewFeedbackItems(
            sessionId = "session-1",
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(1f, 2f, 30f, 40f),
                    comment = legacy.comment,
                ),
            ),
        )
        val screenJson = fixThisJson.encodeToString(preview.screen)

        withConsoleServer(service) { server ->
            val body = """
                {
                  "workspaceId": "workspace-a",
                  "previewId": "${preview.previewId}",
                  "screen": $screenJson,
                  "items": [{
                    "draftItemId": "draft-a",
                    "targetType": "area",
                    "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                    "comment": "legacy saved comment"
                  }]
                }
            """.trimIndent()

            assertEquals(200, ConsoleHttpTestClient(server.url).postJson("/api/items/batch", body).statusCode)
            val stored = service.getSession("session-1")
            assertEquals(1, stored.items.size)
            assertEquals(1, stored.screens.size)
        }
    }
}
```

If the direct `savePreviewFeedbackItems` call sets client ids in this checkout, create the legacy item by calling `store.addScreenWithItems` directly with an `AnnotationDto` whose `clientWorkspaceId/clientDraftItemId` are null.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchItemsApiDedupeLegacyServerItemWithSameTargetAndComment"
```

Expected: FAIL because the server appends the incoming keyed item next to the legacy unkeyed item.

- [ ] **Step 3: Implement narrow semantic helpers**

Add to `FeedbackSessionStoreDraftDeduplication.kt`:

```kotlin
internal fun AnnotationDto.legacySemanticDraftKey(): String? {
    if (clientDraftKey() != null) return null
    val commentKey = comment.trim().takeIf { it.isNotEmpty() } ?: return null
    return "${target.semanticTypeKey()}\u0000${target.semanticNodeKey()}\u0000${target.semanticBoundsKey()}\u0000$commentKey"
}

internal fun incomingSemanticDraftKey(item: AnnotationDto): String? {
    if (item.clientDraftKey() == null) return null
    val commentKey = item.comment.trim().takeIf { it.isNotEmpty() } ?: return null
    return "${item.target.semanticTypeKey()}\u0000${item.target.semanticNodeKey()}\u0000${item.target.semanticBoundsKey()}\u0000$commentKey"
}

private fun AnnotationTargetDto.semanticTypeKey(): String = when (this) {
    is AnnotationTargetDto.Node -> "node"
    is AnnotationTargetDto.Area -> "area"
}

private fun AnnotationTargetDto.semanticNodeKey(): String = when (this) {
    is AnnotationTargetDto.Node -> nodeUid
    is AnnotationTargetDto.Area -> ""
}

private fun AnnotationTargetDto.semanticBoundsKey(): String {
    val bounds = when (this) {
        is AnnotationTargetDto.Node -> boundsInWindow
        is AnnotationTargetDto.Area -> boundsInWindow
    }
    return listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        .joinToString(",") { kotlin.math.round(it).toInt().toString() }
}

internal fun existingLegacySemanticKeysForScreen(session: SessionDto, requestedScreen: SnapshotDto): Set<String> {
    val candidateScreenIds = buildSet {
        add(requestedScreen.screenId)
        session.screens.find { it.fingerprint != null && it.fingerprint == requestedScreen.fingerprint }?.let { add(it.screenId) }
    }
    return session.items
        .filter { item -> candidateScreenIds.isEmpty() || item.screenId in candidateScreenIds }
        .mapNotNull { it.legacySemanticDraftKey() }
        .toSet()
}
```

- [ ] **Step 4: Apply semantic filtering after client-key filtering**

In `FeedbackSessionStoreDelegate.addScreenWithItems`, after `existingClientKeys`, add:

```kotlin
val existingLegacySemanticKeys = existingLegacySemanticKeysForScreen(session, screen)
```

Change `newItems` to:

```kotlin
val newItems = items.filterNot { item ->
    val clientDuplicate = item.clientDraftKey()?.let { it in existingClientKeys } == true
    val legacyDuplicate = incomingSemanticDraftKey(item)?.let { it in existingLegacySemanticKeys } == true
    clientDuplicate || legacyDuplicate
}
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchItemsApiDedupeLegacyServerItemWithSameTargetAndComment" \
  --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.batchItemsApiReusesExistingScreenForPartialWorkspaceDuplicate"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDraftDeduplication.kt \
        fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "Add conservative legacy draft dedupe"
```

## Task 7: Rebuild Console Bundle And Run Full Verification

**Files:**
- Modify generated: `fixthis-mcp/src/main/resources/console/app.js`
- Modify generated: `fixthis-mcp/src/main/resources/console/app.js.map`

- [ ] **Step 1: Rebuild console assets reproducibly**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
```

Expected: no output and exit code 0.

- [ ] **Step 2: Check bundle freshness and syntax**

Run:

```bash
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: both commands exit 0.

- [ ] **Step 3: Run console draft tests**

Run:

```bash
npm run console:draft:test
```

Expected: all console draft tests pass, including `historyPendingDedupe-test.mjs`.

- [ ] **Step 4: Run MCP test suite**

Run:

```bash
./gradlew :fixthis-mcp:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Check diff hygiene**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` has no output. `git status --short` shows only intended source, test, and generated console bundle files.

- [ ] **Step 6: Commit generated bundle and final verification**

```bash
git add fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/main/resources/console/app.js.map
git commit -m "Rebuild console bundle for draft dedupe hardening"
```

## Self-Review Checklist

- [ ] Partial duplicate batches reuse existing persisted screen ids.
- [ ] Full duplicate batches return 200 without appending event-log entries.
- [ ] Browser semantic dedupe is not applied to keyed pending drafts.
- [ ] Browser semantic dedupe ignores empty-comment legacy pins.
- [ ] Recovery candidates are deduped per-envelope before newest selection.
- [ ] Conservative server legacy dedupe has tests and does not dedupe blank comments.
- [ ] `npm run console:draft:test` passes.
- [ ] `./gradlew :fixthis-mcp:test` passes.
