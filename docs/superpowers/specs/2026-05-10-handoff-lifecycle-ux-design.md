# Handoff Lifecycle UX — Design

**Date:** 2026-05-10
**Status:** Draft (pending implementation)
**Owner:** kws
**Related plan:** `docs/superpowers/plans/2026-05-10-handoff-lifecycle-ux.md`

---

## 1. Problem

핸드오프 프롬프트 일원화(`2026-05-10-handoff-prompt-parity`)가 머지된 후, 콘솔에서 어노테이션 상태 표시와 편집 정책이 사용자 멘탈 모델과 맞지 않는 두 가지 UX 문제가 드러났다.

### 1.1 라벨이 lifecycle 을 표현하지 못함

`FeedbackSessionStore.sendDraftToAgent` 는 Save to MCP 시 어노테이션의 `status` 를 강제로 `READY` 로 바꾼다 (`FeedbackSessionStore.kt:215`). 그러나 콘솔 JS 의 `statusLabel(status)` 는 `'ready'` 를 모르고 fallback 으로 `"Open"` 을 반환한다 (`annotations.js:51-55`). 그 결과:

- 사용자가 Save to MCP 를 눌러서 어노테이션이 SENT(ready) 가 됐는데도 UI 라벨은 그대로 "Open" — "보냈는지 안 보냈는지" 시각적으로 구분 불가.
- 에이전트가 claim 해서 IN_PROGRESS 로 전이됐을 때만 라벨이 "In-progress" 로 바뀜 — 그 사이의 "보냈으나 누가 받지 않은 대기 상태" 가 UI 에 존재하지 않음.
- RESOLVED 후의 `agentSummary` ("에이전트가 뭘 했는지") 는 디스크 JSON 에만 있고 UI 에 표시 안 됨.

라이프사이클은 실제로 4단계 (`open / ready / in_progress / resolved`) 인데 UI 는 3단계 (`Open / In-progress / Resolved`) 로 만 표현하고 있다.

### 1.2 편집 정책이 잘못된 곳에서 막음

현재 정책 (`FeedbackSessionStore.kt:328-330`):

```kotlin
if (item.delivery != FeedbackDelivery.DRAFT) {
    throw FeedbackSessionException("ITEM_NOT_EDITABLE: Only draft feedback items can be edited: $itemId")
}
```

= **DRAFT 가 아닌 모든 아이템 편집 차단**. 즉 Save to MCP 직후 (status=ready) 부터 사용자는 코멘트조차 못 고침. 그러나 race 위험은 IN_PROGRESS 부터 (에이전트가 명시적으로 claim 해서 작업 중) 본격적으로 발생한다. SENT(ready) 상태 — 큐에 들어갔지만 아직 누구도 안 받은 상태 — 까지 lock 거는 건 다음 두 가지 흔한 시나리오에서 사용자에게 답답함을 줌:

- Save 직후 오타 발견 ("여기배경 레드로" → "여기배경 빨강으로") — 코멘트만 살짝 고치고 다시 보내고 싶은데 lock.
- Copy Prompt 후 코멘트 변경 — Copy 는 서버에 표시조차 안 되므로 lock 도 없는데, 사용자는 자기가 클립보드에 복사한 것과 디스크 코멘트가 달라지는 걸 인식하지 못함.

특히 Copy Prompt 의 race 는 **현재 어떤 가드도 없다**:
1. 사용자가 Copy Prompt → 클립보드에 옛 코멘트 박힘
2. 사용자가 코멘트 수정 → 디스크 갱신, 클립보드는 그대로
3. 에이전트에 클립보드 붙여넣기 → 에이전트가 옛 코멘트로 작업
4. 콘솔에서는 새 코멘트 보임 — silent mismatch.

### 1.3 ITEM_NOT_EDITABLE 의 navigation 차단

이전 follow-up 에서 부분 fix 했지만(rendering.js back-saved-annotations 핸들러), set-status / set-severity / textarea blur 핸들러는 여전히 SENT 아이템에 대해 PATCH 를 발사하고 거부 시 화면에 에러를 띄움. 사용자가 "왜 클릭은 되는데 변경이 안 돼?" 라는 혼란.

## 2. Goals

