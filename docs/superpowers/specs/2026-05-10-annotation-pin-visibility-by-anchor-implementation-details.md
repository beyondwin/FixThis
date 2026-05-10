# Annotation Pin Visibility by Anchor — Implementation Details

**Date:** 2026-05-10
**Status:** Implementation reference (companion to `plans/2026-05-10-annotation-pin-visibility-by-anchor.md`)
**Primary modules:** `fixthis-mcp/src/main/console/preview.js`, `fixthis-mcp/src/main/console/rendering.js`, `fixthis-mcp/src/main/resources/console/app.js`, `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

This document is the deep technical companion to the plan. It exists to answer "why each line changes and why nothing else needs to" and to give a future debugger the exact mental model. Read it before editing if you have not previously worked on the console JS bundle.

---

## 1. 콘솔 자산 빌드 시스템 정리

브라우저 콘솔은 `fixthis-mcp/src/main/console/` 의 작은 ES-style 모듈 집합으로 작성되고, 배포 산출물은 단일 파일 `fixthis-mcp/src/main/resources/console/app.js` 다. JS 툴체인(번들러/트랜스파일러) 은 사용하지 않는다 — 번들은 정해진 순서로 모듈 본문을 단순 concatenation 한 결과다.

### 모듈 순서

`scripts/build-console-assets.mjs` 의 `sources` 배열과 `FeedbackConsoleServerTest.generatedConsoleAppMatchesConsoleSourceModules` 가 동일한 순서를 강제한다:

1. `state.js`
2. `api.js`
3. `connection.js`
4. `availability.js`
5. `devices.js`
6. `preview.js`
7. `annotations.js`
8. `history.js`
9. `prompt.js`
10. `rendering.js`
11. `sessions-polling.js`
12. `shortcuts.js`
13. `main.js`

순서를 바꿔서는 안 된다. 본 변경은 6번(preview.js) 에 헬퍼를 더하고 10번(rendering.js) 의 한 블록을 교체하므로, 둘 사이의 선언 순서가 자연히 만족된다 (preview.js 의 `visibleScreenNodeUids` 가 rendering.js 호출보다 먼저 정의됨).

### 번들 포맷

`build-console-assets.mjs` 는 각 모듈을 다음과 같이 emit 한다:

```text
// <filename>\n<file content with trailing whitespace stripped>\n
```

그리고 모듈들 사이를 단일 `\n` 로 join 한다. 결과적으로 두 모듈 사이에 빈 줄 한 줄이 들어가고, 각 모듈은 자신의 파일명 주석으로 시작한다. `FeedbackConsoleServerTest.generatedConsoleAppMatchesConsoleSourceModules` (`fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt:480-507`) 가 byte-equal 비교를 수행하므로:

- 직접 `app.js` 를 손으로 편집하면 안 된다. 항상 `node scripts/build-console-assets.mjs` 결과를 그대로 커밋한다.
- 모듈 마지막 줄에 trailing newline 이 있어도 `trimEnd()` 가 제거한 뒤 단일 `\n` 만 남는다. 즉 source 파일의 trailing whitespace 는 영향을 주지 않는다.
- module 간 단일 빈 줄을 깨면 테스트가 깨진다.

### 캐싱과 dev mode

런타임에 `--console-assets-dir <PATH>` 를 주면 서버가 그 디렉토리에서 직접 HTML/CSS/JS 를 읽어 `Cache-Control: no-store` 로 응답한다(`FeedbackConsoleServerTest.servesConsoleAssetsFromConfiguredDirectoryWithoutCaching`). 이 모드에서는 source 만 고치고 브라우저를 hard reload 하면 즉시 반영된다. 패키징된 JAR 모드에서는 `app.js` 가 리소스로 읽히므로 `:fixthis-mcp:installDist` 재실행이 필요하다. 본 변경의 manual smoke 는 dev mode 를 권장한다.

---

## 2. 데이터 모델 — 어노테이션 target 의 두 형태

서버 와이어 포맷은 `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt:103-112` 의 `AnnotationTargetDto` sealed interface 다:

```kotlin
@Serializable
sealed interface AnnotationTargetDto {
    @Serializable @SerialName("semantics_node")
    data class Node(val nodeUid: String, val boundsInWindow: FixThisRect) : AnnotationTargetDto

