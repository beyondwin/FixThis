# MCP Handoff Workflow Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the FixThis MCP agent handoff loop end-to-end (claim/resolve via MCP, live-reflected in the browser console) and remove the now-redundant Sent History UI drawer.

**Architecture:** Four phases. (A) Backend gains `claimFeedback` store method, `fixthis_claim_feedback` MCP tool, focused default for list/read, and a per-item `id:` token plus `agent_protocol:` footer in the compact handoff renderer. (B) `/api/sessions` and `/api/session` start emitting `ETag` headers and honoring `If-None-Match` (304). (C) Console drops the Sent History drawer, removes the `delivery !== 'sent'` filter (4 sites) and the `ready_for_agent` session filter, adds a `working` pip, polls sessions every 2s, and reflects status changes with a brief highlight. (D) Bundled assets are rebuilt and docs are updated. Each phase is independently shippable; backend is forward-compatible with the unchanged old console.

**Tech Stack:** Kotlin 1.9.x, Gradle multi-module, kotlinx.serialization, JUnit 5, `com.sun.net.httpserver` (no Ktor); vanilla ES module JS console concatenated by `scripts/build-console-assets.mjs`; tests assert console behavior by string-inspecting the served HTML.

**Reference spec:** `docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md`

---

## File Structure

### Phase A — Backend agent loop

**Modify:**
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` — add `claimFeedback`
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt` — delegate `claimFeedback` to store
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt` — register `fixthis_claim_feedback`, tweak resolve description, add `includeAll` to list/read
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` — per-item `id:` token + `agent_protocol:` footer

**Test (modify):**
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

### Phase B — ETag polling infrastructure

**Modify:**
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionSummary.kt` — add `inProgressItemsCount`
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttp.kt` — add `ifNoneMatch`, `sendNotModified`, `sendJsonWithEtag`
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/SessionRoutes.kt` — emit ETag and honor If-None-Match on GET routes

**Test (modify):**
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

### Phase C — Console UI removal + polling client

**Create:**
- `fixthis-mcp/src/main/console/sessions-polling.js`

**Modify:**
- `scripts/build-console-assets.mjs` — register the new module
- `fixthis-mcp/src/main/resources/console/index.html` — remove drawer block
- `fixthis-mcp/src/main/resources/console/styles.css` — remove drawer styles, add `.hi-pip.working`, `.ann-row.is-just-changed`
- `fixthis-mcp/src/main/console/state.js` — remove drawer DOM refs, add `pendingMutationCount`, `lastSessionsEtag`, `lastSessionEtag`
- `fixthis-mcp/src/main/console/main.js` — remove drawer listener, start sessions polling, react to visibilitychange
- `fixthis-mcp/src/main/console/history.js` — remove `sentHistorySummaries`/`renderSentHistory`/`clearSentHistory`, drop `ready_for_agent` filters, add `working` pip
- `fixthis-mcp/src/main/console/rendering.js` — remove `renderSentHistory()` call, drop `delivery !== 'sent'` filter, add `mergeSessionIntoState`
- `fixthis-mcp/src/main/console/annotations.js` — drop `delivery !== 'sent'` filter; mutation-lock status toggles
- `fixthis-mcp/src/main/console/preview.js` — drop `delivery !== 'sent'` filter inside `latestPersistedScreen`
- `fixthis-mcp/src/main/console/prompt.js` — toast text, mutation-lock around `sendAgentPrompt` and `copyPrompt`

**Test (modify):**
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

### Phase D — Build + docs

**Run:**
- `node scripts/build-console-assets.mjs`
- `./gradlew :fixthis-mcp:test :fixthis-mcp:installDist`

**Modify:**
- `docs/mcp.md`
- `docs/design-feedback-console-ux.md`
- `docs/troubleshooting.md`
- `CHANGELOG.md`

---

# Phase A — Backend agent loop

## Task 1: Add `claimFeedback` to `FeedbackSessionStore`

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`

- [ ] **Step 1: Add the failing tests**

Append at the end of `FeedbackSessionStoreTest.kt` (inside the existing test class):

```kotlin
@Test
fun claimFeedbackPromotesDraftItemToInProgress() {
    val fixture = newFixture()
    val session = fixture.store.openSession(packageName = "com.example.app", newSession = true)
    val saved = fixture.store.savePreviewFeedbackItems(
        sessionId = session.sessionId,
        previewId = "preview-1",
        items = listOf(testAnnotation(itemId = "item-1", screenId = "pending", comment = "fix this")),
        fallbackScreen = testScreen(screenId = "pending"),
    )
    val claimed = fixture.store.claimFeedback(saved.sessionId, "item-1", agentNote = "starting")
    assertEquals(AnnotationStatusDto.IN_PROGRESS, claimed.status)
    assertEquals("starting", claimed.agentSummary)
    assertEquals(FeedbackDelivery.DRAFT, claimed.delivery)
}

@Test
fun claimFeedbackOnSentItemKeepsSentDelivery() {
    val fixture = newFixture()
    val session = fixture.store.openSession(packageName = "com.example.app", newSession = true)
    val saved = fixture.store.savePreviewFeedbackItems(
        sessionId = session.sessionId,
        previewId = "preview-1",
        items = listOf(testAnnotation(itemId = "item-1", screenId = "pending", comment = "fix this")),
        fallbackScreen = testScreen(screenId = "pending"),
    )
    fixture.store.sendDraftToAgent(saved.sessionId, markdownSnapshot = "snap")
    val claimed = fixture.store.claimFeedback(saved.sessionId, "item-1", agentNote = null)
    assertEquals(AnnotationStatusDto.IN_PROGRESS, claimed.status)
    assertEquals(FeedbackDelivery.SENT, claimed.delivery)
}

@Test
fun claimFeedbackIsIdempotentOnInProgress() {
    val fixture = newFixture()
    val session = fixture.store.openSession(packageName = "com.example.app", newSession = true)
    val saved = fixture.store.savePreviewFeedbackItems(
        sessionId = session.sessionId,
        previewId = "preview-1",
        items = listOf(testAnnotation(itemId = "item-1", screenId = "pending", comment = "fix this")),
        fallbackScreen = testScreen(screenId = "pending"),
    )
    fixture.store.claimFeedback(saved.sessionId, "item-1", agentNote = "first")
    val secondClaim = fixture.store.claimFeedback(saved.sessionId, "item-1", agentNote = "second")
    assertEquals(AnnotationStatusDto.IN_PROGRESS, secondClaim.status)
    assertEquals("second", secondClaim.agentSummary)
}

@Test
fun claimFeedbackRejectsResolvedItem() {
    val fixture = newFixture()
    val session = fixture.store.openSession(packageName = "com.example.app", newSession = true)
    val saved = fixture.store.savePreviewFeedbackItems(
        sessionId = session.sessionId,
        previewId = "preview-1",
        items = listOf(testAnnotation(itemId = "item-1", screenId = "pending", comment = "fix this")),
        fallbackScreen = testScreen(screenId = "pending"),
    )
    fixture.store.updateItemStatus(saved.sessionId, "item-1", AnnotationStatusDto.RESOLVED, agentSummary = "done")
    val ex = assertThrows<FeedbackSessionException> {
        fixture.store.claimFeedback(saved.sessionId, "item-1", agentNote = null)
    }
    assertTrue(ex.message!!.startsWith("ITEM_ALREADY_RESOLVED"))
}

@Test
fun claimFeedbackRejectsUnknownItem() {
    val fixture = newFixture()
    val session = fixture.store.openSession(packageName = "com.example.app", newSession = true)
    val ex = assertThrows<FeedbackSessionException> {
        fixture.store.claimFeedback(session.sessionId, "missing", agentNote = null)
    }
    assertTrue(ex.message!!.contains("Unknown feedback item"))
}

@Test
fun updateItemStatusStillRejectsInProgress() {
    val fixture = newFixture()
    val session = fixture.store.openSession(packageName = "com.example.app", newSession = true)
    val saved = fixture.store.savePreviewFeedbackItems(
        sessionId = session.sessionId,
        previewId = "preview-1",
        items = listOf(testAnnotation(itemId = "item-1", screenId = "pending", comment = "fix this")),
        fallbackScreen = testScreen(screenId = "pending"),
    )
    assertThrows<IllegalArgumentException> {
        fixture.store.updateItemStatus(saved.sessionId, "item-1", AnnotationStatusDto.IN_PROGRESS, agentSummary = null)
    }
}
```

If `testAnnotation` / `testScreen` / `newFixture` helpers do not already exist in this test class, copy the smallest-shape helpers used by the existing `clearDraftItemsKeepsSentHistory` test in the same file as a reference and reuse them. (Read the file first to identify their exact names.)