- 어노테이션 라이프사이클의 4단계 (`Draft / Sent / In Progress / Resolved`) 를 라벨/배지/CSS로 명확히 시각화한다.
- 편집 정책을 lifecycle 의 의미에 맞춰 분리한다:
  - `Draft`, `Sent` 단계는 편집 허용 (사용자 수정 자유)
  - `In Progress`, `Resolved` 단계는 lock (race 명백)
- "이미 보낸 후 수정" 시나리오에 명시적 시그널을 부여 — 사용자가 클릭 한 번으로 재전송할 수 있도록 한다 (Save 한 후 수정한 경우 "Re-save", Copy 한 후 수정한 경우 "Re-copy" 버튼).
- 에이전트도 "이건 사용자가 보낸 후 수정한 항목" 이라는 시그널을 받을 수 있어야 한다 (`fixthis_list_feedback` / `fixthis_read_feedback` 응답).
- `agentSummary` (RESOLVED 후 에이전트가 남긴 메모) 를 콘솔 detail 화면에 표시한다.

## 3. Non-goals

- 어노테이션의 새 lifecycle 단계 추가 (예: "in review", "blocked"). 4단계로 충분.
- 에이전트와 사용자 간 실시간 충돌 방지 (예: optimistic concurrency token, ETag-based PATCH). 현재 in-memory store + file persistence 모델로는 너무 큼. 사용자 nudge 만으로 race 위험을 알린다.
- 클립보드를 사용자가 실제로 어디 붙여넣었는지 추적. Copy 만 트래킹.
- DRAFT 아이템에 대한 새 편집 제한.
- 자동 conflict resolution (사용자 수정 vs 에이전트 작업 결과). 단지 "확인해야 한다" 는 시그널만 노출.
- bridge protocol / persisted JSON field 기존 이름 변경.

## 4. Architecture

### 4.1 Lifecycle 단계 정의

| Phase | `delivery` | `status` | UI 라벨 | UI 색 | 편집 가능? | 안내 메시지 |
|-------|-----------|---------|---------|-------|-----------|------------|
| **Draft** | `draft` | (any) | `Draft` | 회색 outlined | ✅ | (없음) |
| **Sent** | `sent` | `ready` | `Sent · <relative time>` | 노랑/주황 | ✅ | "Sent to MCP. Modify to refine before agent picks up." |
| **Sent (modified)** | `sent` | `ready` (& staleAfterHandoff) | `Sent · Modified` | 노랑 + ⚠ | ✅ | "Modified after Save — agent will see the previous version. Re-save to update." |
| **In Progress** | `sent` | `in_progress` | `In Progress` 🔒 | 파랑 + 자물쇠 | ❌ | "🔒 Agent working — edits locked." |
| **Resolved** | `sent` | `resolved` | `Resolved` ✓ | 초록 + 체크 | ❌ | "✓ Agent completed: <agentSummary>" |

`Sent` 의 두 sub-state (modified vs not) 는 같은 `delivery` + `status` 값이지만 새 boolean 필드로 구분 (§4.3 참고).

### 4.2 새 영속 필드: `lastHandedOffAtEpochMillis`

`AnnotationDto` 에 추가:

```kotlin
@Serializable
data class AnnotationDto(
    ...
    val sentAtEpochMillis: Long? = null,            // 기존: 첫 DRAFT→SENT 시점, 이후 변경 안 됨
    val lastHandedOffAtEpochMillis: Long? = null,   // 신규: Save to MCP 또는 Copy Prompt 시마다 갱신
    ...
)
```

**갱신 트리거 (둘 다 설정):**
- `POST /api/agent-handoffs` (Save to MCP) — 응답 받은 itemIds 모두에 대해 갱신.
- `POST /api/sessions/{sid}/items/mark-handed-off` (신규 endpoint) — Copy Prompt 클라이언트가 클립보드 쓰기 성공 후 호출.

**기존 `sentAtEpochMillis` 와의 차이:**
- `sentAtEpochMillis`: 첫 DRAFT → SENT 전이 시점. 이후 영원히 같은 값.
- `lastHandedOffAtEpochMillis`: Save / Copy 매번 갱신. Re-save 후에는 새 timestamp.

기존 필드를 재사용하지 않는 이유는 의미 분리. `sentAt` 은 "처음 큐에 들어간 때" (배치 추적용), `lastHandedOffAt` 은 "사용자가 마지막으로 에이전트에게 보낸 시점" (stale 검출용).