    @Serializable @SerialName("visual_area")
    data class Area(val boundsInWindow: FixThisRect) : AnnotationTargetDto
}
```

kotlinx.serialization 의 sealed-interface 직렬화 결과는 `type` discriminator 를 가진 JSON object 다:

```json
{ "type": "semantics_node", "nodeUid": "compose:0:merged:1234", "boundsInWindow": {...} }
{ "type": "visual_area", "boundsInWindow": {...} }
```

브라우저 측은 이를 그대로 받는다. 기존 코드(`annotations.js:11-15`) 도 다음 두 가지 시그널을 모두 본다:

```js
if (target.type === 'semantics_node' || target.nodeUid) { ... node case ... }
if (target.type === 'visual_area' || target.boundsInWindow) { ... area case ... }
```

본 변경의 새 predicate 도 같은 관행을 따라 `target.nodeUid` 의 truthy 여부만 보면 충분하다. node 변형은 nodeUid 를 항상 가지고, area 변형은 결코 가지지 않는다.

### nodeUid 의 형식과 안정성

nodeUid 는 사이드킥의 `SemanticsNodeMapper.uidFor` (`fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/inspect/SemanticsNodeMapper.kt:47-48`) 가 발급한다:

```kotlin
private fun uidFor(rootIndex: Int, treeKind: TreeKind, node: SemanticsNode): String =
    "compose:$rootIndex:${treeKind.name.lowercase()}:${node.id}"
