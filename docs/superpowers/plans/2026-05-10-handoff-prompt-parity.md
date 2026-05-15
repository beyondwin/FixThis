# Handoff Prompt Parity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Browser console "Copy Prompt" / "Save to MCP" produces the same complete handoff Markdown the Kotlin `CompactHandoffRenderer` already produces — including `id:` / `session_id:` / `agent_protocol:` so agents can call `fixthis_claim_feedback` and `fixthis_resolve_feedback`. Eliminate the JS-side renderer (~500 LoC) by routing both flows through the server's Kotlin renderer.

**Architecture:** One renderer (Kotlin) is the single source of truth. New `POST /api/sessions/{sessionId}/handoff-preview` returns rendered Markdown for a list of itemIds. Existing `POST /api/agent-handoffs` switches its request body from `{prompt}` to `{itemIds}` and renders server-side before persisting + flipping DRAFT→SENT.

**Tech Stack:** Kotlin (compose-core, MCP); JUnit 4 + kotlin.test (Kotlin tests); vanilla JS modules (no JS test runner today — see spec §6.1); Bash.

**Spec:** `docs/superpowers/specs/2026-05-10-handoff-prompt-parity-design.md`

---

## Conventions

- **Test runners:**
  - MCP only: `./gradlew :fixthis-mcp:test`
  - Full regression:
    ```bash
    ./gradlew :fixthis-compose-core:test \
              :fixthis-cli:test \
              :fixthis-mcp:test \
              :fixthis-compose-sidekick:testDebugUnitTest \
              :fixthis-gradle-plugin:test
    ```
  - Current main baseline: `tests=631 failures=0` (from `2026-05-10` staleness follow-ups merge).
- **Bundle JS:** after editing `fixthis-mcp/src/main/console/*.js`, run `node scripts/build-console-assets.mjs` to regenerate `fixthis-mcp/src/main/resources/console/app.js`. Then `bash -n` is enough to syntax-check; full check via Kotlin server tests.
- **Commit style:** lowercase scope, imperative, one task = one commit (matches recent plans).
- **Compatibility:**
  - JSON field names (`itemId`, `sessionId`, `screenId`, `delivery`, `status`) unchanged.
  - DRAFT/SENT/IN_PROGRESS/RESOLVED state machine unchanged.
  - Bridge protocol unchanged.
  - `fixthis_read_feedback` output: two cosmetic adjustments (screenshot fallback, no overlap blank line) — visible to that tool too. Existing tests are updated in the same task.

## File map

