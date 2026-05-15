# Handoff Lifecycle UX — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어노테이션 라이프사이클 4단계 (`Draft / Sent / In Progress / Resolved`) 를 콘솔 UI 에 시각화하고, 편집 정책을 phase 의 의미에 맞춰 분리한다. SENT(ready) 단계는 편집 허용 + "Modified after handoff" 경고 + Re-save 버튼; IN_PROGRESS / RESOLVED 는 lock + agentSummary 표시. Copy Prompt 도 timestamp 트래킹해서 stale 검출.

**Architecture:** `AnnotationDto` 에 `lastHandedOffAtEpochMillis` 필드 추가. `Store.updateAnnotation` 의 lock 정책을 `delivery==SENT && status in {IN_PROGRESS, RESOLVED}` 로 완화. `staleAfterHandoff` 는 `updatedAt > lastHandedOffAt` 로 매 응답마다 계산. 신규 endpoint `POST /api/sessions/{sid}/items/mark-handed-off` 가 Copy Prompt 후 timestamp 갱신.

**Tech Stack:** Kotlin (mcp), JUnit 4 + kotlin.test, vanilla JS modules (no JS test runner — spec §6.1), Bash.

**Spec:** `docs/superpowers/specs/2026-05-10-handoff-lifecycle-ux-design.md`

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
  - Current main baseline: `tests=647 failures=0` (post-handoff-prompt-parity merge).
- **Bundle JS:** `fixthis-mcp/src/main/console/*.js` 편집 후 `node scripts/build-console-assets.mjs` → `node --check fixthis-mcp/src/main/resources/console/app.js`.
- **Commit style:** lowercase scope, imperative, one task = one commit.
- **Compatibility:**
  - `lastHandedOffAtEpochMillis` 는 default-null serializer 필드로 추가 — 기존 `session.json` 파일과 forward/backward compatible.
  - 기존 JSON field name (`itemId`, `sessionId`, `delivery`, `status`, ...) 변경 없음.
  - 기존 `/api/agent-handoffs` 본문 shape (`{itemIds:[...]}`) 유지, 응답에 `staleAfterHandoff` 만 additive.

## File map

**Created:**

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/MarkHandedOffRoutes.kt` — `POST /api/sessions/{sid}/items/mark-handed-off`.

**Modified:**

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt` (또는 `AnnotationDto` 정의 위치) — `lastHandedOffAtEpochMillis` 필드 추가.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` — (1) `sendDraftToAgent` 가 `lastHandedOffAt` 갱신 + Re-save 분기, (2) `updateAnnotation` / `deleteDraftItem` lock 정책 phase-aware, (3) `markItemsHandedOff` 메서드 신규.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt` — `markItemsHandedOff` wrapper.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt` 또는 `FeedbackConsolePreviewModels.kt` — `MarkHandedOffRequest` 모델, 응답에 `staleAfterHandoff` 가 자동 포함되도록 SessionDto 직렬화 헬퍼 추가 (또는 응답 enrichment 함수).
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` — 새 route 등록.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt` — `fixthis_list_feedback`, `fixthis_read_feedback` 응답에 `staleAfterHandoff`, `delivery`, `lastHandedOffAtEpochMillis` 추가.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` — 새 테스트들.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` — endpoint 테스트들.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt` — tool 응답 검증.
- `fixthis-mcp/src/main/console/annotations.js` — `lifecyclePhase` / `statusLabel` / `statusClass` refactor.
- `fixthis-mcp/src/main/console/rendering.js` — `renderSavedAnnotationDetail` phase-aware (disabled / 배너 / Re-save / agentSummary), list row 색상 띠.
- `fixthis-mcp/src/main/console/prompt.js` — Copy Prompt 후 `markItemsHandedOff` 호출.
- `fixthis-mcp/src/main/console/index.html` — phase 별 CSS 배지 클래스 (또는 `app.js` 안 인라인 style block).
- `fixthis-mcp/src/main/resources/console/app.js` — rebundle.
- `CHANGELOG.md` — 단일 user-visible bullet.

---

## Task 1 — `AnnotationDto` 에 `lastHandedOffAtEpochMillis` 필드 추가

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt` (또는 AnnotationDto 정의가 있는 파일)
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`

**Goal:** 새 필드를 default-null 로 추가. 기존 JSON 파일 forward compatibility 자동 (kotlinx.serialization 의 default value).

- [ ] **Step 1: AnnotationDto 위치 확인**

```bash
grep -rn "data class AnnotationDto" fixthis-mcp/src/main/ | head -1
```

- [ ] **Step 2: 필드 추가**

`AnnotationDto` 의 (sentAtEpochMillis 인접):

```kotlin
@Serializable
data class AnnotationDto(
    ...
    val sentAtEpochMillis: Long? = null,
    val lastHandedOffAtEpochMillis: Long? = null,  // ← 신규
    ...
)
```

다른 lifecycle field 들과 같은 위치에 두어 일관성 유지. default 는 `null` — 기존 파일 로드 시 자동 적용됨.

- [ ] **Step 3: 마이그레이션 테스트 — 기존 JSON 로드 케이스**

`FeedbackSessionStoreTest.kt` 에 추가 (parity test 와 같은 fixture style):