- [ ] **Step 2: Run tests, verify they fail**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest*' -i
```

Expected: 6 new tests fail with `unresolved reference: claimFeedback` (or similar). The `updateItemStatusStillRejectsInProgress` test already passes against the existing `require(...)` constraint — keep it as a regression guard.

- [ ] **Step 3: Implement `claimFeedback` and the helper set**

In `FeedbackSessionStore.kt`, immediately after `updateItemStatus(...)` (after line ~273), add:

```kotlin
private val resolvedStatusSet = setOf(
    AnnotationStatusDto.RESOLVED,
    AnnotationStatusDto.WONT_FIX,
)

fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto =
    synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val now = clock()
        var updatedItem: AnnotationDto? = null
        val updatedItems = session.items.map { item ->
            if (item.itemId != itemId) return@map item
            if (item.status in resolvedStatusSet) {
                throw FeedbackSessionException(
                    "ITEM_ALREADY_RESOLVED: Cannot claim resolved feedback item: $itemId",
                )
            }
            item.copy(
                status = AnnotationStatusDto.IN_PROGRESS,
                agentSummary = agentNote ?: item.agentSummary,
                updatedAtEpochMillis = now,
            ).also { updatedItem = it }
        }
        val item = updatedItem
            ?: throw FeedbackSessionException("Unknown feedback item: $itemId")
        val updated = session.copy(items = updatedItems, updatedAtEpochMillis = now)
        save(updated)
        sessions[sessionId] = updated
        item
    }
```

- [ ] **Step 4: Re-run tests, verify they pass**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest*' -i
```

Expected: all tests pass, including the existing `clearDraftItemsKeepsSentHistory` regression guard.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "feat(mcp): add FeedbackSessionStore.claimFeedback for IN_PROGRESS marking"
```

---

## Task 2: Delegate `claimFeedback` through `FeedbackSessionService`

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`

- [ ] **Step 1: Write failing service-layer test**