**Created:**

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/HandoffPreviewRoutes.kt` — new `POST /api/sessions/{sid}/handoff-preview` route.

**Modified:**

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` — accept optional `itemIds` filter; screenshot fallback; remove blank line after overlap header.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` — new tests + 1-2 small adjustments to existing assertions for the cosmetic changes.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt` — `sendDraftToAgent` builds prompt server-side from itemIds.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` — `sendDraftToAgent` signature accepts itemIds; markdown snapshot still persisted.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt` — change `AgentHandoffRequest` shape from `{prompt}` to `{itemIds}`; add `HandoffPreviewRequest`.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt` — adapt `/api/agent-handoffs` decoder + handler.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` (or wherever routes are registered) — register `HandoffPreviewRoutes`.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` — new acceptance tests.
- `fixthis-mcp/src/main/console/prompt.js` — delete the JS renderer; rewrite `copyPrompt` / `sendAgentPrompt`; add `persistAndCollectItemIds` + `fetchHandoffPreview` helpers.
- `fixthis-mcp/src/main/console/annotations.js` — minor: ensure `currentPromptAnnotations()` (already exists) is still the selection source.
- `fixthis-mcp/src/main/resources/console/app.js` — rebundled output of the JS module changes.
- `CHANGELOG.md` — single user-visible bullet under Unreleased.

---

## Task 1 — Kotlin renderer: itemIds filter + cosmetic alignment

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

**Goal:** Add optional itemIds filter. Apply two cosmetic JS-parity adjustments. All tests in CompactHandoffRendererTest continue to pass after assertion adjustments for the cosmetic changes.

- [ ] **Step 1: Read current renderer and test file**

```bash
cd /<worktree>
sed -n '1,50p' fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt
wc -l fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt
```

Confirm `fun render(session: SessionDto): String` is the single entry point; locate the `Overlap group` line emit and the `screenshot:` line emit.

- [ ] **Step 2: Write failing test for itemIds filter**

Append to `CompactHandoffRendererTest.kt` (just before the closing `}`):

```kotlin
@Test
fun renderFiltersItemsByItemIdsWhenProvided() {
    val session = SessionDto(
        sessionId = "session-filter",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 1L,
                displayName = "Home",
                screenshot = SnapshotScreenshotDto(
                    desktopFullPath = "/p.png",
                    width = 1, height = 1,
                ),
            ),
        ),
        items = listOf(
            AnnotationDto(
                itemId = "keep",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                comment = "kept",
                sequenceNumber = 1,
            ),
            AnnotationDto(
                itemId = "drop",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                comment = "dropped",
                sequenceNumber = 2,
            ),
        ),
    )

    val markdown = CompactHandoffRenderer.render(session, itemIds = listOf("keep"))

    assertTrue(markdown.contains("id: keep"), "expected 'id: keep' in:\n$markdown")
    assertTrue(!markdown.contains("id: drop"), "should not include filtered-out item, got:\n$markdown")
}
```

Run the test class — the new test must FAIL with a compile error (no overload of `render` accepts `itemIds`).

```bash
./gradlew :fixthis-mcp:test --tests '*CompactHandoffRendererTest*' 2>&1 | tail -15
```

- [ ] **Step 3: Add the filter overload to the renderer**

Edit `CompactHandoffRenderer.kt`. Change the entry point from a single-arg function to a two-arg one with default. Pseudocode (adapt to actual variable names you find in the file):

```kotlin
fun render(session: SessionDto, itemIds: List<String>? = null): String = buildString {
    val activeItems = if (itemIds == null) session.items
        else session.items.filter { it.itemId in itemIds }
    val effectiveSession = if (itemIds == null) session else session.copy(items = activeItems)
    // ... existing rendering body now uses `effectiveSession` instead of `session` ...
}
```

If the existing body uses `session.items` directly, the simplest correct edit is to introduce `val effectiveSession` once and substitute. Verify by reading the function body — do NOT touch any rendering logic beyond switching the source.

If `SessionDto.copy(items=...)` is unavailable (e.g., positional `data class`), pass `activeItems` to a renamed inner builder; structure adapts to source.

- [ ] **Step 4: Re-run the new test**

```bash
./gradlew :fixthis-mcp:test --tests '*CompactHandoffRendererTest*' 2>&1 | tail -15
```

`renderFiltersItemsByItemIdsWhenProvided` now PASSES. All other existing tests still PASS.

- [ ] **Step 5: Add failing test for screenshot fallback**

Append:

```kotlin
@Test
fun renderEmitsScreenshotLineUsingFullPathFallbackWhenDesktopPathMissing() {
    val session = SessionDto(
        sessionId = "session-fallback",
        packageName = "x",
        projectRoot = "/r",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 1L,
                displayName = "S",
                screenshot = SnapshotScreenshotDto(
                    desktopFullPath = null,
                    fullPath = "/device/path/s.png",
                    width = 1, height = 1,
                ),
            ),
        ),
        items = listOf(
            AnnotationDto(
                itemId = "i",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                comment = "x",
                sequenceNumber = 1,
            ),
        ),
    )

    val markdown = CompactHandoffRenderer.render(session)

    assertTrue(
        markdown.lineSequence().any { it == "screenshot: /device/path/s.png" },
        "expected screenshot fallback line, got:\n$markdown",
    )
}
```

If `SnapshotScreenshotDto` has no `fullPath` field, look it up first via `Read`/grep; the spec §4.3 names it `screenshot.fullPath`. Use the actual field present.

Run — the new test FAILS (current code emits no screenshot line when `desktopFullPath == null`).

- [ ] **Step 6: Implement screenshot fallback**

In `CompactHandoffRenderer.kt`, find the line that emits `screenshot:`. Change the source from `screen.screenshot.desktopFullPath` to `screen.screenshot.desktopFullPath ?: screen.screenshot.fullPath` (or whichever field name the source uses). Wrap with the existing null check so the line is only emitted when at least one path is non-null.

Re-run the test — PASS.

- [ ] **Step 7: Add failing test for overlap blank line**

Append a test that constructs two annotations on the same screen with overlapping bounds (use `FixThisRect(0f, 0f, 1f, 1f)` for both, plus distinct `targetType = Area` targets so the overlap detector treats them as overlapping). Render and assert:

```kotlin
@Test
fun renderEmitsNoBlankLineBetweenOverlapHeaderAndFirstItem() {
    val session = sessionWithTwoOverlappingItems()  // helper added below

    val markdown = CompactHandoffRenderer.render(session)
    val lines = markdown.lines()
    val overlapIdx = lines.indexOfFirst { it.startsWith("Overlap group") }
    assertTrue(overlapIdx >= 0, "expected Overlap group header in:\n$markdown")
    val nextLine = lines.getOrNull(overlapIdx + 1) ?: ""
    assertTrue(
        nextLine.startsWith("[1]") || nextLine.startsWith("[2]"),
        "expected first item directly after overlap header, got: '$nextLine'\nfull:\n$markdown",
    )
}
```

Add a private helper in the test class to build two overlapping items reusing the existing fixture pattern. The exact shape of the overlap fixture can be found by reading existing tests in the same file — match their style.

Run — the new test FAILS because Kotlin currently emits a blank line between header and `[1]`.

- [ ] **Step 8: Remove the blank line**

In `CompactHandoffRenderer.kt`, find where `Overlap group N (resolve one marker at a time):` is emitted and remove the immediately-following `appendLine()` (the blank line emit). Verify the prior tests still pass after this small change.

```bash
./gradlew :fixthis-mcp:test --tests '*CompactHandoffRendererTest*' 2>&1 | tail -15
```

- [ ] **Step 9: Adjust any pre-existing assertions affected by the cosmetic changes**

Walk the test file: any assertion that depended on the removed blank line or on screenshot being suppressed will fail. Update them to match the new contract. Document each adjustment in the commit message.

- [ ] **Step 10: Module test sweep**

```bash
./gradlew :fixthis-mcp:test 2>&1 | tail -10
```

All tests pass.

- [ ] **Step 11: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "$(cat <<'EOF'
mcp: filter handoff renderer by itemIds; cosmetic JS parity

Adds optional itemIds filter to CompactHandoffRenderer.render, screenshot
path fallback (desktopFullPath ?: fullPath), and removes the blank line
between an Overlap group header and its first item, matching what the
JS console currently emits.

Task: 1
Risk: mid
EOF
)"
```