### 4.3 `staleAfterHandoff` 파생 플래그

서버는 영속하지 않고 매 응답 시 계산:

```kotlin
val stale = item.lastHandedOffAtEpochMillis?.let { handedOff ->
    item.updatedAtEpochMillis > handedOff
} ?: false
```

(handedOff 값이 없으면 — Save / Copy 한 적 없음 — 항상 false.)

**노출되는 곳:**
- `/api/sessions`, `/api/session`, `/api/sessions/{sid}` 응답의 각 item 객체에 `staleAfterHandoff: boolean`
- `fixthis_list_feedback` 의 items 배열 각 객체에 `staleAfterHandoff: boolean`
- `fixthis_read_feedback` 의 item 영역에 `staleAfterHandoff: boolean`

**MCP 측 활용 권장 (문서):**
- 에이전트가 `fixthis_claim_feedback` 호출하기 직전 `staleAfterHandoff: true` 면 `fixthis_read_feedback` 으로 현재 코멘트를 다시 읽고 작업 진행.
- 또는 단순히 사용자에게 "이 항목은 보낸 후 수정됐으니 새 prompt 가져와서 작업하시겠어요?" 같은 nudge.

### 4.4 편집 정책 변경

`FeedbackSessionStore.updateAnnotation` (line 320-345) 의 가드:

```diff
- if (item.delivery != FeedbackDelivery.DRAFT) {
-     throw FeedbackSessionException("ITEM_NOT_EDITABLE: Only draft feedback items can be edited: $itemId")
- }
+ val locked = item.delivery == FeedbackDelivery.SENT &&
+     item.status in setOf(AnnotationStatusDto.IN_PROGRESS, AnnotationStatusDto.RESOLVED)
+ if (locked) {
+     throw FeedbackSessionException("ITEM_NOT_EDITABLE: Agent has claimed this item: $itemId")
+ }
```

`deleteDraftItem` 도 동일 정책 (또는 별도 결정 — 일단 동일하게 락 완화):

```diff
- if (item.delivery != FeedbackDelivery.DRAFT) {
+ val locked = item.delivery == FeedbackDelivery.SENT &&
+     item.status in setOf(AnnotationStatusDto.IN_PROGRESS, AnnotationStatusDto.RESOLVED)
+ if (locked) {
```

요약 매트릭스:

| Phase | updateAnnotation | deleteDraftItem |
|-------|------------------|-----------------|
| Draft | ✅ | ✅ |
| Sent (ready) | ✅ | ✅ (단 이미 보낸 건이라 confirm dialog 권장 — UI 측) |
| In Progress | ❌ 409 | ❌ 409 |
| Resolved | ❌ 409 | ❌ 409 |

`updateAnnotation` 이 SENT 아이템에 적용되면 `updatedAtEpochMillis` 가 변경되므로 자동으로 `staleAfterHandoff=true` 가 되며, UI 가 ⚠ 배지를 띄운다.

### 4.5 신규 endpoint `POST /api/sessions/{sid}/items/mark-handed-off`

Copy Prompt 클라이언트가 클립보드 쓰기 성공 후 호출. 응답: 갱신된 SessionDto.

- **Request body:**
  ```json
  { "itemIds": ["<uuid>", ...] }
  ```
- **Server flow:**
  1. itemIds 비어 있으면 400.
  2. 세션 못 찾으면 404.
  3. 매칭되는 item 들의 `lastHandedOffAtEpochMillis` 를 현재 clock 으로 갱신.
  4. 매칭 안 되는 itemIds 는 silently 무시 (이미 GC 됐을 수 있음 — `CompactHandoffRenderer.render` 와 같은 정책).
  5. `updatedAtEpochMillis` 도 같이 갱신 (handed-off 액션 자체도 mutation).
  6. 갱신된 SessionDto 반환.

- **Errors (JSON `{"error":"..."}` shape — 머지된 정책):**
  - 400 — 빈 itemIds 또는 invalid JSON
  - 404 — 알 수 없는 sessionId

- **Auth:** mutation 이므로 X-FixThis-Console-Token 필수 (POST 모두에 동일 정책).

### 4.6 `sendDraftToAgent` 내 `lastHandedOffAt` 갱신