```kotlin
@Test
fun loadsLegacySessionJsonWithoutLastHandedOffAtField() {
    val sessionId = "legacy-1"
    val storeDir = tempDir.resolve("feedback-sessions").resolve(sessionId)
    storeDir.mkdirs()
    storeDir.resolve("session.json").writeText(
        """
        {
          "schemaVersion":"1.0",
          "sessionId":"$sessionId",
          "packageName":"x",
          "projectRoot":"/r",
          "createdAtEpochMillis":1,
          "updatedAtEpochMillis":1,
          "screens":[],
          "items":[
            {"itemId":"i1","screenId":"s1","createdAtEpochMillis":1,"updatedAtEpochMillis":1,
             "target":{"type":"area","bounds":{"left":0.0,"top":0.0,"right":1.0,"bottom":1.0}},
             "comment":"x","delivery":"sent","status":"ready","sentAtEpochMillis":1}
          ]
        }
        """.trimIndent()
    )
    val store = FeedbackSessionStore(persistence = ondiskPersistence(tempDir))
    val session = store.getSession(sessionId)
    assertEquals(1, session.items.size)
    assertNull(session.items[0].lastHandedOffAtEpochMillis, "legacy field should default to null")
}
```

`tempDir`, `ondiskPersistence` 가 기존 패턴에 없으면 가장 가까운 fixture 를 따라가거나 inline 으로 구현. 실패 → PASS 진행.

- [ ] **Step 4: 모듈 테스트**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest*' 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "$(cat <<'EOF'
mcp: add lastHandedOffAtEpochMillis field to AnnotationDto

New optional field tracks the most recent moment the user handed an
item off to the agent (Save to MCP or Copy Prompt). Used to derive
staleAfterHandoff in subsequent tasks. Defaults to null for backward
compatibility with existing session.json files.

Task: 1
Risk: low
EOF
)"
```

---

## Task 2 — `sendDraftToAgent` 가 `lastHandedOffAt` 갱신 + Re-save 흐름

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`

**Goal:** Save to MCP 시 새로 SENT 가 되는 아이템과 이미 SENT 인 아이템 (Re-save) 모두 `lastHandedOffAt` 갱신. Re-save 시 새 handoff batch 추가.

- [ ] **Step 1: 실패하는 테스트 — sendDraftToAgent 가 lastHandedOffAt 설정**

```kotlin
@Test
fun sendDraftToAgentSetsLastHandedOffAtToSentAt() {
    val store = FeedbackSessionStore(clock = { 1000L }, idGenerator = { "id-1" })
    val session = store.openSession("pkg", projectRoot = "/r")
    store.addItem(session.sessionId, draftItem(itemId = "i1"))
    val updated = store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
    val item = updated.items.first { it.itemId == "i1" }
    assertEquals(1000L, item.sentAtEpochMillis)
    assertEquals(1000L, item.lastHandedOffAtEpochMillis)
}

@Test
fun sendDraftToAgentReSaveUpdatesLastHandedOffAtAndAddsNewBatch() {
    var now = 1000L
    val store = FeedbackSessionStore(clock = { now }, idGenerator = { "batch-${now}" })
    val session = store.openSession("pkg", projectRoot = "/r")
    store.addItem(session.sessionId, draftItem(itemId = "i1"))
    val first = store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap-1")
    assertEquals(1000L, first.items[0].lastHandedOffAtEpochMillis)
    assertEquals(1, first.handoffBatches.size)

    now = 2000L
    val second = store.sendDraftToAgent(
        sessionId = session.sessionId,
        markdownSnapshot = "snap-2",
        targetItemIds = listOf("i1"),
    )
    val item = second.items.first { it.itemId == "i1" }
    assertEquals(2000L, item.lastHandedOffAtEpochMillis, "re-save updates lastHandedOffAt")
    assertEquals(1000L, item.sentAtEpochMillis, "first sentAt is preserved")
    assertEquals(2, second.handoffBatches.size, "re-save creates a new batch")
    assertEquals("batch-2000", second.handoffBatches[1].batchId)
    assertEquals(listOf("i1"), second.handoffBatches[1].itemIds)
}
```

`draftItem(itemId)` 헬퍼는 기존 테스트에 있는 패턴 사용. 둘 다 컴파일 시 fail 또는 runtime 시 fail 예상.

- [ ] **Step 2: store 구현 변경**

`FeedbackSessionStore.sendDraftToAgent` 의 `updatedItems.map` 부분 수정. 현재는 `delivery == DRAFT && (targetSet == null || itemId in targetSet)` 만 처리. Re-save 케이스 추가:

```kotlin
val updatedItems = session.items.map { item ->
    val matchesTarget = targetSet == null || item.itemId in targetSet
    when {
        item.delivery == FeedbackDelivery.DRAFT && matchesTarget -> {
            item.copy(
                delivery = FeedbackDelivery.SENT,
                handoffBatchId = batch.batchId,
                sentAtEpochMillis = now,
                lastHandedOffAtEpochMillis = now,
                status = AnnotationStatusDto.READY,
                updatedAtEpochMillis = now,
            )
        }
        item.delivery == FeedbackDelivery.SENT &&
            item.status == AnnotationStatusDto.READY &&
            matchesTarget -> {
            // Re-save: sentAt 보존, lastHandedOffAt + batch 갱신
            item.copy(
                handoffBatchId = batch.batchId,
                lastHandedOffAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        }
        else -> item
    }
}
```

`candidateDrafts` 계산 (batch.itemIds 결정용) 도 같이 수정 — Re-save 대상 SENT 아이템도 포함해야 batch 에 들어감:

```kotlin
val candidates = session.items.filter { item ->
    val matchesTarget = targetSet == null || item.itemId in targetSet
    matchesTarget && (
        item.delivery == FeedbackDelivery.DRAFT ||
        (item.delivery == FeedbackDelivery.SENT && item.status == AnnotationStatusDto.READY)
    )
}
if (candidates.isEmpty()) {
    throw FeedbackSessionException("NO_DRAFT_FEEDBACK: No items eligible for handoff")
}
val batch = FeedbackHandoffBatch(
    batchId = idGenerator(),
    sequenceNumber = session.handoffBatches.size + 1,
    createdAtEpochMillis = now,
    itemIds = candidates.map { it.itemId },
    markdownSnapshot = markdownSnapshot,
)
```