```

- `rootIndex`: ComposeRootInfo 의 인덱스. 같은 Activity 안에서 root 구성이 안정적이면 보존됨.
- `treeKind`: `merged` 또는 `unmerged`.
- `node.id`: Compose runtime 이 SemanticsNode 에 부여하는 정수 id. 동일 컴포지션이 살아있는 동안 안정적. 컴포지션이 dispose 되면 id 는 회수 후 재사용 가능.

본 변경의 핵심 가정은 "같은 화면을 라이브로 다시 캡처해도 nodeUid 가 보존된다" 와 "다른 화면으로 네비게이트하면 컴포지션이 갈리며 nodeUid 가 달라진다" 이다. 일반적인 NavHost 기반 Compose 앱에서 둘 다 성립한다. 예외 가능성:

- 같은 화면 안에서 LazyColumn 의 새 항목이 들어오며 SemanticsNode id 가 일부 재할당될 수 있다 — 그래도 어노테이션이 anchor 한 노드 자체가 dispose 되지 않는 한 그 uid 는 유지된다.
- 빠른 recomposition 으로 잠시 dispose → recreate 되는 노드가 있다면 uid 가 일시적으로 부재할 수 있다. 본 변경은 단순 규칙으로 출발하고, 사용자 보고가 발생하면 implementation 의 "Hysteresis follow-up" 섹션을 참고해 짧은 디바운스를 추가한다.

### 표시 대상 스크린의 노드 트리

각 캡처는 `SnapshotDto` (`SessionDtoModels.kt:37-47`) 형태로 전달되고, 그 안의 `roots: List<SnapshotRootDto>` 가 노드를 갖는다 (`SessionDtoModels.kt:49-55`):

```kotlin
data class SnapshotRootDto(
    val rootIndex: Int,
    val boundsInWindow: FixThisRect,
    val mergedNodes: List<FixThisNode> = emptyList(),
    val unmergedNodes: List<FixThisNode> = emptyList(),
)
```

`FixThisNode.uid` (`fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt:67`) 가 위에서 본 nodeUid 와 동일 형식이다. 본 변경이 도입하는 헬퍼는 이 두 리스트(merged + unmerged) 를 모두 훑어 uid 를 모은다.

---

## 3. 변경 위치와 정확한 코드

### 3.1 `preview.js` — 새 헬퍼

**위치 권장:** `latestPersistedScreen()` (`preview.js:71-83`) 정의 직전에 두면, `latestScreen()` 이 같은 파일에서 즉시 사용 가능하다. 단, 본 변경에서는 `latestScreen()` 자체는 호출하지 않으므로(rendering.js 가 호출), 위치는 의미보다는 가독성 기준이다.

**추가 코드:**

```js
function visibleScreenNodeUids(screen) {
  const uids = new Set();
  if (!screen) return uids;
  (screen.roots || []).forEach(root => {
    (root.mergedNodes || []).forEach(node => { if (node?.uid) uids.add(node.uid); });
    (root.unmergedNodes || []).forEach(node => { if (node?.uid) uids.add(node.uid); });
  });
  return uids;
}
```

**왜 Set 인가:** `Set.has(x)` 가 O(1) 이므로 saved item 수가 N, 한 화면 노드 수가 M 일 때 전체 비용이 O(N+M) 으로 끝난다. Array.includes 를 쓰면 O(N\*M) 으로 폴링당 수만 회 비교가 발생할 수 있다.

**왜 매 호출마다 재계산하는가:** screen 객체는 `mergeSessionIntoState` (`rendering.js:627-656`) 가 통째로 교체할 수 있고, 라이브 프리뷰도 매 폴링에 새 객체를 만든다. WeakMap 캐시를 도입할 수 있으나, 입력 크기가 작고 호출 주기가 1초당 1\~2회이므로 단순 재계산이 충분히 저렴하고 안전하다.

**null safety:** `screen?.roots`, `node?.uid` 를 모두 가드. 빈 Set 이 반환되면 호출자(filter 안에서 `visibleUids.has(nodeUid)`) 가 자동으로 false 를 돌려 핀이 안 그려진다 — 이는 의도된 안전 동작.

### 3.2 `rendering.js` — saved 오버레이 필터 교체

**현재 (line 72-79):**

```js
renderNumberedFeedbackOverlay(overlay, image);
if (!addItemsFlow) {
  const visibleScreen = latestScreen();
  if (visibleScreen?.screenId) {
    const screenSavedItems = savedEvidenceItems().filter(item => item.screenId === visibleScreen.screenId);
    if (screenSavedItems.length) renderSavedEvidenceOverlay(overlay, image, screenSavedItems);
  }
}
```

**새 코드:**

```js
renderNumberedFeedbackOverlay(overlay, image);
if (!addItemsFlow) {
  const visibleScreen = latestScreen();
  if (visibleScreen?.screenId) {
    const visibleUids = visibleScreenNodeUids(visibleScreen);
    const screenSavedItems = savedEvidenceItems().filter(item => {
      const nodeUid = item?.target?.nodeUid;
      if (nodeUid) return visibleUids.has(nodeUid);
      return item.screenId === visibleScreen.screenId;
    });
    if (screenSavedItems.length) renderSavedEvidenceOverlay(overlay, image, screenSavedItems);
  }
}
```

**판정 흐름 (각 case 별로 무엇이 일어나는가):**

| item 타입 | visibleScreen 종류 | 결과 | 근거 |
|---|---|---|---|
| Node-anchored, anchor 가 같은 화면 라이브 캡처에 살아있음 | live preview screen | 표시 | uid set hit |
| Node-anchored, 사용자가 다른 화면으로 네비게이트 | 새 컴포지션 live preview | 비표시 | uid set miss |
| Node-anchored, 같은 화면 다시 돌아옴 | 복원된 컴포지션 live preview | 다시 표시 | uid set hit (Compose id 보존 가정) |
| Node-anchored, focus 클릭 후 그 item 의 원본 화면 표시 중 | persisted screen (그 item 이 anchor 한 그 screen) | 표시 | uid set self-match |
| Node-anchored, focus 클릭 후 다른 item 들이 같은 원본 화면을 공유 | persisted screen | 표시 | uid set self-match |
| Node-anchored, 디바이스 끊김 → persisted fallback | last persisted screen | 그 persisted screen 에 anchor 가 있는 item 만 표시 | 같은 uid 매치 규칙 |
| Area-anchored, 라이브 폴링 중 같은 캡처 | live preview screen (다른 screenId) | 비표시 | nodeUid 없음 → screenId fallback fail |
| Area-anchored, focus 클릭으로 원본 화면 표시 | 그 item 의 persisted screen | 표시 | screenId 일치 |
| Annotate 모드(`addItemsFlow`) 활성 | `addItemsFlow.screen` | saved 오버레이 자체 비활성 | 기존 `if (!addItemsFlow)` 가드 |

**왜 area 는 fallback 인가:** area 변형은 anchor 가 없어 "현재 화면이 area 가 그어진 그 화면과 같은 logical screen 인가?" 를 직접 답할 방법이 없다. 보수적 정책: 원본 캡처(또는 그것을 focus 한 경우) 에서만 표시. 사용자 워크플로 상 area 는 컴포넌트 anchor 가 잡히지 않을 때의 fallback 이며, 기획상 흔하지 않다.

**왜 `item?.target?.nodeUid` 인가:** kotlinx.serialization 으로 직렬화된 wire 데이터에서 nodeUid 는 항상 string 또는 부재. truthy 검사로 충분히 안전. `item.target` 자체가 null 이 될 가능성은 모델 상 없으나 방어적으로 `?.` 을 둔다.

### 3.3 번들 재생성

```bash
node scripts/build-console-assets.mjs
```

스크립트는:

1. `fixthis-mcp/src/main/console/<module>.js` 들을 정해진 순서로 읽음
2. 각 파일 본문 `trimEnd()` 후 `// <name>\n<body>\n` 로 emit
3. 모듈들을 `\n` 로 join
4. 결과를 `fixthis-mcp/src/main/resources/console/app.js` 에 write