`FeedbackSessionStore.sendDraftToAgent` (line 194-229) 의 `updatedItems.map` 부분:

```diff
  if (item.delivery == FeedbackDelivery.DRAFT && (targetSet == null || item.itemId in targetSet)) {
      item.copy(
          delivery = FeedbackDelivery.SENT,
          handoffBatchId = batch.batchId,
          sentAtEpochMillis = now,
+         lastHandedOffAtEpochMillis = now,
          status = AnnotationStatusDto.READY,
          updatedAtEpochMillis = now,
      )
  }
```

기존 SENT 아이템을 다시 Save 하는 "Re-save" 케이스도 지원해야 함:

```diff
+ } else if (item.delivery == FeedbackDelivery.SENT &&
+            item.status == AnnotationStatusDto.READY &&
+            (targetSet == null || item.itemId in targetSet)) {
+     item.copy(
+         lastHandedOffAtEpochMillis = now,
+         updatedAtEpochMillis = now,
+         // 새 batch 에도 포함되도록 handoffBatchId 갱신
+         handoffBatchId = batch.batchId,
+     )
  }
```

즉 Re-save 는:
- 새 handoff batch 가 추가됨 (sequenceNumber 증가)
- 해당 itemId 들이 새 batch 의 itemIds 에 포함
- 각 item 의 `lastHandedOffAt` 과 `handoffBatchId` 갱신
- `delivery`, `sentAt`, `status` 는 변경 안 됨 (이미 SENT/ready 임)

### 4.7 UI 변경

#### 4.7.1 라벨/배지 매핑

`annotations.js` 의 `statusLabel` / `statusClass` 를 phase-aware 로 교체:

```js
function lifecyclePhase(item) {
  if (item.status === 'resolved') return 'resolved';
  if (item.status === 'in_progress' || item.status === 'in-progress') return 'in_progress';
  if (item.delivery === 'sent') {
    return item.staleAfterHandoff ? 'sent_modified' : 'sent';
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

CSS 추가 (`index.html` 의 인라인 style 또는 별도 css):

```css
.st-draft { background: var(--neutral-200); color: var(--text); }
.st-sent { background: var(--amber-200); color: var(--text); }
.st-sent-modified { background: var(--amber-300); border: 1px solid var(--amber-500); }
.st-sent-modified::before { content: '⚠ '; }
.st-in-progress { background: var(--blue-200); color: var(--text); }
.st-in-progress::before { content: '🔒 '; }
.st-resolved { background: var(--green-200); color: var(--text); }
.st-resolved::before { content: '✓ '; }
```

#### 4.7.2 Detail 화면 phase 별 분기

`rendering.js` 의 `renderSavedAnnotationDetail` 안에서:

```js
const phase = lifecyclePhase(item);
const editable = phase === 'draft' || phase === 'sent' || phase === 'sent_modified';
```

각 input/button 에 `editable` 적용:

```js
'<textarea id="annotationCommentInput" class="annotation-textarea"' +
  (editable ? '' : ' disabled') + '>' + escapeHtmlValue(item.comment) + '</textarea>'
```

상태별 안내 배너 (detail 화면 상단):

| Phase | 배너 |
|-------|------|
| `draft` | (없음) |
| `sent` | "Sent to MCP <relative time>. Modify to refine before agent picks up." |
| `sent_modified` | "⚠ Modified after Save — agent will see the previous version. **[Re-save]**" |
| `in_progress` | "🔒 Agent working on this — edits locked. Wait for resolution." |
| `resolved` | "✓ Agent completed: <agentSummary>" |

`Re-save` 버튼은 `sent_modified` 일 때만 표시. 클릭 시 기존 `sendAgentPrompt(itemIds=[currentItem.itemId])` 와 동등한 흐름.

`agentSummary` 는 `resolved` 일 때 별도 read-only field block 으로 표시 (label "Agent summary").

#### 4.7.3 List row 시각

기존 `ann-row` 에 phase 별 색상 띠 추가 (왼쪽 4px border-left):

```css
.ann-row[data-phase="draft"] { border-left: 4px solid var(--neutral-400); }
.ann-row[data-phase="sent"] { border-left: 4px solid var(--amber-500); }
.ann-row[data-phase="sent_modified"] { border-left: 4px solid var(--amber-700); }
.ann-row[data-phase="in_progress"] { border-left: 4px solid var(--blue-500); }
.ann-row[data-phase="resolved"] { border-left: 4px solid var(--green-500); opacity: 0.8; }
```

#### 4.7.4 Copy Prompt 의 lastHandedOffAt 갱신

`prompt.js` 의 `copyPrompt` 함수에서 클립보드 쓰기 성공 후:

```js
async function copyPrompt() {
    ...
    try {
        const itemIds = await persistAndCollectItemIds();
        const markdown = await fetchHandoffPreview(state.session.sessionId, itemIds);
        await copyTextToClipboard(markdown);
        copied = true;
        // NEW: 서버에 mark-handed-off 알리기
        try {
            const updated = await markItemsHandedOff(state.session.sessionId, itemIds);
            state.session = updated;
            renderInspectorRegion();  // 라벨 즉시 갱신
        } catch (markError) {
            // 서버 호출 실패해도 클립보드 복사는 이미 성공 — 조용히 무시
        }
    } finally { ... }
}