Append to `FeedbackSessionStoreTest.kt` if a service test class does not exist; otherwise create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceClaimTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedbackSessionServiceClaimTest {
    @Test
    fun serviceClaimDelegatesToStoreClaim() {
        val fixture = FeedbackSessionTestFixture.newDefault()
        val session = fixture.service.openSession(packageName = "com.example.app", newSession = true)
        val saved = fixture.service.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = "preview-1",
            items = listOf(testAnnotation(itemId = "item-1", screenId = "pending", comment = "fix this")),
            fallbackScreen = testScreen(screenId = "pending"),
        )
        val claimed = fixture.service.claimFeedback(saved.sessionId, "item-1", agentNote = "via service")
        assertEquals(AnnotationStatusDto.IN_PROGRESS, claimed.status)
        assertEquals("via service", claimed.agentSummary)
    }
}
```

If `FeedbackSessionTestFixture.newDefault()` does not exist, look at how existing tests construct a service (search for `FeedbackSessionService(` in the test directory) and inline the same construction in this test. Reuse `testAnnotation` / `testScreen` helpers from Task 1's resolution.

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionServiceClaimTest*' -i
```

Expected: `unresolved reference: claimFeedback`.

- [ ] **Step 3: Add the delegate method**

In `FeedbackSessionService.kt`, add immediately after the existing `resolveFeedback(...)` method (after line ~232):

```kotlin
fun claimFeedback(sessionId: String, itemId: String, agentNote: String?): AnnotationDto =
    store.claimFeedback(sessionId, itemId, agentNote)
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionService*' -i
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionServiceClaimTest.kt
git commit -m "feat(mcp): expose claimFeedback through FeedbackSessionService"
```

---

## Task 3: Register `fixthis_claim_feedback` tool definition

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Write failing test for tool listing**

Append to `McpProtocolTest.kt`:

```kotlin
@Test
fun toolsListIncludesClaimFeedback() = runBlocking {
    val server = newTestServer()
    val response = server.handle(toolsListRequest())
    val tools = response.result!!.jsonObject["tools"]!!.jsonArray
    val claim = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "fixthis_claim_feedback" }
    val description = claim.jsonObject["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("before starting work", ignoreCase = true), description)
    assertTrue(description.contains("fixthis_resolve_feedback", ignoreCase = true), description)
    val inputSchema = claim.jsonObject["inputSchema"]!!.jsonObject
    val required = inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertEquals(listOf("itemId"), required)
}
```

If helpers `newTestServer()` / `toolsListRequest()` are not present in this file, search for how other `toolsList` assertions are written (e.g. `tools/list`) in the same file and copy the pattern verbatim.

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest.toolsListIncludesClaimFeedback*' -i
```

Expected: `NoSuchElementException` (no `fixthis_claim_feedback` in the list) or `AssertionError`.

- [ ] **Step 3: Add the tool definition**

In `FixThisTools.kt`, locate the `ToolDefinition` list (around line 660–704) and append after the existing `fixthis_resolve_feedback` definition:

```kotlin
ToolDefinition(
    name = "fixthis_claim_feedback",
    description = "Mark a feedback item as in-progress before starting work. Call this AFTER reading the item and BEFORE making code changes. Returns the updated item. The user's browser console reflects the change within 2 seconds. After this call you must eventually call fixthis_resolve_feedback for the same itemId.",
    inputSchema = objectSchema(
        "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        "itemId" to stringProperty("Feedback item id to claim."),
        "agentNote" to stringProperty("Optional short note shown next to the item in the user's console."),
        required = listOf("itemId"),
    ),
),
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest.toolsListIncludesClaimFeedback*' -i
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "feat(mcp): register fixthis_claim_feedback tool definition"
```

---

## Task 4: Implement `fixthis_claim_feedback` handler

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Write failing handler tests**

Append to `McpProtocolTest.kt`:

```kotlin
@Test
fun callClaimFeedbackReturnsUpdatedItem() = runBlocking {
    val server = newTestServer()
    val session = server.bootstrapSessionWithItems(itemIds = listOf("item-1"))
    val response = server.handle(callToolRequest("fixthis_claim_feedback", buildJsonObject {
        put("sessionId", session.sessionId)
        put("itemId", "item-1")
        put("agentNote", "starting")
    }))
    val payload = response.result!!.jsonObject["content"]!!.jsonArray
        .single { (it as JsonObject)["type"]!!.jsonPrimitive.content == "text" }
        .jsonObject["text"]!!.jsonPrimitive.content
    val item = McpProtocol.json.decodeFromString(JsonObject.serializer(), payload)
    assertEquals("in_progress", item["status"]!!.jsonPrimitive.content)
    assertEquals("starting", item["agentSummary"]!!.jsonPrimitive.content)
}

@Test
fun callClaimFeedbackRequiresItemId() = runBlocking {
    val server = newTestServer()
    val response = server.handle(callToolRequest("fixthis_claim_feedback", buildJsonObject {}))
    val errorMessage = response.result!!.jsonObject["content"]!!.jsonArray
        .single { (it as JsonObject)["type"]!!.jsonPrimitive.content == "text" }
        .jsonObject["text"]!!.jsonPrimitive.content
    assertTrue(errorMessage.contains("requires itemId", ignoreCase = true), errorMessage)
}
```

If `bootstrapSessionWithItems` / `callToolRequest` helpers do not exist, model the new tests after another `tools/call` assertion already in the file (e.g. for `fixthis_resolve_feedback`), inlining session creation through the existing service test fixture.

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest.callClaimFeedback*' -i
```

Expected: `Unknown FixThis tool: fixthis_claim_feedback`.

- [ ] **Step 3: Add the handler branch**

In `FixThisTools.kt`, in the `when (name)` block of `callTool` (around line 87–212), insert a new branch directly before `else -> throw FixThisToolException("Unknown FixThis tool: $name")`:

```kotlin
"fixthis_claim_feedback" -> bridgeToolResult {
    val session = requestedSession(arguments)
    val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
        ?: throw FixThisToolException("fixthis_claim_feedback requires itemId")
    val agentNote = arguments.stringParam("agentNote")?.takeIf { it.isNotBlank() }
    val item = feedbackService.claimFeedback(session.sessionId, itemId, agentNote)
    jsonToolResult(McpProtocol.json.encodeToJsonElement(AnnotationDto.serializer(), item).jsonObject)
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "feat(mcp): implement fixthis_claim_feedback handler"
```

---

## Task 5: Update `fixthis_resolve_feedback` description

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Write failing assertion on the description**

Append to `McpProtocolTest.kt`:

```kotlin
@Test
fun resolveFeedbackDescriptionMentionsClaimPairing() = runBlocking {
    val server = newTestServer()
    val response = server.handle(toolsListRequest())
    val tools = response.result!!.jsonObject["tools"]!!.jsonArray
    val resolve = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "fixthis_resolve_feedback" }
    val description = resolve.jsonObject["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("after claiming", ignoreCase = true), description)
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest.resolveFeedbackDescription*' -i
```

Expected: `AssertionError` (current text does not mention claiming).

- [ ] **Step 3: Update the description string**

In `FixThisTools.kt`, locate the `fixthis_resolve_feedback` `ToolDefinition` (around line 693) and replace the description:

```kotlin
ToolDefinition(
    name = "fixthis_resolve_feedback",
    description = "Mark a feedback item as resolved, needing clarification, or not fixed. Call this after claiming an item with fixthis_claim_feedback and finishing the work. Status must be one of resolved, needs_clarification, wont_fix.",
    // inputSchema unchanged
    inputSchema = objectSchema(
        "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        "itemId" to stringProperty("Feedback item id to update."),
        "status" to stringProperty("One of resolved, needs_clarification, or wont_fix."),
        "summary" to stringProperty("Agent summary shown in the console."),
        required = listOf("itemId", "status"),
    ),
),
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "docs(mcp): pair resolve description with claim workflow"
```

---

## Task 6: Add `includeAll` filter to `fixthis_list_feedback`

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `McpProtocolTest.kt`:

```kotlin
@Test
fun listFeedbackDefaultsToSentAndUnfinished() = runBlocking {
    val server = newTestServer()
    val session = server.bootstrapSessionWithItems(itemIds = listOf("draft-1", "sent-open", "sent-resolved"))
    server.feedbackService.sendDraftToAgent(session.sessionId)
    server.feedbackService.resolveFeedback(session.sessionId, "sent-resolved", AnnotationStatusDto.RESOLVED, summary = "fixed")
    server.feedbackService.addDraftFeedbackItem(/* … add draft-1 again as DRAFT */) // see helper note below

    val response = server.handle(callToolRequest("fixthis_list_feedback", buildJsonObject {
        put("sessionId", session.sessionId)
    }))
    val payload = response.result!!.jsonObject["content"]!!.jsonArray
        .single { (it as JsonObject)["type"]!!.jsonPrimitive.content == "text" }
        .jsonObject["text"]!!.jsonPrimitive.content
    val parsed = McpProtocol.json.decodeFromString(JsonObject.serializer(), payload)
    val itemIds = parsed["items"]!!.jsonArray.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
    assertEquals(listOf("sent-open"), itemIds)
    // counters always reflect the full session
    assertTrue(parsed["draftItemsCount"]!!.jsonPrimitive.int >= 1)
    assertTrue(parsed["sentBatchesCount"]!!.jsonPrimitive.int >= 1)
}

@Test
fun listFeedbackIncludeAllReturnsEverything() = runBlocking {
    val server = newTestServer()
    val session = server.bootstrapSessionWithItems(itemIds = listOf("draft-1", "sent-1"))
    server.feedbackService.sendDraftToAgent(session.sessionId)
    server.feedbackService.addDraftFeedbackItem(/* draft-1 again */)

    val response = server.handle(callToolRequest("fixthis_list_feedback", buildJsonObject {
        put("sessionId", session.sessionId)
        put("includeAll", true)
    }))
    val payload = response.result!!.jsonObject["content"]!!.jsonArray
        .single { (it as JsonObject)["type"]!!.jsonPrimitive.content == "text" }
        .jsonObject["text"]!!.jsonPrimitive.content
    val parsed = McpProtocol.json.decodeFromString(JsonObject.serializer(), payload)
    val itemIds = parsed["items"]!!.jsonArray.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }.toSet()
    assertTrue("draft-1" in itemIds)
    assertTrue("sent-1" in itemIds)
}
```

If `bootstrapSessionWithItems` / `addDraftFeedbackItem` helpers don't already shape items the way the test needs, build them inline. The shape required: a session with at least one DRAFT item, one SENT-and-OPEN item, and one SENT-and-RESOLVED item. Use the same factories that other resolve-feedback tests in this file use.

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest.listFeedback*' -i
```

Expected: failure — current default returns all items, no `includeAll` accepted.

- [ ] **Step 3: Update the handler**

In `FixThisTools.kt`, locate the `"fixthis_list_feedback" ->` branch (around line 166–191). Replace its body with:

```kotlin
"fixthis_list_feedback" -> bridgeToolResult {
    val session = requestedSession(arguments)
    val includeAll = arguments.booleanParam("includeAll") ?: false
    val visibleItems = if (includeAll) session.items else session.items.filter {
        it.delivery == FeedbackDelivery.SENT && it.status !in resolvedStatuses
    }
    jsonToolResult(buildJsonObject {
        put("sessionId", session.sessionId)
        put("packageName", session.packageName)
        put("status", session.status.name.lowercase())
        put("screensCount", session.screens.size)
        put("itemsCount", session.items.size)
        put("draftItemsCount", session.items.count { it.delivery == FeedbackDelivery.DRAFT })
        put("sentBatchesCount", session.handoffBatches.size)
        put(
            "unresolvedSentItemsCount",
            session.items.count { it.delivery == FeedbackDelivery.SENT && it.status !in resolvedStatuses },
        )
        put("items", buildJsonArray {
            visibleItems.forEach { item ->
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
```

If `arguments.booleanParam(...)` does not exist, search for `stringParam` in the same file and add a `booleanParam` extension following the same pattern, returning `Boolean?`.

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest*' -i
```

Expected: all green, including the existing tests that assert specific counters.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "feat(mcp): focus fixthis_list_feedback on SENT and unfinished by default"
```

---

## Task 7: Add `includeAll` filter to `fixthis_read_feedback`

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `McpProtocolTest.kt`:

```kotlin
@Test
fun readFeedbackDefaultExcludesDraftItems() = runBlocking {
    val server = newTestServer()
    val session = server.bootstrapSessionWithItems(itemIds = listOf("draft-1", "sent-1"))
    server.feedbackService.sendDraftToAgent(session.sessionId) // sent-1 marked SENT
    server.feedbackService.addDraftFeedbackItem(/* re-add draft-1 */)
    val response = server.handle(callToolRequest("fixthis_read_feedback", buildJsonObject {
        put("sessionId", session.sessionId)
    }))
    val jsonText = response.result!!.jsonObject["content"]!!.jsonArray
        .first { (it as JsonObject)["type"]!!.jsonPrimitive.content == "text" }
        .jsonObject["text"]!!.jsonPrimitive.content
    val parsed = McpProtocol.json.decodeFromString(JsonObject.serializer(), jsonText)
    val itemIds = parsed["items"]!!.jsonArray.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
    assertEquals(listOf("sent-1"), itemIds)
}

@Test
fun readFeedbackByItemIdIgnoresDefaultFilter() = runBlocking {
    val server = newTestServer()
    val session = server.bootstrapSessionWithItems(itemIds = listOf("draft-1"))
    val response = server.handle(callToolRequest("fixthis_read_feedback", buildJsonObject {
        put("sessionId", session.sessionId)
        put("itemId", "draft-1")
    }))
    val jsonText = response.result!!.jsonObject["content"]!!.jsonArray
        .first { (it as JsonObject)["type"]!!.jsonPrimitive.content == "text" }
        .jsonObject["text"]!!.jsonPrimitive.content
    val parsed = McpProtocol.json.decodeFromString(JsonObject.serializer(), jsonText)
    val itemIds = parsed["items"]!!.jsonArray.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
    assertEquals(listOf("draft-1"), itemIds)
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest.readFeedback*' -i
```

Expected: failure (current `read` returns all items).

- [ ] **Step 3: Update the handler**

In `FixThisTools.kt`, replace the `"fixthis_read_feedback" ->` branch (around line 192–201) body with:

```kotlin
"fixthis_read_feedback" -> bridgeToolResult {
    val detailMode = DetailMode.fromWire(arguments.stringParam("detailMode"))
    val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
    val includeAll = arguments.booleanParam("includeAll") ?: false
    val baseSession = requestedSession(arguments)
    val session = baseSession
        .focusedOn(itemId)
        .filteredForAgent(showAll = includeAll || itemId != null)
    toolResult(
        content = listOf(
            textContent(FeedbackQueueFormatter.toJson(session), "application/json"),
            textContent(FeedbackQueueFormatter.toMarkdown(session, detailMode), "text/markdown"),
        ),
    )
}
```

Add a helper next to `focusedOn` (around line 450):

```kotlin
private fun SessionDto.filteredForAgent(showAll: Boolean): SessionDto {
    if (showAll) return this
    val visible = items.filter { it.delivery == FeedbackDelivery.SENT && it.status !in resolvedStatuses }
    return copy(items = visible)
}
```

Also extend the input schema for `fixthis_read_feedback` (around line 681–692):

```kotlin
ToolDefinition(
    name = "fixthis_read_feedback",
    description = "Read the feedback queue as annotation JSON and Markdown. By default returns only SENT items that are not yet resolved. Pass includeAll=true to receive everything; passing itemId always returns that item regardless of state.",
    inputSchema = objectSchema(
        "sessionId" to stringProperty("Feedback session id. If omitted, the active session is used."),
        "itemId" to stringProperty("Optional feedback item id to focus the returned payload."),
        "detailMode" to enumStringProperty(
            description = "Markdown detail level. JSON remains complete regardless of this value.",
            values = listOf("compact", "precise", "full"),
        ),
        "includeAll" to booleanProperty("If true, returns DRAFT and resolved items too."),
    ),
),
```

If `booleanProperty(...)` does not exist alongside `stringProperty`/`enumStringProperty`, add it as a thin wrapper that emits `{"type": "boolean", "description": ...}` (mirror the `stringProperty` implementation).

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*McpProtocolTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "feat(mcp): focus fixthis_read_feedback default; itemId always returns"
```

---

## Task 8: Emit per-item `id:` token in compact handoff

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Write the failing assertion**

Append to `CompactHandoffRendererTest.kt`:

```kotlin
@Test
fun rendersIdTokenForEachItem() {
    val session = sessionWithItems(
        items = listOf(
            testAnnotation(itemId = "item-aaa", screenId = "screen-1", comment = "first"),
            testAnnotation(itemId = "item-bbb", screenId = "screen-1", comment = "second"),
        ),
    )
    val markdown = CompactHandoffRenderer.render(session)
    assertTrue(markdown.contains("id: item-aaa"), markdown)
    assertTrue(markdown.contains("id: item-bbb"), markdown)
}
```

If `sessionWithItems` / `testAnnotation` helpers do not exist in this test file, locate the existing test helpers (search for `private fun` or `companion object` in the same file) and reuse them.

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*CompactHandoffRendererTest.rendersIdTokenForEachItem*' -i
```

Expected: assertion failure (no `id:` line in output).

- [ ] **Step 3: Add the `id:` token in `appendCompactItem`**

In `CompactHandoffRenderer.kt`, at the end of `appendCompactItem(...)` (around line 121–141), insert a single line right after the `appendLine("[${number}] ...")` line:

```kotlin
appendLine("  id: ${item.itemId}")
```

Final relevant region should look like:

```kotlin
appendLine("[${number}] ${prefix}${title.inlineSafe()}")
appendLine("  id: ${item.itemId}")
appendLine(compactUiLine(item, isOverlap, instanceLabel, dupRefMarker))
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*CompactHandoffRendererTest*' -i
```

Expected: all green. If any pre-existing snapshot-style test compares whole output strings, update its expected value to include the new `id:` line.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "feat(handoff): emit per-item id token in compact prompt"
```

---

## Task 9: Append `agent_protocol:` footer to compact handoff

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Write the failing assertion**

Append to `CompactHandoffRendererTest.kt`:

```kotlin
@Test
fun rendersAgentProtocolFooter() {
    val session = sessionWithItems(
        sessionId = "sess-123",
        items = listOf(testAnnotation(itemId = "item-1", screenId = "screen-1", comment = "fix")),
    )
    val markdown = CompactHandoffRenderer.render(session)
    assertTrue(markdown.contains("agent_protocol:"), markdown)
    assertTrue(markdown.contains("before_work: fixthis_claim_feedback"), markdown)
    assertTrue(markdown.contains("on_complete: fixthis_resolve_feedback"), markdown)
    assertTrue(markdown.contains("session_id: sess-123"), markdown)
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*CompactHandoffRendererTest.rendersAgentProtocolFooter*' -i
```

Expected: assertion failure.

- [ ] **Step 3: Append the footer at the end of `render(...)`**

In `CompactHandoffRenderer.kt`'s `render(session: SessionDto)` function, before the closing `}`, add:

```kotlin
appendLine("---")
appendLine("agent_protocol:")
appendLine("  before_work: fixthis_claim_feedback({sessionId, itemId})")
appendLine("  on_complete: fixthis_resolve_feedback({sessionId, itemId, status: resolved|wont_fix|needs_clarification, summary})")
appendLine("  user_console_reflects_within: 2s")
appendLine("session_id: ${session.sessionId}")
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*CompactHandoffRendererTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "feat(handoff): append agent_protocol footer to compact prompt"
```

---

# Phase B — ETag polling infrastructure

## Task 10: Add `inProgressItemsCount` to `FeedbackSessionSummary`

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionSummary.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionPersistenceTest.kt` (or whichever test file currently asserts summary shape; if none, create `FeedbackSessionSummaryTest.kt`)

- [ ] **Step 1: Write failing test**

Append to the chosen test file:

```kotlin
@Test
fun summaryCountsInProgressItems() {
    val session = SessionDto(
        sessionId = "s-1",
        packageName = "com.example",
        status = SessionStatusDto.ACTIVE,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 2_000L,
        items = listOf(
            buildAnnotation(itemId = "i1", status = AnnotationStatusDto.OPEN),
            buildAnnotation(itemId = "i2", status = AnnotationStatusDto.IN_PROGRESS),
            buildAnnotation(itemId = "i3", status = AnnotationStatusDto.IN_PROGRESS),
            buildAnnotation(itemId = "i4", status = AnnotationStatusDto.RESOLVED),
        ),
    )
    val summary = FeedbackSessionSummary.from(session)
    assertEquals(2, summary.inProgressItemsCount)
}
```

If `buildAnnotation` does not exist, find the smallest existing `AnnotationDto`-construction helper or copy a literal constructor call from another test in the same file.

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*summaryCountsInProgressItems*' -i
```

Expected: `inProgressItemsCount` unresolved.

- [ ] **Step 3: Add the field and computation**

In `FeedbackSessionSummary.kt`, add the field to the data class and compute it inside `from(...)`. Keep field order stable for JSON output: insert after `unresolvedItemsCount`.

```kotlin
data class FeedbackSessionSummary(
    val sessionId: String,
    val updatedAtEpochMillis: Long,
    val status: SessionStatusDto,
    val itemsCount: Int = 0,
    val unresolvedItemsCount: Int = 0,
    val inProgressItemsCount: Int = 0,    // NEW
    val draftItemsCount: Int = 0,
    val sentBatchesCount: Int = 0,
    // remaining fields unchanged
) {
    companion object {
        fun from(session: SessionDto): FeedbackSessionSummary = FeedbackSessionSummary(
            sessionId = session.sessionId,
            updatedAtEpochMillis = session.updatedAtEpochMillis,
            status = session.status,
            itemsCount = session.items.size,
            unresolvedItemsCount = session.items.count { it.status !in finishedStatuses },
            inProgressItemsCount = session.items.count { it.status == AnnotationStatusDto.IN_PROGRESS },  // NEW
            draftItemsCount = session.items.count { it.delivery == FeedbackDelivery.DRAFT },
            sentBatchesCount = session.handoffBatches.size,
            // remaining unchanged
        )
    }
}
```

If `finishedStatuses` does not exist, use the literal set already used in the original `from(...)` body.

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test -i
```

Expected: green. (Run the whole module to surface any JSON-shape regression in persistence tests.)

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionSummary.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/
git commit -m "feat(mcp): add inProgressItemsCount to FeedbackSessionSummary"
```

---

## Task 11: Add ETag helpers to `ConsoleHttp.kt`

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttp.kt`

- [ ] **Step 1: Write a small helper test**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttpEtagTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConsoleHttpEtagTest {
    @Test
    fun etagFormatJoinsParts() {
        assertEquals("\"abc:42\"", etagOf("abc", 42L))
        assertEquals("\"3:1700000000000\"", etagOf("3", 1_700_000_000_000L))
    }
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*ConsoleHttpEtagTest*' -i
```

Expected: `unresolved reference: etagOf`.

- [ ] **Step 3: Add the helpers**

Append to `ConsoleHttp.kt`:

```kotlin
internal fun etagOf(prefix: String, version: Long): String = "\"$prefix:$version\""

internal fun HttpExchange.ifNoneMatch(): String? = requestHeaders.getFirst("If-None-Match")

internal fun HttpExchange.sendNotModified(etag: String) {
    responseHeaders.set("ETag", etag)
    sendResponseHeaders(304, -1)
    close()
}

internal fun HttpExchange.sendJsonWithEtag(
    statusCode: Int,
    etag: String,
    write: () -> Unit,
) {
    responseHeaders.set("ETag", etag)
    write()
}
```

The `sendJsonWithEtag` helper is intentionally minimal — callers set Content-Type via the existing `sendText` / `sendJson` helpers. Setting the ETag header before calling `sendJson` is sufficient because `sendBytes` does `responseHeaders.set("Content-Type", ...)` and then writes; `ETag` survives.

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*ConsoleHttpEtagTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttp.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleHttpEtagTest.kt
git commit -m "feat(console): add ETag/304 helpers to ConsoleHttp"
```

---

## Task 12: Emit ETag and honor If-None-Match on `/api/sessions`

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/SessionRoutes.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun apiSessionsResponseIncludesEtag() {
    val server = startTestServer()
    val client = ConsoleHttpTestClient(server.url)
    val first = client.get("/api/sessions")
    assertEquals(200, first.statusCode)
    val etag = first.header("ETag")
    assertNotNull(etag)
    assertTrue(etag.startsWith("\"") && etag.endsWith("\""), etag)
}

@Test
fun apiSessionsReturns304ForMatchingIfNoneMatch() {
    val server = startTestServer()
    val client = ConsoleHttpTestClient(server.url)
    val first = client.get("/api/sessions")
    val etag = first.header("ETag")!!
    val second = client.get("/api/sessions", headers = mapOf("If-None-Match" to etag))
    assertEquals(304, second.statusCode)
    assertEquals(etag, second.header("ETag"))
    assertTrue(second.body.isEmpty())
}

@Test
fun apiSessionsEtagChangesAfterMutation() {
    val server = startTestServer()
    val client = ConsoleHttpTestClient(server.url)
    val first = client.get("/api/sessions").header("ETag")!!
    server.feedbackService.openSession(packageName = "com.example.app", newSession = true)
    val second = client.get("/api/sessions").header("ETag")!!
    assertNotEquals(first, second)
}
```

If `ConsoleHttpTestClient` lacks a `header(name)` accessor or `headers` map argument, extend it minimally — read its existing definition first.

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest.apiSessions*Etag*' -i
```

Expected: failure (no ETag header).

- [ ] **Step 3: Wire ETag into the route**

In `SessionRoutes.kt`, replace the `"/api/sessions"` branch (around line 36–44):

```kotlin
"/api/sessions" -> exchange.requireMethod("GET") {
    val list = service.listSessions(
        packageNameOverride = exchange.queryParameter("packageName"),
        includeClosed = exchange.queryBoolean("includeClosed"),
    )
    val maxUpdated = list.sessions.maxOfOrNull { it.updatedAtEpochMillis } ?: 0L
    val etag = etagOf("${list.sessions.size}", maxUpdated)
    if (exchange.ifNoneMatch() == etag) {
        exchange.sendNotModified(etag)
    } else {
        exchange.responseHeaders.set("ETag", etag)
        exchange.sendJson(200, list)
    }
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/SessionRoutes.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): ETag/304 on /api/sessions"
```

---

## Task 13: Emit ETag and honor If-None-Match on `/api/session`

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/SessionRoutes.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun apiSessionResponseIncludesEtag() {
    val server = startTestServer()
    server.feedbackService.openSession(packageName = "com.example.app", newSession = true)
    val response = ConsoleHttpTestClient(server.url).get("/api/session")
    assertEquals(200, response.statusCode)
    assertNotNull(response.header("ETag"))
}

@Test
fun apiSessionReturns304ForMatchingIfNoneMatch() {
    val server = startTestServer()
    server.feedbackService.openSession(packageName = "com.example.app", newSession = true)
    val client = ConsoleHttpTestClient(server.url)
    val first = client.get("/api/session")
    val etag = first.header("ETag")!!
    val second = client.get("/api/session", headers = mapOf("If-None-Match" to etag))
    assertEquals(304, second.statusCode)
    assertTrue(second.body.isEmpty())
}

@Test
fun apiSessionWithoutCurrentReturns200NullAndNoEtag() {
    val server = startTestServer()
    val response = ConsoleHttpTestClient(server.url).get("/api/session")
    assertEquals(200, response.statusCode)
    assertEquals("null", response.body.trim())
    // no ETag when there is no current session — undefined version
    assertNull(response.header("ETag"))
}
```

- [ ] **Step 2: Run, verify fail**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest.apiSession*Etag*' -i
```

Expected: failure.

- [ ] **Step 3: Wire ETag into the route**

In `SessionRoutes.kt`, replace the `"/api/session"` branch (around line 31–35):

```kotlin
"/api/session" -> exchange.requireMethod("GET") {
    val current = service.currentSessionOrNull()
    if (current == null) {
        exchange.sendText(200, "null", "application/json; charset=utf-8")
    } else {
        val etag = etagOf(current.sessionId, current.updatedAtEpochMillis)
        if (exchange.ifNoneMatch() == etag) {
            exchange.sendNotModified(etag)
        } else {
            exchange.responseHeaders.set("ETag", etag)
            exchange.sendJson(200, current)
        }
    }
}
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/SessionRoutes.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): ETag/304 on /api/session"
```

---

# Phase C — Console UI removal + polling client

> **Build note for this phase**: every change to a `fixthis-mcp/src/main/console/*.js` source file must be followed by `node scripts/build-console-assets.mjs` before running `:fixthis-mcp:test`, because tests assert against the bundled `app.js`. Tasks 14–30 below explicitly run that command in their final verification step.

## Task 14: Update `FeedbackConsoleServerTest` assertions for drawer absence

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Replace existing drawer-presence assertions with absence assertions**

Locate and replace the following assertions in `FeedbackConsoleServerTest.kt`:

| Around line | Current assertion | New assertion |
|---|---|---|
| 734 | `val clearSentHistoryBody = javascriptFunctionBody(html, "clearSentHistory")` and the lines using it (line 783–785) | DELETE these lines |
| 782 | `assertTrue(html.contains("id=\"clearSentHistoryButton\""))` | `assertFalse(html.contains("id=\"clearSentHistoryButton\""))` |
| 1043 | `assertTrue(html.contains("id=\"sentHistory\""))` | `assertFalse(html.contains("id=\"sentHistory\""))` |
| 1602 | `assertTrue(html.contains("class=\"sent-history-drawer\""))` | `assertFalse(html.contains("class=\"sent-history-drawer\""))` |
| 1619 | `assertTrue(html.contains(".sent-history-drawer .history-list"))` | DELETE this line |

Also locate and DELETE any test named `consoleHtmlBuildsCopiesAndSendsSelectedHistoryPrompt` assertions about `clearSentHistory` (the function will no longer exist).

- [ ] **Step 2: Run, verify they FAIL (drawer still present in HTML)**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: failures on the new `assertFalse` checks. This proves we need to remove the drawer in subsequent tasks.

- [ ] **Step 3: Commit RED state**

Commit the test changes alone so the next tasks become "make tests green by deleting code".

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "test(console): assert Sent History drawer is gone (RED)"
```

---

## Task 15: Remove Sent History drawer from HTML

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/index.html`

- [ ] **Step 1: Delete the drawer block**

In `index.html`, delete lines 73–81:

```html
<details class="sent-history-drawer">
  <summary>
    <span>Sent History</span>
    <button id="clearSentHistoryButton" class="sent-clear-button with-icon" type="button" title="Clear sent history" aria-label="Clear sent history">
      <svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/></svg>
    </button>
  </summary>
  <div id="sentHistory" class="history-list"></div>
</details>
```

- [ ] **Step 2: Verify the file is well-formed**

```bash
grep -c "sent-history-drawer" fixthis-mcp/src/main/resources/console/index.html
```

Expected: `0`.

- [ ] **Step 3: Run tests**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: assertions on drawer absence pass; assertions on JS function `clearSentHistory` may still fail (cleared in next task).

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/index.html
git commit -m "feat(console): remove Sent History drawer from index.html"
```

---

## Task 16: Remove Sent History CSS

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`

- [ ] **Step 1: Delete the drawer-related rules**

In `styles.css`, delete:
- The block `.sent-history-drawer { … }` (around line 408–414)
- The block `.sent-history-drawer summary { … }` (around line 415–427)
- The block `.sent-clear-button { … }` (around line 428–434)
- The block `.sent-clear-button svg { … }` (around line 435–439)
- The block `.sent-history-drawer .history-list { … }` (around line 440–444)

Total ~37 lines.

- [ ] **Step 2: Verify removal**

```bash
grep -c "sent-history-drawer\|sent-clear-button" fixthis-mcp/src/main/resources/console/styles.css
```

Expected: `0`.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/styles.css
git commit -m "feat(console): drop Sent History drawer styles"
```

---

## Task 17: Remove drawer DOM refs from `state.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/state.js`

- [ ] **Step 1: Delete two declarations**

In `state.js`, delete:
- Line 23: `const sentHistory = document.getElementById('sentHistory');`
- Line 51: `const clearSentHistoryButton = document.getElementById('clearSentHistoryButton');`

- [ ] **Step 2: Confirm removal**

```bash
grep -n "sentHistory\|clearSentHistoryButton" fixthis-mcp/src/main/console/state.js
```

Expected: no matches.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/main/console/state.js
git commit -m "refactor(console): drop Sent History DOM references in state.js"
```

---

## Task 18: Remove drawer event listener from `main.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`

- [ ] **Step 1: Delete the click handler block**

In `main.js`, delete lines 15–19:

```javascript
clearSentHistoryButton.addEventListener('click', event => {
  event.preventDefault();
  event.stopPropagation();
  clearSentHistory().catch(showError);
});
```

- [ ] **Step 2: Confirm removal**

```bash
grep -n "clearSentHistoryButton\|clearSentHistory(" fixthis-mcp/src/main/console/main.js
```

Expected: no matches.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/main/console/main.js
git commit -m "refactor(console): drop clearSentHistoryButton listener"
```

---

## Task 19: Remove sent-history JS functions from `history.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js`

- [ ] **Step 1: Delete the three functions**

In `history.js`, delete:
- `function sentHistorySummaries() { … }` (around lines 266–268)
- `function renderSentHistory() { … }` (around lines 270–307) — entire function
- `async function clearSentHistory() { … }` (around lines 404–427) — entire function

- [ ] **Step 2: Confirm removal**

```bash
grep -n "sentHistorySummaries\|renderSentHistory\|clearSentHistory" fixthis-mcp/src/main/console/history.js
```

Expected: no matches.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/main/console/history.js
git commit -m "refactor(console): remove sentHistorySummaries/renderSentHistory/clearSentHistory"
```

---

## Task 20: Remove `renderSentHistory()` call from `rendering.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/rendering.js`

- [ ] **Step 1: Delete the call**

In `rendering.js`, remove line 417 (`renderSentHistory();`) inside `renderSessionRegions()`. The function should now be:

```javascript
function renderSessionRegions() {
  const session = state.session;
  renderSessionsList();
}
```

(Keep the local `session` const if other later edits in this plan reference it; otherwise remove it.)

- [ ] **Step 2: Confirm removal**

```bash
grep -n "renderSentHistory" fixthis-mcp/src/main/console/rendering.js
```

Expected: no matches.

- [ ] **Step 3: Rebuild bundle and run all tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: drawer-absence assertions from Task 14 now pass. Everything else unaffected.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/main/console/rendering.js fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): drop Sent History rendering hook"
```

---

## Task 21: Remove `delivery !== 'sent'` filter from inspector + preview

**Files:**
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/preview.js`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Add a failing assertion that the filter is gone**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun consoleHtmlNoLongerFiltersSentItemsFromInspector() {
    val html = FeedbackConsoleAssets.indexHtml
    assertFalse(html.contains("filter(item => item.delivery !== 'sent')"), "Inspector must show SENT items too")
}
```

- [ ] **Step 2: Verify it FAILS (filter still present)**

```bash
./gradlew :fixthis-mcp:test --tests '*consoleHtmlNoLongerFilters*' -i
```

Expected: fail.

- [ ] **Step 3: Remove the filter at all four sites**

In `rendering.js:226`:

```diff
- return (state.session?.items || []).filter(item => item.delivery !== 'sent');
+ return (state.session?.items || []);
```

In `annotations.js:65`:

```diff
- return (state.session?.items || []).filter(item => item.delivery !== 'sent');
+ return (state.session?.items || []);
```

In `preview.js:75` (inside `latestPersistedScreen()`):

```diff
- const persistedScreenIds = new Set(
-   (state.session?.items || [])
-     .filter(item => item.delivery !== 'sent')
-     .map(item => item.screenId)
- );
+ const persistedScreenIds = new Set(
+   (state.session?.items || []).map(item => item.screenId)
+ );
```

The fourth occurrence is in the bundled `app.js` (auto-regenerated next step).

- [ ] **Step 4: Rebuild and re-run tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/rendering.js \
        fixthis-mcp/src/main/console/annotations.js \
        fixthis-mcp/src/main/console/preview.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): show SENT items in inspector and preview"
```

> **Correction note (2026-05-10 follow-up)**: This task's instructions
> were written against an earlier code state. At execution time the
> orchestrator narrowed scope to ONLY `preview.js:75`
> (`latestPersistedScreen`) because:
>
> - `rendering.js`: the filter had already been removed in pre-existing
>   main work; no changes needed.
> - `annotations.js:80`: the filter is inside `currentPromptAnnotations()`,
>   the SEND/COPY path. Removing it would break the send-once invariant
>   and the Send/Copy button enable/disable logic.
> - `preview.js:75`: the only display-side filter still in place — this
>   is what was actually changed (commit `ba31262`).
>
> The original three-site instruction is retained above for historical
> context. A future re-run of this plan should follow the narrowed scope.

---

## Task 22: Remove `ready_for_agent` filter from History list

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Add a failing assertion**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun consoleHtmlNoLongerFiltersReadyForAgentSessions() {
    val html = FeedbackConsoleAssets.indexHtml
    val rendered = javascriptFunctionBody(html, "renderSessionsListFromPayload")
    assertFalse(rendered.contains("'ready_for_agent'"), "History list must show sent sessions too")
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*consoleHtmlNoLongerFiltersReady*' -i
```

Expected: fail.

- [ ] **Step 3: Drop the filter at both sites**

In `history.js:138-145` (`hasActiveHistorySessionForAnnotating`), drop both `ready_for_agent` checks. The function becomes:

```javascript
function hasActiveHistorySessionForAnnotating() {
  return Boolean(
    state.session &&
    state.session.status !== 'closed' &&
    (state.sessionSummaries || []).some(session =>
      session.sessionId === state.session.sessionId &&
      session.status !== 'closed'
    )
  );
}
```

In `history.js:205`:

```diff
- const activeSummaries = sessionSummaries.filter(session => session.status !== 'ready_for_agent' && session.status !== 'closed');
+ const activeSummaries = sessionSummaries.filter(session => session.status !== 'closed');
```

- [ ] **Step 4: Rebuild and re-run**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green. Note: there is an existing test at `FeedbackConsoleServerTest:781` asserting `assertTrue(renderSessionsListBody.contains("session.status !== 'ready_for_agent'"))`. **Delete that line** — it has become an inverted assertion of what we just removed.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/history.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): keep sent sessions visible in History list"
```

---

## Task 23: Add `working` pip + drop `points` pip

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing assertions**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun historyPipsIncludeWorking() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("class=\"hi-pip working\""), "History row must support a 'working' pip")
    assertTrue(html.contains(".hi-pip.working"), "CSS for working pip must exist")
}

@Test
fun historyPipDropsPointsLabel() {
    val html = FeedbackConsoleAssets.indexHtml
    val rendered = javascriptFunctionBody(html, "renderSessionsListFromPayload")
    assertFalse(rendered.contains("hi-pip points"), "Points pip must be removed")
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*historyPip*' -i
```

Expected: fail.

- [ ] **Step 3: Update `renderSessionsListFromPayload`**

In `history.js:209-225`, replace the `renderedSessions` block's pip generation with:

```javascript
const renderedSessions = renderedActiveSummaries.map((session, index) => {
  const open      = historyOpenCount(session);
  const working   = Number(session.inProgressItemsCount || 0);
  const done      = historyDoneCount(session);
  const label     = formatSessionLabel(session, ordinalBySessionId.get(session.sessionId) || index + 1);
  const pips = [
    open    > 0 ? '<span class="hi-pip open">'    + escapeHtml(countLabel(open,    'open',    'open'))    + '</span>' : '',
    working > 0 ? '<span class="hi-pip working">' + escapeHtml(countLabel(working, 'working', 'working')) + '</span>' : '',
    done    > 0 ? '<span class="hi-pip done">'    + escapeHtml(countLabel(done,    'done',    'done'))    + '</span>' : '',
  ].join('');
  return '<div class="history-item session-row ' + (session.sessionId === activeId ? 'is-active' : '') + '" role="button" tabindex="0" data-session-id="' + escapeHtml(session.sessionId) + '">' +
    '<span class="hi-head">' +
      '<span class="hi-title">' + escapeHtml(label) + '</span>' +
      '<button type="button" class="hi-del" data-delete-session-id="' + escapeHtml(session.sessionId) + '" aria-label="Delete history item ' + escapeHtml(label) + '">×</button>' +
    '</span>' +
    '<span class="hi-meta">' + escapeHtml(formatSessionSummary(session)) + '</span>' +
    '<span class="hi-stats">' + pips + '</span>' +
    '<span class="hi-strip">' + renderHistoryStrip(session) + '</span>' +
  '</div>';
}).join('');
```

Also DELETE the `historyPointsCount` function (around line 44–46) — no longer used.

- [ ] **Step 4: Add CSS for working pip**

In `styles.css`, find the existing `.hi-pip.open`, `.hi-pip.done` declarations (search for `.hi-pip`) and add an analogous declaration for `.hi-pip.working` using a third color (amber). Example:

```css
.hi-pip.working {
  color: #b76e00;
  background: rgba(183, 110, 0, 0.12);
  border-color: rgba(183, 110, 0, 0.32);
}
```

(If the existing pip rules use CSS variables, use the same pattern with a new `--pip-working` variable.)

- [ ] **Step 5: Rebuild and re-run**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green. Update or delete any existing test that asserted on the `points` pip (search `hi-pip points`).

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/history.js \
        fixthis-mcp/src/main/resources/console/styles.css \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): replace points pip with working (in_progress) pip"
```

---

## Task 24: Create `sessions-polling.js` and register it in the build script

**Files:**
- Create: `fixthis-mcp/src/main/console/sessions-polling.js`
- Modify: `scripts/build-console-assets.mjs`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Add failing assertion**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun consoleHtmlContainsSessionsPolling() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("function startSessionsPolling"), html.takeLast(2_000))
    assertTrue(html.contains("async function pollSessionsTick"), "Polling tick must exist")
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*consoleHtmlContainsSessionsPolling*' -i
```

Expected: fail.

- [ ] **Step 3: Create the polling module**

Create `fixthis-mcp/src/main/console/sessions-polling.js`:

```javascript
            const SessionsPollIntervalMs = 2000;

            function shouldPollSessions() {
              return !document.hidden && pendingMutationCount === 0;
            }

            function startSessionsPolling() {
              stopSessionsPolling();
              sessionsPollingTimer = setInterval(() => {
                if (shouldPollSessions()) pollSessionsTick().catch(showError);
              }, SessionsPollIntervalMs);
            }

            function stopSessionsPolling() {
              if (sessionsPollingTimer) clearInterval(sessionsPollingTimer);
              sessionsPollingTimer = null;
            }

            async function pollSessionsTick() {
              const listResp = await fetch('/api/sessions', {
                headers: lastSessionsEtag ? { 'If-None-Match': lastSessionsEtag } : {}
              });
              if (listResp.status === 200) {
                lastSessionsEtag = listResp.headers.get('ETag');
                const data = await listResp.json();
                state.sessionSummaries = data.sessions || [];
                renderSessionsList();
              }

              if (state.session?.sessionId) {
                const sessResp = await fetch('/api/session', {
                  headers: lastSessionEtag ? { 'If-None-Match': lastSessionEtag } : {}
                });
                if (sessResp.status === 200) {
                  lastSessionEtag = sessResp.headers.get('ETag');
                  const fresh = await sessResp.json();
                  if (fresh) {
                    mergeSessionIntoState(fresh);
                    renderInspectorRegion();
                  }
                }
              }
            }
```

(`mergeSessionIntoState`, `renderInspectorRegion`, and the global state vars `pendingMutationCount`, `sessionsPollingTimer`, `lastSessionsEtag`, `lastSessionEtag` are introduced in Tasks 25–27. The bundle is concatenated after this module is registered, so forward references resolve at runtime.)

- [ ] **Step 4: Register the module in the build script**

In `scripts/build-console-assets.mjs`, add `'sessions-polling.js'` to the `sources` array. Place it AFTER `rendering.js` and BEFORE `shortcuts.js`:

```javascript
const sources = [
  'state.js',
  'api.js',
  'connection.js',
  'availability.js',
  'devices.js',
  'preview.js',
  'annotations.js',
  'history.js',
  'prompt.js',
  'rendering.js',
  'sessions-polling.js',   // NEW
  'shortcuts.js',
  'main.js',
];
```

- [ ] **Step 5: Build and confirm assertion compiles**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*consoleHtmlContainsSessionsPolling*' -i
```

Expected: pass. (Polling won't actually run yet because the timer/etag globals and `mergeSessionIntoState` haven't been added; just the JS text exists in the bundle.)

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/sessions-polling.js \
        scripts/build-console-assets.mjs \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): add sessions polling module skeleton"
```

---

## Task 25: Add polling globals and `withMutationLock` in `state.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/state.js`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Failing assertion**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun consoleHtmlDeclaresPollingGlobals() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("let pendingMutationCount"))
    assertTrue(html.contains("let sessionsPollingTimer"))
    assertTrue(html.contains("let lastSessionsEtag"))
    assertTrue(html.contains("let lastSessionEtag"))
    assertTrue(html.contains("async function withMutationLock"))
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*consoleHtmlDeclaresPolling*' -i
```

Expected: fail.

- [ ] **Step 3: Add globals + helper**

In `state.js`, in the section where other `let` globals are declared (around lines 61–84), add:

```javascript
let sessionsPollingTimer = null;
let lastSessionsEtag = null;
let lastSessionEtag = null;
let pendingMutationCount = 0;

async function withMutationLock(fn) {
  pendingMutationCount++;
  try {
    return await fn();
  } finally {
    pendingMutationCount--;
  }
}
```

- [ ] **Step 4: Rebuild and re-run**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*consoleHtmlDeclaresPolling*' -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/state.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): add polling globals and withMutationLock"
```

---

## Task 26: Wrap mutating fetches with `withMutationLock`

**Files:**
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/history.js`

- [ ] **Step 1: Wrap the high-traffic mutators**

In `prompt.js`'s `sendAgentPrompt()` (around line 427) and `copyPrompt()` (around line 460), wrap the body that performs the network call:

```javascript
async function sendAgentPrompt() {
  if (promptActionInFlight) return;
  await withMutationLock(async () => {
    error.textContent = '';
    ensurePromptAnnotationsAvailable();
    promptActionInFlight = true;
    updateComposerState();
    let sent = false;
    try {
      if (addItemsFlow) {
        await persistPendingFeedbackItems({ onlyWrittenComments: true });
      }
      const prompt = currentAnnotationsPrompt();
      state.session = await requestJson('/api/agent-handoffs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ prompt: prompt })
      });
      comment.value = '';
      resetAnnotationComposerState();
      invalidatePreviewContext();
      await refreshSessions();
      render();
      startLivePreviewPolling();
      sent = true;
    } finally {
      promptActionInFlight = false;
      updateComposerState();
      if (sent) {
        showSuccess('Saved to MCP ✓ — agent will pick up', 3000);
      }
    }
  });
}
```

(Apply the same `withMutationLock(async () => { … })` wrapper to `copyPrompt()`'s body.)

In `annotations.js`, locate every direct `requestJson('/api/items…', { method: …, …})` site that mutates (POST/PUT/DELETE) and wrap with `withMutationLock(async () => …)`. Use grep to be exhaustive:

```bash
grep -n "requestJson('/api/" fixthis-mcp/src/main/console/annotations.js
```

In `history.js`, do the same for any `/api/session/open`, `/api/session/close`, `/api/items` mutations. (`refreshSessions`, which is a GET, must NOT be wrapped — it is a read.)

- [ ] **Step 2: Update Save to MCP toast**

The wrapper above already replaced `'Saved to MCP ✓'` with `'Saved to MCP ✓ — agent will pick up'`. Confirm only one occurrence:

```bash
grep -n "Saved to MCP" fixthis-mcp/src/main/console/prompt.js
```

Expected: one line, the new text.

- [ ] **Step 3: Add a regression assertion**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun saveToMcpToastMentionsAgentPickup() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("Saved to MCP ✓ — agent will pick up"))
    assertFalse(html.contains("Saved to MCP ✓\","), "Old toast text must be gone")
}

@Test
fun mutationsAreWrappedInLock() {
    val html = FeedbackConsoleAssets.indexHtml
    val sendAgent = javascriptFunctionBody(html, "sendAgentPrompt")
    val copyPrompt = javascriptFunctionBody(html, "copyPrompt")
    assertTrue(sendAgent.contains("withMutationLock"))
    assertTrue(copyPrompt.contains("withMutationLock"))
}
```

- [ ] **Step 4: Rebuild and run all tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/prompt.js \
        fixthis-mcp/src/main/console/annotations.js \
        fixthis-mcp/src/main/console/history.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): mutation-lock fetches and update Save to MCP toast"
```

---

## Task 27: Implement `mergeSessionIntoState` in `rendering.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Failing assertion**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun mergeSessionIntoStatePreservesUserState() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "mergeSessionIntoState")
    assertTrue(body.contains("comment.value"), "Must preserve textarea value")
    assertTrue(body.contains("focusedSavedItemId") || body.contains("focusedPendingItemIndex"))
    assertTrue(body.contains("currentSelection"))
    assertTrue(body.contains("data-just-changed"))
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*mergeSessionIntoState*' -i
```

Expected: `javascriptFunctionBody` throws (function not found).

- [ ] **Step 3: Add the function**

Append to `rendering.js`:

```javascript
function mergeSessionIntoState(fresh) {
  const previous = state.session;
  const preservedComment = comment.value;
  const preservedFocusedSavedItemId = focusedSavedItemId;
  const preservedFocusedPendingIndex = focusedPendingItemIndex;
  const preservedSelection = currentSelection;

  state.session = fresh;

  comment.value = preservedComment;
  focusedSavedItemId = preservedFocusedSavedItemId;
  focusedPendingItemIndex = preservedFocusedPendingIndex;
  currentSelection = preservedSelection;

  const previousStatusById = new Map(
    (previous?.items || []).map(item => [item.itemId, item.status])
  );
  (fresh.items || []).forEach(item => {
    const before = previousStatusById.get(item.itemId);
    if (before && before !== item.status) {
      queueJustChangedHighlight(item.itemId);
    }
  });
}

function queueJustChangedHighlight(itemId) {
  requestAnimationFrame(() => {
    document.querySelectorAll(`[data-item-id="${CSS.escape(itemId)}"]`).forEach(node => {
      node.setAttribute('data-just-changed', 'true');
      setTimeout(() => node.removeAttribute('data-just-changed'), 800);
    });
  });
}
```

- [ ] **Step 4: Add `data-item-id` to inspector item rendering**

In `rendering.js`, locate the function that renders an annotation row inside the inspector (search for `class="ann-row` or the loop that iterates `pendingFeedbackItems` / saved items). Ensure each row's outer element includes `data-item-id="${escapeHtml(item.itemId)}"`. Add it where missing.

(If the row markup spans many places, do this as a single grep-and-add: `grep -n "ann-row" fixthis-mcp/src/main/console/rendering.js`, and at every site that emits the row HTML, ensure the data attribute is present.)

- [ ] **Step 5: Add highlight CSS**

In `styles.css`, append:

```css
.ann-row[data-just-changed],
.history-item[data-just-changed] {
  background-color: rgba(46, 160, 67, 0.18);
  transition: background-color 800ms ease-out;
}
```

- [ ] **Step 6: Rebuild and run all tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test -i
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/rendering.js \
        fixthis-mcp/src/main/resources/console/styles.css \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): mergeSessionIntoState with just-changed highlight"
```

---

## Task 28: Wire up polling lifecycle in `main.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Failing assertion**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun startSessionsPollingIsCalledOnBoot() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("startSessionsPolling()"), "main.js must start sessions polling")
    assertTrue(html.contains("if (!document.hidden)") || html.contains("if (!document.hidden) startSessionsPolling"),
        "visibilitychange handler must restart polling")
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*startSessionsPollingIsCalledOnBoot*' -i
```

Expected: fail.

- [ ] **Step 3: Modify the boot promise chain**

In `main.js`, change the boot tail (around lines 63–72) to:

```javascript
refresh()
  .then(() => {
    if (shouldAutoFetchPreview()) return refreshPreview();
    return null;
  })
  .then(() => {
    startHeartbeatPolling();
    startLivePreviewPolling();
    startSessionsPolling();
  })
  .catch(showError);
```

In the existing `document.addEventListener('visibilitychange', …)` handler (around line 32–36), add a sessions-polling restart line:

```javascript
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);
  if (!document.hidden && state.selectedDeviceSerial) sendBridgeHeartbeat().catch(handleHeartbeatError);
  startLivePreviewPolling();
  startSessionsPolling();
});
```

- [ ] **Step 4: Rebuild and run tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test -i
```

Expected: green.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "feat(console): start sessions polling on boot and visibility change"
```

---

# Phase D — Build + docs

## Task 29: Final build + full test sweep

**Files:**
- Run only

- [ ] **Step 1: Rebuild bundle**

```bash
node scripts/build-console-assets.mjs
```

- [ ] **Step 2: Run full module test**

```bash
./gradlew :fixthis-mcp:test
```

Expected: all green. If anything fails, fix the specific test rather than mass-deleting assertions.

- [ ] **Step 3: Repackage distributions**

```bash
./gradlew :fixthis-mcp:installDist :fixthis-cli:installDist
```

- [ ] **Step 4: Lint check**

```bash
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

Expected: both silent.

- [ ] **Step 5: Commit any remaining `app.js` regeneration**

If `git status` shows `fixthis-mcp/src/main/resources/console/app.js` modified after rebuild:

```bash
git add fixthis-mcp/src/main/resources/console/app.js
git commit -m "chore(console): rebundle app.js"
```

---

## Task 30: Update `docs/mcp.md`

**Files:**
- Modify: `docs/mcp.md`

- [ ] **Step 1: Replace Sent History references**

In `docs/mcp.md`, search for `Sent History`, `Send Agent`, and `sentBatchesCount`. Replace narrative passages so they describe the new claim/resolve loop:

- Remove any sentence that describes the Sent History drawer.
- Replace "click `Send Agent`" with "click `Save to MCP`".
- Add a subsection titled `### Agent claim/resolve protocol`:

```markdown
### Agent claim/resolve protocol

After `Save to MCP` is clicked (or after the user pastes a Copy Prompt
output to an agent), the agent calls:

1. `fixthis_list_feedback` (default returns SENT and unfinished items;
   pass `includeAll: true` to receive everything).
2. `fixthis_read_feedback({itemId})` for the item it intends to work on.
3. `fixthis_claim_feedback({itemId, agentNote?})` BEFORE making any code
   change. This sets the item's `status` to `in_progress`. The user's
   browser console reflects the change within ~2 seconds via ETag
   polling.
4. After completing the work, `fixthis_resolve_feedback({itemId, status,
   summary})` with `status` of `resolved`, `wont_fix`, or
   `needs_clarification`.

The compact handoff prompt (returned by `fixthis_read_feedback` and
copied by the `Copy Prompt` button) embeds an `agent_protocol:` footer
that documents this contract inline; an agent that sees only the pasted
text can still address items by `id` and call MCP tools.
```

- Add a `### Behavior change` callout near the top of the tools section:

```markdown
> **Behavior change (May 2026)**: `fixthis_list_feedback` and
> `fixthis_read_feedback` now default to returning only items that were
> sent to the agent (`delivery: sent`) and are not yet resolved. Pass
> `includeAll: true` to restore the previous "all items" behavior. The
> Sent History drawer in the browser console has been removed; sessions
> remain in the main History list with a `working` pip while an agent is
> active.
```

- [ ] **Step 2: Sanity check**

```bash
grep -in "sent history\|send agent" docs/mcp.md
```

Expected: zero matches (or only matches inside the `Behavior change` callout that explicitly describes the removal).

- [ ] **Step 3: Commit**

```bash
git add docs/mcp.md
git commit -m "docs(mcp): document claim/resolve protocol and behavior changes"
```

---

## Task 31: Update other docs and CHANGELOG

**Files:**
- Modify: `docs/design-feedback-console-ux.md`
- Modify: `docs/troubleshooting.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Sweep "Send Agent" wording**

Replace remaining `Send Agent` mentions across `docs/design-feedback-console-ux.md` and `docs/troubleshooting.md` with `Save to MCP`. In passages that currently describe Sent History as a destination, rewrite them to say the session stays in the main History list with a `working` pip.

```bash
grep -n "Send Agent\|Sent History" docs/design-feedback-console-ux.md docs/troubleshooting.md
```

After edits, the same grep should return zero matches outside of any explicit "previously named" historical note.

- [ ] **Step 2: Add CHANGELOG entry**

Prepend a new entry to `CHANGELOG.md` under the upcoming version section. Follow the existing changelog style:

```markdown
### Added
- `fixthis_claim_feedback` MCP tool — agents call it before starting work on an item; status moves to `in_progress` and the browser console reflects the change within ~2 seconds.
- ETag-based polling on `/api/sessions` and `/api/session` (304 when unchanged); the console polls every 2 seconds and refreshes status badges live.
- `inProgressItemsCount` in session summaries, surfaced as a `working` pip on each History row.
- `agent_protocol:` footer plus per-item `id:` token in the compact handoff prompt so the Copy Prompt route is self-describing.
- `includeAll` parameter on `fixthis_list_feedback` and `fixthis_read_feedback`.

### Changed
- `fixthis_list_feedback` and `fixthis_read_feedback` now default to returning only `delivery: sent` items that are not resolved. Pass `includeAll: true` to restore the previous behavior.
- `fixthis_resolve_feedback` description now mentions the claim/resolve pairing.
- "Save to MCP" toast now reads `Saved to MCP ✓ — agent will pick up`.

### Removed
- Sent History drawer in the browser console. Sessions stay in the main History list; the per-row `×` button still closes a session.
- `points` pip on History rows (replaced by `working`).
```

- [ ] **Step 3: Commit**

```bash
git add docs/design-feedback-console-ux.md docs/troubleshooting.md CHANGELOG.md
git commit -m "docs: refresh console docs and add CHANGELOG entry for handoff workflow"
```

---

## Self-Review

(Performed by the plan author before handing off.)

**Spec coverage:**
- Goals 1 (claim → resolve loop): Tasks 1–7 ✓
- Goal 2 (remove Sent History UI): Tasks 14–20 ✓
- Goal 3 (live status reflection in console): Tasks 11–13 (server) + 24–28 (client) ✓
- Goal 4 (preserve disk JSON compat): No schema changes; verified by reusing existing `FeedbackSessionPersistenceTest` (Phase A's test runs surface any regression)
- Goal 5 (preserve external MCP client compat for counters/enums): Counters retained in Task 6's handler; `READY_FOR_AGENT` and `markdownSnapshot` untouched
- `agent_protocol:` footer + `id:` token: Tasks 8–9 ✓
- `inProgressItemsCount` field: Task 10 ✓
- ETag wiring: Tasks 11–13 ✓
- `working` pip + drawer-free History: Tasks 22–23 ✓
- Polling client + mutation lock + highlight: Tasks 24–28 ✓
- Docs sweep: Tasks 30–31 ✓

**Placeholder scan:** No `TBD`/`TODO` left. Where helpers may not exist (`testAnnotation`, `bootstrapSessionWithItems`, `booleanProperty`, `ConsoleHttpTestClient.header`), tasks instruct the implementer to inspect existing analogues and inline the missing piece — this is concrete enough.

**Type/name consistency:**
- `claimFeedback(sessionId, itemId, agentNote)` signature is identical across store, service, and tool handler.
- `pendingMutationCount`, `sessionsPollingTimer`, `lastSessionsEtag`, `lastSessionEtag` declared once (Task 25), referenced consistently in Tasks 24, 27, 28.
- `mergeSessionIntoState`, `renderInspectorRegion`, `renderSessionsList` referenced consistently.
- `etagOf(prefix, version)` helper signature matches all call sites in Tasks 12 and 13.

No additional fixes required.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