---

## Task 2 — New endpoint `POST /api/sessions/{sid}/handoff-preview`

**Files:**

- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/HandoffPreviewRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt` (add `HandoffPreviewRequest`)
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` (or sibling that registers routes)
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

**Goal:** Expose Kotlin-rendered handoff Markdown via a stateless endpoint. Browser uses it for "Copy Prompt".

- [ ] **Step 1: Read existing route registrations**

```bash
sed -n '1,50p' fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt
grep -n "registerRoute\|listOf<ConsoleRoute>\|: ConsoleRoute" fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/*.kt | head -20
```

Confirm where `ConsoleRoute` instances are wired into the server and how matching by path works (the existing `FeedbackItemRoutes.matches(path)` returns boolean).

- [ ] **Step 2: Add `HandoffPreviewRequest` model**

Edit `AnnotationRequestModels.kt`. Add (placement: near `AgentHandoffRequest`):

```kotlin
@Serializable
data class HandoffPreviewRequest(
    val itemIds: List<String> = emptyList(),
)
```

- [ ] **Step 3: Add failing acceptance test for the new endpoint**

Append to `FeedbackConsoleServerTest.kt` — match the existing test's HTTP harness style (look at any test that POSTs to `/api/items` for fixture pattern):

```kotlin
@Test
fun handoffPreviewEndpointReturnsMarkdownForRequestedItems() {
    val server = startServer()  // existing helper
    val sessionId = seedSessionWithOneItem(server, itemId = "item-A")  // existing helper or build inline
    val response = server.postJson(
        path = "/api/sessions/$sessionId/handoff-preview",
        body = """{"itemIds":["item-A"]}""",
    )
    assertEquals(200, response.statusCode)
    assertTrue(response.contentTypeStartsWith("text/markdown"), "got: ${response.headers["Content-Type"]}")
    assertTrue(response.body.contains("id: item-A"), "expected 'id: item-A' in:\n${response.body}")
    assertTrue(response.body.contains("session_id: $sessionId"), "expected 'session_id:' in:\n${response.body}")
    assertTrue(response.body.contains("agent_protocol:"), "expected agent_protocol block in:\n${response.body}")
}

@Test
fun handoffPreviewEndpointRejectsEmptyItemIds() {
    val server = startServer()
    val sessionId = seedSessionWithOneItem(server, itemId = "item-A")
    val response = server.postJson(
        path = "/api/sessions/$sessionId/handoff-preview",
        body = """{"itemIds":[]}""",
    )
    assertEquals(400, response.statusCode)
}

@Test
fun handoffPreviewEndpointReturns404ForUnknownSession() {
    val server = startServer()
    val response = server.postJson(
        path = "/api/sessions/00000000-0000-0000-0000-000000000000/handoff-preview",
        body = """{"itemIds":["x"]}""",
    )
    assertEquals(404, response.statusCode)
}
```

