- [ ] Design: Feedback Console UX Issues

**상태**: 제안
**대상 버전**: V1.1
**관련 모듈**: `pointpatch-mcp`

## 배경

피드백 콘솔은 MCP 기반 에이전트 워크플로의 핵심 인터페이스다. 사람이 브라우저에서 피드백을 작성하고 에이전트가 이를 읽어 코드를 수정하는 구조인데, 현재 구현에는 이 두 방향의 연결이 여러 곳에서 끊겨 있다.

---

## 이슈 목록

### 1. 영역 선택 없음

**문제**

`FeedbackConsoleAssets.kt` addItem() 함수가 항상 `bounds: { left: 0, top: 0, right: 100, bottom: 100 }` 를 하드코딩해서 보낸다. 스크린샷 위에서 특정 영역을 가리킬 방법이 없다.

에이전트가 받는 `FeedbackItem.target`은 항상 전체 화면 `Area`가 되고, Markdown 출력의 `Target: area 0,0,100,100`은 아무 정보도 담지 못한다.

**제안**

스크린샷 `<img>` 위에 `<canvas>` overlay를 얹고 마우스 드래그로 사각형을 그린다. 선택 완료 시 표시 픽셀 좌표를 이미지 자연 크기 기준 좌표로 변환해서 bounds에 담는다.

```
선택 픽셀 좌표 → img.naturalWidth / img.clientWidth 비율로 스케일 → FeedbackTarget.Area.boundsInWindow
```

`FeedbackScreenshot.width`, `FeedbackScreenshot.height`가 모델에 이미 있고 `/api/screens/{screenId}/screenshot/full` 엔드포인트도 있으므로 추가 서버 변경 없이 UI 수정만으로 된다.

---

### 2. "에이전트에게 전달" 경로 없음

**문제**

`FeedbackSessionStatus.READY_FOR_AGENT` 상태와 `FeedbackSessionService.markReadyForAgent()` 메서드가 존재하지만, 브라우저 UI에 버튼이 없고 `FeedbackConsoleServer`에 이를 호출하는 endpoint도 없다.

에이전트는 `pointpatch_list_feedback`을 반복 호출해서 상태 변화를 직접 확인하는 수밖에 없으며, 언제 피드백이 준비됐는지 알 방법이 없다.

**제안**

- 서버: `POST /api/ready` endpoint 추가 → `markReadyForAgent()` 호출
- UI: "Send to Agent" 버튼 추가. 클릭 시 `/api/ready` 호출 후 버튼 비활성화
- MCP 툴: `pointpatch_list_feedback` description에 `ready_for_agent` 상태가 될 때까지 기다리라는 명시적 안내 추가

---

### 3. 아이템 삭제/수정 없음

**문제**

잘못 입력한 코멘트나 잘못 선택한 영역을 수정하거나 삭제하는 방법이 없다. `FeedbackSessionStore`에 `deleteItem()`, `updateItemComment()` 메서드 자체가 없다.

**제안**

- 스토어: `deleteItem(sessionId, itemId)`, `updateItemComment(sessionId, itemId, comment)` 추가
- 서비스: 동일하게 위임
- 서버: `DELETE /api/items/{itemId}`, `PATCH /api/items/{itemId}` 추가
- UI: 각 아이템 row에 Edit / Delete 버튼 추가. Edit는 코멘트를 인라인 textarea로 전환해서 수정 후 저장

---

### 4. 에이전트 처리 결과가 브라우저에 표시되지 않음

**문제**

`FeedbackItem`에 `agentSummary`, `status` (`resolved` / `needs_clarification` / `wont_fix`) 필드가 있지만 브라우저 UI는 `agentSummary`를 전혀 표시하지 않는다. 에이전트가 작업을 완료하거나 명확히 해달라는 요청을 남겨도 콘솔을 보는 사람이 알 수 없다.

**제안**

- 아이템 row에 status별 시각 구분 (`resolved` = 초록, `needs_clarification` = 노랑, `wont_fix` = 회색 배경)
- `agentSummary`가 있으면 코멘트 아래 에이전트 답변으로 표시