`--check` 플래그로 재생성 없이 동기 여부만 검증할 수 있다(CI 게이트로 활용 가능).

---

## 4. 영향 받지 않는 코드 (의도적으로 손대지 않는 곳)

각 항목은 "여기를 건드리고 싶은 충동이 있을 수 있지만 본 fix 에는 불필요" 하다는 표시다.

### 4.1 `latestScreen()` 자체

`preview.js:85-95`. 표시 대상 screen 의 우선순위는 그대로다. focus → addItemsFlow → live → persisted. 본 변경은 "어떤 screen 을 그릴 것인가" 가 아니라 "그려진 screen 위에 어떤 핀을 얹을 것인가" 만 바꾼다.

### 4.2 `mergeSessionIntoState`

`rendering.js:627-656`. 이미 in-flight 코멘트와 focus 상태를 보존하는 fix 가 들어있다. 본 변경은 saved-evidence 핀 가시성만 다루므로 merge 로직과 직교한다.

### 4.3 sessions polling 가드

`sessions-polling.js:3-10`. `isEditingAnnotation()` 가드(별도 fix)는 여전히 textarea 포커스 중 폴링을 건너뛴다. 본 변경의 핀 가시성 규칙은 폴링이 일어났을 때 어떻게 보일지를 다루므로 이 가드와 독립적이다.

### 4.4 `renderSavedEvidenceOverlay` 시그니처

`rendering.js:239-256`. 받은 items 배열을 그대로 그린다. 필터링은 호출자가 하므로 함수 본체는 변경하지 않는다. `boundsForTarget(item.target)` 도 그대로 — node/area 모두 `boundsInWindow` 를 가진다.

### 4.5 numbered feedback overlay (pending 핀)

`renderNumberedFeedbackOverlay` 가 그리는 pending 핀(addItemsFlow 중 만들어지는 임시 핀)은 본 변경 영향 없음. 그것은 `pendingFeedbackItems` 배열에서 직접 그려지고, addItemsFlow 가 활성일 때만 의미를 가진다.

### 4.6 hover preview, drag preview, current selection

`renderSelectionOverlay` 의 마지막 세 분기. 모두 saved 오버레이와 별개의 임시 시각화이므로 영향 없음.

### 4.7 backend / kotlin

새 필드 없음, DTO 변경 없음, persistence 변경 없음. `FixThisTools.kt`, `FeedbackSessionService.kt`, `TargetEvidenceService.kt` 등 모두 손대지 않는다.

---

## 5. 테스트 전략

### 5.1 자동화 (Kotlin)

**`generatedConsoleAppMatchesConsoleSourceModules`** (`FeedbackConsoleServerTest.kt:480-507`): 본 변경의 source 수정과 번들 수정이 동일한 hunk 를 반영하는지 검증한다. 새 테스트는 추가하지 않는다.

JS 측에는 단위 테스트 인프라가 없으므로, JS 로직 자체에 대한 자동화 테스트는 추가하지 않는다 — 이는 본 변경 범위가 좁고(필터 한 줄, 헬퍼 한 함수) 회귀 위험이 manual smoke 로 충분히 커버되기 때문이다.

### 5.2 수동 (브라우저)

플랜의 Task 4 시나리오 S1\~S11 을 모두 확인. 각 시나리오는 한 가지 케이스만 검증하므로 5\~10분이면 통과 여부를 안다. 가장 중요한 두 가지:

- **S1 (같은 화면 라이브 폴링 중 핀 유지)**: 본 fix 의 정의 그 자체. 깜빡임이나 사라짐이 없어야 한다.
- **S2 (다른 화면 네비게이트 시 직전 핀 사라짐)**: 직전 fix 가 보호하던 회귀(다른 화면 핀이 잘못 그려짐) 가 다시 살아나지 않았음을 확인.

S10 (area annotation 다른 화면) 는 의도된 한계 동작 검증이지, "버그가 없다" 는 통과 조건 자체가 다르다.

### 5.3 디버깅 도구

브라우저 devtools 에서 다음을 console 에 입력하면 실시간으로 진단할 수 있다:

```js
// 현재 표시되는 화면의 모든 uid
visibleScreenNodeUids(latestScreen())
// saved 어노테이션 별 anchor uid
savedEvidenceItems().map(i => ({ id: i.itemId, uid: i.target?.nodeUid, screenId: i.screenId }))
// uid 매치 결과
(() => {
  const v = visibleScreenNodeUids(latestScreen());
  return savedEvidenceItems().map(i => ({ id: i.itemId, hit: !!i.target?.nodeUid && v.has(i.target.nodeUid) }));
})()
```