If `seedSessionWithOneItem` does not yet exist, add a small helper in the test file that starts a session and POSTs one item via `/api/items`. Use existing test fixtures if present.

Run — all three tests FAIL.

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' 2>&1 | tail -20
```

- [ ] **Step 4: Implement `HandoffPreviewRoutes`**

Create `HandoffPreviewRoutes.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.mcp.session.CompactHandoffRenderer
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService

internal class HandoffPreviewRoutes(private val service: FeedbackSessionService) : ConsoleRoute {
    private val pathPrefix = "/api/sessions/"
    private val pathSuffix = "/handoff-preview"

    override fun matches(path: String): Boolean =
        path.startsWith(pathPrefix) && path.endsWith(pathSuffix) &&
            path.length > pathPrefix.length + pathSuffix.length

    override fun handle(exchange: HttpExchange) {
        val raw = exchange.requestURI.path
        if (!matches(raw)) return
        val sessionId = raw.removePrefix(pathPrefix).removeSuffix(pathSuffix)
        if (sessionId.isBlank()) throw FeedbackConsoleHttpException(404, "session not found")

        exchange.requireMethod("POST") {
            val body = exchange.decodeJsonBody(HandoffPreviewRequest.serializer(), blankValue = HandoffPreviewRequest())
            if (body.itemIds.isEmpty()) {
                throw FeedbackConsoleHttpException(400, "itemIds must not be empty")
            }
            val session = service.findSession(sessionId)
                ?: throw FeedbackConsoleHttpException(404, "session not found")
            val markdown = CompactHandoffRenderer.render(session, itemIds = body.itemIds)
            exchange.sendMarkdown(200, markdown)
        }
    }
}
```

If `service.findSession(sessionId)` does not exist, look at how `FeedbackItemRoutes` resolves a session by id — `service.requireCurrentSession()` only resolves the active session. Add a method `findSession(sessionId: String): SessionDto?` in `FeedbackSessionService` (delegates to the store's existing lookup). Search the store for an existing internal lookup; only add a new public method if no equivalent exists.

If `exchange.sendMarkdown(...)` does not exist, add a tiny helper next to `sendJson` that sets `Content-Type: text/markdown; charset=utf-8` and writes the string body (UTF-8). Place it in the same file as `sendJson` to match existing conventions.

- [ ] **Step 5: Register the new route**

Edit `FeedbackConsoleServer.kt` (or the sibling that wires routes — the file you identified in Step 1). Add `HandoffPreviewRoutes(service)` to the route list.

- [ ] **Step 6: Re-run acceptance tests**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' 2>&1 | tail -20
```

The three new tests PASS.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/HandoffPreviewRoutes.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "$(cat <<'EOF'
mcp: add /api/sessions/{sid}/handoff-preview endpoint