async function markItemsHandedOff(sessionId, itemIds) {
    return await requestJson(
        `/api/sessions/${encodeURIComponent(sessionId)}/items/mark-handed-off`,
        {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ itemIds })
        }
    );
}
```

(Save to MCP 흐름 (`sendAgentPrompt`) 은 `/api/agent-handoffs` 서버 응답에 이미 갱신된 SessionDto 가 포함되므로 별도 호출 불필요.)

### 4.8 MCP 도구 응답 변경

`fixthis_list_feedback` items 배열의 각 객체:

```diff
  {
    "itemId": "...",
    "screenId": "...",
    "status": "ready",
+   "delivery": "sent",
+   "staleAfterHandoff": false,
    "comment": "..."
  }
```

`fixthis_read_feedback` 응답의 `item` 객체:

```diff
  {
    ...
    "delivery": "sent",
    "status": "ready",
+   "staleAfterHandoff": false,
+   "lastHandedOffAtEpochMillis": 1778388612522,
    ...
  }
```

(JSON 영속 필드는 서버에 저장된 그대로 추가 노출. 파생 boolean 만 신규.)

## 5. Components

| Layer | File | Change kind |
|-------|------|-------------|
| 도메인 모델 | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt` (또는 sibling) | `AnnotationDto` 에 `lastHandedOffAtEpochMillis` 필드 추가 |
| 영속 + 마이그레이션 | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionPersistence.kt` | 기존 JSON 파일 (필드 없음) 로드 시 null 로 fallback (kotlinx.serialization 의 default value 자동 처리) |
| Store 정책 | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` | (1) `sendDraftToAgent` 가 `lastHandedOffAt` 갱신 + Re-save 케이스 추가, (2) `updateAnnotation` / `deleteDraftItem` 의 lock 정책을 phase-aware 로, (3) `markItemsHandedOff` 신규 메서드 |
| Service | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt` | `markItemsHandedOff` wrapper 추가 |
| HTTP route (신규) | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/MarkHandedOffRoutes.kt` | `POST /api/sessions/{sid}/items/mark-handed-off` |
| HTTP route (수정) | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/SessionRoutes.kt` (또는 응답 직렬화 지점) | 응답에 `staleAfterHandoff` 필드 노출 |
| MCP tools | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt` | `fixthis_list_feedback`, `fixthis_read_feedback` 응답에 `staleAfterHandoff`, `delivery`, `lastHandedOffAtEpochMillis` 노출 |
| 라벨/배지 | `fixthis-mcp/src/main/console/annotations.js` | `lifecyclePhase` / `statusLabel` / `statusClass` refactor |
| Detail 화면 | `fixthis-mcp/src/main/console/rendering.js` | `renderSavedAnnotationDetail` 에 phase-aware disabled + 배너 + Re-save 버튼 + agentSummary 영역 |
| Copy Prompt | `fixthis-mcp/src/main/console/prompt.js` | 클립보드 성공 후 `markItemsHandedOff` 호출 |
| List row | `fixthis-mcp/src/main/console/rendering.js` 또는 인라인 CSS | row 별 phase 색상 띠 |
| 번들 | `fixthis-mcp/src/main/resources/console/app.js` | `node scripts/build-console-assets.mjs` 로 재생성 |
| 테스트 | `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` | `lastHandedOffAt` 갱신, Re-save, lock 정책 case |
| 테스트 | `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` | mark-handed-off endpoint 4 케이스 (200/400/404/auth), staleAfterHandoff 응답 |
| 테스트 | `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/McpProtocolTest.kt` | tool 응답에 새 필드 포함 검증 |
| CHANGELOG | `CHANGELOG.md` | Unreleased 섹션에 사용자 가시 개선 1 bullet |