`IN_PROGRESS` / `RESOLVED` 인 아이템은 Re-save 대상에서 제외 (자동으로 `else -> item` 가지로 빠짐) — 여기 명시적으로 거부 throw 도 한 번 검토. 첫 버전은 silent skip 으로 (caller 가 ITEM_NOT_EDITABLE 에 해당하는 케이스인지 알 책임).

- [ ] **Step 3: 새 테스트 PASS 확인**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest*' 2>&1 | tail -10
```

- [ ] **Step 4: 기존 테스트 영향 점검**

```bash
./gradlew :fixthis-mcp:test 2>&1 | tail -10
```

기존 `sendDraftToAgent` 호출이 `NO_DRAFT_FEEDBACK` 에러 메시지에 의존했다면 (메시지가 약간 변함) 업데이트.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "$(cat <<'EOF'
mcp: sendDraftToAgent updates lastHandedOffAt and supports re-save

DRAFT items become SENT and get sentAt + lastHandedOffAt set.
SENT/READY items already in the session also enter the new handoff
batch (re-save) and have lastHandedOffAt + handoffBatchId refreshed.
sentAt is preserved on re-save. Items in IN_PROGRESS or RESOLVED
state are silently skipped (caller decides whether the route allows it).

Task: 2
Risk: mid
EOF
)"
```

---

## Task 3 — Edit lock 정책 phase-aware 로 변경

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`

**Goal:** `updateAnnotation` 과 `deleteDraftItem` 의 lock 을 IN_PROGRESS / RESOLVED 일 때만 적용. SENT(ready) 는 편집/삭제 허용.

- [ ] **Step 1: 실패하는 테스트 — SENT(ready) 는 편집 가능, IN_PROGRESS / RESOLVED 는 차단**

```kotlin
@Test
fun updateAnnotationAllowsSentReadyComment() {
    val store = FeedbackSessionStore(clock = { 1000L }, idGenerator = { "id" })
    val session = store.openSession("pkg", projectRoot = "/r")
    store.addItem(session.sessionId, draftItem(itemId = "i1"))
    store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
    // status=ready, delivery=sent — should be editable
    val updated = store.updateAnnotation(
        sessionId = session.sessionId,
        itemId = "i1",
        comment = "edited after send",
    )
    val item = updated.items.first { it.itemId == "i1" }
    assertEquals("edited after send", item.comment)
    assertTrue(item.updatedAtEpochMillis > item.sentAtEpochMillis!!,
        "updatedAt must reflect the post-send edit")
}

@Test
fun updateAnnotationRejectsInProgress() {
    val store = freshStoreWithSentItem()
    store.markItemInProgress(session.sessionId, "i1")  // 또는 service.claimItem
    assertFailsWith<FeedbackSessionException> {
        store.updateAnnotation(session.sessionId, "i1", comment = "x")
    }.also { assertTrue(it.message!!.contains("ITEM_NOT_EDITABLE")) }
}

@Test
fun updateAnnotationRejectsResolved() {
    val store = freshStoreWithSentItem()
    store.markItemResolved(session.sessionId, "i1", summary = "done")
    assertFailsWith<FeedbackSessionException> {
        store.updateAnnotation(session.sessionId, "i1", comment = "x")
    }
}

@Test
fun deleteDraftItemAllowsSentReady() {
    val store = freshStoreWithSentItem()
    val updated = store.deleteDraftItem(session.sessionId, "i1")
    assertTrue(updated.items.none { it.itemId == "i1" })
}

@Test
fun deleteDraftItemRejectsInProgress() {
    val store = freshStoreWithSentItem()
    store.markItemInProgress(session.sessionId, "i1")
    assertFailsWith<FeedbackSessionException> {
        store.deleteDraftItem(session.sessionId, "i1")
    }
}
```

`freshStoreWithSentItem` / `markItemInProgress` / `markItemResolved` 는 fixture helper — 기존 store 메서드 이름에 맞춤 (claim / resolve 같은 메서드가 store 또는 service 에 있는지 grep). 없으면 inline 으로 status 만 바꾸는 helper 를 테스트 파일에 추가.

- [ ] **Step 2: store 구현 변경**

```kotlin
fun updateAnnotation(...): SessionDto = synchronized(lock) {
    ...
    val updatedItems = session.items.map { item ->
        if (item.itemId != itemId) return@map item
        found = true
        if (isLocked(item)) {
            throw FeedbackSessionException(
                "ITEM_NOT_EDITABLE: Agent has claimed this item: $itemId"
            )
        }
        item.copy(...)
    }
    ...
}

private fun isLocked(item: AnnotationDto): Boolean =
    item.delivery == FeedbackDelivery.SENT &&
        item.status in setOf(AnnotationStatusDto.IN_PROGRESS, AnnotationStatusDto.RESOLVED)
```

`deleteDraftItem` 도 동일 helper 사용. 함수명이 `deleteDraftItem` 이지만 의미상 이제 "DRAFT-or-SENT item delete" 라 약간 misnomer — 일단 호출처 파급 막기 위해 이름 유지. (Future: rename 또는 deprecation alias.)

- [ ] **Step 3: 테스트 PASS 확인**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStoreTest*' 2>&1 | tail -10
```

- [ ] **Step 4: 기존 SENT/RESOLVED 거부 assert 하던 테스트 확인 + 메시지 변화 반영**

```bash
grep -rn 'ITEM_NOT_EDITABLE' fixthis-mcp/src/test/ --include='*.kt' | head -10
```

매치되는 테스트가 있다면 메시지를 새 문구 ("Agent has claimed this item") 에 맞춰 업데이트. 또는 `contains("ITEM_NOT_EDITABLE")` 형태로 substring 검사면 그대로.