Stateless POST endpoint returns the Kotlin-rendered handoff Markdown
for a list of itemIds. 400 on empty itemIds, 404 on unknown session.
Used by the browser console's Copy Prompt button.

Task: 2
Risk: mid
EOF
)"
```

---

## Task 3 — `/api/agent-handoffs` body shape: `{prompt}` → `{itemIds}`

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt` (`AgentHandoffRequest`)
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt` (`sendDraftToAgent`)
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` (`sendDraftToAgent` signature)
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

**Goal:** Server renders the prompt itself; old `{prompt}` body is rejected. Existing flow (DRAFT→SENT, persistence) unchanged.

- [ ] **Step 1: Add failing acceptance test for new shape**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun agentHandoffsAcceptsItemIdsAndReturnsRenderedPrompt() {
    val server = startServer()
    val sessionId = seedSessionWithOneItem(server, itemId = "item-A")
    val response = server.postJson(
        path = "/api/agent-handoffs",
        body = """{"itemIds":["item-A"]}""",
    )
    assertEquals(200, response.statusCode)
    val payload = parseJson(response.body)
    assertTrue(payload.containsKey("session"), "response should have 'session', got: ${response.body}")
    assertTrue(payload.containsKey("prompt"), "response should have 'prompt', got: ${response.body}")
    val prompt = payload["prompt"]!!.jsonPrimitive.content
    assertTrue(prompt.contains("id: item-A"))
    // Verify DRAFT→SENT flip
    val sessionAfter = payload["session"]!!.jsonObject
    val itemDelivery = sessionAfter["items"]!!.jsonArray
        .map { it.jsonObject }
        .first { it["itemId"]!!.jsonPrimitive.content == "item-A" }
        ["delivery"]!!.jsonPrimitive.content
    assertEquals("sent", itemDelivery)
}

@Test
fun agentHandoffsRejectsLegacyPromptBody() {
    val server = startServer()
    seedSessionWithOneItem(server, itemId = "item-A")
    val response = server.postJson(
        path = "/api/agent-handoffs",
        body = """{"prompt":"# old format"}""",
    )
    assertEquals(400, response.statusCode)
    assertTrue(
        response.body.contains("itemIds"),
        "error message should mention itemIds, got: ${response.body}",
    )
}

@Test
fun agentHandoffsRejectsEmptyItemIds() {
    val server = startServer()
    seedSessionWithOneItem(server, itemId = "item-A")
    val response = server.postJson(
        path = "/api/agent-handoffs",
        body = """{"itemIds":[]}""",
    )
    assertEquals(400, response.statusCode)
}
```

Use the existing `parseJson`/`jsonPrimitive`/`jsonObject` helpers; if absent, import from `kotlinx.serialization.json`.

Run — three new tests FAIL.

- [ ] **Step 2: Update `AgentHandoffRequest` model**

In `AnnotationRequestModels.kt`:

```kotlin
@Serializable
data class AgentHandoffRequest(
    val itemIds: List<String> = emptyList(),
)
```

(Drop the `prompt` field entirely. Default empty list lets the decoder accept `{}` and we surface a clean 400.)

- [ ] **Step 3: Update `FeedbackSessionStore.sendDraftToAgent`**

Find:
```kotlin
fun sendDraftToAgent(sessionId: String, markdownSnapshot: String?): SessionDto = ...
```
The current signature already takes a markdown string. Keep it (the store does not need to know the prompt was rendered server-side). No change to the store unless its body relies on the request's old prompt field — it doesn't; this is a pure pass-through for snapshot storage.

If a `markdownSnapshot` argument is unused inside the body and was only persisted, leave as-is. Verify with grep.

- [ ] **Step 4: Update `FeedbackSessionService.sendDraftToAgent`**

Find:
```kotlin
fun sendDraftToAgent(sessionId: String, prompt: String? = null): SessionDto = ...
```

Change to:

```kotlin
fun sendDraftToAgent(sessionId: String, itemIds: List<String>): SendDraftToAgentResult {
    require(itemIds.isNotEmpty()) { "itemIds must not be empty" }
    val session = store.findSession(sessionId) ?: error("session not found")
    val prompt = CompactHandoffRenderer.render(session, itemIds = itemIds)
    val updated = store.sendDraftToAgent(sessionId, markdownSnapshot = prompt)
    return SendDraftToAgentResult(session = updated, prompt = prompt)
}