## 6. Testing strategy

### 6.1 JS test harness

JS 테스트 러너는 여전히 도입 안 함 (parent plan §6.1 와 동일 정책). 핵심 contract 는 Kotlin 통합 테스트로 보장. 다만 H1 manual smoke 가 필수 — 4개 phase 배지 / disabled 동작 / Re-save 흐름 / agentSummary 표시는 사람이 한 번 돌아봐야 한다.

### 6.2 Acceptance tests (Kotlin)

각 테스트는 변경 전 FAIL → 후 PASS.

1. **`updateAnnotation` allows SENT(ready) edits** — DRAFT 한 후 sendDraftToAgent → updateAnnotation(comment="x") 호출. 200 + `comment="x"` + `updatedAt > sentAt` 확인.
2. **`updateAnnotation` rejects IN_PROGRESS edits** — claim 후 updateAnnotation 호출. `FeedbackSessionException("ITEM_NOT_EDITABLE: ...")`.
3. **`updateAnnotation` rejects RESOLVED edits** — resolve 후 동일.
4. **`sendDraftToAgent` sets `lastHandedOffAt`** — 새 DRAFT → sendDraftToAgent → 해당 item 의 `lastHandedOffAtEpochMillis == sentAtEpochMillis`.
5. **`sendDraftToAgent` updates `lastHandedOffAt` on re-save** — 같은 item 을 두 번 sendDraftToAgent (두 번째 호출은 `targetItemIds=[itemId]`). 두 번째 후의 `lastHandedOffAt > sentAt`. handoffBatches.size == 2.
6. **`staleAfterHandoff` true 검출** — sendDraftToAgent → updateAnnotation 으로 코멘트 변경 → API 응답 또는 service 메서드의 `staleAfterHandoff == true`.
7. **`staleAfterHandoff` false after re-save** — 6번 상황에서 다시 sendDraftToAgent → `staleAfterHandoff == false`.
8. **`markItemsHandedOff` 갱신** — DRAFT 인 item 에 markItemsHandedOff 호출. `lastHandedOffAtEpochMillis` 갱신 확인. `delivery` 는 `draft` 로 유지.
9. **`/api/sessions/{sid}/items/mark-handed-off` 200 happy path** — 위 store-level 테스트와 동일 흐름을 HTTP 로.
10. **`/api/sessions/{sid}/items/mark-handed-off` 400 빈 itemIds** — JSON `{"error":"..."}` 본문.
11. **`/api/sessions/{sid}/items/mark-handed-off` 404 unknown session**.
12. **`/api/sessions/{sid}/items/mark-handed-off` 403 missing token** (mutation guard).
13. **`fixthis_list_feedback` 응답에 staleAfterHandoff 포함** — items 배열의 각 원소 검증.
14. **`fixthis_read_feedback` 응답에 staleAfterHandoff + lastHandedOffAtEpochMillis 포함**.
15. **JSON 마이그레이션** — `lastHandedOffAtEpochMillis` 필드 없는 v0 session.json 을 로드해도 정상 동작 (기본값 null).

### 6.3 Manual verification

H1 manual smoke checklist:

- [ ] DRAFT 어노테이션 작성 → list row 회색 띠, 라벨 "Draft", 모든 input 활성.
- [ ] Save to MCP → row 노랑 띠, 라벨 "Sent", input 활성, 배너 "Sent to MCP …".
- [ ] 코멘트 수정 → row 진한 노랑 띠 + ⚠, 라벨 "Sent · Modified", 배너 "Modified after Save — Re-save" 버튼 표시.
- [ ] **Re-save** 클릭 → 새 handoff batch 생성, 라벨 "Sent" 로 되돌아옴. devtools Network: `/api/agent-handoffs` 200, `staleAfterHandoff: false`.
- [ ] Copy Prompt → 라벨 "Sent" 갱신 (handed-off timestamp). devtools Network: `/api/sessions/.../items/mark-handed-off` 200.
- [ ] Copy Prompt 후 코멘트 수정 → 라벨 "Sent · Modified".
- [ ] `fixthis_claim_feedback` → row 파랑 띠 + 🔒, 라벨 "In Progress", input/button 모두 disabled, 배너 "🔒 Agent working …".
- [ ] In Progress 상태에서 코멘트 textarea 클릭 → cursor 깜빡임 없음 (disabled 작동).
- [ ] In Progress 상태에서 "← All annotations" 클릭 → 즉시 navigation (PATCH 시도 안 함).
- [ ] `fixthis_resolve_feedback` (with summary) → row 초록 띠 + ✓ + 살짝 흐릿, 라벨 "Resolved", agentSummary 별도 read-only field 로 표시.
- [ ] Resolved 에서 "← All annotations" 클릭 → 즉시 navigation.

## 7. Compatibility

| Surface | 영향 | 처리 |
|---------|------|------|
| 영속 JSON `session.json` | 신규 필드 `lastHandedOffAtEpochMillis` 추가. 기존 파일에는 없음 | kotlinx.serialization default value=null. 마이그레이션 코드 없이 forward/backward compatible. |
| `/api/sessions`, `/api/session`, `/api/sessions/{sid}` 응답 shape | items 객체에 `staleAfterHandoff` 추가 | additive — 기존 클라이언트는 무시. |
| `/api/agent-handoffs` 응답 shape | `session.items[*].staleAfterHandoff` 추가 (`session` 도 기존 필드는 그대로) | additive. |
| 신규 `/api/sessions/{sid}/items/mark-handed-off` | 새 endpoint | 기존 route 충돌 없음 (path prefix `/api/sessions/{sid}/items/` 는 신규). |
| 정책 변경 — SENT(ready) 편집 허용 | 기존 거부 → 허용 | 사용자 가시 향상. 기존 테스트가 "이 시점에서 거부" 를 assert 했다면 업데이트 필요 (§5 Tests 항목). |
| MCP 도구 (`fixthis_list_feedback`, `fixthis_read_feedback`) 응답 | `staleAfterHandoff`, `delivery`, `lastHandedOffAtEpochMillis` 추가 | additive. 외부 에이전트 prompt 가 strict shape 검사 한다면 영향 가능 — 알려진 외부 의존 없음. |
| Bridge protocol | 변경 없음 | — |
| DRAFT/SENT/IN_PROGRESS/RESOLVED 의미 | 변경 없음 — 단지 UI 표현이 4단계로 분리됐을 뿐 | — |

## 8. Risks introduced

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Re-save 흐름이 새 handoff batch 를 만들면 batch 수가 많아짐 (1 어노테이션이 N 번 Re-save 시 N batch) | medium | low (스토리지 부담 무시할 수준, UI 는 batches 수 무관) | 별도 GC plan 없음 — 향후 수십 batch 누적 시 `handoffBatches` 응답 페이징 (out of scope) |
| `staleAfterHandoff` 가 `updatedAt > lastHandedOffAt` 비교만으로 판정되므로 다른 mutation (status 변경 등) 도 stale 트리거 | medium | low (사용자가 status 만 바꾸고 코멘트는 그대로면 prompt 내용은 같음에도 stale 표시 — false positive) | 첫 버전은 단순 비교로. 정확도 개선 (예: only 코멘트/severity/label 변경만 stale) 은 future work. |
| Copy Prompt 의 mark-handed-off 호출이 클립보드 쓰기 성공 후 실패해도 사용자에게 표시 안 함 | low | low (클립보드는 성공, 단지 stale 검출이 안 되는 정도) | catch 블록에서 silent ignore. devtools 에는 Network 에러로 보임. |
| 기존 SENT (ready) 인데 `lastHandedOffAtEpochMillis` 가 없는 (마이그레이션 시점) 아이템은 첫 비교에서 stale=false (기본값) — 사용자가 코멘트 변경해도 ⚠ 안 뜸 | medium | low (transient — 다음 Save/Copy 시점부터 정상) | Phase 0 마이그레이션 단계: 모든 SENT 아이템의 `lastHandedOffAtEpochMillis` 를 `sentAtEpochMillis` 로 backfill (1회). 또는 단순히 첫 회는 무시 — 알려진 한계로 문서화. |
| In Progress / Resolved 어노테이션의 코멘트 textarea 가 disabled 인데 사용자가 마우스로 텍스트 select 해서 복사하려고 할 때 — disabled 는 select 도 막음 | low | low | textarea 대신 read-only `<div>` 로 표시 (style 만 textarea 같게). 또는 readonly 속성 사용 (편집은 막지만 select 는 가능). |
| `agentSummary` 가 매우 긴 경우 detail 화면 깨짐 | low | low | `<pre style="white-space:pre-wrap; max-height: 240px; overflow-y: auto">` |