- [ ] **Step 5: 모듈 sweep**

```bash
./gradlew :fixthis-mcp:test 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt
git commit -m "$(cat <<'EOF'
mcp: edit lock applies only when agent has claimed the item

updateAnnotation and deleteDraftItem now reject mutations only when the
item is delivery=SENT and status in {IN_PROGRESS, RESOLVED}. Items in
DRAFT or SENT/READY remain editable so users can fix typos or re-send
after a tweak. The race window narrows to the agent's actual work
period, matching the ITEM_NOT_EDITABLE message ("Agent has claimed
this item").

Task: 3
Risk: mid
EOF
)"
```

---

## Task 4 — 신규 endpoint `POST /api/sessions/{sid}/items/mark-handed-off`

**Files:**

- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/MarkHandedOffRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsolePreviewModels.kt` (or sibling) — `MarkHandedOffRequest`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` — `markItemsHandedOff` method
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt` — wrapper
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` — register
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

**Goal:** Copy Prompt 가 클립보드 쓰기 성공 후 호출하는 endpoint. itemIds 의 `lastHandedOffAt` 만 갱신.

- [ ] **Step 1: 실패하는 acceptance 테스트 4개**

`FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun markHandedOffEndpointUpdatesLastHandedOffAtForItems() {
    val (store, service) = newServiceWithItem(itemId = "i1")
    val server = FeedbackConsoleServer(service = service, port = 0).also { it.start() }
    try {
        val client = ConsoleHttpTestClient(server.url)
        val response = client.postJson(
            path = "/api/sessions/${service.requireCurrentSession().sessionId}/items/mark-handed-off",
            body = """{"itemIds":["i1"]}""",
        )
        assertEquals(200, response.statusCode)
        val session = store.getSession(service.requireCurrentSession().sessionId)
        val item = session.items.first { it.itemId == "i1" }
        assertNotNull(item.lastHandedOffAtEpochMillis, "expected timestamp set")
    } finally { server.stop() }
}

@Test
fun markHandedOffEndpointRejectsEmptyItemIds() {
    ... (400, application/json, body contains "error")
}

@Test
fun markHandedOffEndpointReturns404ForUnknownSession() {
    ... (POST to /api/sessions/00000000-.../items/mark-handed-off → 404)
}

@Test
fun markHandedOffEndpointRequiresConsoleToken() {
    ... (POST without X-FixThis-Console-Token header → 403)
}
```

`newServiceWithItem` 는 기존 헬퍼 패턴 (Task 2 의 server tests 처럼) 사용.

- [ ] **Step 2: `MarkHandedOffRequest` 모델**

```kotlin
@Serializable
data class MarkHandedOffRequest(
    val itemIds: List<String> = emptyList(),
)
```

- [ ] **Step 3: Store + Service 메서드**

`FeedbackSessionStore.kt`:

```kotlin
fun markItemsHandedOff(sessionId: String, itemIds: List<String>): SessionDto =
    synchronized(lock) {
        val session = getSessionLocked(sessionId)
        val targetSet = itemIds.toSet()
        if (targetSet.isEmpty()) throw FeedbackSessionException("itemIds must not be empty")
        val now = clock()
        val updatedItems = session.items.map { item ->
            if (item.itemId in targetSet) {
                item.copy(
                    lastHandedOffAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
            } else item
        }
        val updated = session.copy(
            items = updatedItems,
            updatedAtEpochMillis = now,
        )
        commitSessionMutation(session, updated)
    }
```

`FeedbackSessionService.kt`:

```kotlin
fun markItemsHandedOff(sessionId: String, itemIds: List<String>): SessionDto =
    store.markItemsHandedOff(sessionId, itemIds)
```

- [ ] **Step 4: Route 등록**

`MarkHandedOffRoutes.kt`:

```kotlin
internal class MarkHandedOffRoutes(private val service: FeedbackSessionService) : ConsoleRoute {
    private val pathPrefix = "/api/sessions/"
    private val pathSuffix = "/items/mark-handed-off"

    override fun matches(path: String): Boolean =
        path.startsWith(pathPrefix) && path.endsWith(pathSuffix) &&
            path.length > pathPrefix.length + pathSuffix.length

    override fun handle(exchange: HttpExchange) {
        val raw = exchange.requestURI.path
        if (!matches(raw)) return
        val sessionId = raw.removePrefix(pathPrefix).removeSuffix(pathSuffix)
        if (sessionId.isBlank()) throw FeedbackConsoleHttpException(404, "session not found")
        exchange.requireMethod("POST") {
            val body = exchange.decodeJsonBody(
                MarkHandedOffRequest.serializer(),
                blankValue = MarkHandedOffRequest()
            )
            if (body.itemIds.isEmpty()) {
                throw FeedbackConsoleHttpException(400, "itemIds must not be empty")
            }
            val session = try {
                service.markItemsHandedOff(sessionId, body.itemIds)
            } catch (e: FeedbackSessionException) {
                throw FeedbackConsoleHttpException(404, "session not found")
            }
            exchange.sendJson(200, session)  // SessionDto serializer overload
        }
    }
}
```

`FeedbackConsoleServer.kt` 의 route list 에 `MarkHandedOffRoutes(service)` 추가.

`sendJson(SessionDto)` overload 가 없으면 `ConsoleHttp.kt` 에 추가. 또는 mark-handed-off 가 `{session: SessionDto}` 같은 wrapper 응답을 사용해서 기존 sendJson 활용.