S1 에서 어떤 핀이 사라지면, 위 진단으로 anchor uid 가 실제로 trees 에 있는지 즉시 확인할 수 있다.

---

## 6. Failure modes 와 대응

### F1: S1 에서 핀이 깜빡임

가능 원인:

- Compose recomposition 이 SemanticsNode id 를 재할당. 사이드킥의 `SemanticsNodeMapper.uidFor` 의 입력 자체가 바뀌므로 uid 도 바뀐다.
- 라이브 캡처 도중 잠깐 빈 트리가 들어옴(스크린이 transient 로 비는 순간).

대응:

1. 위 디버깅 도구로 어떤 폴링 틱에서 hit 이 false 가 되는지 식별.
2. 일관되게 false 라면 nodeId 가 실제로 바뀌고 있다는 뜻 — implementation-details 의 "Hysteresis follow-up" 으로 진행.
3. 잠깐만 false 였다가 돌아온다면, hysteresis 한 번이면 해결.

**Hysteresis follow-up (별도 fix 후보):**

`rendering.js` 에 모듈-스코프 캐시를 둔다:

```js
const pinLastSeenAt = new Map(); // itemId -> epochMs
const PIN_HYSTERESIS_MS = 800;
```

필터 안에서 `visibleUids.has(nodeUid)` 가 true 면 `pinLastSeenAt.set(item.itemId, now())` 로 갱신, false 면 `now() - (pinLastSeenAt.get(item.itemId) || 0) <= PIN_HYSTERESIS_MS` 면 여전히 표시. 이 변경은 본 플랜에 포함하지 않는다 — 사용자 보고가 발생하면 별도 plan 으로 추가.

### F2: S2 에서 직전 화면 핀이 새 화면에 잘못 표시

가능 원인:

- node uid 가 다른 화면에 우연히 collision (같은 rootIndex + treeKind + nodeId).
- 사용자가 사실상 같은 화면(예: 단순 dialog 띄움)을 다른 화면으로 오해.

대응:

1. 디버깅 도구로 새 화면의 uid set 과 직전 화면의 anchor uid 를 비교.
2. 실제로 같은 uid 가 두 화면에 모두 있다면, nodeUid 형식만으로는 부족하다는 강한 증거 — backend 에서 screen-level 식별자를 추가하는 별도 plan 이 필요.
3. 우연히 같은 화면 안에 dialog 가 추가된 케이스라면 의도된 동작.

### F3: 어떤 saved 어노테이션도 표시되지 않음

가능 원인:

- `savedEvidenceItems()` 가 빈 배열을 돌려줌 (state.session.items 가 비어 있음).
- visibleScreen 이 null (캡처가 한 번도 없었음).
- 모든 item 의 nodeUid 가 visibleUids 에 없음.

대응:

1. devtools 에서 `savedEvidenceItems().length` 와 `latestScreen()` 을 확인.
2. 둘 다 정상이라면 S6/F1 케이스 — focus 또는 hysteresis 검토.

### F4: `generatedConsoleAppMatchesConsoleSourceModules` 실패

가능 원인:

- source 만 고치고 번들 재생성을 잊음.
- 번들을 손으로 편집함.

대응:

```bash
node scripts/build-console-assets.mjs
git diff fixthis-mcp/src/main/resources/console/app.js
```

차이가 source 변경과 일치하는지 확인 후 다시 테스트.

---

## 7. PR / 커밋 수준 체크리스트

- [ ] `fixthis-mcp/src/main/console/preview.js` 에 `visibleScreenNodeUids` 헬퍼 한 함수 추가만 있음.
- [ ] `fixthis-mcp/src/main/console/rendering.js` 에 `renderSelectionOverlay` 안의 saved 필터 한 블록만 변경.
- [ ] `fixthis-mcp/src/main/resources/console/app.js` 가 두 source 변경과 byte-equal 동기 (스크립트 결과 그대로).
- [ ] `node --check` 가 두 source 와 번들 모두 통과.
- [ ] `./gradlew :fixthis-mcp:test` PASS, 특히 `generatedConsoleAppMatchesConsoleSourceModules` PASS.
- [ ] manual smoke S1\~S11 통과 (S10 은 의도된 한계 확인).
- [ ] CHANGELOG 한 줄 추가.
- [ ] 다른 모듈/Kotlin/persistence 에 변경 없음.
- [ ] `git diff --check` 통과.