data class SendDraftToAgentResult(val session: SessionDto, val prompt: String)
```

Place the data class in the same package or in a sibling file matching project convention (e.g., next to `AgentHandoffResponse` if one exists; otherwise inline). If you discover during reading that the store already throws on missing session, propagate that exception rather than `error(...)` — match existing error model.

- [ ] **Step 5: Update route handler in `FeedbackItemRoutes.kt`**

Replace the `/api/agent-handoffs` branch:

```kotlin
"/api/agent-handoffs" -> exchange.requireMethod("POST") {
    val request = exchange.decodeAgentHandoffBody()
    if (request.itemIds.isEmpty()) {
        throw FeedbackConsoleHttpException(400, "itemIds must not be empty (legacy {prompt} body is no longer accepted)")
    }
    val sessionId = service.requireCurrentSession().sessionId
    val result = try {
        service.sendDraftToAgent(sessionId, request.itemIds)
    } catch (error: IllegalStateException) {
        throw FeedbackConsoleHttpException(404, error.message ?: "session not found")
    } catch (error: IllegalArgumentException) {
        throw FeedbackConsoleHttpException(400, error.message ?: "invalid request")
    }
    exchange.sendJson(200, mapOf("session" to result.session, "prompt" to result.prompt))
}
```

If `mapOf` doesn't serialize cleanly through `sendJson`, define a small `@Serializable AgentHandoffResponse(val session: SessionDto, val prompt: String)` in `AnnotationRequestModels.kt` and pass that.

- [ ] **Step 6: Re-run tests**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' 2>&1 | tail -20
```

All three new tests PASS. Existing `/api/agent-handoffs` tests likely fail because they sent `{prompt}` — update those test bodies to send `{itemIds}` (the right migration; do not keep dead legacy paths).

Search for legacy-shape callers:

```bash
grep -rn '"prompt":\|prompt = "' fixthis-mcp/src/test/ --include="*.kt" | grep -i "handoff" | head
```

Update any matching call sites to the new body shape.

- [ ] **Step 7: Module test sweep**

```bash
./gradlew :fixthis-mcp:test 2>&1 | tail -10
```

All pass.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "$(cat <<'EOF'
mcp: agent-handoffs accepts {itemIds}, renders prompt server-side

Body shape changes from {prompt} to {itemIds}. Server renders Markdown
via CompactHandoffRenderer with the itemIds filter, persists snapshot,
flips DRAFT→SENT, returns {session, prompt}. Legacy body returns 400.

Task: 3
Risk: mid
EOF
)"
```

---

## Task 4 — Browser: delete JS renderer, route through server

**Files:**

- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify (rebundle target): `fixthis-mcp/src/main/resources/console/app.js`

**Goal:** Remove ~500 LoC of duplicated JS rendering. Both buttons fetch from the new server endpoints.

- [ ] **Step 1: Identify removal blast radius**

```bash
cd /<worktree>
grep -rn "currentAnnotationsPromptCompact\|compactItemLines\|compactScreenHeader\|compactCandidatesBlock\|compactUiLine\|compactDetectOverlap\|computeInstanceLabels\|computeDuplicateMarkers\|compactSourceRoot\|computeSourceRoot\|promptItemTitle\|currentAnnotationsPrompt" fixthis-mcp/src/main/console/ 2>&1 | sort -u
```

Confirm every match is inside `prompt.js` (the renderer is internal). If anything outside `prompt.js` matches, **STOP and revisit**: those callers must be addressed before deletion. Likely none; double-check.

- [ ] **Step 2: Replace `prompt.js` core**

Read the file first:

```bash
wc -l fixthis-mcp/src/main/console/prompt.js
```

Locate the renderer functions (their name list is in Step 1). Delete them in one editor pass. Then replace `copyPrompt` and `sendAgentPrompt` with the rewritten versions:

```js
async function persistAndCollectItemIds() {
    const before = (state.session && Array.isArray(state.session.items)) ? state.session.items : [];
    const beforeIds = new Set(before.map(item => item.itemId));
    if (addItemsFlow) {
        await persistPendingFeedbackItems({ onlyWrittenComments: true });
    }
    const after = (state.session && Array.isArray(state.session.items)) ? state.session.items : [];
    const newlyPersisted = after.filter(item => !beforeIds.has(item.itemId)).map(item => item.itemId);
    if (newlyPersisted.length === 0) {
        // No new items: fall back to currently-pending (already-sent or already-drafted) prompt selection.
        const fallback = currentPromptAnnotations().map(item => item.itemId).filter(Boolean);
        if (fallback.length === 0) throw new Error('Add a comment to at least one annotation before sending.');
        return fallback;
    }
    return newlyPersisted;
}