- [ ] **Step 5: 테스트 PASS 확인**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' 2>&1 | tail -20
```

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/MarkHandedOffRoutes.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsolePreviewModels.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "$(cat <<'EOF'
mcp: add /api/sessions/{sid}/items/mark-handed-off endpoint

Lets the browser tell the server "user just copied these items'
prompt to the clipboard". The server bumps lastHandedOffAtEpochMillis
on each matching item so subsequent edits show as stale-after-handoff.
400 on empty itemIds, 404 on unknown session, 403 without console
token. Items not in the session are silently skipped (mirrors the
GC-tolerant pattern used by handoff-preview).

Task: 4
Risk: mid
EOF
)"
```

---

## Task 5 — 응답에 `staleAfterHandoff` 노출 + MCP tools 응답 enrich

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt` (또는 응답 직렬화 transformer)
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt`

**Goal:** HTTP 와 MCP 응답 모두 각 item 에 파생 boolean `staleAfterHandoff` 와 영속 필드 `lastHandedOffAtEpochMillis` 노출.

- [ ] **Step 1: `staleAfterHandoff` 계산 위치 결정**

옵션 A: `AnnotationDto` 의 computed property (직렬화 transient — `@Transient` 또는 별도 wrapper class).
옵션 B: 응답 직전에 enrich 하는 `SessionDto.toResponse()` 함수.

권장 — **옵션 B**. 이유: `staleAfterHandoff` 는 영속 안 함 (spec §10 Q4). 모델에 들어가면 직렬화 시 매번 무시 처리 필요해 복잡.

`SessionResponse` / `AnnotationResponse` 같은 별도 DTO 를 만들거나, JsonObject manipulation 으로 inline enrich. 가장 단순한 방법:

```kotlin
// fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/SessionResponseEnrichment.kt (new file)
internal fun enrichItemsWithStaleness(session: SessionDto): JsonObject {
    val base = fixThisJson.encodeToJsonElement(SessionDto.serializer(), session).jsonObject
    val items = base["items"]?.jsonArray ?: return base
    val enrichedItems = items.map { itemEl ->
        val obj = itemEl.jsonObject
        val updatedAt = obj["updatedAtEpochMillis"]?.jsonPrimitive?.long ?: 0L
        val handedOff = obj["lastHandedOffAtEpochMillis"]?.jsonPrimitive?.longOrNull
        val stale = handedOff != null && updatedAt > handedOff
        JsonObject(obj + ("staleAfterHandoff" to JsonPrimitive(stale)))
    }
    return JsonObject(base + ("items" to JsonArray(enrichedItems)))
}
```

호출처: `SessionRoutes`, `FeedbackItemRoutes`, `MarkHandedOffRoutes` 등 SessionDto 를 응답으로 보내는 모든 곳에서 sendJson 호출 직전에 적용.

- [ ] **Step 2: 실패하는 테스트**

```kotlin
@Test
fun sessionResponseIncludesStaleAfterHandoffFalseInitially() {
    ... // GET /api/session, item.staleAfterHandoff == false
}

@Test
fun sessionResponseStaleAfterHandoffTrueWhenUpdatedAfterSend() {
    ... // sendDraft → updateAnnotation → GET → staleAfterHandoff == true
}

@Test
fun sessionResponseStaleAfterHandoffFalseAfterReSave() {
    ... // sendDraft → updateAnnotation → re-sendDraft → GET → staleAfterHandoff == false
}
```

- [ ] **Step 3: enrichment 적용**

`sendJson(statusCode, session)` 호출처를 모두 찾아 새 `enrichItemsWithStaleness(session)` 결과로 sendJson 호출. 또는 `sendJson(SessionDto)` overload 자체를 enrichment 포함하도록 수정.

```bash
grep -rn 'sendJson(.*session\|sendJson(.*SessionDto' fixthis-mcp/src/main/kotlin/ --include='*.kt' | head -15
```

- [ ] **Step 4: MCP tools 응답 enrichment**

`FixThisTools.kt` 의 `fixthis_list_feedback`, `fixthis_read_feedback` 응답 빌더에서 같은 함수 사용 또는 inline 계산.

- [ ] **Step 5: McpProtocolTest 업데이트**

```kotlin
@Test
fun listFeedbackToolResponseIncludesStaleAfterHandoff() {
    ... // tool 호출 응답에 items[*].staleAfterHandoff 존재
}
```

- [ ] **Step 6: 모듈 sweep**

```bash
./gradlew :fixthis-mcp:test 2>&1 | tail -10
```

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/SessionResponseEnrichment.kt \
        fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "$(cat <<'EOF'
mcp: surface staleAfterHandoff and lastHandedOffAt in responses

Every HTTP and MCP response that carries an annotation now reports
staleAfterHandoff (derived: updatedAt > lastHandedOffAt) plus the
underlying lastHandedOffAtEpochMillis field. Lets browser and agents
detect "user modified after Save/Copy" without keeping their own state.

Task: 5
Risk: mid
EOF
)"
```

---

## Task 6 — JS lifecyclePhase + 라벨/배지 refactor

**Files:**

- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/index.html` (CSS) 또는 별도 css module
- Rebundle: `fixthis-mcp/src/main/resources/console/app.js`

**Goal:** 4-phase 라벨 + 배지 색상. List row 색상 띠.

- [ ] **Step 1: `lifecyclePhase` / `statusLabel` / `statusClass` refactor**

`annotations.js` 의 기존 `statusLabel(status)` / `statusClass(status)` 를 `(item)` 인자로 변경. 호출처 (rendering.js) 도 같이 수정.

```js
function lifecyclePhase(item) {
    const status = String(item?.status || 'open');
    if (status === 'resolved') return 'resolved';
    if (status === 'in_progress' || status === 'in-progress') return 'in_progress';
    if (item?.delivery === 'sent') {
        return item?.staleAfterHandoff ? 'sent_modified' : 'sent';
    }
    return 'draft';
}

function statusLabel(item) {
    switch (lifecyclePhase(item)) {
        case 'resolved': return 'Resolved';
        case 'in_progress': return 'In Progress';
        case 'sent_modified': return 'Sent · Modified';
        case 'sent': return 'Sent';
        default: return 'Draft';
    }
}

function statusClass(item) {
    return 'st-' + lifecyclePhase(item).replace('_', '-');
}
```