---

### 5. 브라우저 자동 갱신 없음

**문제**

에이전트가 `pointpatch_resolve_feedback`으로 아이템을 처리해도 브라우저는 수동 Refresh 전까지 변화를 모른다. 에이전트 작업이 끝났는지 사람이 직접 확인해야 한다.

**제안**

두 가지 방식 중 선택:

**A. SSE (Server-Sent Events)** — 서버가 세션 변경 시 이벤트를 push

```
GET /api/events  →  text/event-stream
서버: 세션 상태 변경 시 data: {"type":"session_updated"} 전송
```

장점: 실시간. 단점: `com.sun.net.httpserver.HttpServer` 기반이라 스트리밍 구현이 번거롭다.

**B. 폴링** — 브라우저가 일정 간격으로 `/api/session`을 자동 호출

장점: 구현 단순. 단점: 불필요한 요청 발생.

V1 범위에서는 폴링이 현실적이다. 세션 status가 `ready_for_agent` 이상일 때만 폴링하도록 제한하면 부하를 줄일 수 있다.

---

### 6. 스크린 탐색 없음

**문제**

Screens 패널에 목록이 표시되지만 클릭해도 해당 스크린 스냅샷으로 전환되지 않는다. 항상 마지막으로 캡처한 스크린만 보인다. 여러 화면을 순서대로 캡처한 경우 이전 화면을 다시 볼 수 없다.

**제안**

- `state.selectedScreenId` 추적
- Screens 목록 클릭 시 해당 스크린으로 snapshot 전환
- 해당 스크린에 달린 아이템만 Queue 패널에 필터링 표시

---

### 7. semantics 노드 연결 없음

**문제**

`CapturedScreen.roots`에 semantics 트리 전체가 들어있지만, 콘솔에서 영역을 선택해도 해당 위치의 Compose 노드를 자동으로 연결하지 않는다. 콘솔 플로우로 만든 `FeedbackItem`은 `selectedNode`, `nearbyNodes`, `sourceCandidates`가 항상 비어 있어 에이전트가 어떤 Compose 컴포넌트를 수정해야 하는지 추론해야 한다.

**제안**

영역 선택 완료 시 브라우저에서 `screen.roots`의 semantics 노드와 선택 bounds를 대조해 교집합이 가장 큰 노드를 `selectedNode` 후보로 계산하고, POST body에 포함한다.

```
선택 bounds ∩ node.boundsInWindow → 가장 큰 교집합 노드 = selectedNode
나머지 겹치는 노드 = nearbyNodes
```

서버 `/api/items` endpoint가 `selectedNode`를 받아 `FeedbackSessionService.addAreaFeedback()`에 전달하도록 확장한다. `sourceCandidates`는 source-index 접근이 필요하므로 V1에서는 서버 사이드에서 채우거나 비워두는 것이 현실적이다.

---

## 우선순위

| # | 이슈 | 영향 | 구현 비용 | 우선순위 |
|---|------|------|-----------|----------|
| 2 | 에이전트 전달 경로 없음 | 워크플로 차단 | 낮음 | P0 |
| 3 | 아이템 삭제/수정 없음 | UX 필수 | 낮음 | P0 |
| 1 | 영역 선택 없음 | 피드백 정밀도 | 중간 | P1 |
| 4 | 에이전트 결과 미표시 | 루프 완성 | 낮음 | P1 |
| 5 | 자동 갱신 없음 | UX 편의 | 낮음 | P1 |
| 6 | 스크린 탐색 없음 | UX 편의 | 낮음 | P2 |
| 7 | semantics 연결 없음 | 에이전트 컨텍스트 품질 | 높음 | P2 |

P0 두 가지는 서버 + 스토어 레벨 변경이고, P1의 영역 선택과 자동 갱신/결과 표시는 주로 UI 변경이다. P2의 semantics 연결은 브라우저-서버 간 인터페이스 확장이 필요하다.