async function fetchHandoffPreview(sessionId, itemIds) {
    const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/handoff-preview`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ itemIds }),
    });
    if (!response.ok) {
        const text = await response.text();
        throw new Error(`Preview fetch failed (${response.status}): ${text}`);
    }
    return response.text();
}

async function copyPrompt() {
    if (promptActionInFlight) return;
    await withMutationLock(async () => {
        error.textContent = '';
        ensurePromptAnnotationsAvailable();
        promptActionInFlight = true;
        updateComposerState();
        const labelSpan = copyPromptButton.querySelector('span:not(.button-icon)');
        const originalLabel = labelSpan ? labelSpan.textContent : null;
        let copied = false;
        try {
            const itemIds = await persistAndCollectItemIds();
            const markdown = await fetchHandoffPreview(state.session.sessionId, itemIds);
            await copyTextToClipboard(markdown);
            copied = true;
        } finally {
            promptActionInFlight = false;
            updateComposerState();
            if (copied && labelSpan) {
                labelSpan.textContent = 'Copied ✓';
                setTimeout(() => {
                    if (labelSpan.textContent === 'Copied ✓') labelSpan.textContent = originalLabel;
                }, 1500);
            }
        }
    });
}

async function sendAgentPrompt() {
    if (promptActionInFlight) return;
    await withMutationLock(async () => {
        error.textContent = '';
        ensurePromptAnnotationsAvailable();
        promptActionInFlight = true;
        updateComposerState();
        let sent = false;
        try {
            const itemIds = await persistAndCollectItemIds();
            const result = await requestJson('/api/agent-handoffs', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ itemIds }),
            });
            state.session = result.session;
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
            if (sent) showSuccess('Saved to MCP ✓ — agent will pick up', 3000);
        }
    });
}
```

Verify: `copyTextToClipboard`, `requestJson`, `withMutationLock`, `ensurePromptAnnotationsAvailable`, `currentPromptAnnotations`, `persistPendingFeedbackItems`, `state`, `resetAnnotationComposerState`, `invalidatePreviewContext`, `refreshSessions`, `render`, `startLivePreviewPolling`, `showSuccess`, `error`, `comment`, `copyPromptButton`, `updateComposerState`, `addItemsFlow`, `promptActionInFlight` are all already in scope (defined elsewhere in the modules concatenated by `build-console-assets.mjs`). If anything is missing, look for it in `annotations.js`, `state.js`, `connection.js`, etc., before introducing a new helper.

- [ ] **Step 3: Bundle**

```bash
node scripts/build-console-assets.mjs
node --check fixthis-mcp/src/main/resources/console/app.js
```

Both succeed. Bundle size should shrink by roughly 8-12 KB (visible in `wc -c`).

- [ ] **Step 4: Re-run module tests**

The Kotlin server tests added in Tasks 2-3 cover the API contract. JS code does not have a dedicated test runner (per spec §6.1), so this step is a sanity check that the Kotlin tests still pass:

```bash
./gradlew :fixthis-mcp:test 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/prompt.js \
        fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
console: route Copy Prompt and Save to MCP through server renderer

Deletes ~500 LoC of duplicated client-side prompt rendering. Both
buttons now fetch Markdown from the server, which uses the single
Kotlin CompactHandoffRenderer source of truth — restoring id /
session_id / agent_protocol fields in the clipboard prompt.

Task: 4
Risk: mid
EOF
)"
```

---

## Task 5 — Manual smoke + CHANGELOG + full regression

**Files:**

- Modify: `CHANGELOG.md`

**Goal:** Document the user-visible improvement; verify nothing regressed across all five modules.

- [ ] **Step 1: Optional — manual smoke (skip if no UI session at hand)**

Per `CLAUDE.md`:

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis console \
  --package io.github.beyondwin.fixthis.sample \
  --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
```

In the browser console: connect a device, add an annotation with a comment, click **Copy Prompt**, paste into a scratch buffer. Verify the text contains:
- `id: <uuid>`
- `session_id: <uuid>`
- `agent_protocol:`
- `fixthis_claim_feedback({sessionId, itemId})` literal

Then click **Save to MCP** (the same item or a new one) and confirm the same fields appear in the persisted handoff record (e.g., via `cat .fixthis/feedback-sessions/<sid>/session.json | jq '.handoffs[-1].markdownSnapshot'`).

Skip this step if no device / sample app is available — Task 2-3 acceptance tests already cover the contract.

- [ ] **Step 2: CHANGELOG entry**

Read `CHANGELOG.md`. Find the top "Unreleased" or current open release section. Add a single bullet matching the existing style:

```markdown
- Fixed: "Copy Prompt" / "Save to MCP" output now includes `id:` / `session_id:` / `agent_protocol:` / `crop:` / `⚠ stale:`, restoring agent-side `fixthis_claim_feedback` and `fixthis_resolve_feedback` after a handoff. The browser no longer renders the prompt itself — both buttons route through the new server endpoint `POST /api/sessions/{sid}/handoff-preview` (or, for Save to MCP, `POST /api/agent-handoffs` with `{itemIds:[...]}`). Eliminates ~500 LoC of duplicated rendering.
```

If the surrounding section uses a different prefix convention (`Added:` / `Improved:` / etc.), match it. Look at the parent staleness follow-ups CHANGELOG entry for reference.

- [ ] **Step 3: Full 5-module regression**

```bash
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. Test count baseline before this plan was 631 (post-staleness merge); after this plan expect roughly 631 + 4 (Task 1 new tests: itemIds filter, screenshot fallback, overlap blank line, plus 1 fixture helper test) + 6 (Task 2-3 new server tests) − N (existing /api/agent-handoffs tests updated — count unchanged) ≈ 641.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for handoff prompt parity"
```

---

## Out of scope (intentionally deferred)

- **Polling → SSE/WS** in `sessions-polling.js` — separate plan.
- **JS test runner** — current convention is Kotlin integration tests (spec §6.1).
- **DRAFT-item UI nag** — if a user composes annotations and never sends, the agent silently can't see them.
- **Schema versioning on `/api/sessions/{sid}/handoff-preview`** — re-add a `mode` query parameter when a second prompt variant ships.
- **Cross-process race hardening** on `FeedbackSessionStore.commitSessionMutation`.

## Risk register

- **R1: Removed JS helper has a non-prompt caller** — Task 4 Step 1 grep guard catches this. Likelihood low, impact low (compile/runtime error caught immediately).
- **R2: Cosmetic Kotlin changes (Task 1) break a passing test elsewhere** — Task 1 Step 9 explicitly walks the file. Risk contained.
- **R3: Server unreachable mid-click** — `requestJson` already surfaces fetch errors via `error.textContent` / `showError`. Same UX as today's persist failure.
- **R4: Stale itemIds (browser believes item exists; server has GC'd)** — surfaces as 404. JS shows the error; user refreshes session. No silent corruption.

## Self-review checklist (after implementation)

- C2 OQ#2 deprecation message verified: legacy `{prompt}` body returns 400 with a hint mentioning `itemIds` (Task 3 acceptance test).
- Cosmetic adjustments (screenshot fallback, no overlap blank line) propagated to `fixthis_read_feedback` output too — that is intentional per spec §7.
- No `currentAnnotationsPromptCompact` (or sibling helper) remains in `prompt.js` after Task 4 (grep returns no matches).
- `app.js` rebundled — `node --check` passes — bundle size shrunk roughly 8-12 KB.
- 5-module regression green; no new warnings in build output beyond pre-existing ones.