- [ ] **Step 2: 호출처 마이그레이션**

`rendering.js` 의 `statusLabel(status)`, `statusClass(status)` 호출을 `statusLabel(item)`, `statusClass(item)` 으로 변경 (boolean status string → item 객체).

```bash
grep -n "statusLabel\|statusClass" fixthis-mcp/src/main/console/rendering.js | head -15
```

- [ ] **Step 3: CSS 배지 색상**

`fixthis-mcp/src/main/console/index.html` (또는 인라인 style block) 에 추가:

```css
.st-draft { background: var(--neutral-200, #e5e7eb); color: var(--text, #111); }
.st-sent { background: var(--amber-200, #fde68a); color: var(--text, #111); }
.st-sent-modified {
  background: var(--amber-300, #fcd34d);
  border: 1px solid var(--amber-500, #f59e0b);
  position: relative;
}
.st-sent-modified::before { content: '⚠ '; }
.st-in-progress { background: var(--blue-200, #bfdbfe); color: var(--text, #111); }
.st-in-progress::before { content: '🔒 '; }
.st-resolved { background: var(--green-200, #bbf7d0); color: var(--text, #111); }
.st-resolved::before { content: '✓ '; }

.ann-row[data-phase="draft"] { border-left: 4px solid var(--neutral-400, #9ca3af); }
.ann-row[data-phase="sent"] { border-left: 4px solid var(--amber-500, #f59e0b); }
.ann-row[data-phase="sent_modified"] { border-left: 4px solid var(--amber-700, #b45309); }
.ann-row[data-phase="in_progress"] { border-left: 4px solid var(--blue-500, #3b82f6); }
.ann-row[data-phase="resolved"] { border-left: 4px solid var(--green-500, #22c55e); opacity: 0.85; }
```

기존 css 변수 토큰이 다르면 (`--bg-0` 등 사용 중) 그것에 맞춤.

- [ ] **Step 4: List row 에 `data-phase` 속성**

`rendering.js` 의 row 생성 코드에서:

```js
'<button type="button" class="ann-row" data-phase="' + lifecyclePhase(item) + '" ...>'
```

(또는 button 의 inline style 에 border-left 직접.)

- [ ] **Step 5: 번들**

```bash
node scripts/build-console-assets.mjs
node --check fixthis-mcp/src/main/resources/console/app.js
```

- [ ] **Step 6: 모듈 sweep (서버 테스트는 영향 없어야 함)**

```bash
./gradlew :fixthis-mcp:test 2>&1 | tail -10
```

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/annotations.js \
        fixthis-mcp/src/main/console/rendering.js \
        fixthis-mcp/src/main/console/index.html \
        fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
console: lifecycle-aware status labels and badges

Replaces statusLabel/statusClass(status) with (item)-aware variants
that derive lifecyclePhase from delivery + status + staleAfterHandoff.
Adds CSS for Draft/Sent/Sent-Modified/In-Progress/Resolved badges
plus a per-row left-border color stripe. The user can now see at a
glance which phase each annotation is in and whether it was
modified after Save.