## 9. Future work (intentionally deferred)

- **Stale 정확도 개선**: 코멘트만 변경 시에만 stale 처리. 현재는 mass mutation (status 변경 포함) 모두 stale 트리거.
- **`handoffBatches` 페이징**: 100+ Re-save 시 응답 크기 부담.
- **에이전트 측 자동 re-read on stale**: `fixthis_claim_feedback` 가 `staleAfterHandoff=true` 면 자동으로 최신 prompt 반환하는 옵션. 현재는 에이전트가 직접 결정.
- **Cross-process cache invalidation (H8)**: console 서버가 MCP binary 의 변경을 즉시 감지 못하는 별도 이슈. 본 spec 의 변경은 모두 console 서버 자체에서 발생하므로 이 spec 범위 안에서는 영향 없음. 단 외부 MCP 호출로 status 변경이 일어날 때는 여전히 콘솔 재시작 필요 — 별도 plan.
- **Confirm dialog on SENT delete**: SENT 아이템 삭제 시 "이미 보낸 항목입니다 — 삭제하시겠어요?" 모달 (UI-only, server 정책은 본 spec 의 phase-aware lock 으로 충분).
- **Re-copy 버튼**: Copy Prompt 후 코멘트 수정 시 detail 화면에서 "Re-copy" 버튼 표시 (Re-save 와 대칭). 현재는 사용자가 수동으로 Copy Prompt 다시 누르면 됨.
- **Diff 표시**: `staleAfterHandoff=true` 인 아이템에 대해 "에이전트가 본 prompt vs 현재" diff 를 콘솔에서 보여줌.

## 10. Open questions

1. **SENT(ready) 의 delete 도 허용할까?** — 현재 spec 은 허용 (§4.4). 그러나 "보낸 후 삭제" 는 사용자 실수 가능성이 있고, 에이전트 측에서는 사라진 항목을 어떻게 처리하는지 (현재 `fixthis_read_feedback` 이 unknown item 에러 반환) 도 영향. **결정:** 일단 허용. UI 측에서 "이미 보낸 항목 — 삭제하시겠어요?" confirm dialog 추가는 future work.
2. **`lastHandedOffAt` 을 `sentAtEpochMillis` 와 통합할까?** — 두 필드의 의미가 결국 비슷해 보임. 그러나 `sentAt` 은 첫 SENT 시점 (handoff batch 추적 / sequenceNumber 계산에 사용), `lastHandedOffAt` 은 마지막 사용자 액션 시점 (stale 검출). 의미 분리가 더 깔끔. **결정:** 별도 필드 유지.
3. **Re-save 시 새 handoff batch 를 만들 것인가, 기존 batch 의 markdownSnapshot 만 갱신할 것인가?** — 새 batch 가 더 명확 (히스토리 보존), 기존 batch 갱신은 스토리지 절약. **결정:** 새 batch (히스토리 보존이 디버깅에 유리).
4. **`staleAfterHandoff` 를 영속화할까 매번 계산할까?** — 영속화하면 race 위험 (mutation 시 잊고 갱신 안 함). 매번 계산은 O(1). **결정:** 매번 계산 (영속하지 않음).
5. **`mark-handed-off` endpoint 의 `lastHandedOffAt` 갱신만 있고 다른 mutation 없는데, 이걸 mutation 으로 간주해서 X-FixThis-Console-Token 가드를 적용하나?** — 현재 코드는 mutation method (POST/PUT/PATCH/DELETE) 모두 가드. **결정:** 일관성 유지 — POST 이므로 가드 적용. JS 측은 `requestJson` 사용해서 토큰 자동 부착.