Task: 6
Risk: mid
EOF
)"
```

---

## Task 7 — Detail 화면 phase-aware (disabled / 배너 / Re-save / agentSummary)

**Files:**

- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Rebundle: `fixthis-mcp/src/main/resources/console/app.js`

**Goal:** SENT_MODIFIED 면 Re-save 버튼 + 경고 배너; IN_PROGRESS / RESOLVED 면 모든 input/button disabled + 배너; RESOLVED 는 agentSummary 영역.

- [ ] **Step 1: `renderSavedAnnotationDetail` 의 phase 결정**

함수 상단:

```js
const phase = lifecyclePhase(item);
const editable = phase === 'draft' || phase === 'sent' || phase === 'sent_modified';
```

- [ ] **Step 2: input 들에 disabled 속성**

```js
const dis = editable ? '' : ' disabled';
```

각 textarea / input / button 에 `dis` 적용. severity / status segmented buttons 도 마찬가지. delete 버튼은 phase 가 in_progress / resolved 면 disabled.

- [ ] **Step 3: 배너 + Re-save 버튼**

detail 화면 상단 (back 버튼 다음):

```js
const banner = (() => {
    if (phase === 'sent_modified') {
        return '<div class="annotation-banner annotation-banner-warn">' +
            '<span>⚠ Modified after Save — agent will see the previous version.</span>' +
            '<button type="button" class="annotation-resave" data-resave-current>Re-save</button>' +
            '</div>';
    }
    if (phase === 'sent') {
        const ts = item.lastHandedOffAtEpochMillis ? formatTime(item.lastHandedOffAtEpochMillis) : '';
        return '<div class="annotation-banner annotation-banner-info">' +
            'Sent to MCP' + (ts ? ' · ' + escapeHtml(ts) : '') +
            '. Modify to refine before agent picks up.' +
            '</div>';
    }
    if (phase === 'in_progress') {
        return '<div class="annotation-banner annotation-banner-locked">' +
            '🔒 Agent working on this — edits locked.' +
            '</div>';
    }
    if (phase === 'resolved') {
        const summary = item.agentSummary ? escapeHtml(item.agentSummary) : '(no summary provided)';
        return '<div class="annotation-banner annotation-banner-done">' +
            '<div>✓ Agent completed</div>' +
            '<pre class="annotation-summary">' + summary + '</pre>' +
            '</div>';
    }
    return '';
})();
```

CSS 배너 클래스도 추가 (`index.html`):

```css
.annotation-banner { margin: 8px 0; padding: 10px 12px; border-radius: 6px; }
.annotation-banner-info { background: var(--amber-100); }
.annotation-banner-warn { background: var(--amber-200); border: 1px solid var(--amber-500); display: flex; justify-content: space-between; align-items: center; }
.annotation-banner-locked { background: var(--blue-100); }
.annotation-banner-done { background: var(--green-100); }
.annotation-summary { white-space: pre-wrap; max-height: 240px; overflow-y: auto; margin-top: 8px; }
.annotation-resave { padding: 4px 12px; }
```

- [ ] **Step 4: Re-save 버튼 핸들러**

```js
draftItems.querySelectorAll('[data-resave-current]').forEach(button => {
    button.addEventListener('click', async () => {
        try {
            const result = await requestJson('/api/agent-handoffs', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ itemIds: [item.itemId] }),
            });
            state.session = result.session;
            renderInspectorRegion();
            renderPreviewOnly();
            showSuccess('Re-saved to MCP ✓', 2000);
        } catch (error) {
            showError(error);
        }
    });
});
```

- [ ] **Step 5: 비-편집 phase 의 click 핸들러 skip**

기존 `[data-set-status]` / `[data-set-severity]` / textarea blur / label change 핸들러들은 이미 `persistSavedEvidenceItem` 호출. 이것들이 IN_PROGRESS / RESOLVED 일 때 호출되면 서버가 거부 → showError. 회피:

```js
draftItems.querySelectorAll('[data-set-status]').forEach(button => {
    button.addEventListener('click', () => {
        if (!editable) return;  // ← 추가
        item.status = button.dataset.setStatus;
        persistSavedEvidenceItem(item, editSessionId)...
    });
});
```

textarea / input 도 disabled 면 input 이벤트 자체가 안 발생하지만, 방어적으로 핸들러 안에서 `if (!editable) return;` 추가.

- [ ] **Step 6: SENT 케이스의 back 핸들러 — 기존 fix (commit `781da48`) 와 정합**

이전 follow-up 에서 `delivery === 'sent'` 면 persist skip 하고 navigate. 새 정책에서는 SENT(ready) 도 편집 가능하므로 다시 persist 호출 필요. 단 IN_PROGRESS / RESOLVED 면 skip.

```js
draftItems.querySelectorAll('[data-back-saved-annotations]').forEach(button => {
    button.addEventListener('click', () => {
        const goBack = () => {
            focusedSavedItemId = null;
            focusedSavedSessionId = null;
            renderPreviewOnly();
            renderInspectorRegion();
        };
        if (!editable) {
            goBack();  // locked phases — navigate without persist attempt
        } else {
            persistSavedEvidenceItem(item, editSessionId)
                .then(goBack)
                .catch(error => { showError(error); goBack(); });
        }
    });
});
```

- [ ] **Step 7: 번들 + manual smoke 직전 확인**

```bash
node scripts/build-console-assets.mjs
node --check fixthis-mcp/src/main/resources/console/app.js
./gradlew :fixthis-mcp:test 2>&1 | tail -10
```

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/console/rendering.js \
        fixthis-mcp/src/main/console/index.html \
        fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
console: phase-aware annotation detail UI

renderSavedAnnotationDetail now branches on lifecyclePhase:

- DRAFT / SENT / SENT_MODIFIED: editable inputs + appropriate banner
- SENT_MODIFIED: explicit "⚠ Modified after Save" banner with Re-save button
- IN_PROGRESS: 🔒 locked banner, all inputs disabled
- RESOLVED: ✓ completed banner, agentSummary shown read-only,
  inputs disabled

The Re-save button calls /api/agent-handoffs with the single
itemId so the user can refresh the agent's view after a tweak.
Back-navigation skips persist attempts in locked phases (matches the
earlier fix that prevented stuck-on-detail when persist 409s).

Task: 7
Risk: mid
EOF
)"
```

---

## Task 8 — Copy Prompt 가 mark-handed-off 호출

**Files:**

- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Rebundle: `fixthis-mcp/src/main/resources/console/app.js`

**Goal:** 클립보드 쓰기 성공 후 서버에 lastHandedOffAt 갱신 알림. Save to MCP 와 대칭.

- [ ] **Step 1: `markItemsHandedOff` helper**

`prompt.js`:

```js
async function markItemsHandedOff(sessionId, itemIds) {
    return await requestJson(
        '/api/sessions/' + encodeURIComponent(sessionId) + '/items/mark-handed-off',
        {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ itemIds })
        }
    );
}
```

- [ ] **Step 2: `copyPrompt` 에 통합**

```js
async function copyPrompt() {
    if (promptActionInFlight) return;
    await withMutationLock(async () => {
        ...
        try {
            const itemIds = await persistAndCollectItemIds();
            const markdown = await fetchHandoffPreview(state.session.sessionId, itemIds);
            await copyTextToClipboard(markdown);
            copied = true;
            // NEW: tell server about the handoff so staleAfterHandoff works
            try {
                const updated = await markItemsHandedOff(state.session.sessionId, itemIds);
                state.session = updated;
                renderInspectorRegion();  // refresh badges
            } catch (markError) {
                // Clipboard write succeeded — silently ignore mark errors.
                // The user already has the prompt; this only impairs stale detection.
            }
        } finally { ... }
    });
}
```

- [ ] **Step 3: 번들**

```bash
node scripts/build-console-assets.mjs
node --check fixthis-mcp/src/main/resources/console/app.js
```

- [ ] **Step 4: 모듈 sweep**

```bash
./gradlew :fixthis-mcp:test 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/prompt.js \
        fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
console: Copy Prompt notifies server of handoff timestamp

After the clipboard write succeeds, the browser POSTs to the new
/api/sessions/{sid}/items/mark-handed-off endpoint so the server can
bump lastHandedOffAtEpochMillis. The badge updates to "Sent" and any
subsequent edit transitions the item to "Sent · Modified" with the
Re-save / Re-copy nudge.

Mark errors are intentionally silenced — the clipboard write is the
user-facing success criterion, and a missed timestamp only impairs
stale-detection (a fallback that fixthis_read_feedback already
covers via lastHandedOffAtEpochMillis on the read path).

Task: 8
Risk: low
EOF
)"
```

---

## Task 9 — CHANGELOG + manual smoke + full regression

**Files:**

- Modify: `CHANGELOG.md`

**Goal:** 사용자 가시 변경 기록 + 5-module regression + 사람 한 번 manual smoke.

- [ ] **Step 1: CHANGELOG entry**

```markdown
- Added: Annotation lifecycle is now visualized in 4 phases — Draft / Sent / In Progress / Resolved — with distinct badge colors and a left-border stripe per row. Sent items remain editable; modifying one after Save (or Copy Prompt) raises a "⚠ Modified after Save" banner with a Re-save button so the agent gets the up-to-date version. In Progress and Resolved items are locked, with the agent's resolution summary surfaced inline. Tracked via the new `lastHandedOffAtEpochMillis` field on each annotation and the derived `staleAfterHandoff` flag on every list / read response.
```

surrounding 스타일 (`Added:` / `Improved:` / `Fixed:`) 매치.

- [ ] **Step 2: Full 5-module regression**

```bash
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test 2>&1 | tail -20
```

기대: BUILD SUCCESSFUL. 새 테스트 (~10-15개) 추가됐으니 baseline 647 → 약 660+.

- [ ] **Step 3: Manual smoke (mandatory before merge)**

spec §6.3 의 11개 체크리스트 중 최소 다음 8개:

- [ ] DRAFT → list row 회색, 라벨 "Draft", input 활성
- [ ] Save to MCP → 노랑 띠, "Sent" 라벨
- [ ] 코멘트 수정 → 진한 노랑 + ⚠, "Sent · Modified", Re-save 버튼
- [ ] **Re-save** → 새 batch, 라벨 "Sent" 복귀
- [ ] Copy Prompt → "Sent" 라벨 즉시 갱신, mark-handed-off 200
- [ ] `fixthis_claim_feedback` → 파랑 + 🔒, 모든 input disabled, "← All annotations" 즉시 navigation
- [ ] `fixthis_resolve_feedback` → 초록 + ✓, agentSummary 표시
- [ ] In_progress / resolved 에서 status / severity 세그먼트 클릭 → 무반응 (disabled)

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for handoff lifecycle UX"
```

---

## Out of scope (intentionally deferred)

- **Stale 정확도** — 코멘트 변경에만 stale 트리거 (현재는 모든 mutation). spec §9.
- **`handoffBatches` 페이징** — 100+ Re-save 시.
- **에이전트 자동 re-read** — `fixthis_claim_feedback` 가 `staleAfterHandoff=true` 면 자동 prompt 갱신.
- **Cross-process cache invalidation (H8)** — 별도 plan.
- **Confirm dialog on SENT delete** — UI-only.
- **Re-copy 버튼** — Copy 후 수정 시 detail 에 Re-copy 표시 (현재는 Copy Prompt 다시 누르면 됨).
- **Diff 표시** — Stale 항목의 변경 내역.

## Risk register

- **R1: 기존 테스트가 SENT(ready) 편집 거부 assert** — Task 3 Step 4 에서 grep + 메시지 매칭 업데이트.
- **R2: `staleAfterHandoff` false positive** — status/severity 변경만 해도 stale. 첫 버전은 OK, future work.
- **R3: SENT(ready) 인데 `lastHandedOffAt` 없는 (마이그레이션 시점) 아이템은 첫 비교에서 stale=false** — 사용자 코멘트 변경해도 ⚠ 안 뜸. transient 한계 — 다음 Save/Copy 부터 정상.
- **R4: Detail 화면의 disabled textarea 가 select 도 막음** — readonly 속성 사용 또는 read-only `<div>`. Task 7 Step 2 에서 결정.
- **R5: 매우 긴 `agentSummary` 가 detail 깨뜨림** — `<pre>` + max-height + overflow scroll. Task 7 Step 3 의 css 처리.
- **R6: Re-save 가 spurious batch 양산** — 사용자가 코멘트 안 바꾸고 Re-save 누르면 의미 없는 batch 추가. UI 측에서 `staleAfterHandoff=false` 면 Re-save 버튼 안 보이므로 자연 가드.

## Self-review checklist (after implementation)

- `lastHandedOffAtEpochMillis` 영속 + `staleAfterHandoff` 파생이 의도대로 동작 (Task 5 의 3개 시나리오 테스트 PASS).
- SENT(ready) 편집은 200, IN_PROGRESS / RESOLVED 편집은 409 with `ITEM_NOT_EDITABLE` 메시지.
- 4 phase 라벨/배지/색상이 모두 콘솔에서 시각적으로 구별됨.
- "← All annotations" 뒤로가기가 모든 phase 에서 항상 동작 (PATCH 실패에 막히지 않음).
- Re-save 클릭 시 새 handoff batch 추가, `staleAfterHandoff` 즉시 false.
- `agentSummary` 가 RESOLVED detail 에서 읽히게 표시.
- Copy Prompt 후 라벨이 "Sent" 로 즉시 갱신 (Network 탭에 mark-handed-off 200).
- 번들 size 변화 합리적 (~5-10 KB 증가 — 새 helper, CSS).
- 5-module regression 그린, 신규 테스트 ~10-15개 추가 (baseline 647 → ~660).
- Manual smoke 8/8 통과.
